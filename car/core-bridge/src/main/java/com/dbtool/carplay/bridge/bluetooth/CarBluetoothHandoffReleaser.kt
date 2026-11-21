package com.dbtool.carplay.bridge.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dbtool.carplay.api.settings.CarBluetoothHandoffPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 无线 CarPlay 握手完成时，**真正**把系统蓝牙链路释放掉。
 *
 * ## 为什么需要它（现场证据）
 *
 * iPhone 在 AirPlay 建好之后会下发 `disableBluetooth`（命令体带 `deviceID`
 * = 本次 iPhone 的蓝牙地址），要求车机断开这条蓝牙链路。上游只关了**自己进程**的
 * RFCOMM socket（`CarPlayController.closeBluetoothBootstrapTransport()`），
 * 系统蓝牙与 iPhone 之间的 ACL / profile 连接一点没动。
 *
 * 实车诊断包（设备 `d5ddb4312da1`，2026-10-09 20:51）：
 *
 * - `20:51:27.308` 收到 `disableBluetooth`（`deviceID=1c:8e:2a:49:29:f6`）；
 * - `20:51:27.744` 只关了进程内 socket（`BluetoothSocket CONNECTED -> CLOSED`）；
 * - `20:51:48.106` iPhone 关掉 AirPlay control socket，会话结束（约 21 秒后）；
 * - `20:51:48.351` 又从头重连一次 RFCOMM ⇒ iPhone 认为这次握手失败。
 *
 * ⇒ 本类就是补上"断系统蓝牙"这一半。
 *
 * ## 力度由设置决定
 *
 * 见 [CarBluetoothHandoffPolicy]：`OFF` 不动、`DISCONNECT_LINK` 只断这台设备、
 * `DISABLE_ADAPTER` 关适配器（会话结束再恢复）。
 *
 * - **默认是 [CarBluetoothHandoffPolicy.OFF]**（2026-10-21 车主口径：不能关车机蓝牙，
 *   否则耳机/胎压/车钥匙一起断）。
 * - [CarBluetoothHandoffPolicy.DISCONNECT_LINK] 的 2026-10-21 新实现：主路径改为
 *   特权 `app_process` 独立进程直调 `BluetoothDevice.disconnect()`（见
 *   [BtLinkReleaseCommand] / [BtLinkReleaseTool]）。应用进程内反射只保留为
 *   "某些 ROM 恰好可用"的快路径，**不再取 profile proxy 白等 2×3s**。
 * - 主路径失败时**只记日志、保持连接**；`DISABLE_ADAPTER` 绝不会成为自动回退。
 *
 * ## ⚠️ `DISABLE_ADAPTER` 的恢复有**两条**路径，缺一不可
 *
 * 1. 会话结束（[onSessionEnded]）—— 正常路径；
 * 2. **下次进程启动**（[restoreIfLeftDisabled]）—— 覆盖"会话中途进程被杀"：
 *    静默装自己、OOM、用户强停、车机重启。没有这一条，蓝牙会一直停着，
 *    连带车钥匙/胎压一起断（[CarBluetoothHandoffPrefs] 落盘的就是这个标记）。
 *
 * ## 线程与失败边界
 *
 * - 所有动作丢给**自己的单线程 daemon executor**：`getProfileProxy` 要
 *   `CountDownLatch.await`，绝不能落在主线程或上游的 handoff 线程上。
 * - **任何异常只记日志、绝不外抛**：这是握手收尾的"锦上添花"动作，
 *   失败最多是 iPhone 继续掉线，**不能让它把已经建好的会话搞崩**。
 */
internal class CarBluetoothHandoffReleaser(
    private val context: Context,
    private val log: (String) -> Unit,
    /**
     * 2026-10-21：特权 adb 通道（只读 `peek()` + 已有通道执行命令）。
     *
     * 定向断链的主路径是 `app_process` 独立进程（见 [BtLinkReleaseCommand]）；
     * 没有通道时**只记日志、保持连接**，绝不回退到关闭适配器。
     */
    private val privilegedShell: CarPrivilegedShell? = null,
    /** 本应用 APK 路径（`context.packageCodePath`），用作 `app_process` 的 CLASSPATH。 */
    private val apkPath: String = context.packageCodePath,
) {

    /**
     * ⚠️ **懒创建**：`Executors.newSingleThreadExecutor` 本身不建线程（第一次
     * `execute` 才建），但把它写成 `by lazy` 能让"只想调
     * [restoreIfLeftDisabled] 的一次性实例"（`CarAppServices` 在 App 启动时
     * 就是那么用的）连 executor 对象都不必造。
     */
    private val worker by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dbplay-bt-release").apply { isDaemon = true }
        }
    }

    /** "我们关过适配器、还没恢复"的落盘标记（见类注释第 2 条恢复路径）。 */
    private val prefs = CarBluetoothHandoffPrefs(context)

    /**
     * 握手完成（上游 `CarPlayStatus.WirelessActive`）时调用。
     *
     * @param targetMac `disableBluetooth` 命令里 `deviceID` 的值（大写）。
     *   为 null 时不直接放弃：只有**恰好一台**已连接的 `PHONE_SMART` 时才定向断开，
     *   多台则记日志放弃——宁可不修，也不误断车上其它设备。
     */
    fun onHandoffComplete(policy: CarBluetoothHandoffPolicy, targetMac: String?) {
        when (policy) {
            CarBluetoothHandoffPolicy.OFF -> {
                log("蓝牙释放：策略为「不处理」，保持上游行为（只关自己的 bootstrap socket）")
            }

            CarBluetoothHandoffPolicy.DISCONNECT_LINK -> {
                val mac = targetMac?.takeIf { it.isNotBlank() }
                if (mac == null) {
                    // 2026-10-21：不再"什么都不做"——枚举已配对的 PHONE_SMART，
                    // 只在**恰好一台**处于连接态时才动手，多台则放弃（避免误断其它手机）。
                    log("蓝牙释放：iPhone 的 disableBluetooth 未带 deviceID，尝试按唯一已连接手机定向断开")
                }
                submit("握手完成，定向断开 ${mac ?: "唯一已连接手机"}") { releaseTargetedLink(mac) }
            }

            CarBluetoothHandoffPolicy.DISABLE_ADAPTER -> {
                submit("握手完成，关闭蓝牙适配器") { disableAdapter() }
            }
        }
    }

    /**
     * 新无线会话启动前的恢复屏障。
     *
     * `BluetoothAdapter.enable()` 只表示请求受理，不代表 STATE_ON。旧实现的 onSessionEnded
     * 异步发 enable 后立刻返回；紧接着 reconnect/start 的 upstream 会立刻构造 BluetoothSocket，
     * 实车日志证实会在约 0.6s 内抛 Bluetooth is not enabled。等待只针对本 App 之前
     * 关掉且落盘记录的适配器；用户自己关闭的蓝牙不会被擅自打开。
     * 必须从会话 worker 调用，不能在 UI 主线程等待。
     */
    fun restoreBeforeWirelessStart(timeoutMillis: Long = RESTORE_WAIT_MILLIS): Boolean {
        if (!prefs.adapterDisabledByUs) return true
        val latch = CountDownLatch(1)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        worker.execute {
            try {
                if (prefs.adapterDisabledByUs) restoreAdapter()
                val adapter = adapter()
                ready.set(adapter?.state == BluetoothAdapter.STATE_ON)
                if (!ready.get()) {
                    log("蓝牙释放：新无线会话前等待恢复仍未达 STATE_ON")
                }
            } catch (error: Throwable) {
                log("蓝牙释放：新无线会话前恢复异常（${error.javaClass.simpleName}: ${error.message}）")
            } finally {
                latch.countDown()
            }
        }
        val completed = runCatching { latch.await(timeoutMillis + RESTORE_COMPLETION_MARGIN_MILLIS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed) log("蓝牙释放：新无线会话前恢复等待超时（${timeoutMillis}ms）")
        return completed && ready.get()
    }

    /** 会话结束时调用：只有 `DISABLE_ADAPTER` 档才需要把适配器恢复回去。 */
    fun onSessionEnded(policy: CarBluetoothHandoffPolicy) {
        if (policy != CarBluetoothHandoffPolicy.DISABLE_ADAPTER) return
        // ⚠️ 读**落盘**标记而不是内存字段：两者本可只留一个，但落盘那份还要
        // 覆盖"进程被杀"（见 [restoreIfLeftDisabled]），所以以它为准。
        if (!prefs.adapterDisabledByUs) return
        submit("会话结束，恢复蓝牙适配器") { restoreAdapter() }
    }

    /**
     * 进程启动时调用：上一次我们关了适配器却没来得及恢复 ⇒ 现在补上。
     *
     * ## 为什么必须有它
     *
     * 恢复动作挂在会话事件上，而**进程被杀**这条路径不经过任何会话事件
     * （静默装自己 / OOM / 用户强停 / 车机重启）。没有这一条，蓝牙会一直停着 ——
     * 连带车上的车钥匙、胎压、耳机全断，而用户不知道去哪儿开回来。
     *
     * 幂等：没有遗留标记时直接返回，不会去动用户自己关的蓝牙。
     *
     * ⚠️ 由 `CarAppServices.restoreBluetoothAdapterIfLeftDisabled()` 在 App 启动时调，
     * 动作丢在 worker 上（`enable()` 是 binder 调用，不能落主线程）。
     */
    fun restoreIfLeftDisabled() {
        if (!prefs.adapterDisabledByUs) return
        log("蓝牙适配器：上次进程退出时仍处于「我们关掉的」状态，本次启动补上恢复")
        submit("进程启动，补恢复上次遗留的蓝牙关闭") { restoreAdapter() }
    }

    // ---------------------------------------------------------------- 内部

    private fun submit(reason: String, action: () -> Unit) {
        worker.execute {
            runCatching(action).onFailure {
                log("蓝牙释放失败（$reason）：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /**
     * 定向断链（2026-10-21 新实现）。
     *
     * 顺序（方案 §2.2）：
     * 1. 适配器必须 `STATE_ON`，否则跳过 —— **绝不打开/关闭适配器**；
     * 2. 目标 MAC 为空时，只接受"恰好一台已连接的 PHONE_SMART"，多台放弃；
     * 3. 应用内快路径：只试一次 `BluetoothDevice.disconnect()`（**不取 profile proxy**，
     *    本车实测每个代理等 3s 都超时，纯属白等）；
     * 4. 主路径：`app_process` 独立进程直调（uid 0/2000），校验 `connectedAfter=false`
     *    且 `adapter=ON`；
     * 5. 失败只记日志、保持连接 —— **不回退到 `disableAdapter()`**（车主明确否掉）。
     */
    private fun releaseTargetedLink(targetMac: String?) {
        val adapter = adapter() ?: run {
            log("蓝牙释放：本机没有可用的 BluetoothAdapter")
            return
        }
        if (!hasConnectPermission()) {
            log("蓝牙释放：缺少 BLUETOOTH_CONNECT 权限，跳过")
            return
        }
        if (adapter.state != BluetoothAdapter.STATE_ON) {
            log("蓝牙释放：适配器不在 STATE_ON（state=${adapter.state}），跳过；绝不改适配器")
            return
        }
        val device = resolveTargetDevice(adapter, targetMac)
        if (device == null) {
            log(
                "蓝牙释放：无法唯一确定目标手机" +
                    "（deviceID=${targetMac ?: "未提供"}），跳过定向断开（适配器未动）",
            )
            return
        }
        val address = deviceAddress(device)
        val before = isConnected(device)
        log("蓝牙释放：策略=DISCONNECT_LINK target=$address adapter=ON ACL连接=$before")
        if (!before) {
            log("蓝牙释放：ACL 已断开，无需动作（适配器仍为 ON）")
            return
        }

        // 应用内快路径：只反射一次 disconnect()。预期本车会失败，仅用于日志与
        // "某些 ROM 上公开 API 可用"的快速成功。
        val inProcess = invokeBoolean(device, "disconnect")
        log("蓝牙释放：应用内 BluetoothDevice.disconnect() -> $inProcess")
        Thread.sleep(SETTLE_MILLIS)
        if (!isConnected(device)) {
            log("蓝牙释放完成：$address 已断开（应用内直调成功）；适配器仍为 ON")
            return
        }

        // 主路径：特权 app_process。
        val access = privilegedShell?.peek()
        if (access == null) {
            log("蓝牙释放：无 ADB 通道，跳过定向断开（适配器未动，连接保持）")
            return
        }
        val command = BtLinkReleaseCommand.disconnect(access.prefixOrEmpty, apkPath, address)
        val result = privilegedShell.shell(command, PRIVILEGED_TIMEOUT_MILLIS)
        if (result == null) {
            log("蓝牙释放：特权通道不可用，跳过定向断开（适配器未动，连接保持）")
            return
        }
        log("蓝牙释放：privileged shell exit=${result.exitCode} output=${result.output.trim()}")
        val parsed = BtLinkReleaseResult.parse(result.output)
        if (parsed == null) {
            // 没有 DBPLAY-BT 标记行 = 类加载/启动失败（加固包问题）或命令根本没跑起来。
            log("蓝牙释放：无法解析 DBPLAY-BT 输出，按未知处理（适配器未动，连接保持）")
            return
        }
        when {
            parsed.released -> log(
                "蓝牙释放完成：$address 已断开" +
                    "（privileged uid=${parsed.uid ?: "?"}）；适配器仍为 ON",
            )

            parsed.connectedAfter == true -> log(
                "蓝牙释放未生效：$address 仍处于连接态" +
                    "（result=${parsed.result} exception=${parsed.exception ?: "none"}）；" +
                    "按车主口径保持连接，不回退关闭适配器",
            )

            else -> log(
                "蓝牙释放：结果未知（result=${parsed.result} " +
                    "exception=${parsed.exception ?: "none"}）；保持连接",
            )
        }
        if (parsed.released) {
            // 方案 §1.3：+3s 复查，覆盖厂商 BTDevConnectionPolicy 的自动回连。
            Thread.sleep(POST_RELEASE_RECHECK_MILLIS)
            if (isConnected(device)) {
                log(
                    "蓝牙释放复查：$address 在 ${POST_RELEASE_RECHECK_MILLIS}ms 后重新连上" +
                        "（厂商策略回连）；保持适配器 ON",
                )
            } else {
                log("蓝牙释放复查：$address 仍保持断开（+${POST_RELEASE_RECHECK_MILLIS}ms）；适配器 ON")
            }
        }
    }

    /**
     * 解析目标设备。
     *
     * `targetMac` 为空时才枚举已配对设备，并且只接受**恰好一台**处于连接态的
     * `PHONE_SMART` —— 多台时返回 null（放弃），避免误断车上其它手机。
     */
    private fun resolveTargetDevice(
        adapter: BluetoothAdapter,
        targetMac: String?,
    ): BluetoothDevice? {
        if (!targetMac.isNullOrBlank()) {
            return runCatching { adapter.getRemoteDevice(targetMac.uppercase()) }.getOrNull()
        }
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .filter {
                    it.bluetoothClass?.deviceClass == BluetoothClass.Device.PHONE_SMART &&
                        isConnected(it)
                }
                .singleOrNull()
        }.getOrNull()
    }

    private fun disableAdapter() {
        val adapter = adapter() ?: run {
            log("蓝牙释放：本机没有可用的 BluetoothAdapter")
            return
        }
        val result = invokeBoolean(adapter, "disable")
        // ⚠️ 落盘必须在**动作之后立刻**发生，且**成功才记**：这个标记会在
        // 下次进程启动时被当作"要补一次 enable"的依据，记错了就会去打开一个
        // 我们从未关过的适配器（= 擅自改用户的系统设置）。
        prefs.adapterDisabledByUs = result == true
        log(
            "蓝牙释放：关闭蓝牙适配器 -> $result" +
                if (result == true) "（已落盘，会话结束时恢复；若进程被杀，下次启动也会补恢复）" else "",
        )
    }

    private fun restoreAdapter() {
        val adapter = adapter() ?: run {
            // 拿不到适配器（连 `BluetoothManager` 都没有）⇒ 保持标记不动，
            // 下次启动再试一次。这种车机上"蓝牙关着"本来也无从谈起。
            log("蓝牙释放：本机没有可用的 BluetoothAdapter，无法恢复")
            return
        }
        val result = invokeBoolean(adapter, "enable")
        // enable() 只表示请求受理；不能在这里就让后续会话误以为蓝牙已经可用。
        val stateReady = waitUntilAdapterOn(adapter, RESTORE_WAIT_MILLIS)
        // 只有真实到达 STATE_ON 才清标记；请求已受理但尚在 TURNING_ON 时保留，
        // 让新会话屏障/下次进程启动还能继续等或补恢复。
        prefs.adapterDisabledByUs = !stateReady
        log("蓝牙释放：恢复蓝牙适配器 -> $result；STATE_ON=$stateReady" +
            if (stateReady) "" else "（保留恢复标记）")
    }

    private fun waitUntilAdapterOn(adapter: BluetoothAdapter, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (adapter.state == BluetoothAdapter.STATE_ON) return true
            try {
                Thread.sleep(RESTORE_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return adapter.state == BluetoothAdapter.STATE_ON
            }
        }
        return adapter.state == BluetoothAdapter.STATE_ON
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    /**
     * 无线起栈前等待「适配器开 + 至少 1 个已配对设备」。
     *
     * 只等不拦：超时也返回结果，由调用方决定是否继续（native 会给出真实错误）。
     * 解决「第一次连不上、重进 App 才能连」——`No bonded PHONE_SMART devices found`。
     */
    fun awaitBondedPhoneForWirelessStart(
        // 2026-10-21 方案 §1.5：6s → 3s。iPhone 已配对时立即返回；
        // 6s 只在配对表异常时有意义，3s 足够覆盖蓝牙栈启动窗口。
        timeoutMs: Long = 3_000L,
    ): CarBluetoothReadiness.Result {
        val a = adapter()
        return CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = timeoutMs,
            adapterOn = { a?.state == BluetoothAdapter.STATE_ON },
            bondedCount = { a?.bondedDevices?.size ?: 0 },
        )
    }

    private fun isConnected(device: BluetoothDevice): Boolean = invokeBoolean(device, "isConnected") == true

    /** 反射调用一个无参方法并取布尔结果；任何失败都返回 null（调用方按"没成功"处理）。 */
    private fun invokeBoolean(target: Any, method: String): Boolean? = try {
        val reflected = target.javaClass.getMethod(method)
        reflected.isAccessible = true
        reflected.invoke(target) as? Boolean
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun deviceAddress(device: BluetoothDevice): String =
        runCatching { device.address?.uppercase() }.getOrNull() ?: "?"

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /**
         * 特权 `app_process` 命令的超时。
         *
         * 实测启动 + binder 往返应在数百 ms；4s 是防挂死上限（方案 §1.3）。
         */
        const val PRIVILEGED_TIMEOUT_MILLIS = 4_000L

        /** 释放成功后 +3s 复查（覆盖厂商策略的自动回连）。 */
        const val POST_RELEASE_RECHECK_MILLIS = 3_000L

        /** 新无线会话最多等待蓝牙恢复的时间。 */
        const val RESTORE_WAIT_MILLIS = 5_000L

        /** 适配器状态轮询间隔。 */
        const val RESTORE_POLL_MILLIS = 100L

        /** Worker 内状态轮询结束后给调用线程留出回传时间。 */
        const val RESTORE_COMPLETION_MARGIN_MILLIS = 500L

        /** 断开后等栈落地再复查的间隔。 */
        const val SETTLE_MILLIS = 800L
    }
}
