package com.dbtool.carplay.bridge.mfi

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarMfiDerSignature] 的单测。
 *
 * 这个转换是整条链路上最容易踩死、最难排查的一环：返回 DER 而不是 raw，
 * iPhone 只会回 `AuthenticationFailed`，本机看不到任何原因。
 * 所以这里不能只做"字节数对不对"的弱断言，必须用**真实的 ECDSA 签名往返**
 * 来证明转换无损——把 `toRaw` 的结果再转回 DER 交给 `Signature.verify`。
 */
class CarMfiDerSignatureTest {

    private val random = SecureRandom()

    @Test
    fun `真实签名转 raw 后仍是有效签名`() {
        repeat(64) {
            val pair = newP256KeyPair()
            val digest = ByteArray(32).also(random::nextBytes)
            val der = signDer(pair.private as ECPrivateKey, digest)

            val raw = CarMfiDerSignature.toRaw(der)

            assertEquals("raw 签名必须是 64 字节", 64, raw.size)
            assertTrue(
                "转成 raw 之后用同一份公钥必须验签通过",
                verifyRaw(pair.public as ECPublicKey, digest, raw),
            )
        }
    }

    @Test
    fun `r 与 s 高位为 1 时剥掉 DER 的前导零`() {
        // 这是最常见的形态：约一半的签名会命中，长度是 70/71/72 字节不等。
        val raw = ByteArray(64)
        raw[0] = 0x80.toByte()
        raw[31] = 0xFF.toByte()
        raw[32] = 0x80.toByte()
        raw[63] = 0xFF.toByte()

        val der = CarMfiDerSignature.toDer(raw)

        // DER 里 r、s 各多出一个 0x00 符号位字节。
        assertEquals(72, der.size)
        assertArrayEquals(raw, CarMfiDerSignature.toRaw(der))
    }

    @Test
    fun `r 与 s 数值很小时左补零到 32 字节`() {
        val raw = ByteArray(64)
        raw[31] = 0x01
        raw[63] = 0x02

        val der = CarMfiDerSignature.toDer(raw)

        // DER 会去掉前导零，r/s 各只剩 1 字节，整个签名只有 8 字节。
        assertEquals(8, der.size)
        assertArrayEquals(raw, CarMfiDerSignature.toRaw(der))
    }

    @Test
    fun `r 或 s 为零时仍还原成 32 个零字节`() {
        val raw = ByteArray(64)
        raw[63] = 0x01

        assertArrayEquals(raw, CarMfiDerSignature.toRaw(CarMfiDerSignature.toDer(raw)))
    }

    @Test
    fun `随机构造的 raw 签名可以无损往返`() {
        repeat(256) {
            val raw = ByteArray(64).also(random::nextBytes)
            assertArrayEquals(raw, CarMfiDerSignature.toRaw(CarMfiDerSignature.toDer(raw)))
        }
    }

    // --------------------------------------------------------------- 拒绝路径

    @Test(expected = IllegalArgumentException::class)
    fun `标签不是 SEQUENCE 时拒绝`() {
        CarMfiDerSignature.toRaw(byteArrayOf(0x31, 0x00))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `声明长度与实际不符时拒绝`() {
        val raw = ByteArray(64)
        val der = CarMfiDerSignature.toDer(raw)
        // 把声明长度改大 1。
        der[1] = (der[1] + 1).toByte()
        CarMfiDerSignature.toRaw(der)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `有多余尾随字节时拒绝`() {
        val der = CarMfiDerSignature.toDer(ByteArray(64)) + byteArrayOf(0x00)
        CarMfiDerSignature.toRaw(der)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `整数超过 P-256 坐标长度时拒绝`() {
        // SEQUENCE { INTEGER 33 字节非零, INTEGER 1 字节 }
        val body = byteArrayOf(0x02, 0x21) + ByteArray(33) { 0x01 } + byteArrayOf(0x02, 0x01, 0x01)
        CarMfiDerSignature.toRaw(byteArrayOf(0x30, body.size.toByte()) + body)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `被截断的签名时拒绝`() {
        CarMfiDerSignature.toRaw(byteArrayOf(0x30, 0x44, 0x02, 0x20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `raw 长度不是 64 时拒绝`() {
        CarMfiDerSignature.toDer(ByteArray(63))
    }

    // ------------------------------------------------------------------ 工具

    private fun newP256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun signDer(privateKey: ECPrivateKey, digest: ByteArray): ByteArray =
        Signature.getInstance("NONEwithECDSA").run {
            initSign(privateKey)
            update(digest)
            sign()
        }

    private fun verifyRaw(publicKey: ECPublicKey, digest: ByteArray, raw: ByteArray): Boolean =
        Signature.getInstance("NONEwithECDSA").run {
            initVerify(publicKey)
            update(digest)
            verify(CarMfiDerSignature.toDer(raw))
        }
}
