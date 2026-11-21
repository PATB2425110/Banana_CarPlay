package com.dbtool.carplay.geely.lynk

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 调试反连隧道的「关得掉 / 断得了 / 拒得了」三条现场语义的单测（2026-10-16）。
 *
 * ## 为什么值得钉
 *
 * 现场事故是「**关了开关，对端还看得到车机**」：`stop()` 只做了 `interrupt()`，
 * 而 `interrupt` 对阻塞在 `Socket.read` / `connect` 上的 BIO 线程**无效**，
 * 工作线程不退出、socket 不关闭 ⇒ 服务器侧仍有一条活连接。
 *
 * 根因还有第二层：两个 `Socket()` 从来没存进字段，`stop()` 里 close 的是 `null`。
 * 所以下面第 2 条用例（对端读到 EOF）用**外部可观测的事实**（对端 read 返回 -1）
 * 钉死这一点 —— 只测「状态变 IDLE」是测不出来的（状态本来就会被改）。
 *
 * ## 协议（测试里的假服务器必须**实现**，不能只收不发）
 *
 * 隧道连上后先发一行 `DBPLAY-TUNNEL <secret>\n`，**只有**服务端回 `DBPLAY-OK\n`
 * 才会进入 `CONNECTED`。假服务器若「accept 后只读丢弃」，隧道会卡在 8s 握手超时，
 * 用例就会假失败。
 *
 * 连上的判据一律用**粘性字段** `status.lastConnectedAtMs > 0`（状态会跳：
 * `CONNECTED → RETRYING → CONNECTING`，盯 `state` 很容易抽到中间态）。
 * 全程「轮询 + 超时 fail」，没有一处盲 sleep 后断言。
 */
class LynkDebugTunnelTest {

    private var tunnel: LynkDebugTunnel? = null
    private val closers = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        runCatching { tunnel?.stop() }
        tunnel = null
        // 等工作线程真正退出：否则上一条用例的残留线程会让下一条用例的
        // 「已无名叫 dbplay-debug-tunnel 的活线程」断言假失败。
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && tunnelWorkerAlive()) {
            Thread.sleep(20)
        }
        closers.forEach { runCatching { it() } }
        closers.clear()
    }

    @Test(timeout = 20_000)
    fun `stop_一秒内线程退出且状态回IDLE`() {
        val relay = FakeRelayServer("DBPLAY-OK\n")
        closers.add { relay.close() }
        val adbd = FakeAdbd()
        closers.add { adbd.close() }

        val t = LynkDebugTunnel("127.0.0.1", relay.port, localAdbPort = adbd.port, secret = "s")
        tunnel = t
        t.start()

        waitUntil(5_000, "隧道连上（lastConnectedAtMs > 0）") { t.status.lastConnectedAtMs > 0 }
        assertEquals(LynkDebugTunnel.State.CONNECTED, t.status.state)

        t.stop()

        waitUntil(1_000, "工作线程已退出") { !tunnelWorkerAlive() }
        assertEquals(LynkDebugTunnel.State.IDLE, t.status.state)
    }

    @Test(timeout = 20_000)
    fun `stop后_对端读到EOF`() {
        val relay = FakeRelayServer("DBPLAY-OK\n")
        closers.add { relay.close() }
        val adbd = FakeAdbd()
        closers.add { adbd.close() }

        val t = LynkDebugTunnel("127.0.0.1", relay.port, localAdbPort = adbd.port, secret = "s")
        tunnel = t
        t.start()

        waitUntil(5_000, "隧道连上（lastConnectedAtMs > 0）") { t.status.lastConnectedAtMs > 0 }

        val accepted = relay.accepted
        assertNotNull("服务端应已 accept 到隧道连接", accepted)
        val peer = accepted!!
        peer.soTimeout = 1_000
        t.stop()

        val n = try {
            peer.getInputStream().read()
        } catch (e: SocketTimeoutException) {
            fail("stop() 后 1s 内未读到 EOF（read 超时）: $e")
            -2
        }
        assertTrue("对端 read 应返回 -1（EOF），实际=$n", n == -1)
    }

    @Test(timeout = 20_000)
    fun `对端断开_进入RETRYING且计数增加`() {
        val relay = FakeRelayServer("DBPLAY-OK\n")
        closers.add { relay.close() }
        val adbd = FakeAdbd()
        closers.add { adbd.close() }

        val t = LynkDebugTunnel("127.0.0.1", relay.port, localAdbPort = adbd.port, secret = "s")
        tunnel = t
        val seen = Collections.synchronizedList(mutableListOf<LynkDebugTunnel.State>())
        t.onStateChanged = { seen.add(it) }
        t.start()

        waitUntil(5_000, "隧道连上（lastConnectedAtMs > 0）") { t.status.lastConnectedAtMs > 0 }

        val accepted = relay.accepted
        assertNotNull("服务端应已 accept 到隧道连接", accepted)
        accepted!!.close() // 服务端主动断开

        waitUntil(8_000, "reconnectCount 增至 1") { t.status.reconnectCount >= 1 }
        assertTrue(
            "应观测到 RETRYING，实际观测=$seen",
            seen.contains(LynkDebugTunnel.State.RETRYING),
        )
    }

    @Test(timeout = 20_000)
    fun `口令不符_永不CONNECTED且记录失败原因`() {
        val relay = FakeRelayServer("NOPE\n")
        closers.add { relay.close() }
        val adbd = FakeAdbd()
        closers.add { adbd.close() }

        val t = LynkDebugTunnel("127.0.0.1", relay.port, localAdbPort = adbd.port, secret = "s")
        tunnel = t
        val seen = Collections.synchronizedList(mutableListOf<LynkDebugTunnel.State>())
        t.onStateChanged = { seen.add(it) }
        t.start()

        // 轮询 2s（拿到失败原因就提前结束），再看整段观测里有没有 CONNECTED
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && t.status.lastError == null) {
            Thread.sleep(20)
        }
        assertNotNull("口令被拒后应记录失败原因（status.lastError）", t.status.lastError)
        assertTrue(
            "口令不符时状态里不该出现 CONNECTED，实际观测=$seen",
            !seen.contains(LynkDebugTunnel.State.CONNECTED),
        )
    }

    /**
     * ⚠️ 2026-10-16 编排者补：**这条才是真正钉住现场事故的用例**。
     *
     * 前 4 条都在「已 CONNECTED、正在 bridge」的时刻停 —— 而 `bridge()` 自己
     * 每 500ms 轮询 `running`，`stop()` 置 false 后它**自己就会收尾并 close 两端**，
     * 所以哪怕 `stop()` 里的主动 close 被删掉，前 4 条依然全绿（已用反向验证实测：
     * 禁用 close 后 4 条仍全过 = 假绿灯）。
     *
     * 现场事故真正发生的时刻是**握手/连接阶段**：工作线程阻塞在 BIO `socket.read`
     * / `connect` 上，这段没有任何轮询，`interrupt()` 对 BIO socket 无效 ⇒
     * 只能靠 `stop()` 主动 `close()` 唤醒；否则要干等 8s（握手超时）或 15s（连接超时），
     * 期间对端一直看得到车机 —— 这正是「关了开关没关掉」。
     *
     * 本用例让假服务器**永不回 ACK**，把隧道钉在握手读上，再 stop()：
     * 断言工作线程 1s 内退出 + 对端 1s 内读到 EOF。禁用 close 后本用例必红。
     */
    @Test(timeout = 20_000)
    fun `stop_握手未完成时_一秒内线程退出且对端读到EOF`() {
        val relay = FakeRelayServer(ack = null) // null = 永不回 ACK
        closers.add { relay.close() }
        val adbd = FakeAdbd()
        closers.add { adbd.close() }

        val t = LynkDebugTunnel("127.0.0.1", relay.port, localAdbPort = adbd.port, secret = "s")
        tunnel = t
        t.start()

        // 钉住「此刻工作线程确实卡在握手读上」：等假服务器收到 hello 行
        waitUntil(5_000, "服务端收到 hello 行（隧道卡在握手读）") { relay.helloLine != null }
        assertTrue(
            "hello 行应以 DBPLAY-TUNNEL 开头，实际=${relay.helloLine}",
            relay.helloLine!!.startsWith("DBPLAY-TUNNEL"),
        )
        assertTrue(
            "此刻应还没连上（握手未完成），实际 state=${t.status.state}",
            t.status.lastConnectedAtMs == 0L,
        )

        val peer = relay.accepted
        assertNotNull("服务端应已 accept 到隧道连接", peer)
        peer!!.soTimeout = 1_000

        t.stop()

        waitUntil(1_000, "工作线程已退出（禁用 close 时会等满 8s 握手超时）") { !tunnelWorkerAlive() }
        assertEquals(LynkDebugTunnel.State.IDLE, t.status.state)

        val n = try {
            peer.getInputStream().read()
        } catch (e: SocketTimeoutException) {
            fail("stop() 后 1s 内未读到 EOF（read 超时）: $e")
            -2
        }
        assertTrue("对端 read 应返回 -1（EOF），实际=$n", n == -1)
    }

    // ---- 轮询工具 ----

    private fun tunnelWorkerAlive(): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == "dbplay-debug-tunnel" && it.isAlive }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        if (!cond()) fail("等待超时（${timeoutMs}ms）：$what")
    }

    // ---- 两个假的本地服务 ----

    /**
     * 假反连服务器：读一行 hello → 回 [ack] → 之后把读到的字节全丢弃。
     *
     * [ack] 传 `null` = **永不回 ACK**，用于「握手卡住时被 stop」的用例。
     *
     * ⚠️ 不 close 掉已接受的 socket（只在 [close] 里统一关）：否则「对端读到 EOF」
     * 那条用例会与这里的收尾线程抢同一个 socket，`read()` 有机会抛 `SocketException`
     * 而不是返回 -1，变成假失败。
     */
    private class FakeRelayServer(private val ack: String?) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private val acceptedSockets = Collections.synchronizedList(mutableListOf<Socket>())

        @Volatile
        var accepted: Socket? = null
            private set

        /** 收到并读出的 hello 行原文（null = 还没读到）。 */
        @Volatile
        var helloLine: String? = null
            private set

        private val acceptor = thread(name = "fake-relay-accept", isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val s = server.accept()
                    acceptedSockets.add(s)
                    accepted = s
                    helloLine = readLineSafe(s)
                    if (ack == null) continue // 永不回 ACK：把隧道钉在握手读上
                    s.getOutputStream().apply {
                        write(ack.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    drain(s)
                }
            } catch (_: Throwable) {
                // accept() 被 close()/interrupt() 打断：正常收尾
            }
        }

        fun close() {
            runCatching { server.close() }
            runCatching { acceptor.interrupt() }
            acceptedSockets.forEach { runCatching { it.close() } }
        }
    }

    /** 假车机 adbd：accept 后只读丢弃（本类不校验 ADB 协议）。 */
    private class FakeAdbd {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private val acceptedSockets = Collections.synchronizedList(mutableListOf<Socket>())

        private val acceptor = thread(name = "fake-adbd-accept", isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val s = server.accept()
                    acceptedSockets.add(s)
                    drain(s)
                }
            } catch (_: Throwable) {
                // 同上
            }
        }

        fun close() {
            runCatching { server.close() }
            runCatching { acceptor.interrupt() }
            acceptedSockets.forEach { runCatching { it.close() } }
        }
    }

}

/** 读一行（\n 结尾），读不到返回 null。 */
private fun readLineSafe(s: Socket): String? = try {
    val sb = StringBuilder()
    val inS = s.getInputStream()
    while (sb.length < 256) {
        val c = inS.read()
        if (c < 0 || c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
    }
    sb.toString()
} catch (_: Throwable) {
    null
}

/** 读到 EOF/异常为止，全丢弃。 */
private fun drain(s: Socket) {
    try {
        val buf = ByteArray(4096)
        val inS = s.getInputStream()
        while (inS.read(buf) >= 0) {
            // 丢弃
        }
    } catch (_: Throwable) {
        // 对端关闭 / 超时：结束本次搬运
    }
}
