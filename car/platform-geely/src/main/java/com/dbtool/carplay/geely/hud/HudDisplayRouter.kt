package com.dbtool.carplay.geely.hud

import android.content.Context
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.view.Display

/**
 * 一块 display 的纯数据快照（不含 Android 类型 ⇒ 可在 JVM 单测里构造）。
 *
 * 2026-10-21 实车（VIN `L6TTEST1111111114`，CS11）读数：
 *
 * | displayId | 名称 | 尺寸 | flags | 用途 |
 * | --- | --- | --- | --- | --- |
 * | 0 | 主屏 | 1920×1080 | — | CarPlay 主画面 |
 * | 2 | 仪表 | 1920×1080 @30Hz | — | `com.geely.hud/HUDActivity` 的宿主 |
 * | 3 | HDMI | **1024×274** @60Hz | `0xb` | HUD 玻璃（本模块目标） |
 */
data class HudDisplayDescriptor(
    val displayId: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val flags: Int,
    val name: String = "",
)

/** [HudDisplayRouter.route] 的结果。 */
sealed interface HudDisplayRoute {
    data class Found(val display: HudDisplayDescriptor) : HudDisplayRoute

    /** 没找到 / 不唯一 —— 调用方必须报错，**不得回退到别的屏**。 */
    data class Unavailable(val reason: String) : HudDisplayRoute
}

/**
 * 按「**1024×274 + FLAG_PRESENTATION**」定位 HUD 屏（2026-10-22）。
 *
 * ## 为什么不能按 `com.geely.hud` 找
 *
 * 实车数据里 `com.geely.hud/com.geely.hud.base.HUDActivity` 跑在 **display 2**
 * （1920×1080 的仪表屏，HUD 区只是仪表内的一块），不是 display 3 的 HDMI 玻璃。
 * 按包名/组件定位会稳定地投错屏：仪表上出现 HUD 画面、真正的 HUD 一片空。
 *
 * ## 为什么多候选要返回空
 *
 * 两块屏同时命中 1024×274 + PRESENTATION 时，没有任何本地判据能决定哪块是
 * HUD 玻璃（车机可能还接了别的 HDMI 条屏）。猜一个的失败模式是「投到用户
 * 看不见的屏」且**日志看起来一切正常**。宁可报 [HUD_AMBIGUOUS] 让人来确认。
 *
 * 纯逻辑：不引用 `DisplayManager`/`Display`，只在 [HudDisplays] 里做系统查询。
 */
class HudDisplayRouter(
    private val expectedWidthPixels: Int = EXPECTED_WIDTH_PIXELS,
    private val expectedHeightPixels: Int = EXPECTED_HEIGHT_PIXELS,
    private val requiredFlags: Int = FLAG_PRESENTATION,
) {

    fun route(displays: List<HudDisplayDescriptor>): HudDisplayRoute {
        val matches = displays.filter { candidate ->
            candidate.widthPixels == expectedWidthPixels &&
                candidate.heightPixels == expectedHeightPixels &&
                candidate.flags and requiredFlags == requiredFlags
        }
        return when (matches.size) {
            0 -> HudDisplayRoute.Unavailable(HUD_NOT_FOUND)
            1 -> HudDisplayRoute.Found(matches.single())
            else -> HudDisplayRoute.Unavailable(HUD_AMBIGUOUS)
        }
    }

    companion object {
        /** 实车 Display 3 的宽（2026-10-21 实测）。 */
        const val EXPECTED_WIDTH_PIXELS = 1024

        /** 实车 Display 3 的高。 */
        const val EXPECTED_HEIGHT_PIXELS = 274

        /**
         * Android `Display.FLAG_PRESENTATION`（0x8）。
         *
         * 用字面量而不是 `Display.FLAG_PRESENTATION`：本文件的上半部分要能在
         * JVM 单测里运行，字面量与框架常量逐位相同（`android.view.Display` 的
         * 定义就是 `public static final int FLAG_PRESENTATION = 1 << 3`）。
         */
        const val FLAG_PRESENTATION = 0x8

        const val HUD_NOT_FOUND = "HUD_NOT_FOUND"

        /** 命中不止一块 —— 与「没找到」分开报，处置方式不同（换屏 vs 拔掉多余的屏）。 */
        const val HUD_AMBIGUOUS = "HUD_AMBIGUOUS"
    }
}

/**
 * 真正问系统的薄适配层（Android 9 可用 API）。
 *
 * 与 [HudDisplayRouter] 分开：后者只做纯判据，这里只做「怎么枚举」，
 * 任何系统异常都降级成空表（HUD 不可用不该把会话带崩）。
 */
object HudDisplays {

    fun query(context: Context): List<HudDisplayDescriptor> {
        val manager = context.applicationContext
            .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return emptyList()
        return manager.displays.orEmpty().mapNotNull { display ->
            runCatching {
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                HudDisplayDescriptor(
                    displayId = display.displayId,
                    widthPixels = size.x,
                    heightPixels = size.y,
                    flags = display.flags,
                    name = display.name.orEmpty(),
                )
            }.getOrNull()
        }
    }

    /** 重新取一次真实 `Display` 对象（建 `Presentation` 用；不要缓存跨配置变化）。 */
    fun display(context: Context, displayId: Int): Display? {
        val manager = context.applicationContext
            .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        return runCatching { manager.getDisplay(displayId) }.getOrNull()
    }
}
