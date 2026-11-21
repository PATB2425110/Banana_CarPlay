package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关键帧恢复命令的目标屏必须正确。
 *
 * 背景（2026-10-08，PATCH-0034）：1.1.0 现场日志连续 8 次
 * `video recovery key frame requested sent=false` —— 仪表流（111）从未注册恢复回调，
 * 而唯一的命令构造器 `mainScreenKeyFrameCommand()` **不带 uuid**、只作用于主屏。
 * 该组用例锁死「主屏不带 uuid / 仪表带 ALT_UUID」这一约定，防止回归。
 */
class ClusterKeyFrameCommandTest {

    @Test
    fun mainScreenCommandKeepsEmptyParamsSoPrimaryDecoderIsNotRestarted() {
        val command = mainScreenKeyFrameCommand()
        assertEquals("forceKeyFrame", command["type"])
        assertEquals(emptyMap<String, Any?>(), command["params"])
        assertTrue("主屏命令不得携带 uuid", (command["params"] as Map<*, *>).isEmpty())
    }

    @Test
    fun altScreenCommandCarriesAltUuid() {
        val command = altScreenKeyFrameCommand()
        assertEquals("forceKeyFrame", command["type"])
        assertEquals(
            AirPlayInfoPlist.ALT_UUID,
            (command["params"] as Map<*, *>)["uuid"],
        )
    }

    @Test
    fun altScreenCommandDoesNotTargetThePrimaryDisplay() {
        val uuid = (altScreenKeyFrameCommand()["params"] as Map<*, *>)["uuid"]
        assertEquals(AirPlayInfoPlist.ALT_UUID, uuid)
        assertTrue("仪表命令绝不能带主屏 uuid", uuid != AirPlayInfoPlist.MAIN_UUID)
    }

    @Test
    fun bothCommandsUseTheForceKeyFrameOpcode() {
        assertEquals("forceKeyFrame", mainScreenKeyFrameCommand()["type"])
        assertEquals("forceKeyFrame", altScreenKeyFrameCommand()["type"])
    }

    @Test
    fun altScreenCommandHasNoExtraKeys() {
        val command = altScreenKeyFrameCommand()
        assertEquals(setOf("type", "params"), command.keys)
        val params = command["params"] as Map<*, *>
        assertEquals(setOf("uuid"), params.keys)
        assertNull(params["url"])
    }
}
