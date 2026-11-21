package com.dbtool.carplay.ecarx.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 帆书（`io.dushu.car`）媒体卡片广播通道 —— **伪装成帆书**，把当前曲目推给车机媒体中心。
 *
 * 移植自参考实现 DBTOOL2.0（吉利车机**实车验证**）的 `FdBookBroadcaster`，
 * 广播常量 1:1 复刻，**不要改字符串**。
 *
 * ## 为什么必须伪装成帆书（这是整个方案的核心）
 *
 * 车机的媒体卡片 / dock / **仪表盘 DimMenu** 由 `com.geely.online.service` 这个
 * 系统服务进程渲染。它只认两种数据来源：
 *
 * 1. 自己注册过的**白名单媒体源**（帆书、QQ 音乐、网易云……）；
 * 2. 走 OneOS 的 `IMusicManager` / MediaSession 注册（需要车机 SDK 与白名单）。
 *
 * 我们两样都没有。但帆书这条通道是**广播**：
 *
 * ```
 * 本类 sendBroadcast
 *   → com.geely.online.service / com.media.control.MediaControlReceiver
 *   → 服务进程内置 FDBookManager.setMusicInfo
 *   → onMusicCurrentMediaInfoResultWithSource(info, "fandeng")
 *   → MediaCenter → 卡片 UI / DimMenu（仪表）
 * ```
 *
 * 也就是说：**只要把广播发到那个固定的组件上、带上它认的 key，
 * 服务端就会当成"帆书上报的曲目"照单全收**。它不校验发送方是谁。
 *
 * ## 两条广播，职责完全不同（搞混就什么都不显示）
 *
 * | | 心跳 | 数据 |
 * |---|---|---|
 * | 带 `RECEIVER_MEDIA_APP_STATUS` | ✅ `true` | ❌ 不带 |
 * | 内容 | 什么都不带 | 歌名/歌手/封面URL/时长/进度/播放状态 |
 * | 周期 | **每 2s** | 内容变化时（经 700ms 防抖） |
 *
 * ⚠️ 服务端收到 `APP_STATUS=true` 后启动一个 **3s 计时器**，3s 内没再收到心跳就
 * `notifyAppStausChanged(false)` ⇒ **清空卡片**。所以心跳周期必须 < 3s。
 * 而且带 `APP_STATUS` 的那条广播在记录心跳后**直接 return，不处理元数据** ——
 * 所以元数据必须**另发一条**。
 *
 * ## 为什么不做 NAT / 不改状态
 *
 * 这条通道**不抢** OneOS 活动源（`requestAudioSource`）、**不抢** Android 音频焦点
 * （`requestAudioFocus`），因此天然不会打断第三方播放器。我们只是"替帆书报个幕"。
 *
 * ## 与参考实现 DBTOOL2.0 的接口差异（**刻意**，不是漏移植）
 *
 * 参考实现有 `publishPlayState` / `publishProgress` / `refreshCover` / `updateCoverUrl`
 * 四个"增量更新"方法，因为它那边的**状态、进度、封面是三个独立回调**
 * （来自第三方播放器的 NotificationListener）。我们这边只有一个订阅流
 * （`CarNowPlayingSource.observe`）⇒ 任何变化都走 [publish] 一次到位。
 *
 * 所以这里**只有** [publish] 一个入口：多出来的方法没有调用方，
 * 留着就是"没人敢删的死代码"。**若将来真的接第二个数据源，再按需补回，
 * 并顺手补一条单测说明它为什么必要。**
 */
internal class FdBookBroadcaster(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** 是否正在维持卡片（控制心跳开关）。 */
    @Volatile
    private var active = false
    @Volatile
    private var released = false

    @Volatile
    private var name = ""
    @Volatile
    private var author = ""
    @Volatile
    private var coverUrl = ""
    private var coverRefreshPending = false
    private var metadataRefreshPending = false
    private var trackRefreshPending = false
    private var itemId: String? = null
    @Volatile
    private var durationMillis = 0
    @Volatile
    private var positionMillis = 0
    @Volatile
    private var playStatus = PLAY_STATUS_STOPPED

    /** 上一次真正发出的数据广播指纹（歌名|歌手|封面|状态|时长），用于去重。 */
    @Volatile
    private var lastSentKey = ""

    /** 上一次发给服务端的真实状态；曲目身份由上游单独传入。 */
    @Volatile
    private var lastSentName = ""
    @Volatile
    private var lastSentStatus = PLAY_STATUS_STOPPED

    /** 上一次**实际发出去**的封面 URL；null/空 = 本次曲目还没成功带过封面。 */
    @Volatile
    private var lastSentCoverUrl: String? = null

    /** 上一次数据广播的单调时钟时刻（ms）—— 封面补发的最小间隔以它为准。 */
    @Volatile
    private var lastSentAt = 0L

    /** 防抖开始时间；保证连续歌词更新不会无限推迟广播。 */
    private var dataPendingSince = 0L

    /**
     * 封面 URL 提供器 —— 发送时**实时**拉取最新封面 URL。
     *
     * 必要性：第三方/上游的"元数据"与"封面字节"是**异步**到达的
     * （封面走文件传输，比元数据晚）。若在收到元数据那一刻就把 URL 定死，
     * 封面到的时候广播早发完了，卡片上永远没有封面。
     */
    private var coverUrlSupplier: (() -> String?)? = null

    fun setCoverUrlSupplier(supplier: () -> String?) {
        coverUrlSupplier = supplier
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!active) return
            sendAppStatusBroadcast()
            handler.postDelayed(this, HEARTBEAT_MILLIS)
        }
    }

    /** 启动心跳。幂等。 */
    @Synchronized
    fun startHeartbeat() {
        if (active || released) return
        active = true
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        Log.i(TAG, "心跳已启动（周期 ${HEARTBEAT_MILLIS}ms）")
    }

    /** 停止心跳。服务端 3s 后会自动清空卡片。 */
    @Synchronized
    fun stopHeartbeat() {
        active = false
        handler.removeCallbacks(heartbeat)
        Log.i(TAG, "心跳已停止")
    }

    /** 只带 `APP_STATUS=true` 的心跳广播（服务端据此判定帆书还活着）。 */
    private fun sendAppStatusBroadcast() {
        dispatch(Intent().putExtra(KEY_APP_STATUS, true), "心跳")
    }

    /**
     * 推送当前曲目元数据。
     *
     * @param title 歌名（**必填**，空则整条忽略）
     * @param author 歌手
     * @param coverUrl 封面 URL —— 必须是 `http(s)://`；null/空时**保留上一次封面**
     * @param durationMillis 总时长
     * @param positionMillis 当前进度
     * @param isPlaying 是否在播
     */
    fun publish(
        title: String,
        author: String,
        coverUrl: String?,
        durationMillis: Long,
        positionMillis: Long,
        isPlaying: Boolean,
        refreshCover: Boolean = false,
        refreshMetadata: Boolean = false,
        trackChanged: Boolean = false,
        mediaId: String? = null,
    ) {
        if (released) return
        if (title.isBlank()) {
            Log.d(TAG, "publish 跳过：歌名为空")
            return
        }
        // 切歌时先清掉旧封面，避免新歌短暂显示上一首的封面。
        // （参考实现的 Java 版是给形参 `coverUrl` 重新赋值，Kotlin 的形参是 val，
        //   所以这里直接操作字段——语义完全一样。）
        if (refreshCover) this.coverUrl = ""
        if (refreshMetadata) metadataRefreshPending = true
        if (trackChanged) trackRefreshPending = true
        name = title
        itemId = mediaId
        this.author = author
        // 封面粘滞：只有非空 http(s) URL 才更新；空值保留旧封面（防闪烁）。
        if (coverUrl != null && coverUrl.startsWith("http")) {
            this.coverUrl = coverUrl
        }
        if (durationMillis > 0) this.durationMillis = durationMillis.toInt()
        this.positionMillis = positionMillis.toInt()
        this.playStatus = if (isPlaying) PLAY_STATUS_PLAYING else PLAY_STATUS_PAUSED
        if (refreshCover) coverRefreshPending = true

        // 第一次 publish 自动拉起心跳。
        startHeartbeat()
        scheduleDataBroadcast()
    }

    fun release() {
        released = true
        handler.removeCallbacks(debouncedSend)
        stopHeartbeat()
        // A cancelled track update must not leak into a later publish on this instance.
        // Reset fingerprints too: after the heartbeat timeout the receiver has no card.
        coverRefreshPending = false
        metadataRefreshPending = false
        trackRefreshPending = false
        dataPendingSince = 0L
        lastSentKey = ""
        lastSentName = ""
        lastSentStatus = PLAY_STATUS_STOPPED
        lastSentCoverUrl = null
        lastSentAt = 0L
        name = ""
        author = ""
        coverUrl = ""
        durationMillis = 0
        positionMillis = 0
        playStatus = PLAY_STATUS_STOPPED
        itemId = null
    }

    /**
     * 封面字节更新后调用：必要时补发一次数据广播，让车机拿到新的 `?v=`。
     *
     * 背景：元数据与封面是**异步**到达的。封面比第一次数据广播晚到时，
     * 那次广播里的 URL 对应的本地还没有图；而指纹不变又不会触发第二次广播，
     * 车机就一直拿着旧 URL（指纹不变它不会重取）⇒ 卡片/仪表没封面。
     *
     * ⚠️ 调用方必须**先写入字节再调这里**，否则车机按新 URL 取到的还是旧字节。
     *
     * 只补发**一条**，且使用最新真实播放状态（不能重放防抖前的旧暂停状态）—— 不走
     * [FdBookBroadcastPlan.statusSequence] 的状态跳变序列，避免把卡片刷成
     * "暂停→播放"闪烁。判据见 [FdBookBroadcastPlan.shouldRebroadcastForCover]。
     */
    fun onCoverUpdated(coverUrl: String) {
        if (released) return
        // 元数据还没到：等正常广播即可，此时补发只会把空歌名推上去。
        if (name.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val shouldSend = FdBookBroadcastPlan.shouldRebroadcastForCover(
            lastSentCoverUrl = lastSentCoverUrl,
            newCoverUrl = coverUrl,
            sinceLastSentMillis = now - lastSentAt,
        )
        this.coverUrl = coverUrl
        // A same-status raw cover broadcast is ignored by the legacy receiver too.
        // Merge it into the complete snapshot, then use the box's refresh control value.
        if (!shouldSend && coverUrl == lastSentCoverUrl) return
        coverRefreshPending = true
        scheduleDataBroadcast()
    }

    // --------------------------------------------------------------- 内部

    /**
     * 700ms 防抖。
     *
     * 切歌瞬间第三方/上游会在几百毫秒里连续推好几条（歌手在"带合作者/不带"之间
     * 反复、状态在播放/暂停之间抖），逐条发会让车机卡片闪。合并成稳定后的最后一条。
     */
    private fun scheduleDataBroadcast() {
        handler.removeCallbacks(debouncedSend)
        val now = SystemClock.elapsedRealtime()
        if (dataPendingSince == 0L) dataPendingSince = now
        val delay = FdBookBroadcastPlan.debounceDelay(
            now - dataPendingSince, DEBOUNCE_MILLIS, MAX_DEBOUNCE_MILLIS,
        )
        handler.postDelayed(debouncedSend, delay)
    }

    private val debouncedSend = Runnable {
        if (released) return@Runnable
        dataPendingSince = 0L
        // 发送时实时补封面 URL：元数据先到、封面后到时，靠这一步把 URL 填上。
        if (coverUrl.isEmpty()) {
            runCatching { coverUrlSupplier?.invoke() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { coverUrl = it }
        }
        val key = FdBookBroadcastPlan.fingerprint(
            name = name,
            author = author,
            coverUrl = coverUrl,
            status = playStatus,
            durationMillis = durationMillis,
            itemId = itemId,
        )
        if (key == lastSentKey) {
            metadataRefreshPending = false
            coverRefreshPending = false
            if (!trackRefreshPending) return@Runnable
        }

        // Changed complete snapshots must reach state-deduplicating receivers even if the
        // phone has no queue number. Refresh=0 follows box va.a, never fake pause=2.
        val sequence = FdBookBroadcastPlan.statusSequence(
            snapshotChanged = key != lastSentKey || trackRefreshPending,
            status = playStatus,
            lastStatus = lastSentStatus,
            firstSnapshot = lastSentKey.isEmpty(),
        )

        lastSentKey = key
        lastSentName = name
        lastSentStatus = playStatus
        // 原车接收端把数据广播当成完整媒体快照处理：歌词/歌名刷新时如果
        // 不带当前封面 URL，方控会把已有封面清空。封面不会因此重新获取，
        // 这里只是重复发送已经存在的本地 URL。
        val includeCover = coverRefreshPending || coverUrl.isNotEmpty()
        coverRefreshPending = false
        metadataRefreshPending = false
        trackRefreshPending = false
        sequence.forEach { sendDataBroadcast(it, includeCover) }
    }

    private fun sendDataBroadcast(status: Int, includeCover: Boolean = true) {
        val intent = Intent()
            .putExtra(KEY_NAME, name)
            .putExtra(KEY_AUTHOR, author)
            .putExtra(KEY_FAVORITE, false)
            .putExtra(KEY_TOTAL_DURATION, durationMillis)
            .putExtra(KEY_CURRENT_POSITION, positionMillis)
            .putExtra(KEY_PLAY_STATUS, status)
            .putExtra(KEY_BOOK_ID, FdBookBroadcastPlan.bookId(itemId))
            .putExtra(KEY_FRAGMENT_ID, FdBookBroadcastPlan.fragmentId(itemId))
        if (includeCover) intent.putExtra(KEY_COVER_URL, coverUrl)
        dispatch(intent, "数据")
        lastSentStatus = status
        // 记录"实际发出去的封面"与时刻：封面补发的去重与最小间隔都以它为准。
        lastSentCoverUrl = coverUrl.ifEmpty { null }
        lastSentAt = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "数据广播：$name - $author status=$status " +
                "pos=$positionMillis/$durationMillis cover=${if (coverUrl.isEmpty()) "无" else coverUrl}",
        )
    }

    /**
     * 显式指定组件发广播。
     *
     * ⚠️ **必须** `setComponent`，不能只靠 action：
     * - Android 8+ 对隐式广播有限制，显式指定组件才稳；
     * - 车机上可能还有别的应用监听同一个 action，不指定组件会一起收到。
     */
    private fun dispatch(intent: Intent, what: String) {
        try {
            intent.action = RECEIVER_ACTION
            intent.component = ComponentName(RECEIVER_PACKAGE, RECEIVER_CLASS)
            appContext.sendBroadcast(intent)
        } catch (error: Throwable) {
            Log.w(TAG, "$what 广播发送失败：$error")
        }
    }

    internal companion object {
        private const val TAG = "DBPlay-FdBroadcast"

        // ---- 帆书 BroadcastUtil 常量（1:1 复刻，勿改字符串）----
        const val RECEIVER_ACTION = "ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER"
        const val RECEIVER_PACKAGE = "com.geely.online.service"
        const val RECEIVER_CLASS = "com.media.control.MediaControlReceiver"

        const val KEY_COVER_URL = "RECEIVER_MEDIA_BOOK_COVERURL"
        const val KEY_NAME = "RECEIVER_MEDIA_BOOK_NAME"
        const val KEY_AUTHOR = "RECEIVER_MEDIA_BOOK_AUTHOR_NAME"
        const val KEY_FAVORITE = "RECEIVER_MEDIA_FAVORITE_STATUS"
        const val KEY_TOTAL_DURATION = "RECEIVER_MEDIA_TOTAL_DURATION"
        const val KEY_CURRENT_POSITION = "RECEIVER_MEDIA_CURRENT_POSITION"
        const val KEY_PLAY_STATUS = "RECEIVER_MEDIA_PLAY_STATUS"
        const val KEY_BOOK_ID = "RECEIVER_MEDIA_BOOK_ID"
        const val KEY_FRAGMENT_ID = "RECEIVER_MEDIA_FRAGMENT_ID"
        const val KEY_APP_STATUS = "RECEIVER_MEDIA_APP_STATUS"

        /** 播放状态码（帆书：3 播放 / 2 暂停 / 0 停止）。 */
        const val PLAY_STATUS_PLAYING = FdBookBroadcastPlan.PLAY_STATUS_PLAYING
        const val PLAY_STATUS_PAUSED = FdBookBroadcastPlan.PLAY_STATUS_PAUSED
        const val PLAY_STATUS_STOPPED = 0

        /** 心跳周期 —— **必须 < 服务端 3s 超时**，取 2s（与帆书 timer 一致）。 */
        const val HEARTBEAT_MILLIS = 2_000L

        /** 数据广播防抖窗口。实测切歌瞬态抖动约 0.7s。 */
        const val DEBOUNCE_MILLIS = 700L

        /** 连续歌词/元数据更新时的最大等待，避免防抖窗口无限向后滑。 */
        const val MAX_DEBOUNCE_MILLIS = 1_500L
    }
}
