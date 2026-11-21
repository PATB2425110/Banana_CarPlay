package com.dbtool.carplay.ui.cluster

import com.dbtool.carplay.api.display.ClusterDialLayout
import com.dbtool.carplay.api.display.DialTransform
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表中央导航卡片的纯函数单测。
 *
 * 三类最容易在现场出错、又最难肉眼发现的问题在这里钉住：
 * 1. `setNavigation(null)` / 空快照必须回到「什么都不画」；
 * 2. 中央矩形必须跟着表盘变换走，任何一侧都不能侵入表盘外接圆；
 * 3. 转向码不能张冠李戴（左转画成右转是最难被发现的错误）。
 */
class ClusterNavigationCardTest {

    private val leftCx = 576.8421052f
    private val rightCx = 1343.1578948f
    private val radius = 210f
    private val safeLeft = 150f
    private val safeRight = 1770f

    private fun region(
        left: DialTransform = DialTransform(),
        right: DialTransform = DialTransform(),
    ) = ClusterNavigationLayout.region(
        leftDialCx = leftCx,
        rightDialCx = rightCx,
        outerVisibleRadius = radius,
        left = left,
        right = right,
        safeLeft = safeLeft,
        safeRight = safeRight,
    )

    // ------------------------------------------------------------ 空数据

    @Test
    fun `setNavigation null 不产生任何内容`() {
        assertNull(clusterNavigationContentOf(null))
    }

    @Test
    fun `没有可见字段的快照等同 null`() {
        assertNull(clusterNavigationContentOf(CarNavigationSnapshot()))
        assertNull(
            clusterNavigationContentOf(
                CarNavigationSnapshot(currentRoad = "   ", arrivalEpochSeconds = 0L),
            ),
        )
        // hasContent=true 但所有格式化结果都无效（负数）时也不画空卡。
        assertNull(clusterNavigationContentOf(CarNavigationSnapshot(routeMeters = -1)))
    }

    @Test
    fun `主题皮肤视图已从新项目删除`() {
        assertFalse(File("src/main/java/com/dbtool/carplay/ui/cluster/ClusterSkinView.kt").exists())
        assertNull(clusterNavigationContentOf(null))
    }

    // ------------------------------------------------------------ 中间区几何

    @Test
    fun `恒等布局的卡片完整落在左右表外接圆之间`() {
        val r = region()
        val leftDialRightEdge = leftCx + radius
        val rightDialLeftEdge = rightCx - radius

        assertFalse(r.isEmpty)
        assertTrue("左边缘不得进入左表: ${r.left} <= $leftDialRightEdge", r.left > leftDialRightEdge)
        assertTrue("右边缘不得进入右表: ${r.right} >= $rightDialLeftEdge", r.right < rightDialLeftEdge)
        assertEquals(810.8421f, r.left, 0.01f)
        assertEquals(1109.1579f, r.right, 0.01f)
        assertEquals(960f, r.centerX, 0.01f)
        assertEquals(ClusterNavigationLayout.TOP, r.top, 0f)
        assertEquals(ClusterNavigationLayout.BOTTOM, r.bottom, 0f)
    }

    @Test
    fun `出厂默认外移布局的卡片仍不与表盘相交`() {
        val default = ClusterDialLayout.DEFAULT
        val r = region(default.left, default.right)

        val leftDialRightEdge = leftCx + default.left.dx + radius * default.left.scale
        val rightDialLeftEdge = rightCx + default.right.dx - radius * default.right.scale

        assertFalse(r.isEmpty)
        assertTrue("左边缘侵入左表", r.left > leftDialRightEdge)
        assertTrue("右边缘侵入右表", r.right < rightDialLeftEdge)
        // 默认空隙约 587px，卡片按上限 560px 收窄，不能铺满整块空隙。
        assertEquals(ClusterNavigationLayout.MAX_WIDTH, r.width, 0.01f)
        assertTrue("默认布局下卡片仍应居中于可用空隙", r.centerX in 900f..1020f)
    }

    @Test
    fun `表盘向内调导致空隙不足时返回空矩形而不是压表盘`() {
        val r = region(
            left = DialTransform(dx = 200f, scale = 2f),
            right = DialTransform(dx = -200f, scale = 2f),
        )
        assertTrue(r.isEmpty)
        assertEquals(0f, r.width, 0f)
    }

    @Test
    fun `隐藏表盘时中央区退到安全边距内`() {
        val r = region(
            left = DialTransform(visible = false),
            right = DialTransform(visible = false),
        )
        assertFalse(r.isEmpty)
        assertTrue(r.left >= safeLeft)
        assertTrue(r.right <= safeRight)
        assertEquals(ClusterNavigationLayout.MAX_WIDTH, r.width, 0.01f)
        assertEquals(960f, r.centerX, 0.01f)
    }

    // ------------------------------------------------------------ 内容与箭头

    @Test
    fun `快照字段映射到距离 路名 小字`() {
        val snapshot = CarNavigationSnapshot(
            routeMeters = 3200,
            segmentMeters = 250,
            currentRoad = "人民路",
            maneuverDescription = "右转进入人民路",
            maneuverType = 2,
            arrivalEpochSeconds = Instant.parse("2020-01-02T03:04:00Z").epochSecond,
        )
        val content = clusterNavigationContentOf(snapshot, ZoneId.of("UTC"))
        requireNotNull(content)
        assertEquals("250 米", content.distanceText)
        assertEquals("右转进入人民路", content.roadText)
        assertEquals("剩余 3.2 公里 · 预计 03:04", content.detailText)
        assertEquals(ClusterManeuverKind.RIGHT, content.arrow)
    }

    @Test
    fun `只有剩余里程时只画小字`() {
        val content = clusterNavigationContentOf(
            CarNavigationSnapshot(routeMeters = 1_000),
            ZoneId.of("UTC"),
        )
        requireNotNull(content)
        assertNull(content.distanceText)
        assertNull(content.roadText)
        assertEquals("剩余 1.0 公里", content.detailText)
    }

    @Test
    fun `路名按转向描述 当前路 下一段路 目的地顺序回落`() {
        assertEquals(
            "右转",
            clusterNavigationContentOf(
                CarNavigationSnapshot(
                    maneuverDescription = "右转",
                    currentRoad = "当前路",
                    nextRoad = "下一段路",
                    destination = "目的地",
                ),
            )?.roadText,
        )
        assertEquals(
            "当前路",
            clusterNavigationContentOf(
                CarNavigationSnapshot(currentRoad = "当前路", nextRoad = "下一段路"),
            )?.roadText,
        )
        assertEquals(
            "下一段路",
            clusterNavigationContentOf(
                CarNavigationSnapshot(nextRoad = "下一段路", destination = "目的地"),
            )?.roadText,
        )
        assertEquals(
            "目的地",
            clusterNavigationContentOf(
                CarNavigationSnapshot(destination = "目的地"),
            )?.roadText,
        )
    }

    @Test
    fun `转向码映射到正确的箭头语义`() {
        val expectations = mapOf(
            1 to ClusterManeuverKind.LEFT,
            20 to ClusterManeuverKind.LEFT,
            22 to ClusterManeuverKind.LEFT,
            52 to ClusterManeuverKind.LEFT,
            2 to ClusterManeuverKind.RIGHT,
            21 to ClusterManeuverKind.RIGHT,
            23 to ClusterManeuverKind.RIGHT,
            53 to ClusterManeuverKind.RIGHT,
            3 to ClusterManeuverKind.STRAIGHT,
            5 to ClusterManeuverKind.STRAIGHT,
            4 to ClusterManeuverKind.UTURN,
            18 to ClusterManeuverKind.UTURN,
            26 to ClusterManeuverKind.UTURN,
            6 to ClusterManeuverKind.ROUNDABOUT,
            19 to ClusterManeuverKind.ROUNDABOUT,
            7 to ClusterManeuverKind.ROUNDABOUT,
            28 to ClusterManeuverKind.ROUNDABOUT,
            46 to ClusterManeuverKind.ROUNDABOUT,
            13 to ClusterManeuverKind.KEEP_LEFT,
            14 to ClusterManeuverKind.KEEP_RIGHT,
            10 to ClusterManeuverKind.ARRIVAL,
            24 to ClusterManeuverKind.ARRIVAL,
            9876 to ClusterManeuverKind.GENERIC,
        )
        expectations.forEach { (code, kind) ->
            assertEquals("maneuverType=$code", kind, clusterManeuverKindOf(code))
        }
        assertEquals(ClusterManeuverKind.GENERIC, clusterManeuverKindOf(null))
    }

    // ------------------------------------------------------------ 文本截断

    @Test
    fun `超宽文本按字符截断并追加省略号`() {
        val measure = { text: String -> text.length * 10f }
        assertEquals("abcdef", ellipsizeToWidth("abcdef", 60f, measure))
        assertEquals("abc…", ellipsizeToWidth("abcdef", 45f, measure))
        assertEquals("", ellipsizeToWidth("abcdef", 5f, measure))
        assertEquals("", ellipsizeToWidth("", 100f, measure))
    }

    private fun sourceFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过）")
}
