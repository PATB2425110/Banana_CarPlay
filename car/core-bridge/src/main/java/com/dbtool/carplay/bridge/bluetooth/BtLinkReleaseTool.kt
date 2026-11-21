package com.dbtool.carplay.bridge.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 特权 `app_process` 入口：**只断开指定 MAC 的蓝牙链路**，绝不关闭适配器。
 *
 * ## 为什么是独立进程
 *
 * Android 9 上 `BluetoothDevice.disconnect()` 是 hidden / SystemApi，
 * 应用进程（targetSdk 37）反射调用被 hidden API 限制拦掉（10-11 实车：
 * `disconnect() -> null`，复查仍连接）。本车 `ro.debuggable=1`、
 * `service.adb.root=1`，用 `CLASSPATH=<apk> app_process /system/bin` 起
 * uid 0 的独立 JVM 直调框架接口，是仓库已有手法（PATCH-0072 / PATCH-0081）。
 *
 * ## 硬约束
 *
 * - **绝不调用 `BluetoothAdapter.disable()/enable()`**；适配器必须全程 `STATE_ON`。
 *   适配器不在 ON 时直接 `result=refused` 退出。
 * - 只操作命令行给出的 MAC（或"恰好一台已连接 PHONE_SMART"），不碰其它设备。
 * - `probe` / `status` **只读**，不产生任何写操作。
 *
 * ## 输出协议
 *
 * stdout 混有 JVM 噪声 ⇒ 每次调用打印一条带标记的单行：
 * ```
 * DBPLAY-BT result=disconnected adapter=ON adapterState=12 uid=0 sdk=28 \
 *   connectedBefore=true connectedAfter=false disconnectMethod=ok policyForbidden=false
 * ```
 *
 * 调用方用 [BtLinkReleaseResult.parse] 解析；**没有标记行 = 类加载/启动失败 = 未知**。
 */
object BtLinkReleaseTool {

    private const val TAG = "DBPLAY-BT"
    private const val PROFILE_WAIT_SECONDS = 3L
    private const val SETTLE_MILLIS = 800L
    /**
     * `BluetoothProfile.CONNECTION_POLICY_*` 在编译 SDK 37 的公开桩里已隐藏，
     * 但运行时（Android 9）的值固定为 0 / 100（AOSP `BluetoothProfile`）。
     * 这两个常量只用于 `forbid` / `allow` 动词（默认策略不使用）。
     */
    private const val CURRENT_POLICY_FORBIDDEN = 0
    private const val CURRENT_POLICY_ALLOWED = 100

    @JvmStatic
    fun main(args: Array<String>) {
        // PATCH-0081 的 `Aborted` 根因之一是裸 app_process 没有主 Looper。
        // 必须在 ActivityThread / binder 初始化之前准备。
        runCatching { Looper.prepareMainLooper() }

        val verb = args.getOrNull(0)?.lowercase() ?: "probe"
        val mac = args.getOrNull(1)
        val fields = linkedMapOf<String, Any?>(
            "uid" to runCatching { Process.myUid() }.getOrDefault(-1),
            "sdk" to Build.VERSION.SDK_INT,
        )
        val exit = try {
            dispatch(verb, mac, fields)
        } catch (error: Throwable) {
            fields["exception"] = "${error.javaClass.name}:${error.message ?: ""}"
            emit(fields, result = "error")
            System.err.println(
                "$TAG exception=${sanitize(error.javaClass.name)}:${sanitize(error.message ?: "")}",
            )
            3
        }
        println("$TAG exit=$exit")
        System.out.flush()
        System.exit(exit)
    }

    private fun dispatch(verb: String, mac: String?, fields: MutableMap<String, Any?>): Int {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            fields["adapter"] = "absent"
            emit(fields, result = "error")
            return 2
        }
        val state = runCatching { adapter.state }.getOrDefault(-1)
        val adapterOn = state == BluetoothAdapter.STATE_ON
        fields["adapter"] = if (adapterOn) "ON" else "OFF"
        fields["adapterState"] = state

        // 适配器不在 ON：除只读 probe/status 外一律拒绝。**绝不自动打开/关闭适配器。**
        if (!adapterOn && verb != "probe" && verb != "status") {
            fields["exception"] = "adapter-not-on"
            emit(fields, result = "refused")
            return 2
        }

        return when (verb) {
            "probe" -> probe(adapter, mac, fields)
            "disconnect" -> disconnect(adapter, mac, fields)
            "forbid" -> policy(adapter, mac, fields, forbid = true)
            "allow" -> policy(adapter, mac, fields, forbid = false)
            "status" -> status(adapter, mac, fields)
            else -> {
                fields["exception"] = "unknown-verb"
                emit(fields, result = "unknown-verb")
                2
            }
        }
    }

    // ---------------------------------------------------------------- probes

    /** 只读：适配器 / 配对 / 连接状态 / `disconnect` 方法可获取性 / uid。 */
    private fun probe(
        adapter: BluetoothAdapter,
        mac: String?,
        fields: MutableMap<String, Any?>,
    ): Int {
        val device = resolveDevice(adapter, mac)
        fields["target"] = device?.address ?: "none"
        fields["bonded"] = device?.let {
            runCatching { it.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        }
        fields["connectedBefore"] = device?.let { isConnected(it) }
        if (device == null) {
            fields["disconnectMethod"] = "no-device"
            fields["exception"] = "no-target"
            emit(fields, result = "probe")
            return 1
        }
        val outcome = tryDisconnect(device, invoke = false)
        fields["disconnectMethod"] = outcome.method
        fields["exception"] = outcome.exceptionName ?: "none"
        emit(fields, result = "probe")
        return if (outcome.method == "ok") 0 else 1
    }

    /** 只读：当前连接状态 + 策略（策略读取失败时只记 unknown）。 */
    private fun status(
        adapter: BluetoothAdapter,
        mac: String?,
        fields: MutableMap<String, Any?>,
    ): Int {
        val device = resolveDevice(adapter, mac)
        fields["target"] = device?.address ?: "none"
        fields["bonded"] = device?.let {
            runCatching { it.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        }
        fields["connectedBefore"] = device?.let { isConnected(it) }
        fields["disconnectMethod"] = device?.let { tryDisconnect(it, invoke = false).method }
        emit(fields, result = "status")
        return 0
    }

    // ---------------------------------------------------------------- actions

    private fun disconnect(
        adapter: BluetoothAdapter,
        mac: String?,
        fields: MutableMap<String, Any?>,
    ): Int {
        val device = resolveDevice(adapter, mac)
        if (device == null) {
            fields["target"] = "none"
            fields["exception"] = "no-target"
            emit(fields, result = "failed")
            return 2
        }
        fields["target"] = device.address
        fields["bonded"] = runCatching {
            device.bondState == BluetoothDevice.BOND_BONDED
        }.getOrDefault(false)
        val before = isConnected(device)
        fields["connectedBefore"] = before
        fields["policyForbidden"] = false
        if (!before) {
            fields["connectedAfter"] = false
            fields["disconnectMethod"] = "skipped"
            emit(fields, result = "disconnected")
            return 0
        }

        // ① 直接 BluetoothDevice.disconnect()（hidden API，反射）。
        val outcome = tryDisconnect(device, invoke = true)
        fields["disconnectMethod"] = outcome.method
        // false / null = 服务端拒绝（方法存在、权限也在，但 Binder 返回失败）。
        fields["disconnectResult"] = outcome.value
        if (outcome.exceptionName != null) fields["exception"] = outcome.exceptionName

        // ② 直调失败/返回 false：对 HFP、A2DP 分别断开（每个 proxy 最多等 3s）。
        if (outcome.value != true) {
            disconnectProfiles(adapter, device)
        }

        sleep(SETTLE_MILLIS)
        var after = isConnected(device)
        fields["connectedAfter"] = after

        emit(fields, result = if (!after) "disconnected" else "failed")
        return if (!after) 0 else 1
    }

    /**
     * `forbid` / `allow`：对 HFP/A2DP 设置 `CONNECTION_POLICY_*`。
     *
     * 只影响这两个 profile，**不影响 SPP/iAP2 的 UUID** ⇒ 下次无线 CarPlay
     * 仍能建立 RFCOMM。默认策略不使用本动作（Stage 2 才需要）。
     */
    private fun policy(
        adapter: BluetoothAdapter,
        mac: String?,
        fields: MutableMap<String, Any?>,
        forbid: Boolean,
    ): Int {
        val device = resolveDevice(adapter, mac)
        if (device == null) {
            fields["target"] = "none"
            fields["exception"] = "no-target"
            emit(fields, result = "failed")
            return 2
        }
        fields["target"] = device.address
        fields["connectedBefore"] = isConnected(device)
        val applied = if (forbid) {
            forbidProfiles(adapter, device)
        } else {
            allowProfiles(adapter, device)
        }
        fields["policyForbidden"] = if (forbid) applied else false
        if (forbid && applied) {
            // 策略落地后再补一次 disconnect，让现有 ACL 立即释放。
            tryDisconnect(device, invoke = true)
            sleep(SETTLE_MILLIS)
        }
        fields["connectedAfter"] = isConnected(device)
        emit(fields, result = if (applied) (if (forbid) "forbidden" else "allowed") else "failed")
        return if (applied) 0 else 1
    }

    // ---------------------------------------------------------------- helpers

    private fun resolveDevice(adapter: BluetoothAdapter, mac: String?): BluetoothDevice? {
        if (!mac.isNullOrBlank()) {
            return runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        }
        // 没有 deviceID 时才枚举：只接受**恰好一台**已连接的 PHONE_SMART，
        // 多台就放弃，避免误断车上其它手机。
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .filter {
                    it.bluetoothClass?.deviceClass == BluetoothClass.Device.PHONE_SMART &&
                        isConnected(it)
                }
                .singleOrNull()
        }.getOrNull()
    }

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        val method = device.javaClass.getMethod("isConnected")
        method.isAccessible = true
        method.invoke(device) as? Boolean == true
    }.getOrDefault(false)

    private class DisconnectOutcome(
        val method: String,
        val value: Boolean?,
        val exceptionName: String?,
    )

    /**
     * 反射调用 `BluetoothDevice.disconnect()`。
     *
     * [invoke] = false 时只取方法（`probe` 用，**不产生写操作**）；
     * 方法取不到 / 被 hidden API 拦 / 权限被拒都会落到不同的 [method] 值上，
     * 供上车日志一条看清。
     */
    private fun tryDisconnect(device: BluetoothDevice, invoke: Boolean): DisconnectOutcome = try {
        val method = device.javaClass.getMethod("disconnect")
        method.isAccessible = true
        if (!invoke) {
            DisconnectOutcome("ok", null, null)
        } else {
            DisconnectOutcome("ok", method.invoke(device) as? Boolean, null)
        }
    } catch (error: NoSuchMethodException) {
        // hidden API 黑名单最常见的表现：方法"不存在"。
        DisconnectOutcome("hidden-api-blocked", null, error.javaClass.name)
    } catch (error: SecurityException) {
        // 权限被拒（服务端 / 调用方）。
        DisconnectOutcome("security", null, error.javaClass.name)
    } catch (error: IllegalAccessException) {
        DisconnectOutcome("hidden-api-blocked", null, error.javaClass.name)
    } catch (error: InvocationTargetException) {
        val cause = error.cause ?: error
        val method = if (cause is SecurityException) "security" else "invoke-error"
        DisconnectOutcome(method, null, cause.javaClass.name)
    } catch (error: Throwable) {
        DisconnectOutcome("invoke-error", null, error.javaClass.name)
    }

    private fun disconnectProfiles(adapter: BluetoothAdapter, device: BluetoothDevice): Boolean {
        val context = systemContext() ?: return false
        val headset = withProfile(adapter, context, BluetoothProfile.HEADSET) { proxy ->
            invokeProfileDisconnect(proxy, device)
        }
        val a2dp = withProfile(adapter, context, BluetoothProfile.A2DP) { proxy ->
            invokeProfileDisconnect(proxy, device)
        }
        return headset || a2dp
    }

    private fun forbidProfiles(adapter: BluetoothAdapter, device: BluetoothDevice): Boolean {
        val context = systemContext() ?: return false
        val headset = withProfile(adapter, context, BluetoothProfile.HEADSET) { proxy ->
            invokeConnectionPolicy(proxy, device, CURRENT_POLICY_FORBIDDEN)
        }
        val a2dp = withProfile(adapter, context, BluetoothProfile.A2DP) { proxy ->
            invokeConnectionPolicy(proxy, device, CURRENT_POLICY_FORBIDDEN)
        }
        return headset && a2dp
    }

    private fun allowProfiles(adapter: BluetoothAdapter, device: BluetoothDevice): Boolean {
        val context = systemContext() ?: return false
        val headset = withProfile(adapter, context, BluetoothProfile.HEADSET) { proxy ->
            invokeConnectionPolicy(proxy, device, CURRENT_POLICY_ALLOWED)
        }
        val a2dp = withProfile(adapter, context, BluetoothProfile.A2DP) { proxy ->
            invokeConnectionPolicy(proxy, device, CURRENT_POLICY_ALLOWED)
        }
        return headset && a2dp
    }

    /**
     * `BluetoothHeadset/A2dp.disconnect()` 与 `setConnectionPolicy()` 在
     * 编译 SDK 37 的公开桩里不可见（hidden / SystemApi），只能反射。
     * 本工具以 uid 0/2000 的 `app_process` 运行，服务端权限检查会放行。
     */
    private fun invokeProfileDisconnect(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): Boolean = try {
        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
        method.isAccessible = true
        method.invoke(proxy, device) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    private fun invokeConnectionPolicy(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        policy: Int,
    ): Boolean = try {
        val method = proxy.javaClass.getMethod(
            "setConnectionPolicy",
            BluetoothDevice::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(proxy, device, policy) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    private fun withProfile(
        adapter: BluetoothAdapter,
        context: Context,
        profile: Int,
        block: (BluetoothProfile) -> Boolean,
    ): Boolean {
        val latch = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, p: BluetoothProfile) {
                proxy = p
                latch.countDown()
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!runCatching { adapter.getProfileProxy(context, listener, profile) }
                .getOrDefault(false)
        ) {
            return false
        }
        val acquired = try {
            latch.await(PROFILE_WAIT_SECONDS, TimeUnit.SECONDS)
            proxy
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } ?: return false
        return try {
            block(acquired)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { adapter.closeProfileProxy(profile, acquired) }
        }
    }

    /** `app_process` 没有应用 Context；用 `ActivityThread.systemMain()` 拿系统 Context。 */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val main = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(main) as? Context
    }.getOrNull()

    private fun emit(fields: Map<String, Any?>, result: String) {
        val order = listOf(
            "adapter", "adapterState", "uid", "sdk", "target", "bonded",
            "connectedBefore", "connectedAfter", "disconnectMethod",
            "disconnectResult", "policyForbidden", "exception",
        )
        val line = StringBuilder("$TAG result=").append(sanitize(result))
        for (key in order) {
            val value = fields[key] ?: continue
            line.append(' ').append(key).append('=').append(sanitize(value.toString()))
        }
        println(line)
    }

    /** stdout 单行协议：空白全部替换成 `_`，避免把字段拆坏。 */
    private fun sanitize(value: String): String =
        value.replace(Regex("\\s+"), "_").take(180).ifEmpty { "empty" }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
