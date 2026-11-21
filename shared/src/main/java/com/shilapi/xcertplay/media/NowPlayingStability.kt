package com.shilapi.xcertplay.media

/** Only an unknown wire status may inherit; STOPPED(0) and PAUSED(2) are authoritative. */
internal fun resolvePlaybackStatus(incoming: Int, previous: Int?): Int =
    if (incoming < 0) previous ?: incoming else incoming

internal fun retainDisplayText(incoming: String?, previous: String?): String? =
    incoming?.takeIf { it.isNotBlank() } ?: previous

/** Metadata enrichment is not a track transition. Remember known queue across missing fields. */
internal class NowPlayingIdentityContinuity {
    private var knownQueue: Long? = null
    private var knownApp: String? = null
    private var generation = 1L

    fun observe(queue: Long?, app: String?): List<Any?> {
        val namedApp = app?.takeIf { it.isNotBlank() }
        val appChanged = knownApp != null && namedApp != null && knownApp != namedApp
        val queueChanged = knownQueue != null && queue != null && knownQueue != queue
        if (appChanged || queueChanged) generation += 1
        if (appChanged) knownQueue = null
        if (namedApp != null) knownApp = namedApp
        if (queue != null) knownQueue = queue
        return listOf(generation)
    }

    fun reset() {
        knownQueue = null
        knownApp = null
        generation = 1L
    }
}

internal class CarPlayArtwork(val fileTransferId: Int, val data: ByteArray)

/** Session-scoped, bounded transfer cache. Missing/changing metadata must not evict a cover. */
internal class NowPlayingArtworkCache {
    private val transfers = LinkedHashMap<Int, CarPlayArtwork>()
    private var displayed: CarPlayArtwork? = null
    private var metadataId: Int? = null

    fun contains(id: Int): Boolean = transfers.containsKey(id)

    /** Metadata can immediately select an already cached image before another payload arrives.
     * Only a new known metadata ID selects: repeated lyrics/missing IDs must not undo a
     * just-arrived prefetch that the original bridge intentionally permits displaying.
     */
    fun selectForMetadata(id: Int?): CarPlayArtwork? {
        if (id == null || metadataId == id) return null
        metadataId = id
        return transfers[id]?.also { displayed = it }
    }

    fun accept(id: Int, bytes: ByteArray): CarPlayArtwork? {
        if (bytes.isEmpty()) return null
        val same = displayed?.takeIf { it.data.contentEquals(bytes) }
        val cover = same ?: CarPlayArtwork(id, bytes.copyOf())
        transfers.remove(id)
        transfers[id] = cover
        while (transfers.size > 4) transfers.remove(transfers.keys.first())
        displayed = cover
        return cover
    }

    fun clear() {
        transfers.clear()
        displayed = null
        metadataId = null
    }
}
