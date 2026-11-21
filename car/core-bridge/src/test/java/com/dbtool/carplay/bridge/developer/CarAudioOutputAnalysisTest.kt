package com.dbtool.carplay.bridge.developer

import org.junit.Assert.*
import org.junit.Test

class CarAudioOutputAnalysisTest {
    @Test fun noPermissionDoesNotInventHardwareFormat() {
        val report = CarAudioOutputAnalysis.conclusion(1, 1, false, "")
        assertTrue(report.contains("无DUMP权限"))
        assertTrue(report.contains("最终DAC/DSP输出格式：未知"))
        assertTrue(report.contains("系统仍可能重采样"))
    }

    @Test fun stoppedPlayerIsReportedWithoutGuessing48k() {
        val report = CarAudioOutputAnalysis.conclusion(0, 0, false, "")
        assertTrue(report.contains("播放音乐后重新扫描"))
        assertTrue(report.contains("未读取到本应用音轨"))
        assertFalse(report.contains("48000"))
    }

    @Test fun unrelatedMixerDumpCannotBeReportedAsCurrentDac() {
        val report = CarAudioOutputAnalysis.conclusion(1, 1, true, "Output thread sample rate: 192000")
        assertTrue(report.contains("尚未可靠关联"))
        assertTrue(report.contains("最终DAC/DSP输出格式：未知"))
    }

    @Test fun emptyOrDeniedDumpHasAnExplicitReason() {
        assertTrue(CarAudioOutputAnalysis.conclusion(1, 1, true, "").contains("超时"))
        assertTrue(CarAudioOutputAnalysis.conclusion(1, 1, true, "Permission Denial").contains("拒绝读取"))
    }
}
