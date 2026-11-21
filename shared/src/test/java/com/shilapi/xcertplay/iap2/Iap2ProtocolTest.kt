package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2NavigationAccumulator
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.message.Iap2MediaRemoteCommand
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingAccumulator
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.session.Iap2FileTransferHandler
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.transport.Iap2LinkEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2ProtocolTest {
    @Test
    fun catalogRegistersEveryReferenceEndpointOnce() {
        assertEquals(151, Iap2Endpoints.ALL.size)
        assertEquals(151, Iap2Endpoints.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun framerAcceptsFragmentedAndConcatenatedFrames() {
        val first = Iap2Frame(0x4300, byteArrayOf(0, 4, 0, 0))
        val second = Iap2Frame(0x4301, byteArrayOf(0, 5, 0, 0, 1))
        val bytes = first.encodedFrame() + second.encodedFrame()
        val framer = Iap2CsmFramer()

        assertTrue(framer.offer(bytes.copyOfRange(0, 3)).isEmpty())
        assertEquals(listOf(first, second), framer.offer(bytes.copyOfRange(3, bytes.size)))
    }

    @Test
    fun orderedParameterListPreservesRepeatedAndUnknownIds() {
        val original = listOf(
            Iap2Parameter(0, byteArrayOf(1)),
            Iap2Parameter(0, byteArrayOf(2)),
            Iap2Parameter(0x7fff, byteArrayOf(3, 4)),
        )
        val decoded = Iap2ParameterList.parse(Iap2ParameterList.of(original).encode())

        assertEquals(listOf(0, 0, 0x7fff), decoded.asList().map { it.id })
        assertArrayEquals(byteArrayOf(1), decoded.all(0)[0].payload)
        assertArrayEquals(byteArrayOf(2), decoded.all(0)[1].payload)
        assertArrayEquals(byteArrayOf(3, 4), decoded.first(0x7fff)?.payload)
    }

    @Test
    fun genericBuilderCanConstructNestedUnmodeledEndpointBody() {
        val frame = Iap2Messages.buildRaw(0x0d01) {
            u16(0, 1)
            group(4) {
                u32(0, 7)
                u8(1, 2)
            }
        }
        val body = Iap2BodyReader.of(frame)
        val roadSign = body.group(4)

        assertEquals(1, body.u16(0))
        assertEquals(7L, roadSign.u32(0))
        assertEquals(2, roadSign.u8(1))
    }

    @Test
    fun carPlayStartSessionSupportsReferenceOptionalFields() {
        val frame = Iap2CarPlayMessages.startSession(
            wiredIpv6Addresses = listOf("fe80::2"),
            wiredReserved = 3L,
            wireless = Iap2WirelessSessionParameters(
                ssid = "LIVI",
                passphrase = "secret",
                channel = 36,
                ipAddresses = listOf("192.168.1.1", "192.168.1.2"),
                securityType = 3,
            ),
            airPlayPort = 7000,
            deviceIdentifier = "dev-1",
            publicKey = "pub",
            sourceVersion = "1.0",
            sdkVersion = "27.0",
            clusterAsset = Iap2ClusterAsset("cluster", 4),
            mutualAuth = true,
        )
        val body = Iap2BodyReader.of(frame)
        val wired = body.group(0)
        val wireless = body.group(1)

        assertEquals("fe80::2", wired.string(0))
        assertEquals(3L, wired.u32(1))
        assertEquals("LIVI", wireless.string(0))
        assertEquals(2, wireless.all(3).size)
        assertEquals("192.168.1.2", wireless.all(3)[1].payload?.let { String(it).trimEnd('\u0000') })
        assertEquals(7000L, body.u32(2))
        assertEquals("27.0", body.string(6))
        assertEquals("cluster", body.group(7).string(0))
        assertEquals(4L, body.group(7).u32(1))
        assertEquals(1, body.u8(8))
    }

    @Test
    fun wirelessCarPlayAvailabilityUsesBooleanReferenceSemantics() {
        assertFalse(
            Iap2WirelessMessages.wirelessCarPlayAvailability(
                Iap2Messages.buildRaw(0x4e0d) { u8(0, 0) },
            ),
        )
        assertTrue(
            Iap2WirelessMessages.wirelessCarPlayAvailability(
                Iap2Messages.buildRaw(0x4e0d) { u8(0, 1) },
            ),
        )
        val invalid = Iap2Messages.buildRaw(0x4e0d) { u8(0, 2) }
        try {
            Iap2WirelessMessages.wirelessCarPlayAvailability(invalid)
            throw AssertionError("Expected an invalid boolean availability to be rejected")
        } catch (_: Iap2ProtocolException) {
            // Expected.
        }
    }

    @Test
    fun accessoryWifiConfigurationOmitsOrIncludesOptionalBssid() {
        val withoutBssid = Iap2WirelessMessages.accessoryWiFiConfiguration(
            ssid = "LIVI",
            passphrase = "secret",
            channel = 36,
            securityType = 3,
        )
        val withBssid = Iap2WirelessMessages.accessoryWiFiConfiguration(
            ssid = "LIVI",
            passphrase = "secret",
            channel = 36,
            securityType = 3,
            bssid = byteArrayOf(1, 2, 3, 4, 5, 6),
        )

        assertNull(Iap2BodyReader.of(withoutBssid).first(0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), Iap2BodyReader.of(withBssid).bytes(0))
    }

    @Test
    fun formatterShowsEndpointNamesFieldsAndNestedGroups() {
        val frame = Iap2CarPlayMessages.startSession(
            wiredIpv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            publicKey = "pub",
            sourceVersion = "1.0",
        )

        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wired", frame)

        assertTrue(formatted.startsWith("IAP2 TX [wired] 0x4301 CarPlayStartSession"))
        assertTrue("wired:" in formatted)
        assertTrue("wiredIP: \"fe80::2\"" in formatted)
        assertTrue("airPlayPort: u32=7000" in formatted)
        assertTrue("raw-body=" in formatted)
    }

    @Test
    fun formatterFallsBackForMalformedBodies() {
        val formatted = Iap2FrameFormatter.format(
            Iap2TraceDirection.RX,
            "wired",
            Iap2Frame(0x4300, byteArrayOf(0, 1, 2)),
        )

        assertTrue(formatted.contains("body=malformed TLV"))
        assertTrue(formatted.contains("raw-body=00 01 02"))
    }

    @Test
    fun formatterEscapesLineBreaksInNmeaStrings() {
        val frame = Iap2ControlMessages.locationInformation("\$GPGGA,1\r\n")
        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wired", frame)

        assertTrue(formatted.contains("\\r\\n"))
    }

    @Test
    fun nowPlayingUpdatesMergeIncrementallyAndFreezeElapsedTimeWhenPaused() {
        var now = 1_000L
        val accumulator = Iap2NowPlayingAccumulator { now }
        val initial = accumulator.update(
            Iap2Messages.buildRaw(0x5001) {
                group(0) {
                    string(1, "Track")
                    u32(4, 180_000)
                    string(12, "Artist")
                }
                group(1) {
                    u8(0, 1)
                    u32(1, 12_000)
                    u32(2, 2)
                    u32(3, 10)
                    string(7, "Music")
                }
            },
        )
        now = 4_000L
        val paused = accumulator.update(
            Iap2Messages.buildRaw(0x5001) {
                group(1) { u8(0, 2) }
            },
        )

        assertEquals("Track", initial.title)
        assertEquals(Iap2PlaybackStatus.PLAYING, initial.status)
        assertEquals(2L, paused.queueIndex)
        assertEquals("Music", paused.appName)
        assertEquals(Iap2PlaybackStatus.PAUSED, paused.status)
        assertEquals(15_000L, paused.elapsedMillis)
        assertEquals(4_000L, paused.positionUpdateRealtimeMillis)
    }

    @Test
    fun nowPlayingUpdateParsesArtworkFileTransferIdentifier() {
        val accumulator = Iap2NowPlayingAccumulator { 0L }
        val withArtwork = accumulator.update(
            Iap2Messages.buildRaw(0x5001) {
                group(0) {
                    string(1, "Track")
                    u8(26, 7)
                }
            },
        )

        assertEquals(7, withArtwork.artworkFileTransferId)
    }

    @Test
    fun linkAndNowPlayingSubscriptionDeclareArtworkFileTransferCapability() {
        val sessions = Iap2LinkEngine().peerSynchronization().sessions
        val nowPlaying = Iap2ControlMessages.subscriptions().first()

        assertTrue(
            sessions.any {
                it.id == Iap2LinkEngine.FILE_TRANSFER_SESSION_ID && it.kind == 1 && it.version == 2
            },
        )
        assertTrue(Iap2BodyReader.of(nowPlaying).group(0).has(26))
    }

    @Test
    fun fileTransferAssemblesArtworkAndReceivesReusedIdentifier() {
        val handler = Iap2FileTransferHandler()
        val setup = byteArrayOf(
            7, 0x04,
            0, 0, 0, 0, 0, 0, 0, 5,
            0, 2,
        )

        assertArrayEquals(byteArrayOf(7, 0x01), handler.handle(setup).response)
        assertNull(handler.handle(byteArrayOf(7, 0x80.toByte(), 1, 2)).response)
        assertNull(handler.handle(byteArrayOf(7, 0x00, 3)).response)
        val completed = handler.handle(byteArrayOf(7, 0x40, 4, 5))
        assertArrayEquals(byteArrayOf(7, 0x05), completed.response)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), completed.artwork?.bytes)

        val reused = handler.handle(setup)
        assertArrayEquals(byteArrayOf(7, 0x01), reused.response)
        val replacement = handler.handle(byteArrayOf(7, 0xc0.toByte(), 5, 4, 3, 2, 1))
        assertArrayEquals(byteArrayOf(7, 0x05), replacement.response)
        assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1), replacement.artwork?.bytes)
    }

    @Test
    fun hidMediaRemoteUsesAdvertisedDescriptorAndPressReleaseReports() {
        val start = Iap2HidMessages.startMediaPlaybackRemote()
        val next = Iap2HidMessages.mediaReport(Iap2MediaRemoteCommand.NEXT)
        val release = Iap2HidMessages.releaseMediaButtons()

        assertEquals(0x6800, start.messageId)
        assertEquals(Iap2HidMessages.MEDIA_PLAYBACK_COMPONENT_ID, Iap2BodyReader.of(start).u16(0))
        assertArrayEquals(
            byteArrayOf(
                0x05, 0x0c, 0x09, 0x01, 0xa1.toByte(), 0x01, 0x15, 0x00,
                0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x04, 0x09, 0xb0.toByte(),
                0x09, 0xb1.toByte(), 0x09, 0xb5.toByte(), 0x09, 0xb6.toByte(),
                0x81.toByte(), 0x02, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(),
                0x03, 0xc0.toByte(),
            ),
            Iap2BodyReader.of(start).bytes(4),
        )
        assertEquals(0x6802, next.messageId)
        assertArrayEquals(byteArrayOf(4), Iap2BodyReader.of(next).bytes(1))
        assertArrayEquals(byteArrayOf(0), Iap2BodyReader.of(release).bytes(1))
        assertEquals(0x6803, Iap2HidMessages.stopMediaPlaybackRemote().messageId)
    }

    // ── DBPlay PATCH-0016: iAP2 navigation decoding for the Geely HUD bridge ──
    //
    // Rewritten by PATCH-0018. The stock Geely app proved the route-guidance
    // body is a **TLV stream**, not a CSM parameter list:
    //
    //     [u16 length][u16 id][value ...]   length includes its own 4-byte header
    //
    // These tests build that exact shape, so they would have caught the wrong
    // reader that the first revision shipped. The ids asserted here come from
    // the stock decoder and are the same constants the production reader uses.

    /** Builds one TLV entry: 4-byte header whose length includes the header. */
    private fun tlv(id: Int, value: ByteArray): ByteArray {
        val len = value.size + 4
        return byteArrayOf(
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
            ((id ushr 8) and 0xFF).toByte(),
            (id and 0xFF).toByte(),
        ) + value
    }

    private fun u16be(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /** Signed 16-bit, big-endian — the wire shape of every angle field. */
    private fun s16be(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /**
     * Builds one `0x5204` lane group — the inner TLV list carried by the outer id 2.
     *
     * Group parts (stock `Iap2LaneDecoder`): `0` position (2B), `1` status (1B),
     * `2` angles (signed 16-bit list), `3` highlight (signed 16-bit).
     */
    private fun laneGroup(
        position: Int,
        status: Int,
        angles: List<Int> = emptyList(),
        highlight: Int? = null,
    ): ByteArray {
        var body = tlv(0, u16be(position)) + tlv(1, byteArrayOf(status.toByte()))
        if (angles.isNotEmpty()) body += tlv(2, angles.flatMap { s16be(it).asIterable() }.toByteArray())
        if (highlight != null) body += tlv(3, s16be(highlight))
        return tlv(2, body)
    }

    private fun u32be(v: Long) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun u64be(v: Long) = ByteArray(8) { i -> ((v ushr (8 * (7 - i))) and 0xFF).toByte() }

    private fun routeFrame(vararg tlvs: ByteArray) = Iap2Frame(0x5201, tlvs.reduce { a, b -> a + b })

    @Test
    fun navigationAccumulatorMergesIncrementallyAndKeepsStaleFields() {
        val accumulator = Iap2NavigationAccumulator()
        // 0x5201: id 3 = 当前道路(文本), id 4 = 目的地(文本),
        //         id 6 = 剩余时间(8B), id 7 = 路线剩余距离(4B),
        //         id 10(0xa) = 到下一机动距离(4B).
        // 注意 0x5201 **没有** id 0 的 42 标记 —— 那个标记只属于 0x5204。
        val first = accumulator.update(
            routeFrame(
                tlv(7, u32be(12_400)),
                tlv(6, u64be(900)),
                tlv(10, u32be(350)),
                tlv(3, "人民路".toByteArray()),
                tlv(4, "解放路".toByteArray()),
            ),
        )
        assertEquals(12_400, first.routeMeters)
        assertEquals(900, first.routeSeconds)
        assertEquals(350, first.segmentMeters)
        assertEquals("人民路", first.currentRoad)
        assertEquals("解放路", first.destination)
        assertTrue(first.hasContent)

        // A later frame that only reports the shrinking distance must keep the
        // road names from the previous frame.
        val second = accumulator.update(
            routeFrame(tlv(7, u32be(9_800))),
        )
        assertEquals(9_800, second.routeMeters)
        assertEquals(900, second.routeSeconds)
        assertEquals("人民路", second.currentRoad)
        assertEquals("解放路", second.destination)
    }

    @Test
    fun maneuverUpdateMergesIntoRouteStateAndKeepsNestingOptional() {
        val accumulator = Iap2NavigationAccumulator()
        accumulator.update(
            routeFrame(tlv(7, u32be(5_000)), tlv(6, u64be(300))),
        )
        // 0x5201: id 13(0xd) = 当前机动索引(2B). 原版流程是 0x5201 先声明
        // "当前看第几条", 0x5202 再送那条的内容。
        accumulator.update(
            routeFrame(tlv(7, u32be(5_000)), tlv(6, u64be(300)), tlv(13, u16be(4))),
        )
        // 0x5202: id 1 = 该机动的索引(2B), id 2 = 描述(文本), id 3 = 类型,
        //         id 4 = 机动后道路(文本).
        // PATCH-0039 校正了这张表：旧实现把 id 2 当成 afterRoad，实际 id 2 是描述，
        // afterRoad 是 id 4 —— 所以 `maneuverDescription` 以前恒为 null。
        val nested = accumulator.update(
            Iap2Frame(
                0x5202,
                tlv(1, u16be(4)) +
                    tlv(2, "右转进入解放路".toByteArray()) +
                    tlv(3, u16be(22)) +
                    tlv(4, "解放路".toByteArray()),
            ),
        )
        assertEquals(22, nested.maneuverType)
        assertEquals(4, nested.maneuverIndex)
        assertEquals("右转进入解放路", nested.maneuverDescription)
        assertEquals("解放路", nested.nextRoad)
        // Route-level fields survive a manoeuvre-only frame.
        assertEquals(5_000, nested.routeMeters)
        assertEquals(300, nested.routeSeconds)

        // A manoeuvre frame with no type cannot update the manoeuvre, but must
        // not throw or wipe the accumulated route fields either.
        val noType = accumulator.update(
            Iap2Frame(0x5202, tlv(1, u16be(5))),
        )
        assertEquals(5_000, noType.routeMeters)
        assertEquals(22, noType.maneuverType)
        // 一条没被 0x5201 指名的记录不会变成当前机动。
        assertEquals(4, noType.maneuverIndex)
    }

    @Test
    fun navigationAccumulatorIgnoresForeignAndMalformedFramesWithoutThrowing() {
        val accumulator = Iap2NavigationAccumulator()

        // A NowPlaying frame is not navigation: state stays empty.
        val afterNowPlaying = accumulator.update(
            Iap2Messages.buildRaw(0x5001) { group(0) { string(1, "Track") } },
        )
        assertFalse(afterNowPlaying.hasContent)

        // A TLV whose declared length is shorter than its own header is
        // malformed; the stock reader discards the whole frame, so must we.
        val afterMalformed = accumulator.update(
            Iap2Frame(0x5201, byteArrayOf(0, 2, 0, 7, 1)),
        )
        assertFalse(afterMalformed.hasContent)

        // A TLV that overruns the buffer is equally malformed.
        val afterOverrun = accumulator.update(
            Iap2Frame(0x5201, byteArrayOf(0, 0x40, 0, 7, 9, 9)),
        )
        assertFalse(afterOverrun.hasContent)

        // State from a good frame survives a following malformed one.
        accumulator.update(routeFrame(tlv(7, u32be(4_200))))
        val afterGoodThenBad = accumulator.update(
            Iap2Frame(0x5201, byteArrayOf(0, 1)),
        )
        assertEquals(4_200, afterGoodThenBad.routeMeters)

        assertEquals(
            CarNavigationState(),
            accumulator.reset(),
        )
    }

    // ── 0x5204 lane guidance (PATCH-0039 decodes it; PATCH-0018 only declared it) ──

    /** Builds one `0x5204` frame carrying a single lane group with `eventId`. */
    private fun laneFrame(eventId: Int, vararg groups: ByteArray, instruction: String? = null): Iap2Frame {
        var body = tlv(0, u16be(42)) + tlv(1, u16be(eventId))
        if (instruction != null) body += tlv(3, instruction.toByteArray())
        for (g in groups) body += g
        return Iap2Frame(0x5204, body)
    }

    /** Builds the `0x5201` pointer that makes the phone's lane group visible. */
    private fun lanePointer(eventId: Int, visible: Boolean = true): Iap2Frame =
        routeFrame(tlv(0x10, u16be(eventId)), tlv(0x12, byteArrayOf(if (visible) 1 else 0)))

    @Test
    fun laneGuidanceDecodesTheWholeGroupAndKeepsTheEventId() {
        assertTrue(Iap2NavigationAccumulator.isNavigationMessage(0x5204))
        val accumulator = Iap2NavigationAccumulator()

        val shown = accumulator.update(
            laneFrame(
                7,
                laneGroup(position = 0, status = 0, angles = listOf(-45), highlight = -45),
                laneGroup(position = 1, status = 1, angles = listOf(0)),
                instruction = "走左侧两车道",
            ),
        )
        // 还没有 0x10 时，直接采用这一帧的车道组。
        assertEquals(7, shown.laneEventId)
        assertEquals(2, shown.lanes.size)

        val state = accumulator.update(lanePointer(7))

        assertEquals(7, state.laneEventId)
        assertEquals("走左侧两车道", state.laneInstruction)
        // 整组保留并按 position 排序（原版 `sortedBy { index }`），
        // 旧实现把 id 1 误读成选中下标，只会留下一条。
        assertEquals(2, state.lanes.size)
        assertEquals(listOf(0, 1), state.lanes.map { it.index })
        // 角度 = (listOfNotNull(highlight) + angles).distinct()，highlight 前置且去重。
        assertEquals(listOf(-45), state.lanes[0].angles)
        assertEquals(-45, state.lanes[0].highlightAngle)
        assertEquals(listOf(0), state.lanes[1].angles)
        assertNull(state.lanes[1].highlightAngle)
        assertTrue(state.hasContent)
    }

    @Test
    fun laneVisibilityFollowsThePointerAndTheFlag() {
        val accumulator = Iap2NavigationAccumulator()

        accumulator.update(laneFrame(3, laneGroup(position = 0, status = 1)))
        accumulator.update(laneFrame(9, laneGroup(position = 0, status = 1)))

        // 指向 3 ⇒ 显示 3。
        assertEquals(3, accumulator.update(lanePointer(3)).laneEventId)
        // 指向未收到过的 event ⇒ 无车道可显示（不是显示上一次的）。
        assertTrue(accumulator.update(lanePointer(4)).lanes.isEmpty())
        // 指回 9 ⇒ 组还在，重新显示。
        assertEquals(9, accumulator.update(lanePointer(9)).laneEventId)
        // 0x12 不再当显示开关。领克实车上它不是“是否画车道”。
        assertEquals(9, accumulator.update(lanePointer(9, visible = false)).laneEventId)
        assertEquals(1, accumulator.update(lanePointer(9, visible = false)).lanes.size)
        // 0x10 = 0xffff 是"无选中"哨兵值。
        assertEquals(9, accumulator.update(lanePointer(9)).laneEventId)
        assertTrue(accumulator.update(routeFrame(tlv(0x10, u16be(0xFFFF)))).lanes.isEmpty())
        // 只送 0x12 不改选中项；此时选中项已被 0xffff 清掉 ⇒ 仍不显示。
        assertTrue(accumulator.update(routeFrame(tlv(0x12, byteArrayOf(1)))).lanes.isEmpty())

        // 清路会把车道存储一并清掉，之后再指回旧 event 也没东西可显示。
        accumulator.update(lanePointer(9))
        assertEquals(9, accumulator.update(lanePointer(9)).laneEventId)
        assertTrue(accumulator.update(routeFrame(tlv(0x14, byteArrayOf(0)))).lanes.isEmpty())
        assertTrue(accumulator.update(lanePointer(9)).lanes.isEmpty())
    }

    @Test
    fun laneGuidanceRejectsMalformedGroupsWithoutTouchingState() {
        val accumulator = Iap2NavigationAccumulator()
        accumulator.update(routeFrame(tlv(7, u32be(1_000))))
        accumulator.update(lanePointer(7))

        fun lanesOf(vararg tlvs: ByteArray): List<*> =
            accumulator.update(Iap2Frame(0x5204, tlvs.reduce { a, b -> a + b })).lanes

        // 缺 id 0 标记
        assertTrue(lanesOf(tlv(1, u16be(7)), laneGroup(0, 0)).isEmpty())
        // 标记值不是 42
        assertTrue(lanesOf(tlv(0, u16be(41)), tlv(1, u16be(7)), laneGroup(0, 0)).isEmpty())
        // 标记宽度不是 2
        assertTrue(lanesOf(tlv(0, byteArrayOf(42)), tlv(1, u16be(7)), laneGroup(0, 0)).isEmpty())
        // 缺 event id
        assertTrue(lanesOf(tlv(0, u16be(42)), laneGroup(0, 0)).isEmpty())
        // 没有任何车道组
        assertTrue(lanesOf(tlv(0, u16be(42)), tlv(1, u16be(7))).isEmpty())
        // 两条车道抢同一个 position ⇒ 整帧拒绝（原版 distinctBy 检查）
        assertTrue(
            lanesOf(tlv(0, u16be(42)), tlv(1, u16be(7)), laneGroup(0, 0), laneGroup(0, 1)).isEmpty(),
        )
        // 组内缺 status
        assertTrue(
            lanesOf(tlv(0, u16be(42)), tlv(1, u16be(7)), tlv(2, tlv(0, u16be(0)))).isEmpty(),
        )
    }

    // ── route lifecycle (PATCH-0039: clear / grace / source change / staleness) ──

    @Test
    fun routeSourceChangeWipesTheRouteButACosmeticRenameDoesNot() {
        val accumulator = Iap2NavigationAccumulator()
        fun route(vararg tlvs: ByteArray) = accumulator.update(routeFrame(*tlvs))

        // id 0x13 = source。首次出现只是记录，不触发清空。
        assertEquals(9_000, route(tlv(0x13, "高德地图".toByteArray()), tlv(7, u32be(9_000))).routeMeters)

        // 同一个 source，只是多了空白 ⇒ `sourceKey` 折叠后相同，不清空。
        assertEquals(
            8_000,
            route(tlv(0x13, " 高德地图 ".toByteArray()), tlv(7, u32be(8_000))).routeMeters,
        )

        // 换成 Apple Maps ⇒ 整条路线作废，但同一帧的字段照常写入。
        assertEquals(
            7_000,
            route(tlv(0x13, "Apple Maps".toByteArray()), tlv(7, u32be(7_000))).routeMeters,
        )
    }

    @Test
    fun guidanceStateTwoClearsImmediatelyAndZeroArmsTheGraceWindow() {
        var now = 0L
        val accumulator = Iap2NavigationAccumulator { now }
        fun route(vararg tlvs: ByteArray) = accumulator.update(routeFrame(*tlvs))

        // id 1 = guidanceState。先起一条活路线。
        assertEquals(5_000, route(tlv(1, byteArrayOf(1)), tlv(7, u32be(5_000))).routeMeters)

        // 2 = 立即结束，且同帧其余字段被丢弃。
        assertNull(route(tlv(1, byteArrayOf(2)), tlv(7, u32be(6_000))).routeMeters)

        // 重新起一条路线，再用 0 触发宽限。
        assertEquals(5_000, route(tlv(1, byteArrayOf(1)), tlv(7, u32be(5_000))).routeMeters)
        assertEquals(5_000, route(tlv(1, byteArrayOf(0))).routeMeters)

        // 宽限未到（< 3 s）仍保留。
        now += 2_000_000_000L
        assertEquals(4_900, route(tlv(7, u32be(4_900))).routeMeters)

        // 超过 3 s ⇒ 宽限到期，路线被丢掉。
        now += 2_000_000_000L
        assertNull(route(tlv(7, u32be(4_800))).routeMeters)

        // guidanceState > 0 会取消待结束：之后再晚也不会被宽限规则清掉。
        assertEquals(4_000, route(tlv(1, byteArrayOf(1)), tlv(7, u32be(4_000))).routeMeters)
        now += 5_000_000_000L
        assertEquals(3_900, route(tlv(7, u32be(3_900))).routeMeters)
    }

    @Test
    fun explicitClearFlagWipesTheRoute() {
        val accumulator = Iap2NavigationAccumulator()
        fun route(vararg tlvs: ByteArray) = accumulator.update(routeFrame(*tlvs))

        assertEquals(5_000, route(tlv(1, byteArrayOf(1)), tlv(7, u32be(5_000))).routeMeters)
        // id 0x14 值 0 = 显式清空，同帧其余字段被丢弃。
        assertNull(route(tlv(0x14, byteArrayOf(0)), tlv(7, u32be(1_000))).routeMeters)
    }

    @Test
    fun finalEndFrameExpiresWithoutAnotherIncomingMessage() {
        var now = 0L
        val accumulator = Iap2NavigationAccumulator { now }
        accumulator.update(routeFrame(tlv(1, byteArrayOf(1)), tlv(7, u32be(5000))))
        accumulator.update(routeFrame(tlv(1, byteArrayOf(0))))
        assertTrue(accumulator.hasPendingEnd())
        now = 2_000_000_000L
        assertEquals(5000, accumulator.expirePendingEnd().routeMeters)
        now = 3_100_000_000L
        assertFalse(accumulator.expirePendingEnd().hasContent)
        assertFalse(accumulator.hasPendingEnd())
    }

    @Test
    fun resumedRouteCancelsPendingEndTimer() {
        var now = 0L
        val accumulator = Iap2NavigationAccumulator { now }
        accumulator.update(routeFrame(tlv(1, byteArrayOf(1)), tlv(7, u32be(5000))))
        accumulator.update(routeFrame(tlv(1, byteArrayOf(0))))
        now = 1_000_000_000L
        accumulator.update(routeFrame(tlv(1, byteArrayOf(1)), tlv(7, u32be(4900))))
        now = 4_000_000_000L
        assertFalse(accumulator.hasPendingEnd())
        assertEquals(4900, accumulator.expirePendingEnd().routeMeters)
    }

    @Test
    fun routeGoesStaleAfterTwoMinutesWithoutARefreshField() {
        var now = 0L
        val accumulator = Iap2NavigationAccumulator { now }
        fun route(vararg tlvs: ByteArray) = accumulator.update(routeFrame(*tlvs))

        assertEquals(5_000, route(tlv(1, byteArrayOf(1)), tlv(7, u32be(5_000))).routeMeters)

        // 走满 120 s 之后送一帧**不含**任何 ROUTE_REFRESH_IDS 的 id（这里是 id 2），
        // 路线被判过期；若换成 id 7（在刷新集合里）则不会过期。
        now += 121_000_000_000L
        assertNull(route(tlv(2, u16be(0))).routeMeters)
    }

    @Test
    fun aTruncatedManeuverIndexListDropsTheRestOfTheFrame() {
        val accumulator = Iap2NavigationAccumulator()
        // id 0xd 存在但宽度不足 2 字节 ⇒ 原版视作"机动列表还是空的"，
        // 本帧其余字段（这里是 id 7）被丢弃。
        val state = accumulator.update(
            routeFrame(tlv(0xd, byteArrayOf(0)), tlv(7, u32be(1_000))),
        )
        assertNull(state.routeMeters)
        assertFalse(state.hasContent)
    }
}
