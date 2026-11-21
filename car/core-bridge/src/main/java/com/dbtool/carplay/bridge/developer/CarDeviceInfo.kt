package com.dbtool.carplay.bridge.developer

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.dbtool.carplay.api.system.CarSystemAdapter
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * 设备身份与系统信息读取。
 *
 * 这两件事放在一起，是因为它们都是"后台认这台车"所需的字段：
 * 设备号用来定位记录，系统信息用来判断这台车是什么环境。
 */
internal class CarDeviceInfo(
    context: Context,
    private val system: CarSystemAdapter,
    private val prefs: CarDeveloperPrefs,
) {

    private val appContext = context.applicationContext

    // -------------------------------------------------------------- 设备短码

    /** 12 位十六进制短码，与后台 `device_logs.device_id` 的口径一致。 */
    fun deviceId(): String {
        prefs.deviceId?.let { return it }
        val generated = generateDeviceId()
        prefs.deviceId = generated
        return generated
    }

    private fun generateDeviceId(): String {
        val raw = listOf(anchorValue(), Build.MODEL.orEmpty(), Build.MANUFACTURER.orEmpty())
            .joinToString("|")
        return sha256Hex(raw).take(DEVICE_ID_LENGTH)
    }

    /**
     * 稳定锚点。优先级：ANDROID_ID → Build.SERIAL → 随机值。
     *
     * 说明：**不把 VIN 当锚点**。VIN 在本机往往读不到（需要 VHAL / root），
     * 而"先读不到、后读到了"会让锚点中途升级、设备号跟着变——
     * 参考实现正是这么踩的坑。VIN 只作为元数据随上传一起报，不参与主键。
     */
    private fun anchorValue(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        // 9774d56d682e549c 是早期模拟器/山寨机的固定值，不能当唯一标识
        if (!androidId.isNullOrBlank() && androidId != LEGACY_FAKE_ANDROID_ID) {
            return "android:$androidId"
        }
        val serial = runCatching { Build.SERIAL }.getOrNull()
        if (!serial.isNullOrBlank() && !serial.equals("unknown", ignoreCase = true)) {
            return "serial:$serial"
        }
        // 兜底：随机值。写进 prefs 之后同样是终身不变的，
        // 只是清应用数据后会换号——这是可接受的降级。
        return "random:${UUID.randomUUID()}"
    }

    // ------------------------------------------------------------------ VIN

    /**
     * VIN 读取器。
     *
     * ⚠️ 必须**惰性构造**：`CarVinReader` 自身无状态、构造廉价，但把它提到
     * 字段初始化处会让 `CarDeviceInfo` 的每一次 `new` 都多建一个对象——
     * 而本类在 `CarDeveloperMode` / `CarUpdateManager` / `CarVehicleProfileReporter`
     * 里各持一份，构造点在应用启动路径上。
     */
    private val vinReader: CarVinReader by lazy {
        CarVinReader(appContext, object : CarVinReader.Deps {
            override fun systemProperty(key: String): String? = system.systemProperty(key)
            override fun shellFallback(briefBudget: Boolean): String =
                VinShellFallback.read(briefBudget)
        })
    }

    /**
     * 车架号。读不到返回空串（服务端允许 vin 为空）。
     *
     * ## 为什么不再是"只读 5 个系统属性"（2026-10-07 改）
     *
     * 旧实现只读 `ro.product.vin` / `ro.car.vin` / `persist.sys.vin` / `ro.vin` /
     * `gsm.serial` 这 5 个**猜出来**的键。真车机上**一个都不存在**——后台
     * 「DBPlay 车辆」页因此全是空 VIN（实测确认）。VIN 读取真正的难点在于：
     * 需要 car 权限、各 OEM 属性名不同、Android Automotive 各版本 `Car` 构造
     * 签名也不同。这些都由 [CarVinReader] 的降级链覆盖，见那里的说明。
     *
     * ## 保留 [clean] 的位置
     *
     * 合法性判定由 `CarVinReader.isValid` 负责（口径与 DBtool3.0 一致）；
     * 这里只再走一遍 [clean]，是因为 VIN 之外的调用方也可能复用同一套
     * "无意义值"过滤口径（`unknown` / `null` / `-1` …）。
     *
     * @param briefBudget true = 首屏 / 自动上报路径，不跑慢的 `logcat` 兜底。
     */
    fun vin(briefBudget: Boolean = true): String {
        val value = clean(vinReader.read(briefBudget))
        return if (value.isEmpty()) "" else value.uppercase(Locale.ROOT)
    }

    // -------------------------------------------------------------- 系统信息

    fun systemInfo(forceRefresh: Boolean = false): CarDeveloperPrefs.SystemInfo {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            prefs.readSystemInfo(now)?.let { return it }
        }
        val info = readSystemInfo()
        prefs.writeSystemInfo(info, now)
        return info
    }

    private fun readSystemInfo(): CarDeveloperPrefs.SystemInfo {
        val androidVersion = clean(Build.VERSION.RELEASE)
        val deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
            .map(::clean)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return CarDeveloperPrefs.SystemInfo(
            androidVersion = androidVersion,
            systemVersion = readSystemVersion(),
            deviceModel = deviceModel,
        )
    }

    /**
     * 读"用户看到的系统版本"。
     *
     * ⚠️ **绝不能读 `ro.product.flymeVersion`**：真机上它是 `1.9`，
     * 而用户看到的系统版本是 `2.5.x`——这是被服务端明确标记为"已知错误值"
     * （`LEGACY_WRONG_SYSTEM_VERSIONS = ('Flyme 1.9',)`）的字段。
     * 真实来源是**车机系统包的版本名**：
     * `Package: com.flyme.auto.launcher v26060210 (2.5.0.ota3.(cs11a5)…)`。
     */
    private fun readSystemVersion(): String {
        for (pkg in FLYME_SYSTEM_PACKAGES) {
            val text = CarShell.run("dumpsys package $pkg", DUMPSYS_TIMEOUT_MILLIS)
            extractVersionName(text)?.let { return "Flyme $it" }
        }
        // 系统属性兜底。Flyme Auto 上 ro.build.display.id 只是整包构建串，
        // 不是系统版本——服务端也会跳过它，这里保持一致，宁可留空也不报错值。
        val flymeAuto = clean(system.systemProperty("ro.build.flyme_auto").orEmpty())
        val isFlymeAuto = flymeAuto == "1" || flymeAuto.equals("true", ignoreCase = true)
        for (key in SYSTEM_PROP_KEYS) {
            if (key == "ro.build.display.id" && isFlymeAuto) continue
            val value = clean(system.systemProperty(key).orEmpty())
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** 从 `dumpsys package` 输出里取 `versionName=2.5.0…` 的前 2~3 段数字。 */
    private fun extractVersionName(text: String): String? {
        if (text.isEmpty()) return null
        val match = VERSION_NAME_REGEX.find(text) ?: return null
        return match.groupValues[1]
    }

    private fun clean(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        if (text.lowercase(Locale.ROOT) in MEANINGLESS_VALUES) return ""
        return text
    }

    private companion object {
        const val DEVICE_ID_LENGTH = 12
        const val LEGACY_FAKE_ANDROID_ID = "9774d56d682e549c"
        const val DUMPSYS_TIMEOUT_MILLIS = 6_000L

        val MEANINGLESS_VALUES = setOf("unknown", "null", "n/a", "none", "-1", "0")

        /** 车机系统包：版本名就是系统版本。 */
        val FLYME_SYSTEM_PACKAGES = listOf(
            "com.flyme.auto.launcher",
            "com.flyme.auto.systemui",
        )

        /** 只取前 2~3 段数字，与服务端的 `_PKG_VERSION_RE` 口径一致。 */
        val SYSTEM_PROP_KEYS = listOf(
            "ro.build.system.version",
            "ro.system.version",
            "ro.build.display.id",
        )

        val VERSION_NAME_REGEX = Regex("""versionName=(\d{1,2}\.\d{1,2}(?:\.\d{1,2})?)""")

        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
