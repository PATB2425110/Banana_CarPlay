package com.dbtool.carplay.bridge.navigation

import com.shilapi.xcertplay.iap2.message.CarLaneGuidance
import com.shilapi.xcertplay.iap2.message.CarLaneStatus
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyMapBroadcastTest {

    @Test
    fun `Apple 机动类型换成仪表箭头号，不原样送出`() {
        assertEquals(2, GeelyNaviIcons.arrow(1, 0))
        assertEquals(3, GeelyNaviIcons.arrow(2, 0))
        assertEquals(9, GeelyNaviIcons.arrow(3, 0))
        assertEquals(8, GeelyNaviIcons.arrow(4, 0))
        assertEquals(19, GeelyNaviIcons.arrow(4, 1))
        assertEquals(11, GeelyNaviIcons.guideIcon(29, 0).first)
        assertEquals(15, GeelyNaviIcons.arrow(10, 0))
        assertEquals(0, GeelyNaviIcons.arrow(99, 0))
        assertTrue(GeelyNaviIcons.arrow(1, 0) != 1)
    }

    @Test
    fun `角度先变成位再变成车道图标`() {
        assertEquals(1, GeelyNaviIcons.laneAngleMask(emptyList()))
        assertEquals(1, GeelyNaviIcons.laneAngleMask(listOf(0)))
        assertEquals(4, GeelyNaviIcons.laneAngleMask(listOf(-90)))
        assertEquals(2, GeelyNaviIcons.laneAngleMask(listOf(90)))
        assertEquals(3, GeelyNaviIcons.laneAngleMask(listOf(0, 90)))
        assertEquals(0, GeelyNaviIcons.trafficLaneIcon(1))
        assertEquals(1, GeelyNaviIcons.trafficLaneIcon(4))
        assertEquals(4, GeelyNaviIcons.trafficLaneIcon(3))
        assertEquals(5, GeelyNaviIcons.trafficLaneIcon(8))
    }

    @Test
    fun `导航开始写 Dim，重复不写，结束只停一次`() {
        val sink = RecordingSink()
        val card = GeelyNaviCard(sink)
        val active = CarNavigationState(
            maneuverType = 1,
            drivingSide = 0,
            maneuverDescription = "攸州南路",
            segmentMeters = 120,
            routeMeters = 3400,
            routeSeconds = 600,
            lanes = listOf(
                CarLaneGuidance(0, listOf(-90), null, CarLaneStatus.GOOD),
                CarLaneGuidance(1, listOf(0), null, CarLaneStatus.GOOD),
            ),
        )
        card.push(active)
        card.push(active)
        assertEquals(listOf("start", "update"), sink.events)
        val update = sink.last!!
        assertEquals(2, update.arrow)
        assertEquals(120, update.segmentMeters)
        assertEquals("攸州南路", update.road)
        assertEquals(3400, update.routeMeters)
        assertEquals(600, update.etaSeconds)
        assertEquals(listOf(NaviLaneIcon(1, 1), NaviLaneIcon(2, 0)), update.lanes)

        card.push(null)
        card.push(CarNavigationState())
        assertEquals(listOf("start", "update", "stop"), sink.events)
    }

    private class RecordingSink : NaviCardSink {
        val events = mutableListOf<String>()
        var last: NaviCardUpdate? = null
        override fun started() {
            events += "start"
        }
        override fun stopped() {
            events += "stop"
        }
        override fun update(card: NaviCardUpdate) {
            events += "update"
            last = card
        }
    }
}
