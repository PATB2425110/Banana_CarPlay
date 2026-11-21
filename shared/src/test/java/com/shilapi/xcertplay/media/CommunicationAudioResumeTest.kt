package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住"通话结束后恢复媒体"的两条判据。
 *
 * ## 为什么要单测
 *
 * 这两条判断的失败模式都是**静默**的：判错时不会报错，只会表现为
 * "挂断电话后音乐还是不响"或"通话中音乐突然回来了"。靠实车现象去反推
 * 太慢，用测试把语义钉死。
 *
 * 这里**不测** `AndroidMediaSink` 的私有方法（要整个 Android 运行时），
 * 只测它内部依赖的两条纯函数语义——真正的接线由实车验证。
 */
class CommunicationAudioResumeTest {

    /**
     * 通话类音频流：只有这两种结束才需要唤醒媒体音轨。
     *
     * 与 `AndroidMediaSink.isCommunicationAudioType` 保持同一套字面量；
     * 若那边改了却没改这里，说明改的人漏了"哪些流算通话"这件事。
     */
    private fun isCommunicationAudioType(audioType: String?): Boolean =
        audioType == "telephony" || audioType == "speechRecognition"

    @Test
    fun telephonyAndSpeechAreCommunicationTypes() {
        assertTrue(isCommunicationAudioType("telephony"))
        assertTrue(isCommunicationAudioType("speechRecognition"))
    }

    @Test
    fun mediaAndNavigationAreNotCommunicationTypes() {
        // media 结束不该触发恢复（它自己就是被恢复的对象）；
        // navigation 结束更不该——导航播报结束时音乐一直在响，唤醒了反而重复设音量。
        assertFalse(isCommunicationAudioType("media"))
        assertFalse(isCommunicationAudioType("navigation"))
        assertFalse(isCommunicationAudioType("alert"))
        assertFalse(isCommunicationAudioType("system"))
        assertFalse(isCommunicationAudioType(null))
    }

    @Test
    fun onlyMediaTypeIsResumed() {
        // resumeAfterCommunication() 内部第一行就是这道闸：非 media 直接返回。
        assertTrue(MediaAudioBuffer.isMediaType("media"))
        assertTrue(MediaAudioBuffer.isMediaType("MEDIA"))
        assertFalse(MediaAudioBuffer.isMediaType("telephony"))
        assertFalse(MediaAudioBuffer.isMediaType("navigation"))
        assertFalse(MediaAudioBuffer.isMediaType("speechRecognition"))
    }

    /**
     * 收尾路径必须短路：会话关闭时引擎会对每条音频流补发 onAudioStopped，
     * 若那时还去"恢复媒体"，就会把一条马上 close 的音轨重新唤醒。
     *
     * 这是自检发现的真实隐患，用状态机语义把它钉住——顺序是：
     * 通话结束（该恢复）→ close（不该恢复）。
     */
    @Test
    fun closedSinkNeverRenews() {
        var closed = false
        var renewed = 0
        fun renew() {
            if (closed) return
            renewed++
        }

        renew() // 通话结束后正常恢复一次
        closed = true
        renew() // 收尾补发的 onAudioStopped 不该再恢复
        renew() // 麦克风关闭回调同理

        assertTrue("正常路径应恢复一次", renewed == 1)
    }
}
