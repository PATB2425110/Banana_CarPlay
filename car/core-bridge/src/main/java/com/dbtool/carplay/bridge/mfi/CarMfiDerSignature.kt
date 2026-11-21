package com.dbtool.carplay.bridge.mfi

/**
 * ECDSA 签名在 DER 与 raw 两种表示之间转换。
 *
 * ## 为什么必须有这一层
 *
 * `java.security.Signature` 输出的永远是 **DER**（`SEQUENCE { INTEGER r, INTEGER s }`，
 * 长度 70~72 字节不等）。但 iAP2 MFi 协议要的是 **raw**（`r || s`，P-256 下固定 64 字节）。
 *
 * 证据链（对着 upstream 源码核过）：
 * - I2C 版 `MfiAuthenticationClient.signChallenge()` 把协处理器寄存器里的字节**原样返回**；
 * - `Iap2MfiAuthenticationClient` 把它**原样**塞进 iAP2 `0xaa03` 响应帧；
 * - 而 DiPlay 的 `LocalMfiAuthenticationClient` 内部做了 `derToRaw(signer.sign())`。
 *
 * 三条合起来说明：协处理器输出的就是 raw，所以本地软件实现必须自己转。
 *
 * **返回 DER 的后果**：签名长度变成 70~72 字节，iPhone 直接回 `AuthenticationFailed`，
 * 而且不会有任何提示告诉你原因。这是整条链路上最容易踩死、最难排查的一个点。
 *
 * ## 为什么不用 BouncyCastle
 *
 * 这里只需要读一个两字段的 SEQUENCE，手写 DER 解析约 40 行；
 * 而引入 `bcprov` 会给 `:car:core-bridge` 增加一个运行时依赖。
 * 更重要的收益：本文件不依赖任何 Android API，可以在普通 JVM 单元测试里被完整验证。
 */
internal object CarMfiDerSignature {

    /** P-256 的 raw 签名长度：r(32) + s(32)。 */
    const val RAW_BYTES = 64

    private const val TAG_SEQUENCE = 0x30
    private const val TAG_INTEGER = 0x02
    private const val COORDINATE_BYTES = 32

    /**
     * DER → raw(64)。
     *
     * 要处理三种真实情况：
     * 1. `r`/`s` 高位为 1 时 DER 会补一个前导 `0x00`，长度变 33 —— 必须去掉；
     * 2. `r`/`s` 数值小的时候 DER 会去掉前导零，长度可能只有 1~31 —— 必须**左补零**到 32；
     * 3. 长度字段可能用长格式（`0x81 xx`），虽然 70 字节签名用不到，但不能假设。
     */
    fun toRaw(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        reader.expectTag(TAG_SEQUENCE)
        val declared = reader.readLength()
        require(declared == der.size - reader.offset) {
            "ECDSA signature length $declared does not match ${der.size - reader.offset} remaining bytes"
        }
        val r = reader.readInteger()
        val s = reader.readInteger()
        require(reader.offset == der.size) { "ECDSA signature has trailing bytes" }
        val raw = ByteArray(RAW_BYTES)
        r.copyInto(raw, COORDINATE_BYTES - r.size)
        s.copyInto(raw, RAW_BYTES - s.size)
        return raw
    }

    /**
     * raw(64) → DER。
     *
     * 生产代码用不到（iAP2 只要 raw），存在的唯一理由是**让测试能反向验证**：
     * 单测把 `toRaw` 的结果再转回 DER，交给 `Signature.verify` 校验，
     * 这样"转换正确"就有了密码学证据，而不是靠肉眼比对十六进制。
     */
    fun toDer(raw: ByteArray): ByteArray {
        require(raw.size == RAW_BYTES) { "raw signature must be $RAW_BYTES bytes, got ${raw.size}" }
        val body = encodeInteger(raw.copyOfRange(0, COORDINATE_BYTES)) +
            encodeInteger(raw.copyOfRange(COORDINATE_BYTES, RAW_BYTES))
        return byteArrayOf(TAG_SEQUENCE.toByte()) + encodeLength(body.size) + body
    }

    private fun encodeInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) start++
        var content = value.copyOfRange(start, value.size)
        if (content[0].toInt() and 0x80 != 0) content = byteArrayOf(0.toByte()) + content
        return byteArrayOf(TAG_INTEGER.toByte()) + encodeLength(content.size) + content
    }

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xff -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
    }

    private class DerReader(private val bytes: ByteArray) {
        var offset = 0
            private set

        fun expectTag(expected: Int) {
            require(offset < bytes.size) { "ECDSA signature is truncated" }
            val actual = bytes[offset++].toInt() and 0xff
            require(actual == expected) {
                "Expected DER tag 0x${expected.toString(16)} but found 0x${actual.toString(16)}"
            }
        }

        fun readLength(): Int {
            require(offset < bytes.size) { "ECDSA signature is truncated" }
            val first = bytes[offset++].toInt() and 0xff
            if (first < 0x80) return first
            val count = first and 0x7f
            require(count in 1..4) { "Unsupported DER length encoding 0x${first.toString(16)}" }
            require(offset + count <= bytes.size) { "ECDSA signature is truncated" }
            var value = 0
            repeat(count) { value = (value shl 8) or (bytes[offset++].toInt() and 0xff) }
            return value
        }

        /** 读出 INTEGER 的内容，已剥掉 DER 的符号位前导零。 */
        fun readInteger(): ByteArray {
            expectTag(TAG_INTEGER)
            val length = readLength()
            require(length >= 1) { "ECDSA integer is empty" }
            require(offset + length <= bytes.size) { "ECDSA signature is truncated" }
            var start = offset
            val end = offset + length
            offset = end
            // DER 在高位为 1 时会补一个 0x00 保持正数语义，这里去掉它。
            if (end - start > 1 && bytes[start] == 0.toByte()) start++
            val value = bytes.copyOfRange(start, end)
            require(value.size <= COORDINATE_BYTES) {
                "ECDSA integer is ${value.size} bytes, longer than the P-256 coordinate size"
            }
            return value
        }
    }
}
