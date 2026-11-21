package com.dbtool.carplay.bridge.mfi

import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarMfiAccessoryKey] 的单测。
 *
 * 覆盖重点是**用户可自定义身份**带来的失败模式：用户会喂进来错的文件、
 * 不配对的私钥与证书、别的曲线的证书。这些都必须变成一条明确的中文异常，
 * 而不是留到 iPhone 那边变成一个没有任何提示的 `AuthenticationFailed`。
 *
 * 需要一个真实可解析的证书，由 [TestX509] 现场拼出来（见那个文件的说明）。
 */
class CarMfiAccessoryKeyTest {

    @Test
    fun `配对正确的私钥与证书可以载入`() {
        val pair = TestX509.keyPair()
        val certificate = TestX509.certificateFor(pair.public)

        val key = CarMfiAccessoryKey.fromBytes(pair.private.encoded, certificate)

        assertEquals(3, key.protocolMajor)
        assertArrayEquals(certificate, key.certificatePayload())
        assertEquals(
            "certificateSha256 必须是证书载荷本身的摘要",
            certificate.sha256Hex(),
            key.certificateSha256Hex,
        )
        assertEquals(
            "指纹取公钥摘要前 16 位",
            16,
            key.publicKeyFingerprint.length,
        )
    }

    @Test
    fun `签出来的是 64 字节 raw 签名且能被证书公钥验证`() {
        val pair = TestX509.keyPair()
        val key = CarMfiAccessoryKey.fromBytes(
            pair.private.encoded,
            TestX509.certificateFor(pair.public),
        )
        val challenge = ByteArray(32) { (it * 7).toByte() }

        val raw = key.signRaw(challenge)

        assertEquals(CarMfiDerSignature.RAW_BYTES, raw.size)
        // 用 CarMfiDerSignature 转回 DER 再验签，等于同时验证了签名本身与转换代码。
        val verified = Signature.getInstance("NONEwithECDSA").run {
            initVerify(pair.public)
            update(challenge)
            verify(CarMfiDerSignature.toDer(raw))
        }
        assertTrue("签名必须能被证书里的公钥验证通过", verified)
    }

    @Test
    fun `challenge 不是 32 字节时拒绝`() {
        val pair = TestX509.keyPair()
        val key = CarMfiAccessoryKey.fromBytes(
            pair.private.encoded,
            TestX509.certificateFor(pair.public),
        )

        val tooShort = runCatching { key.signRaw(ByteArray(31)) }.exceptionOrNull()
        val tooLong = runCatching { key.signRaw(ByteArray(33)) }.exceptionOrNull()

        assertTrue("31 字节应当被拒绝，实际 $tooShort", tooShort is IllegalArgumentException)
        assertTrue("33 字节应当被拒绝，实际 $tooLong", tooLong is IllegalArgumentException)
    }

    @Test
    fun `私钥与证书不配对时给出明确原因`() {
        val keyPair = TestX509.keyPair()
        val otherPair = TestX509.keyPair()

        val error = runCatching {
            CarMfiAccessoryKey.fromBytes(
                keyPair.private.encoded,
                TestX509.certificateFor(otherPair.public),
            )
        }.exceptionOrNull()

        assertTrue("期望 CarMfiIdentityException，实际 $error", error is CarMfiIdentityException)
        assertTrue(
            "原因必须点明是私钥与证书不匹配：${error?.message}",
            error?.message.orEmpty().contains("不匹配"),
        )
    }

    @Test
    fun `非 P-256 的证书被拒绝`() {
        val pair = TestX509.keyPair("secp384r1")

        val error = runCatching {
            CarMfiAccessoryKey.fromBytes(
                pair.private.encoded,
                TestX509.certificateFor(pair.public),
            )
        }.exceptionOrNull()

        assertTrue("期望 CarMfiIdentityException，实际 $error", error is CarMfiIdentityException)
        assertTrue(
            "原因必须点明曲线不对：${error?.message}",
            error?.message.orEmpty().contains("P-256"),
        )
    }

    @Test
    fun `私钥字节不是 PKCS8 时被拒绝`() {
        val error = runCatching {
            CarMfiAccessoryKey.fromBytes(
                ByteArray(64) { 0x42 },
                TestX509.certificateFor(TestX509.keyPair().public),
            )
        }.exceptionOrNull()

        assertTrue("期望 CarMfiIdentityException，实际 $error", error is CarMfiIdentityException)
        assertTrue(
            "原因必须点明是私钥格式问题：${error?.message}",
            error?.message.orEmpty().contains(CarMfiAccessoryKey.IDENTITY_FILE),
        )
    }

    @Test
    fun `证书是垃圾字节时被拒绝`() {
        val pair = TestX509.keyPair()

        val error = runCatching {
            CarMfiAccessoryKey.fromBytes(pair.private.encoded, ByteArray(128) { 0x7F })
        }.exceptionOrNull()

        assertTrue("期望 CarMfiIdentityException，实际 $error", error is CarMfiIdentityException)
        assertTrue(
            "原因必须点明证书格式问题：${error?.message}",
            error?.message.orEmpty().contains("证书"),
        )
    }

    @Test
    fun `证书为空时被拒绝`() {
        val pair = TestX509.keyPair()

        val error = runCatching {
            CarMfiAccessoryKey.fromBytes(pair.private.encoded, ByteArray(0))
        }.exceptionOrNull()

        assertTrue("期望 CarMfiIdentityException，实际 $error", error is CarMfiIdentityException)
    }

    @Test
    fun `载入时会擦除传入的私钥字节`() {
        val pair = TestX509.keyPair()
        val encoded = pair.private.encoded

        CarMfiAccessoryKey.fromBytes(encoded, TestX509.certificateFor(pair.public))

        assertArrayEquals(
            "明文私钥副本必须被擦掉，避免在堆里留一份",
            ByteArray(encoded.size),
            encoded,
        )
    }

    @Test
    fun `不同身份的指纹不同`() {
        val first = TestX509.keyPair()
        val second = TestX509.keyPair()

        val firstKey = CarMfiAccessoryKey.fromBytes(
            first.private.encoded,
            TestX509.certificateFor(first.public),
        )
        val secondKey = CarMfiAccessoryKey.fromBytes(
            second.private.encoded,
            TestX509.certificateFor(second.public),
        )

        assertNotEquals(firstKey.publicKeyFingerprint, secondKey.publicKeyFingerprint)
        assertEquals(16, firstKey.publicKeyFingerprint.length)
    }

    private fun ByteArray.sha256Hex(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
