package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AudioChannelOverride] 的分支测试（DBPlay PATCH-0013）。
 *
 * 这一段逻辑决定了「用户在设置页选的流号」能不能安全地变成一次建轨尝试，
 * 而它的失败模式是**沉默的**：选错了不会报错，只会没声音，现场很难回摆。
 * 所以每个分支都要有断言。
 *
 * 放在 `shared` 是因为这里没有 android.* 依赖：Android SDK 的 stub 在本地单测里
 * 调 `AudioTrack` 会抛 "not mocked"。渲染器里那段真正的建轨因此靠人工在车机上验，
 * 但**取值范围**这一层必须靠机器保证。
 */
class AudioChannelOverrideTest {

    @Test
    fun `media channel passes through`() {
        assertEquals(5, AudioChannelOverride.forChannel(AudioChannel.MEDIA, media = 5, navigation = 9))
    }

    @Test
    fun `navigation channel passes through`() {
        assertEquals(9, AudioChannelOverride.forChannel(AudioChannel.NAVIGATION, media = 5, navigation = 9))
    }

    @Test
    fun `phone channel is never overridden`() {
        // 通话若被乱指定流号，现场表现是「打电话没声」，比音乐没声更难回退。
        assertEquals(0, AudioChannelOverride.forChannel(AudioChannel.PHONE, media = 5, navigation = 9))
    }

    @Test
    fun `assistant channel is never overridden`() {
        assertEquals(0, AudioChannelOverride.forChannel(AudioChannel.ASSISTANT, media = 5, navigation = 9))
    }

    @Test
    fun `zero means automatic on both channels`() {
        assertEquals(0, AudioChannelOverride.forChannel(AudioChannel.MEDIA, media = 0, navigation = 0))
        assertEquals(0, AudioChannelOverride.forChannel(AudioChannel.NAVIGATION, media = 0, navigation = 0))
    }

    @Test
    fun `out of range values degrade to automatic instead of being passed to the ROM`() {
        // 负值/超过 20 的值不该被送到 AudioTrack：那是调用方出错，
        // 与其让 ROM 抛异常把整条音频带走，不如按 0（自动）处理。
        assertEquals(0, AudioChannelOverride.forChannel(AudioChannel.MEDIA, media = -3, navigation = 0))
        assertEquals(0, AudioChannelOverride.forChannel(AudioChannel.MEDIA, media = 21, navigation = 0))
        assertEquals(0, AudioChannelOverride.forChannel(AudioChannel.NAVIGATION, media = 0, navigation = 999))
    }

    @Test
    fun `boundary values are allowed`() {
        assertEquals(1, AudioChannelOverride.forChannel(AudioChannel.MEDIA, media = 1, navigation = 0))
        assertEquals(20, AudioChannelOverride.forChannel(AudioChannel.MEDIA, media = 20, navigation = 0))
        assertEquals(21, AudioChannelOverride.forChannel(AudioChannel.NAVIGATION, media = 0, navigation = 21))
    }
}
