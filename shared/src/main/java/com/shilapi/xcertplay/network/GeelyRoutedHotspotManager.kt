package com.shilapi.xcertplay.network

import android.os.Build
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * 车机原厂**路由**热点（`WirelessHotspotMode.GEELY_ROUTED`）。
 *
 * ## 来源（2026-10-25，1:1 对齐参考 APK）
 *
 * 移植自 `GeelyCarPlay-Settings-Refined-debug.apk`（`io.dushu.car` v0.3.2-debug，
 * SHA-256 `4af7f7649b198bc7…`）的
 * `io.dushu.car.network.GeelyRoutedHotspotManager` + `GeelyRoutedHostReadiness` +
 * `GeelyRoutedHotspotManagerKt`。
 *
 * 车主口径：「完全按照反编译的文件执行，我在车上实测可以用」。
 *
 * ## 与 `ManualHotspotManager`（DBPlay 现有 MANUAL 档）的区别
 *
 * | | `ManualHotspotManager` | 本类 |
 * |---|---|---|
 * | 网卡 | 写死 / 打分选 `eth0.11` 等 | **任意 `eth*`**，`eth0` 优先 |
 * | 地址 | 恰好一个 IPv4 | 必须 `198.18.x.x`（严格两字节） |
 * | 凭据 | 反射读 `SoftApConfiguration` | **调用方给定**，从不读系统配置 |
 * | 返回 | `bssid=网卡 MAC`、`channel` 可能非 0、`backend=MANUAL_HOTSPOT` | **`bssid=null`、`channel=0`、`frequencyMHz=null`、`bandLabel="Car hotspot"`、`backend=GEELY_ROUTED_HOTSPOT`** |
 *
 * ## 为什么在 `:shared` 里另写一套判据，而不是复用 `:car:api` 的 `CarGeelyRoutedHost`
 *
 * `:shared` 是上游基础层，`:car:*` 依赖它、**不能反向依赖**。因此：
 * - 判据（`eth` 前缀 / `198.18` 两字节 / `eth0` 优先 / 机型门禁）在本类内**自带一份**；
 * - `:car:api` 的 `CarGeelyRoutedHost` 仍保留（供二开层做设置页校验与单测穷举）；
 * - **两处口径必须同步**：`:car:api` 的 `CarGeelyRoutedHostTest` 与
 *   `:shared` 的 `GeelyRoutedHotspotManagerTest` 都锁同一组边界值，改一处必须改两处。
 *
 * ## 不启动热点
 *
 * 参考包 `start()` 里没有反射系统服务、没有绑定 action、没有
 * `startSoftAp`/`startTetheredHotspot`，Manifest 也没有 `NETWORK_SETTINGS` /
 * `TETHER_PRIVILEGED` / `WRITE_SETTINGS`。本类同样**只找已存在的地址**。
 */
class GeelyRoutedHotspotManager(
    private val ssid: String,
    private val passphrase: String,
    private val security: Iap2WirelessSecurity,
    private val onDiagnostic: (String) -> Unit = {},
    /** 可注入时钟/睡眠，供单测在不睡觉的情况下验证预算。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** 注入点：默认 null ⇒ 用成员 [defaultPause]（可被 close 唤醒）。 */
    pause: ((Long) -> Unit)? = null,
    private val sample: () -> List<Pair<String, InetAddress>> = { readInterfaceAddresses() },
) : WirelessHotspotManager {

    @Volatile
    private var closed = false

    private val waitLock: Any = Object()

    /**
     * 默认暂停：**真正**用 [waitLock] 的 `wait()`，因此 [close] 的 `notifyAll()`
     * 能立刻打断等待（对齐参考包 `start$lambda$8`：
     * `synchronized(waitLock) { if (!closed) waitLock.wait(millis) }`）。
     *
     * 注意：这个 lambda 必须在 [waitLock] 之后声明 —— Kotlin 按声明顺序初始化属性。
     * 单测可注入自己的 `pause` 跳过真睡眠。
     */
    private val defaultPause: (Long) -> Unit = { millis ->
        synchronized(waitLock) {
            if (!closed) (waitLock as java.lang.Object).wait(millis)
        }
    }

    /** 实际使用的暂停函数：注入了就用注入的，否则用 [defaultPause]。 */
    private val pauseFn: (Long) -> Unit = pause ?: defaultPause

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        // 参考包 `ManualHotspotManager.start` 同款断言；本类当前链路都在 worker 线程，
        // 这条只是防止将来有人从 UI 直接调用。
        // JVM 单测里 `android.os.Looper` 是 stub（调用抛 "Stub!"），因此包一层：
        // 拿不到 Looper 就视为"不在主线程"，让纯逻辑测试可以跑。
        val onMainThread = runCatching {
            android.os.Looper.myLooper() != null &&
                android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
        }.getOrDefault(false)
        check(!onMainThread) {
            "GeelyRoutedHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val selection = await(timeoutMillis)
        val iface = selection.first
        val address = selection.second
        onDiagnostic(
            "Car routed hotspot host=${address.hostAddress} interface=$iface candidates=${lastCandidateCount}",
        )
        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            // 与参考包逐字段一致：不声明未知信道、无 BSSID、无频段。
            channel = 0,
            frequencyMHz = null,
            bssid = null,
            interfaceName = iface,
            hostAddress = address,
            bandLabel = "Car hotspot",
            backend = WirelessHotspotBackend.GEELY_ROUTED_HOTSPOT,
        )
    }

    // 注：参考包接口有 `onCarPlayConfirmed()`（空实现），而 DBPlay 上游
    // `WirelessHotspotManager` 接口**没有**这个方法 —— 所以这里不写 override，
    // 行为等价（那边也是空实现，没有可观测效果）。

    override fun close() {
        closed = true
        synchronized(waitLock) { (waitLock as Object).notifyAll() }
    }

    // ------------------------------------------------------------------ 内部

    @Volatile
    private var lastCandidateCount: Int = 0

    /**
     * 有界等待（对齐参考包 `GeelyRoutedHostReadiness.await`）。
     *
     * 判据顺序逐字照抄：**采样 → 判取消 → 判超时 → 判命中 → 暂停**。
     * 暂停用 `waitLock.wait()`（可被 [close] 唤醒），上限 250ms。
     */
    private fun await(timeoutMillis: Long): Pair<String, InetAddress> {
        val started = nowMillis()
        var attempt = 0
        while (!closed) {
            attempt++
            val candidates = sample()
            if (closed) throw IOException("KX11 hotspot startup cancelled")
            val remaining = timeoutMillis - (nowMillis() - started)
            if (remaining <= 0) {
                throw IOException(
                    "KX11 vehicle Ethernet address (198.18.x.x) unavailable after ${timeoutMillis}ms",
                )
            }
            lastCandidateCount = candidates.count { (name, addr) -> isVehicleAddress(name, addr) }
            val picked = selectHost(candidates)
            if (picked != null) {
                // 走 onDiagnostic 而不是 android.util.Log：JVM 单测里 Log 是 stub
                // （"Method i in android.util.Log not mocked"），而 onDiagnostic 是
                // 注入点，生产链路由 CarPlayController 接到 debugLog。
                onDiagnostic(
                    "routed hotspot picked iface=${picked.first} " +
                        "host=${picked.second.hostAddress} attempt=$attempt",
                )
                return picked
            }
            pauseFn(minOf(250L, remaining))
        }
        throw IOException("KX11 hotspot startup cancelled")
    }

    companion object {
        /** 网卡名前缀（参考：`startsWith("eth")`，区分大小写）。 */
        const val INTERFACE_PREFIX = "eth"

        /** 优先网卡。 */
        const val PREFERRED_INTERFACE = "eth0"

        /** 机型门禁用的 SDK。 */
        const val REQUIRED_SDK_INT = 28

        /**
         * 机型门禁，逐字对齐参考包 `usesGeelyRoutedHotspot`。
         *
         * - `sdk == 28` 硬门槛；
         * - KX11 那支**才**看 `device`；
         * - CS11 两支**不看** `device`，且 model 要 `trim`；
         * - **没有**"device 以 cs11 开头即为真"的兜底。
         */
        fun usesRoutedHotspot(buildModel: String?, buildDevice: String?, sdkInt: Int): Boolean {
            if (sdkInt != REQUIRED_SDK_INT) return false
            val model = buildModel.orEmpty()
            val device = buildDevice.orEmpty()
            val kx11 = model.equals("KX11", ignoreCase = true) &&
                device.startsWith("kx11", ignoreCase = true)
            val trimmed = model.trim()
            return kx11 ||
                trimmed.equals("CS11", ignoreCase = true) ||
                trimmed.equals("QUALCOMM CS11", ignoreCase = true) ||
                trimmed.equals("CX11", ignoreCase = true) ||
                trimmed.equals("QUALCOMM CX11", ignoreCase = true)
        }

        /** 当前机型是否该用本类（供控制器选 manager）。 */
        fun isSupportedOnThisDevice(): Boolean =
            usesRoutedHotspot(Build.MODEL, Build.DEVICE, Build.VERSION.SDK_INT)

        /** 地址判据：`Inet4Address` 且 `198.18.x.x`（严格两字节，不是 /15）。 */
        fun isVehicleAddress(interfaceName: String?, address: InetAddress?): Boolean {
            if (interfaceName == null || !interfaceName.startsWith(INTERFACE_PREFIX)) return false
            if (address !is Inet4Address) return false
            val b = address.address
            return b.size == 4 && (b[0].toInt() and 0xFF) == 198 && (b[1].toInt() and 0xFF) == 18
        }

        /**
         * 选一个宿主：`eth0` 优先，其余按接口名字典序；
         * 同一接口多个命中地址时**保留枚举顺序**（`sortedWith` 是稳定排序，与参考一致）。
         */
        fun selectHost(candidates: List<Pair<String, InetAddress>>): Pair<String, InetAddress>? =
            candidates
                .filter { (name, addr) -> isVehicleAddress(name, addr) }
                .sortedWith(
                    compareBy<Pair<String, InetAddress>> { if (it.first == PREFERRED_INTERFACE) 0 else 1 }
                        .thenBy { it.first },
                )
                .firstOrNull()

        /** 读全部网卡地址（`isUp` 且非 loopback，与参考一致）。 */
        internal fun readInterfaceAddresses(): List<Pair<String, InetAddress>> {
            val out = ArrayList<Pair<String, InetAddress>>()
            val all: List<NetworkInterface>? = try {
                NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }
            } catch (error: java.net.SocketException) {
                // 参考包原文：catch SocketException → IOException("Could not inspect ...")
                throw IOException("Could not inspect vehicle network interfaces", error)
            }
            // 参考包对 null 枚举做了兜底（→ 空列表）。平台类型不写这个分支会 NPE。
            if (all == null) return out
            // 参考包把 isUp() && !isLoopback() 包在**同一个** runCatching 里：
            // 只要任一调用抛异常，整张网卡就被判 false（排除）。
            // 分开包会导致 isLoopback() 抛异常时 loopback 默认 false，反而**保留**该网卡。
            for (nic in all) {
                val usable = runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false)
                if (!usable) continue
                val name = runCatching { nic.name }.getOrNull() ?: continue
                val addrs = runCatching { nic.inetAddresses }.getOrNull() ?: continue
                while (addrs.hasMoreElements()) {
                    val a = runCatching { addrs.nextElement() }.getOrNull() ?: continue
                    out.add(name to a)
                }
            }
            return out
        }
    }
}
