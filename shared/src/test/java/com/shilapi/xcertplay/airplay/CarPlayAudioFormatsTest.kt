package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioFormatsTest {
    private fun output(q: AudioQualitySettings = AudioQualitySettings(), wireless: Boolean = true,
        lpcm: Boolean = false, type: Int = 102, name: String = "media"): Int =
        CarPlayAudioFormats.build(q, true, wireless, lpcm)
            .single { it["type"] == type && it["audioType"] == name }["audioOutputFormats"] as Int

    @Test fun music100RetainsLegacyAndBothStereoRatesWhileToggleChangesOnly102() {
        for (wireless in listOf(false, true)) {
            assertEquals(0xcbfc, output(wireless = wireless, type = 100))
            assertEquals(0xc00000, output(wireless = wireless))
            assertEquals(0x8800, output(wireless = wireless, lpcm = true))
            val off = CarPlayAudioFormats.build(AudioQualitySettings(), true, wireless, false)
            val on = CarPlayAudioFormats.build(AudioQualitySettings(), true, wireless, true)
            assertEquals(off.dropLast(1), on.dropLast(1))
        }
    }
    @Test fun musicCeiling44100Excludes48000ForPcmAndAac() {
        val q = AudioQualitySettings(musicRate = 44100)
        assertEquals(0xffc, output(q, type = 100))
        assertEquals(0x400000, output(q))
        assertEquals(0x800, output(q, lpcm = true))
    }
    @Test fun voiceCapsAndChannelsAreSeparateFromMusic() {
        val q = AudioQualitySettings(assistantRate = 24000, phoneRate = 16000, navigationChannels = 1)
        assertEquals(0x7000c3fc, output(q, name = "default", type = 100))
        assertEquals(0x70004154, output(q, name = "telephony", type = 100))
        assertEquals(0xc7fc, output(q, wireless = false, name = "default", type = 101))
        assertEquals(0xc00000, output(q))
    }
    @Test fun microphoneDisabledOmitsAllInputsAndEnabledRetainsLegacyCapabilities() {
        for (wireless in listOf(false, true)) {
            val disabled = CarPlayAudioFormats.build(AudioQualitySettings(), false, wireless, false)
            assertTrue(disabled.none { it.containsKey("audioInputFormats") })
            val enabled = CarPlayAudioFormats.build(AudioQualitySettings(), true, wireless, false)
            for (entry in enabled.filter { it.containsKey("audioInputFormats") })
                assertEquals(if (wireless && entry["audioType"] != "compatibility") 0x70004154
                    else 0x4154 or (if (wireless) 0x10000000 else 0), entry["audioInputFormats"])
        }
    }
    @Test fun transportIncompatibleCeilingsNeverAdvertiseZeroAndDoNotInventCodecs() {
        val q = AudioQualitySettings(assistantRate = 8000, phoneRate = 8000, navigationRate = 44100, microphoneRate = 8000)
        for (wireless in listOf(false, true)) {
            for (entry in CarPlayAudioFormats.build(q, true, wireless, false))
                assertNotEquals(0, entry["audioOutputFormats"])
        }
        assertEquals(0x7000c3fc, output(q, name = "default", type = 101))
    }
    @Test fun defaultOfferContainsAllLegacyNonMediaBitsWithoutDroppingCurrentOnes() {
        for (wireless in listOf(false, true)) {
            val opus = if (wireless) 0x70000000 else 0
            for (mic in listOf(false, true)) {
                for (lpcm in listOf(false, true)) {
                    val all = CarPlayAudioFormats.build(AudioQualitySettings(), mic, wireless, lpcm)
                    for (item in all.filter { it["audioType"] != "media" }) {
                        val name = item["audioType"] as String
                        val old = (if (name in listOf("telephony", "speechRecognition")) 0x4154 else 0xc3fc) or
                            (if (name == "compatibility") 0 else opus)
                        val actual = item["audioOutputFormats"] as Int
                        assertEquals("$wireless/$mic/$name", old, actual and old)
                    }
                    assertEquals(0xcbfc, all.single { it["type"] == 100 && it["audioType"] == "media" }["audioOutputFormats"])
                    assertEquals(if (lpcm) 0x8800 else 0xc00000,
                        all.single { it["type"] == 102 }["audioOutputFormats"])
                }
            }
        }
    }
    @Test fun legacyMusicUnionSurvivesActualInfoPlistSerializationWithoutAddingMicrophoneInputs() {
        for (wireless in listOf(false, true)) {
            for (mic in listOf(false, true)) {
                for (lpcm in listOf(false, true)) {
                    val info = AirPlayInfoPlist.build(AirPlayConfig(
                        deviceName = "test", deviceId = "02:00:00:00:00:02",
                        btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
                        main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
                        wirelessAudio = wireless, microphone = mic, experimentalBufferedLpcm = lpcm,
                    ))
                    val decoded = BplistCodec.decode(BplistCodec.encode(info)) as Map<*, *>
                    val formats = (decoded["audioFormats"] as List<*>).map { it as Map<*, *> }
                    val music = formats.single { (it["type"] as Number).toInt() == 100 && it["audioType"] == "media" }
                    val actual = (music["audioOutputFormats"] as Number).toInt()
                    assertEquals(0xc3fc, actual and 0xc3fc)
                    assertEquals(0x8800, actual and 0x8800)
                    assertFalse(music.containsKey("audioInputFormats"))
                    if (!mic) assertTrue(formats.none { it.containsKey("audioInputFormats") })
                    val buffered = formats.single { (it["type"] as Number).toInt() == 102 }
                    assertEquals(if (lpcm) 0x8800 else 0xc00000,
                        (buffered["audioOutputFormats"] as Number).toInt())
                }
            }
        }
    }
}
