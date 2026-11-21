package com.dbtool.carplay.bridge.mfi

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * 造一张**最小可解析**的自签名 X.509 证书，供 [CarMfiAccessoryKeyTest] 使用。
 *
 * ## 为什么不引 BouncyCastle
 *
 * 需要证书只是因为 `CarMfiAccessoryKey` 要从证书里取公钥。而 `CertificateFactory`
 * **只解析、不验签**——所以签名值填什么都可以，用不着真去签一次。
 * 为这点需求引入 `bcpkix` 会多一个测试依赖（还可能要求联网解析），
 * 而手写 DER 只要下面这十几行。
 *
 * ## 为什么"签名算法"两边用同一个 OID
 *
 * 真实证书里 `tbsCertificate.signature` 与外侧 `signatureAlgorithm` 必须一致，
 * JDK 的 `X509CertImpl` 会检查这一点。所以这里两边都用 `ecdsa-with-SHA256`。
 */
internal object TestX509 {

    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_CONTEXT_EXPLICIT_0 = 0xA0

    private val OID_ECDSA_WITH_SHA256 = oid(1, 2, 840, 10045, 4, 3, 2)
    private val OID_COMMON_NAME = oid(2, 5, 4, 3)

    /** `AlgorithmIdentifier` = `SEQUENCE { ecdsa-with-SHA256 }`（无参数）。 */
    private val SIGNATURE_ALGORITHM = der(TAG_SEQUENCE, OID_ECDSA_WITH_SHA256)

    fun certificateFor(publicKey: PublicKey): ByteArray {
        val name = der(
            TAG_SEQUENCE,
            der(
                TAG_SET,
                der(
                    TAG_SEQUENCE,
                    OID_COMMON_NAME + der(TAG_UTF8_STRING, "xcertplay-test".toByteArray()),
                ),
            ),
        )
        val validity = der(
            TAG_SEQUENCE,
            der(TAG_UTC_TIME, "200101000000Z".toByteArray(Charsets.US_ASCII)) +
                der(TAG_UTC_TIME, "491231235959Z".toByteArray(Charsets.US_ASCII)),
        )
        val tbsCertificate = der(
            TAG_SEQUENCE,
            // version: [0] EXPLICIT INTEGER 2 —— v3
            der(TAG_CONTEXT_EXPLICIT_0, der(TAG_INTEGER, byteArrayOf(0x02))) +
                der(TAG_INTEGER, byteArrayOf(0x01)) + // serialNumber
                SIGNATURE_ALGORITHM +
                name + // issuer
                validity +
                name + // subject
                // SubjectPublicKeyInfo 直接复用 JDK 编出来的那份，不重复实现。
                publicKey.encoded,
        )
        // 签名值随便填：解析器不验签。
        val signatureValue = byteArrayOf(0x30, 0x00)
        return der(
            TAG_SEQUENCE,
            tbsCertificate + SIGNATURE_ALGORITHM + der(TAG_BIT_STRING, byteArrayOf(0x00) + signatureValue),
        )
    }

    /** 生成一对指定曲线的密钥。 */
    fun keyPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(curve))
        }.generateKeyPair()

    private fun oid(vararg arcs: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(arcs[0] * 40 + arcs[1])
        for (index in 2 until arcs.size) {
            var remaining = arcs[index]
            val chunk = ArrayDeque<Int>()
            chunk.addFirst(remaining and 0x7F)
            remaining = remaining ushr 7
            while (remaining > 0) {
                chunk.addFirst((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            chunk.forEach(body::write)
        }
        return der(TAG_OID, body.toByteArray())
    }

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
    }
}
