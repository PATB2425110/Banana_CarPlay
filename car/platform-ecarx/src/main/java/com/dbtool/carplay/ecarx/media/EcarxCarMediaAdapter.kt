package com.dbtool.carplay.ecarx.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dbtool.carplay.android.AndroidCarMediaAdapter
import com.dbtool.carplay.api.media.CarNowPlaying
import com.dbtool.carplay.api.media.CarNowPlayingSource

/**
 * Ecarx / 吉利 OneOS 的媒体卡片适配：把 iPhone 正在放的东西推给车机媒体中心。
 *
 * ## 它解决什么
 *
 * 车机的**仪表盘 / 中控卡片 / dock** 只会显示它自己认识的媒体源。CarPlay 的画面
 * 是我们投上去的，车机系统并不知道"iPhone 正在放哪首歌"，所以仪表盘一直是空的。
 *
 * 解法（参考实现 DBTOOL2.0 在吉利车机上实车验证）：**伪装成帆书**，
 * 走它的广播通道上报曲目，封面用一个本地 HTTP 服务喂 URL。
 * 细节见 [FdBookBroadcaster] 与 [LocalCoverHttpServer]。
 *
 * ## 数据链路
 *
 * ```
 * iPhone --iAP2--> 上游 CarPlayMediaSessionService (Media3 MediaSession)
 *                          ↑ 标准 MediaController（只读）
 *                  CarPlayNowPlayingClient  (:car:core-bridge)
 *                          ↓ CarNowPlayingSource
 *                  本类（:car:platform-ecarx）
 *                    ├─ LocalCoverHttpServer  ← 封面字节 → http://127.0.0.1:<port>/cover.jpg?v=<n>
 *                    └─ FdBookBroadcaster     ← 歌名/歌手/时长/进度/状态/封面URL → 车机 MediaControlReceiver
 * ```
 *
 * ## 为什么封面要"每次发送时实时取 URL"而不是收到就定死
 *
 * 元数据与封面是**异步**到达的：封面走 iAP2 的文件传输，通常比 `NowPlayingUpdate`
 * 晚。若在拿到元数据那一刻就把 URL 定死，封面到的时候广播已经发完了，
 * 卡片上就永远没有封面。所以 [FdBookBroadcaster.setCoverUrlSupplier] 拿到的是一个
 * **发送时**才求值的闭包。
 *
 * ## 封面 URL 的版本号：**只在"喂出去的字节真的变了"时才变**（比参考实现更严一格）
 *
 * 车机端按 URL 缓存封面（Glide）。参考实现 DBTOOL2.0 用的 tag 是"切歌计数"
 * （歌名变了就 +1），在它的场景下够用——它的封面来自第三方播放器的
 * `MediaDescription.getIconBitmap()`，**与元数据同一次回调到达**，所以 700ms 防抖窗口
 * 内封面已经写进本地服务了。
 *
 * 我们这边不一样：封面走 iAP2 **文件传输**，比元数据晚到，且很可能晚过防抖窗口。
 * 若照抄"切歌计数"会遇到这个组合：
 *
 * 1. 切歌 → tag 变 → 广播发出（`?v=N`），但本地服务里**还是上一首的字节** ⇒ 车机缓存了旧封面；
 * 2. 封面到 → 写字节，但 tag 没变（歌名没再变）⇒ URL 不变 ⇒ 指纹不变 ⇒ **不再重发**。
 *
 * 结果是"歌名换了、封面永远停在上一首"。所以这里把版本号的语义收紧成
 * **"封面字节的代次"**：
 *
 * - 切歌（歌名变）⇒ 版本 +1 且**清空**本地封面（新封面还没到，宁可暂时没封面，
 *   也不能喂上一首的）；
 * - 收到新封面字节 ⇒ 版本 +1 并写入。
 *
 * URL 因此**恰好**在"服务端能拿到的字节变了"的时刻改变，既不会漏刷新，也不会
 * 同一首歌反复重载。
 *
 * ## ⚠️ 不做能力探测就放弃（参考实现从不探测）
 *
 * [canSeeReceiver] 只是**报告**：`queryBroadcastReceivers` 受 `<queries>`、导出标志、
 * 用户 ID 等一堆因素影响，"探不到"不等于"没有"。而向不存在的组件发广播**是无害的**
 * （不崩、不报错，只是没人处理）。所以探不到时照发，只留一行日志。
 */
internal class EcarxCarMediaAdapter(private val context: Context) : AndroidCarMediaAdapter() {

    private val appContext = context.applicationContext

    private val coverServer = LocalCoverHttpServer()
    private var broadcaster: FdBookBroadcaster? = null

    /**
     * 封面**字节**的代次 —— 封面 URL 的 `?v=` 取值。
     *
     * 只在"喂出去的字节确实变了"时 +1（切歌清空、收到新字节）。见类文档。
     */
    private var coverVersion = 0L

    /** Metadata keys are not proof of a track change. Only known queue transitions may flip. */
    private val queueContinuity = MediaQueueContinuity()

    /** 标题/歌词变化也要刷新原车卡片，但不能因此重新获取专辑图。 */
    private var lastMediaContentKey: Any? = null

    /** 上一次写入 HTTP 服务的封面字节，避免同一张封面反复写入。 */
    private var lastArtwork: ByteArray? = null

    override fun attachNowPlaying(source: CarNowPlayingSource): () -> Unit {
        if (!canSeeReceiver()) {
            // 只报告，不阻断：参考实现根本不探测，直接发广播。
            Log.i(
                TAG,
                "看不到 ${FdBookBroadcaster.RECEIVER_PACKAGE}/${FdBookBroadcaster.RECEIVER_CLASS}" +
                    "（探不到 ≠ 没有），仍然照常上报",
            )
        }
        val server = coverServer.start()
        if (server <= 0) {
            // 没有本地封面服务，元数据还能推，但封面一定出不来。
            // 不直接放弃：歌名/歌手/进度先上去，至少卡片不是空的。
            Log.w(TAG, "封面 HTTP 服务没起来，本次只上报元数据（不会有封面）")
        }
        val fd = FdBookBroadcaster(appContext)
        fd.setCoverUrlSupplier { coverServer.coverUrl(coverVersion.toString()) }
        broadcaster = fd

        val unsubscribe = source.observe { nowPlaying ->
            // observe() may already have posted its initial callback when unsubscribed.
            // An old attachment must not mutate the next session's cover/queue cache.
            if (broadcaster === fd) onNowPlaying(fd, nowPlaying)
        }
        Log.i(TAG, "媒体卡片上报已开启（封面端口 $server）")

        return {
            unsubscribe()
            fd.release()
            // A delayed cleanup from an older attachment must not stop the new cover server.
            if (broadcaster === fd) {
                broadcaster = null
                coverServer.stop()
                lastArtwork = null
                queueContinuity.reset()
                lastMediaContentKey = null
                Log.i(TAG, "媒体卡片上报已关闭")
            }
        }
    }

    private fun onNowPlaying(fd: FdBookBroadcaster, nowPlaying: CarNowPlaying?) {
        // null = 会话刚起来还没收到 NowPlayingUpdate，或会话已经断了。
        // **不**清卡片：瞬时 null 很常见，清了会闪；真的断开会话时
        // `attachNowPlaying` 返回的句柄会 stop 心跳，卡片由服务端 3s 超时清掉。
        if (nowPlaying == null || !nowPlaying.hasContent) return

        val trackChanged = queueContinuity.observe(nowPlaying)
        val mediaContentKey = listOf(
            nowPlaying.title,
            nowPlaying.artist,
            nowPlaying.durationMillis,
            nowPlaying.isPlaying,
        )
        val contentChanged = mediaContentKey != lastMediaContentKey
        lastMediaContentKey = mediaContentKey
        // Keep the current image/URL until genuinely different bytes arrive. Metadata
        // enrichment, artist variants and first queue discovery must never clear it.

        val artwork = nowPlaying.artworkBytes
        var artworkChanged = false
        if (artwork != null && artwork.isNotEmpty() && !artwork.contentEquals(lastArtwork)) {
            lastArtwork = artwork
            artworkChanged = true
            coverVersion += 1
            // 先写字节、再补发：顺序反了车机会按新 URL 取到旧字节。
            // updateCover 返回的正是写入成功后的 URL（服务没起来时为 null，则不补发）。
            coverServer.updateCover(artwork, coverVersion.toString())?.let { url ->
                fd.onCoverUpdated(url)
            }
        }

        // 封面交给 CoverUrlSupplier 在**发送时**取（解异步到达的时序竞争），
        // 所以这里传 null 而不是传刚才那个 URL。
        fd.publish(
            title = nowPlaying.title,
            author = nowPlaying.artist,
            coverUrl = null,
            durationMillis = nowPlaying.durationMillis,
            positionMillis = nowPlaying.positionMillis,
            isPlaying = nowPlaying.isPlaying,
            refreshCover = artworkChanged,
            refreshMetadata = contentChanged,
            trackChanged = trackChanged,
            mediaId = nowPlaying.mediaId,
        )
    }

    /**
     * 本车机**看起来**有没有帆书那条广播的接收者。
     *
     * 判据是"`com.geely.online.service/com.media.control.MediaControlReceiver`
     * 能不能被解析到"，而不是读厂商字符串——组件在就在、不在就不在。
     *
     * ⚠️ 这是一个 **receiver**，所以用 `queryBroadcastReceivers` 而不是
     * `resolveService`（后者只认 service，永远返回 null）。Android 11+ 上
     * `queryBroadcastReceivers` 还要求 manifest 里有对应的 `<queries>`。
     *
     * ⚠️ 返回值只用于**日志**。不要拿它去 `return {}` 放弃整条链路：
     * 探测受 `<queries>` / 导出标志 / 用户 ID 影响，"探不到"经常只是"看不见"。
     */
    private fun canSeeReceiver(): Boolean = runCatching {
        val probe = Intent(FdBookBroadcaster.RECEIVER_ACTION)
            .setComponent(ComponentName(FdBookBroadcaster.RECEIVER_PACKAGE, FdBookBroadcaster.RECEIVER_CLASS))
        appContext.packageManager.queryBroadcastReceivers(probe, 0).isNotEmpty()
    }.getOrDefault(false)

    private companion object {
        const val TAG = "DBPlay-EcarxMedia"
    }
}
