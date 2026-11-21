package com.dbtool.carplay.ecarx.bluetooth

/**
 * A2DP 代理的**生命周期管理**（2026-10-25）。
 *
 * ## 为什么单独抽出来
 *
 * `EcarxBluetoothAudioGuard` 直接依赖 Android 的 `BluetoothAdapter` /
 * `BluetoothProfile`，纯 JVM 单测无法构造，导致「代理是否被反复重建」
 * 「失效后能否恢复」这些**纯状态逻辑**无法验证。
 *
 * 这里把「缓存 / 建立 / 失效 / 释放」收成一个不依赖 Android 类型的泛型类，
 * 既能被真实代码复用，也能被普通 JUnit 直接测。
 *
 * ## 背景（现场日志 + 独立复审）
 *
 * 1.2.53 诊断包显示：会话期间 10.5 分钟内
 * `BluetoothA2dpSink: Proxy object connected` 紧跟 `close mService = null`
 * 成对出现 **209 次** —— 旧实现每轮巡检都 `getProfileProxy` + `closeProfileProxy`。
 *
 * 首版缓存化被复审抓出两个 P0（探针实测复现）：
 * - **P0-1**：`disconnectA2dp()` 绕过 registry 自己建代理 ⇒ 只要 A2DP 还连着，
 *   每轮巡检仍新增一对，抖动只是变稀不是消失；
 * - **P0-2**：没有关闭态与世代 ⇒ `close()` 之后 `acquire()` 仍能建成并缓存，
 *   无人释放；旧监听器的迟到 `invalidate()` 会把**新建立的代理**清掉但不释放
 *   （探针 `[2] BReleased=false B!=C acquireCount=3 releaseCount=1`）。
 *
 * 本版据此重写：`closed` + `epoch` 世代、`invalidate(expected)` 按身份匹配、
 * `obtain()` 移到锁外（避免主线程/回调线程被 2 秒 latch 阻塞）、
 * 迟到建成的代理自行释放、生命周期方法不抛异常。
 *
 * ## 并发约定
 *
 * - [acquire] 可并发；锁内只做状态读写，`obtain()` 在锁外执行；
 * - `closed` 期间 [acquire] 返回 null；[open] 重新开启（每次 `start()`）；
 * - [invalidate] 只在传入实例与当前缓存**同一对象**时才清除，避免迟到回调误伤；
 * - [close] 幂等，且保证不向调用方抛异常。
 *
 * @param obtain 建立底层代理；返回 `null` 表示建立失败
 * @param release 释放底层代理
 */
internal class A2dpProxyRegistry<T : Any>(
    private val obtain: () -> T?,
    private val release: (T) -> Unit,
) {

    private val lock = Any()

    /** 当前缓存的代理。`@Volatile`：快路径无锁读，必须让 close/invalidate 的写入可见。 */
    @Volatile
    private var cached: T? = null

    /** 已关闭：`close()` 之后、`open()` 之前，`acquire()` 一律返回 null。**初始即关闭**。 */
    @Volatile
    private var closed: Boolean = true

    /**
     * 正在建立中（P1-3 的实现核心）。
     *
     * `obtain()` 要等系统回调、最长 2 秒。为了让 `close()`（主线程）与
     * `invalidate()`（回调线程）**不必等这 2 秒**，`obtain()` 必须放在锁外；
     * 但锁外执行就会有多个线程同时进来 —— 用本标志让它们**跳过本轮**
     * （巡检是周期任务，跳过下一轮自然再来），而不是各自建一条绑定。
     */
    @Volatile
    private var obtaining: Boolean = false

    /** 世代号：每次 `close()` 自增。迟到的 `obtain()` 据此判定自己已过期。 */
    @Volatile
    private var epoch: Int = 0

    /** 累计建立次数（诊断/测试断言用）。 */
    @Volatile
    var acquireCount: Int = 0
        private set

    /** 累计释放次数（诊断/测试断言用）。 */
    @Volatile
    var releaseCount: Int = 0
        private set

    /** 重新开启（会话开始）。 */
    fun open() {
        synchronized(lock) {
            closed = false
        }
    }

    /**
     * 取代理：已缓存直接返回，否则建立一次并缓存。
     *
     * **`obtain()` 在锁外执行**：建代理要等系统回调（最长 2 秒），
     * 持锁会让 `close()`（主线程）与 `invalidate()`（回调线程）一起卡住。
     *
     * @return `null` = 未开启 / 建立失败 / 建立期间被 close（迟到的实例已自行释放）
     */
    fun acquire(): T? {
        cached?.let { return it }

        val myEpoch: Int
        synchronized(lock) {
            if (closed) return null
            cached?.let { return it }
            // 别人正在建：跳过本轮，不重复 bind（巡检是周期任务，下轮再来）。
            if (obtaining) return null
            obtaining = true
            myEpoch = epoch
        }

        var made: T? = null
        try {
            made = runCatching { obtain() }.getOrNull()
        } finally {
            synchronized(lock) {
                obtaining = false
                if (made == null) {
                    // 失败：什么都不必做，下轮重试。
                } else if (closed || epoch != myEpoch) {
                    // 建立期间被 close()：自己收拾，不进缓存（否则无人释放）。
                    releaseQuietly(made)
                } else {
                    cached = made
                    acquireCount++
                }
            }
        }
        if (made == null) return null
        // 若期间被 close，上面已释放；此时返回当前缓存（极可能是 null）。
        return synchronized(lock) { if (cached === made) made else cached }
    }

    /**
     * 标记**指定代理**失效（系统侧 `onServiceDisconnected` / 读设备失败）。
     *
     * 按身份匹配：只有当传入实例正是当前缓存时才处理；旧监听器的迟到回调
     * 因此不会误伤重新建立的代理。
     *
     * ## N2（2026-10-25 复审）：必须**释放**，不能只摘引用
     *
     * 只清缓存不 close 会有两种后果，取决于 ROM 的 profile 连接器语义：
     * - 若连接器自动重连：旧绑定永久挂着，每次回收/读失败就多一条永不 close
     *   的绑定；且下次 `acquire` 会再 `getProfileProxy`，等于恢复「每轮新建且
     *   不回收」的抖动（比修复前更糟）；
     * - 若连接器拒绝重复 connect（AOSP 8+ `"connect() called when already connected"`）：
     *   `getProfileProxy` 一直 false ⇒ `poll: getProfileProxy(11) refused` 刷屏 ⇒
     *   **整会话失明**，`stop()/start()` 也救不回来（旧绑定不在 `cached` 里）。
     *
     * `closeProfileProxy` 对已断开的服务是安全 no-op，因此这里直接释放。
     * 释放放在**锁外**：release 是 binder unbind，不应占着 registry 锁。
     */
    fun invalidate(expected: T) {
        var victim: T? = null
        synchronized(lock) {
            if (cached === expected) {
                victim = cached
                cached = null
            }
        }
        if (victim != null) releaseQuietly(victim)
    }

    /**
     * 会话结束：关闭并释放代理。幂等，可重复调用。
     *
     * 关闭后 [acquire] 返回 null，直到再次 [open]。
     */
    fun close() {
        val held: T?
        synchronized(lock) {
            closed = true
            epoch++
            held = cached
            cached = null
        }
        if (held != null) releaseQuietly(held)
    }

    /** 是否持有代理（测试与诊断用）。 */
    fun isHeld(): Boolean = cached != null

    /** 是否处于关闭态（测试与诊断用）。 */
    fun isClosed(): Boolean = closed

    /** 释放并计数；异常不外抛，保证生命周期方法安全。 */
    private fun releaseQuietly(p: T) {
        releaseCount++
        runCatching { release(p) }
    }
}
