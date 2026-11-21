package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PATCH-0089 契约：副屏内容 URL 的判据必须与原版 `GeelyClusterContent.url()` 一致。
 *
 * ## 背景（2026-10-11，从 `GeelyCarPlay-0.3.2` 同步「仪表地图」板块）
 *
 * 原版用三个字段决定给 iPhone 的副屏 URL：
 * 导航卡片 > 仅地图布局 > 纯地图（`when` 顺序即优先级）。DBPlay 此前把内容**写死**
 * 成地图，于是"导航卡片 / 仅地图布局 / 对齐"三个设置项无处落地。
 *
 * 这几条用例把判据钉死 —— URL 是与 iPhone 的**协议**，改错一个字就是"设置点了没反应"，
 * 而且排查时很难看出是拼串错了。
 */
class ClusterContentUrlTest {

    @Test
    fun `都不开时是纯地图 URL`() {
        assertEquals(
            "maps:/car/instrumentcluster/map",
            CarPlayClusterDisplay.contentUrl(
                showNavigationCard = false,
                alignment = 0,
                mapLayoutTrial = false,
            ),
        )
    }

    @Test
    fun `仅地图布局时在地图 URL 上带对齐参数`() {
        assertEquals(
            "maps:/car/instrumentcluster/map?maneuverLayout=rightAligned",
            CarPlayClusterDisplay.contentUrl(
                showNavigationCard = false,
                alignment = 1,
                mapLayoutTrial = true,
            ),
        )
    }

    @Test
    fun `导航卡片优先于仅地图布局`() {
        // 两者都开时必须走卡片那支（原版 when 顺序），且三组显示参数都要在。
        assertEquals(
            "maps:/car/instrumentcluster?showCompass=user&showETA=yes" +
                "&showSpeedLimit=user&maneuverLayout=topAligned",
            CarPlayClusterDisplay.contentUrl(
                showNavigationCard = true,
                alignment = 2,
                mapLayoutTrial = true,
            ),
        )
    }

    @Test
    fun `对齐越界被夹进合法档位`() {
        // 设置项可能来自旧版本或被手改过 prefs，越界不能抛。
        assertEquals(
            "maps:/car/instrumentcluster/map?maneuverLayout=leftAligned",
            CarPlayClusterDisplay.contentUrl(false, -5, true),
        )
        assertEquals(
            "maps:/car/instrumentcluster/map?maneuverLayout=topAligned",
            CarPlayClusterDisplay.contentUrl(false, 99, true),
        )
    }

    @Test
    fun `对齐档位顺序与协议一致`() {
        // clusterAlignment 存的是下标 ⇒ 改序 = 改已保存值的含义。
        assertEquals(
            listOf("leftAligned", "rightAligned", "topAligned"),
            CarPlayClusterDisplay.MANEUVER_LAYOUTS,
        )
        assertEquals(3, CarPlayClusterDisplay.MANEUVER_LAYOUT_LABELS.size)
    }
}
