package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NavigationCardTextTest {

    @Test
    fun `instruction is preferred to road name without translating unknown turn codes`() {
        val snapshot = CarNavigationSnapshot(
            maneuverDescription = " 进入环岛 ",
            currentRoad = "当前道路",
            maneuverType = 9876,
        )
        assertEquals("进入环岛", navigationHeadline(snapshot))
    }

    @Test
    fun `missing instruction falls back to road then generic navigation text`() {
        assertEquals(
            "滨江路",
            navigationHeadline(CarNavigationSnapshot(currentRoad = "滨江路")),
        )
        assertEquals("导航进行中", navigationHeadline(CarNavigationSnapshot()))
    }

    @Test
    fun `missing or invalid distance stays hidden and real values are formatted`() {
        assertNull(navigationDistanceText(null))
        assertNull(navigationDistanceText(-1))
        assertEquals("0 米", navigationDistanceText(0))
        assertEquals("250 米", navigationDistanceText(250))
        assertEquals("1.3 公里", navigationDistanceText(1250))
    }

    @Test
    fun `duration handles sub-minute and hour boundaries`() {
        assertNull(navigationDurationText(null))
        assertNull(navigationDurationText(-1))
        assertEquals("即将到达", navigationDurationText(0))
        assertEquals("不到 1 分钟", navigationDurationText(45))
        assertEquals("59 分钟", navigationDurationText(3599))
        assertEquals("1 小时", navigationDurationText(3600))
        assertEquals("1 小时 1 分钟", navigationDurationText(3660))
    }

    @Test
    fun `destination prefers destination label and arrival uses supplied timezone`() {
        assertEquals(
            "机场",
            navigationDestinationText(CarNavigationSnapshot(destination = "机场", nextRoad = "机场路")),
        )
        assertEquals(
            "机场路",
            navigationDestinationText(CarNavigationSnapshot(nextRoad = "机场路")),
        )
        val epoch = Instant.parse("2020-01-02T03:04:00Z").epochSecond
        assertEquals("03:04", navigationArrivalText(epoch, ZoneId.of("UTC")))
        assertNull(navigationArrivalText(null, ZoneId.of("UTC")))
    }
}
