package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.util.Log

/**
 * DBPlay **车机能力探测**（借鉴 DiPlay 的 `hud/BydVehicleCapabilityProbe`）。
 *
 * ## 方法学（从比亚迪那套抽象出来，通用）
 *
 * DiPlay 探测车机私有能力时**不猜**，而是：
 *
 * 1. 定义「能力字段清单」（本题 = Lynk 各服务/接口是否存在）
 * 2. 逐项实测，**记录「谁回了 / 谁失败」**
 * 3. 输出 `ProbeBatch`：`repliedFields` / `failedReads` / `transportReplies`
 * 4. 所有探测**走本机 ADB shell**（`dumpsys` / `getprop` / `cmd`），
 *    必要时用 `app_process /system/bin` + `CLASSPATH` 起 JVM 直调私有接口
 *
 * 关键：**探测结果只记录实测事实，不做假设。** 没回话就是「未验证」，
 * 绝不按车型/分辨率推断。
 *
 * ## 对 DBPlay 的用途
 *
 * 上车后第一条命令应该是「跑一遍探测」，把 Lynk 各服务是否存在摸清楚，
 * 而不是直接盲目启动。探测结果决定走哪条链路（借屏 / ADB / 官方 API）。
 *
 * ⚠️ 未实机验证。
 */
class LynkCapabilityProbe(private val adb: LynkLocalAdb?) {

    companion object {
        private const val TAG = "DBPlay-Probe"

        /** 起 JVM 探私有接口的手法（DiPlay 原串）。 */
        const val APP_PROCESS = "app_process /system/bin"
        const val CLASSPATH_KEY = "CLASSPATH="
    }

    /** 一个待探测的能力。 */
    data class Probe(
        val name: String,
        /** 探测命令（走本机 ADB shell）。 */
        val command: String,
        /** 期望在输出里看到的关键词（空 = 只要命令不报错就算通）。 */
        val expect: String? = null,
    )

    /** 探测结果（对标 DiPlay 的 `ProbeBatch`）。 */
    data class ProbeBatch(
        val capabilities: Map<String, Boolean>,
        val repliedFields: List<String>,
        val failedReads: List<String>,
        val transportReplies: Int,
        val complete: Boolean,
    )

    /** Lynk 相关能力清单。 */
    fun lynkProbes(): List<Probe> = listOf(
        // ① oneOS 服务（音乐元数据 / 借屏 / 方向盘都走它）
        Probe("oneosapi", "dumpsys activity services com.geely.service.oneosapi", "oneosapi"),
        Probe("oneosapi_link", "service list | grep -i oneos", "oneos"),
        // ② DimNaviService（借屏 wire）
        Probe("dimnavi", "service list | grep -i dim", "dim"),
        // ③ 仪表菜单服务
        Probe("dimmenu", "cmd package resolve-activity -c android.intent.category.DEFAULT com.geely.dimmenu", "dimmenu"),
        // ④ 输入服务（方向盘）
        Probe("inputservice", "dumpsys activity services com.geely.inputservice", "inputservice"),
        // ⑤ 车机 Tethering（热点）
        Probe("tethering", "dumpsys connectivity | grep -i tether", "tether"),
        // ⑥ 本机 adbd 是否在（ADB 投屏前提）
        Probe("adbd", "getprop service.adb.tcp.port", null),
        // ⑦ sdk 版本（LynkLocalHotspot 判定用）
        Probe("sdk", "getprop ro.build.version.sdk", "28"),
        // ⑧ 显示设备清单（借屏/ADB 投屏都需要）
        Probe("displays", "dumpsys display | grep -E 'Display #|uniqueId'", "Display #"),
    )

    /** 批量探测，返回完整记录。 */
    fun run(probes: List<Probe> = lynkProbes()): ProbeBatch {
        val caps = mutableMapOf<String, Boolean>()
        val replied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var replies = 0

        for (p in probes) {
            val out = adb?.shell(p.command, 5000)
            val ok = when {
                out == null -> false
                out.isBlank() -> false
                p.expect != null -> out.contains(p.expect)
                else -> true
            }
            caps[p.name] = ok
            if (ok) {
                replied.add(p.name)
                replies++
                Log.d(TAG, "能力 ${p.name}: 有")
            } else {
                failed.add(p.name)
                Log.d(TAG, "能力 ${p.name}: 无（$out）")
            }
        }

        val batch = ProbeBatch(
            capabilities = caps,
            repliedFields = replied,
            failedReads = failed,
            transportReplies = replies,
            complete = failed.isEmpty(),
        )
        Log.i(TAG, "探测完成 replied=${replied.size} failed=${failed.size} complete=${batch.complete}")
        return batch
    }

    /**
     * 生成 `app_process` 启动命令（用于探私有接口，DiPlay 原手法）。
     *
     * @param apkPath 本应用 apk 路径（`context.packageCodePath`）
     * @param mainClass 入口类
     */
    fun appProcessCommand(apkPath: String, mainClass: String, args: String = ""): String =
        "$CLASSPATH_KEY$apkPath $APP_PROCESS $mainClass $args"

    /** 便捷：拿本应用 apk 路径。 */
    fun selfApkPath(context: Context): String = context.packageCodePath

    /** shell 引号（DiPlay 有 `shellQuote`）。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
