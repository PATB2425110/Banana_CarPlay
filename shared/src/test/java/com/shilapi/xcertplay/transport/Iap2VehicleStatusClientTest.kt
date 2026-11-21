package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Iap2VehicleStatusClientTest {
    private class Source : Iap2VehicleStatusProvider {
        var value = Iap2VehicleStatusSnapshot(480, -12, false, true)
        var opens = 0
        var closes = 0
        var selected: Set<Int> = emptySet()
        var failRead = false
        var failingOpens = 0
        override fun open(fields: Set<Int>): Iap2VehicleStatusSubscription {
            selected = fields
            opens++
            if (failingOpens-- > 0) throw IllegalStateException("temporarily unavailable")
            return object : Iap2VehicleStatusSubscription {
                override fun latest(): Iap2VehicleStatusSnapshot {
                    if (failRead) throw IllegalStateException("read failed")
                    return value
                }
                override fun close() { closes++ }
            }
        }
        override fun close() {}
    }
    private class Harness(val source: Source = Source()) {
        var now = 0L
        val sent = mutableListOf<Iap2Frame>()
        val client = Iap2VehicleStatusClient(source, { sent += it }, nowMillis = { now })
    }
    private fun start(vararg ids: Int): Iap2Frame =
        Iap2BodyBuilder.opaque(0xa100).apply { ids.forEach { void(it) } }.build()
    private fun fields(frame: Iap2Frame) = Iap2ParameterList.parse(frame.payload).asList()
    private fun ids(frame: Iap2Frame) = fields(frame).map { it.id }

    @Test fun firstSnapshotHasExactFieldWidthsSignedTemperatureAndByteOrder() {
        val h = Harness()
        assertTrue(h.client.accept(start(0, 1, 2, 3)))
        val frame = h.sent.single()
        assertEquals(0xa101, frame.messageId)
        val values = fields(frame).associate { it.id to it.payload }
        assertArrayEquals(byteArrayOf(1, 0xe0.toByte()), values[0])
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xf4.toByte()), values[1])
        assertArrayEquals(byteArrayOf(0), values[2])
        assertArrayEquals(byteArrayOf(1), values[3])
        assertEquals(setOf(0, 1, 2, 3), h.source.selected)
    }

    @Test fun noRequestMeansNoReadLeaseOrSend() {
        val h = Harness()
        repeat(3) { h.client.poll() }
        assertEquals(0, h.source.opens)
        assertTrue(h.sent.isEmpty())
    }

    @Test fun onlyRequestedFieldsAndChangedValuesAreSent() {
        val h = Harness()
        h.client.accept(start(0, 3))
        h.client.poll()
        assertEquals(1, h.sent.size)
        h.source.value = h.source.value.copy(outsideTemperatureC = 20, rangeWarning = true)
        h.client.poll()
        assertEquals(1, h.sent.size)
        h.source.value = h.source.value.copy(rangeKm = 479)
        h.client.poll()
        assertEquals(listOf(0), ids(h.sent.last()))
        assertArrayEquals(byteArrayOf(1, 0xdf.toByte()), fields(h.sent.last()).single().payload)
    }

    @Test fun duplicateSelectorsDeduplicateAndUnknownSelectorsDoNotBecomeAllFields() {
        val h = Harness()
        h.client.accept(start(3, 3, 99))
        assertEquals(setOf(3), h.source.selected)
        assertEquals(listOf(3), ids(h.sent.single()))
    }

    @Test fun repeatedStartImmediatelyReplaysCurrentSnapshotWithoutRestartingSource() {
        val h = Harness()
        h.client.accept(start(0))
        h.client.accept(start(0))
        assertEquals(2, h.sent.size)
        assertEquals(1, h.source.opens)
        assertEquals(0, h.source.closes)
    }

    @Test fun newSelectorsReplaceOldSubscription() {
        val h = Harness()
        h.client.accept(start(0))
        h.client.accept(start(1))
        assertEquals(2, h.source.opens)
        assertEquals(1, h.source.closes)
        assertEquals(listOf(1), ids(h.sent.last()))
        h.source.value = h.source.value.copy(rangeKm = 400)
        h.client.poll()
        assertEquals(2, h.sent.size)
    }

    @Test fun stopImmediatelyClosesLeaseAndPreventsFurtherReporting() {
        val h = Harness()
        h.client.accept(start(0))
        h.client.accept(Iap2Frame(0xa102, byteArrayOf()))
        h.source.value = h.source.value.copy(rangeKm = 1)
        h.client.poll()
        assertFalse(h.client.active)
        assertEquals(1, h.source.closes)
        assertEquals(1, h.sent.size)
        h.client.close()
        h.client.close()
        assertEquals(1, h.source.closes)
    }

    @Test fun unknownInitialSnapshotIsEmptyNotFabricatedZero() {
        val h = Harness()
        h.source.value = Iap2VehicleStatusSnapshot()
        h.client.accept(start(0, 1, 2, 3))
        assertTrue(fields(h.sent.single()).isEmpty())
        h.client.poll()
        assertEquals(1, h.sent.size)
        h.source.value = Iap2VehicleStatusSnapshot(rangeKm = 0)
        h.client.poll()
        assertEquals(listOf(0), ids(h.sent.last()))
    }

    @Test fun recoveryOfSameValueAfterUnavailableIsReportedAgain() {
        val h = Harness()
        h.client.accept(start(0))
        h.source.value = Iap2VehicleStatusSnapshot()
        h.client.poll()
        assertEquals(1, h.sent.size)
        h.source.value = Iap2VehicleStatusSnapshot(rangeKm = 480)
        h.client.poll()
        assertEquals(2, h.sent.size)
    }

    @Test fun invalidWidthValuesAreOmittedNotClampedOrWrapped() {
        val h = Harness()
        h.source.value = Iap2VehicleStatusSnapshot(-1, 32768, true, false)
        h.client.accept(start(0, 1, 2, 3))
        assertEquals(listOf(2, 3), ids(h.sent.single()))
        h.source.value = Iap2VehicleStatusSnapshot(65536, -32769)
        h.client.poll()
        assertEquals(1, h.sent.size)
    }

    @Test fun malformedOrNonVoidStartDoesNotDestroyValidSubscription() {
        val h = Harness()
        h.client.accept(start(0))
        h.client.accept(Iap2BodyBuilder.opaque(0xa100).u8(3, 1).build())
        h.client.accept(Iap2Frame(0xa100, byteArrayOf(0, 4, 0)))
        assertEquals(1, h.source.opens)
        assertEquals(0, h.source.closes)
        assertTrue(h.client.active)
    }

    @Test fun sourceFailureDoesNotInventStateOrEscapeIntoControlLoop() {
        val h = Harness()
        h.source.failRead = true
        h.client.accept(start(0, 2))
        h.client.poll()
        assertTrue(h.sent.single().payload.isEmpty())
        h.source.failRead = false
        h.client.poll()
        assertEquals(listOf(0, 2), ids(h.sent.last()))
    }

    @Test fun channelClosureDoesNotCloseAnotherChannelsLease() {
        val source = Source()
        val a = Iap2VehicleStatusClient(source, {})
        val bSent = mutableListOf<Iap2Frame>()
        val b = Iap2VehicleStatusClient(source, { bSent += it })
        a.accept(start(0))
        b.accept(start(0))
        a.close()
        source.value = source.value.copy(rangeKm = 450)
        b.poll()
        assertTrue(b.active)
        assertEquals(2, bSent.size)
        b.close()
        assertEquals(2, source.closes)
    }

    @Test fun transportSendFailureIsNotHiddenAsSuccessfulDelivery() {
        val client = Iap2VehicleStatusClient(Source(), { throw IOException("closed") })
        try {
            client.accept(start(0))
            fail("send should fail")
        } catch (_: IOException) {
            client.close()
        }
        assertFalse(client.active)
    }

    @Test fun emptySelectionHasNoAllFieldsFallback() {
        val h = Harness()
        h.client.accept(start())
        assertEquals(0, h.source.opens)
        assertTrue(h.sent.single().payload.isEmpty())
        assertFalse(h.client.active)
    }

    @Test fun absentProviderDoesNotSubscribeOrSend() {
        val sent = mutableListOf<Iap2Frame>()
        val client = Iap2VehicleStatusClient(null, { sent += it })
        assertTrue(client.accept(start(0)))
        assertTrue(sent.isEmpty())
        assertFalse(client.accept(Iap2Frame(0x5201, byteArrayOf())))
    }

    @Test fun identificationAddsVehicleMessagesOnlyWhenEnabledAndKeepsExistingCapabilities() {
        val base = Iap2IdentificationConfig("a", "b", "c", "d", "e", "f", 3)
        for (wireless in listOf(false, true)) {
            val config = if (wireless) base.copy(
                wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "test"),
            ) else base
            val off = Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload)
            val on = Iap2ParameterList.parse(
                Iap2IdentificationClient.identificationInformation(config.copy(
                    vehicleStatusUpdatesEnabled = true, locationInformationEnabled = true,
                )).payload,
            )
            fun messages(list: Iap2ParameterList, id: Int): Set<Int> {
                val bytes = list.require(id).payload
                return bytes.indices.step(2).map { Iap2WireCodec.readU16(bytes, it) }.toSet()
            }
            assertFalse(0xa101 in messages(off, 6))
            assertTrue(0xa101 in messages(on, 6))
            assertFalse(0xa100 in messages(on, 6))
            assertFalse(0xa102 in messages(on, 6))
            assertFalse(0xa101 in messages(on, 7))
            assertTrue(setOf(0xa100, 0xa102).all { it in messages(on, 7) })
            assertTrue(messages(on, 6).containsAll(messages(off, 6)))
            assertTrue(messages(on, 7).containsAll(messages(off, 7)))
            assertTrue(0xfffb in messages(on, 6))
            assertTrue(0x5201 in messages(on, 7))
        }
    }

    @Test fun deferredMfiRequestsAreBoundedAndDoNotSubscribeBeforeAuthentication() {
        val h = Harness()
        val pending = PendingVehicleStatusRequest()
        repeat(100) { pending.accept(start(it % 4)) }
        assertEquals(0, h.source.opens)
        assertTrue(h.sent.isEmpty())
        h.client.accept(pending.take()!!)
        assertEquals(setOf(3), h.source.selected)
        assertNull(pending.take())
    }

    @Test fun deferredStopWinsAndMalformedStartCannotEraseValidRequest() {
        val pending = PendingVehicleStatusRequest()
        pending.accept(start(0))
        pending.accept(Iap2Frame(0xa100, byteArrayOf(0)))
        assertEquals(listOf(0), ids(pending.take()!!))
        pending.accept(start(0))
        pending.accept(Iap2Frame(0xa102, byteArrayOf()))
        assertEquals(0xa102, pending.take()!!.messageId)
        assertFalse(pending.accept(Iap2Frame(0xaa04, byteArrayOf())))
        assertFalse(pending.accept(Iap2Frame(0x5201, byteArrayOf())))
    }

    @Test fun failedOpenRecoversForExistingSubscriptionWithThrottledRetries() {
        val h = Harness()
        h.source.failingOpens = 1
        h.client.accept(start(0))
        assertTrue(h.client.active)
        assertTrue(h.sent.single().payload.isEmpty())
        repeat(10) { h.client.poll() }
        assertEquals(1, h.source.opens)
        h.now = 30_000
        h.client.poll()
        assertEquals(2, h.source.opens)
        assertEquals(listOf(0), ids(h.sent.last()))
    }

    @Test fun stopAndCloseCancelFailedOpenRetries() {
        for (close in listOf(false, true)) {
            val h = Harness()
            h.source.failingOpens = 1
            h.client.accept(start(0))
            if (close) h.client.close() else h.client.accept(Iap2Frame(0xa102, byteArrayOf()))
            h.now = 100_000
            h.client.poll()
            assertEquals(1, h.source.opens)
            assertFalse(h.client.active)
        }
    }

    @Test fun arbitraryNegativeMonotonicClockOriginDoesNotPreventInitialOpen() {
        val h = Harness()
        h.now = -100_000
        h.client.accept(start(0))
        assertEquals(1, h.source.opens)
        assertEquals(listOf(0), ids(h.sent.single()))
    }

    @Test fun repeatedSameSelectorsCannotBypassFailedOpenBackoff() {
        val h = Harness()
        h.source.failingOpens = 1
        h.client.accept(start(0))
        h.now = 1
        repeat(5) { h.client.accept(start(0)) }
        assertEquals(1, h.source.opens)
        assertEquals(6, h.sent.size) // Immediate unknown snapshot is still answered each time.
        assertTrue(h.sent.all { it.payload.isEmpty() })
        h.now = 30_000
        h.client.accept(start(0))
        assertEquals(2, h.source.opens)
        assertEquals(listOf(0), ids(h.sent.last()))
    }
}
