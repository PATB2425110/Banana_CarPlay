package com.dbtool.carplay.bridge.navigation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.iap2.message.CarLaneGuidance
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge

/**
 * CarPlay 导航写到亿咖通原厂仪表小卡片。
 *
 * 这台 CX11 上 `updateTurnByTurnArrow` 只打日志。仪表要的是导航服务打出来的协议 3407。
 * 车机地图在 `reportGuideInfo` 里通过 `BroadcastConnection` 把标准引导帧发给 `com.geely.naviService`，
 * 那个进程再转成 `onGuideInfo` / 3407。所以这里发高德引导 `10001` 和导航状态 `10019`，并点名导航服务。
 * 不发 30424，不调用 `switchNaviMode`。
 * 面板停在模式 3 时原厂小卡片会被收起，那是仪表投屏抢屏干的，这条链路不动面板。
 */
internal class GeelyNaviCard(
    private val sink: NaviCardSink,
) {
    private var active = false
    private var key: String? = null

    fun push(state: CarNavigationState?) {
        if (state?.maneuverType == null) {
            if (active) {
                sink.stopped()
                active = false
                key = null
            }
            return
        }
        if (!active) {
            sink.started()
            active = true
        }
        val update = cardUpdate(state)
        val next = listOf(
            update.arrow,
            update.segmentMeters,
            update.road,
            update.routeMeters,
            update.etaSeconds,
            update.lanes.joinToString(";") { "${it.number}:${it.backIconId}" },
        ).joinToString("|")
        if (next == key) return
        key = next
        sink.update(update)
    }
}

internal interface NaviCardSink {
    fun started()
    fun stopped()
    fun update(card: NaviCardUpdate)
}

internal data class NaviCardUpdate(
    val arrow: Int,
    val segmentMeters: Int,
    val road: String,
    val routeMeters: Int,
    val etaSeconds: Int,
    val lanes: List<NaviLaneIcon>,
)

internal data class NaviLaneIcon(
    val number: Int,
    val backIconId: Int,
)

internal object GeelyNaviIcons {
    /** 苹果机动类型 → 高德图标号。不要把 maneuverType 原样送进去。 */
    fun arrow(type: Int, drivingSide: Int?): Int = guideIcon(type, drivingSide).first

    /**
     * 高德图标号 → 仪表皮肤号。车机地图实车日志是 `turnId=103`（右转），
     * 对应高德图标 3。DimMenu 资源是 `skin_nav_turn_to_101` 到 `129`。
     */
    fun clusterTurn(icon: Int): Int = if (icon <= 0) 0 else icon + 100

    fun guideIcon(type: Int, drivingSide: Int?): Pair<Int, Int> {
        val leftHand = drivingSide == 1
        return when (type) {
            1, 20 -> 2 to 0
            2, 21 -> 3 to 0
            47 -> 6 to 0
            48 -> 7 to 0
            13, 22, 49, 52 -> 4 to 0
            14, 23, 50, 53 -> 5 to 0
            4, 18, 19, 26 -> (if (leftHand) 19 else 8) to 0
            6 -> (if (leftHand) 17 else 11) to 0
            7 -> (if (leftHand) 18 else 12) to 0
            in 28..46 -> (if (leftHand) 17 else 11) to (type - 27)
            10, 12, 24, 25, 27 -> 15 to 0
            3, 5, 8, 9, 11, 51 -> 9 to 0
            else -> 0 to 0
        }
    }

    fun laneAngleMask(angles: List<Int>): Int {
        if (angles.isEmpty()) return 1
        var mask = 0
        for (angle in angles) {
            mask = mask or when {
                kotlin.math.abs(angle) >= 160 -> 8
                angle > 20 -> 2
                angle < -20 -> 4
                else -> 1
            }
        }
        return mask
    }

    fun trafficLaneIcon(mask: Int): Int = when (mask) {
        1 -> 0
        4 -> 1
        5 -> 2
        2 -> 3
        3 -> 4
        8, 12 -> 5
        6 -> 6
        7 -> 7
        10 -> 8
        else -> 0
    }
}

private fun cardUpdate(state: CarNavigationState): NaviCardUpdate {
    val road = state.maneuverDescription?.takeIf { it.isNotBlank() }
        ?: state.nextRoad?.takeIf { it.isNotBlank() }
        ?: state.currentRoad?.takeIf { it.isNotBlank() }
        ?: ""
    return NaviCardUpdate(
        arrow = GeelyNaviIcons.arrow(state.maneuverType ?: 0, state.drivingSide),
        segmentMeters = (state.segmentMeters ?: 0).coerceAtLeast(0),
        road = road.trim { it <= ' ' || it == '\u0000' },
        routeMeters = (state.routeMeters ?: 0).coerceAtLeast(0),
        etaSeconds = (state.routeSeconds ?: 0).coerceAtLeast(0),
        lanes = state.lanes.take(16).map { lane ->
            NaviLaneIcon(
                number = lane.index.coerceAtLeast(0) + 1,
                backIconId = GeelyNaviIcons.trafficLaneIcon(GeelyNaviIcons.laneAngleMask(lane.angles)),
            )
        },
    )
}

/**
 * 反射调用亿咖通 `INaviInteraction`。类不在（平板）时只记一次日志。
 * 这里不调用 `switchNaviMode`。
 */
internal class EcarxDimNaviSink(context: Context) : NaviCardSink {
    private val app = context.applicationContext
    private var navi: Any? = null
    private var reportedMissing = false

    override fun started() {
        sendGuideState(active = true)
        val target = navi() ?: return
        if (!invokeBool(target, "notifyTurnByTurnStarted", true)) {
            invoke(target, "notifyTurnByTurnStarted")
        }
        Log.i(TAG, "notifyTurnByTurnStarted")
    }

    override fun stopped() {
        sendGuideState(active = false)
        sendStandardGuide(null)
        sendFactoryGuide(null)
        val target = navi ?: return
        invoke(target, "notifyTurnByTurnStopped")
        publishNaviStatus(active = false, card = null)
        Log.i(TAG, "notifyTurnByTurnStopped")
    }

    override fun update(card: NaviCardUpdate) {
        sendStandardGuide(card)
        sendFactoryGuide(card)
        val target = navi() ?: return
        val turn = GeelyNaviIcons.clusterTurn(card.arrow)
        invokeArrow(target, turn)
        invokeInt(target, "updateDistanceToNextGuidancePoint", card.segmentMeters)
        invokeString(target, "updateNextGuidancePointName", card.road)
        invokeInt(target, "updateDistanceToDestination", card.routeMeters)
        invokeInt(target, "updateETA", card.etaSeconds)
        pushNaviInfo(target, card)
        publishNaviStatus(active = true, card)
        // 不调用 updateLaneInfo。空车道也会传 Object[]，而车机要的是 ILaneInfo[]，
        // Method.invoke 直接抛 IllegalArgumentException。高德不发车道时一样崩。
        Log.i(
            TAG,
            "navi card arrow=${card.arrow} turn=$turn dist=${card.segmentMeters} road=${card.road} " +
                "remain=${card.routeMeters} eta=${card.etaSeconds} lanes=${card.lanes.size}",
        )
    }

    private fun navi(): Any? {
        navi?.let { return it }
        val created = runCatching { openNavi(app) }.getOrElse { error ->
            if (!reportedMissing) {
                reportedMissing = true
                Log.i(TAG, "DimInteraction unavailable: ${error.message}")
            }
            null
        }
        navi = created
        return created
    }

    private fun openNavi(context: Context): Any {
        val dimClass = Class.forName(DIM_CLASS)
        val dim = dimClass.getMethod("create", Context::class.java).invoke(null, context)
            ?: error("DimInteraction.create returned null")
        return dimClass.getMethod("getNaviInteraction").invoke(dim)
            ?: error("getNaviInteraction returned null")
    }

    private fun invoke(target: Any, name: String) {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.isEmpty()
        } ?: return
        runCatching { method.invoke(target) }
            .onFailure { Log.w(TAG, "$name failed: ${it.message}") }
    }

    private fun invokeBool(target: Any, name: String, value: Boolean): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))
        } ?: return false
        return runCatching { method.invoke(target, value) }
            .onFailure { Log.w(TAG, "$name failed: ${it.message}") }
            .isSuccess
    }

    private fun invokeInt(target: Any, name: String, value: Int) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return
        runCatching { method.invoke(target, value) }
            .onFailure { Log.w(TAG, "$name failed: ${it.message}") }
    }

    private fun invokeString(target: Any, name: String, value: String) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: return
        runCatching { method.invoke(target, value) }
            .onFailure { Log.w(TAG, "$name failed: ${it.message}") }
    }

    private fun invokeArrow(target: Any, arrow: Int) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == "updateTurnByTurnArrow" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return
        runCatching { method.invoke(target, arrow) }
            .onFailure { Log.w(TAG, "updateTurnByTurnArrow failed: ${it.message}") }
    }

    /**
     * 这台 CX11 上 `updateTurnByTurnArrow` 这几个函数只打日志，不往仪表送内容。
     * 仪表认的是 `navi.action.status`。原库用 `sendStickyBroadcastAsUser(user=-1)`，
     * 普通应用没有跨用户权限，广播发不出去，卡片就是空的。
     * 这里改发给当前用户，并带上箭头、距离和路名。
     */
    private fun publishNaviStatus(active: Boolean, card: NaviCardUpdate?) {
        val intent = Intent(NAVI_STATUS_ACTION).putExtra("status", active)
        if (card != null) {
            intent.putExtra("nextGuidancePointName", card.road)
            intent.putExtra("distanceToNextGuidancePoint", card.segmentMeters)
            intent.putExtra("remainDistance", card.routeMeters)
            intent.putExtra("eta", card.etaSeconds)
            intent.putExtra("turnId", card.arrow)
        }
        val plain = runCatching { app.sendBroadcast(intent) }
        val dimMenu = runCatching {
            app.sendBroadcast(Intent(intent).setPackage(DIM_MENU_PACKAGE))
        }
        Log.i(
            TAG,
            "navi status active=$active plain=${plain.isSuccess} dimMenu=${dimMenu.isSuccess}",
        )
    }

    /**
     * 高德标准引导帧。车机地图的 `BroadcastConnection` 发的是这一帧，
     * `com.geely.naviService` 收到后打出 `onGuideInfo`，图标号再加 100 变成仪表的 turnId。
     */
    private fun sendStandardGuide(card: NaviCardUpdate?) {
        val intent = Intent(FACTORY_GUIDE_ACTION)
            .addFlags(FACTORY_INCLUDE_BACKGROUND)
            .putExtra("KEY_TYPE", AMAP_GUIDE)
        if (card == null) {
            intent.putExtra("TYPE", 0)
            intent.putExtra("ICON", 0)
            intent.putExtra("NEW_ICON", 0)
            intent.putExtra("CUR_ROAD_NAME", "")
            intent.putExtra("NEXT_ROAD_NAME", "")
            intent.putExtra("SEG_REMAIN_DIS", 0)
            intent.putExtra("ROUTE_REMAIN_DIS", 0)
            intent.putExtra("ROUTE_REMAIN_TIME", 0)
        } else {
            intent.putExtra("TYPE", 0)
            intent.putExtra("ICON", card.arrow)
            intent.putExtra("NEW_ICON", card.arrow)
            intent.putExtra("CUR_ROAD_NAME", card.road)
            intent.putExtra("NEXT_ROAD_NAME", card.road)
            intent.putExtra("SEG_REMAIN_DIS", card.segmentMeters)
            intent.putExtra("SEG_REMAIN_TIME", 0)
            intent.putExtra("ROUTE_REMAIN_DIS", card.routeMeters)
            intent.putExtra("ROUTE_REMAIN_TIME", card.etaSeconds)
            intent.putExtra("ROAD_TYPE", 0)
            intent.putExtra("ROUNG_ABOUT_NUM", 0)
            intent.putExtra("CUR_SPEED", 0)
        }
        dispatch(intent)
        Log.i(TAG, "10001 icon=${card?.arrow ?: 0} dist=${card?.segmentMeters ?: -1} road=${card?.road.orEmpty()}")
    }

    /** 8 开始导航，9 结束。NaviServiceUI 用这个判断 `isGuideStart`。 */
    private fun sendGuideState(active: Boolean) {
        val intent = Intent(FACTORY_GUIDE_ACTION)
            .addFlags(FACTORY_INCLUDE_BACKGROUND)
            .putExtra("KEY_TYPE", AMAP_STATE)
            .putExtra("EXTRA_STATE", if (active) 8 else 9)
        dispatch(intent)
        Log.i(TAG, "10019 state=${if (active) 8 else 9}")
    }

    /** 吉利扩展引导帧。图标用仪表皮肤号。 */
    private fun sendFactoryGuide(card: NaviCardUpdate?) {
        val intent = Intent(FACTORY_GUIDE_ACTION)
            .addFlags(FACTORY_INCLUDE_BACKGROUND)
            .putExtra("KEY_TYPE", FACTORY_GUIDE)
        if (card == null) {
            intent.putExtra("TYPE", 0)
            intent.putExtra("ICON", 0)
            intent.putExtra("NEW_ICON", -1)
            intent.putExtra("CUR_ROAD_NAME", "")
            intent.putExtra("NEXT_ROAD_NAME", "")
            intent.putExtra("SEG_REMAIN_DIS", -1)
            intent.putExtra("ROUTE_REMAIN_DIS", -1)
            intent.putExtra("ROUTE_REMAIN_TIME", -1)
        } else {
            val turn = GeelyNaviIcons.clusterTurn(card.arrow)
            intent.putExtra("TYPE", 0)
            intent.putExtra("ICON", turn)
            intent.putExtra("NEW_ICON", turn)
            intent.putExtra("CUR_ROAD_NAME", card.road)
            intent.putExtra("NEXT_ROAD_NAME", card.road)
            intent.putExtra("SEG_REMAIN_DIS", card.segmentMeters)
            intent.putExtra("SEG_REMAIN_TIME", 0)
            intent.putExtra("ROUTE_REMAIN_DIS", card.routeMeters)
            intent.putExtra("ROUTE_REMAIN_TIME", card.etaSeconds)
            intent.putExtra("ROUNG_ABOUT_NUM", 0)
            intent.putExtra("ROAD_TYPE", 0)
            intent.putExtra("CUR_SPEED", 0)
        }
        dispatch(intent)
        Log.i(TAG, "30407 turn=${if (card == null) 0 else GeelyNaviIcons.clusterTurn(card.arrow)} dist=${card?.segmentMeters ?: -1} road=${card?.road.orEmpty()}")
    }

    private fun dispatch(intent: Intent) {
        listOf(null, NAVI_SERVICE_PACKAGE, FACTORY_MAP_PACKAGE, DIM_MENU_PACKAGE).forEach { pkg ->
            val outgoing = Intent(intent)
            if (pkg != null) outgoing.setPackage(pkg)
            runCatching { app.sendBroadcast(outgoing) }
                .onFailure { Log.w(TAG, "guide $pkg failed: ${it.message}") }
        }
    }

    /** 剩余路程走车机信号 `setDstToDestination`，`updateDistanceToDestination` 本身不会。 */
    private fun pushNaviInfo(target: Any, card: NaviCardUpdate) {
        val info = runCatching {
            val type = Class.forName(NAVI_INFO_CLASS)
            val ctor = type.getConstructor(
                String::class.java,
                java.lang.Double.TYPE,
                java.lang.Double.TYPE,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
            )
            ctor.newInstance(
                card.road,
                0.0,
                0.0,
                card.routeMeters.toLong(),
                card.etaSeconds.toLong(),
            )
        }.getOrElse {
            Log.w(TAG, "NaviInfo unavailable: ${it.message}")
            return
        }
        val method = target.javaClass.methods.firstOrNull {
            it.name == "updateNaviInfo" && it.parameterTypes.size == 1
        } ?: return
        runCatching { method.invoke(target, info) }
            .onFailure { Log.w(TAG, "updateNaviInfo failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "DBPlay-DimNavi"
        const val DIM_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
        const val NAVI_INFO_CLASS = "com.ecarx.xui.adaptapi.diminteraction.NaviInfo"
        const val NAVI_STATUS_ACTION = "navi.action.status"
        const val DIM_MENU_PACKAGE = "com.geely.dimmenu"
        const val FACTORY_MAP_PACKAGE = "com.geely.map"
        const val NAVI_SERVICE_PACKAGE = "com.geely.naviService"
        const val FACTORY_GUIDE_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        const val AMAP_GUIDE = 10001
        const val AMAP_STATE = 10019
        const val FACTORY_GUIDE = 30407
        const val FACTORY_INCLUDE_BACKGROUND = 0x01000000
    }
}

/**
 * 每秒读一次 CarPlay 导航快照。内容变了才写 Dim。
 * 不看 HUD 开关，也不看仪表投屏开关。
 */
object GeelyClusterCardLink {
    private const val TAG = "DBPlay-VendorNavi"
    private val handler = Handler(Looper.getMainLooper())
    private var card: GeelyNaviCard? = null

    private val tick = object : Runnable {
        override fun run() {
            val current = card ?: return
            runCatching { current.push(CarPlayNavigationBridge.current()) }
                .onFailure { Log.e(TAG, "navi card tick failed", it) }
            handler.postDelayed(this, 1_000L)
        }
    }

    fun start(context: Context) {
        if (card != null) return
        guardNaviStatusThread()
        card = GeelyNaviCard(EcarxDimNaviSink(context.applicationContext))
        Log.i(TAG, "vendor navi link up dim=INaviInteraction")
        handler.post(tick)
    }

    /**
     * `notifyTurnByTurnStarted` 会另起名为 `navi.action.status` 的线程，
     * 在里面 `sendStickyBroadcastAsUser(user=-1)`。普通应用没有
     * `INTERACT_ACROSS_USERS`，这个 SecurityException 发生在我们的 try/catch 之外，
     * 默认处理会杀掉整个进程。这条线程的异常只记日志。
     */
    private fun guardNaviStatusThread() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name == "navi.action.status") {
                Log.w(TAG, "dim navi status broadcast denied", error)
            } else if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                throw error
            }
        }
    }

    fun stop() {
        val current = card ?: return
        handler.removeCallbacks(tick)
        current.push(null)
        card = null
        Log.i(TAG, "vendor navi link down")
    }
}
