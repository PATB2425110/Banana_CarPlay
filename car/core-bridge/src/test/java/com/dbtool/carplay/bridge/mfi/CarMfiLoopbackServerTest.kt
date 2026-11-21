package com.dbtool.carplay.bridge.mfi

import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiHttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarMfiLoopbackServer] 的单测。
 *
 * ## 为什么用上游的客户端来打自己的服务
 *
 * 这个环回服务存在的唯一理由是"让上游以为自己在跟一个远程服务说话"，
 * 所以真正需要被证明的命题是 **"上游那个真实客户端能完整跑通"**，
 * 而不是"我的服务在某个自造协议下能应答"。
 *
 * `:car:core-bridge` 对 `:shared` 是 `implementation` 依赖，测试源集能看到它，
 * 于是这里直接 new 出上游的 `RemoteMfiAuthenticationClient` 当对手方。
 * 这样一旦上游改了 HTTP 契约，本测试会先红，而不是等到真机上握手失败。
 */
class CarMfiLoopbackServerTest {

    /** 一个不依赖任何密码学的假签名器：证书就是一段可辨识的字节。 */
    private class FakeSigner(
        private val certificate: ByteArray = ByteArray(607) { (it % 251).toByte() },
    ) : CarMfiSigner {
        var signCalls = 0
            private set

        override val protocolMajor: Int get() = 3

        override fun certificatePayload(): ByteArray = certificate.copyOf()

        override fun signRaw(challenge: ByteArray): ByteArray {
            signCalls++
            // 与生产实现一致：只接受 32 字节摘要。
            require(challenge.size == CarMfiAccessoryKey.CHALLENGE_BYTES) {
                "challenge must be ${CarMfiAccessoryKey.CHALLENGE_BYTES} bytes"
            }
            return ByteArray(CarMfiDerSignature.RAW_BYTES) { challenge[it % challenge.size] }
        }
    }

    @Test
    fun `上游客户端可以完整走完 reset 取证书与签名`() {
        val signer = FakeSigner()
        CarMfiLoopbackServer(signer).use { server ->
            val client = RemoteMfiAuthenticationClient(server.endpoint, server.token)

            client.reset()
            assertEquals(3, client.protocolMajor())
            assertArrayEquals(signer.certificatePayload(), client.readCertificate(65_525))

            val challenge = ByteArray(32) { it.toByte() }
            val signature = client.signChallenge(challenge)
            assertEquals(
                "iAP2 MFi 要的是 raw 64 字节，不是 DER",
                CarMfiDerSignature.RAW_BYTES,
                signature.size,
            )
            assertEquals(1, signer.signCalls)
        }
    }

    @Test
    fun `端点只监听回环地址`() {
        FakeSigner().let { CarMfiLoopbackServer(it) }.use { server ->
            assertTrue(server.endpoint.startsWith("http://127.0.0.1:"))
            assertTrue(server.port in 1..65_535)
        }
    }

    @Test
    fun `每次启动都换一个新令牌`() {
        val first = CarMfiLoopbackServer(FakeSigner())
        val second = CarMfiLoopbackServer(FakeSigner())
        try {
            assertTrue("令牌不能为空", first.token.isNotEmpty())
            assertTrue("两次启动的令牌不能相同", first.token != second.token)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `令牌不对时拒绝并且不调用签名器`() {
        val signer = FakeSigner()
        CarMfiLoopbackServer(signer).use { server ->
            val (status, _) = rawRequest(
                endpoint = server.endpoint,
                method = "POST",
                path = CarMfiLoopbackServer.PATH_SIGN,
                body = """{"challenge":"${ByteArray(32).toBase64()}"}""",
                token = "wrong-token",
            )
            assertEquals(401, status)
            assertEquals("未授权的请求绝不能走到签名器", 0, signer.signCalls)
        }
    }

    @Test
    fun `完全不带令牌时也拒绝`() {
        val signer = FakeSigner()
        CarMfiLoopbackServer(signer).use { server ->
            val (status, _) = rawRequest(
                endpoint = server.endpoint,
                method = "GET",
                path = CarMfiLoopbackServer.PATH_CERTIFICATE,
                token = null,
            )
            assertEquals(401, status)
        }
    }

    @Test
    fun `未知路径返回 404 方法不对返回 405`() {
        CarMfiLoopbackServer(FakeSigner()).use { server ->
            val (notFound, _) = rawRequest(server.endpoint, "GET", "/mfi/whatever", token = server.token)
            assertEquals(404, notFound)

            val (wrongMethod, _) = rawRequest(
                server.endpoint,
                "DELETE",
                CarMfiLoopbackServer.PATH_CERTIFICATE,
                token = server.token,
            )
            assertEquals(405, wrongMethod)
        }
    }

    @Test
    fun `challenge 长度不对时返回 400 而不是 500`() {
        CarMfiLoopbackServer(FakeSigner()).use { server ->
            // 上游允许 1..128 字节，但本地 MFi v3 只接受 32 字节摘要。
            val (status, body) = rawRequest(
                endpoint = server.endpoint,
                method = "POST",
                path = CarMfiLoopbackServer.PATH_SIGN,
                body = """{"challenge":"${ByteArray(31).toBase64()}"}""",
                token = server.token,
            )
            assertEquals(400, status)
            assertTrue(body.contains("challenge"))
        }
    }

    @Test
    fun `请求体缺少 challenge 时返回 400`() {
        CarMfiLoopbackServer(FakeSigner()).use { server ->
            val (status, body) = rawRequest(
                endpoint = server.endpoint,
                method = "POST",
                path = CarMfiLoopbackServer.PATH_SIGN,
                body = """{"requestId":"x"}""",
                token = server.token,
            )
            assertEquals(400, status)
            assertTrue(body.contains("challenge"))
        }
    }

    @Test
    fun `身份缺失时返回可读的 503 且原因能透传到上游异常`() {
        val reason = "未安装本地认证身份"
        CarMfiLoopbackServer(UnavailableCarMfiSigner(reason)).use { server ->
            val client = RemoteMfiAuthenticationClient(server.endpoint, server.token)
            // reset 不碰签名器，所以会成功——这正是真实调用顺序。
            client.reset()

            val error = runCatching { client.protocolMajor() }.exceptionOrNull()
            assertTrue(
                "期望上游抛 RemoteMfiHttpException，实际是 $error",
                error is RemoteMfiHttpException,
            )
            assertEquals(503, (error as RemoteMfiHttpException).statusCode)
            assertTrue(
                "用户必须能看到具体原因，而不是一句 HTTP 503：${error.message}",
                error.message.orEmpty().contains(reason),
            )
        }
    }

    @Test
    fun `签名器抛非预期异常时返回 500 且服务不退出`() {
        val exploding = object : CarMfiSigner {
            override val protocolMajor: Int get() = 3
            override fun certificatePayload(): ByteArray = ByteArray(16)
            override fun signRaw(challenge: ByteArray): ByteArray =
                throw IllegalStateException("boom")
        }
        CarMfiLoopbackServer(exploding).use { server ->
            val (status, _) = rawRequest(
                server.endpoint,
                "POST",
                CarMfiLoopbackServer.PATH_SIGN,
                body = """{"challenge":"${ByteArray(32).toBase64()}"}""",
                token = server.token,
            )
            assertEquals(500, status)

            // 关键：一个请求炸掉之后，服务必须还能继续应答。
            val (stillAlive, _) = rawRequest(
                server.endpoint,
                "GET",
                CarMfiLoopbackServer.PATH_CERTIFICATE,
                token = server.token,
            )
            assertEquals(200, stillAlive)
        }
    }

    @Test
    fun `关闭后端口不再接受连接`() {
        val server = CarMfiLoopbackServer(FakeSigner())
        val endpoint = server.endpoint
        server.close()

        val failed = runCatching {
            rawRequest(endpoint, "GET", CarMfiLoopbackServer.PATH_CERTIFICATE, token = server.token)
        }.exceptionOrNull()
        assertTrue("关闭后应当连不上，实际是 $failed", failed is IOException)
    }

    @Test
    fun `统计已应答的请求数`() {
        CarMfiLoopbackServer(FakeSigner()).use { server ->
            assertEquals(0, server.requestCount)
            val client = RemoteMfiAuthenticationClient(server.endpoint, server.token)
            client.reset()
            client.protocolMajor()
            assertEquals(2, server.requestCount)
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun rawRequest(
        endpoint: String,
        method: String,
        path: String,
        body: String? = null,
        token: String?,
    ): Pair<Int, String> {
        val connection = URL("$endpoint$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            status to text
        } finally {
            connection.disconnect()
        }
    }

    private fun ByteArray.toBase64(): String = java.util.Base64.getEncoder().encodeToString(this)
}
