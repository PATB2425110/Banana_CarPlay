package com.dbtool.carplay.bridge.developer

import com.dbtool.carplay.api.developer.CarDeveloperEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拉黑判定的两条纯逻辑：**解释远端应答** 与 **合成最终判定**。
 *
 * ## 为什么值得单测
 *
 * 这两条决定"这台车还能不能用 CarPlay"，而它们的错误方向代价极不对称：
 *
 * - **误拦**（把"没问到"当成"已拉黑"）⇒ 一次服务端抖动就让**整个车队**连不上。
 *   车机常年没网，这条路径一点都不罕见。
 * - **漏拦**（把"没表态"当成"明确回未拉黑"）⇒ 管理员点了拉黑却拦不住，
 *   而页面上还显示着"已拉黑"，看起来完全正常。
 *
 * 两种都不会在现场报错，只会表现为"有时候连不上 / 有时候拦不住"。
 *
 * 本模块的单测是**纯 JVM**（没有 Robolectric，`org.json` 在这里是 stub），
 * 所以被测的是"拿到值之后怎么判"，取值那一步在 `CarDeviceStatusSync.query` 里。
 */
class CarMfiBlockPolicyTest {

    // ------------------------------------------------------ 远端应答的解释

    @Test
    fun `2xx 且 blocked=true 带回原因`() {
        assertEquals(
            RemoteBlockStatus.Blocked("管理员停用"),
            interpretBlockResponse(200, blocked = true, reason = "管理员停用"),
        )
    }

    @Test
    fun `2xx 且 blocked=true 但没给原因则落到兜底文案`() {
        // 服务端老版本 / reason 被清空：用户仍要读到一句能照做的话，不能是空白。
        for (reason in listOf(null, "", "   ")) {
            assertEquals(
                "reason=${reason?.let { "「$it」" } ?: "null"}",
                RemoteBlockStatus.Blocked(CarDeveloperEndpoints.BLOCKED_REASON_FALLBACK),
                interpretBlockResponse(200, blocked = true, reason = reason),
            )
        }
    }

    @Test
    fun `2xx 且 blocked=false 判为未拉黑`() {
        assertEquals(
            RemoteBlockStatus.Clear,
            interpretBlockResponse(200, blocked = false, reason = null),
        )
    }

    @Test
    fun `服务端没表态（blocked 为 null）一律算没问到`() {
        // 字段缺失（老服务端）/ JSON null / 类型不对 —— 取值那一步都给出 null。
        // 这里必须落到 Unreachable（→ 退回落盘缓存），**不能**当成"明确回未拉黑"，
        // 否则一次字段改名就能把已拉黑的车悄悄放行。
        assertEquals(
            RemoteBlockStatus.Unreachable,
            interpretBlockResponse(200, blocked = null, reason = "说了一堆但没表态"),
        )
    }

    @Test
    fun `非 2xx 即使带着 blocked=true 也不拦`() {
        // HTTP 层就没成功，body 里的 blocked 可能来自中间代理的错误页。
        for (status in listOf(400, 401, 403, 404, 429, 500, 502, 503)) {
            assertEquals(
                "status=$status",
                RemoteBlockStatus.Unreachable,
                interpretBlockResponse(status, blocked = true, reason = "停用"),
            )
        }
    }

    // ------------------------------------------------------ 判定策略（sticky）

    @Test
    fun `远端说已拉黑则拦，并把原因写进本地`() {
        val store = FakeBlockStore()
        assertEquals(
            CarBlockStatus.Blocked("停用", fromCache = false),
            resolveBlockStatus(store, RemoteBlockStatus.Blocked("停用")),
        )
        assertTrue(store.isBlocked)
        assertEquals("停用", store.reason)
    }

    @Test
    fun `远端明确说未拉黑则放行并清除本地标记`() {
        val store = FakeBlockStore(initiallyBlocked = true, initialReason = "旧原因")
        assertEquals(CarBlockStatus.Clear, resolveBlockStatus(store, RemoteBlockStatus.Clear))
        assertFalse(store.isBlocked)
        // 原因要一起清：留着会让下一次拉黑（若服务端没给 reason）显示上一次的旧文案。
        assertEquals("", store.reason)
    }

    @Test
    fun `断网且本地有标记则照样拦（sticky 的关键一格）`() {
        val store = FakeBlockStore(initiallyBlocked = true, initialReason = "停用")
        assertEquals(
            CarBlockStatus.Blocked("停用", fromCache = true),
            resolveBlockStatus(store, RemoteBlockStatus.Unreachable),
        )
    }

    @Test
    fun `断网且本地有标记但没记原因则照样拦，用兜底文案`() {
        val store = FakeBlockStore(initiallyBlocked = true, initialReason = "")
        assertEquals(
            CarBlockStatus.Blocked(CarDeveloperEndpoints.BLOCKED_REASON_FALLBACK, fromCache = true),
            resolveBlockStatus(store, RemoteBlockStatus.Unreachable),
        )
    }

    @Test
    fun `断网且本地无标记则放行（服务端故障绝不能锁死车队）`() {
        assertEquals(
            CarBlockStatus.Unknown,
            resolveBlockStatus(FakeBlockStore(), RemoteBlockStatus.Unreachable),
        )
    }

    @Test
    fun `断网不会清掉本地标记`() {
        val store = FakeBlockStore(initiallyBlocked = true, initialReason = "停用")
        resolveBlockStatus(store, RemoteBlockStatus.Unreachable)
        assertTrue("sticky 语义：只有明确回 false 才清除", store.isBlocked)
    }

    @Test
    fun `心跳 500 且 body 里写着 blocked=true 也不拦，也不写本地`() {
        // 端到端串一遍：HTTP 层失败 ⇒ 解释成 Unreachable ⇒ 判定成 Unknown（放行）。
        val store = FakeBlockStore()
        val remote = interpretBlockResponse(500, blocked = true, reason = "停用")
        assertEquals(CarBlockStatus.Unknown, resolveBlockStatus(store, remote))
        assertFalse(store.isBlocked)
    }
}

/** [CarMfiBlockStore] 的内存实现：单测里替代 SharedPreferences。 */
private class FakeBlockStore(
    initiallyBlocked: Boolean = false,
    initialReason: String = "",
) : CarMfiBlockStore {

    override var isBlocked: Boolean = initiallyBlocked
        private set

    override var reason: String = initialReason
        private set

    override fun cache(reason: String) {
        isBlocked = true
        this.reason = reason
    }

    override fun clear() {
        isBlocked = false
        reason = ""
    }
}
