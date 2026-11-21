package com.dbtool.carplay.geely.lynk

import android.util.Log
import com.dbtool.carplay.api.developer.CarSensitiveText
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * DBPlay **调试反连隧道**（2026-10-10 新增，斌哥口径）。
 *
 * ## 这是什么
 *
 * 斌哥原话：「我想让你在 DBPlay 开着模式里面加一个，服务器就是公网 IP」。
 *
 * 痛点：改一行代码就要重新出包 → 传包 → 车机手装，一轮十几分钟。
 * 目标：车机开着 App 就自动**反连公网服务器**，开发者在自己机器上
 * `adb connect` 一路连到车机，能 logcat / shell / push，无需再出包。
 *
 * ## 为什么必须「车机主动反连」
 *
 * 车机在 NAT 后面（车载 4G 卡 / 车上 WiFi），没有公网入口，
 * 开发者侧**连不进去**。能通的只有车机 → 公网服务器这一个方向。
 *
 * ## 与 ADB 协议的关系（关键设计约束）
 *
 * ADB **不是裸 TCP**，它有握手 / AUTH / 多路复用，所以「把 adbd 的流
 * 直接搬过去」这种纯转发**不可行**——对端 adb 客户端看到的不是真 adbd。
 *
 * 真正可行的是**端口级隧道**：
 *
 * ```
 * [车机 App] --(本类)--> 反连 TCP serverHost:tunnelPort
 *                          ↓ 双向转发
 * [开发者/服务器] adb connect 127.0.0.1:<本地映射口>
 *                 ↓ socat 中继
 *              车机 127.0.0.1:adbPort（真 adbd）
 * ```
 *
 * 即：本类在车机上扮演「**socat 客户端**」——把外部进来的每个字节
 * 转发给**本机 adbd**（`127.0.0.1:adbPort`），把 adbd 回的每个字节
 * 转发回去。服务器侧的 socat/中继只做纯 TCP 搬运，**ADB 协议原样透传**，
 * 由开发者的 adb 客户端与车机 adbd 直接对话。
 *
 * ## 复用已有的「内置 ADB」能力
 *
 * 前置条件：车机 adbd 已在监听（由用户显式初始化的全局 ADB 通道配置为 5556）。
 * 本类**不负责**开 adbd，只负责把 adbd 的端口接到公网。
 *
 * ## 连接态与自愈
 *
 * - `IDLE` 未启用 / `CONNECTING` 正在连 / `CONNECTED` 已通
 * - `RETRYING` 断开但会自动重连（指数退避，上限 60s）
 * - 所有异常都吞掉并转 [RETRYING]，**绝不因隧道问题影响 CarPlay 主链路**
 *
 * ⚠️ 只应在**用户显式打开**调试开关时启动；默认关闭（安全）。
 * ⚠️ 未在真车验证（模拟器上 adbd 端口与车机不同，需真机确认 adbPort）。
 */
class LynkDebugTunnel(
    /** 公网服务器地址（斌哥口径：填公网 IP）。 */
    private val serverHost: String,
    /** 服务器上等待反连的端口。 */
    private val serverPort: Int,
    /** 车机本机 adbd 监听端口（默认复用 DBPlay 全局 5556 ADB 通道）。 */
    private val localAdbPort: Int,
    /**
     * 反连口令（2026-10-10，斌哥选"加口令"防 7000 裸奔）。
     *
     * 服务器中继会先等这串口令，对不上直接踢掉 —— 防止被人扫到端口
     * 就白捡一台车机的 ADB。留空 = 不校验（**不推荐**，仅调试用）。
     */
    private val secret: String = "",
    /**
     * 车架号（2026-10-17，车队化）。有值时握手帧追加 `vin=`，
     * 跳板据此把不同车分到不同端口；null / 空串时发旧帧，保持兼容。
     */
    private val vin: String? = null,
    /** App 版本名（握手帧 `app=` 字段，仅在有 VIN 时追加）。 */
    private val appVersion: String = "",
    /**
     * VIN 身份 token（2026-10-17，P1）。非空且 VIN 非空时握手追加 `tok=`。
     * 空串 = 不发送，保持 legacy 通道。
     */
    private val token: String = "",
) {

    companion object {
        private const val TAG = "XCExt.dbplay-tunnel"

        /** 口令握手前缀（服务器侧 relay.py 按同一协议解析）。 */
        private const val HELLO_MAGIC = "DBPLAY-TUNNEL"

        /** 握手帧里的 VIN 字段前缀。 */
        private const val VIN_FIELD = "vin="

        /** 握手帧里的 App 版本字段前缀。 */
        private const val APP_FIELD = "app="

        /** 握手帧里的 VIN 身份 token 字段前缀。 */
        private const val TOKEN_FIELD = "tok="

        /** 口令握手超时。 */
        private const val HELLO_TIMEOUT_MS = 8_000

        /** 首次重连等待。 */
        private const val RETRY_BASE_MS = 3_000L

        /** 重连退避上限。 */
        private const val RETRY_MAX_MS = 60_000L

        /** 单次 socket 连接超时。 */
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** 转发缓冲区大小（与 ADB MAX_PAYLOAD 同量级，减少小包）。 */
        private const val BUF_SIZE = 64 * 1024

        /**
         * 构造口令握手帧（2026-10-17）。
         *
         * 三种形态必须严格向后兼容：
         * - 有 VIN + token：`DBPLAY-TUNNEL <secret> vin=<VIN> app=<version> tok=<token>\n`
         * - 有 VIN 无 token：`DBPLAY-TUNNEL <secret> vin=<VIN> app=<version>\n`
         * - 无 VIN：与旧版逐字节一致的 `DBPLAY-TUNNEL <secret>\n`
         *
         * 抽成 internal 函数只为单测钉住字节形态，不改变 [runLoop] 的发送时机。
         */
        internal fun buildHandshake(
            secret: String,
            vin: String?,
            appVersion: String,
            token: String = "",
        ): String {
            val trimmedVin = vin?.trim().orEmpty()
            val line = StringBuilder()
                .append(HELLO_MAGIC)
                .append(' ')
                .append(secret)
            if (trimmedVin.isNotEmpty()) {
                line.append(' ').append(VIN_FIELD).append(trimmedVin)
                val trimmedVersion = appVersion.trim()
                if (trimmedVersion.isNotEmpty()) {
                    line.append(' ').append(APP_FIELD).append(trimmedVersion)
                }
                val trimmedToken = token.trim()
                if (trimmedToken.isNotEmpty()) {
                    line.append(' ').append(TOKEN_FIELD).append(trimmedToken)
                }
            }
            return line.append('\n').toString()
        }
    }

    /** 隧道状态。 */
    enum class State { IDLE, CONNECTING, CONNECTED, RETRYING }

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var running = false

    private var worker: Thread? = null

    /** 最近一次失败原因（已脱敏的简短描述）。 */
    @Volatile
    private var lastError: String? = null

    /** 最近一次成功连上的时刻（毫秒）。未连过为 0。 */
    @Volatile
    private var lastConnectedAtMs: Long = 0L

    /** 当前这次连接开始时刻（毫秒）。未在连为 0。 */
    @Volatile
    private var connectStartedAtMs: Long = 0L

    /** 本轮累计重连次数（start 时归零）。 */
    @Volatile
    private var reconnectCount: Int = 0

    /** 当前活动 socket：stop 时必须主动 close 才能唤醒阻塞读。 */
    @Volatile
    private var downstreamSocket: Socket? = null

    @Volatile
    private var upstreamSocket: Socket? = null

    /** 隧道诊断快照（只读，给 UI 展示「连没连上 / 上次为什么失败」）。 */
    data class TunnelStatus(
        val state: State,
        val lastError: String?,
        val lastConnectedAtMs: Long,
        val connectStartedAtMs: Long,
        val reconnectCount: Int,
    )

    /** 只读诊断快照。 */
    val status: TunnelStatus
        get() = TunnelStatus(state, lastError, lastConnectedAtMs, connectStartedAtMs, reconnectCount)

    /** 当前状态（给 UI/诊断用）。 */
    val currentState: State get() = state

    /** 状态变化回调（给 UI 刷新用，可为 null）。 */
    var onStateChanged: ((State) -> Unit)? = null

    private fun setState(s: State) {
        state = s
        runCatching { onStateChanged?.invoke(s) }
    }

    /**
     * 启动隧道（幂等）。已在跑则直接返回。
     *
     * @param serverHost 公网服务器（覆盖构造参数，便于设置页改地址后立即生效）
     * @param serverPort 反连端口
     */
    fun start(serverHost: String = this.serverHost, serverPort: Int = this.serverPort) {
        if (running) {
            Log.i(TAG, "隧道已在运行，忽略重复启动")
            return
        }
        running = true
        reconnectCount = 0
        lastError = null
        connectStartedAtMs = 0L
        setState(State.CONNECTING)
        worker = thread(name = "dbplay-debug-tunnel", isDaemon = true) {
            runLoop(serverHost, serverPort)
        }
    }

    /**
     * 停止隧道并释放资源。
     *
     * ⚠️ 2026-10-16：**必须主动 close socket**。只 `interrupt()` 唤不醒
     * 阻塞在 `Socket.read` / `connect` 上的工作线程（interrupt 对
     * 传统 BIO socket 无效），结果是「关了开关但反连连接还挂着」
     * —— 对端仍看得到车机。close 会让阻塞读立刻抛异常并让循环退出。
     */
    fun stop() {
        if (!running && worker == null) return
        running = false
        // 关键：主动 close 两端 socket，才能唤醒阻塞在 read/connect 的工作线程
        runCatching { downstreamSocket?.close() }
        runCatching { upstreamSocket?.close() }
        downstreamSocket = null
        upstreamSocket = null
        worker?.interrupt()
        worker = null
        setState(State.IDLE)
        Log.i(TAG, "隧道已停止")
    }

    /** 主循环：连 → 转发 → 断 → 退避 → 重连，直到 [stop]。 */
    private fun runLoop(host: String, port: Int) {
        var backoff = RETRY_BASE_MS
        while (running) {
            try {
                setState(State.CONNECTING)
                connectStartedAtMs = System.currentTimeMillis()
                // ⚠️ 2026-10-11（PATCH-0076）：**不落服务器地址原文**。
                //    这条日志会进 logcat ⇒ 也就会进诊断包（要上传）。脱敏口径见 `CarSensitiveText`。
                Log.i(TAG, "正在反连服务器${CarSensitiveText.hostLabel(host)}（端口 $port）…")
                val ds = Socket()
                downstreamSocket = ds
                ds.use { downstream ->
                    downstream.tcpNoDelay = true
                    downstream.soTimeout = 0
                    downstream.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                    // 口令握手（2026-10-10）：连上先报口令，服务器对不上直接踢。
                    if (secret.isNotEmpty()) {
                        val line = buildHandshake(secret, vin, appVersion, token)
                        downstream.getOutputStream().apply {
                            write(line.toByteArray(Charsets.UTF_8))
                            flush()
                        }
                        // 等服务器 ACK（限时），拿不到就当作失败重连
                        downstream.soTimeout = HELLO_TIMEOUT_MS
                        val ack = readLineSafe(downstream)
                        downstream.soTimeout = 0
                        if (ack == null || !ack.startsWith("DBPLAY-OK")) {
                            Log.w(TAG, "服务器拒绝口令（ack=${ack?.take(32)}），断开重试")
                            throw IOException("tunnel auth rejected")
                        }
                    }

                    Log.i(
                        TAG,
                        "已连上服务器${CarSensitiveText.hostLabel(host)}（端口 $port），" +
                            "开始对接本机 adbd 127.0.0.1:$localAdbPort",
                    )
                    setState(State.CONNECTED)
                    backoff = RETRY_BASE_MS
                    lastError = null
                    lastConnectedAtMs = System.currentTimeMillis()

                    // 上游 = 车机本机 adbd
                    val us = Socket()
                    upstreamSocket = us
                    us.use { upstream ->
                        upstream.tcpNoDelay = true
                        upstream.connect(
                            InetSocketAddress("127.0.0.1", localAdbPort),
                            CONNECT_TIMEOUT_MS,
                        )
                        Log.i(TAG, "本机 adbd 已对接，双向转发开始")
                        bridge(upstream, downstream)
                    }
                }
            } catch (t: Throwable) {
                if (!running) break
                Log.w(TAG, "隧道断开（${t.javaClass.simpleName}: ${t.message}），${backoff}ms 后重连")
                lastError = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(120)
            }
            if (!running) break
            reconnectCount++
            setState(State.RETRYING)
            runCatching { Thread.sleep(backoff) }
            backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
        }
        setState(State.IDLE)
        Log.i(TAG, "隧道循环退出")
    }

    /**
     * 双向字节搬运：任一方向 EOF/异常即结束本次会话（触发外层重连）。
     *
     * 用两条守护线程各搬一个方向；主线程负责收尾。
     */
    private fun bridge(a: Socket, b: Socket) {
        val a2b = thread(name = "tunnel-up", isDaemon = true) { pump(a, b) }
        val b2a = thread(name = "tunnel-down", isDaemon = true) { pump(b, a) }
        // 等任一方向结束
        runCatching {
            while (a2b.isAlive && b2a.isAlive) {
                Thread.sleep(500)
                if (!running) break
            }
        }
        // 收尾：关闭两端（socketA 在 use 块里会再兜一次，幂等）
        runCatching { a.close() }
        runCatching { b.close() }
        a2b.interrupt()
        b2a.interrupt()
    }

    /**
     * 从 socket 读一行（\n 结尾，兼容 \r\n），带超时。
     *
     * 用于口令握手：服务器回 `DBPLAY-OK\n`。读不到返回 null。
     */
    private fun readLineSafe(s: Socket): String? {
        return try {
            val sb = StringBuilder()
            val inS = s.getInputStream()
            while (sb.length < 256) {
                val c = inS.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString()
                if (c != '\r'.code) sb.append(c.toChar())
            }
            sb.toString()
        } catch (t: Throwable) {
            null
        }
    }

    /** 单向搬运：src → dst，读到 -1 即停。 */
    private fun pump(src: Socket, dst: Socket) {
        val buf = ByteArray(BUF_SIZE)
        try {
            val inS = src.getInputStream()
            val outS = dst.getOutputStream()
            while (running) {
                val n = inS.read(buf)
                if (n < 0) break
                outS.write(buf, 0, n)
                outS.flush()
            }
        } catch (io: IOException) {
            // 正常断开（对端关闭 / 网络抖动），交给外层重连
        } catch (t: Throwable) {
            Log.w(TAG, "转发异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
