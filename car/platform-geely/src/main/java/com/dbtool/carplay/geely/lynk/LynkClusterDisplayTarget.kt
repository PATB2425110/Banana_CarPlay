package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.util.Log
import java.lang.reflect.Method

/**
 * Lynk 车机**选屏**：从演示屏类目里按 `uniqueId` 找仪表屏。
 *
 * ## 与 GeelyCarPlay 的「硬条件遍历」不同
 *
 * 上游 DiPlay(Lynk) 的做法：
 *
 * ```kotlin
 * // 车型门禁：只有 lynk_03 才启用，否则直接空列表（不投）
 * if (HeadUnitProfiles.detect(...).id != "lynk_03") return emptyList()
 *
 * // 拿「演示屏」类目，而不是遍历所有 Display 猜
 * dm.getDisplays(DisplayManagerCompat.DISPLAY_CATEGORY_PRESENTATION)
 *
 * // 定位靠 uniqueId，不靠 displayId 数字
 * fun find(ctx, uniqueId): Target?
 * ```
 *
 * 要点：**`DISPLAY_CATEGORY_PRESENTATION` + `uniqueId`** 是正统取副屏姿势；
 * `displayId` 数字随开机重排，**不能写死**。
 *
 * ## 实现要点
 *
 * `DISPLAY_CATEGORY_PRESENTATION` 是 `androidx.core.hardware.display.DisplayManagerCompat`
 * 里的常量（= 2），本模块没依赖 androidx.core → **直接用常量 2**；
 * `Display.getUniqueId()` 在 API 33+ 才公开，本车是 API 28 → **走反射**。
 *
 * ⚠️ 本车实测屏清单：id=0 主屏 INTERNAL；id=1 HDMI；id=2 HDMI 1920x1080@30（用户称仪表）；
 * id=3 HDMI 1024x274。**选中哪块必须以真机 `uniqueId` 为准**，不要写死 id=2。
 */
object LynkClusterDisplayTarget {

    private const val TAG = "DBPlay-LynkTarget"

    /** androidx.core DisplayManagerCompat.DISPLAY_CATEGORY_PRESENTATION 的值。 */
    private const val CATEGORY_PRESENTATION = "android.hardware.display.category.PRESENTATION"

    /** 一块候选副屏。 */
    data class Screen(
        val displayId: Int,
        val uniqueId: String,
        val name: String,
        val width: Int,
        val height: Int,
        val refreshRate: Float,
        val isPresentationCategory: Boolean,
    )

    /**
     * 列出演示屏类目下的所有副屏（跳过主屏 displayId==0）。
     *
     * @return 候选列表，可能为空（说明该车机没暴露 PRESENTATION 类目）
     */
    fun listPresentationScreens(context: Context): List<Screen> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return emptyList()
        val result = mutableListOf<Screen>()
        val displays = runCatching { dm.getDisplays(CATEGORY_PRESENTATION) }
            .getOrNull() ?: return emptyList()
        for (d in displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY) continue
            result += snapshot(d, presentation = true)
        }
        Log.i(TAG, "演示屏类目命中 ${result.size} 块: ${result.map { it.displayId }}")
        return result
    }

    /** 全量列出所有副屏（PRESENTATION 类目为空时的兜底诊断）。 */
    fun listAllSecondaryScreens(context: Context): List<Screen> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return emptyList()
        return dm.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .map { snapshot(it, presentation = false) }
    }

    /**
     * 按 `uniqueId` 找屏（不靠 displayId）。
     *
     * @return 找到的屏；找不到返回 null
     */
    fun find(context: Context, uniqueId: String): Screen? =
        (listPresentationScreens(context) + listAllSecondaryScreens(context))
            .firstOrNull { it.uniqueId == uniqueId }

    /** 反射拿 `Display.getUniqueId()`（API 33+ 才公开）。失败退回 name / displayId。 */
    private fun uniqueIdOf(d: Display): String {
        try {
            val m: Method = Display::class.java.getMethod("getUniqueId")
            (m.invoke(d) as? String)?.let { return it }
        } catch (_: Throwable) {
            // 旧系统没这个方法，走兜底。
        }
        return runCatching { d.name }.getOrDefault("display-${d.displayId}")
    }

    private fun snapshot(d: Display, presentation: Boolean): Screen {
        val mode = runCatching { d.mode }.getOrNull()
        val w = mode?.physicalWidth ?: d.width
        val h = mode?.physicalHeight ?: d.height
        val rate = mode?.refreshRate ?: d.refreshRate
        return Screen(
            displayId = d.displayId,
            uniqueId = uniqueIdOf(d),
            name = runCatching { d.name }.getOrDefault(""),
            width = w,
            height = h,
            refreshRate = rate,
            isPresentationCategory = presentation,
        )
    }

    /** 车机是否暴露了演示屏类目（决定借屏路线是否可行）。 */
    fun hasPresentationCategory(context: Context): Boolean =
        listPresentationScreens(context).isNotEmpty()
}
