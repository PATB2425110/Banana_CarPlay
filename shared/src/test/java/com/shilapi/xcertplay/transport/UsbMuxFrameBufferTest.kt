package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UsbMuxFrameBuffer` 的行为钉子（2026-10-08 移植 DiPlay 时补）。
 *
 * 这里的每一条都对应一个**在现场真实发生过**、而且表现成"莫名超时/断流"的形态：
 * 没有单测时，它们只会在实车上以"这次又连不上了"的形式出现，无法回归。
 */
class UsbMuxFrameBufferTest {

    @Test
    fun versionReplyFollowedByPaddedTcpReplyIsRecovered() {
        val buffer = UsbMuxFrameBuffer()
        buffer.append(versionFrame())
        buffer.append(tcpFrameWithOptionalPadding())

        val version = buffer.takeFrame()
        assertNotNull(version)
        assertEquals(0, version!!.protocol)
        assertEquals(20, version.length)

        // 那 4 个字节**不该**把后面的合法 TCP 帧一起毁掉。
        val tcp = buffer.takeFrame()
        assertNotNull(tcp)
        assertEquals(PROTOCOL_TCP, tcp!!.protocol)
        assertEquals(TCP_FRAME_BYTES, tcp.length)
        assertEquals(TCP_FRAME_BYTES - HEADER_BYTES, tcp.payload.size)
    }

    @Test
    fun aNormalFrameRightAfterTheVersionReplyIsNotMistakenForPadding() {
        val buffer = UsbMuxFrameBuffer()
        buffer.append(versionFrame())
        buffer.append(muxFrame(PROTOCOL_TCP, ByteArray(20) { 0x11 }))

        assertNotNull(buffer.takeFrame())
        val tcp = buffer.takeFrame()
        assertNotNull(tcp)
        assertEquals(PROTOCOL_TCP, tcp!!.protocol)
        assertEquals(20, tcp.payload.size)
    }

    @Test
    fun aFrameSplitAcrossTwoReadsIsReassembled() {
        val buffer = UsbMuxFrameBuffer()
        val frame = muxFrame(PROTOCOL_TCP, ByteArray(32) { 0x22 })
        buffer.append(frame.copyOfRange(0, 8))
        assertNull("头都没攒齐，不能出帧", buffer.takeFrame())

        buffer.append(frame.copyOfRange(8, frame.size))
        val taken = buffer.takeFrame()
        assertNotNull(taken)
        assertEquals(32, taken!!.payload.size)
    }

    @Test
    fun anIllegalLengthWithNoEligiblePaddingIsRejected() {
        val buffer = UsbMuxFrameBuffer()
        // 协议 6 + 长度 5（< 16）——而且上一条回复**不是**"可能带 padding"的那两种，
        // 所以没有任何恢复余地，必须如实报错。
        val broken = ByteArray(16)
        putU32(broken, 0, PROTOCOL_TCP)
        putU32(broken, 4, 5)
        buffer.append(broken)

        val error = assertThrows(IphoneUsbException.Protocol::class.java) { buffer.takeFrame() }
        assertTrue(error.message!!.contains("Invalid USBMUX frame length"))
    }

    @Test
    fun paddingIsOnlySkippedOnceSoRepeatedRecoveryCannotLoop() {
        val buffer = UsbMuxFrameBuffer()
        buffer.append(versionFrame())
        assertNotNull(buffer.takeFrame())
        // 第二条：这次偏移 0 处就是一个**合法**头 ⇒ 走正常路径，不再跳 4 字节。
        buffer.append(muxFrame(PROTOCOL_TCP, ByteArray(8) { 0x33 }))
        val taken = buffer.takeFrame()
        assertNotNull(taken)
        assertEquals(8, taken!!.payload.size)
    }

    // ---------------------------------------------------------------- 构造

    private fun versionFrame(): ByteArray = muxFrame(PROTOCOL_VERSION, ByteArray(4)).also {
        putU32(it, 8, 2) // word8 = USBMUX version 2
    }

    private fun muxFrame(protocol: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_BYTES + payload.size)
        putU32(frame, 0, protocol)
        putU32(frame, 4, frame.size)
        putU32(frame, 8, MUX_MAGIC)
        payload.copyInto(frame, HEADER_BYTES)
        return frame
    }

    /** 4 字节 padding + 一个 word8 = CAPTURED_REPLY_MAGIC 的合法 TCP 帧。 */
    private fun tcpFrameWithOptionalPadding(): ByteArray {
        val tcp = ByteArray(TCP_FRAME_BYTES)
        putU32(tcp, 0, PROTOCOL_TCP)
        putU32(tcp, 4, TCP_FRAME_BYTES)
        putU32(tcp, 8, CAPTURED_REPLY_MAGIC)
        // TCP 头在帧内偏移 16 起：源端口 / 目的端口必须非 0，数据偏移必须是 5（20 字节）。
        putU16(tcp, HEADER_BYTES, 0x1234)
        putU16(tcp, HEADER_BYTES + 2, 0x5678)
        tcp[HEADER_BYTES + 12] = 0x50
        return ByteArray(4) + tcp
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private companion object {
        const val HEADER_BYTES = 16
        const val TCP_FRAME_BYTES = 16 + 20 + 4
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_TCP = 6
        val MUX_MAGIC = 0xfeedface.toInt()
        val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
    }
}
