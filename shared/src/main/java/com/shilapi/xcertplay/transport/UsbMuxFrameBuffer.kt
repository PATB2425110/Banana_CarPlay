package com.shilapi.xcertplay.transport

internal data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val word8: Int,
    val sequence: Int,
    val payload: ByteArray,
)

/**
 * USBMUX 的**增量**分帧器。
 *
 * ## 为什么从 `Iap2UsbMuxHost` 里抽出来
 *
 * 2026-10-08 移植自 DiPlay（`shared/.../transport/UsbMuxFrameBuffer.kt`）。
 * 原先这段逻辑内联在 host 里，只有"攒够 16 字节 → 读长度 → 够长就出一帧"三行，
 * 于是有两个在现场真实发生、但**只表现为"莫名超时/断流"**的缺陷：
 *
 * 1. **声明的长度非法就直接抛**。iPhone 的某些回复（实测 iOS 27 的 VERSION、
 *    以及带 payload 的合法 TCP 回复）在帧尾**多出 4 个字节**。旧的判据
 *    `length < 16 || length > 65536` 撞上这 4 字节时读到的是一个荒唐的长度，
 *    于是抛 `Invalid USBMUX frame length`，整条 USBMUX 链路就地死掉
 *    （DiPlay issue #100 / #114 / #298，以及 v0.2.14 的 #362）。
 * 2. **失败时会丢掉已经读到的剩余数据**。旧的 `takeFrame` 在抛之前不做保留，
 *    一次误判就把后面**本来完好的**帧一起吞了。
 *
 * 现在把它做成一个纯字节流状态机：只做"攒、判、切"，不碰任何 USB 或线程，
 * 于是可以脱离 Android 单测（见 `UsbMuxFrameBufferTest`）。
 *
 * ## 那 4 个字节怎么处理（刻意保守）
 *
 * **不做扫描、不做模糊匹配**：只在**上一条回复本身"可能有尾随 padding"**时，
 * 才允许在偏移 0 处跳过恰好 4 字节，而且跳之前必须确认跳过后的那个头
 * **是一个合法的 MUX/TCP 头**（协议号、长度、TCP 头长、端口都合规），
 * 或者是一个**形状完全符合**协议 1 诊断载荷的帧。
 *
 * 这样做的理由：一个"拆分到达"的正常帧，它的头必然就在偏移 0 —— 上面那条
 * "偏移 0 是合法 MUX/TCP 头"的判据会**直接放行**，不会被误跳。
 * 只有"偏移 0 的头明显不合法、偏移 4 的头完全合法"时才认定是 padding。
 *
 * ## 调用约定
 *
 * 调用方（`Iap2UsbMuxHost`）负责用**自己的状态锁**把 [append] / [takeFrame]
 * 串行化——本类**自身不加锁**。
 */
internal class UsbMuxFrameBuffer(private val diagnostic: (String) -> Unit = {}) {
    private var bytes = ByteArray(0)
    private var optionalReplyPadding: UsbMuxFrame? = null
    private var paddingReports = 0
    private var lastUsbReadBytes = 0
    val bufferedBytes: Int get() = bytes.size

    fun append(transfer: ByteArray) {
        lastUsbReadBytes = transfer.size
        bytes += transfer
    }

    fun takeFrame(): UsbMuxFrame? {
        if (bytes.size < HEADER_BYTES) return null
        var length = readU32(bytes, 4)
        if (length !in HEADER_BYTES..MAX_FRAME_BYTES) {
            val previous = optionalReplyPadding
            // 抓包实证：iOS 27 的 VERSION 回复、以及合法的 RX TCP 回复（含带 payload 的）
            // 都会多 4 个字节。**不扫描、不丢弃 USB 完成量**：只有这一个 4 字节边界
            // 有资格被跳过，且必须在校验过 TCP 或抓到的协议 1 诊断形状之后。
            // 偏移 0 处的正常头或拆分头**永远保留**。
            // 等候选的 MUX/TCP 头或完整诊断载荷到齐；超时就把这些字节原样留着，
            // 一个合法的拆分帧绝不能被丢掉。
            if (previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                bytes.size < PADDING_BYTES + HEADER_BYTES
            ) {
                return null
            }
            val followingProtocol = readU32(bytes, PADDING_BYTES)
            val followingLength = readU32(bytes, PADDING_BYTES + 4)
            val candidateHeader = previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                readU32(bytes, PADDING_BYTES + 8) == CAPTURED_REPLY_MAGIC
            val validTarget = when {
                candidateHeader && followingProtocol == PROTOCOL_TCP &&
                    followingLength in (HEADER_BYTES + TCP_HEADER_BYTES)..MAX_FRAME_BYTES -> {
                    if (bytes.size < PADDING_BYTES + HEADER_BYTES + TCP_HEADER_BYTES) return null
                    val tcpHeaderBytes =
                        ((bytes[PADDING_BYTES + HEADER_BYTES + 12].toInt() ushr 4) and 0x0f) * 4
                    tcpHeaderBytes in TCP_HEADER_BYTES..(followingLength - HEADER_BYTES) &&
                        readU16(bytes, PADDING_BYTES + HEADER_BYTES) != 0 &&
                        readU16(bytes, PADDING_BYTES + HEADER_BYTES + 2) != 0
                }
                candidateHeader && followingProtocol == PROTOCOL_DIAGNOSTIC &&
                    followingLength in (HEADER_BYTES + 2)..(HEADER_BYTES + MAX_DIAGNOSTIC_PAYLOAD_BYTES) -> {
                    // issue #100 也抓到协议 1 前后各多 4 字节。恢复之前要把
                    // 它的**整个有界子类型/文本载荷**校验完。
                    // 超时则把 trailer 与不完整的帧原样留着。
                    if (bytes.size < PADDING_BYTES + followingLength) return null
                    isCapturedDiagnosticPayload(
                        bytes,
                        PADDING_BYTES + HEADER_BYTES,
                        followingLength - HEADER_BYTES,
                    )
                }
                else -> false
            }
            if (previous != null && validTarget) {
                bytes = bytes.copyOfRange(PADDING_BYTES, bytes.size)
                optionalReplyPadding = null
                if (paddingReports++ < MAX_PADDING_REPORTS) {
                    report(
                        "USBMUX optional reply padding skipped bytes=$PADDING_BYTES " +
                            "previousProtocol=${previous.protocol} previousLength=${previous.length} " +
                            "nextProtocol=$followingProtocol nextLength=$followingLength " +
                            "lastUsbReadBytes=$lastUsbReadBytes bufferedBytes=${bytes.size}",
                    )
                }
                if (bytes.size < HEADER_BYTES) return null
                length = readU32(bytes, 4)
            } else {
                report(
                    "USBMUX framing rejected declaredLength=$length bufferedBytes=${bytes.size} " +
                        "lastUsbReadBytes=$lastUsbReadBytes optionalReplyPadding=${previous != null}",
                )
                throw IphoneUsbException.Protocol("Invalid USBMUX frame length $length")
            }
        }
        if (bytes.size < length) return null
        val frame = UsbMuxFrame(
            protocol = readU32(bytes, 0), length = length, word8 = readU32(bytes, 8),
            sequence = ((bytes[12].toInt() and 0xff) shl 8) or (bytes[13].toInt() and 0xff),
            payload = bytes.copyOfRange(HEADER_BYTES, length),
        )
        bytes = bytes.copyOfRange(length, bytes.size)
        optionalReplyPadding = frame.takeIf(::canHaveOptionalReplyPadding)
        return frame
    }

    private fun canHaveOptionalReplyPadding(frame: UsbMuxFrame): Boolean {
        if (frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == 2) {
            return true
        }
        if (frame.protocol == PROTOCOL_DIAGNOSTIC && frame.word8 == CAPTURED_REPLY_MAGIC) {
            return isCapturedDiagnosticPayload(frame.payload, 0, frame.payload.size)
        }
        if (frame.protocol != PROTOCOL_TCP || frame.word8 != CAPTURED_REPLY_MAGIC ||
            frame.payload.size < TCP_HEADER_BYTES
        ) {
            return false
        }
        val tcpHeaderBytes = ((frame.payload[12].toInt() ushr 4) and 0x0f) * 4
        return tcpHeaderBytes in TCP_HEADER_BYTES..frame.payload.size &&
            readU16(frame.payload, 0) != 0 && readU16(frame.payload, 2) != 0
    }

    /** 只有抓到的那个诊断子类型 + 其后**有界的可打印 ASCII** 才算数。 */
    private fun isCapturedDiagnosticPayload(source: ByteArray, offset: Int, length: Int): Boolean =
        length in 2..MAX_DIAGNOSTIC_PAYLOAD_BYTES &&
            source[offset].toInt() == DIAGNOSTIC_TEXT_SUBTYPE &&
            (offset + 1 until offset + length).all { source[it].toInt() in 0x20..0x7e }

    private fun report(line: String) {
        runCatching { diagnostic(line) }
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val HEADER_BYTES = 16
        const val TCP_HEADER_BYTES = 20
        const val MAX_FRAME_BYTES = 65_536
        const val VERSION_BYTES = 20
        const val PADDING_BYTES = 4
        const val MAX_PADDING_REPORTS = 4
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_DIAGNOSTIC = 1
        const val PROTOCOL_TCP = 6
        const val DIAGNOSTIC_TEXT_SUBTYPE = 4
        const val MAX_DIAGNOSTIC_PAYLOAD_BYTES = 1_024
        val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
    }
}
