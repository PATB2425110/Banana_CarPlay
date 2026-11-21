package com.dbtool.carplay.ecarx.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 车机蓝牙音乐（A2DP）与 CarPlay 抢声道的守卫。
 *
 * ## 现场问题（已实机确认）
 *
 * 日志（设备 d5ddb4312da1，0.8.9）证实：无线 CarPlay 先借蓝牙做 iAP2
 * 「bootstrap」握手（`iAP2 control starting` / `Bluetooth handoff requested`
 * 各 50+ 次），握手完成后请求 `disableBluetooth`（112 次）关闭该链路，但
 * **系统没有真正执行** —— HFP 仍保持 `isHfpConnected state : 2`，
 * 蓝牙继续占着音频输出，于是 CarPlay 声音被抢。
 *
 * ## 参考实现（只借思路，不复制代码）
 *
 * DiPlay 吉利定制版 `carlito12345/DiPlay` 的
 * `shared/.../vehicle/GeelyBluetoothAudioGuard.kt`（许可 GPL-3.0，
 * **照搬会传染**，故本文件为独立重写）。其做法：
 *
 * - 只盯「当前在跑 CarPlay 的那台设备」的地址，其它蓝牙设备一概不碰；
 * - 监听 A2DP-sink 连上的广播，用**反射**调 `disconnect()` 断掉该设备的音乐连接；
 * - 不断配对、不碰 HFP（打电话走蓝牙照常）；
 * - 500ms 去抖防反复断开；
 * - 拿不到权限/方法就老实记日志，不硬撑。
 *
 * ## 本文件的动作边界（重要）
 *
 * - **只断 A2DP（媒体/音乐）**，绝不调用 HFP 的 disconnect，打电话照常；
 * - 若提供 [targetMac]（当前 CarPlay 设备），**只对它动手**，其它蓝牙设备
 *   （例如用户自己的另一台手机）一概不碰；未提供时退化为「任何 A2DP 连接
 *   都断」，仅用于无法拿到地址的兜底场景。
 * - 断开是有副作用的动作，故去抖 500ms，且失败只记日志不硬撑。
 *
 * ## 使用
 *
 * ```kotlin
 * val guard = EcarxBluetoothAudioGuard(context, armed = true)
 * guard.start()   // CarPlay 会话建立（SessionStarted）时
 * guard.stop()    // 会话结束（SessionEnded）时
 * ```
 *
 * @param armed `false` = 只观测（排障用）；`true` = 观测到 A2DP 占用时真断开。
 * @param targetMac 当前 CarPlay 设备的蓝牙地址（大写）。为空则对任意 A2DP 连接生效。
 * @param pollIntervalMillis 主动巡检周期：广播不可靠时兜底检查 A2DP 是否仍连着。
 */
class EcarxBluetoothAudioGuard(
    private val context: Context,
    private val armed: Boolean = true,
    private val targetMac: String? = null,
    // 2026-10-25：3s -> 15s。现场日志（1.2.53 诊断包）显示会话期间 10.5 分钟
    // 新建/销毁 A2DP 代理 209 次，每次都是 `Proxy object connected` 紧跟
    // `close mService = null`，等于每 3 秒搅一次蓝牙栈，而 CarPlay 的无线通道
    // 完全依赖这条蓝牙链路（RFCOMM/iAP2 + 0x5703 下发 SSID 密码）。
    // 广播（ACTION_CONNECTION_STATE_CHANGED / BECOMING_NOISY）仍是主路径，
    // 巡检只是「系统不执行 disableBluetooth」时的兜底，没必要这么密。
    private val pollIntervalMillis: Long = A2DP_POLL_INTERVAL_MILLIS,
) {

    @Volatile
    private var running = false

    /** 会话期间主动巡检：广播不可靠时（实测系统不执行 disableBluetooth）兜底。 */
    private var poller: java.util.concurrent.ScheduledExecutorService? = null

    /**
     * A2DP 代理的生命周期（2026-10-25 新增）。
     *
     * 旧实现每轮巡检都 `getProfileProxy` + `closeProfileProxy`，现场日志里
     * `BluetoothA2dpSink: Proxy object connected` / `close mService = null`
     * 成对出现 209 次。代理与「已连接设备列表」都是系统侧状态，
     * 不需要每轮重建；建立一次、会话结束时释放即可。
     *
     * 状态逻辑抽到 [A2dpProxyRegistry]（纯 JVM，可单测）。
     */
    private val a2dpProxy = A2dpProxyRegistry<BluetoothProfile>(
        obtain = { getProfileProxyBlocking(A2DP_SINK_PROFILE) },
        release = { p -> closeProfileProxyQuietly(A2DP_SINK_PROFILE, p) },
    )

    /** 去抖：同一地址 500ms 内只处理一次（armed 模式下才用到）。 */
    @Volatile
    private var lastActionAtMillis: Long = 0L

    /** 记录 A2DP 上次状态，便于识别"从断开变连接"。 */
    @Volatile
    private var lastA2dpState: Int = BluetoothProfile.STATE_DISCONNECTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                -> onBluetoothConnectionChanged(intent)

                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    // 「音频即将被别的输出抢走」——蓝牙音乐抢占 CarPlay 时
                    // 车机常发这个广播，比 A2DP 状态变化更早、更直接。
                    Log.w(TAG, "ACTION_AUDIO_BECOMING_NOISY (output being stolen)")
                    logAudioRouteState("noisy")
                }

                else -> Unit
            }
        }
    }

    /** 开始观测。可在会话进行中重复调用（幂等）。 */
    fun start() {
        if (running) return
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        runCatching { context.registerReceiver(receiver, filter) }
            .onFailure { Log.w(TAG, "registerReceiver failed: ${it.message}") }
        running = true
        a2dpProxy.open()
        Log.i(TAG, "guard start (armed=$armed, observe-only=${!armed}, target=${targetMac ?: "*"})")
        if (!hasConnectPermission()) {
            Log.w(TAG, "missing BLUETOOTH_CONNECT: A2DP device address will be unavailable")
        }
        logAudioRouteState("start")
        startPoller()
    }

    /** 停止观测并注销广播（幂等）。 */
    fun stop() {
        if (!running) return
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "unregisterReceiver failed: ${it.message}") }
        running = false
        stopPoller()
        Log.i(TAG, "guard stop")
        logAudioRouteState("stop")
    }

    // ---------------------------------------------------------------- 内部

    private fun onBluetoothConnectionChanged(intent: Intent) {
        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val mac = device?.address?.uppercase()
        val name = device?.name

        Log.i(
            TAG,
            "a2dp ${stateName(prev)} -> ${stateName(state)} " +
                "device=${name ?: "?"}/${mac ?: "?"} armed=$armed",
        )

        // 「刚建立 A2DP 音乐连接」= 最可能抢走 CarPlay 声音的时刻。
        val justConnected = state == BluetoothProfile.STATE_CONNECTED &&
            prev != BluetoothProfile.STATE_CONNECTED
        lastA2dpState = state
        if (!justConnected) return
        handleA2dpOccupied(device, mac, "broadcast")
    }

    /**
     * A2DP 音乐链路占用时的统一处理入口（广播与主动巡检共用）。
     *
     * - 只为 [targetMac] 指定的设备（当前 CarPlay 手机）动手；未指定时任意设备；
     * - armed=false 只记一行日志；
     * - 去抖 500ms，避免反复断连。
     */
    private fun handleA2dpOccupied(device: BluetoothDevice?, mac: String?, source: String) {
        if (targetMac != null && mac != null && !mac.equals(targetMac.uppercase())) {
            Log.i(TAG, "skip $mac (not the CarPlay device $targetMac, source=$source)")
            return
        }
        if (!armed) {
            Log.w(
                TAG,
                "POTENTIAL CONFLICT OBSERVED (armed=false, took no action, source=$source): " +
                    "device ${mac ?: "?"} opened an A2DP music link while a CarPlay " +
                    "session is running.",
            )
            logAudioRouteState("a2dp-connected")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastActionAtMillis < DEBOUNCE_MILLIS) {
            Log.i(TAG, "debounced: skip disconnect for ${mac ?: "?"}")
            return
        }
        lastActionAtMillis = now
        Log.w(TAG, "A2DP occupied by ${mac ?: "?"} (source=$source): disconnecting music link")
        disconnectA2dp(device)
    }

    // ------------------------------------------------------------ 主动巡检

    private fun startPoller() {
        if (poller != null) return
        val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bt-guard-poll").apply { isDaemon = true }
        }
        exec.scheduleWithFixedDelay(
            { runCatching { pollA2dpOnce() } },
            // 首次巡检只等 1 秒：会话开始时手机可能已经 A2DP 连着、系统又不执行
            // disableBluetooth，这一段是最需要补刀的时机（旧 3s 周期天然覆盖）。
            // 代理反正会缓存，首轮多建一次的代价可忽略。
            POLL_INITIAL_DELAY_MILLIS,
            pollIntervalMillis,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        poller = exec
    }

    private fun stopPoller() {
        poller?.shutdownNow()
        poller = null
        // 会话结束释放缓存代理（只在这里 close，不再每轮 close）。
        a2dpProxy.close()
    }

    /**
     * 巡检一轮：取 A2DP_SINK 已连设备，若发现带 CarPlay 的目标设备仍在播，
     * 就交给 [handleA2dpOccupied]。
     *
     * 之所以需要它：实测系统收到 `disableBluetooth`（112 次）后并未真的断开，
     * 广播也可能不再触发；只有主动查 [BluetoothProfile.getConnectedDevices]
     * 才能发现「还连着」并补刀。
     */
    private fun pollA2dpOnce() {
        if (!armed || !running) return
        if (!hasConnectPermission()) return
        val p = a2dpProxy.acquire() ?: return
        // P1-6：读失败（服务重启 / adapter 关闭 / 绑定失效）必须让缓存失效，
        // 否则整个会话永久失明 —— 旧实现每 3 秒换新代理，天然自愈；缓存化后
        // 必须显式 invalidate，否则只会一直拿同一个死代理。
        val connected = runCatching { p.connectedDevices }.getOrElse {
            Log.w(TAG, "poll: connectedDevices failed (${it.javaClass.simpleName}); invalidate proxy")
            a2dpProxy.invalidate(p)
            return
        }
        if (connected.isNullOrEmpty()) return
        for (d in connected) {
            if (d == null) continue
            val mac = runCatching { d.address?.uppercase() }.getOrNull()
            if (targetMac != null && mac != null && !mac.equals(targetMac.uppercase())) continue
            handleA2dpOccupied(d, mac, "poll")
        }
    }

    /**
     * 阻塞式取 `getProfileProxy`（最多等 2 秒）。
     *
     * 系统回调 [BluetoothProfile.ServiceListener.onServiceDisconnected] 会
     * 通过 [onA2dpProxyLost] 让 [a2dpProxy] 失效，下一轮巡检自动重建。
     */
    private fun getProfileProxyBlocking(profile: Int): BluetoothProfile? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val gate = Any()
        var proxy: BluetoothProfile? = null
        /** 调用方已放弃（超时/中断）：迟到回调必须自行解绑，否则代理与 ServiceConnection 双双泄漏。 */
        var abandoned = false
        val latch = java.util.concurrent.CountDownLatch(1)
        val ok = runCatching {
            adapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p0: Int, p1: BluetoothProfile?) {
                        var late: BluetoothProfile? = null
                        synchronized(gate) {
                            if (p0 == profile) {
                                if (abandoned) late = p1 else proxy = p1
                            }
                        }
                        latch.countDown()
                        // P1-4：等不到的人已经走了，这条绑定没人会 close，自己收拾。
                        if (late != null) {
                            Log.w(TAG, "poll: late proxy($profile) after timeout; releasing to avoid leak")
                            closeProfileProxyQuietly(profile, late)
                        }
                    }

                    override fun onServiceDisconnected(p0: Int) {
                        // 系统侧回收：按**身份**让缓存失效（迟到回调不能误伤新代理）。
                        val mine = synchronized(gate) { proxy }
                        if (p0 == A2DP_SINK_PROFILE && mine != null) onA2dpProxyLost(mine)
                    }
                },
                profile,
            )
        }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "poll: getProfileProxy($profile) refused")
            return null
        }
        val got = runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
        if (!got) {
            // P1-4（复审二轮）：超时与回调是两个线程抢 gate 的窄窗口。
            // 必须**在 gate 内**判断「回调是否已经先一步放进 proxy」：
            // 已放进 ⇒ 取走并在锁外释放；没放 ⇒ 才置 abandoned 让回调自行释放。
            // 只设 abandoned 不查 proxy，会漏掉下面这条交错：
            //   轮询 await 超时 → 主线程先抢 gate 写入 proxy → 轮询再置 abandoned
            //   → 此后无人 close，代理与绑定双双泄漏。
            var late: BluetoothProfile? = null
            synchronized(gate) {
                val peek = proxy
                if (peek != null) {
                    late = peek
                    proxy = null
                } else {
                    abandoned = true
                }
            }
            late?.let {
                Log.w(TAG, "poll: proxy($profile) arrived at timeout edge; releasing to avoid leak")
                closeProfileProxyQuietly(profile, it)
            }
            Log.w(TAG, "poll: getProfileProxy($profile) timed out after 2s")
            return null
        }
        val p = synchronized(gate) { proxy }
        if (p != null) Log.i(TAG, "poll: proxy($profile) established (cached for session)")
        return p
    }

    /** 系统回收了 A2DP 代理：按身份清缓存，下一轮巡检重建。 */
    private fun onA2dpProxyLost(expected: BluetoothProfile) {
        Log.w(TAG, "poll: A2DP_SINK proxy lost by system; will rebuild next round")
        a2dpProxy.invalidate(expected)
    }

    private fun closeProfileProxyQuietly(profile: Int, p: BluetoothProfile) {
        runCatching {
            BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(profile, p)
        }.onFailure { Log.w(TAG, "closeProfileProxy failed: ${it.message}") }
    }

    /**
     * 反射断掉该设备的 A2DP-sink。**这是 DiPlay 的做法**：
     * 普通应用拿不到公开 API，只能试隐藏的 `BluetoothA2dp.disconnect`。
     *
     * 目前 `armed=false` 时不会被调用；保留以便实机确认后打开开关。
     */
    private fun disconnectA2dp(device: BluetoothDevice?) {
        if (device == null) return
        if (!hasConnectPermission()) {
            Log.w(TAG, "disconnect skipped: missing BLUETOOTH_CONNECT")
            return
        }
        // P0-1（2026-10-25 复审）：旧实现这里自己 `getProfileProxy` + `closeProfileProxy`，
        // 完全绕过缓存 —— 生产 armed=true / targetMac=null，只要 A2DP 仍连着，
        // 每轮巡检都会走 handleA2dpOccupied → disconnectA2dp，于是**每轮仍新增一对代理**，
        // 抖动只是变稀不是消失。现在复用同一 profile(11) 的缓存代理。
        val p = a2dpProxy.acquire()
        if (p == null) {
            Log.w(TAG, "disconnect: A2DP_SINK proxy unavailable (ROM may not expose it)")
            return
        }
        runCatching {
            val method = p.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            method.isAccessible = true
            val result = method.invoke(p, device) as? Boolean
            Log.i(TAG, "disconnect(${device.address}) -> $result (reused cached proxy)")
        }.onFailure {
            Log.w(
                TAG,
                "Factory Bluetooth audio handoff unavailable: " +
                    "${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    /** 把当前音频路由状态记进日志，便于与 A2DP 事件时间线对照。 */
    private fun logAudioRouteState(reason: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            Log.i(TAG, "audio route ($reason): AudioManager unavailable")
            return
        }
        Log.i(
            TAG,
            "audio route ($reason): mode=${am.mode} musicActive=${am.isMusicActive} " +
                "a2dpOn=${am.isBluetoothA2dpOn} scoOn=${am.isBluetoothScoOn}",
        )
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        // 不用 androidx.core 的 ContextCompat：platform-ecarx 模块只依赖 car:api 与
        // car:platform-android，没有 androidx.core，避免为一个权限检查新拉依赖。
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    companion object {
        /**
         * A2DP 主动巡检周期（2026-10-25：3000 -> 15000）。
         *
         * 现场日志（1.2.53 诊断包）：3 秒周期使会话期间 10.5 分钟内出现
         * `BluetoothA2dpSink` 代理 `connected`/`close mService = null`
         * 成对 **209 次**，持续搅动蓝牙栈，而 CarPlay 无线通道完全依赖它。
         *
         * 广播（A2DP 状态变化 / BECOMING_NOISY）仍是主路径，巡检只做兜底，
         * 因此降到 15 秒不影响「系统不执行 disableBluetooth」时的补刀能力。
         *
         * **测试锁定了下限**：见 `A2dpProxyRegistryTest.巡检周期不得退回秒级`。
         */
        internal const val A2DP_POLL_INTERVAL_MILLIS: Long = 15_000L

        /** 首次巡检延迟（会话开始即补刀）。 */
        internal const val POLL_INITIAL_DELAY_MILLIS: Long = 1_000L

        private const val TAG = "XCExt.bt.guard"

        /**
         * A2DP-sink profile 号 = 11。
         *
         * `BluetoothProfile.A2DP_SINK` 在 compileSdk 37 里不是公开常量（属隐藏 API），
         * 直接用会编译不过；DiPlay 吉利版同样是写字面量 11。
         */
        private const val A2DP_SINK_PROFILE = 11
        private const val DEBOUNCE_MILLIS = 500L
    }
}
