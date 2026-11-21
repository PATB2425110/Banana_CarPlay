package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Locale

/**
 * Navigation state reconstructed from the iPhone's route-guidance iAP2 messages.
 *
 * ## Why this file lives in `:shared` (DBPlay PATCH-0016, rewritten by PATCH-0018)
 *
 * Upstream declares the navigation endpoints (`0x5201` RouteGuidanceUpdate,
 * `0x5202` RouteGuidanceManeuverUpdate, `0x5204` LaneGuidanceInfoUpdate) in
 * [com.shilapi.xcertplay.iap2.catalog.Iap2Endpoint], and advertises that it will
 * *receive* them in [com.shilapi.xcertplay.transport.Iap2IdentificationClient],
 * but it ships no field schema, no decoder and no consumer for any of them. The
 * frames arrive and are dropped by `CarPlayController.onIap2Incoming`.
 *
 * DBPlay needs this data to drive the Geely HUD bridge, and the callback is
 * `private` inside the Core with no adapter seam, so the decoder has to live
 * here. Registered as **PATCH-0016** in `PATCHES.md`; the wire format below was
 * corrected against the stock Geely app in **PATCH-0018**, and the `0x5202`
 * id table plus the route-lifecycle rules were corrected again in
 * **PATCH-0039** after a line-by-line re-read of `Iap2RouteState`.
 *
 * ## Evidence status — this revision is OBSERVED, not inferred
 *
 * The earlier revision of this file read the body as a CSM parameter list
 * (2-byte parameter id + 2-byte width + value). That was wrong: the body is a
 * **TLV stream**, and the wrong reader meant every frame was silently discarded.
 *
 * The layout below is taken line-by-line from the stock Geely app's own decoder
 * (`GeelyCarPlay`, classes10.dex):
 *
 *  - `io.dushu.car.navigation.RouteGuidanceTlvsKt.routeTlvs(byte[])`
 *    — `u16 length | u16 id | value[length - 4]`, `offset += length`.
 *    A `length < 4` or an overrun invalidates the **whole** list (returns null),
 *    so one bad TLV means "ignore this frame", never "decode the rest".
 *  - `Iap2RouteState.parseRouteUpdate` — ids for `0x5201`.
 *  - `Iap2RouteState.parseManeuverUpdate` — ids for `0x5202`.
 *  - `Iap2LaneDecoder.decode` — ids for `0x5204`.
 *  - `Iap2RouteState.validFieldWidths` — the exact width each id must have.
 *
 * Still deliberately cautious, because being wrong must degrade to a blank HUD
 * rather than a broken session:
 *
 * 1. [Iap2NavigationAccumulator.update] never throws; a malformed frame updates
 *    nothing and the previous state is kept.
 * 2. [CarNavigationState.hasContent] gates publishing, so downstream sees
 *    "no navigation" rather than a bogus `0 m / 0 min` card.
 *
 * ## What this revision does NOT port
 *
 * The stock app also keeps a lane store (8-entry LRU keyed by event id, plus a
 * `selected` / `showing` pair), preserves unknown lane TLV ids in
 * `GeelyLane.unknownFields`, and validates every id against a width table
 * (`validRouteFieldWidths`) before accepting a frame. None of that is needed to
 * *display* guidance, so it is not ported. Route end-debounce (`pendingEndNs`)
 * **is** ported as of PATCH-0039, because without it a single transient
 * `guidanceState == 0` blanked the cluster mid-junction.
 */
data class CarNavigationState(
    /** Local generation, never the component identifier or a claimed phone route id. */
    val routeGeneration: Long? = null,
    val guidanceState: Int? = null,
    val maneuverState: Int? = null,
    val maneuverIndices: List<Int> = emptyList(),
    val maneuverCount: Int? = null,
    val shownInApp: Boolean? = null,
    val laneCount: Int? = null,
    val laneDescription: String? = null,
    val sourceSupportsGuidance: Boolean? = null,
    val routeDistanceDisplay: String? = null,
    val routeDistanceUnits: Int? = null,
    val maneuverDistanceDisplay: String? = null,
    val maneuverDistanceUnits: Int? = null,
    /** Total remaining distance of the active route, in metres. */
    val routeMeters: Int? = null,
    /** Total remaining travel time of the active route, in seconds. */
    val routeSeconds: Int? = null,
    /** Remaining distance to the next manoeuvre along the current segment, in metres. */
    val segmentMeters: Int? = null,
    /** Name of the road currently being travelled, when the phone supplies one. */
    val currentRoad: String? = null,
    /** Name of the road the next manoeuvre leads onto. */
    val nextRoad: String? = null,
    /** Human-readable manoeuvre instruction from the phone, e.g. "右转进入…". */
    val maneuverDescription: String? = null,
    /**
     * Raw iPhone manoeuvre type code.
     *
     * Kept exactly as the phone reports it. Mapping onto the Geely HUD's turn
     * vocabulary happens in `:car:platform-geely`, mirroring how the stock app
     * separates "what the phone said" from "what the HUD is told to show".
     */
    val maneuverType: Int? = null,
    /** Which side of the road the vehicle drives on, as reported for the manoeuvre. */
    val drivingSide: Int? = null,
    /** Ordinal of the active manoeuvre within the route, used as a stable key. */
    val maneuverIndex: Int? = null,
    /** True while the phone reports an active route. */
    val routeActive: Boolean = false,
    /** Destination text supplied by the phone, when present. */
    val destination: String? = null,
    /** Estimated arrival time, as an epoch second. */
    val arrivalEpochSeconds: Long? = null,
    /** Identifier of the active route-guidance session. */
    val sessionId: Long? = null,
    /** Monotonic revision of the route-guidance stream, used to drop stale frames. */
    val revision: Long? = null,

    // ── 0x5202 manoeuvre extras (PATCH-0039) ────────────────────────────────
    //
    // The stock app keeps all of these on its manoeuvre record and both the
    // self-draw classifier and the HUD bridge read them. They were missing here
    // because the `0x5202` id table was inferred rather than observed.

    /** Junction shape code (`0x5202` id 9). */
    val junctionType: Int? = null,
    /** First junction angle (`0x5202` id 0xa, first signed 16-bit entry). */
    val junctionAngle: Int? = null,
    /**
     * Every junction angle (`0x5202` id 0xa, signed 16-bit entries).
     *
     * The stock app keeps both this list and the scalar [junctionAngle]
     * (`Iap2Maneuver` has both fields), and hands the list to the self-draw
     * layer for complex junctions.
     */
    val junctionAngles: List<Int> = emptyList(),
    /**
     * Roundabout exit angle (`0x5202` id 0xb).
     *
     * The stock classifier uses this to tell `UTURN` from `UTURN_RIGHT` for
     * manoeuvre types `4 / 18 / 26`: an angle in `[1, 181)` means a right-hand
     * U-turn.
     */
    val exitAngle: Int? = null,

    // ── 0x5204 lane guidance (PATCH-0039) ───────────────────────────────────

    /**
     * Lane guidance for the upcoming manoeuvre.
     *
     * Decoded from `0x5204` following the stock app's `Iap2LaneDecoder`: the
     * payload is a TLV list whose id-0 entry is a `42` marker, whose id-1 entry
     * is the **event id** (not a selected-lane index) and whose id-2 entries are
     * the lanes themselves. The **whole group** is kept, sorted by lane index —
     * the stock renderer draws every lane, and `laneCount` is the group size.
     */
    /**
     * Lane group the phone currently wants shown.
     *
     * This is **not** the raw last `0x5204` frame: it is what the stock app's
     * `Iap2LaneStore.current()` returns, i.e. the group whose event id the phone
     * pointed at with `0x5201` id `0x10` — and only while it has also asked for
     * lanes with id `0x12`. The selection and the visibility flag live in the
     * accumulator's lane store rather than on this state object, exactly like
     * `Iap2LaneStore.selected` / `.showing`.
     *
     * Empty means nothing is selected, or the selection is `0xFFFF`.
     * A `0x5204` group that arrives before any `0x10` selection is shown
     * immediately. `0x12` is not a visibility switch on this car.
     */
    val lanes: List<CarLaneGuidance> = emptyList(),
    /** Event id of the published lane group ([CarLaneGuidance.eventId]). */
    val laneEventId: Int? = null,
    /** Free-text lane instruction (`0x5204` id 3), e.g. "走左侧两车道". */
    val laneInstruction: String? = null,
    /** Navigation source id (`0x5201` id `0x13`); a change invalidates the route. */
    val routeSource: String? = null,
) {
    /**
     * True when at least one field carries real information.
     *
     * Downstream must treat `false` as "nothing to show" rather than rendering a
     * zeroed navigation card.
     */
    val hasContent: Boolean
        get() = routeMeters != null ||
            routeSeconds != null ||
            segmentMeters != null ||
            currentRoad != null ||
            nextRoad != null ||
            maneuverDescription != null ||
            maneuverType != null ||
            lanes.isNotEmpty() || destination != null || arrivalEpochSeconds != null
}

/** A single lane offered at the upcoming manoeuvre. */
data class CarLaneGuidance(
    /** Position of this lane in the group, left to right. */
    val index: Int,
    /** Angles, in degrees, describing how the lane bends ahead. */
    val angles: List<Int>,
    /** Angle that should be highlighted, or `null` when no lane is preferred. */
    val highlightAngle: Int?,
    /** Whether the lane is recommended, merely usable, or to be avoided. */
    val status: CarLaneStatus,
)

/** Recommendation level assigned to a lane. */
enum class CarLaneStatus {
    NOT_GOOD,
    GOOD,
    PREFERRED,
    ;

    companion object {
        /**
         * Maps the iAP2 status byte onto a recommendation level.
         *
         * The stock decoder reads this as a raw unsigned byte and renders it as
         * a small ordered vocabulary; the ordering below matches how the Geely
         * bridge labels lanes (`NOT_GOOD` / `GOOD` / `PREFERRED`).
         */
        fun fromWire(value: Int): CarLaneStatus = when (value) {
            0 -> NOT_GOOD
            1 -> GOOD
            else -> PREFERRED
        }
    }
}

/**
 * One TLV entry of a route-guidance body.
 *
 * Mirrors the stock app's `RouteTlv`: a 16-bit id and the raw value bytes that
 * followed the 4-byte header.
 */
internal class Iap2RouteTlv(val id: Int, val value: ByteArray) {
    /** Unsigned big-endian integer of the value, truncated to 32 bits like the stock reader. */
    fun unsigned(): Int {
        var result = 0
        for (b in value) result = (result shl 8) or (b.toInt() and 0xFF)
        return result
    }

    /** Unsigned big-endian integer widened to 64 bits, for epoch-second fields. */
    fun unsignedLong(): Long {
        var result = 0L
        for (b in value) result = (result shl 8) or (b.toLong() and 0xFF)
        return result
    }

    /**
     * First four bytes as an unsigned big-endian 32-bit value.
     *
     * Mirrors the stock `u32` reader, which always consumes exactly four bytes.
     * [unsigned] instead consumes *every* byte and truncates to 32 bits, so the
     * two only agree while the field really is four bytes wide.
     */
    fun u32(): Long {
        var result = 0L
        for (i in 0 until minOf(4, value.size)) result = (result shl 8) or (value[i].toLong() and 0xFF)
        return result
    }

    /** Big-endian signed 16-bit value, used for lane and junction angles. */
    fun signedAngle(): Int = unsigned().toShort().toInt()

    /** Value decoded as UTF-8 text. */
    fun text(): String = String(value, Charsets.UTF_8).trimEnd('\u0000')
}

/**
 * Splits an iAP2 body into its TLV entries.
 *
 * Port of the stock `RouteGuidanceTlvsKt.routeTlvs`. Returns `null` when the
 * stream is not a well-formed TLV list, matching the stock behaviour of
 * discarding the entire frame rather than decoding a prefix.
 */
internal fun iap2RouteTlvs(data: ByteArray): List<Iap2RouteTlv>? {
    if (data.size > 65529) return null
    val result = ArrayList<Iap2RouteTlv>()
    var offset = 0
    while (offset < data.size) {
        if (data.size - offset < TLV_HEADER_BYTES) return null
        val length = u16(data, offset)
        if (length < TLV_HEADER_BYTES || length > data.size - offset) return null
        result.add(Iap2RouteTlv(u16(data, offset + 2), data.copyOfRange(offset + 4, offset + length)))
        offset += length
    }
    return result
}

private fun u16(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

private const val TLV_HEADER_BYTES = 4

/**
 * Merges incremental route-guidance updates into a [CarNavigationState].
 *
 * iAP2 sends route guidance as a stream of partial updates, so each frame is
 * layered onto the last known state instead of replacing it.
 *
 * This class never throws. See the evidence-status note on [CarNavigationState].
 */
class Iap2NavigationAccumulator(
    /**
     * Monotonic clock, in nanoseconds.
     *
     * Injected so the route-end grace and the staleness rule can be unit-tested
     * without sleeping. Mirrors the stock app's `Iap2RouteState$1` lambda, which
     * wraps `System.nanoTime()`.
     */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var current = CarNavigationState()
    private val maneuvers = LinkedHashMap<Int, ManeuverSample>()

    /**
     * Lane groups keyed by event id — port of the stock `Iap2LaneStore.events`.
     *
     * The phone may send a group ahead of time and only point at it later from
     * `0x5201` (id `0x10`), so the groups have to be kept rather than replaced.
     * The stock store holds eight and evicts the oldest entry that is not the
     * selected one; the same rule is applied here.
     */
    private val laneGroups = LinkedHashMap<Int, LaneSample>()

    /**
     * Stock `Iap2LaneStore.selected`: the lane-group event id the phone last
     * pointed at (`0x5201` id `0x10`). `null` means "nothing selected".
     */
    private var selectedLaneEventId: Int? = null

    /**
     * When the phone reported "route ending" (`guidanceState == 0`), or null.
     *
     * Stock `Iap2RouteState.pendingEndNs`. The route is only dropped once this
     * is older than [END_GRACE_NS] — a single transient `0` must not blank the
     * cluster mid-junction.
     */
    private var pendingEndNs: Long? = null

    /** When a route-level field last arrived; drives the [STALE_ROUTE_NS] rule. */
    private var lastRouteUpdateNs: Long? = null

    // Apple can prefetch manoeuvres/lanes after a zero-state boundary and name the
    // new source later. A source switch must discard the old route, not those new
    // records. Stage only post-boundary records for the existing grace duration;
    // this adds no timer and never extends route liveness.
    private var sourcePrefetchNs: Long? = null
    private val sourcePrefetchManeuvers = LinkedHashMap<Int, ManeuverSample>()
    private val sourcePrefetchLanes = LinkedHashMap<Int, LaneSample>()
    private var diagnostics: (String) -> Unit = {}
    private var explicitLaneSelectionSeen = false
    private var lastControlTrace: String? = null

    /** Metadata-only trace: no road/destination text or raw Parcel content. */
    @Synchronized
    fun setDiagnostics(sink: (String) -> Unit) { diagnostics = sink }

    private fun trace(message: String) {
        runCatching { diagnostics("navigation.merge $message") }
    }

    /**
     * Layers [frame] onto the accumulated state and returns the merged result.
     *
     * Returns the unchanged state when the frame is not a navigation message or
     * carries nothing this revision understands.
     */
    @Synchronized
    fun update(frame: Iap2Frame): CarNavigationState {
        if (!isNavigationMessage(frame.messageId)) return current
        return try {
            when (frame.messageId) {
                ROUTE_GUIDANCE_UPDATE -> mergeRouteGuidance(frame.payload)
                ROUTE_GUIDANCE_MANEUVER_UPDATE -> mergeManeuver(frame.payload)
                LANE_GUIDANCE_INFO_UPDATE -> mergeLanes(frame.payload)
                else -> Unit
            }
            expireIfStale()
            current
        } catch (error: Exception) {
            // A degraded HUD is acceptable; a broken CarPlay session is not.
            trace("decode_exception messageId=${frame.messageId} type=${error.javaClass.simpleName}")
            current
        }
    }

    /** Drops all accumulated state, e.g. when route guidance stops. */
    @Synchronized
    fun reset(): CarNavigationState = clear()

    /** Evaluate an explicit end grace window even when the phone sends no further frames. */
    @Synchronized
    fun expirePendingEnd(): CarNavigationState {
        expireIfStale()
        return current
    }

    @Synchronized
    fun hasPendingEnd(): Boolean = pendingEndNs != null

    /**
     * Applies the stock app's two time-based rules.
     *
     * Evaluated lazily on every frame, exactly like the stock `isActive()`: the
     * phone never sends a heartbeat, so "the route ended" is only ever
     * *inferred* here. The case where frames stop arriving altogether is covered
     * downstream by the self-draw snapshot TTL.
     */
    private fun expireIfStale() {
        val now = nanoTime()
        val end = pendingEndNs
        if (end != null && now - end >= END_GRACE_NS) {
            clear("end_grace")
            return
        }
        val last = lastRouteUpdateNs
        if (current.routeActive && last != null && now - last >= STALE_ROUTE_NS) clear("stale")
    }

    /** Stock `clear()`: wipe every route-level field, the manoeuvre cache and the lanes. */
    private fun clear(reason: String = "session_reset"): CarNavigationState {
        trace("clear reason=$reason active=${current.routeActive} maneuvers=${maneuvers.size} laneGroups=${laneGroups.size}")
        current = CarNavigationState()
        maneuvers.clear()
        laneGroups.clear()
        selectedLaneEventId = null
        explicitLaneSelectionSeen = false
        pendingEndNs = null
        lastRouteUpdateNs = null
        clearSourcePrefetch()
        return current
    }

    private fun clearSourcePrefetch() {
        sourcePrefetchNs = null
        sourcePrefetchManeuvers.clear()
        sourcePrefetchLanes.clear()
    }

    private fun hasFreshSourcePrefetch(): Boolean {
        val since = sourcePrefetchNs ?: return false
        val age = nanoTime() - since
        if (age < 0L || age >= END_GRACE_NS) {
            clearSourcePrefetch()
            return false
        }
        return true
    }

    private fun beginSourcePrefetch() {
        if (!hasFreshSourcePrefetch()) sourcePrefetchNs = nanoTime()
    }

    private fun clearForSourceChange() {
        val fresh = hasFreshSourcePrefetch()
        val newManeuvers = if (fresh) LinkedHashMap(sourcePrefetchManeuvers) else emptyMap()
        val newLanes = if (fresh) LinkedHashMap(sourcePrefetchLanes) else emptyMap()
        clear("source_change")
        maneuvers.putAll(newManeuvers)
        laneGroups.putAll(newLanes)
        // No old selection or published content survives. The new route must
        // select its own indices; do not infer one from arrival order.
    }

    /** A reset while no route has started still clears all visible content. Only
     * records received AFTER the preceding zero boundary can survive as a short-
     * lived candidate, requiring a later positive frame and an explicit index.
     * An active-route stop, session reset, or expiry never uses this exception.
     */
    private fun clearStartupBoundary(reason: String) {
        val keep = !current.routeActive && hasFreshSourcePrefetch()
        val since = if (keep) sourcePrefetchNs else null
        val stagedManeuvers = if (keep) LinkedHashMap(sourcePrefetchManeuvers) else emptyMap()
        val stagedLanes = if (keep) LinkedHashMap(sourcePrefetchLanes) else emptyMap()
        clear(reason)
        if (since != null) {
            sourcePrefetchNs = since // do NOT extend the original 3s candidate window
            sourcePrefetchManeuvers.putAll(stagedManeuvers)
            sourcePrefetchLanes.putAll(stagedLanes)
        }
        trace("startup_boundary reason=$reason stagedManeuvers=${stagedManeuvers.size} stagedLanes=${stagedLanes.size}")
    }

    /**
     * `0x5201`: route-level fields.
     *
     * ## Control reset and source metadata are deliberately separated
     *
     * The route field walk retains the stock layout. Compatibility handling
     * checks reset/clear controls before source metadata can consume prefetched
     * records. Reset/clear still discard all visible content immediately:
     *
     * 1. **clear/reset**: drop visible state; inactive startup candidates may be
     *    staged within their ORIGINAL bound, never extending route liveness.
     * 2. **source** (id `0x13`): discard the old route on a changed key; retain
     *    only fresh records staged after an explicit zero-state boundary.
     * 3. **guidanceState** (id `1`): `2` wipes and stops; `0` only arms the
     *    [END_GRACE_NS] timer and stops; anything `> 0` cancels a pending end.
     * 4. **refresh stamp**: the [ROUTE_REFRESH_IDS] set decides whether
     *    [lastRouteUpdateNs] moves. Evaluated *after* step 3, so a
     *    `guidanceState == 0` frame never refreshes the route.
     * 5. field walk, then the empty-manoeuvre-list short circuit, then
     *    `routeActive`.
     */
    private fun mergeRouteGuidance(body: ByteArray) {
        val tlvs = iap2RouteTlvs(body) ?: run { trace("invalid_tlv messageId=0x5201"); return }
        // Optional fields are deltas. Reject malformed known fields atomically,
        // and do not mix another navigation component into this component's cache.
        if (!validRouteFields(tlvs)) { trace("invalid_fields messageId=0x5201"); return }
        val guidanceState = singleField(tlvs, ROUTE_TLV_GUIDANCE_STATE)
            ?.takeIf { it.value.size >= 1 }
            ?.let { it.value[0].toInt() and 0xFF }
        val indexFields = tlvs.filter { it.id == ROUTE_TLV_MANEUVER_INDICES }
        val indices = indexFields.flatMap { field ->
            field.value.indices.step(2).map { u16(field.value, it) }
        }.distinct()
        val indexField = indexFields.firstOrNull()
        val clearFlag = tlvs.any { it.id == ROUTE_TLV_CLEAR && it.unsigned() == 0 }
        val control = "control state=$guidanceState clear=$clearFlag indexBytes=${indexField?.value?.size} " +
            "index=${indexField?.takeIf { it.value.size >= 2 }?.let { u16(it.value, 0) }} " +
            "sourcePresent=${tlvs.any { it.id == ROUTE_TLV_SOURCE }} active=${current.routeActive} " +
            "maneuvers=${maneuvers.size} laneGroups=${laneGroups.size}"
        if (control != lastControlTrace) { lastControlTrace = control; trace(control) }
        // These control frames already discard the rest of their body. Handle
        // them before source metadata consumes a just-received prefetch stage.
        if (clearFlag) {
            clearStartupBoundary("clear_flag")
            current = current.copy(sourceSupportsGuidance = false,
                routeSource = singleField(tlvs, ROUTE_TLV_SOURCE)?.text())
            return
        }
        if (guidanceState == 2) {
            clearStartupBoundary("state_2")
            current = current.copy(guidanceState = 2)
            return
        }
        if (guidanceState != null && guidanceState > 0 && !current.routeActive && hasFreshSourcePrefetch()) {
            // Promotion does not publish anything by itself. The ordinary field
            // walk below must resolve the phone's explicit selection.
            maneuvers.putAll(sourcePrefetchManeuvers)
            laneGroups.putAll(sourcePrefetchLanes)
            while (maneuvers.size > MANEUVER_CACHE_LIMIT) {
                val victim = maneuvers.keys.firstOrNull { it != current.maneuverIndex } ?: break
                maneuvers.remove(victim)
            }
            while (laneGroups.size > LANE_STORE_LIMIT) {
                val victim = laneGroups.keys.firstOrNull { it != selectedLaneEventId } ?: break
                laneGroups.remove(victim)
            }
        }

        // ── 1. source (id 0x13) ──────────────────────────────────────────────
        // Stock: a *change* of source invalidates the whole route — the user
        // switched navigation app, or the phone restarted guidance. The two
        // sides are compared through `sourceKey`, which folds the many spellings
        // of a navigation app ("Apple Maps" / "apple地图" / "苹果地图") onto one
        // key so a cosmetic rename does not look like a route change.
        // Note the stock app does **not** stop here after wiping: it records the
        // new source and keeps processing the frame.
        // Compatibility exception: preserve only the short-lived post-zero
        // prefetch stage, not the old route cache. This is our regression-tested
        // addition for late source metadata, not a claim about stock behaviour.
        val incomingSource = singleField(tlvs, ROUTE_TLV_SOURCE)?.text()
        if (incomingSource != null) {
            val previous = current.routeSource
            if (!previous.isNullOrEmpty() && sourceKey(incomingSource) != sourceKey(previous)) clearForSourceChange()
            current = current.copy(routeSource = incomingSource)
            // Even positive state + source is not a resolved route selection.
            // State 3 with source precedes Apple's new records and later source
            // key in vehicle logs. Consume only after resolving an explicit
            // maneuver below, never here and never renew the original bound.
        }
        val wasRerouting = current.guidanceState == 5
        if (guidanceState == 5 && !wasRerouting && current.maneuverType != null) {
            // A new route must not reuse records from the previous route at index 0.
            maneuvers.clear()
            laneGroups.clear()
            selectedLaneEventId = null
            explicitLaneSelectionSeen = true
            current = withoutManeuver(current).copy(lanes = emptyList(), laneEventId = null,
                laneInstruction = null, maneuverIndex = null, maneuverIndices = emptyList(),
                segmentMeters = null, routeMeters = null, routeSeconds = null, arrivalEpochSeconds = null,
                routeDistanceDisplay = null, maneuverDistanceDisplay = null,
                routeGeneration = nextRouteGeneration())
        }
        current = current.copy(
            guidanceState = guidanceState ?: current.guidanceState,
            maneuverState = singleField(tlvs, 2)?.unsigned() ?: current.maneuverState,
            maneuverCount = singleField(tlvs, 14)?.unsigned() ?: current.maneuverCount,
            shownInApp = singleField(tlvs, 15)?.let { it.unsigned() == 1 } ?: current.shownInApp,
            laneCount = singleField(tlvs, 17)?.unsigned() ?: current.laneCount,
            laneDescription = singleField(tlvs, 18)?.text() ?: current.laneDescription,
            sourceSupportsGuidance = singleField(tlvs, 20)?.let { it.unsigned() == 1 }
                ?: current.sourceSupportsGuidance,
            routeDistanceDisplay = singleField(tlvs, 8)?.text() ?: current.routeDistanceDisplay,
            routeDistanceUnits = singleField(tlvs, 9)?.unsigned() ?: current.routeDistanceUnits,
            maneuverDistanceDisplay = singleField(tlvs, 11)?.text() ?: current.maneuverDistanceDisplay,
            maneuverDistanceUnits = singleField(tlvs, 12)?.unsigned() ?: current.maneuverDistanceUnits,
        )

        // ── 3. guidanceState (id 1) ──────────────────────────────────────────
        // 2 = end now, 0 = end after the grace period, > 0 = alive.
        when (guidanceState) {
            0 -> {
                beginSourcePrefetch()
                if (current.routeActive && pendingEndNs == null) pendingEndNs = nanoTime()
                return
            }
            null -> Unit
            else -> pendingEndNs = null
        }

        // ── 4. refresh stamp ─────────────────────────────────────────────────
        if (tlvs.any { it.id in ROUTE_REFRESH_IDS }) lastRouteUpdateNs = nanoTime()

        // ── 5. field walk ────────────────────────────────────────────────────
        var currentRoad = current.currentRoad
        var destination = current.destination
        var arrival = current.arrivalEpochSeconds
        var remainingSeconds = current.routeSeconds
        var remainingMeters = current.routeMeters
        var distanceMeters: Int? = null
        var activeIndex: Int? = null
        var maneuverListPresent = false
        var selectedLaneIndex: Int? = null

        for (tlv in tlvs) {
            when (tlv.id) {
                // id 1 was handled above.
                ROUTE_TLV_MANEUVER_STATE -> Unit // manoeuvre state index, unused by this app

                ROUTE_TLV_CURRENT_ROAD -> currentRoad = tlv.text()

                ROUTE_TLV_DESTINATION -> destination = tlv.text()

                // Stock: `takeIf { it > 0 }` — a zero epoch is not an ETA.
                ROUTE_TLV_ARRIVAL_TIME -> if (tlv.value.size >= 8) {
                    val v = tlv.unsignedLong()
                    if (v > 0L) arrival = v
                }

                // Stock: `takeIf { it >= 0 }`, so a negative value *clears* the
                // field rather than being ignored.
                ROUTE_TLV_REMAINING_TIME -> if (tlv.value.size >= 8) {
                    val v = tlv.unsignedLong()
                    remainingSeconds = if (v >= 0L) toRouteInt(v) else null
                }

                ROUTE_TLV_REMAINING_DISTANCE -> if (tlv.value.size >= 4) {
                    remainingMeters = toRouteInt(tlv.u32())
                }

                // Stock clamps to `Int.MAX_VALUE` here and only applies
                // `coerceAtLeast(0)` at the very end.
                ROUTE_TLV_DISTANCE_TO_MANEUVER -> if (tlv.value.size >= 4) {
                    distanceMeters = tlv.u32().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }

                ROUTE_TLV_MANEUVER_INDICES -> {
                    maneuverListPresent = true
                    activeIndex = indices.firstOrNull()
                }

                ROUTE_TLV_SELECTED_LANE -> if (tlv.value.size >= 2) selectedLaneIndex = tlv.unsigned()

                // 领克实车上 0x12 不是“是否显示车道”。拿它当开关会把车道丢掉。
                ROUTE_TLV_LANES_VISIBLE -> Unit

                // id 0x13 and 0x14 were handled above.
                else -> Unit
            }
        }

        // Stock applies the lane-store selection *before* the empty-list check,
        // so a half-started route can still show or hide the lane strip.
        if (selectedLaneIndex != null) {
            explicitLaneSelectionSeen = true
            // `0xffff` means nothing selected. `0` is a real group id on this car.
            selectedLaneEventId =
                if (selectedLaneIndex == LANE_SELECTED_NONE) null else selectedLaneIndex
            publishLane()
        } else if (activeIndex != null && current.maneuverIndex != null && activeIndex != current.maneuverIndex) {
            // A new maneuver has no implicit claim on the previous lane event.
            // Ordinary same-maneuver delta updates still retain their arrows.
            explicitLaneSelectionSeen = true
            selectedLaneEventId = null
            publishLane()
        }
        if (current.laneCount == 0) {
            selectedLaneEventId = null
            explicitLaneSelectionSeen = true
            laneGroups.clear()
            sourcePrefetchLanes.clear()
            publishLane()
        }

        // Stock: a manoeuvre-index field that is present but too short to hold an
        // index means "the list is still empty" — the rest of the frame is
        // dropped, so a half-started route cannot blank the cluster.
        if (maneuverListPresent && activeIndex == null) {
            // Keep route context from this delta, but never keep a stale arrow.
            current = withoutManeuver(current).copy(
                routeMeters = remainingMeters, routeSeconds = remainingSeconds,
                currentRoad = currentRoad, destination = destination, arrivalEpochSeconds = arrival,
                segmentMeters = distanceMeters, maneuverIndex = null, maneuverIndices = emptyList(),
                maneuverDistanceDisplay = singleField(tlvs, 11)?.text(),
            )
            return
        }

        var routeActive = current.routeActive
        if (guidanceState != null) routeActive = true
        if (activeIndex != null) routeActive = true

        current = current.copy(
            routeMeters = remainingMeters,
            routeSeconds = remainingSeconds,
            segmentMeters = distanceMeters ?: if (activeIndex != null && activeIndex != current.maneuverIndex)
                null else current.segmentMeters,
            currentRoad = currentRoad,
            destination = destination,
            arrivalEpochSeconds = arrival,
            maneuverIndex = activeIndex ?: current.maneuverIndex,
            routeActive = routeActive,
            maneuverDistanceDisplay = if (activeIndex != null && activeIndex != current.maneuverIndex &&
                tlvs.none { it.id == 11 }) null else current.maneuverDistanceDisplay,
            maneuverIndices = if (maneuverListPresent) indices else current.maneuverIndices,
            routeGeneration = current.routeGeneration ?: if (routeActive) nextRouteGeneration() else null,
        )

        val active = maneuvers[activeIndex ?: current.maneuverIndex]
        if (activeIndex != null && active == null) {
            current = withoutManeuver(current)
            trace("selection_missing index=$activeIndex cache=${maneuvers.size} lanes=${laneGroups.size} state=$guidanceState")
        }
        if (active != null) {
            current = current.copy(
                maneuverType = active.type,
                drivingSide = active.drivingSide,
                maneuverDescription = active.description.ifEmpty { null },
                nextRoad = active.afterRoad.ifEmpty { null },
                junctionType = active.junctionType,
                junctionAngle = active.junctionAngle,
                junctionAngles = active.junctionAngles,
                exitAngle = active.exitAngle,
            )
            if (distanceMeters != null) {
                current = current.copy(segmentMeters = distanceMeters)
            }
            if (incomingSource != null && guidanceState != null && guidanceState > 0 && activeIndex != null) {
                clearSourcePrefetch()
            }
        }
    }

    private fun withoutManeuver(state: CarNavigationState) = state.copy(
        maneuverType = null, maneuverDescription = null, nextRoad = null,
        drivingSide = null, junctionType = null, junctionAngle = null,
        junctionAngles = emptyList(), exitAngle = null,
    )

    private fun nextRouteGeneration(): Long = ROUTE_GENERATION.incrementAndGet()

    private fun validRouteFields(fields: List<Iap2RouteTlv>): Boolean {
        if (fields.filter { it.id in 0..20 && it.id != 13 }.groupBy { it.id }.any { it.value.size > 1 }) return false
        return fields.all { f ->
            val size = f.value.size
            when (f.id) {
                0 -> size == 2 && f.unsigned() == 42
                1 -> size == 1 && f.unsigned() in 0..6
                2 -> size == 1 && f.unsigned() in 0..3
                5, 6 -> size == 8
                7, 10 -> size == 4
                9, 12 -> size == 1 && f.unsigned() in 0..4
                13 -> size % 2 == 0
                14, 16, 17 -> size == 2
                15, 20 -> size == 1 && f.unsigned() in 0..1
                else -> true
            }
        }
    }

    /**
     * `0x5202`: the manoeuvre record, keyed by its index in id 1.
     *
     * Port of the stock `parseManeuverUpdate`. The record is cached so that a
     * later `0x5201` carrying only an index can still resolve a full manoeuvre.
     */
    private fun mergeManeuver(body: ByteArray) {
        val tlvs = iap2RouteTlvs(body) ?: return
        val component = singleField(tlvs, 0)
        if (component != null && (component.value.size != 2 || component.unsigned() != 42)) return

        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        val angles = ArrayList<Int>()

        for (tlv in tlvs) {
            when (tlv.id) {
                MANEUVER_TLV_INDEX -> if (tlv.value.size >= 2) index = u16(tlv.value, 0)
                MANEUVER_TLV_TYPE -> if (tlv.value.size >= 1) {
                    type = if (tlv.value.size == 2) u16(tlv.value, 0) else tlv.value[0].toInt() and 0xFF
                }
                MANEUVER_TLV_AFTER_ROAD -> afterRoad = tlv.text()
                MANEUVER_TLV_DRIVING_SIDE -> if (tlv.value.size >= 1) drivingSide = tlv.value[0].toInt() and 0xFF
                MANEUVER_TLV_JUNCTION_ANGLES -> {
                    var i = 0
                    while (i + 1 < tlv.value.size) {
                        angles.add((((tlv.value[i].toInt() and 0xFF) shl 8) or (tlv.value[i + 1].toInt() and 0xFF)).toShort().toInt())
                        i += 2
                    }
                }
                else -> Unit
            }
        }

        if (index == null || type == null) return
        val description = singleField(tlvs, MANEUVER_TLV_DESCRIPTION)?.text().orEmpty()
        val junctionType = singleField(tlvs, MANEUVER_TLV_JUNCTION_TYPE)?.unsigned()
        // Stock: `junctionAngle = angles.firstOrNull()` — there is no separate id
        // for it. The previous revision read id 0xb, which is actually `exitAngle`.
        val junctionAngle = angles.firstOrNull()
        val exitAngle = singleField(tlvs, MANEUVER_TLV_EXIT_ANGLE)?.signedAngle()
        val exitInfo = singleField(tlvs, MANEUVER_TLV_EXIT_INFO)?.text().orEmpty()
        val sample = ManeuverSample(
            type = type,
            drivingSide = drivingSide,
            afterRoad = afterRoad,
            description = description,
            junctionType = junctionType,
            junctionAngle = junctionAngle,
            exitAngle = exitAngle,
            exitInfo = exitInfo,
            junctionAngles = angles,
        )

        maneuvers[index] = sample
        if (hasFreshSourcePrefetch()) {
            sourcePrefetchManeuvers.remove(index)
            sourcePrefetchManeuvers[index] = sample
            while (sourcePrefetchManeuvers.size > MANEUVER_CACHE_LIMIT) {
                sourcePrefetchManeuvers.remove(sourcePrefetchManeuvers.keys.first())
            }
        }
        // The stock app caps the cache at 32 and evicts the oldest entry that is
        // neither active nor listed; only the cap is kept here.
        while (maneuvers.size > MANEUVER_CACHE_LIMIT) {
            val victim = maneuvers.keys.firstOrNull { it != index && it != current.maneuverIndex }
            if (victim == null) break
            maneuvers.remove(victim)
        }

        // Stock resolves the active manoeuvre *lazily* (`activeManeuver()` reads
        // `maneuvers[activeIndex]` on every publish), so a record only becomes
        // visible once the phone has named it in a `0x5201` index list. The
        // eager copy below is our stand-in for that lookup — which is why the
        // index must match: an unrequested record must not become current.
        if (index == current.maneuverIndex) {
            current = current.copy(
                maneuverType = sample.type,
                drivingSide = sample.drivingSide,
                maneuverDescription = sample.description.ifEmpty { null },
                nextRoad = sample.afterRoad.ifEmpty { null },
                junctionType = sample.junctionType,
                junctionAngle = sample.junctionAngle,
                junctionAngles = sample.junctionAngles,
                exitAngle = sample.exitAngle,
            )
        }
    }

    /**
     * `0x5204`: lane guidance.
     *
     * Port of the stock `Iap2LaneDecoder`: the body is itself a TLV list whose
     * id-2 entries are lane groups, each of which is *another* TLV list. A group
     * whose first entry is `id 0, width 2, value 42` is a lane record carrying a
     * position (id 0), a status (id 1) and highlight angles (id 3).
     */
    private fun mergeLanes(body: ByteArray) {
        val tlvs = iap2RouteTlvs(body) ?: return
        val marker = singleField(tlvs, LANE_TLV_MARKER) ?: return
        if (marker.value.size != 2 || marker.unsigned() != LANE_MARKER_VALUE) return
        // Stock `Iap2LaneDecoder`: id 1 is the **event id**, not a selected-lane
        // index. The lane the phone wants highlighted comes from `0x5201` id
        // `0x10` instead — an earlier revision confused the two and used this
        // field to *drop* every lane but one.
        val eventId = singleField(tlvs, LANE_TLV_EVENT_ID) ?: return
        if (eventId.value.size != 2) return
        val instruction = singleField(tlvs, LANE_TLV_INSTRUCTION)?.text().orEmpty()

        val groups = tlvs.filter { it.id == LANE_TLV_GROUP }
        if (groups.size > MAX_LANE_GROUPS) return
        if (groups.isEmpty()) {
            // A valid marker + event id with no groups replaces that event with
            // empty content. Malformed nonempty groups still leave state intact.
            laneGroups.remove(eventId.unsigned())
            sourcePrefetchLanes.remove(eventId.unsigned())
            publishLane()
            return
        }

        val lanes = ArrayList<CarLaneGuidance>()
        for (group in groups) {
            val parts = iap2RouteTlvs(group.value) ?: return
            val position = singleField(parts, LANE_PART_INDEX) ?: return
            if (position.value.size != 2) return
            val status = singleField(parts, LANE_PART_STATUS) ?: return
            if (status.value.size != 1) return
            val highlightField = singleField(parts, LANE_PART_HIGHLIGHT)
            if (highlightField != null && highlightField.value.size != 2) return
            val highlight = highlightField?.signedAngle()

            val angles = ArrayList<Int>()
            for (tf in parts.filter { it.id == LANE_PART_ANGLES }) {
                var i = 0
                while (i + 1 < tf.value.size) {
                    angles.add((((tf.value[i].toInt() and 0xFF) shl 8) or (tf.value[i + 1].toInt() and 0xFF)).toShort().toInt())
                    i += 2
                }
            }
            if (angles.size > MAX_LANE_ANGLES) return
            // Stock: `anglesDegrees = (listOfNotNull(highlight) + angles).distinct()`.
            // The highlight is *prepended*, and a duplicate later in the list is
            // dropped — the renderer draws one arrow per entry and colours the
            // entry equal to `highlightAngle`, so the order is visible.
            val merged = ArrayList<Int>(angles.size + 1)
            if (highlight != null) merged += highlight
            merged += angles

            lanes.add(
                CarLaneGuidance(
                    index = position.unsigned(),
                    angles = merged.distinct(),
                    highlightAngle = highlight,
                    status = CarLaneStatus.fromWire(status.unsigned()),
                ),
            )
        }
        if (lanes.isEmpty()) return
        // Stock rejects the whole frame when two lanes claim the same position.
        if (lanes.distinctBy { it.index }.size != lanes.size) return

        // Keep **every** lane, sorted by position: the stock renderer draws the
        // whole group, and `laneCount` downstream is the group size. The previous
        // revision kept only the lane matching the (mis-read) id-1 field, so the
        // cluster lane strip could never show more than one arrow.
        val group = LaneSample(
            lanes = lanes.sortedBy { it.index },
            instruction = instruction.ifEmpty { null },
            eventId = eventId.unsigned(),
        )

        // Stock `Iap2LaneStore.accept`: remove-then-put so this event becomes the
        // most recent, then trim back to eight, never evicting the selected one.
        laneGroups.remove(group.eventId)
        laneGroups[group.eventId] = group
        if (hasFreshSourcePrefetch()) {
            sourcePrefetchLanes.remove(group.eventId)
            sourcePrefetchLanes[group.eventId] = group
            while (sourcePrefetchLanes.size > LANE_STORE_LIMIT) {
                sourcePrefetchLanes.remove(sourcePrefetchLanes.keys.first())
            }
        }
        // 还没有 0x10 选中任何组时，直接采用这一帧的编号。
        if (selectedLaneEventId == null && !explicitLaneSelectionSeen) selectedLaneEventId = group.eventId
        while (laneGroups.size > LANE_STORE_LIMIT) {
            val victim = laneGroups.keys.firstOrNull { it != selectedLaneEventId } ?: break
            laneGroups.remove(victim)
        }
        publishLane()
    }

    /**
     * Republishes the currently displayable lane group.
     *
     * 选中编号缺失或为 `0xFFFF` 时不显示。`0x12` 不参与。
     */
    private fun publishLane() {
        val group = if (current.laneCount == 0) null else selectedLaneEventId?.let { laneGroups[it] }
        current = current.copy(
            lanes = group?.lanes.orEmpty(),
            laneEventId = group?.eventId,
            laneInstruction = group?.instruction,
        )
    }

    private fun singleField(tlvs: List<Iap2RouteTlv>, id: Int): Iap2RouteTlv? {
        var found: Iap2RouteTlv? = null
        for (tlv in tlvs) {
            if (tlv.id != id) continue
            if (found != null) return null
            found = tlv
        }
        return found
    }

    private fun toRouteInt(value: Long): Int =
        if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()

    /**
     * Normalises a navigation-app name so cosmetic differences do not look like
     * a route change.
     *
     * Port of the stock `Iap2RouteState.sourceKey` (`smali:3463-3627`): trim,
     * lowercase with [Locale.ROOT], then fold the known spellings of Amap and
     * Apple Maps onto one key. Anything else is its own key.
     */
    private fun sourceKey(name: String): String = when (val key = name.trim().lowercase(Locale.ROOT)) {
        "百度地图", "百度", "baidu", "baidu maps", "baidumap" -> "baidu"
        "autonavi", "高德地图", "高德", "amap" -> "amap"
        "苹果地图", "apple", "apple maps", "apple地图" -> "apple"
        else -> key
    }

    /** A decoded `0x5202` manoeuvre record, cached by index. */
    private data class ManeuverSample(
        val type: Int,
        val drivingSide: Int,
        val afterRoad: String,
        val description: String,
        val junctionType: Int?,
        /** First junction angle — the stock app's `junctionAngle`. */
        val junctionAngle: Int?,
        /** Roundabout exit angle (id `0xb`). */
        val exitAngle: Int?,
        val exitInfo: String,
        val junctionAngles: List<Int>,
    )

    /**
     * A decoded `0x5204` lane group, cached by event id.
     *
     * The stock app's `GeelyLaneGuidance` carries the group size as well; here
     * the size is just [lanes].size, which is the same value (`Iap2LaneDecoder`
     * passes `lanes.size` as the count).
     */
    private data class LaneSample(
        val lanes: List<CarLaneGuidance>,
        val instruction: String?,
        val eventId: Int,
    )

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        const val LANE_GUIDANCE_INFO_UPDATE = 0x5204

        private const val MANEUVER_CACHE_LIMIT = 32
        private val ROUTE_GENERATION = java.util.concurrent.atomic.AtomicLong()
        private const val MAX_LANE_GROUPS = 16
        private const val MAX_LANE_ANGLES = 8
        private const val LANE_MARKER_VALUE = 42

        /**
         * Lane groups kept by the store (`Iap2LaneStore.accept` trims to `> 8`).
         */
        private const val LANE_STORE_LIMIT = 8

        /**
         * `0x5201` id `0x10` value meaning "nothing selected" (`Iap2LaneStore.select`).
         */
        private const val LANE_SELECTED_NONE = 0xFFFF

        /**
         * Route-end grace, in nanoseconds (`END_GRACE_NS` in the stock app).
         *
         * The phone signals "route ending" with `guidanceState == 0` and then
         * often keeps sending updates for a moment; the stock app waits 3 s
         * before actually dropping the route so a transient 0 does not blank
         * the cluster mid-junction.
         */
        private const val END_GRACE_NS = 3_000_000_000L

        /**
         * Route staleness, in nanoseconds (`STALE_ROUTE_NS` in the stock app).
         *
         * 120 s without a route-level field means the route is gone.
         */
        private const val STALE_ROUTE_NS = 120_000_000_000L

        /** True when [messageId] is a navigation endpoint this decoder recognises. */
        fun isNavigationMessage(messageId: Int): Boolean = when (messageId) {
            ROUTE_GUIDANCE_UPDATE,
            ROUTE_GUIDANCE_MANEUVER_UPDATE,
            LANE_GUIDANCE_INFO_UPDATE,
            -> true
            else -> false
        }

        // ─────────────────────────────────────────────────────────────────────
        // TLV ids below are OBSERVED on the stock Geely app's own decoder, not
        // inferred. See the evidence-status note on CarNavigationState.
        //
        // PATCH-0039 corrected the 0x5202 table: the previous revision had
        // description/afterRoad/junctionType/exitAngle shifted onto wrong ids,
        // which is why `maneuverDescription` was always null and the self-draw
        // classifier could never take its (more reliable) text branch.
        // ─────────────────────────────────────────────────────────────────────

        // ── 0x5201 RouteGuidanceUpdate ──
        private const val ROUTE_TLV_GUIDANCE_STATE = 1
        private const val ROUTE_TLV_MANEUVER_STATE = 2
        private const val ROUTE_TLV_CURRENT_ROAD = 3
        private const val ROUTE_TLV_DESTINATION = 4
        private const val ROUTE_TLV_ARRIVAL_TIME = 5
        private const val ROUTE_TLV_REMAINING_TIME = 6
        private const val ROUTE_TLV_REMAINING_DISTANCE = 7
        private const val ROUTE_TLV_DISTANCE_TO_MANEUVER = 0xA
        private const val ROUTE_TLV_MANEUVER_INDICES = 0xD
        private const val ROUTE_TLV_SELECTED_LANE = 0x10
        private const val ROUTE_TLV_LANES_VISIBLE = 0x12
        private const val ROUTE_TLV_SOURCE = 0x13
        private const val ROUTE_TLV_CLEAR = 0x14

        /**
         * Ids that count as "the route is still alive".
         *
         * Verbatim from the stock `Iap2RouteState` refresh set (`smali:2215-2279`,
         * a ten-entry `setOf`). A frame carrying none of these must not move
         * [lastRouteUpdateNs], otherwise a stream of unrelated frames would keep
         * a dead route looking fresh for ever.
         *
         * Note `1` is here even though [ROUTE_TLV_GUIDANCE_STATE] short-circuits
         * for the values `0` and `2` — a `guidanceState > 0` frame *is* a refresh.
         */
        private val ROUTE_REFRESH_IDS = setOf(
            ROUTE_TLV_GUIDANCE_STATE,
            ROUTE_TLV_CURRENT_ROAD,
            ROUTE_TLV_DESTINATION,
            ROUTE_TLV_ARRIVAL_TIME,
            ROUTE_TLV_REMAINING_TIME,
            ROUTE_TLV_REMAINING_DISTANCE,
            ROUTE_TLV_DISTANCE_TO_MANEUVER,
            ROUTE_TLV_MANEUVER_INDICES,
            ROUTE_TLV_SELECTED_LANE,
            ROUTE_TLV_LANES_VISIBLE,
        )

        // ── 0x5202 RouteGuidanceManeuverUpdate ──
        private const val MANEUVER_TLV_INDEX = 1
        private const val MANEUVER_TLV_DESCRIPTION = 2
        private const val MANEUVER_TLV_TYPE = 3
        private const val MANEUVER_TLV_AFTER_ROAD = 4
        private const val MANEUVER_TLV_DRIVING_SIDE = 8
        private const val MANEUVER_TLV_JUNCTION_TYPE = 9
        private const val MANEUVER_TLV_JUNCTION_ANGLES = 0xA
        private const val MANEUVER_TLV_EXIT_ANGLE = 0xB
        private const val MANEUVER_TLV_EXIT_INFO = 0xD

        // ── 0x5204 LaneGuidanceInfoUpdate ──
        private const val LANE_TLV_MARKER = 0
        private const val LANE_TLV_EVENT_ID = 1
        private const val LANE_TLV_GROUP = 2
        private const val LANE_TLV_INSTRUCTION = 3
        private const val LANE_PART_INDEX = 0
        private const val LANE_PART_STATUS = 1
        private const val LANE_PART_ANGLES = 2
        private const val LANE_PART_HIGHLIGHT = 3
    }
}
