package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 沉浸模式（画面延展到状态栏下）→ AirPlay 安全区声明的单测（2026-10-08）。
 *
 * ## 为什么这几条必须有测试
 *
 * 沉浸的**全部价值**在于"顶格的同时把那条状态栏声明成安全区"：
 * - 少了声明 ⇒ 回到 2026-10-06 被车主否决的状态（CarPlay 顶栏被状态栏盖住）；
 * - 声明错了位置 ⇒ iPhone 把 UI 排到错误的地方，**现场看起来跟"没生效"一模一样**，
 *   而画布、协议、日志全都正常 —— 这类"错了也看不出来"的地方只能靠单测钉住。
 *
 * ## 为什么是纯 JVM 测试
 *
 * [CarPlayConfigBuilder.airPlayConfig] 是纯翻译层（不碰 Android API），
 * 所以这里直接调它、断言产物 `AirPlayConfig`，比读源码字面量更有说服力。
 * 真正的"iPhone 收到后怎么排"仍属实车验证项（Apple 没把
 * `drawUIOutsideSafeArea` 的组合行为写死）。
 *
 * ## 🔴 PATCH-0094（2026-10-11）：主屏的安全区**只有**本文件这一条来源
 *
 * 「仪表导航 → 核心安全区」那个矩形（`CarSessionSettings.safeArea`）
 * **只作用于仪表副屏**，主屏不读它。所以本文件里那几条"画过框也不影响主屏"
 * 的用例是**判别性**的：只要哪天有人把它接回主屏，`top` 就会变成框里的值。
 */
class ImmersiveVideoSafeAreaTest {

    private val display = CarDisplay(
        id = 0,
        widthPixels = 1920,
        heightPixels = 1080,
        densityDpi = 160,
    )

    /** 车机实测的状态栏高度（本机 CS11 实机日志：72px）。 */
    private val statusBarHeight = 72

    private fun config(
        settings: CarSessionSettings,
        immersiveSafeAreaTopPx: Int = 0,
    ) = CarPlayConfigBuilder.airPlayConfig(
        settings = settings,
        display = display,
        widthPixels = 1920,
        heightPixels = 1080,
        immersiveSafeAreaTopPx = immersiveSafeAreaTopPx,
    )

    @Test
    fun `隐藏顶部栏打开时不把安全区顶进状态栏`() {
        val config = config(
            CarSessionSettings.default().copy(hideTopBar = true),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        assertEquals(0, config.main.safeArea?.top)
    }

    @Test
    fun `隐藏顶部栏关闭时用实测状态栏高度`() {
        val config = config(
            CarSessionSettings.default().copy(hideTopBar = false),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        assertEquals(statusBarHeight, config.main.safeArea?.top)
    }

    @Test
    fun `沉浸开启时用实测状态栏高度声明安全区顶部`() {
        val config = config(
            CarSessionSettings.default().copy(immersiveVideo = true),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        assertEquals(statusBarHeight, config.main.safeArea?.top)
    }

    /**
     * 量不到状态栏高度时**不猜**：宁可保持全屏安全区。
     *
     * 猜错（例如拿面板高度当栏高）会让 iPhone 把 UI 排到屏幕中间，
     * 比"没避让"更难排查 —— 那时画面看起来"对了一半"，没有任何报错。
     */
    @Test
    fun `沉浸开启但量不到状态栏时不声明安全区`() {
        val config = config(
            CarSessionSettings.default().copy(immersiveVideo = true),
            immersiveSafeAreaTopPx = 0,
        )
        assertEquals(0, config.main.safeArea?.top)
    }

    /**
     * 🔴 PATCH-0094（2026-10-11，车主口径「仪表导航 的安全区只限副屏」）：
     * **主屏不读「核心安全区」那个框**，仍按实测状态栏高度避让。
     *
     * 改之前这条链是"画过框就以手动值为准"——后果是用户在「仪表导航」里
     * 画一个"上方 720px"的框，**主屏的 CarPlay UI 也被挤到上方一条**
     * （`toInsets` 算出的 `bottom = 360` 直接作用到主屏画布）。
     * 现场表现是"我只调了仪表，主屏怎么变小了"。
     */
    @Test
    fun `画过框也不影响主屏，仍按实测状态栏高度避让`() {
        val config = config(
            CarSessionSettings.default().copy(
                immersiveVideo = true,
                // 画布 1920×1080 上的矩形：上方留 40px（旧模型里的 `top = 40`）。
                safeArea = CarSettingsSafeArea(x = 0, y = 40, width = 1920, height = 1080 - 40),
            ),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        // 40 必须**不出现**：出现就说明主屏又读了这个框。
        assertEquals(statusBarHeight, config.main.safeArea?.top)
    }

    /**
     * 画框的**下 / 左 / 右**同样不落到主屏；`drawUIOutsideSafeArea` 仍原样透传。
     *
     * ⚠️ `safeAreaDrawOutside` 是「显示与视频 → 画面绘制到安全区外」，与那个矩形
     * 是**两件**事（它决定"画面怎么铺"，主屏副屏都吃）—— 别顺手一起砍掉。
     */
    @Test
    fun `画框的四边不落到主屏，drawUIOutsideSafeArea 原样透传`() {
        val config = config(
            CarSessionSettings.default().copy(
                immersiveVideo = true,
                // 画布 1920×1080 上的矩形：左 5、右 6、下 10、上 0。
                safeArea = CarSettingsSafeArea(
                    x = 5,
                    y = 0,
                    width = 1920 - 5 - 6,
                    height = 1080 - 10,
                ),
                safeAreaDrawOutside = false,
            ),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        assertEquals(statusBarHeight, config.main.safeArea?.top)
        assertEquals(0, config.main.safeArea?.bottom)
        assertEquals(0, config.main.safeArea?.left)
        assertEquals(0, config.main.safeArea?.right)
        assertEquals(false, config.main.safeAreaDrawOutside)
    }

    /**
     * 越界的实测值必须被夹回画布内。
     *
     * `AirPlayInfoPlist` 用 `heightPixels - safe.top - safe.bottom` 算安全区高度，
     * `top >= heightPixels` 会算出非正的高度 ⇒ 发出去的 `/info` 是无效声明。
     */
    @Test
    fun `主屏画过框时以框为准，不再用状态栏高度覆盖`() {
        val config = config(
            CarSessionSettings.default().copy(
                immersiveVideo = true,
                mainSafeArea = CarSettingsSafeArea(x = 0, y = 40, width = 1920, height = 1080 - 40),
            ),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        assertEquals(40, config.main.safeArea?.top)
        assertEquals(0, config.main.safeArea?.bottom)
        assertEquals(0, config.main.safeArea?.left)
        assertEquals(0, config.main.safeArea?.right)
    }

    @Test
    fun `主屏框拉满整屏时仍按状态栏高度避让`() {
        val config = config(
            CarSessionSettings.default().copy(
                immersiveVideo = true,
                mainSafeArea = CarSettingsSafeArea(x = 0, y = 0, width = 1920, height = 1080),
            ),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        assertEquals(statusBarHeight, config.main.safeArea?.top)
        assertEquals(0, config.main.safeArea?.bottom)
    }

    @Test
    fun `主屏框的四边都会报出去`() {
        val config = config(
            CarSessionSettings.default().copy(
                immersiveVideo = true,
                mainSafeArea = CarSettingsSafeArea(
                    x = 5,
                    y = 20,
                    width = 1920 - 5 - 6,
                    height = 1080 - 20 - 10,
                ),
            ),
            immersiveSafeAreaTopPx = statusBarHeight,
        )
        assertEquals(20, config.main.safeArea?.top)
        assertEquals(10, config.main.safeArea?.bottom)
        assertEquals(5, config.main.safeArea?.left)
        assertEquals(6, config.main.safeArea?.right)
    }

    @Test
    fun `实测值越界时夹回画布内`() {
        val config = config(
            CarSessionSettings.default().copy(immersiveVideo = true),
            immersiveSafeAreaTopPx = 5000,
        )
        assertEquals(1079, config.main.safeArea?.top)
    }
}
