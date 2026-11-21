package com.dbtool.carplay.bridge.developer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **框架层环境快照**（不需要任何权限、不需要 adb 通道）。
 *
 * ## 为什么单独一个类
 *
 * 诊断包里最缺的三类信息，恰恰是**框架直接给、但我们从来没采过**的：
 *
 * 1. **显示体系**：仪表副屏排查（白屏/黑屏）的核心 —— 有哪几块屏、`displayId`、
 *    分辨率、刷新率、`state`（ON/OFF）。`getprop` 与 `logcat` 都读不到这个，
 *    只有 `DisplayManager` 能。
 * 2. **运行时环境**：电量/温度/内存/存储/开机时长 —— 判断"是不是车机状态异常"
 *    而不是"我们的代码有 bug"。
 * 3. **系统设置**：`settings` 表里的亮度/音量/旋转/动画倍率/时区/语言 ——
 *    这些**只影响表现层、不影响功能**，出问题时最容易被漏掉。
 *
 * 这些**全部走框架 API**，不需要 `READ_LOGS` / `DUMP`，所以在应用自己的 uid 下
 * 也拿得到。**放在这里而不是塞进 `getprop` 解析**，是因为 `getprop` 根本没有这些值。
 *
 * ⚠️ 所有读取都 `runCatching` 兜底：车机 ROM 常把某个 API 阉掉或抛异常，
 * 采集**绝不能**因为一格读不到就整份失败。
 */
internal class CarDiagnosticEnvironment(private val context: Context) {

    private val appContext = context.applicationContext

    /** 一整个环境快照，按"人看得懂"的顺序拼成文本。 */
    fun render(): String = buildString {
        appendSection("显示体系（DisplayManager）", displays())
        appendSection("运行环境（内存/存储/电量/温度）", runtime())
        appendSection("系统设置（settings）", systemSettings())
        appendSection("网络（连通性/WiFi）", network())
        appendSection("音频（AudioManager）", audio())
    }

    // ------------------------------------------------------------ 显示体系

    /**
     * **全部显示器**（含主屏）—— 仪表副屏排查的第一手资料。
     *
     * 输出每一块的 `id / name / 类型 / 尺寸 / 密度 / 刷新率 / 状态 / 旋转 /
     * 是否主屏 / 支持的显示模式`。吉利车机上副屏与仪表屏常都叫「HDMI 屏幕」，
     * 所以 `type` 与 `id` 必须一起看（`id` 才是选屏判据）。
     */
    private fun displays(): String = runCatching {
        val manager = appContext.getSystemService(DisplayManager::class.java)
            ?: return@runCatching "# DisplayManager 不可用\n"
        val displays = manager.displays ?: return@runCatching "# displays 为空\n"
        val metrics = DisplayMetrics()
        buildString {
            appendLine("共 ${displays.size} 块显示器")
            for (display in displays) {
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                val typeName = when (display.flags and Display.FLAG_PRESENTATION) {
                    0 -> "内部/默认"
                    else -> "可演示（副屏候选）"
                }
                appendLine(
                    "id=${display.displayId} name=${display.name} 状态=${stateName(display.state)} " +
                        "尺寸=${metrics.widthPixels}x${metrics.heightPixels} " +
                        "密度=${metrics.densityDpi}dpi refresh=${display.refreshRate}Hz " +
                        "旋转=${display.rotation} 类型=$typeName " +
                        "flags=0x${Integer.toHexString(display.flags)} " +
                        "valid=${display.isValid}",
                )
                runCatching {
                    display.supportedModes?.forEach { mode ->
                        appendLine(
                            "    mode ${mode.modeId}: ${mode.physicalWidth}x${mode.physicalHeight}" +
                                "@${mode.refreshRate}Hz",
                        )
                    }
                }
            }
        }
    }.getOrElse { "# 读显示体系失败：${it.javaClass.simpleName}: ${it.message}\n" }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_UNKNOWN -> "UNKNOWN"
        else -> "0x${Integer.toHexString(state)}"
    }

    // ---------------------------------------------------------- 运行环境

    /** 内存 / 存储 / 电量 / 温度 / 开机时长 —— 判断"车机本身是否异常"。 */
    private fun runtime(): String = buildString {
        runCatching {
            val am = appContext.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            if (mi != null) {
                appendLine(
                    "内存：总=${mb(mi.totalMem)} 可用=${mb(mi.availMem)} " +
                        "低内存=${mi.lowMemory} 阈值=${mb(mi.threshold)}",
                )
            }
            val rt = Runtime.getRuntime()
            appendLine(
                "本进程：max=${mb(rt.maxMemory())} 已用=${mb(rt.totalMemory() - rt.freeMemory())} " +
                    "已申请=${mb(rt.totalMemory())}",
            )
        }.onFailure { appendLine("内存：读取失败 ${it.javaClass.simpleName}") }

        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            appendLine("内部存储 /data：总=${mb(total)} 可用=${mb(free)}")
        }.onFailure { appendLine("内部存储：读取失败 ${it.javaClass.simpleName}") }

        runCatching {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                appendLine(
                    "电量：$pct% 状态=${batteryStatus(status)} 供电=${pluggedName(plugged)} " +
                        "温度=${temp / 10.0}℃ 电压=${volt}mV",
                )
            } else {
                appendLine("电量：无广播（车机可能不带电池）")
            }
        }.onFailure { appendLine("电量：读取失败 ${it.javaClass.simpleName}") }

        appendLine("开机时长：${uptime()}")
        appendLine("当前时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine(
            "Java/VM：sdkInt=${Build.VERSION.SDK_INT} ${Build.VERSION.RELEASE} " +
                "fingerprint=${Build.FINGERPRINT}",
        )
        appendLine(
            "Build：MANUFACTURER=${Build.MANUFACTURER} BRAND=${Build.BRAND} " +
                "MODEL=${Build.MODEL} DEVICE=${Build.DEVICE} PRODUCT=${Build.PRODUCT} " +
                "HARDWARE=${Build.HARDWARE} BOARD=${Build.BOARD} " +
                "DISPLAY=${Build.DISPLAY} ID=${Build.ID} TYPE=${Build.TYPE} TAGS=${Build.TAGS}",
        )
        appendLine("CPU ABI：${Build.SUPPORTED_ABIS?.joinToString()}（32位=${Build.SUPPORTED_32_BIT_ABIS?.joinToString()}）")
    }

    private fun uptime(): String = runCatching {
        val ms = SystemClock.elapsedRealtime()
        val d = ms / 86_400_000
        val h = (ms % 86_400_000) / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        "${d}天${h}小时${m}分（elapsedRealtime=${ms}ms）"
    }.getOrElse { "读取失败" }

    private fun batteryStatus(s: Int): String = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "未知"
        else -> "0x${Integer.toHexString(s)}"
    }

    private fun pluggedName(p: Int): String = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
        0 -> "无"
        else -> "0x${Integer.toHexString(p)}"
    }

    // ------------------------------------------------------------ 系统设置

    /**
     * `settings` 三张表里**与表现层有关**的键。
     *
     * ⚠️ 刻意**不采**任何含隐私/账号的键；这里全是"亮度/音量/动画/时区"这类
     * 设备级表现设置。每项都注明来源表（`global`/`secure`/`system`）。
     */
    private fun systemSettings(): String {
        val resolver = appContext.contentResolver
        val pretty = listOf("global" to SETTINGS_GLOBAL, "secure" to SETTINGS_SECURE, "system" to SETTINGS_SYSTEM)
        return buildString {
            for ((table, keys) in pretty) {
                for (key in keys) {
                    val value = runCatching {
                        @Suppress("DEPRECATION")
                        when (table) {
                            "global" -> Settings.Global.getString(resolver, key)
                            "secure" -> Settings.Secure.getString(resolver, key)
                            else -> Settings.System.getString(resolver, key)
                        }
                    }.getOrNull()
                    appendLine("$table.$key = ${value ?: "(null)"}")
                }
            }
        }
    }

    // -------------------------------------------------------------- 网络

    /**
     * 连通性 / 当前网络能力 / WiFi 状态。
     *
     * ⚠️ **不采 `SSID` 与 `BSSID`**：`SSID` 属于敏感信息（用户自建热点名可能含个人信息），
     * 而本工程真正需要的是"走的是哪种传输、通不通"。热点名在
     * `effective_settings.txt` 里已按脱敏口径处理。
     */
    private fun network(): String = buildString {
        runCatching {
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            if (caps == null) {
                appendLine("当前网络：无（activeNetwork=null）")
            } else {
                val transports = buildList {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WiFi")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("蜂窝")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("蓝牙")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("USB")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                }
                appendLine("当前网络：传输=${transports.ifEmpty { listOf("未知") }.joinToString("+")}")
                appendLine(
                    "  能力：联网=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                        "已验证=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                        "不计费=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}",
                )
                appendLine("  下行=${caps.linkDownstreamBandwidthKbps}kbps 上行=${caps.linkUpstreamBandwidthKbps}kbps")
            }
        }.onFailure { appendLine("网络：读取失败 ${it.javaClass.simpleName}") }

        runCatching {
            @Suppress("DEPRECATION")
            val wifi = appContext.getSystemService(WifiManager::class.java)
            appendLine(
                "WiFi：启用=${wifi?.isWifiEnabled} " +
                    "状态=${wifi?.wifiState} " +
                    "IP=${wifi?.connectionInfo?.ipAddress?.let(::formatIp) ?: "(无)"}",
            )
        }.onFailure { appendLine("WiFi：读取失败 ${it.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun formatIp(ip: Int): String =
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"

    // -------------------------------------------------------------- 音频

    private fun audio(): String = runCatching {
        val am = appContext.getSystemService(AudioManager::class.java)
            ?: return@runCatching "# AudioManager 不可用\n"
        buildString {
            appendLine("模式=${am.mode}（0=正常 1=铃声 2=通话 3=通话中）")
            appendLine("音乐音量=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            appendLine("导航音量=${am.getStreamVolume(STREAM_NAVIGATION)}/${am.getStreamMaxVolume(STREAM_NAVIGATION)}")
            appendLine("系统音量=${am.getStreamVolume(AudioManager.STREAM_SYSTEM)}/${am.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)}")
            appendLine("是否静音=${am.isStreamMute(AudioManager.STREAM_MUSIC)} 扬声器=${am.isSpeakerphoneOn}")
        }
    }.getOrElse { "# 读音频失败：${it.javaClass.simpleName}\n" }

    // ------------------------------------------------------------ 工具

    private fun mb(bytes: Long): String = "%.1fMB".format(bytes / 1024.0 / 1024.0)

    private fun StringBuilder.appendSection(title: String, body: String) {
        appendLine("========== $title ==========")
        append(body)
        if (!body.endsWith("\n")) appendLine()
        appendLine()
    }

    private companion object {
        /**
         * `AudioManager.STREAM_NAVIGATION` 是 **API 33** 才有的公开常量；
         * 本车是 Android 9（sdkInt 28），引用它编译不过。其值为 **12**（自 API 21 起固定）。
         */
        const val STREAM_NAVIGATION = 12

        val SETTINGS_GLOBAL = listOf(
            "device_provisioned",
            "airplane_mode_on",
            "stay_on_while_plugged_in",
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        )
        val SETTINGS_SECURE = listOf(
            "user_rotation",
            "screen_brightness",
            "install_non_market_apps",
        )
        val SETTINGS_SYSTEM = listOf(
            "screen_brightness",
            "screen_off_timeout",
            "sound_effects_enabled",
            "time_12_24",
        )
    }
}
