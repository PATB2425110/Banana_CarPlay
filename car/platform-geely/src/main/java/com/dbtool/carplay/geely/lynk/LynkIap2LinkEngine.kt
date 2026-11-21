package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **iAP2 链路层**（可靠传输 + 状态机）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 的 `transport/Iap2LinkEngine.java`（91995 字节）反编译提取。
 * iAP2（iPod Accessory Protocol 2）是 CarPlay 的底层传输协议，
 * **CarPlay 会话死活都由这一层的状态机决定**。
 *
 * ## 为什么对 DBPlay 重要
 *
 * 现场掉线（连上→20秒断→自动重连）的直接现象是「蓝牙控制通道 Socket closed」，
 * 但**判定链路死亡的其实是这一层**。有了它就能看到真正的死因：
 * 是对端 RESET、标记丢失、还是重传耗尽。
 *
 * ## 帧格式
 *
 * ```
 * Header (9 字节) + 可选 payload，整帧最大 65535，payload 最大 65525
 * 序列号 u16 小端，初值 99
 * 1 字节校验
 * ```
 *
 * ## 状态机
 *
 * ```
 * DETECTING   ── 找 IAP2_MARKER（"iAP2" 魔数）
 *      ↓
 * NEGOTIATING ── 交换 SynchronizationPayload(maxOutgoing, maxLength, sessionDescriptor)
 *      ↓
 * NORMAL      ── 正常收发（ACK / 重传 / 乱序重组）
 *      ↓
 * Dead(reason) ── 显式死亡态，带原因字符串
 * ```
 *
 * ## 控制位
 *
 * | 位 | 值 | 含义 |
 * |---|---|---|
 * | SYNCHRONIZE | 128 | 同步帧 |
 * | ACKNOWLEDGEMENT | 64 | 收到确认 |
 * | EXTENDED_ACKNOWLEDGEMENT | 32 | 扩展确认 |
 * | RESET | 16 | 重置会话 |
 *
 * 会话号：`CONTROL_SESSION_ID=10`、`EA_SESSION_ID=11`（External Accessory）、
 * `FILE_TRANSFER_SESSION_ID=12`。
 *
 * ⚠️ 未实机验证。本类只还原「协议常量 + 状态机骨架 + 死亡原因」，
 * 完整编解码需对接 DBPlay 既有的 socket 读写。
 */
class LynkIap2LinkEngine {

    companion object {
        private const val TAG = "DBPlay-iAP2"

        // ---- 帧尺寸 ----
        const val HEADER_BYTES = 9
        const val CHECKSUM_BYTES = 1
        const val MAX_PAYLOAD_BYTES = 65525
        const val MAX_WIRE_FRAME_BYTES = 65535

        // ---- 控制位 ----
        const val CONTROL_SYNCHRONIZE = 128
        const val CONTROL_ACKNOWLEDGEMENT = 64
        const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 32
        const val CONTROL_RESET = 16

        // ---- 会话号 ----
        const val CONTROL_SESSION_ID = 10
        const val EA_SESSION_ID = 11
        const val FILE_TRANSFER_SESSION_ID = 12

        // ---- 可靠性 ----
        const val INITIAL_SEQUENCE = 99
        const val ACK_SLACK = 10
        const val DEFAULT_MAX_ACKNOWLEDGEMENTS = 3
        const val DEFAULT_MAX_RETRANSMISSIONS = 4
        const val DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS = 4000L
        const val MARKER_RESEND_MILLIS = 1000L
        const val SYNCHRONIZATION_RESEND_MILLIS = 500L
        const val SYNCHRONIZATION_VERSION = 1
        const val SYNCHRONIZATION_FIXED_BYTES = 10
        const val SESSION_DESCRIPTOR_BYTES = 3
    }

    /** 链路状态。 */
    enum class State { DETECTING, NEGOTIATING, NORMAL }

    /** 链路死亡（显式状态 + 原因）。 */
    data class Dead(val reason: String)

    /** 链路事件。 */
    sealed class Event {
        data class Data(val sessionId: Int, val payload: ByteArray) : Event()
        data class Control(val control: Int, val sessionId: Int) : Event()
        data class Synchronized(val payload: SyncPayload) : Event()
        object Reset : Event()
    }

    /** 同步协商负载。 */
    data class SyncPayload(
        val version: Int,
        val maxOutgoing: Int,
        val maxLength: Int,
        val sessionDescriptor: Int,
    )

    /** 发送中的包（用于重传）。 */
    data class Packet(
        val sequence: Int,
        val sessionId: Int,
        var retransmissions: Int = 0,
        val maxRetransmissions: Int = DEFAULT_MAX_RETRANSMISSIONS,
        val payload: ByteArray,
    )

    // ---- 运行态 ----
    var state: State = State.DETECTING
        private set
    private var nextSequence = INITIAL_SEQUENCE
    private var lastReceivedInOrder = -1
    private var lastSentAcknowledged = -1
    private var lastAcknowledgedReceived = -1
    private var peerMaxLength = MAX_PAYLOAD_BYTES
    private var outOfOrder = 0

    /** 已知死亡原因（对标 DiPlay 的 `Dead(reason)` 与 `feedEof`）。 */
    private val deadReasons = listOf(
        "peer sent iAP2 reset",
        "iAP2 marker was not received",
        "iAP2 out-of-order packet limit exceeded",
        "iAP2 outbound queue limit exceeded",
        "iAP2 pending event limit exceeded",
        "iAP2 pending output limit exceeded",
        "iAP2 session payload exceeds 65525 bytes",
        "iAP2 session payload exceeds peer maxLength",
        "iAP2 inbound frame exceeds 65535 bytes",
        "iAP2 session id must fit in one byte",
    )

    /** 进入协商态。 */
    fun enterNegotiating() {
        state = State.NEGOTIATING
        Log.d(TAG, "进入 NEGOTIATING 态")
    }

    /** 收到对端同步帧 → 采纳对端限额，进入 NORMAL。 */
    fun handleSynchronization(payload: SyncPayload) {
        peerMaxLength = payload.maxLength
        state = State.NORMAL
        Log.d(TAG, "同步完成 maxOutgoing=${payload.maxOutgoing} maxLength=${payload.maxLength}")
    }

    /** 收到 ACK。 */
    fun handleAcknowledgement(sequence: Int) {
        lastAcknowledgedReceived = sequence
    }

    /** 处理入站数据，返回事件或死亡。 */
    fun handleData(sessionId: Int, payload: ByteArray): Any {
        if (payload.size > MAX_PAYLOAD_BYTES) return dead("iAP2 session payload exceeds 65525 bytes")
        if (payload.size > peerMaxLength) return dead("iAP2 session payload exceeds peer maxLength")
        if (sessionId !in 0..255) return dead("iAP2 session id must fit in one byte")
        return Event.Data(sessionId, payload)
    }

    /** 对端发 RESET。 */
    fun onPeerReset(): Dead = dead("peer sent iAP2 reset")

    /** 底层 EOF（socket 断）。 */
    fun feedEof(): Dead = dead("iAP2 marker was not received")

    /** 到期未 ACK → 重传，超过上限则死亡。 */
    fun retransmitDuePacket(p: Packet): Any {
        if (p.retransmissions >= p.maxRetransmissions) {
            return dead("iAP2 packet ${p.sequence} was not acknowledged")
        }
        p.retransmissions++
        Log.d(TAG, "重传 seq=${p.sequence} 第 ${p.retransmissions}/${p.maxRetransmissions} 次")
        return p
    }

    /** 乱序计数超限。 */
    fun noteOutOfOrder(limit: Int = 8): Any {
        outOfOrder++
        return if (outOfOrder > limit) dead("iAP2 out-of-order packet limit exceeded") else outOfOrder
    }

    private fun dead(reason: String): Dead {
        state = State.DETECTING
        Log.w(TAG, "链路死亡: $reason")
        return Dead(reason)
    }

    /** 列出全部可能的死亡原因（供诊断面板展示）。 */
    fun knownDeadReasons(): List<String> = deadReasons
}
