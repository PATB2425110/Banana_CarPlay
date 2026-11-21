package com.dbtool.carplay.api.hotspot

import com.dbtool.carplay.api.settings.CarHotspotSecurity
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 「车机热点」（`CarHotspotMode.GEELY_ROUTED`）的**纯逻辑层**。
 *
 * ## 来源与口径（2026-10-25，只读核实）
 *
 * 依据参考 APK `GeelyCarPlay-Settings-Refined-debug.apk`（`io.dushu.car` v0.3.2-debug，
 * SHA-256 前 16 位 `4af7f7649b198bc7`）里 `io.dushu.car.network.GeelyRoutedHotspotManager`
 * 与 `GeelyRoutedHostReadiness` 的真实反编译结果，**不是**凭类名推断。
 *
 * 核实到的关键事实（务必别再误读）：
 *
 * 1. **它不启动热点**。`start()` 里没有反射系统服务、没有绑定 action、没有
 *    `startSoftAp` / `startTetheredHotspot`，Manifest 也没有 `NETWORK_SETTINGS` /
 *    `TETHER_PRIVILEGED` / `WRITE_SETTINGS`。它只**枚举网卡找一个已存在的地址**。
 * 2. 它带**有界等待**：`GeelyRoutedHostReadiness.await(timeoutMillis, scan, isClosed,
 *    onProgress)`，即「轮询扫描 + 超时 + 可取消 + 进度回调」。
 * 3. `ssid` / `passphrase` / `security` 三个字段是**要通告给 iPhone 的凭据**
 *    （由用户填写），**不是**用来开热点。
 * 4. 地址判据：接口名 `startsWith("eth")`（区分大小写），且地址是 `Inet4Address`、
 *    第一字节 `198`、第二字节 `18` ⇒ 严格 `198.18.x.x`（/16 网段，不是 /15）。
 * 5. 候选排序：`eth0` 优先，其余按接口名字典序，取第一个。
 *
 * ## 与 [CarTcamHotspot] 的关系
 *
 * 两者都是「复用原厂已开的网」，差别只在判据与凭据来源：
 *
 * | | TCAM（现有） | GEELY_ROUTED（新增） |
 * |---|---|---|
 * | 网卡 | 写死 `eth0.11` | **任意 `eth*`**，`eth0` 优先 |
 * | 地址 | 自动识别，恰好一个 IPv4 | 必须 `198.18.x.x` |
 * | 凭据 | 反射读车机 AP 配置 | **用户手填** |
 *
 * ⇒ **两条链路必须各自独立**，不允许新档被 TCAM 分支静默接管（见
 * `TcamHotspotProfile` 与 `CarHotspotResolver` 的分流）。
 *
 * ## 为什么放在 `:car:api`
 *
 * 与 [CarTcamHotspot] 同理：`:car:api` 无 Robolectric，`NetworkInterface` /
 * `ConnectivityManager` 在 JVM 上读不了；而这些判据判错一次就是「静默失败 + 超时」，
 * 必须能在 JVM 上被单测穷举。需要 Android 框架的部分在
 * `:shared` 的 `GeelyRoutedHotspotManager`（独立上游 manager，不复用 ManualHotspotManager）。
 */
object CarGeelyRoutedHost {

    /** 网卡名前缀（参考包判据：`name.startsWith("eth")`，**区分大小写**）。 */
    const val INTERFACE_PREFIX: String = "eth"

    /** 优先网卡名。参考包：`eth0` 优先，其余按字典序。 */
    const val PREFERRED_INTERFACE: String = "eth0"

    /** 地址第一字节（`198.18.x.x` 的 `198`）。 */
    const val ADDRESS_FIRST_OCTET: Int = 198

    /** 地址第二字节（`198.18.x.x` 的 `18`）。 */
    const val ADDRESS_SECOND_OCTET: Int = 18

    /**
     * 扫地址的**有界预算**（ms）。
     *
     * **60 秒，与参考包一致**（2026-10-25 车主口径：「完全按照反编译的文件执行，
     * 我在车上实测可以用」）。参考包 `CarPlayController.startWirelessHotspot`
     * 对 MANUAL 分支传的就是 `60000`，由 `GeelyRoutedHostReadiness.await` 用它
     * 构造 budget。
     *
     * ⚠️ 我先前设为 5 秒是自作主张的"UX 优化"，已回退：车主实测参考包可用，
     * 说明 60 秒的耐心是这条链路的**真实要求**（车机路由地址可能出现得慢）。
     * 缩短会让"本来能连上的场景"变成超时失败。
     */
    const val SCAN_TIMEOUT_MILLIS: Long = 60_000L

    /** 轮询间隔（ms），沿用 [CarTcamHotspot.POLL_INTERVAL_MILLIS]。 */
    const val POLL_INTERVAL_MILLIS: Long = 200L

    /** 加密网络密码长度下限（WPA/WPA2/WPA3 规范）。 */
    const val PASSPHRASE_MIN_LENGTH: Int = 8

    /** 加密网络密码长度上限。 */
    const val PASSPHRASE_MAX_LENGTH: Int = 63

    // ------------------------------------------------------------------ 网卡判据

    /** 接口名是否属于候选（`eth*`，区分大小写）。 */
    fun isRoutedInterface(interfaceName: String?): Boolean {
        if (interfaceName == null) return false
        return interfaceName.startsWith(INTERFACE_PREFIX)
    }

    /**
     * 地址是否是「吉利原厂路由热点地址」：`Inet4Address` 且 `198.18.x.x`。
     *
     * 逐字节判两个 octet，**不做 `/15` 之类的范围放宽** —— 参考包就是严格两字节，
     * 放宽会把 `198.19.x.x` 也吃进来，那不是这张网。
     */
    fun isRoutedAddress(address: InetAddress?): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        if (bytes.size != 4) return false
        return (bytes[0].toInt() and 0xFF) == ADDRESS_FIRST_OCTET &&
            (bytes[1].toInt() and 0xFF) == ADDRESS_SECOND_OCTET
    }

    /**
     * 在候选里挑一个（参考包 `selectGeelyRoutedHost` 的等价判据）。
     *
     * 入参是 `(接口名, 地址)` 列表，返回 `eth0` 优先、其余按接口名字典序的第一个命中。
     * 同一个接口有多个符合的地址时，取地址字典序最小的一个，保证**结果确定**
     * （参考包取枚举顺序的第一个，枚举顺序在不同 JVM/ROM 上不保证稳定）。
     *
     * @return `null` 表示没有候选
     */
    fun selectHost(candidates: List<Pair<String, InetAddress>>): Pair<String, InetAddress>? {
        val matched = candidates.filter { (name, addr) ->
            isRoutedInterface(name) && isRoutedAddress(addr)
        }
        if (matched.isEmpty()) return null
        // 与参考包一致：`sortedWith` 是**稳定排序**，`eth0` 优先、其余按接口名字典序，
        // 同一接口多个命中地址时**保留原始枚举顺序**（不做地址级 tie-break）。
        // 2026-10-25 车主口径「完全按照反编译执行」，故删掉我先前加的地址字典序。
        return matched.sortedWith(
            compareBy<Pair<String, InetAddress>> { if (it.first == PREFERRED_INTERFACE) 0 else 1 }
                .thenBy { it.first },
        ).first()
    }

    /** 候选里符合判据的条数（诊断用，参考包也把这个数写进日志）。 */
    fun candidateCount(candidates: List<Pair<String, InetAddress>>): Int =
        candidates.count { (name, addr) -> isRoutedInterface(name) && isRoutedAddress(addr) }

    // ------------------------------------------------------------------ 设备门禁

    /**
     * 机型门禁（参考包 `usesGeelyRoutedHotspot` 的等价判据）。
     *
     * 参考包原文（`usesGeelyRoutedHotspot`）：`sdk == 28` 且
     * （`MODEL` KX11 + `DEVICE` `kx11*`）或（`trim(MODEL)` == `CS11`）或
     * （`trim(MODEL)` == `QUALCOMM CS11`）。**CS11 两支不看 DEVICE。**
     *
     * ⚠️ 这是**参考包的口径**，不是 DBPlay 现有 `isEcarxPlatform` 的口径。
     * 实车验证时要打印真实 `Build.MODEL` / `Build.DEVICE` / `SDK_INT` 复核。
     */
    fun usesRoutedHotspot(buildModel: String?, buildDevice: String?, sdkInt: Int): Boolean {
        if (sdkInt != 28) return false
        val model = buildModel.orEmpty()
        val device = buildDevice.orEmpty()
        // 逐字对齐参考包 usesGeelyRoutedHotspot（2026-10-25 二轮核正）：
        // - KX11 那支**才**看 device；
        // - CS11 两支**完全不看 device**，且 model 要 trim；
        // - 有 `QUALCOMM CS11` 这个机型名；
        // - **没有**"device 以 cs11 开头即为真"的兜底（那是上一版我编的，已删）。
        val kx11 = model.equals("KX11", ignoreCase = true) &&
            device.startsWith("kx11", ignoreCase = true)
        val trimmed = model.trim()
        return kx11 ||
            trimmed.equals("CS11", ignoreCase = true) ||
            trimmed.equals("QUALCOMM CS11", ignoreCase = true) ||
            trimmed.equals("CX11", ignoreCase = true) ||
            trimmed.equals("QUALCOMM CX11", ignoreCase = true)
    }

    // ------------------------------------------------------------------ 凭据

    /**
     * 凭据校验。返回 `null` 表示通过，否则返回**面向车主的中文原因**。
     *
     * 规则：
     * - SSID 不得为空、不得含换行；
     * - 开放网络（[CarHotspotSecurity.OPEN]）密码**必须为空**；
     * - 加密网络密码 **8..63** 位，且不得含换行。
     */
    fun validateCredentials(
        ssid: String,
        passphrase: String,
        security: CarHotspotSecurity,
    ): String? {
        val s = ssid.trim()
        if (s.isEmpty()) return "请填写车机热点的名称（SSID）。"
        // 长度/NUL 一律复用 CarHotspotNameRules：IEEE 802.11 的 SSID element 上限是
        // **32 octets（UTF-8 字节）**，不是 32 个字符 —— 11 个汉字 = 33 字节，会被
        // hostapd / SoftApConfiguration 拒掉，而按字符数判会放它过去一路发到 0x5703。
        // 该类已实现「NUL 优先、再判字节数」与中文说明，不再另写一套。
        CarHotspotNameRules.problemOf(s)?.let { problem ->
            return CarHotspotNameRules.describe(problem, s)
        }
        if (security == CarHotspotSecurity.OPEN) {
            return if (passphrase.isEmpty()) null else "开放网络不需要密码，请清空密码。"
        }
        if ('\u0000' in passphrase) return "密码含非法字符（U+0000）。"
        if (passphrase.length < PASSPHRASE_MIN_LENGTH || passphrase.length > PASSPHRASE_MAX_LENGTH) {
            return "加密网络的密码需要 $PASSPHRASE_MIN_LENGTH~$PASSPHRASE_MAX_LENGTH 位。"
        }
        return null
    }

    /** 规范化 SSID（去掉首尾空白；只用于对外比较/通告，不改用户原值）。 */
    fun normalizeSsid(ssid: String): String = ssid.trim()
}

/**
 * 「车机热点」附着成功后的结论。
 *
 * 与 [CarTcamHotspotInfo] 同构，但**类型独立**：一旦两者共用一个类型，
 * 下游任何 `is CarTcamHotspotInfo` 的判断都会把新档当成 TCAM（静默误接管的来源之一）。
 *
 * @property source 诊断标识，恒为 [SOURCE_TAG]，用于日志区分两条链路。
 */
data class CarGeelyRoutedInfo(
    val ssid: String,
    val passphrase: String,
    val security: CarHotspotSecurity,
    val interfaceName: String,
    val hostAddress: Inet4Address,
    val source: String = SOURCE_TAG,
) {
    companion object {
        /** 诊断标识（车主口径：`source=geely-routed`）。 */
        const val SOURCE_TAG: String = "geely-routed"
    }
}

/**
 * 「车机热点」扫地址的阶段结果（纯数据）。
 *
 * 失败时必须能说清**卡在哪一步**（车主要求：不得静默切换到另一种模式）：
 * - [NoInterface]：一个 `eth*` 都没有 —— 大概率车机热点没开
 * - [NoAddress]：有 `eth*`，但没有 `198.18.x.x` —— 热点开在别的网段
 * - [TimedOut]：预算内一直没等到
 */
sealed interface RoutedScanResult {
    data class Found(val interfaceName: String, val address: Inet4Address, val candidateCount: Int) :
        RoutedScanResult

    /** 有 `eth*` 接口，但没有任何一个带 `198.18.x.x`。 */
    data class NoAddress(val interfaces: List<String>) : RoutedScanResult

    /** 连 `eth*` 接口都没有。 */
    data object NoInterface : RoutedScanResult

    /** 预算耗尽。 */
    data class TimedOut(val elapsedMs: Long, val lastInterfaces: List<String>) : RoutedScanResult

    /**
     * 读网卡本身失败（`SocketException` / `SecurityException` 等）。
     *
     * **不能**并进 [NoInterface]：那会把"系统不让读网卡"说成"热点没开"，
     * 用户按提示去开车机热点也不会有用。参考包对此单独抛
     * `IOException("Could not inspect vehicle network interfaces")`。
     */
    data class EnumerationFailed(val reason: String, val elapsedMs: Long) : RoutedScanResult
}
