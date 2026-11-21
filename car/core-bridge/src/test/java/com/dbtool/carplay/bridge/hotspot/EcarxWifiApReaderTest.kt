package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxWifiApReaderTest {

    private class MissingApi

    private class NullCreateApi {
        companion object {
            @JvmStatic
            fun create(context: Context?): Any? = null
        }
    }

    private class ThrowingApi {
        fun getWifiApState(): Int = throw IllegalStateException("state boom")
        fun isWifiApEnabled(): Boolean = throw IllegalStateException("enabled boom")
        fun getWifiApClients(): List<Any> = throw IllegalStateException("clients boom")
        fun close() {
            throw IllegalStateException("close boom")
        }

        companion object {
            @JvmStatic
            fun create(context: Context?): ThrowingApi = ThrowingApi()
        }
    }

    private class NullValueApi {
        fun getWifiApState(): Any? = null
        fun isWifiApEnabled(): Any? = null

        companion object {
            @JvmStatic
            fun create(context: Context?): NullValueApi = NullValueApi()
        }
    }

    private class FakeClient(
        private val ip: String?,
        private val mac: String?,
        private val name: String?,
    ) {
        fun getIP(): String? = ip
        fun getMac(): String? = mac
        fun getName(): String? = name
    }

    private class FakeWifiAp(private val clients: List<Any>) {
        fun getWifiApClients(): List<Any> = clients

        companion object {
            @JvmStatic
            fun create(context: Context?): FakeWifiAp = created ?: error("no fake prepared")

            @JvmStatic
            var created: FakeWifiAp? = null
        }
    }

    private class FakeExternalWifiAp(
        private val state: Any?,
        private val enabled: Any?,
    ) {
        var closed = false

        fun getWifiApState(): Any? = state
        fun isWifiApEnabled(): Any? = enabled
        fun close() {
            closed = true
        }

        companion object {
            @JvmStatic
            fun create(context: Context?): FakeExternalWifiAp = created ?: error("no fake prepared")

            @JvmStatic
            var created: FakeExternalWifiAp? = null
        }
    }

    private fun lookup(external: Class<*>?, wifiAp: Class<*>?): (String) -> Class<*>? = { name ->
        when {
            name.contains("ExternalWifiAP", ignoreCase = true) -> external
            name.endsWith("WifiAp", ignoreCase = true) -> wifiAp
            else -> null
        }
    }

    private fun assertUnreadable(snapshot: EcarxWifiApSnapshot) {
        assertNull(snapshot.state)
        assertNull(snapshot.enabled)
        assertTrue(snapshot.clients.isEmpty())
        assertFalse(snapshot.connected)
    }

    @Test
    fun `missing vendor class returns all null snapshot without throwing`() {
        assertUnreadable(EcarxWifiApReader.readWith(null, lookup(MissingApi::class.java, null)))
    }

    @Test
    fun `create returning null returns all null snapshot without throwing`() {
        assertUnreadable(
            EcarxWifiApReader.readWith(null, lookup(NullCreateApi::class.java, NullCreateApi::class.java)),
        )
    }

    @Test
    fun `throwing vendor methods return all null snapshot without throwing`() {
        assertUnreadable(
            EcarxWifiApReader.readWith(null, lookup(ThrowingApi::class.java, ThrowingApi::class.java)),
        )
    }

    @Test
    fun `vendor methods returning null yield null fields and no clients`() {
        assertUnreadable(
            EcarxWifiApReader.readWith(null, lookup(NullValueApi::class.java, NullValueApi::class.java)),
        )
    }

    @Test
    fun `vendor state enabled and clients are read and instances are closed`() {
        val wifiAp = FakeWifiAp(
            listOf(
                FakeClient("192.168.15.7", "aa:bb:cc:dd:ee:ff", "phone"),
                FakeClient(null, null, null),
            ),
        )
        val external = FakeExternalWifiAp(state = 13, enabled = false)
        FakeWifiAp.created = wifiAp
        FakeExternalWifiAp.created = external

        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(FakeExternalWifiAp::class.java, FakeWifiAp::class.java),
        )

        assertEquals(13, snapshot.state)
        assertEquals(false, snapshot.enabled)
        assertTrue(snapshot.connected)
        assertEquals(2, snapshot.clients.size)
        assertEquals("192.168.15.7", snapshot.clients[0].ip)
        assertEquals("aa:bb:cc:dd:ee:ff", snapshot.clients[0].mac)
        assertEquals("phone", snapshot.clients[0].name)
        assertNull(snapshot.clients[1].ip)
        assertTrue("vendor external instance must be closed", external.closed)
    }

    @Test
    fun `throwing close does not escape read`() {
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(ThrowingApi::class.java, ThrowingApi::class.java),
        )

        assertUnreadable(snapshot)
    }

    // ── TCAM Binder 兜底（2026-10-20：本车缺 Wifi6ApImpl，SDK 永远 null）────────

    @Test
    fun `厂商 SDK 缺失时用 TCAM Binder 状态兜底`() {
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(null, null),            // 所有类都找不到
            binderState = { 13 },
        )

        assertEquals(13, snapshot.state)
        assertEquals(true, snapshot.enabled)
        assertTrue("state=13 必须判为已连接", snapshot.connected)
        assertTrue(snapshot.clients.isEmpty())
    }

    @Test
    fun `Binder 返回 11 时不得判关——enabled 为 null 表示不拦`() {
        // 2026-10-20 现场回归（VIN 381425）：TCAM 在"热点开着但还没客户端"时稳定返回 11，
        // 旧断言把 11 当成"确定关闭"，导致 1.1.34 起把本可连上的场景整条拦死。
        // 现在 11 只作为"读不准"（null ⇒ 不拦），由后续真实连接结果说话。
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(null, null),
            binderState = { 11 },
        )

        assertEquals(11, snapshot.state)
        assertNull(snapshot.enabled)
        assertFalse(snapshot.connected)
    }

    @Test
    fun `Binder 抛异常时快照仍全空不崩`() {
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(null, null),
            binderState = { throw IllegalStateException("binder boom") },
        )

        assertUnreadable(snapshot)
    }

    @Test
    fun `SDK 有值时优先于 Binder`() {
        FakeWifiAp.created = null
        FakeExternalWifiAp.created = FakeExternalWifiAp(state = 13, enabled = true)
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(FakeExternalWifiAp::class.java, FakeWifiAp::class.java),
            binderState = { 11 },          // Binder 说关，但 SDK 说开 → 采信 SDK
        )

        assertEquals(13, snapshot.state)
        assertTrue(snapshot.connected)
    }

    // ── 厂商门面 getSystemService("tcam_wifi")（2026-10-20 framework 研究结论）──────

    @Test
    fun `门面可用时直接采信并带客户端列表`() {
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(null, null),
            binderState = { 11 },          // 门面优先，Binder 不参与
            serviceSnapshot = {
                TcamWifiServiceClient.Snapshot(
                    state = 13,
                    enabled = true,
                    clients = listOf(
                        TcamWifiServiceClient.Client("iPhone", "be:fc:1f:83:7e:0e", "192.168.15.7", 2),
                    ),
                )
            },
        )

        assertEquals(13, snapshot.state)
        assertTrue(snapshot.connected)
        assertEquals(1, snapshot.clients.size)
        assertEquals("192.168.15.7", snapshot.clients[0].ip)
        assertEquals("iPhone", snapshot.clients[0].name)
    }

    @Test
    fun `门面只给客户端不给状态时按客户端判定已连`() {
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(null, null),
            binderState = { null },
            serviceSnapshot = {
                TcamWifiServiceClient.Snapshot(
                    state = null,
                    enabled = null,
                    clients = listOf(
                        TcamWifiServiceClient.Client("iPhone", null, "192.168.15.9", 2),
                    ),
                )
            },
        )

        assertNull(snapshot.state)
        assertEquals(1, snapshot.clients.size)
    }

    @Test
    fun `门面返回全空时回落到 Binder`() {
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(null, null),
            binderState = { 13 },
            serviceSnapshot = { TcamWifiServiceClient.Snapshot(null, null, emptyList()) },
        )

        assertEquals(13, snapshot.state)
        assertTrue(snapshot.connected)
    }

    @Test
    fun `门面抛异常不影响后续兜底`() {
        val snapshot = EcarxWifiApReader.readWith(
            null,
            lookup(null, null),
            binderState = { 11 },
            serviceSnapshot = { throw IllegalStateException("service boom") },
        )

        assertEquals(11, snapshot.state)
        assertFalse(snapshot.connected)
    }
}
