package com.dbtool.carplay.bridge.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dbtool.carplay.api.media.CarNowPlaying
import com.dbtool.carplay.api.media.CarNowPlayingSource
import com.shilapi.xcertplay.media.CarPlayMediaSessionService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「iPhone 现在在放什么」的读取端 —— 通过 **Media3 的公开接口**连上游的
 * [CarPlayMediaSessionService]。
 *
 * ## 为什么不直接读上游的 `CarPlayMediaSessionBridge`
 *
 * 上游把 iAP2 的元数据与封面字节封在那个 bridge 里，但它是 **`internal`**。
 * Kotlin 的 `internal` 是**模块级**可见性：`:car:core-bridge` 虽然被允许
 * `import com.shilapi.xcertplay.*`，也照样读不到 `internal` 符号。
 *
 * 两条出路：
 *
 * 1. 给上游加一个公开接缝 —— 要登记 `PATCHES.md`，还会破坏"足迹复核为空"这条硬规则；
 * 2. **走 Media3 已经公开的那扇门**：上游那个类是一个标准的 `MediaSessionService`
 *    （`exported=true`，带 `androidx.media3.session.MediaSessionService` 的 intent-filter），
 *    任何同应用组件都能用 `MediaBrowser` + `MediaController` 连上它。
 *
 * 选 2。附带好处：这条路子与 DBTOOL2.0 完全同构 —— 它也是拿标准 `MediaController`
 * 去读播放状态、发 transport 命令的。
 *
 * ## 读得到什么
 *
 * | 字段 | 来源 |
 * |---|---|
 * | `title` / `artist` / `album` / `duration` | 上游 `Iap2NowPlayingState` → `MediaMetadata` |
 * | `artworkBytes` | 上游 `MediaMetadata.setArtworkData(...)`，即 iPhone 经 iAP2 文件传输送来的封面原始字节 |
 * | `isPlaying` | `Player.isPlaying`（上游由 iAP2 播放状态映射而来） |
 * | `positionMillis` | `Player.currentPosition`（上游是外推值，不是每帧推送） |
 *
 * ## 连接与重连
 *
 * 上游的 `CarPlayMediaSessionService` 只在 CarPlay 会话期间存在
 * （`CarPlayMediaSessionBridge.attach` 里 `startService`、`detach` 里 `stopService`）。
 * 所以**连不上是常态**：会话还没起来、或者刚结束。这里不把连不上当错误，
 * 只按退避重试，并且把"当前值 = null"如实上报——上层据此走"不知道"的分支。
 *
 * ⚠️ **会话结束时必须 [disconnect]，不能只"不管它"**：绑定用的是
 * `bindService(BIND_AUTO_CREATE)`，会**重建**上游刚 `stopService` 掉的服务，
 * 而上游把 idle 通知设成了 ALWAYS ⇒ 车机上会凭空挂着一条媒体通知。详见 [disconnect]。
 *
 * ## 线程
 *
 * Media3 的 `MediaController` 要求**在它的应用线程**（这里是主线程）上访问。
 * 本类所有 controller 操作都排在主线程；[observe] 的回调也在主线程发出。
 * 订阅方若要在别的线程上干活，自己 post 过去。
 */
class CarPlayNowPlayingClient(context: Context) : CarNowPlayingSource {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listeners = CopyOnWriteArrayList<(CarNowPlaying?) -> Unit>()

    @Volatile
    private var latest: CarNowPlaying? = null

    @Volatile
    private var browser: MediaBrowser? = null

    private val connecting = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * 是否**应该**保持连接。
     *
     * 与 [closed] 的区别：[closed] 是终态（进程内不再用），本标记可反复开关。
     * 它必须存在，否则会有一个很难发现的副作用 —— 见 [disconnect]。
     */
    @Volatile
    private var enabled = false

    /** 排队中的重连任务，[disconnect] 时要能精确取消它。 */
    @Volatile
    private var pendingConnect: Runnable? = null

    private var retryAttempts = 0

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // A temporarily empty timeline is not a session disconnect. Lifecycle teardown
            // still publishes null explicitly; real pause/stop updates have metadata.
            readSnapshot(player)?.let(::publish)
        }
    }

    private val browserListener = object : MediaBrowser.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.i(TAG, "与 CarPlayMediaSessionService 断开，稍后重连")
            browser = null
            scheduleConnect(RECONNECT_BASE_MILLIS)
        }
    }

    // ------------------------------------------------------------- 生命周期

    /** 幂等。连不上会自己退避重试，不需要调用方轮询。 */
    fun connect() {
        // 终态之后不许再开——否则 release() 排在主线程上的拆解逻辑会被本行
        // 重新置回 enabled 而跳过，连接就漏掉了。
        if (closed.get()) return
        enabled = true
        attemptConnect()
    }

    /**
     * 断开并停止重连，但**保持可用**（之后还能再 [connect]）。
     *
     * ## 为什么必须在会话结束时断开（不是可选优化）
     *
     * 上游在 `onCreate` 里写了 `setShowNotificationForIdlePlayer(
     * SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)` —— media3 的语义是
     * **只要服务活着，即使播放器是 idle 也会挂一条媒体通知**。
     *
     * 而 `MediaBrowser` 是用 `bindService(BIND_AUTO_CREATE)` 连的：
     * **绑定会重建已经被 `stopService` 停掉的服务**。于是"用户停掉 CarPlay 之后，
     * 我们每 3s 重连一次"就会让那个媒体会话服务一直复活，
     * 车机上凭空挂着一条媒体通知 —— 用户没在放任何东西。
     *
     * 上游自己的节奏是 `attach` 里 `startService` / `detach` 里 `stopService`，
     * 服务生命周期与 CarPlay 会话严格对齐；我们要跟它一致。
     *
     * 幂等。所有操作排在主线程（Media3 要求 controller 在它的应用线程上访问）。
     *
     * ⚠️ **补一句上面没说透的**：`stopService()` 停的是"被 start 过的服务"，
     * 而**绑定关系还在时服务不会被销毁**（bound service 活到最后一个客户端解绑）。
     * 也就是说只要我们还 bind 着，上游那个 `stopService` 根本杀不掉它 ——
     * 断开连接不只是"避免重连把它拉起来"，而是**唯一能让它退出的手段**。
     */
    fun disconnect() {
        enabled = false
        mainHandler.post {
            // ⚠️ 这行不能省：`stop()` 之后紧接着 `start()` 时，本 lambda 可能排在
            // 新 `connect()` 之后才执行。那时连接已经（或正在）重新建立，
            // 再拆一次会把它拆掉，而且**不会再有重连**——卡片从此不再更新。
            // `enabled` 已被新的 connect() 置回 true ⇒ 保留这条连接。
            if (enabled) return@post
            pendingConnect?.let { mainHandler.removeCallbacks(it) }
            pendingConnect = null
            runCatching { browser?.release() }
            browser = null
            connecting.set(false)
            retryAttempts = 0
            publish(null)
        }
    }

    /** 终态：断开并释放。释放后不可再用。 */
    fun release() {
        enabled = false
        if (!closed.compareAndSet(false, true)) return
        mainHandler.post {
            pendingConnect?.let { mainHandler.removeCallbacks(it) }
            pendingConnect = null
            runCatching { browser?.release() }
            browser = null
            connecting.set(false)
            publish(null)
        }
    }

    private fun attemptConnect() {
        if (closed.get() || !enabled) return
        if (browser != null) return
        if (!connecting.compareAndSet(false, true)) return
        val token = runCatching {
            SessionToken(
                appContext,
                ComponentName(appContext, CarPlayMediaSessionService::class.java),
            )
        }.getOrElse { error ->
            connecting.set(false)
            Log.w(TAG, "构造 SessionToken 失败", error)
            return
        }
        val future = runCatching {
            MediaBrowser.Builder(appContext, token)
                .setListener(browserListener)
                .buildAsync()
        }.getOrElse { error ->
            connecting.set(false)
            Log.w(TAG, "MediaBrowser 构建失败", error)
            scheduleConnect(RECONNECT_BASE_MILLIS)
            return
        }
        future.addListener(
            { onBrowserReady(future) },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun onBrowserReady(future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>) {
        connecting.set(false)
        // 期间可能已经被 disconnect() / release() —— 连上了也要立刻放掉，
        // 否则这次绑定已经把服务拉起来了，通知照样会冒出来。
        if (closed.get() || !enabled) {
            runCatching { future.get().release() }
            return
        }
        val connected = runCatching { future.get() }.getOrElse { error ->
            Log.i(TAG, "还没连上 CarPlayMediaSessionService（会话未建立？）：$error")
            null
        }
        if (connected == null) {
            scheduleConnect(backoffMillis())
            return
        }
        retryAttempts = 0
        browser = connected
        connected.addListener(playerListener)
        Log.i(TAG, "已连上 CarPlayMediaSessionService，开始读取正在播放")
        // Reconnecting can initially expose the service placeholder before its snapshot.
        // Explicit disconnect/release owns clearing, not a transport reconstruction.
        readSnapshot(connected)?.let(::publish)
    }

    private fun scheduleConnect(delayMillis: Long) {
        if (closed.get() || !enabled) return
        if (browser != null) return
        retryAttempts += 1
        Log.i(TAG, "第 $retryAttempts 次重连将在 ${delayMillis}ms 后开始")
        val task = Runnable { attemptConnect() }
        pendingConnect = task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun backoffMillis(): Long {
        val step = retryAttempts.coerceAtMost(MAX_BACKOFF_STEPS).coerceAtLeast(1)
        return RECONNECT_BASE_MILLIS * step
    }

    // ------------------------------------------------------------- 订阅

    override fun observe(listener: (CarNowPlaying?) -> Unit): () -> Unit {
        listeners += listener
        // 立刻回调一次当前值：订阅方不该自己去问一遍，那是竞态的来源。
        val current = latest
        mainHandler.post { runCatching { listener(current) } }
        return {
            listeners -= listener
            Unit
        }
    }

    override fun current(): CarNowPlaying? = latest

    // ------------------------------------------------------------ 发令（2026-10-11）
    //
    // 底栏上的 ⏸ / ⏭ 要真的能按 ⇒ 这里把三条命令落到 MediaController 上。
    //
    // 两个约束：
    // 1. **必须走主线程**：Media3 要求 controller 在它的应用线程上访问，本类所有
    //    controller 操作都排在 `mainHandler` 上（见类头的说明）；
    // 2. **不要新开字段**：Media3 的 `MediaBrowser` **就是** `MediaController`
    //    （继承关系），所以直接复用 [browser]，不需要再存一个 controller 引用。
    //
    // 还没连上（`browser == null`）时是**安全空操作**：底栏在未连接时点它不该崩。

    override fun play() = withController { it.play() }

    override fun pause() = withController { it.pause() }

    override fun next() = withController { it.seekToNextMediaItem() }

    /**
     * 上一曲（2026-10-19 新增）。
     *
     * 上游 `CarPlayRemotePlayer.handleSeek` 把 `COMMAND_SEEK_TO_PREVIOUS(_MEDIA_ITEM)`
     * 翻成 iAP2 的 `PREVIOUS`，与 [next] 走同一条路 —— 所以这里只要发对命令，
     * 不需要给上游开任何接缝。
     */
    override fun previous() = withController { it.seekToPreviousMediaItem() }

    private fun withController(block: (MediaController) -> Unit) {
        mainHandler.post {
            val controller = browser ?: return@post
            runCatching { block(controller) }
        }
    }

    private fun publish(value: CarNowPlaying?) {
        if (latest == value) return
        latest = value
        Log.i(TAG, "正在播放更新：${value ?: "无"}")
        listeners.forEach { listener ->
            // 单个订阅方抛异常不能拖垮其它订阅方（车机适配器属于"锦上添花"）。
            runCatching { listener(value) }
                .onFailure { Log.w(TAG, "订阅方回调异常", it) }
        }
    }

    /**
     * 从 Player 读一份快照。
     *
     * 歌名与歌手都为空时返回 **null** 而不是"空快照"：
     * 上游在会话刚建立、还没收到 `NowPlayingUpdate` 时会先发一条占位元数据，
     * 把它当成"有一首歌但没有名字"会让车机卡片出现一张空卡片。
     */
    private fun readSnapshot(player: Player): CarNowPlaying? {
        val metadata: MediaMetadata = runCatching { player.mediaMetadata }
            .getOrDefault(MediaMetadata.EMPTY)
        val title = metadata.title?.toString().orEmpty()
        val artist = metadata.artist?.toString().orEmpty()
        if (title.isBlank() && artist.isBlank()) return null
        return CarNowPlaying(
            title = title,
            artist = artist,
            album = metadata.albumTitle?.toString().orEmpty(),
            durationMillis = durationOf(player, metadata),
            positionMillis = runCatching { player.currentPosition }
                .getOrDefault(0L)
                .coerceAtLeast(0L),
            // isPlaying also depends on READY/suppression and can briefly become false while
            // Media3 applies metadata. The remote play intent reflects actual iAP2 pause/stop.
            isPlaying = runCatching { player.playWhenReady }.getOrDefault(latest?.isPlaying ?: false),
            artworkBytes = artworkOf(metadata),
            trackNumber = metadata.trackNumber,
            mediaId = runCatching { player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank) }
                .getOrNull() ?: latest?.mediaId,
        )
    }

    /**
     * 取封面字节，带一个**尺寸上限**。
     *
     * ## 封面确实会经 binder 传到我们这里（已核对 media3 1.11.1 字节码）
     *
     * `MediaSessionImpl` 里**一个字都没提 artwork** ⇒ 它把 `PlayerInfo`（内含
     * `MediaItem` → `MediaMetadata`）原样交给 controller，`artworkData` 不做任何处理。
     * media3 里所有 artwork 相关的代码都在 **legacy 兼容路径**上
     * （`LegacyConversions` / `MediaSessionLegacyStub` / `MediaControllerImplLegacy`），
     * 那是 `MediaSessionCompat` 桥，与我们无关。
     *
     * ## 所以上限只能自己加
     *
     * Binder 单次事务上限 **1MB**，而 media3-session 1.11.1 里**搜不到
     * `TransactionTooLargeException`** ⇒ 它不会替我们兜底。一张超大封面（有些播客 /
     * 无损专辑会给几 MB 的图）会让整条 `PlayerInfo` 事务超限抛异常 —— 那是**整个媒体
     * 会话服务**一起崩，比"这张封面显示不出来"严重得多。
     *
     * 超限时**丢弃并大声记日志**，而不是原样透传。正常专辑封面（iAP2 给的一般是
     * 几百 KB 以内的 JPEG）远达不到 [MAX_ARTWORK_BYTES]。
     */
    private fun artworkOf(metadata: MediaMetadata): ByteArray? {
        val data = metadata.artworkData ?: return null
        if (data.size > MAX_ARTWORK_BYTES) {
            Log.w(
                TAG,
                "封面过大（${data.size} 字节 > $MAX_ARTWORK_BYTES），为避免 Binder 事务超限已丢弃；" +
                    "若现场出现「歌名在、封面不出来」，先看这一行",
            )
            return null
        }
        return data
    }

    private fun durationOf(player: Player, metadata: MediaMetadata): Long {
        metadata.durationMs?.let { if (it > 0) return it }
        val fromPlayer = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        return if (fromPlayer > 0 && fromPlayer != C.TIME_UNSET) fromPlayer else 0L
    }

    private companion object {
        const val TAG = "CarPlay-NowPlaying"

        /** 连不上时的基础退避。会话没起来时连不上是常态，所以别退避得太狠。 */
        const val RECONNECT_BASE_MILLIS = 3_000L
        const val MAX_BACKOFF_STEPS = 5

        /**
         * 封面字节上限。Binder 单次事务上限 1MB，取一半留余量
         * （同一事务里还有 `PlayerInfo` 的其它字段）。
         */
        const val MAX_ARTWORK_BYTES = 512 * 1024
    }
}
