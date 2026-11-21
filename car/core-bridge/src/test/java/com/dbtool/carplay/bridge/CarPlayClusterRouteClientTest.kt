package com.dbtool.carplay.bridge

import com.dbtool.carplay.bridge.navigation.CarPlayClusterRouteClient
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0119（2026-10-14 车主口径）契约：「**启动地图导航就自动投屏**」的触发端。
 *
 * ## 为什么必须钉住
 *
 * 这一条链路的失败模式**在手机上完全看不出来**（要真车 + 车机仪表 + 一个真在导航的
 * iPhone），而且大多是"静默失败"：
 *
 * | 写错的地方 | 现场表现 |
 * | --- | --- |
 * | 把 `null` / `routeActive=false` 直接当成"导航结束" | 每个路口等个灯，仪表就被收掉 |
 * | 结论文变了却不发请求（或每帧都发） | 导航开始仪表不亮 / 反复建窗拆窗 |
 * | 关掉开关时不主动收起 | "开关关了、仪表还亮着"，比没有开关更困惑 |
 * | 超时用真实时间而不是注入的时钟 | 单测要么真等 8 秒、要么测不到边界 |
 *
 * ## 走真实的桥，不塞测试钩子
 *
 * 订阅侧刻意走**真的** [CarPlayNavigationBridge]（`observe` / `attach` / `publish`），
 * 而不是给客户端开一个"测试专用入口"：这样连"订阅接线本身对不对、`null` 到底怎么
 * 送进来"都一起被证明。代价是每条用例要自己 `attach` 一个 owner，
 * 并在 `@After` 里断开、`detach`（`publish` 只认当前 owner）。
 *
 * ⚠️ 上游按"变化"投递（`publish` 里 `snapshot == state` 会直接 return），所以要模拟
 * "又推了一帧"必须让快照真的不同 —— 这里用递增的 `revision`，与真机上的推帧同形。
 */
class CarPlayClusterRouteClientTest {

    /** 本链路发出去的请求序列（true = 该显示，false = 该收起）。 */
    private val requests = mutableListOf<Boolean>()

    /** 排下的定时任务（[Pair] 的第一项是延迟毫秒）。单测不真等。 */
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()

    /** 注入的单调时钟（毫秒）。测试推进它，就等价于"时间过去了"。 */
    private var now = 0L

    /** 让每次 publish 的快照都不同（上游按"变化"投递）。 */
    private var revision = 0L

    private var projectionEnabled = true
    private var displayAvailable = true
    private var client: CarPlayClusterRouteClient? = null
    private val owner = Any()

    @After
    fun tearDown() {
        client?.disconnect()
        client = null
        CarPlayNavigationBridge.detach(owner)
    }

    // ------------------------------------------------------------ 供体

    private fun start(): CarPlayClusterRouteClient {
        val created = CarPlayClusterRouteClient(
            onVisibilityRequest = { requests += it },
            isProjectionEnabled = { projectionEnabled },
            isDisplayAvailable = { displayAvailable },
            clock = { now },
            postTimeout = { delayMs, task -> scheduled += delayMs to task },
            cancelTimeout = { task -> scheduled.removeAll { it.second === task } },
            log = {},
        )
        created.connect()
        CarPlayNavigationBridge.attach(owner)
        client = created
        return created
    }

    /** 推一帧"导航进行中"。 */
    private fun pushRouteActive() {
        CarPlayNavigationBridge.publish(
            owner,
            CarNavigationState(routeActive = true, revision = ++revision),
        )
    }

    /** 推一帧 `routeActive = false`（上游折叠后的"不活跃"）。 */
    private fun pushRouteInactive() {
        CarPlayNavigationBridge.publish(
            owner,
            CarNavigationState(routeActive = false, revision = ++revision),
        )
    }

    /** 推 `null` 快照（契约里 `null` **也是**"导航不活跃"，不是"明确结束"）。 */
    private fun pushNull() {
        CarPlayNavigationBridge.publish(owner, null)
    }

    /** 跑掉此刻排着的定时任务（等价于"定时器到点了"）。 */
    private fun fireTimers() {
        val due = scheduled.toList()
        scheduled.clear()
        due.forEach { (_, task) -> task.run() }
    }

    // ------------------------------------------------------------ 导航开始

    @Test
    fun `导航开始请求显示一次，再来一帧不重复请求`() {
        val c = start()

        pushRouteActive()
        assertEquals("导航开始必须请求显示", listOf(true), requests)
        assertTrue(c.status().shown)

        // iPhone 又推了一帧（距离/秒数变了，结论没变）⇒ 不能再来一次请求：
        // 落点那条链一次请求就是一次建窗 + 抢屏。
        pushRouteActive()
        assertEquals("结论没变就不该再请求一次", listOf(true), requests)
    }

    @Test
    fun `前置条件不满足时不请求显示`() {
        projectionEnabled = false
        start()

        pushRouteActive()
        assertEquals("投屏总闸关着，什么都不该请求", emptyList<Boolean>(), requests)

        // 用户在会话中途把总闸打开 + 选好屏 ⇒ 下一帧导航状态就该把画面投上去
        // （这一条钉的是"门的前置条件每帧现读"，漏了它现场表现是"都开好了还是不投"）。
        projectionEnabled = true
        displayAvailable = true
        pushRouteActive()
        assertEquals(listOf(true), requests)
    }

    @Test
    fun `未选副屏时不请求显示`() {
        displayAvailable = false
        start()

        pushRouteActive()
        assertEquals(emptyList<Boolean>(), requests)
    }

    // ------------------------------------------------------------ 导航停

    @Test
    fun `持续不活跃未超阈值时不请求收起`() {
        start()
        pushRouteActive()
        pushRouteInactive()

        assertEquals("非活跃不等于明确结束，一帧都不许收", listOf(true), requests)
        assertEquals(
            "静默期必须在到达阈值时才判定",
            CarPlayClusterRouteClient.NAV_STOP_GRACE_MS,
            scheduled.single().first,
        )

        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS - 1
        fireTimers()
        assertEquals("还没到阈值，不能收屏", listOf(true), requests)
    }

    @Test
    fun `恢复活跃后旧的静默期作废`() {
        start()
        pushRouteActive()
        pushRouteInactive()

        // 路口等灯结束、iPhone 又开始推帧 ⇒ 那个"导航停"的定时器必须作废。
        pushRouteActive()
        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS * 10
        fireTimers()

        assertEquals("恢复导航后绝不能因为旧计时器收屏", listOf(true), requests)
    }

    @Test
    fun `持续不活跃超过阈值请求收起一次`() {
        start()
        pushRouteActive()
        pushRouteInactive()

        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS
        fireTimers()
        assertEquals("停够了才算明确结束导航", listOf(true, false), requests)

        // 再跑一次（万一还有排着的任务）也只能收一次 —— 收起 = 拆窗 + 把仪表还回原厂。
        fireTimers()
        assertEquals(listOf(true, false), requests)
    }

    @Test
    fun `null 快照也走超时那条路，不立刻收屏`() {
        start()
        pushRouteActive()
        pushNull()

        assertEquals("null = 导航不活跃，不是明确结束", listOf(true), requests)
        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS
        fireTimers()
        assertEquals(listOf(true, false), requests)
    }

    @Test
    fun `超时判定用的是注入的时钟`() {
        start()
        pushRouteActive()
        pushRouteInactive()

        // 注入的时钟没动过：即便任务真的跑了，也**不能**算"停够 8 秒"。
        // （若实现里用了 SystemClock / 真实时间，这一条会在真机时间流逝后误收屏。）
        fireTimers()
        assertEquals("时钟没推进就不该判成超时", listOf(true), requests)

        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS
        fireTimers()
        assertEquals(listOf(true, false), requests)
    }

    // ------------------------------------------------------------ 开关

    @Test
    fun `开关关掉时若正在投屏就请求收起一次`() {
        val c = start()
        pushRouteActive()

        c.setEnabled(false)
        assertEquals("关掉开关不能留一块亮着的仪表", listOf(true, false), requests)

        // 关掉之后：导航再怎么变都不再自动投。
        pushRouteInactive()
        pushRouteActive()
        assertEquals(listOf(true, false), requests)

        // 幂等：再关一次什么都不发。
        c.setEnabled(false)
        assertEquals(listOf(true, false), requests)

        assertFalse(c.status().enabled)
    }

    @Test
    fun `没在投屏时关掉开关不发任何请求`() {
        val c = start()

        c.setEnabled(false)
        assertEquals("没投着就不用收起", emptyList<Boolean>(), requests)
    }

    @Test
    fun `本链路没投过的东西，静默超时也不许去收起`() {
        start()

        // 会话刚起来、导航还没开始：上游只有"不活跃"。静默满 8 秒后会走到"明确结束"
        // 那一支 —— 但本链路一次都没请求过显示，所以**一个字节都不该发**：
            // 那块画面可能是用户通过设置页手动打开的，不归本开关管。
        pushRouteInactive()
        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS
        fireTimers()

        assertEquals("不许把用户自己开的投屏收掉", emptyList<Boolean>(), requests)
    }

    @Test
    fun `重新打开时若导航仍在进行就补一次显示`() {
        val c = start()
        pushRouteActive()

        c.setEnabled(false)
        c.setEnabled(true)
        assertEquals(
            "重新打开要按当前导航状态补一次，不能等下一次 iPhone 推帧",
            listOf(true, false, true),
            requests,
        )
        assertTrue(c.status().enabled)
    }

    // ------------------------------------------------------------ 订阅

    @Test
    fun `断开订阅后不再有任何请求`() {
        val c = start()
        pushRouteActive()
        c.disconnect()

        pushRouteInactive()
        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS
        fireTimers()
        assertEquals("断开后不许再碰仪表", listOf(true), requests)
        assertFalse(c.status().subscribed)
    }

    @Test
    fun `connect 与 disconnect 都是幂等的`() {
        val c = start()
        c.connect()
        c.connect()
        pushRouteActive()
        // 重复 connect 若真的连出两条订阅，这里会看到两次请求。
        assertEquals(listOf(true), requests)

        c.disconnect()
        c.disconnect()
        assertFalse(c.status().subscribed)
    }

    @Test
    fun `断开重连后不会凭上一会话的导航状态补投`() {
        val c = start()
        pushRouteActive()

        // 会话结束（仪表已还给车机）：断开订阅，本链路也要把"上次导航在进行"忘掉。
        CarPlayNavigationBridge.detach(owner)
        c.disconnect()
        c.connect()
        CarPlayNavigationBridge.attach(owner)

        // 新会话里导航还没开始 —— 把开关拨一次也不能因此把画面投上去。
        c.setEnabled(false)
        c.setEnabled(true)
        assertEquals("上一会话的导航状态不许被当成「现在正在导航」", listOf(true), requests)
    }
}
