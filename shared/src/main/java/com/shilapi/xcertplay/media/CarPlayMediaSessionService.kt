package com.shilapi.xcertplay.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.shilapi.xcertplay.iap2.message.Iap2MediaRemoteCommand
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import java.util.concurrent.atomic.AtomicInteger

internal data class CarPlayMediaSnapshot(
    val nowPlaying: Iap2NowPlayingState? = null,
    val controlsAvailable: Boolean = false,
    val artwork: CarPlayArtwork? = null,
)

/**
 * Whether a completed artwork transfer is worth publishing.
 *
 * Artwork is accepted even when it does not match the current track id yet, because iPhones may
 * push session-12 artwork before the first NowPlayingUpdate that names it. Only an exact repeat
 * of an already cached id is skipped.
 */
internal fun shouldPublishArtwork(
    incomingId: Int,
    currentTrackId: Int?,
    hasSameIdCached: Boolean,
): Boolean = incomingId != currentTrackId || !hasSameIdCached

/** Process-local seam between the iAP2 owner and the lifecycle-owned Media3 service. */
internal object CarPlayMediaSessionBridge {
    private var owner: Any? = null
    private var commandSink: ((Iap2MediaRemoteCommand) -> Boolean)? = null
    private var snapshot = CarPlayMediaSnapshot()
    private val artworkCache = NowPlayingArtworkCache()
    private var observer: ((CarPlayMediaSnapshot) -> Unit)? = null
    private val artworkMismatchLogCount = AtomicInteger(0)

    fun attach(
        context: Context,
        owner: Any,
        commandSink: (Iap2MediaRemoteCommand) -> Boolean,
    ) {
        val update = synchronized(this) {
            this.owner = owner
            this.commandSink = commandSink
            snapshot = CarPlayMediaSnapshot()
            artworkCache.clear()
            artworkMismatchLogCount.set(0)
            observer to snapshot
        }
        update.first?.invoke(update.second)
        context.applicationContext.startService(
            Intent(context.applicationContext, CarPlayMediaSessionService::class.java),
        )
    }

    fun detach(context: Context, owner: Any) {
        val update = synchronized(this) {
            if (this.owner !== owner) return
            this.owner = null
            commandSink = null
            snapshot = CarPlayMediaSnapshot()
            artworkCache.clear()
            artworkMismatchLogCount.set(0)
            observer to snapshot
        }
        update.first?.invoke(update.second)
        context.applicationContext.stopService(
            Intent(context.applicationContext, CarPlayMediaSessionService::class.java),
        )
    }

    fun publish(owner: Any, nowPlaying: Iap2NowPlayingState) = update(owner) {
        // UNKNOWN is missing knowledge, not a pause. Explicit PAUSED/STOPPED always wins.
        var next = nowPlaying.copy(status = Iap2PlaybackStatus.fromWire(resolvePlaybackStatus(
            nowPlaying.status.wireValue, it.nowPlaying?.status?.wireValue,
        )))
        val previous = it.nowPlaying
        // The accumulator already retains omitted queue attributes (Iap2Messages.kt),
        // but also tolerate an explicitly incomplete reconstructed snapshot at this seam.
        if (previous != null && next.queueIndex == null &&
            (next.appName.isNullOrBlank() || previous.appName.isNullOrBlank() || previous.appName == next.appName)
        ) next = next.copy(queueIndex = previous.queueIndex)
        if (previous != null && previous.queueIndex == next.queueIndex &&
            (next.appName == null || previous.appName == null || previous.appName == next.appName)
        ) {
            // Incremental metadata can temporarily contain empty strings; that is not a
            // request to blank the card. A real queue/app change does not inherit text.
            next = next.copy(
                title = retainDisplayText(next.title, previous.title),
                artist = retainDisplayText(next.artist, previous.artist),
                album = retainDisplayText(next.album, previous.album),
                durationMillis = next.durationMillis ?: previous.durationMillis,
                appName = retainDisplayText(next.appName, previous.appName),
            )
        }
        it.copy(
            nowPlaying = next,
            artwork = artworkCache.selectForMetadata(next.artworkFileTransferId) ?: it.artwork,
        )
    }

    fun publishArtwork(owner: Any, fileTransferId: Int, data: ByteArray) = update(owner) {
        val currentTrackId = it.nowPlaying?.artworkFileTransferId
        if (fileTransferId != currentTrackId &&
            artworkMismatchLogCount.getAndIncrement() < ARTWORK_MISMATCH_LOG_LIMIT
        ) {
            Log.i(
                ARTWORK_LOG_TAG,
                "artwork id 不匹配：收到 $fileTransferId / 当前曲目 $currentTrackId，仍按最近封面显示",
            )
        }
        val artwork = artworkCache.accept(fileTransferId, data) ?: return@update it
        it.copy(artwork = artwork)
    }

    fun hasArtwork(owner: Any, fileTransferId: Int): Boolean = synchronized(this) {
        this.owner === owner && artworkCache.contains(fileTransferId)
    }

    fun setControlsAvailable(owner: Any, available: Boolean) = update(owner) {
        it.copy(controlsAvailable = available)
    }

    fun observe(observer: ((CarPlayMediaSnapshot) -> Unit)?) {
        val initial = synchronized(this) {
            this.observer = observer
            snapshot
        }
        observer?.invoke(initial)
    }

    fun send(command: Iap2MediaRemoteCommand): Boolean =
        synchronized(this) { commandSink }?.invoke(command) == true

    private inline fun update(owner: Any, transform: (CarPlayMediaSnapshot) -> CarPlayMediaSnapshot) {
        val update = synchronized(this) {
            if (this.owner !== owner) return
            val next = transform(snapshot)
            if (next == snapshot) return
            snapshot = next
            observer to next
        }
        update.first?.invoke(update.second)
    }

    private const val ARTWORK_LOG_TAG = "CarPlay-Artwork"
    private const val ARTWORK_MISMATCH_LOG_LIMIT = 5
}

class CarPlayMediaSessionService : MediaSessionService() {
    private lateinit var player: CarPlayRemotePlayer
    private lateinit var session: MediaSession
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        player = CarPlayRemotePlayer(Looper.getMainLooper(), CarPlayMediaSessionBridge::send)
        session = MediaSession.Builder(this, player).build()
        addSession(session)
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        CarPlayMediaSessionBridge.observe { snapshot ->
            mainHandler.post { player.update(snapshot) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        CarPlayMediaSessionBridge.observe(null)
        player.release()
        session.release()
        super.onDestroy()
    }
}

private class CarPlayRemotePlayer(
    looper: Looper,
    private val send: (Iap2MediaRemoteCommand) -> Boolean,
) : SimpleBasePlayer(looper) {
    private data class MetadataKey(
        val identity: List<Any?>,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMillis: Long?,
        val queueIndex: Long?,
        val queueCount: Long?,
        val artwork: CarPlayArtwork?,
    )

    private var snapshot = CarPlayMediaSnapshot()
    private var metadataKey: MetadataKey? = null
    private var playlist: List<MediaItemData>? = null
    private var trackIdentity: List<Any?>? = null
    private val identityContinuity = NowPlayingIdentityContinuity()
    private var trackGeneration = 0L
    private var mediaItem: MediaItem? = null

    fun update(snapshot: CarPlayMediaSnapshot) {
        verifyApplicationThread()
        if (snapshot == this.snapshot) return
        this.snapshot = snapshot
        if (snapshot.nowPlaying == null) {
            metadataKey = null
            playlist = null
            trackIdentity = null
            identityContinuity.reset()
            mediaItem = null
        }
        invalidateState()
    }

    override fun getState(): State {
        val nowPlaying = snapshot.nowPlaying
        val commands = Player.Commands.Builder()
            .addAllReadOnlyCommands()
            .addIf(Player.COMMAND_PLAY_PAUSE, snapshot.controlsAvailable)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, snapshot.controlsAvailable)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, snapshot.controlsAvailable)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, snapshot.controlsAvailable)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, snapshot.controlsAvailable)
            .build()
        val state = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(
                nowPlaying?.status.isPlaybackActive(),
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            .setPlaybackState(
                if (nowPlaying == null || nowPlaying.status == Iap2PlaybackStatus.STOPPED) {
                    Player.STATE_IDLE
                } else {
                    Player.STATE_READY
                },
            )
        if (nowPlaying != null) {
            // Internal sentinels keep standard previous/next commands callable around the current item.
            state.setPlaylist(playlist(nowPlaying, snapshot.artwork))
                .setCurrentMediaItemIndex(1)
                .setContentPositionMs(
                    PositionSupplier.getExtrapolating(
                        nowPlaying.currentPositionMillis(),
                        if (nowPlaying.status == Iap2PlaybackStatus.PLAYING) 1f else 0f,
                    ),
                )
        }
        return state.build()
    }

    override fun getPlaceholderState(suggestedPlaceholderState: State): State = state

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady != snapshot.nowPlaying?.status.isPlaybackActive()) {
            send(if (playWhenReady) Iap2MediaRemoteCommand.PLAY else Iap2MediaRemoteCommand.PAUSE)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> send(Iap2MediaRemoteCommand.NEXT)

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> send(Iap2MediaRemoteCommand.PREVIOUS)
        }
        return Futures.immediateVoidFuture()
    }

    private fun playlist(
        nowPlaying: Iap2NowPlayingState,
        artwork: CarPlayArtwork?,
    ): List<MediaItemData> {
        val identity = identityContinuity.observe(nowPlaying.queueIndex, nowPlaying.appName)
        val nextKey = MetadataKey(
            identity = identity,
            title = nowPlaying.title ?: nowPlaying.appName,
            artist = nowPlaying.artist,
            album = nowPlaying.album,
            durationMillis = nowPlaying.durationMillis,
            queueIndex = nowPlaying.queueIndex,
            queueCount = nowPlaying.queueCount,
            artwork = artwork,
        )
        playlist?.takeIf { metadataKey == nextKey }?.let { return it }

        if (trackIdentity != identity || mediaItem == null) {
            trackIdentity = identity
            trackGeneration += 1
            // Item identity is separate from live display metadata (which may contain lyrics).
            mediaItem = MediaItem.Builder().setMediaId("$MEDIA_ID-$trackGeneration").build()
        }
        val metadata = nowPlaying.toMediaMetadata(artwork)
        val itemData = MediaItemData.Builder(checkNotNull(mediaItem).mediaId)
            .setMediaItem(checkNotNull(mediaItem))
            .setMediaMetadata(metadata)
            .setDurationUs(nowPlaying.durationMillis?.times(1_000) ?: C.TIME_UNSET)
            .build()
        return listOf(
            itemData.buildUpon().setUid(PREVIOUS_UID).build(),
            itemData,
            itemData.buildUpon().setUid(NEXT_UID).build(),
        ).also {
            metadataKey = nextKey
            playlist = it
        }
    }

    private fun Iap2NowPlayingState.toMediaMetadata(artwork: CarPlayArtwork?): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title ?: appName)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMillis)
            .setTrackNumber(queueIndex?.let { (it + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() })
            .setTotalTrackCount(queueCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt())
            .setIsPlayable(true)
            .apply {
                if (artwork != null && artwork.data.isNotEmpty()) {
                    setArtworkData(artwork.data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()

    private fun Iap2NowPlayingState.currentPositionMillis(): Long {
        val extrapolated = if (status == Iap2PlaybackStatus.PLAYING && positionUpdateRealtimeMillis > 0) {
            elapsedMillis + (System.nanoTime() / 1_000_000L - positionUpdateRealtimeMillis).coerceAtLeast(0)
        } else {
            elapsedMillis
        }
        return durationMillis?.let { extrapolated.coerceAtMost(it) } ?: extrapolated
    }

    private fun Iap2PlaybackStatus?.isPlaybackActive(): Boolean =
        this == Iap2PlaybackStatus.PLAYING ||
            this == Iap2PlaybackStatus.SEEKING_FORWARD ||
            this == Iap2PlaybackStatus.SEEKING_BACKWARD

    private companion object {
        const val MEDIA_ID = "carplay-now-playing"
        const val PREVIOUS_UID = "carplay-previous"
        const val NEXT_UID = "carplay-next"
    }
}
