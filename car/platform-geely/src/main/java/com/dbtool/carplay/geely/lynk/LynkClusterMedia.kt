package com.dbtool.carplay.geely.lynk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lynk 车机**仪表音乐元数据**通道：向 oneOS `linkmanager` 推歌名/歌手/进度。
 *
 * ## 与借屏完全无关的第二条 Binder 通道
 *
 * 投画面走 `DimNaviService`（见 [LynkClusterDisplayWire]）；
 * 推歌名/进度/时长走这条 —— **别跟 `ecarx.dimprotocol` 混**。
 *
 * ## 协议（从 DiPlay(Lynk) 反编译提取）
 *
 * ```
 * 服务：com.geely.service.oneosapi / OneOSApiService   （★ bindService，不是 checkService）
 * 管理接口：com.geely.lib.oneosapi.IServiceManager
 * 业务接口：com.geely.lib.oneosapi.linkmanager.ILinkManager
 * 线程名：dbplay-lynk-media（单线程 worker）
 * ```
 *
 * 拿到 ILinkManager：`bindService(OneOSApiService)` 成功 → 在 `IServiceManager` 上
 * `transact(19)` 取回业务 IBinder → 之后每次推数据 `transact(26)`：
 *
 * | transact | 常量 | 数据 | 语义 |
 * |---|---|---|---|
 * | 19 | LINK_SERVICE | 读回 IBinder | 取 ILinkManager |
 * | 26 | MUSIC_METADATA=515 | writeInt(515) + writeString(json) | 下发音乐元数据 |
 *
 * 报文 json（`musicMessage()` 逐字段实测）：
 *
 * ```json
 * {"MediaData":"{\"Name\":..,\"Artist\":..,\"AlbumName\":..,\"TotalTime\":<秒>,
 *   \"ElapsedTime\":<秒>,\"Status\":3,\"AppName\":\"CarPlay\",\"AppPackageName\":\"<包名>\"}"}
 * ```
 *
 * - **Status：3 = 播放中，2 = 暂停**（[stop] 时重发一份只改 Status 的报文）。
 * - `TotalTime` / `ElapsedTime` 单位是**秒**。
 *
 * ## 并发纪律
 *
 * 触发链：`update(...)` → 与上次去重（latestKey）→ 入队 → worker 串行 publish；
 * 用递增 `generation` 令牌**丢弃过期报文**（并发下防串话，可借鉴）。
 *
 * ⚠️ 未实机验证；先写最小探测包确认 `OneOSApiService` 存在。
 */
class LynkClusterMedia(
    private val context: Context,
    /** 报文里回的包名。上游 DiPlay 卡死自己的 applicationId —— 移植时用本应用包名。 */
    private val appPackageName: String = context.packageName,
) {

    private companion object {
        const val TAG = "DBPlay-LynkMedia"

        const val SERVICE_PKG = "com.geely.service.oneosapi"
        const val SERVICE_CLS = "com.geely.service.oneosapi.OneOSApiService"
        const val MANAGER_INTERFACE = "com.geely.lib.oneosapi.IServiceManager"
        const val LINK_INTERFACE = "com.geely.lib.oneosapi.linkmanager.ILinkManager"

        /** IServiceManager 上取 ILinkManager 的 transact 码。 */
        const val TXN_LINK_SERVICE = 19

        /** ILinkManager 上下发音乐元数据的 transact 码。 */
        const val TXN_PUSH = 26

        /** 音乐元数据的 messageId。 */
        const val MUSIC_METADATA = 515
    }

    /** 一次要显示的曲目信息。 */
    data class NowPlaying(
        val name: String,
        val artist: String,
        val albumName: String,
        /** 总时长（毫秒）；报文里折成秒。 */
        val totalTimeMillis: Long,
        /** 已播放时长（毫秒）。 */
        val elapsedTimeMillis: Long,
        /** true = 播放中；false = 暂停。 */
        val playing: Boolean,
    ) {
        /** 去重键。 */
        fun dedupKey(): String = "$name|$artist|$albumName|$playing|$totalTimeMillis"
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dbplay-lynk-media").apply { isDaemon = true }
    }

    private val generation = AtomicInteger(0)
    private var latestKey: String? = null
    private var link: IBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "OneOSApiService 已连接，取 ILinkManager")
            link = service?.let { fetchLinkManager(it) }
            if (link == null) Log.w(TAG, "ILinkManager 取回为空")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "OneOSApiService 断开")
            link = null
        }
    }

    /** 绑定 oneOS 服务。幂等。 */
    fun start() {
        if (bound) return
        val intent = Intent().apply {
            component = ComponentName(SERVICE_PKG, SERVICE_CLS)
        }
        bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        Log.i(TAG, "bindService OneOSApiService bound=$bound")
    }

    /** 解绑。 */
    fun stop() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
        link = null
    }

    /**
     * 推送一条曲目信息。与上次相同则跳过；否则入队。
     */
    fun update(np: NowPlaying) {
        val key = np.dedupKey()
        if (key == latestKey && np.playing) return
        latestKey = key
        publish(np)
    }

    /**
     * 停止播放：重发一份只把 Status 改成 2（暂停）的报文。
     */
    fun stop2(np: NowPlaying) {
        latestKey = null
        publish(np.copy(playing = false))
    }

    // ---- worker ----

    private fun publish(np: NowPlaying) {
        val gen = generation.incrementAndGet()
        worker.execute {
            if (gen != generation.get()) {
                Log.d(TAG, "丢弃过期报文 gen=$gen")
                return@execute
            }
            val target = link ?: run {
                Log.w(TAG, "ILinkManager 未就绪，跳过本次推送")
                return@execute
            }
            val json = buildJson(np)
            val ok = push(target, json)
            Log.i(TAG, "推送音乐元数据 ok=$ok gen=$gen name=${np.name}")
        }
    }

    private fun fetchLinkManager(serviceManager: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MANAGER_INTERFACE)
            if (!serviceManager.transact(TXN_LINK_SERVICE, data, reply, 0)) return null
            reply.readException()
            reply.readStrongBinder()
        } catch (e: Exception) {
            Log.w(TAG, "取 ILinkManager 失败", e)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun push(linkManager: IBinder, json: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(LINK_INTERFACE)
            data.writeInt(MUSIC_METADATA)
            data.writeString(json)
            linkManager.transact(TXN_PUSH, data, reply, 0).also {
                if (it) reply.readException()
            }
        } catch (e: Exception) {
            Log.w(TAG, "推送音乐元数据失败", e)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 组装 oneOS 报文。 */
    private fun buildJson(np: NowPlaying): String {
        val media = JSONObject().apply {
            put("Name", np.name)
            put("Artist", np.artist)
            put("AlbumName", np.albumName)
            put("TotalTime", (np.totalTimeMillis / 1000L).coerceAtLeast(0))
            put("ElapsedTime", (np.elapsedTimeMillis / 1000L).coerceAtLeast(0))
            put("Status", if (np.playing) 3 else 2)
            put("AppName", "CarPlay")
            put("AppPackageName", appPackageName)
        }
        return JSONObject().put("MediaData", media.toString()).toString()
    }
}
