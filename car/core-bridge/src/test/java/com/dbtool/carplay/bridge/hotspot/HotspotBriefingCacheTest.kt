package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.bridge.CarHotspotBriefing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PATCH-0133：热点简报 TTL 缓存。
 *
 * 纯 JVM 测试，不依赖 Android：时钟由构造参数注入，直接推进。
 */
class HotspotBriefingCacheTest {

    private var nowMillis = 1_000L
    private val cache = HotspotBriefingCache(ttlMs = 30_000L) { nowMillis }
    private val settings = CarSessionSettings()

    @Test
    fun `same fingerprint hits within thirty seconds`() {
        cache.put(settings, briefing("复用车机热点「Lynk」"))

        nowMillis += 1_000L
        val hit = cache.get(settings)

        assertNotNull(hit)
        assertEquals("复用车机热点「Lynk」", hit!!.briefing.target)
        assertEquals(29_000L, hit.remainingMillis)
        assertEquals(29L, hit.remainingSeconds)
    }

    @Test
    fun `expires at thirty seconds`() {
        cache.put(settings, briefing("复用车机热点「Lynk」"))

        nowMillis += 30_000L

        assertNull(cache.get(settings))
    }

    @Test
    fun `fingerprint changes when any conclusion-affecting setting changes`() {
        cache.put(settings, briefing("复用车机热点「Lynk」"))

        // 任务书点名的四个字段，一个都不能漏进缓存键。
        val variants = listOf(
            settings.copy(hotspotMode = CarHotspotMode.ADB_HOSTAPD),
            settings.copy(manualHotspotSsid = "另一张网"),
            settings.copy(manualHotspotHostAddress = "192.168.15.1"),
            settings.copy(adbHotspotInterface = "wlan2"),
        )
        variants.forEach { variant ->
            assertNull(
                "settings=${variant.hotspotMode}/${variant.manualHotspotSsid}/" +
                    "${variant.manualHotspotHostAddress}/${variant.adbHotspotInterface}",
                cache.get(variant),
            )
        }
    }

    @Test
    fun `force never returns a cached snapshot`() {
        cache.put(settings, briefing("复用车机热点「Lynk」"))

        assertNotNull(cache.get(settings))
        assertNull(cache.get(settings, force = true))
        // force 只绕过本次读取；调用方负责 clear（见 CarAppController 的强制失效路径）。
        assertNotNull(cache.get(settings))
    }

    private fun briefing(target: String) = CarHotspotBriefing(
        target = target,
        detail = "测试用简报",
        phoneHint = null,
    )
}
