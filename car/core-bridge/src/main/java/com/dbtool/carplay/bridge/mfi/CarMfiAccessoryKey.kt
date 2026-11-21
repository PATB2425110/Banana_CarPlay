package com.dbtool.carplay.bridge.mfi

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/** 身份文件缺失、损坏或与证书不匹配。消息面向用户可读。 */
class CarMfiIdentityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 从 `identity.pk8` + `certificate.p7b` 载入的 P-256 配件身份。
 *
 * ## 这个类为什么存在
 *
 * 正常 CarPlay 接收端需要一颗 MFi 认证协处理器（CH341 转 I2C、板载 I2C，或远程服务）。
 * 本类走的是第四条路：**把配件私钥直接放在车机上，用 Android 自带的密码学库完成签名**，
 * 不需要任何额外硬件。这也是 DiPlay 的 `LocalMfiAuthenticationClient` 在做的事。
 *
 * 代价必须写清楚：这样做的前提是手上有一份**Apple 签发的配件证书与配对私钥**。
 * 本类不生成、也无法生成这样的身份——它只负责加载与使用。
 *
 * ## 载入时做的四项校验
 *
 * 1. **文件大小上限**：单文件 16 KB，防止把大文件读进内存；
 * 2. **私钥可用**：PKCS#8 解析成功，且解析后立刻擦除内存中的密钥字节；
 * 3. **证书是 P-256**：比对椭圆曲线阶，避免拿 secp384r1 之类的证书走到签名阶段才失败；
 * 4. **私钥与证书配对**：用随机 32 字节做一次真实 sign/verify 自检。
 *    第 4 项最关键——私钥和证书不配对时，错误只会在 iPhone 那边表现为
 *    `AuthenticationFailed`，本机没有任何线索。这里提前把它变成一条明确的异常。
 *
 * 第 4 项用的是 DER 签名（`Signature` 的原生输出），与 [CarMfiDerSignature] 无关，
 * 因此它验证的是"密钥与证书配对"这件事本身，而不是我自己的转换代码。
 */
class CarMfiAccessoryKey private constructor(
    private val privateKey: PrivateKey,
    private val certificatePayload: ByteArray,
    /** 证书载荷的 SHA-256，小写十六进制。环回服务要用它填 `certificateSha256` 字段。 */
    val certificateSha256Hex: String,
    /** 证书里公钥的 SHA-256 前 8 字节十六进制，仅用于设置页展示"当前是哪一份身份"。 */
    val publicKeyFingerprint: String,
) : CarMfiSigner {

    override val protocolMajor: Int get() = PROTOCOL_MAJOR

    override fun certificatePayload(): ByteArray = certificatePayload.copyOf()

    override fun signRaw(challenge: ByteArray): ByteArray {
        require(challenge.size == CHALLENGE_BYTES) {
            "MFi v3 expects a $CHALLENGE_BYTES-byte digest, got ${challenge.size}"
        }
        // NONEwithECDSA：输入已经是摘要，**不能再哈希一次**。
        val signer = Signature.getInstance("NONEwithECDSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        return CarMfiDerSignature.toRaw(signer.sign())
    }

    companion object {
        /** iAP2 MFi 下发的 challenge 是 32 字节摘要。 */
        const val CHALLENGE_BYTES = 32

        /** MFi 协议主版本。 */
        const val PROTOCOL_MAJOR = 3

        const val IDENTITY_FILE = "identity.pk8"
        const val CERTIFICATE_FILE = "certificate.p7b"

        /** 单个身份文件的大小上限。真实文件是几十到几百字节，16 KB 已经很宽松。 */
        const val MAX_FILE_BYTES = 16 * 1024

        /** P-256 的曲线阶（`n`），用来确认证书确实是 prime256v1。 */
        private const val P256_ORDER =
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"

        /**
         * 从目录载入。
         *
         * @throws CarMfiIdentityException 目录不存在、文件缺失/过大/为空、解析失败、
         *         曲线不对，或私钥与证书不配对。
         */
        fun load(directory: File): CarMfiAccessoryKey {
            if (!directory.isDirectory) {
                throw CarMfiIdentityException("本地认证身份目录不存在：${directory.absolutePath}")
            }
            val keyFile = File(directory, IDENTITY_FILE)
            val certificateFile = File(directory, CERTIFICATE_FILE)
            if (!keyFile.isFile || !certificateFile.isFile) {
                throw CarMfiIdentityException(
                    "本地认证身份不完整：需要 $IDENTITY_FILE 与 $CERTIFICATE_FILE",
                )
            }
            return fromBytes(readBounded(keyFile), readBounded(certificateFile))
        }

        /** 直接从字节构造。测试与导入流程共用这一条路径。 */
        fun fromBytes(encodedKey: ByteArray, certificate: ByteArray): CarMfiAccessoryKey {
            if (certificate.isEmpty()) throw CarMfiIdentityException("证书文件为空")
            val privateKey = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encodedKey))
            } catch (error: Exception) {
                throw CarMfiIdentityException("$IDENTITY_FILE 不是有效的 PKCS#8 EC 私钥", error)
            } finally {
                // 密钥已经进入 KeyFactory，明文副本立刻擦掉。
                encodedKey.fill(0)
            }

            val publicKey = extractPublicKey(certificate)
            val order = publicKey.params.order.toString(16)
            if (order != P256_ORDER) {
                throw CarMfiIdentityException("证书不是 P-256（曲线阶 $order）")
            }
            verifyPairing(privateKey, publicKey)

            return CarMfiAccessoryKey(
                privateKey = privateKey,
                certificatePayload = certificate.copyOf(),
                certificateSha256Hex = certificate.sha256Hex(),
                publicKeyFingerprint = publicKey.encoded.sha256Hex().take(16),
            )
        }

        /**
         * 从证书载荷里取出公钥。
         *
         * 同时接受两种输入，因为"用户可自定义"意味着文件来源不可控：
         * - **PKCS#7 容器**（`.p7b`，Apple 下发的就是这种，DiPlay 用的也是它）；
         * - **裸 X.509 DER 证书**（`.cer` / `.crt`，用户自己转换出来的常见形态）。
         */
        private fun extractPublicKey(certificate: ByteArray): ECPublicKey {
            val factory = CertificateFactory.getInstance("X.509")
            val certificates = runCatching {
                factory.generateCertificates(certificate.inputStream()).toList()
            }.getOrElse { emptyList() }

            if (certificates.isNotEmpty()) {
                if (certificates.size != 1) {
                    throw CarMfiIdentityException(
                        "证书容器里有 ${certificates.size} 张证书，期望恰好 1 张",
                    )
                }
                return certificates.single().publicKey as? ECPublicKey
                    ?: throw CarMfiIdentityException("证书公钥不是 EC 公钥")
            }

            val single = runCatching { factory.generateCertificate(certificate.inputStream()) }
                .getOrElse { error ->
                    throw CarMfiIdentityException(
                        "证书既不是 PKCS#7 容器也不是 X.509 证书",
                        error,
                    )
                }
            return single.publicKey as? ECPublicKey
                ?: throw CarMfiIdentityException("证书公钥不是 EC 公钥")
        }

        /** 用随机摘要做一次真实签名往返，确认私钥确实属于证书里的公钥。 */
        private fun verifyPairing(privateKey: PrivateKey, publicKey: ECPublicKey) {
            val challenge = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
            val signature = try {
                Signature.getInstance("NONEwithECDSA").run {
                    initSign(privateKey)
                    update(challenge)
                    sign()
                }
            } catch (error: Exception) {
                throw CarMfiIdentityException("无法用私钥签名", error)
            }
            val verified = try {
                Signature.getInstance("NONEwithECDSA").run {
                    initVerify(publicKey)
                    update(challenge)
                    verify(signature)
                }
            } catch (error: Exception) {
                throw CarMfiIdentityException("无法校验签名", error)
            }
            if (!verified) {
                throw CarMfiIdentityException("私钥与证书不匹配：签名自检失败")
            }
        }

        private fun readBounded(file: File): ByteArray {
            val output = ByteArrayOutputStream()
            file.inputStream().use { stream ->
                val buffer = ByteArray(1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_FILE_BYTES) {
                        throw CarMfiIdentityException("${file.name} 超过 $MAX_FILE_BYTES 字节上限")
                    }
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            if (bytes.isEmpty()) throw CarMfiIdentityException("${file.name} 为空")
            return bytes
        }

        private fun ByteArray.sha256Hex(): String =
            MessageDigest.getInstance("SHA-256").digest(this)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
