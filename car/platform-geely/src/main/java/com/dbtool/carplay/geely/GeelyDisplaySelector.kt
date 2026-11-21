package com.dbtool.carplay.geely

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.view.Display
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.CarResolutionPolicy

/**
 * 一块车机显示屏的**只读快照**。
 *
 * 从 `Display` 抽成纯数据，是为了让 [GeelyDisplaySelector.select] 能在 JVM 上单测——
 * `Display` 在单测里造不出来（需要真实 `DisplayManager`）。
 */
data class GeelyDisplayDescriptor(
    val id: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val isDefault: Boolean,
    /** `Display.getName()`：吉利车机上多块副屏**都叫「HDMI 屏幕」**，故不能单靠它分辨。 */
    val name: String = "",
    /** `Display.getType()`（HDMI / WIFI / INTERNAL …）。 */
    val displayType: Int = 0,
    val xdpi: Float = 0f,
    val ydpi: Float = 0f,
    val refreshRate: Float = 0f,
)

/**
 * 仪表 HDMI2 副屏的选择判据。
 *
 * ## 移植自哪
 *
 * 反编译 `GeelyCarPlay-0.3.2-cs11-routed.apk` 的
 * `io.dushu.car.geely.GeelyDisplaySelector`（baksmali 实证）。
 * 原实现的两段判据都保留了，没有凭猜测加东西。
 *
 * ## 为什么判据这么"硬"
 *
 * 原版是"**唯一命中**"式选择：既要 display id 对上用户保存的值，又要
 * **非主屏**，又要**恰好 1920x1080**——三者同时满足才认。这不是保守，
 * 而是因为仪表 HDMI2 在一块车机上就是固定一块屏；放宽任一条件都会在
 * 某些车型上误选到副驾屏 / 后排屏，那是比"没有仪表"更糟的结果。
 *
 * 本移植**完整保留**这个语义：拿不到就返回 null，由上层按"无副屏"处理。
 */
object GeelyDisplaySelector {

    /** 仪表 HDMI2 的固定分辨率：1920x1080（原版写的 0x780 / 0x438）。 */
    const val CLUSTER_WIDTH_PX = 1920
    const val CLUSTER_HEIGHT_PX = 1080

    /**
     * 画面在面板内的可见高度：720px（原版 `GeelyDisplaySelection.videoHeight = 0x2d0`）。
     *
     * 面板 1080 高，视频只占上方 720，下方 360 留给车机自己的仪表并保持透明。
     * 这是**原版实测值**，不是猜的。
     */
    const val CLUSTER_VISIBLE_HEIGHT_PX = 720

    /**
     * 本机是否是 eCarx / 吉利车机。
     *
     * 判据（原版 `isEcarx` 逐行移植）：
     * 1. `Build.MANUFACTURER + " " + Build.BRAND` 小写后含 `ecarx` 或 `geely`；
     * 2. 否则遍历已安装应用，只要有**系统应用**（`flags & FLAG_SYSTEM`）的包名
     *    以 `com.ecarx.` 或 `com.geely.` 开头就算。
     *
     * ⚠️ 第 2 条**故意不限定**具体包名：不同车型预装的 OneOS 组件名不一样，
     * 写死某一个会在别的车型上漏判。前缀匹配是原版的做法。
     */
    fun isEcarx(context: Context): Boolean {
        val fingerprint = (
            android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND
            ).lowercase()
        if (fingerprint.contains("ecarx") || fingerprint.contains("geely")) return true

        val packages = runCatching {
            context.packageManager.getInstalledApplications(0)
        }.getOrNull() ?: return false

        return packages.any { app: ApplicationInfo ->
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val name = app.packageName ?: return@any false
            isSystem && (name.startsWith("com.ecarx.") || name.startsWith("com.geely."))
        }
    }

    /**
     * 一块候选屏的可读标签（供设置页列表用）。
     *
     * ## 2026-10-08 改：补上「能分辨」的信息
     *
     * 现场反馈「选错了屏」。根因是**标签不可分辨**：车上两块副屏
     * （`displayId=1` 的 1920×1080 与 `displayId=3` 的 1024×274）
     * 在 `Display.getName()` 里**都叫「HDMI 屏幕」**，而旧标签只吐
     * `副屏 · displayId=N · W×H` —— 用户面对两个几乎一样的字符串无从下手。
     *
     * 现在按**实测数据**补三类判据（都只是**提示**，不替用户自动选）：
     *
     * 1. **宽高比**：仪表面板是细长条（1024×274 ⇒ ≈3.7）；HDMI 外接屏 16:9（≈1.8）。
     * 2. **绝对高度**：仪表面板高度小（≤480px），外接屏常 1080。
     * 3. **物理尺寸**：由 `xdpi/ydpi` 折算 mm，和实际看到的那块屏对得上。
     */
    fun labelOf(d: GeelyDisplayDescriptor): String {
        val kind = if (d.isDefault) "主屏" else "副屏"
        val hint = panelHint(d)
        val size = physicalSizeLabel(d)
        val name = d.name.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "$kind · $hint$name · displayId=${d.id} · ${d.widthPixels}×${d.heightPixels}$size"
    }

    /**
     * 面板性质的**人工判据**（实测得来的经验值，用于列表里的提示文字）。
     *
     * ⚠️ **刻意不参与自动选择**：[select] 只认用户明确选中的 id。
     * 这里只帮用户看懂"哪块更像仪表"，决定权仍在人手里。
     */
    fun panelHint(d: GeelyDisplayDescriptor): String {
        if (d.widthPixels <= 0 || d.heightPixels <= 0) return "分辨率未知"
        val ratio = d.widthPixels.toDouble() / d.heightPixels.toDouble()
        return when {
            ratio >= 3.0 -> "细长 · 像仪表面板"
            d.heightPixels <= 480 -> "矮屏 · 像仪表面板"
            ratio >= 1.5 -> "宽屏 · 像中控或外接 HDMI"
            else -> "普通屏"
        }
    }

    /** 「像仪表面板」的分值，**只用于候选列表排序**（大的排前），不用于自动选屏。 */
    fun clusterLikeness(d: GeelyDisplayDescriptor): Int {
        if (d.widthPixels <= 0 || d.heightPixels <= 0) return 0
        val ratio = d.widthPixels.toDouble() / d.heightPixels.toDouble()
        var score = 0
        if (ratio >= 3.0) score += 3
        if (d.heightPixels <= 480) score += 2
        if (d.refreshRate in 50f..70f) score += 1
        return score
    }

    /** 由 dpi + 像素数折算物理尺寸（mm）。取不到 dpi 时返回空串。 */
    fun physicalSizeLabel(d: GeelyDisplayDescriptor): String {
        val mm = physicalSizeMmOrNull(d) ?: return ""
        return " · 约 ${mm.first}×${mm.second}mm"
    }

    /**
     * 面板自报的物理尺寸（mm）；**取不到时返回 null**。
     *
     * ## 为什么要单独抽出来（PATCH-0095，2026-10-11）
     *
     * 此前只有 [physicalSizeLabel] 用它（**仅用于设置页的提示文字**），
     * 而 [specOf] 声明给 iPhone 的 `CarClusterSpec` **从不带物理尺寸** ⇒
     * 副屏一路落到上游兜底值（实车诊断包里这一格是 `widthPhysical=200`）。
     * 现在两处共用同一份换算，标签与声明不会各说各话。
     *
     * ## ⚠️ 这台车大概率读不到
     *
     * 本机（CS11）实测 `Display.getXdpi()/getYdpi()` **返回 0.0**
     * （诊断包 `显示器快照` 里就是 `dpi=0.0x0.0`）⇒ 这里返回 null，
     * 由 `CarPlayConfigBuilder` 用产品出厂值（350mm）等比推算。
     * **这是如实的**：面板不告诉我们它多大，就不该编一个数出来。
     *
     * 返回 null 而不是 `(0, 0)`：0 会让下游算出 `heightPhysical = 0`
     * （`0 * h / w`），再被 `sanitizeReportedPhysicalMm` 夹到 1mm ——
     * 那是在告诉 iPhone「这块屏 1 毫米高」。
     */
    fun physicalSizeMmOrNull(d: GeelyDisplayDescriptor): Pair<Int, Int>? {
        if (d.widthPixels <= 0 || d.heightPixels <= 0) return null
        if (d.xdpi <= 0f || d.ydpi <= 0f) return null
        val wMm = Math.round(d.widthPixels / d.xdpi * 25.4f)
        val hMm = Math.round(d.heightPixels / d.ydpi * 25.4f)
        if (wMm <= 0 || hMm <= 0) return null
        return wMm to hMm
    }

    /**
     * 选出仪表副屏。**非 eCarx 车机直接返回 null**（原版 `select` 的第一句）。
     *
     * ## 2026-10-08 改：从「自动唯一命中」改为「按用户选中的 id 命中」
     *
     * 原实现要求 `id == requestedId && 非主屏 && 恰好 1920×1080` 且
     * **`singleOrNull`（唯一命中）**——它假设车上那块仪表屏**有且只有一块**。
     * 2026-10-08 现场日志推翻了该假设：车上出现**不止一块** 1920×1080 的
     * 非主屏（HDMI 副屏），`singleOrNull` 返回 null 后上层落到
     * `candidates().firstOrNull()`，**抓错了屏** ⇒ 用户报「选错了屏幕 / 主屏上
     * 出现 1920×720 白块」。
     *
     * 新语义：**只认用户明确选中的 id**（非主屏即可，尺寸不再作为硬条件）。
     * 用户没选（requestedId 无匹配）⇒ 返回 null，由上层报「请在设置里选屏」，
     * 绝不替用户猜一块。
     *
     * @param requestedId 用户在设置里选中的 display id（-1 / 无匹配 = 未选）。
     */
    fun select(
        isEcarx: Boolean,
        displays: List<GeelyDisplayDescriptor>,
        requestedId: Int,
    ): GeelyDisplayDescriptor? {
        if (!isEcarx) return null
        if (requestedId < 0) return null
        // 只认用户选中的那一块：非主屏 + id 完全一致。
        //
        // 用 `singleOrNull` 而不是 `firstOrNull`，是对齐原版的**唯一命中**语义
        // （原版 `select` 是 `singleOrNull { id == p3 && !isDefault && ... }`）。
        // displayId 在 `DisplayManager` 里本就唯一，所以两者结果通常一致；
        // 但语义上"唯一命中"表达的是**只认确定的一块**，一旦出现 id 重复这种
        // 异常，宁可返回 null（不开窗）也不任取其一 —— 这正是原版的谨慎。
        return displays.singleOrNull { it.id == requestedId && !it.isDefault }
    }

    /**
     * [select] 返回 null 的**原因**（诊断用；纯函数，判据与 [select] 完全一致）。
     *
     * ## 为什么单独抽一个函数（PATCH-0118，2026-10-14）
     *
     * 真车诊断包（1.1.3/196）里仪表路径**一条日志都没有**，事后无法回答
     * 「卡在哪一步」。`select` 本身是纯函数、每次建栈都会被调（还有 JVM 单测），
     * 不该自己打日志；把「为什么没选中」抽成同一份判据的可读结论，
     * 由调用方（[GeelyClusterPresenter]）走诊断通道落盘，两处判据由单测钉成一致。
     *
     * 返回 `null` = [select] 会命中；否则返回三种成因之一：
     * 非 eCarx / 未选屏（-1）/ 选中 id 无匹配（含"选的是主屏"与"id 重复"两种异常形态）。
     */
    fun selectFailureReason(
        isEcarx: Boolean,
        displays: List<GeelyDisplayDescriptor>,
        requestedId: Int,
    ): String? {
        if (!isEcarx) return "非 eCarx 车机（平台不提供仪表副屏）"
        if (requestedId < 0) return "未选副屏（clusterDisplayId=-1）"
        val matching = displays.filter { it.id == requestedId }
        return when {
            matching.isEmpty() -> "选中的副屏不在场（requestedId=$requestedId）"
            matching.any { it.isDefault } -> "选中的是主屏（requestedId=$requestedId，不允许）"
            matching.size > 1 -> "多块屏共用 id=$requestedId（唯一命中失败，判为未选）"
            else -> null
        }
    }

    /**
     * 列出当前可选的副屏候选 —— **所有非主屏**都列出来，交给用户挑。
     *
     * ## 2026-10-08 改：不再按 1920×1080 过滤
     *
     * 原实现只列「恰好 1920×1080」的非主屏。现场证明车上有**多块** 1920×1080
     * 副屏（或仪表其实是别的分辨率），硬卡尺寸会让用户想要的那块**根本不出现在
     * 列表里**。改为列出全部非主屏，由用户按 displayId + 分辨率自行判断。
     */
    fun candidates(isEcarx: Boolean, displays: List<GeelyDisplayDescriptor>): List<GeelyDisplayDescriptor> {
        if (!isEcarx) return emptyList()
        // 2026-10-08：按「像仪表面板」的程度排序（细长/矮屏排前），**只排序不自动选**。
        // 用户看到的第一行就是最可能的仪表面板，但仍需他自己点一下才算选定。
        return displays.filter { !it.isDefault }
            .sortedWith(
                compareByDescending<GeelyDisplayDescriptor> { clusterLikeness(it) }
                    .thenBy { it.id },
            )
    }

    /** 把 `Display` 读成纯数据快照。 */
    fun describe(display: Display): GeelyDisplayDescriptor {
        val mode = display.mode
        return GeelyDisplayDescriptor(
            id = display.displayId,
            widthPixels = mode?.physicalWidth ?: 0,
            heightPixels = mode?.physicalHeight ?: 0,
            isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            name = runCatching { display.name }.getOrDefault(""),
            displayType = readIntProperty(display, "getType"),
            xdpi = readFloatProperty(display, "getXdpi"),
            ydpi = readFloatProperty(display, "getYdpi"),
            refreshRate = mode?.refreshRate ?: 0f,
        )
    }

    /**
     * 反射读 `Display` 的 int 属性。
     *
     * 之所以不用直接调用：本模块的 compileSdk 下这几个方法在 Kotlin 里
     * 解析不到（编译期 `Unresolved reference`），而它们在**设备上是存在的**
     * （`getType` 自 API 17、`getXdpi`/`getYdpi` 自 API 1）。反射是这里最稳的读法。
     *
     * 读不到不是错误 —— 这些字段只是给用户看的提示，缺失时标签自动退化。
     */
    private fun readIntProperty(display: Display, methodName: String): Int =
        runCatching {
            Display::class.java.getMethod(methodName).invoke(display) as? Int
        }.getOrNull() ?: 0

    /** 反射读 `Display` 的 float 属性（同 [readIntProperty] 的理由）。 */
    private fun readFloatProperty(display: Display, methodName: String): Float =
        runCatching {
            Display::class.java.getMethod(methodName).invoke(display) as? Float
        }.getOrNull() ?: 0f

    /** 读当前所有屏。取不到时返回空表（上层按"无副屏"处理，不抛）。 */
    fun snapshotDisplays(manager: DisplayManager): List<GeelyDisplayDescriptor> =
        runCatching { manager.displays.map(::describe) }.getOrDefault(emptyList())

    /**
     * 副屏声明规格。
     *
     * ## 2026-10-08 改：可见高不能超过面板本身
     *
     * `CLUSTER_VISIBLE_HEIGHT_PX = 720` 是**吉利 1920×1080 仪表面板**的实测值
     * （视频铺上方 720，下方 360 留车机仪表）。
     *
     * 但现场这台车的仪表面板是 `displayId=3` 的 **1024×274** —— 高度只有 274，
     * 再按 720 申报，等于告诉 iPhone「这块屏有 720 高」，比屏本身还高。
     * 现在取 `min(面板高, 720)`：矮屏按实际高度报，高屏仍按 720 留出仪表区。
     *
     * ## 2026-10-11（PATCH-0095）：补上**物理尺寸**
     *
     * 此前这里**完全不传** `widthPhysicalMm/heightPhysicalMm` ⇒ 副屏声明一路落到
     * 上游兜底值 200mm（而主屏出厂值是 350mm），iPhone 按「像素 + 物理尺寸」
     * 推算出来的仪表界面密度比主屏小 1.75 倍 —— 实车表现是"仪表上的内容偏小"。
     * 现在面板自报 dpi 时如实折算，报不出来就留 null 交给下游用产品出厂值
     * （见 [physicalSizeMmOrNull] 与 `CarPlayConfigBuilder.CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM`）。
     */
    fun specOf(descriptor: GeelyDisplayDescriptor, densityDpi: Int): CarClusterSpec {
        val panelHeight = descriptor.heightPixels
        val visibleHeight = when {
            panelHeight <= 0 -> CLUSTER_VISIBLE_HEIGHT_PX
            panelHeight < CLUSTER_VISIBLE_HEIGHT_PX -> panelHeight
            else -> CLUSTER_VISIBLE_HEIGHT_PX
        }
        val physical = physicalSizeMmOrNull(descriptor)
        return CarClusterSpec(
            displayId = descriptor.id,
            widthPixels = descriptor.widthPixels,
            heightPixels = descriptor.heightPixels,
            densityDpi = densityDpi,
            widthPhysicalMm = physical?.first,
            heightPhysicalMm = physical?.second,
            fps = clusterFpsFor(descriptor.refreshRate),
            visibleHeightPixels = visibleHeight,
        )
    }

    /**
     * 副屏**声明帧率** = 面板实际刷新率（2026-10-11）。
     *
     * ## 为什么不能一律写 60
     *
     * 车主口径：「**仪表盘只支持 30 帧**」。真车日志也对得上：这块仪表面板是
     * `id=2 HDMI 屏幕 1920x1080 refresh=30.0Hz`，而我们的 `/info` 一直声明 `maxFPS=60`
     * —— 手机按 60fps 推、解码器按 60fps 解，而面板 30Hz 画不出来，多出来的那一半
     * 纯粹是带宽、解码与功耗，还可能造成节奏不齐（忽快忽慢）。
     *
     * 取不到刷新率（`<= 0`，虚拟屏/厂商 ROM 常见）时退回
     * [CarResolutionPolicy.DEFAULT_FPS]，那是 `:car:api` 的既有缺省值，
     * 不在这里另立一个数。
     *
     * ⚠️ 上界夹到 [CLUSTER_FPS_MAX]：`/info` 里声明得比面板还高没有任何好处；
     * 下界 [CLUSTER_FPS_MIN] 同理（再低就不是"仪表能显示的帧率"，而是卡顿）。
     * 最终还会由 `:shared` 的 `AirPlayDisplaySettings.sanitizeFps` 按 5 的步长收敛。
     */
    fun clusterFpsFor(refreshRateHz: Float): Int {
        if (refreshRateHz <= 0f) return CarResolutionPolicy.DEFAULT_FPS
        return Math.round(refreshRateHz).coerceIn(CLUSTER_FPS_MIN, CLUSTER_FPS_MAX)
    }

    /** 副屏帧率允许范围（与 `:shared` 的 `AirPlayDisplaySettings` 同口径，本模块看不到那边）。 */
    private const val CLUSTER_FPS_MIN = 30
    private const val CLUSTER_FPS_MAX = 60
}
