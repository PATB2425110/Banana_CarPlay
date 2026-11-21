package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbtool.carplay.api.hotspot.CarAdbHotspotBootInstaller
import com.dbtool.carplay.api.hotspot.CarAdbHotspotCommands
import com.dbtool.carplay.api.hotspot.CarAdbHotspotConfig
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarHotspotSubnet
import com.dbtool.carplay.api.hotspot.CarAdbHotspotHost
import com.dbtool.carplay.api.hotspot.CarAdbHotspotPhase
import com.dbtool.carplay.api.hotspot.CarAdbHotspotReadiness
import com.dbtool.carplay.api.hotspot.CarAdbHotspotState
import com.dbtool.carplay.api.hotspot.CarAdbHotspotStep
import com.dbtool.carplay.api.hotspot.CarAdbShellQuirks
import com.dbtool.carplay.api.hotspot.CarHotspotNetwork
import com.dbtool.carplay.api.hotspot.CarIwDevOutput
import com.dbtool.carplay.api.hotspot.CarKillallExitCode
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.bridge.SharedPreferencesCarSettingsStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「DBPlay 自建热点」的实现（ADB + 系统 hostapd + busybox udhcpd）。
 *
 * ## 它在整条链路里的位置
 *
 * **这是唯一路径，不是兜底。** 2026-10-05 起 `CarHotspotResolver.resolve()`
 * **完全不探测**车机原生热点，直接按默认配置走自建（斌哥口径：
 * "不探测，直接按默认配置使用 DBPlay 热点"）。所以配置阶段拿到的是
 * 常量化的默认网络，而不是探测结果。
 *
 * ⚠️ 历史注释曾写"这是兜底，首选是探测并复用车机原生热点"——**已作废**。
 * 旧探测链（`CarNativeHotspotProbe` / `LocalSoftApReader`）代码仍在，但只在
 * 自检 / 开发者页里被调用，**不在正常连接路径上**。
 * 别照旧注释把 `resolve()` 改回探测——那会让每次连接多等一次探测（实车 5~13 秒）。
 *
 * ## 参数从哪来
 *
 * 网卡 / 网段 / 网关 / DHCP 池 / 信道由 [CarAdbHotspotPlanner] 算出，
 * 经 `CarHotspotPlan.adbNetwork` 传进来（[ensureStarted] 的 `network`）；
 * `network == null` 时 planner 走**默认常量配置**。
 * **这里没有任何写死的网络参数**——`CarAdbHotspotDefaults` 里只剩候选列表。
 *
 * 只有 SSID / 密码是产品硬要求固定的（`DBPlay` / `12345678`），
 * 且取自设置页，不写死常量——hostapd 实际使用的凭据与通告给 iPhone 的凭据
 * 必须完全一致，否则 iPhone 会拿着 A 密码去连 B 密码的 AP。
 *
 * ## 为什么要记 steps
 *
 * 这套流程有 6 个车机相关的不确定点（网卡名、`su` 是否存在、`hostapd` 路径、
 * ABI、Android 版本、DHCP 工具），盲写代码不可能一次成。把每一步的原始输出
 * 留在状态里，上车跑一次「ADB 热点自检」就能定位到具体是哪一条挂的，
 * 而不是只看到一句"开热点失败"。
 *
 * ## 线程
 *
 * 与 `CarUpdateManager` 同一套约定：单线程 worker + 主线程投递回调。
 * [ensureStarted] / [selfCheck] 阻塞，[stop] 非阻塞（提交到 worker）。
 */
internal class CarAdbHotspotManager(
    private val appContext: Context,
    private val session: AdbShellSession,
    /**
     * "现在允许自动拉起热点吗"的判据来源（2026-10-04）。
     *
     * 默认恒 true，保持既有测试/空实现行为不变。生产侧接的是
     * `settings.keepHotspotAlive`——斌哥的口径是**永久开关**，
     * 没有"本次开机"这种中间态。
     */
    private val autoStartGate: () -> Boolean = { true },
) : CarAdbHotspotHost {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-adb-hotspot").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()

    private val running = AtomicBoolean(false)

    @Volatile
    private var snapshot = CarAdbHotspotState()

    /** 本次拉起实际采用的网络参数，[stop] 只回收这一组。 */
    @Volatile
    private var activeNetwork: CarHotspotNetwork? = null

    /** 本次拉起实际写进去的配置文件路径（hostapd 要从这里读）。 */
    @Volatile
    private var activeConfPath: String? = null

    /**
     * 自建热点停止与设备侧 5556 ADB 初始化彼此独立。
     * 关闭热点可以回收 hostapd/DHCP，但不得撤销全局 ADB owner 或释放共享通道。
     */

    // ------------------------------------------------------------ 对外

    override fun state(): CarAdbHotspotState = snapshot

    /**
     * 是否允许自动拉起热点。
     *
     * 斌哥口径（2026-10-04）：**"热点是永久开启，跟随车机自启动；
     * 关掉热点是永久关闭"**。所以判据就是持久化设置 `keepHotspotAlive`
     * 本身——**没有**"本次开机"这种中间态。
     *
     * 判据由构造方注入（[autoStartGate]），因为"要/不要"是用户的持久意愿、
     * 该跟设置一起落盘；manager 只负责执行，不自己持有这份状态。
     */
    override fun isAutoStartAllowed(): Boolean = autoStartGate()

    override fun ensureStarted(
        network: CarHotspotNetwork?,
        onState: (CarAdbHotspotState) -> Unit,
    ): Boolean {
        // ⚠️ 2026-10-05 修复「保存设置后重连卡在『无线通道就绪中』很久」的根因。
        //
        // 原判据是「running 与 snapshot.isRunning 两项同时为真才返回」，它**恒为假**：
        // `running` 只是「此刻有一次拉起在跑」的瞬时标志，`runBringUp` 返回后
        // `finally { running.set(false) }` 就把它清了。于是热点**明明在运行**、
        // `snapshot.isRunning == true`，第一项却是 false ⇒ 复用守卫永不命中 ⇒
        // 每次会话启动（首次连、保存设置后重连、回首页再连）都把整条链重跑一遍：
        // 探测原生热点 → 选网段 → 写配置 → 配地址 → 起 hostapd → 起 DHCP，
        // 实测每次白等 5~13 秒（DHCP 那段 `STARTING_DHCP` 一次就 8.5 秒）。
        //
        // 正确语义：**热点已经在跑就立刻复用，不要重跑**。判据只取 `snapshot.isRunning`。
        // 需要重跑的只有两种情况：从未拉起（snapshot 非 running）、或用户手动关过
        // （此时 snapshot 已被 tearDown 置为非 running）。两者都由 snapshot 表达。
        // ⚠️ 2026-10-05 二次修复「**退出整个 App 后再进来，连接很慢**」。
        //
        // 上一版把判据从「running 与 snapshot 双真」收缩到只取 `snapshot.isRunning`，
        // 修好了同进程内的复用；但 `snapshot` 是**内存字段**（见本类第 78 行），
        // **进程一被杀就清零**。用户"退出整个 DBPlay"正是杀掉进程，于是：
        // 热点其实还在跑（hostapd 活着、网卡地址还在），snapshot 却是全新默认值
        // ⇒ 判据为假 ⇒ 整条链重跑一遍，实车白等 **17.4 秒**
        // （13:49:05 probe → 13:49:22.5 RUNNING，`STARTING_DHCP` 重复 4 次）。
        //
        // 正确语义：**复用要看设备的实况，不看内存**。进程内、进程外都一样——
        // 只要"我们建的那个热点"此刻真的在跑（hostapd 进程在 + 网卡上有我们配的
        // 网关地址），就直接复用。
        //
        // 判据刻意宽松（两步都得真，但都从设备读）：
        //   ① `pidof hostapd` 有输出 —— 热点进程活着；
        //   ② 目标网卡（配置项/探测所得，或逐个候选）上出现我们的 host 地址 ——
        //      确认**是我们那一个**热点，而不是车机原生热点或别人的 AP。
        // 任一不满足才落回下面的重跑路径，行为与原来完全一致。
        // ⚠️ 这两行的**形态**（`if (…) return true`）被 `HotspotReuseContractTest` 钉住
        // （判据是"内存判据在前、设备实况垫后"），不要为了塞进别的语句改写成块。
        //
        // 【改动 C 2026-10-15】热点这个 owner 的登记不放这里，而是放在下面三处
        // "热点真的在跑"的出口：拉起成功处、零 ADB 复用命中处、跨进程复用命中处 ——
        // 没有登记点，`tearDown()` 里的 `close(OWNER_HOTSPOT)` 就成了空操作。
        // （内存判据 `snapshot.isRunning` 短路的那条出口不必再登记：能走到它，
        //   说明上面那三处之一已经在本次进程里登记过了。）
        if (snapshot.isRunning) return true
        if (reuseRunningHotspotIfPresent()) return true
        if (!running.compareAndSet(false, true)) {
            // 已经有一次拉起在进行中。等它，而不是并发再跑一遍——
            // 两条线程同时 ifconfig / hostapd 只会互相破坏。
            return waitForRunning()
        }
        return try {
            runBringUp(network, onState)
        } finally {
            running.set(false)
        }
    }

    override fun selfCheck(interfaceOverride: String, onState: (CarAdbHotspotState) -> Unit) {
        worker.execute {
            // 【改动 C 2026-10-15】自检里会真的跑 `ensure(force = true)`（开通 ADB、
            // 起 server、自连、提权探测）—— 这一段属于"长期使用共享通道"，按 owner
            // 记账登记 `OWNER_SELFCHECK`，跑完注销（它是**有界**动作，能成对收）。
            session.open(CarAdbHotspotDefaults.OWNER_SELFCHECK)
            try {
                runSelfCheck(interfaceOverride, onState)
            } finally {
                session.close(CarAdbHotspotDefaults.OWNER_SELFCHECK)
            }
        }
    }

    override fun stop() {
        // ⚠️ 2026-10-04：这里**不**置 `manuallyStoppedThisBoot`。
        //
        // `stop()` 有两条调用来源，语义完全不同：
        // 1. **会话收尾**（`CarPlaySessionBridge.stop()/close()` 在常开关掉时）——
        //    这是程序行为，不该让用户的"本次开机不再自动拉起"生效；
        // 2. **设置页「手动关闭热点」**——这才该置标记。
        //
        // 所以标记由上层 `stopPrewarmedHotspot()` 显式置，而不是在这里"顺手"置：
        // 这里置会把第 1 种情况也误判成用户意愿。
        worker.execute { tearDown() }
    }

    /**
     * 用户的**手动关闭**入口（设置页按钮专用，2026-10-04）。
     *
     * 只**停掉当前热点**；"永久不再自动起"由上层把
     * `settings.keepHotspotAlive` 落盘成 false 来表达——判据经
     * [autoStartGate] 回流，所以下次开机预热、下次会话 start 都会跳过。
     *
     * 刻意不在本类里存任何"关过"的状态：那份意愿属于设置、必须落盘，
     * 存内存里就成了"本次开机"的中间态，而斌哥要的是**永久**。
     */
    override fun stopByUser() {
        // 兼容清除旧版本遗留的热点开机自启标记，再回收当前自建 AP。
        // 不释放共享 ADB 通道：5556 初始化是全局能力，与自建热点生命周期无关。
        worker.execute {
            clearBootEnabledFlag()
            tearDown()
        }
    }

    /**
     * 把落盘的「开机自启」开关置 0（2026-10-06 新增）。
     *
     * 走 ADB + `setprop`：属性名是 `persist.*`，会落进 `/data/property`，
     * 跨重启存活。这也是热点开机脚本**第一步**读的那个值。
     *
     * ## 为什么要容忍失败
     *
     * 关热点这个动作**不以 ADB 可用为前提** —— 用户可能就是先断了 ADB 再点关闭。
     * 拿不到 root 时静默跳过：`tearDown()` 仍然会把当前热点停掉（这是用户
     * 立刻能看到的效果），只是「永久关」这一层没落盘。不该因此报错打断用户。
     */
    private fun clearBootEnabledFlag() {
        // 照同文件 `describeInterfaceOwner` 的既定写法：
        // `session.peek()` 只用来取**提权前缀**（`Access` 持有它）；命令本身走
        // `session.runner`。`peek()` 为空说明「这轮还没建过 ADB 通道」——
        // 没有通道就无从 setprop，直接返回。整个动作 runCatching 兜住：
        // 用户可能先断了 ADB 再点关闭，那种情况不该抛异常打断关闭流程。
        val prefix = session.peek()?.prefixOrEmpty.orEmpty()
        runCatching {
            session.runner.shell(
                "$prefix/system/bin/setprop " +
                    CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY + " " +
                    CarAdbHotspotDefaults.BOOT_ENABLED_OFF,
            )
        }
    }

    // ------------------------------------------------------------ 拉起

    private fun runBringUp(
        requested: CarHotspotNetwork?,
        onState: (CarAdbHotspotState) -> Unit,
    ): Boolean {
        val steps = mutableListOf<CarAdbHotspotStep>()
        var index = 0

        fun record(title: String, ok: Boolean, detail: String) {
            index += 1
            steps += CarAdbHotspotStep(index, title, ok, detail)
            publish(onState) { it.copy(steps = steps.toList()) }
        }

        fun fail(phase: CarAdbHotspotPhase, message: String): Boolean {
            publish(onState) {
                it.copy(phase = CarAdbHotspotPhase.FAILED, steps = steps.toList(), message = message)
            }
            return false
        }

        // ---- 1. adb 通道（与探测链路共用，含开通 ADB / 二进制 / server / 自连 / 提权）
        publish(onState) {
            it.copy(
                phase = CarAdbHotspotPhase.CHECKING_BINARIES,
                steps = emptyList(),
                // 上一轮的"网卡被谁占着"结论必须清掉：不清的话，这一轮还没跑到
                // 收尾校验，调用方就会读到一个**过期的**别人的 AP 名字。
                foreignApSsid = null,
                message = "正在准备 adb 通道…",
            )
        }
        val access = session.ensure()
        // 通道细节（是否拿到 shell、是否提权）逐条进 steps —— 那是会被展示出来的地方，
        // 不再另存一份结构化布尔值（曾经有 adbReady / rooted 两个字段，无人读取）。
        access.steps.forEach { step -> record(step.title, step.ok, step.detail) }
        // ⚠️ 2026-10-15（1.1.9 实车回归）：固定口 5556 不可用时**先自愈一次再判失败**。
        //
        // 诊断包 1792054941726（用户报"自建热点无法正常使用"）里，失败点就是这里：
        // 车机重启后 `瞬态=5555 持久=5556`，adbd 听厂商口，而 1.1.9 的 acquire()
        // 只固定连 5556 ⇒ `Connection refused` ⇒ 整条自建热点在起 hostapd **之前**
        // 就结束了。自愈的判据/边界全在 [AdbShellSession.repairDedicatedPort]：
        // 只在"设备当前口确实不是 5556"时执行，带冷却窗口、不扫描候选端口、不改用 5555。
        var usableAccess = access
        if (!usableAccess.shellOk) {
            val repair = session.repairDedicatedPort()
            record(
                "专用 ADB 端口 5556 自愈",
                repair.attempted && repair.init?.access?.shellOk == true,
                repair.reason,
            )
            repair.init?.let { usableAccess = it.access }
        }
        if (!usableAccess.shellOk) {
            return fail(CarAdbHotspotPhase.CHECKING_BINARIES, usableAccess.failure ?: "adb 通道不可用")
        }
        if (!usableAccess.rooted) {
            usableAccess = session.ensureRootForHotspot()
            record("自建热点按需提权", usableAccess.rooted, usableAccess.failure ?: "root shell 已就绪")
        }
        val prefix = usableAccess.prefix
            ?: return fail(
                CarAdbHotspotPhase.CONNECTING_ADB,
                "adb shell 既不是 root 也没有 su，无法配置网卡与启动 hostapd",
            )

        // ---- 2. 网络参数：网卡 / 网段 / 网关 / DHCP / 信道
        publish(onState) {
            it.copy(phase = CarAdbHotspotPhase.SELECTING_NETWORK, message = "正在按车机环境选择网卡与网段…")
        }
        val network = requested
            ?: CarAdbHotspotPlanner.plan(appContext, info = null, interfaceOverride = configuredInterface())
        activeNetwork = network
        // 落盘足迹：进程被杀后，"网上那个地址是不是我们留的"只有一个答案来源。
        // 各热点模式必须互不污染（PATCH-0068）—— 见 `CarHotspotFootprintStore`。
        CarHotspotFootprintStore.save(
            appContext,
            CarHotspotFootprintStore.Footprint(
                interfaceName = network.interfaceName,
                address = network.hostAddress,
                prefixLength = network.prefixLength,
            ),
        )
        record(
            "网络参数选择（网卡 / 网段 / 信道）",
            true,
            buildString {
                append("网卡 ").append(network.interfaceName)
                append("；网段 ").append(network.subnet)
                append("；网关 ").append(network.hostAddress)
                append("；DHCP ").append(network.dhcpStart).append("-").append(network.dhcpEnd)
                append("；信道 ").append(network.channel)
                append("\n选择理由：").append(network.rationale)
            },
        )
        publish(onState) {
            it.copy(
                interfaceName = network.interfaceName,
                subnet = network.subnet,
                hostAddress = network.hostAddress,
                channel = network.channel,
            )
        }

        // ---- 3. hostapd 路径（候选里逐个探测，不假定在 /vendor/bin/hw/）
        //
        // ⚠️ 探测不到**不等于**没有（见 detectHostapdPath 的 KDoc），所以这里
        // **绝不因此放弃整条链**：退回参考实现的位置，让真正的启动去报错。
        // 参考实现根本不探测 —— 它直接 `su 0 /vendor/bin/hw/hostapd <conf>`。
        // 我们把一个自己发明的探测放在关键路径上并让它致命，就是"自造判据比参考更严"
        // 那一类错误（本项目已因此栽过两次：逐条判退出码、只读一次 devices）。
        val probedHostapd = detectHostapdPath(prefix)
        val hostapdPath = probedHostapd
            ?: CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES.first()
        record(
            "定位 hostapd",
            true,
            if (probedHostapd != null) {
                "使用 $probedHostapd"
            } else {
                "候选路径一个都没探到，退回参考实现的位置 $hostapdPath。" +
                    "**真正的判据是它能不能启动**，所以这一步不阻断。\n" +
                    "探过的候选：${CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES.joinToString()}"
            },
        )

        // ---- 4~7. 逐个候选信道尝试：配地址 → 起 hostapd → 起 udhcpd → 校验
        //
        // 为什么是**换信道**而不是"同一信道重试三遍"：1/6/11 互不重叠，
        // 某个信道被监管域/驱动拒绝时换一个才有意义；同一个信道连试三次，
        // 第三次的结果几乎一定与前两次相同。
        // 参考实现只试一次（它把信道写死）——"写死"恰恰是它换一台车就废的原因之一。
        val candidates = channelAttempts(network.channel)
        var failure: String? = null
        // 网卡上别人的 AP 的名字（读到了才有）。换信道解决不了"网卡被别人占着"，
        // 但保留最后读到的那个值，好让调用方知道**为什么**抢不到网卡。
        var foreignApSsid: String? = null
        for ((round, channel) in candidates.withIndex()) {
            val attempt = network.copy(channel = channel)
            val tag = if (candidates.size > 1) "信道 $channel：" else ""

            publish(onState) {
                it.copy(
                    phase = CarAdbHotspotPhase.CONFIGURING,
                    message = if (candidates.size > 1) {
                        "正在配置信道 $channel（第 ${round + 1}/${candidates.size} 次尝试）…"
                    } else {
                        "正在写入热点配置…"
                    },
                )
            }
            val configure = configure(attempt, prefix)
            configure.forEach { step ->
                record(tag + step.title, step.result.ok || step.result.timedOut, step.result.summary)
            }
            // ⚠️ 这里**故意不判** `configure` 里那些非阻断步骤的成败：参考实现只把
            // 最后一句 `ifconfig` 的输出当判据，前面 7 条 `su 0` 命令的退出码一律不看
            // （见 [ConfigStep] 的说明）。逐条判 ⇒ 一句无害报错就掐断整条链
            // ⇒ 回落这台车上必然超时的 LocalOnlyHotspot。真正的判据在下面收尾处。
            // 唯一要确认的是"配置文件确实落到了设备上"，否则 hostapd 没东西可读。
            val confPath = activeConfPath
            if (confPath == null) {
                failure = "配置文件没有落到设备上（信道 $channel）"
                continue
            }

            publish(onState) {
                it.copy(phase = CarAdbHotspotPhase.STARTING_HOSTAPD, message = "正在启动 hostapd…")
            }
            val hostapd = startDaemonWithRetry {
                session.runner.shellDetached("$prefix$hostapdPath $confPath")
            }
            val hostapdOk = hostapd.ok || hostapd.timedOut
            record(tag + "启动 hostapd（$hostapdPath）", hostapdOk, hostapd.summary)
            if (!hostapdOk) {
                failure = "hostapd 启动失败（信道 $channel）：${hostapd.summary}"
                // 与"没完全就绪"那条路径一样，换信道之前先回收本轮的本地 adb 客户端：
                // 不回收的话它的 shell 可能还挂在网卡上，让下一轮"注定失败"。
                // （`stopDaemons()` 只 destroy 本地子进程，不会动 adb server。）
                session.runner.stopDaemons()
                continue
            }

            publish(onState) {
                it.copy(phase = CarAdbHotspotPhase.STARTING_DHCP, message = "正在启动 DHCP…")
            }
            val udhcpd = startDaemonWithRetry {
                // 用的是**部署后**的路径，不是随包路径：见 configure() 里那一步的注释。
                session.runner.shellDetached(
                    "$prefix${CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH} udhcpd -f " +
                        File(appContext.filesDir, CarAdbHotspotDefaults.LOCAL_UDHCPD_CONF)
                            .absolutePath,
                )
            }
            val udhcpdOk = udhcpd.ok || udhcpd.timedOut
            record(tag + "启动 DHCP（busybox udhcpd）", udhcpdOk, udhcpd.summary)

            // ---- 收尾校验：网卡是否真的拿到了我们配的地址
            val verify = session.runner.shell("/system/bin/ifconfig ${attempt.interfaceName}")
            val addressUp = verify.ok && verify.output.contains(attempt.hostAddress)
            record(tag + "校验网卡地址（应为 ${attempt.hostAddress}）", addressUp, verify.summary)

            // ---- 收尾校验 ②：网卡上**实际在广播**的是不是我们配的那个 SSID
            //
            // ⚠️ 2026-09-30 修正：这一步**从"只记录"升级成了判据**。
            //
            // 升级前的注释写的是"升级会让这一支失败 ⇒ 回落 LOH ⇒ 本车 LOH 一次即
            // 永久中毒，所以刻意只记录"。实车证明那个取舍是错的：网卡被别人的 AP
            // 占着时，判据照样报"热点已就绪"，我们把设置页的 SSID 发给 iPhone，
            // 而它**没有被广播** ⇒ iPhone 永远关联不上 ⇒ 界面停在
            // 「无线通道就绪」，**整份诊断包里没有一句报错**（现象与日志完全脱节，
            // 是这次实车最难查的一处）。
            //
            // 现在：广播的不是我们的名字 ⇒ 这一轮不算就绪。而失败的下游也**不再**
            // 是 LOH —— `foreignApSsid` 会把这个名字带到 `CarPlaySessionBridge`，
            // 那里**跳过** LOH 回落并给出可执行的中文提示（网卡被占着时 LOH 用同一张
            // 网卡，一定也起不来；白等 60 秒还落下一个不可用的状态）。
            //
            // ⚠️ 第四项是**三态**的：`broadcasting == null`（厂商 ROM 不打印 `ssid`
            // 行、或信标还没起）**不算**"有别人的 AP"。凭"读不到"否决就是本工程
            // 反复栽跟头的"自造判据比参考更严"，所以这里折算成放行。
            val expectedSsid = readHotspotSettings().ssid
            val iwNow = session.runner.shell("iw dev")
            val broadcasting = CarIwDevOutput.apSsid(iwNow.output, attempt.interfaceName)
            val foreign = broadcasting?.takeIf { it != expectedSsid }
            if (foreign != null) {
                foreignApSsid = foreign
                // 单独记一条"是谁占着网卡"：这条信息决定现场能不能自己排掉
                // （2026-09-30 实车就是别人的 `car` 占着 wlan0，而当时的日志里
                // 一个字都没提，只能靠事后逐行翻 `iw dev` 才发现）。
                record(
                    "网卡占用者（${attempt.interfaceName} 上广播的是谁）",
                    false,
                    describeInterfaceOwner(attempt.interfaceName, foreign),
                )
            }
            record(
                tag + "校验实际广播的 SSID（应为 $expectedSsid）",
                foreign == null,
                when {
                    broadcasting == expectedSsid -> "实际广播「$broadcasting」"
                    broadcasting == null ->
                        "⚠️ 读不到实际广播的 SSID（网卡可能还没起信标，也可能是厂商的 iw " +
                            "输出不同）——按放行处理，不因此判失败"
                    else ->
                        "⚠️ 实际广播的是「$broadcasting」而不是「$expectedSsid」——" +
                            "网卡被别人的 AP 占着；而发给 iPhone 的是「$expectedSsid」，" +
                            "那个名字**没有被广播**，iPhone 必然关联不上"
                },
            )

            // 判据集中在 [CarAdbHotspotReadiness]（纯函数、可单测）。四项缺一不可：
            // `hostapd` 就是那张 AP 本身，少了它就会出现"hostapd 已退出、但地址还在、
            // udhcpd 还活着" ⇒ 判成就绪 ⇒ 会话继续 ⇒ **iPhone 根本搜不到 SSID**；
            // 而"广播的不是我们配的 SSID"则会变成"iPhone 连一个不存在的网络"。
            // 这两种都是最难查的现象：日志里只看到一句"热点已就绪"。
            val noForeignAp = foreign == null
            if (CarAdbHotspotReadiness.isReady(hostapdOk, udhcpdOk, addressUp, noForeignAp)) {
                activeNetwork = attempt
                // 【改动 C 2026-10-15】热点真的起来了 ⇒ 登记热点这个 owner
                // （与 `tearDown()` 末尾的 `close(OWNER_HOTSPOT)` 成对）。
                session.open(CarAdbHotspotDefaults.OWNER_HOTSPOT)
                publish(onState) {
                    it.copy(
                        phase = CarAdbHotspotPhase.RUNNING,
                        steps = steps.toList(),
                        channel = channel,
                        foreignApSsid = null,
                        message = "热点已就绪：${attempt.hostAddress}" +
                            "（信道 $channel，SSID 见设置页）",
                    )
                }
                return true
            }
            failure = "信道 $channel 上热点没完全就绪（" +
                CarAdbHotspotReadiness.explain(hostapdOk, udhcpdOk, addressUp, noForeignAp) + "）"
            // 换信道之前先回收本轮的本地 adb 客户端。远端进程由下一轮
            // `configure()` 里的 `killall` 负责——不收回的话旧 hostapd 可能仍占着
            // 网卡，让下一轮"注定失败"。
            session.runner.stopDaemons()
        }

        publish(onState) {
            it.copy(
                phase = CarAdbHotspotPhase.FAILED,
                steps = steps.toList(),
                // 网卡被别人的 AP 占着时，把这个名字交出去——调用方据此**跳过**
                // LOH 回落并给出可执行提示（见 CarAdbHotspotState.foreignApSsid）。
                foreignApSsid = foreignApSsid,
                message = "热点没有起来：${failure ?: "所有候选信道都失败"}",
            )
        }
        return false
    }

    /**
     * 「这张网卡现在被谁占着」的一句话描述，进日志与自检页。
     *
     * 为什么要单独写一条：2026-09-30 实车整份诊断包里**没有任何一处**说明
     * "wlan0 上广播的是别人的 `car`"——只能靠事后逐行翻 `iw dev` 才看出来。
     *
     * ⚠️ 这里**只报事实**（谁在跑、读的哪份配置），**不判定它是谁**：
     * "是车机自己的 AP" 还是 "第三方 App 留下的残留"，日志本身证明不了
     * （`ps` 只能给出父进程与 argv，看不出意图）。把猜测写成结论，下一个看日志的人
     * 就会拿它当前提——所以推测一律带上"可能"。
     *
     * 命令刻意只用只读的 `ps` + `/proc/<pid>/cmdline`（都是已提权的 shell），
     * 不杀进程、不改配置——这一步是**报告**，不是处置。
     */
    private fun describeInterfaceOwner(interfaceName: String, foreignSsid: String): String {
        val prefix = session.peek()?.prefixOrEmpty.orEmpty()
        // ⚠️ 必须走 `CarAdbHotspotCommands.hostapdOwners` 而不是自己拼 `for` 循环：
        // `su 0 for p in …` 会得到 `for: not found`，然后把"读不到"误读成
        // "没有 hostapd 在跑"——正好把这一步要查的东西查漏。
        val owners = session.runner.shell(CarAdbHotspotCommands.hostapdOwners(prefix))
        return buildString {
            append("⚠️ ").append(interfaceName).append(" 上广播的是「").append(foreignSsid)
            append("」，不是我们配的 SSID —— 这张网卡现在不属于我们。\n")
            if (owners.output.isBlank()) {
                append("（读不到正在运行的 hostapd 进程；见「相关进程」那一步的原始输出）")
            } else {
                append("正在运行的 hostapd（pid + 启动参数）：\n")
                owners.output.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { append("  ").append(it).append('\n') }
                append("怎么读：hostapd 的启动参数里那个配置文件**就是它的凭据来源**。")
                append("把这里的路径与「运行中 hostapd 实际读的配置」那一步的内容对一下，")
                append("就知道「").append(foreignSsid).append("」是谁写进网卡的。")
                append("（⚠️ 这一步只给事实，不判定它是车机自己的还是第三方留下的——")
                append("ps 看不出意图。）")
            }
            append("前缀=").append(if (prefix.isEmpty()) "无（已是 root）" else prefix.trim())
        }
    }

    /**
     * 这次拉起要依次尝试的信道：**首选信道排第一，其余按候选表补齐**。
     *
     * 首选来自 [CarHotspotNetwork.channel]（规划器选的，已经避开过车机自己
     * 正在用的那个），所以它必须排在最前——把它挤到后面等于白白多失败一次。
     *
     * 候选表是 5 GHz 的 `36/40/44/48`（2026-10-07 起只走 5 GHz，
     * 见 `CarAdbHotspotDefaults.CHANNEL_CANDIDATES`），所以最坏情况是 4 次尝试。
     */
    private fun channelAttempts(planned: Int): List<Int> {
        val table = CarAdbHotspotDefaults.CHANNEL_CANDIDATES.mapNotNull { it.toIntOrNull() }
        return (listOf(planned) + table).distinct()
    }

    /**
     * 把 `killall` 的「没有可杀的目标」折算成成功（2026-10-06）。
     *
     * 判据本身在 [CarKillallExitCode.isNoTarget]（纯函数、有单测兜着）
     * ——这里只负责把它接回 `CommandResult`。理由与判据细节见那个 object 的 KDoc。
     */
    private fun relaxKillallNoTarget(
        result: AdbShellRunner.CommandResult,
    ): AdbShellRunner.CommandResult {
        if (result.ok || result.timedOut) return result
        if (!CarKillallExitCode.isNoTarget(result.exitCode, result.output)) return result
        // 退出码折算成 0，输出**原样保留**（"谁被杀了/没杀到"是要进诊断的证据）。
        return AdbShellRunner.CommandResult(exitCode = 0, output = result.output)
    }

    /**
     * 起一个**长驻**守护进程（`hostapd` / `udhcpd`），失败时重试。
     *
     * 参考实现在两条独立线程里各重试 **5 次 × 500ms**，这里取 3 次 —— 因为
     * [AdbShellRunner.shellDetached] 判定"起来了没有"之前已经**观察了 1.5 秒**
     * 的早期输出（hostapd 报 `interface not found`、udhcpd 报配置解析失败都在这段
     * 窗口里），相当于参考实现那 5 次里的前 3 次。再叠加 3 次重试已经比它更宽。
     *
     * ⚠️ 重试**不是**为了掩盖失败：每次重试之间只有 500ms，重试全挂时
     * 返回的是**最后一次的原始输出**，那句话才是要进日志的东西。
     */
    private fun startDaemonWithRetry(
        launch: () -> AdbShellRunner.CommandResult,
    ): AdbShellRunner.CommandResult {
        var last: AdbShellRunner.CommandResult? = null
        repeat(DAEMON_ATTEMPTS) { round ->
            val result = launch()
            if (result.ok || result.timedOut) return result
            last = result
            if (round < DAEMON_ATTEMPTS - 1) Thread.sleep(DAEMON_RETRY_BACKOFF_MILLIS)
        }
        return last ?: AdbShellRunner.CommandResult(1, "守护进程一次都没能启动")
    }

    // ------------------------------------------------------------ 设备探测

    /**
     * 找 `hostapd`。
     *
     * 参考实现用的是 `/vendor/bin/hw/hostapd`，但不同 ROM 放在不同地方
     * （AOSP 在 `/apex/com.android.wifi/bin/hostapd`），所以逐个 `test -x`，
     * 一次 shell 往返问完所有候选。
     *
     * ## ⚠️ 为什么必须**带提权前缀**（旧版这里没带，是个真实隐患）
     *
     * `/vendor/bin/hw/` 下的二进制在部分 ROM 上是 `0700 root:root`。那时以
     * `shell` uid 跑 `test -x` **看不到它**，于是我们把"有 hostapd"误判成"没有"。
     *
     * 而参考实现**根本不探测**：它直接 `su 0 /vendor/bin/hw/hostapd <conf>`，
     * 以 root 的身份执行。所以探测也必须以 root 的身份问，否则这就是一个
     * **自己发明的、比参考实现更严的判据** —— 本项目已经因此栽过两次
     * （逐条判退出码、`devices` 只读一次）。
     *
     * ## ⚠️ 不能写成 `su 0 for f in ...`
     *
     * `su 0 <cmd>` 是把后面的参数当**一个命令**执行，`for` 不是命令名 ——
     * 会报 `for: not found`，然后我们又会把"探测不到"误读成"没有 hostapd"。
     * 必须用 `sh -c '<脚本>'` 把复合语句交给**内层** shell（单引号由设备侧
     * shell 剥掉，脚本整体作为 `sh -c` 的一个参数）。
     *
     * @param prefix 提权前缀（`""` 或 `"su 0 "`）。
     * @return 可执行路径；null = 一个都没探到（**调用方不应因此放弃**，
     *         见 `runBringUp` 里那一步的注释）。
     */
    private fun detectHostapdPath(prefix: String): String? {
        val probe = session.runner.shell(
            CarAdbHotspotCommands.hostapdProbe(prefix, CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES),
        )
        return probe.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("OK ") }
            ?.removePrefix("OK ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ 跨进程复用

    /**
     * 「进程被杀之后重进，热点其实还在跑」的复用判定（2026-10-05）。
     *
     * ## 为什么需要它
     *
     * [ensureStarted] 的第一道判据是 `snapshot.isRunning`，而 `snapshot` 是内存字段。
     * 用户"退出整个 App"会杀掉进程，snapshot 随之清零；此时热点（hostapd 进程 +
     * 网卡地址）在设备上**依然活着**，但判据为假 ⇒ 整条链重跑 ⇒ 实车白等 17.4 秒。
     *
     * ## 判据（都从设备读，不看内存）
     *
     * 1. `pidof hostapd` 有输出；
     * 2. 目标网卡上有我们配的 host 地址（`ifconfig <if>` 输出里含该地址）。
     *
     * 第 2 条是关键：只有它能把**我们建的热点**与车机原生热点 / 别人的 AP 区分开
     * （车机原生的那个落在 `192.168.175.1`，我们建的在 `192.168.176.1`）。
     *
     * ## 网卡从哪来
     *
     * 按优先级：内存里上次用的 `activeNetwork.interfaceName` → 设置项
     * `configuredInterface()` → 默认 `wlan0`。这些都是**读操作**，失败一律
     * 视为"没复用到"，落回正常重跑，不阻断、不报错。
     *
     * @return true = 热点确实在跑、可复用（已把 snapshot 补成 RUNNING）。
     */
    /**
     * **零 ADB 复用判据**：只读本进程可见的网卡，判断"我们那张热点"是否在跑。
     *
     * 判据与 [reuseRunningHotspotIfPresent] 的 shell 版同源（网卡 + 我们的网关地址），
     * 但**完全不碰 ADB/root**：`NetworkInterface.getNetworkInterfaces()` 是公开 API，
     * 普通应用权限即可读到自己所在命名空间的地址（实车验证：能读到 `wlan0`）。
     *
     * 命中后把内存状态补成 RUNNING 并返回 true；读不到返回 false，调用方落回
     * 原来的 shell 版判据（行为不变，只是多花那 10 秒——只在真的没热点时才付）。
     *
     * ⚠️ 不用 `pidof hostapd` 校验进程：那要 root。网卡上挂着我方网关地址已经是
     *    足够强的证据——车机原生热点、别人的 AP 都不会用 `CarHotspotSubnet` 选的地址。
     */
    private fun reuseRunningHotspotByNic(): Boolean = runCatching {
        val candidates = linkedSetOf<String>()
        activeNetwork?.interfaceName?.takeIf { it.isNotBlank() }?.let { candidates += it }
        runCatching { configuredInterface() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { candidates += it }
        candidates += "wlan0"
        candidates += "wlan1"
        candidates += "wlan2"

        val nics = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in candidates) {
            val nic = nics.firstOrNull { runCatching { it.name }.getOrNull() == iface }
                ?: continue
            if (!runCatching { nic.isUp }.getOrDefault(false)) continue
            if (runCatching { nic.isLoopback }.getOrDefault(false)) continue
            val addrs = runCatching {
                nic.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { it.hostAddress.orEmpty() }
            }.getOrDefault(emptyList())
            if (addrs.isEmpty()) continue

            // ⚠️ 2026-10-13（PATCH-0107 修 A）：判据**不再现算 planner 网关**。
            //
            // 旧写法 `CarAdbHotspotPlanner.plan(...).hostAddress` 拿"这一刻重算出来的
            // 网关"去网卡上找，而 planner 是**冲突规避**选网段的：上一轮自建已把
            // `wlan0` 配上 `192.168.175.1`，下一轮 planner 就把 175 当"冲突"改选 176
            // ⇒ 判据要找的是 `.176.1`，网上躺着的却是 `.175.1`（我们自己的）
            // ⇒ **恒不命中** ⇒ 每次重连都把整条 21 步拉起链重跑一遍。
            // 实车日志：`adb hotspot ready … host=192.168.175.1` 而 `hotspot plan …
            // 192.168.176.0/24 / 网关 192.168.176.1`，15/15 次 plan 全是 176。
            //
            // 这正是 `CarHotspotModeInit`（2026-10-11）已经踩过并修掉的同一个坑，
            // 其类注释写得很清楚：「planner 是冲突规避的，每轮会换网段 —— 拿现算值
            // 去比对等于永远找不到真地址」。复发形态完全一致，只是发生在另一处。
            //
            // 正确判据（与 `CarHotspotModeInit.awaitResidueCleared` 同口径）：
            // **网卡上挂着我方候选池里的地址**即证明"我们那张热点在跑"，不需要知道
            // 具体是哪一个。安全性由 `SUBNET_CANDIDATES` 保证——车主口径 2026-10-11
            // 已确认车机固定 `192.168.15.0/24`，**与我们的池不相交**
            // （见 `CarHotspotModeInit` 类注释第 269 行）。
            val ours = addrs.filter { addr ->
                CarAdbHotspotDefaults.SUBNET_CANDIDATES.any { candidate ->
                    CarHotspotSubnet.sameSlash24(addr, candidate)
                }
            }
            val hostAddress = ours.firstOrNull() ?: continue
            if (hostAddress.isBlank()) continue

            publish(null) {
                it.copy(
                    phase = CarAdbHotspotPhase.RUNNING,
                    interfaceName = iface,
                    hostAddress = hostAddress,
                    message = "热点已在运行（按网卡实况复用，未用 ADB）",
                )
            }
            android.util.Log.i(
                TAG,
                "复用已运行热点（零 ADB）：iface=$iface host=$hostAddress，" +
                    "跳过整条拉起链与 ADB 探测（原会白等 10~20 秒）",
            )
            // 【改动 C 2026-10-15】复用命中 = 热点在跑 ⇒ 登记热点这个 owner
            // （与 `tearDown()` 末尾的 `close(OWNER_HOTSPOT)` 成对；`open` 幂等）。
            session.open(CarAdbHotspotDefaults.OWNER_HOTSPOT)
            return@runCatching true
        }
        false
    }.getOrDefault(false)

    private fun reuseRunningHotspotIfPresent(): Boolean {
        // ⚠️ 2026-10-05（斌哥定论）：**先做零 ADB 判据**，命中就直接复用。
        //
        // 斌哥口径：「ADB 仅用于开启 DBPlay 永久热点，开启后热点随车机开机自动启动，
        // 使用固定网段，后续无需每次都执行 ADB。」
        //
        // 下面那套「pidof hostapd + ifconfig」本来是对的判断，但它全走 `runner.shell`
        // ⇒ 必须先 `session.ensure()` 建 ADB 通道 ⇒ 实车单条 `adb connect 127.0.0.1`
        // 卡 **9983ms**（SELinux 挡 adb 读 USB，`device poll` 线程卡在重试上）。
        // 于是「复用」这个本该毫秒级完成的检查，反而成了每次连接最贵的一步。
        //
        // 零 ADB 判据：**我们从设备读网卡**——本进程 `NetworkInterface` 就能读到
        // `wlan0` 上挂着我们配的网关地址（planner 算出来的那个，实车 192.168.175.1）。
        // 网关地址是我们自己配的、车机原生热点不会用，读到即"我们那张热点在跑"。
        // 毫秒级、零 root、零 ADB。
        if (reuseRunningHotspotByNic()) return true

        val prefix = session.peek()?.prefixOrEmpty.orEmpty()
        // prefixOrEmpty 已经是 `""` 或 `"su 0 "`，直接用即可（与 hostapdOwners 同款）。
        // 复用也得先有 shell；拿不到就老实重跑（重跑途中会自己建通道）。
        val runner = runCatching { session.runner }.getOrNull() ?: return false

        // 1) hostapd 进程活着吗？
        val pidOf = runCatching {
            runner.shell("$prefix/system/bin/pidof hostapd")
        }.getOrNull() ?: return false
        if (!pidOf.ok || pidOf.output.isBlank()) return false

        // 2) 目标网卡（候选集合，逐个验）上有没有我们的地址？
        val candidates = linkedSetOf<String>()
        activeNetwork?.interfaceName?.let { candidates += it }
        runCatching { configuredInterface() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { candidates += it }
        candidates += "wlan0"

        for (iface in candidates) {
            val verify = runCatching {
                runner.shell("/system/bin/ifconfig $iface")
            }.getOrNull() ?: continue
            if (!verify.ok) continue
            // ⚠️ 2026-10-13（PATCH-0107 修 A）：同样**不再现算 planner 网关**。
            // 旧写法拿 `CarAdbHotspotPlanner.plan(...).hostAddress` 去 `ifconfig` 输出里
            // 找，而 planner 冲突规避会每轮换网段（上一轮占 175 → 这一轮算 176）
            // ⇒ 找的是 `.176.1`、网卡上是 `.175.1` ⇒ 恒不命中。
            // 详见 `reuseRunningHotspotByNic()` 里的同一段注释。
            val ours = CarAdbHotspotDefaults.SUBNET_CANDIDATES
                .filter { candidate -> verify.output.contains(candidate.substringBeforeLast('.')) }
                .flatMap { candidate ->
                    verify.output.lineSequence()
                        .map { it.trim().substringAfter("inet addr:", "").substringBefore(' ') }
                        .filter { addr ->
                            addr.isNotBlank() && CarHotspotSubnet.sameSlash24(addr, candidate)
                        }
                        .toList()
                }
            val hostAddress = ours.firstOrNull() ?: continue

            // 命中：热点在跑，把内存状态补成 RUNNING —— 让 UI 与后续 `ensureStarted`
            // 的第一道判据（snapshot.isRunning）看到一致结果，不必再次走这里。
            // 沿用 publish(null, ...)：只更新状态，不额外回调调用方（本方法是同步判据，
            // 由 ensureStarted 的调用方自己去读状态）。
            publish(null) {
                it.copy(
                    phase = CarAdbHotspotPhase.RUNNING,
                    interfaceName = iface,
                    hostAddress = hostAddress,
                    message = "热点已在运行（进程重启后按设备实况复用）",
                )
            }
            android.util.Log.i(
                TAG,
                "复用已运行热点（跨进程）：iface=$iface host=$hostAddress，" +
                    "跳过整条拉起链（原会白等十余秒）",
            )
            // 【改动 C 2026-10-15】同上：跨进程复用命中也要登记 owner。
            session.open(CarAdbHotspotDefaults.OWNER_HOTSPOT)
            return true
        }
        return false
    }

    // ------------------------------------------------------------ 配置

    /**
     * 配置阶段的一步。
     *
     * [blocking] = `false` 表示**这一步失败只记日志、不阻断整条链**——这是对齐
     * 参考实现的语义：它那 7 条 `su 0` 命令的退出码**一律不看**
     * （`Process.waitFor()` 的返回值被直接丢弃，见 `ADB-HOTSPOT.md` §2.4），
     * 真正算数的只有最后一句 `/system/bin/ifconfig <网卡>` 的输出里有没有 `UP`。
     */
    private data class ConfigStep(
        val title: String,
        val result: AdbShellRunner.CommandResult,
        val blocking: Boolean = true,
    )

    /**
     * 参考实现的 7 条命令，顺序即字节码顺序（见 `ADB-HOTSPOT.md` §2.6），
     * 但**所有涉及网络参数的地方都改成了运行时选择的值**。
     *
     * ## 哪些步骤是阻断的
     *
     * 只有"配置文件真的落到设备上了"这一件事是阻断的：hostapd 要从那个路径
     * 读配置，没有它后面根本无从谈起。其余步骤（配地址、`killall`、`ip rule`、
     * `setprop`、部署 busybox）**全部不阻断**——它们各自失败都会在下游暴露出来：
     * 地址没配上 ⇒ 收尾的 `ifconfig` 校验不过；busybox 没部署 ⇒ `udhcpd` 起不来。
     *
     * 这正是参考实现的判据结构：**不逐条判、只认最终结果**。逐条判会让一句
     * 无害报错（最典型的是 `ip rule add` 的 `RTNETLINK answers: File exists`）
     * 把整条 ADB 热点链掐断，回落到这台车上必然超时的 LocalOnlyHotspot。
     *
     * @return 按执行顺序排列的步骤。
     */
    private fun configure(
        network: CarHotspotNetwork,
        prefix: String,
    ): List<ConfigStep> {        val localSoftap = File(appContext.filesDir, CarAdbHotspotDefaults.LOCAL_SOFTAP_CONF)
        val localUdhcpd = File(appContext.filesDir, CarAdbHotspotDefaults.LOCAL_UDHCPD_CONF)
        val result = mutableListOf<ConfigStep>()
        // 先清掉上一轮的值：`writeLocalConfig` 失败会**提前 return**，那时下面
        // 那段拷贝循环根本不会跑。不在这里清，[activeConfPath] 会留着上一轮的
        // 路径 ⇒ 调用方误以为"配置已就绪"，拿着一个已经过期的路径去起 hostapd。
        activeConfPath = null

        val writeError = writeLocalConfig(network)
        result += ConfigStep(
            "写入本地配置",
            AdbShellRunner.CommandResult(
                exitCode = if (writeError == null) 0 else 1,
                output = writeError ?: "已生成 ${localSoftap.absolutePath} 与 ${localUdhcpd.absolutePath}",
            ),
        )
        if (writeError != null) return result

        // 配置文件落点也是候选：/data 根目录在部分 ROM 上只有 root 可写，
        // 所以逐个真写一次（`cp` 成功才算数），而不是先猜再写。
        val attempts = StringBuilder()
        var confPath: String? = null
        for (target in CarAdbHotspotDefaults.SOFTAP_CONF_CANDIDATES) {
            val copy = session.runner.shell("$prefix/system/bin/cp ${localSoftap.absolutePath} $target")
            val chmod = if (copy.ok) {
                session.runner.shell(
                    "$prefix/system/bin/chmod ${CarAdbHotspotDefaults.REMOTE_CONF_MODE} $target",
                )
            } else {
                copy
            }
            attempts.append(target).append(" → ").append(copy.summary)
            if (copy.ok && chmod.ok) {
                confPath = target
                attempts.append("（选用）")
                break
            }
            attempts.append('\n')
        }
        activeConfPath = confPath
        result += ConfigStep(
            "拷贝配置到设备",
            AdbShellRunner.CommandResult(
                exitCode = if (confPath == null) 1 else 0,
                output = attempts.toString(),
            ),
        )
        if (confPath == null) return result

        // 以下全部 `blocking = false`：失败只留证据，判据交给收尾的网卡校验。
        result += ConfigStep(
            "给 ${network.interfaceName} 配地址 ${network.hostAddress}",
            assignAddress(network, prefix),
            blocking = false,
        )

        // ⚠️ 这一步会杀掉**所有** `hostapd`，包括车机自己那个 —— 这是"接管网卡"的
        // 固有代价（同一张 `wlan0` 上不可能同时有两张 AP），不是副作用。
        // 标题刻意改成"清理网卡占用者"而不是"清理上一轮残留"：后者会让人以为
        // 只动我们自己的东西，而实车上被它杀掉的那个正是**别人**的 AP
        // （2026-09-30：`wlan0` 上广播 `car` 的那个 hostapd）。
        // 现场要据此判断"我这一步到底动了什么"，所以不能含糊。
        //
        // ⚠️ 2026-10-06 判据放宽：`killall` 在**没有可杀的目标**时退出码就是 1
        // （`killall: hostapd: No such process`）。那种情况恰恰是**最正常的**
        // ——没有残留的 AP 占着网卡，本来就不需要清理。原样记成 FAIL 会让诊断包里
        // 每次出现一条假失败（0.10.7 诊断包里这条**每一次都 FAIL**），
        // 读日志的人会先去查它，白白浪费一次上车的机会。本步**非阻断**，
        // 放宽不影响任何控制流，只让证据忠实。
        result += ConfigStep(
            "清理网卡占用者（killall hostapd / busybox）",
            relaxKillallNoTarget(
                session.runner.shell("$prefix/system/bin/killall -s KILL hostapd busybox"),
            ),
            blocking = false,
        )

        result += ConfigStep(
            "加路由规则（本网段走 ${network.interfaceName}）",
            addIpRuleIdempotent(network, prefix),
            blocking = false,
        )

        // ADB 端口只在用户手动初始化入口配置。热点会话绝不改 USB/TCP ADB 属性，
        // 也不重启 adbd；这里仅复用既有的 5556 shell 执行 AP 网络配置。

        // ⚠️ 这一步**不是可选的**，也不是"顺手拷贝一下"：busybox 靠 `argv[0]`
        // 的 basename 找 applet，而随包文件名只能是 `lib*.so` ⇒ 直接执行它
        // 永远 `applet not found`。必须改名部署到 /data/local/tmp 才能跑 udhcpd。
        // 详见 `CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH`。
        // 它同样不阻断：部署失败会在下游的 `udhcpd` 上直接暴露（`applet not found`）。
        result += ConfigStep(
            "部署 busybox 到 ${CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH}",
            session.runner.stageBusybox(prefix),
            blocking = false,
        )

        // 按需模式不安装 init/BOOT_COMPLETED 热点服务。自建热点仅由
        // CarPlaySessionBridge 在 ADB_HOSTAPD 会话中启动，并在会话结束时收回。

        return result
    }

    /**
     * 安装「开机自启热点」的 init 服务（2026-10-06 新增）。
     *
     * ## 它做什么
     *
     * 1. 生成脚本 = [CarAdbHotspotBootInstaller.BOOT_SCRIPT] + 运行时网段变量；
     * 2. 连同 init 服务定义一起 base64 编码；
     * 3. 一条 root shell 里：`remount rw /system` → 落两个文件 → `chmod`
     *    → `restorecon` → **挂回 ro** → 回读校验；
     * 4. 成功后置落盘开关 [CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY] = `1`
     *    （斌哥口径：「永久开」= 开机自动回来）。
     *
     * ## 为什么每次连接都重装一遍
     *
     * 写同样的内容是**幂等**的，代价只有一次 `remount`。收益是：
     * 用户改了 SSID / 密码 / 网段后，开机脚本自动跟着更新，不需要额外的
     * "配置变更时重新安装"逻辑。比"只在没装过时装"少一整类状态同步 bug。
     *
     * @param network 本次实际生效的网络参数（网段是运行时算出来的，不能写死）。
     */
    private fun installBootService(
        session: AdbShellSession,
        prefix: String,
        network: CarHotspotNetwork,
    ): AdbShellRunner.CommandResult {
        val installer = CarAdbHotspotBootInstaller
        val fullScript = installer.BOOT_SCRIPT +
            installer.buildNetworkVariables(
                hostAddress = network.hostAddress,
                // ⚠️ 用 planner 算好的 subnet（`192.168.176.0/24`），别自己拼：
                // 网段前缀长度不是常量（planner 会按候选段调整），自己算迟早错。
                subnet = network.subnet,
                dhcpStart = network.dhcpStart,
                dhcpEnd = network.dhcpEnd,
            )
        val scriptB64 = base64(fullScript)
        val rcB64 = base64(installer.BOOT_RC)

        val result = session.runner.shell(
            installer.buildInstallScript(scriptB64, rcB64, prefix),
            timeoutMillis = BOOT_INSTALL_TIMEOUT_MILLIS,
        )
        val output = result.output

        return when {
            // 退出码 0：这是「预期内的跳过」（/system 没被改动、热点照开），不是失败。
            // 记成失败会让它每次都成为 `firstFailure`，掩盖真正卡住的那一步
            // （诊断页 / "ADB 热点失败" 日志都按 firstFailure 定位）。详情仍写在输出里。
            output.contains("SYSTEM_NOT_RW") -> AdbShellRunner.CommandResult(
                exitCode = 0,
                output = "⚠️ /system 无法挂成 rw（可能有 dm-verity 保护），" +
                    "已跳过安装、未修改任何系统文件。\n" +
                    "现象：重启后热点仍不会自动回来，其余功能不受影响。\n" +
                    "原始输出：\n" + output.trim(),
            )

            output.contains("INSTALL_OK") -> {
                // 装成功 ⇒ 置「永久开」，开机脚本第一步会读它
                session.runner.shell(
                    "$prefix/system/bin/setprop " +
                        CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY + " " +
                        CarAdbHotspotDefaults.BOOT_ENABLED_ON,
                )
                AdbShellRunner.CommandResult(
                    exitCode = 0,
                    output = "已安装：\n  " + CarAdbHotspotDefaults.BOOT_SCRIPT_PATH + "\n  " +
                        CarAdbHotspotDefaults.BOOT_RC_PATH + "\n" +
                        "永久开关已置 " + CarAdbHotspotDefaults.BOOT_ENABLED_ON +
                        "（重启后热点自动回来）\n" +
                        "原始输出：\n" + output.trim(),
                )
            }

            else -> AdbShellRunner.CommandResult(
                exitCode = result.exitCode,
                output = "安装未确认成功（未见 INSTALL_OK）。\n原始输出：\n" + output.trim(),
            )
        }
    }

    /**
     * **开通车机 TCP ADB**（`service.adb.tcp.port=<port>` + 拉起 `adbd`）。
     *
     * ## 为什么需要它（2026-10-05，为"每台车都能开"）
     *
     * ADB 通道 = `service.adb.tcp.port` 有值 **且** `init.svc.adbd=running`。
     * 参考实现只写 `persist.sys.usb.adb`，从不设 TCP 端口——它的目标车机
     * 默认开着。换一台 ADB 关着的车，`AdbShellSession` 会在第 4 步
     * `connect` 直接失败，**热点根本走不到**。
     *
     * 而走到 [configure] 时我们**已经有 root shell**（`prefix`），
     * 是唯一能安全补这个配置的时刻：补完这台车以后开机就有 TCP ADB，
     * `BootReceiver` 能直接走 adb 拉起热点，不再靠运气。
     *
     * ## 幂等
     *
     * 先 `getprop` 读现值：已经是目标端口就只补 `ctl.start adbd`（adbd 可能
     * 停了）；值不同才写。**不 stop 再 start**——那会掐断我们自己正在用的
     * adb 连接（当前 shell 就在这条通道上）。
     *
     * ## 失败不阻断
     *
     * 属性只读 / SELinux 拒绝写入时返回非 0，但[ConfigStep] 标了
     * `blocking = false`——热点该开还得开。（这也是本次改动能"对老车零风险"的
     * 原因：能开的车本来就能开，开不了的至少多一条可能。）
     */
    private fun ensureTcpAdb(session: AdbShellSession, prefix: String): AdbShellRunner.CommandResult {
        val expected = CarAdbHotspotDefaults.DEVICE_ADB_PORT.toString()
        val prop = CarAdbHotspotDefaults.ADB_PORT_PROPERTY
        val state = CarAdbHotspotDefaults.ADB_STATE_PROPERTY
        val setprop = "$prefix/system/bin/setprop"
        val getprop = "$prefix/system/bin/getprop"
        // 一条 shell 里做完：读现值 → 需要才写 → 拉起 adbd → 复检。
        // 分开跑会白白多开几次 adb shell（每次都在那条慢通道上）。
        // ⚠️ `\$cur` 的 `\$` 是**给 Kotlin 看的**（转义成字面 `$`），
        //    shell 最终收到的是 `$cur` —— 展开的是 shell 变量，不是 Kotlin 的。
        val script = buildString {
            append("cur=$(").append(getprop).append(' ').append(prop).append("); ")
            append("if [ \"\$cur\" != \"").append(expected).append("\" ]; then ")
            append(setprop).append(' ').append(prop).append(' ').append(expected).append("; ")
            append("fi; ")
            append("st=$(").append(getprop).append(' ').append(state).append("); ")
            append("if [ \"\$st\" != \"running\" ]; then ")
            append(setprop).append(" ctl.start adbd; ")
            append("fi; ")
            append("echo \"").append(prop).append("=$(").append(getprop).append(' ')
                .append(prop).append(") ").append(state).append("=$(").append(getprop)
                .append(' ').append(state).append(")\"")
        }
        return session.runner.shell(script)
    }

    /**
     * **幂等**地加 `ip rule`：先 `del` 再 `add`。
     *
     * ## 为什么必须幂等（这是一个真实故障点）
     *
     * `ip rule add` 在规则**已存在**时返回 `RTNETLINK answers: File exists`，
     * 退出码非 0。而上一条规则极容易残留——只要上一次没有走到
     * [tearDown]（进程被杀、会话异常结束、用户直接退出 App），
     * `ip rule del` 就不会执行，规则留在内核里。
     *
     * 于是下一次拉起时：
     * ```
     * ip rule add → File exists（退出码 2）
     *   ⇒ configure() 里的这一步被标成失败
     *   ⇒ 在"逐条判"的老实现里：整条 ADB 热点放弃 ⇒ 回落 LocalOnlyHotspot
     *   ⇒ 这台车机的 LOH 回调不来 ⇒ 60 秒超时 ⇒ LOH 中毒
     * ```
     * 现象就是"**第一次连得上，之后再也起不来**"。
     *
     * ## 参考实现为什么不受影响
     *
     * 它的 `Adbtool` 只读输出、**根本不看退出码**（见 `ADB-HOTSPOT.md` §2.4），
     * 所以那句 `File exists` 被无声跳过、流程照走。
     *
     * ## 两层保险
     *
     * ① 本方法把 `File exists` 判成"等价于添加成功"（下面 [实现] 一段）；
     * ② 这一步在 [configure] 里是 `blocking = false` —— 就算它真的失败了，
     * 也只留证据、不掐断整条链，判据交给收尾的 `ifconfig` 校验。
     * 少任何一层，`File exists` 都还能把流程拖垮。
     *
     * ## 实现
     *
     * `del` 失败（规则本来就不存在）是**正常**的，只看 `add`。
     * 另外把 `File exists` 也当成功：万一 `del` 因为别的原因没生效，
     * 结果与"已经加上了"是等价的，没有理由让整条链失败。
     */
    private fun addIpRuleIdempotent(
        network: CarHotspotNetwork,
        prefix: String,
    ): AdbShellRunner.CommandResult {
        val target = "${network.hostAddress}/${network.prefixLength}"
        val remove = session.runner.shell("$prefix/system/bin/ip rule del to $target priority 200")
        val add = session.runner.shell("$prefix/system/bin/ip rule add to $target priority 200")
        val benign = CarAdbShellQuirks.isBenignIpConflict(add.output)
        return AdbShellRunner.CommandResult(
            exitCode = if (add.ok || benign) 0 else add.exitCode,
            output = "先删残留规则：${remove.summary}\n后加：${add.summary}" +
                if (benign) "\n（`File exists` = 规则本来就在，等价于添加成功）" else "",
        )
    }

    /**
     * 给 AP 网卡配地址。
     *
     * 参考实现用 `ifconfig <if> 192.168.175.1 up`。这里把它作为主路径
     * （toybox 的 `ifconfig` 是**幂等**的：重复设置同一个地址不会报错），
     * 失败再退到 `ip link set` + `ip addr add`（`ip addr add` 对已存在的地址
     * 会返回 `RTNETLINK answers: File exists`，那是无害的，所以只看退出码不看输出）。
     */
    private fun assignAddress(
        network: CarHotspotNetwork,
        prefix: String,
    ): AdbShellRunner.CommandResult {
        val address = "${network.hostAddress}/${network.prefixLength}"
        val byIfconfig = session.runner.shell(
            "$prefix/system/bin/ifconfig ${network.interfaceName} ${network.hostAddress} up",
        )
        if (byIfconfig.ok) return byIfconfig

        val byIp = session.runner.shell("$prefix/system/bin/ip link set ${network.interfaceName} up")
        val add = session.runner.shell(
            "$prefix/system/bin/ip addr add $address dev ${network.interfaceName}",
        )
        // `ip addr add` 对已存在的地址返回 `RTNETLINK answers: File exists`——
        // 与 `ip rule add` 同类的"无害非零退出码"，同样不能当失败（理由见
        // [addIpRuleIdempotent]）。地址已经在网卡上，正是我们要的结果。
        val benign = CarAdbShellQuirks.isBenignIpConflict(add.output)
        return AdbShellRunner.CommandResult(
            exitCode = if (byIp.ok || add.ok || benign) 0 else 1,
            output = "ifconfig 失败：${byIfconfig.summary}\n" +
                "ip link set → ${byIp.summary}\n" +
                "ip addr add → ${add.summary}" +
                if (benign) "\n（`File exists` = 地址本来就在，等价于配置成功）" else "",
        )
    }

    /**
     * 生成两份配置文件。**结构逐字对照参考实现**，但每一处涉及网络参数的地方
     * 都换成 [CarHotspotNetwork] 里选好的值；另有几处有意偏离，都在注释里写清楚了。
     *
     * @return 失败原因；null 表示写成功。
     */
    /**
     * 生成两份配置文件。
     *
     * **内容生成全在 [CarAdbHotspotConfig]**（纯函数、有单测）——那是"错一个字节
     * hostapd 就立刻退出"的地方，留在这一层（要 `Context`）就只能靠上车试。
     * 这里只负责取凭据 + 落盘。
     *
     * @return 失败原因；null 表示写成功。
     */
    private fun writeLocalConfig(network: CarHotspotNetwork): String? = runCatching {
        val settings = readHotspotSettings()

        val softap = CarAdbHotspotConfig.softap(
            network = network,
            ssid = settings.ssid,
            passphrase = settings.passphrase,
            open = settings.open,
        )
        val udhcpd = CarAdbHotspotConfig.udhcpd(network)

        File(appContext.filesDir, CarAdbHotspotDefaults.LOCAL_SOFTAP_CONF)
            .writeText(softap, Charsets.UTF_8)
        File(appContext.filesDir, CarAdbHotspotDefaults.LOCAL_UDHCPD_CONF)
            .writeText(udhcpd, Charsets.UTF_8)
        null
    }.getOrElse { error -> "写配置失败：${error.javaClass.simpleName}: ${error.message}" }

    // ------------------------------------------------------------ 自检

    private fun runSelfCheck(interfaceOverride: String, onState: (CarAdbHotspotState) -> Unit) {
        val steps = mutableListOf<CarAdbHotspotStep>()
        var index = 0

        fun record(title: String, ok: Boolean, detail: String) {
            index += 1
            steps += CarAdbHotspotStep(index, title, ok, detail)
            publish(onState) { it.copy(steps = steps.toList()) }
        }

        publish(onState) {
            it.copy(
                phase = CarAdbHotspotPhase.CHECKING_BINARIES,
                steps = emptyList(),
                message = "正在自检…",
            )
        }

        // 1 adb 通道（自检里**真的执行**开通 ADB：不打开它后面几步无从验证。
        //   它写的是系统属性，幂等且可逆，不属于"破坏性"操作。）
        val access = session.ensure(force = true)
        access.steps.forEach { step -> record(step.title, step.ok, step.detail) }

        if (access.shellOk) {
            val prefix = access.prefixOrEmpty

            // 2 网络参数（只算不写）
            publish(onState) { it.copy(phase = CarAdbHotspotPhase.SELECTING_NETWORK) }
            val network = CarAdbHotspotPlanner.plan(appContext, info = null, interfaceOverride = interfaceOverride)
            record(
                "网络参数选择（网卡 / 网段 / 信道）",
                true,
                "${network.interfaceName}；${network.subnet}；网关 ${network.hostAddress}；" +
                    "DHCP ${network.dhcpStart}-${network.dhcpEnd}；信道 ${network.channel}\n" +
                    "选择理由：${network.rationale}",
            )
            publish(onState) {
                it.copy(
                    interfaceName = network.interfaceName,
                    subnet = network.subnet,
                    hostAddress = network.hostAddress,
                    channel = network.channel,
                )
            }

            // 3 hostapd 路径
            // 自检里 `ok = 探到了没有`（它是**报告**，不是闸门）；真正拉起热点时
            // 探不到也会退回参考实现的位置继续试，见 runBringUp 那一步。
            val hostapdPath = detectHostapdPath(prefix)
            record(
                "定位 hostapd",
                hostapdPath != null,
                hostapdPath?.let { "使用 $it" }
                    ?: "候选路径一个都没探到（真正拉起时会退回参考实现的位置 " +
                    "${CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES.first()} 继续试）：" +
                    CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES.joinToString(),
            )

            // 4 只读探测：不碰系统状态，只看现状
            publish(onState) { it.copy(phase = CarAdbHotspotPhase.PROBING_INTERFACE) }
            val name = network.interfaceName
            val readOnly = buildList {
                add(
                    "ifconfig $name（当前地址与状态）" to
                        session.runner.shell("/system/bin/ifconfig $name"),
                )
                add(
                    "ip rule show（是否已有本网段规则）" to
                        session.runner.shell("${prefix}/system/bin/ip rule show"),
                )
                add(
                    "网段冲突检查（本机已有地址）" to
                        session.runner.shell("/system/bin/ip -4 addr show"),
                )
                add(
                    "现有 hostapd / udhcpd 进程" to
                        session.runner.shell("${prefix}/system/bin/ps -A | grep -i -E 'hostapd|udhcpd'"),
                )
                // busybox 必须**改名部署**才能跑（见 REMOTE_BUSYBOX_PATH）。
                // 这一步只问"现在能用吗"，不会去部署——自检承诺只读。
                add(
                    "已部署的 busybox（${CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH}）" to
                        session.runner.probeStagedBusybox(),
                )
            }
            readOnly.forEach { (title, result) -> record(title, result.ok, result.summary) }
        }

        publish(onState) {
            it.copy(
                phase = CarAdbHotspotPhase.IDLE,
                steps = steps.toList(),
                message = "自检完成：${steps.count { it.ok }}/${steps.size} 步通过",
            )
        }
    }

    // ------------------------------------------------------------ 收尾

    /**
     * 清掉「自建热点」**留在网卡上的地址**（如果确实还在），返回一句话结论；null = 没有记录。
     *
     * 契约见 `CarAdbHotspotHost.releaseLeftoverAddress`。要点：
     * - 证据来源是**落盘的足迹**（[CarHotspotFootprintStore]），不是内存里的 `activeNetwork`
     *   —— 后者在进程被杀后就不存在了，而网卡上的地址还在（"模式互相污染"里最难查的一种）；
     * - 只删**我们实际配过的那一个地址**，绝不按网段删（`SUBNET_CANDIDATES` 里含
     *   `192.168.175.0/24`，车机自带热点可能正好也在 175 ⇒ 会误删车机的地址）；
     * - **允许用 ADB 工具**：ADB 是本工程的共享设施，不属于任何一个热点模式。
     */
    override fun releaseLeftoverAddress(): String? {
        val footprint = CarHotspotFootprintStore.load(appContext) ?: return null
        val present = CarNetworkInterfaces.addressPresentAnywhere(appContext, footprint.address)
        if (present == null) {
            CarHotspotFootprintStore.clear(appContext)
            return "自建热点足迹 ${footprint.cidr}：网卡上已无此地址（记录已清）"
        }
        val (iface, address) = present
        // 需要 ADB 通道才能删地址；没有就如实报"没清成"（由调用方决定是否继续）。
        val access = session.peek() ?: runCatching { session.ensure() }.getOrNull()
        if (access == null || !access.shellOk) {
            return "⚠️ 自建热点残留 $address（网卡 $iface）仍在，但没有可用的 ADB 通道 —— 未清理"
        }
        val prefix = access.prefixOrEmpty
        session.runner.shell(
            "$prefix/system/bin/ip addr del $address/${footprint.prefixLength} dev $iface",
        )
        val cleared = CarNetworkInterfaces.addressPresentAnywhere(appContext, address) == null
        if (cleared) CarHotspotFootprintStore.clear(appContext)
        return if (cleared) {
            "已用 ADB 清掉自建热点残留 $address（网卡 $iface）—— 复用不会再被它污染"
        } else {
            "⚠️ 已发清理命令，但自建热点残留 $address（网卡 $iface）仍在"
        }
    }

    /**
     * 等 hostapd 自己退出（`SIGTERM` 之后）。**有界**：等不到就返回 false，由调用方升级为强杀。
     *
     * 为什么值得等：hostapd 退出的过程里包含 nl80211 `stop_ap` —— 那一步才把 BSS 拆掉、
     * beacon 才停（见 `CarAdbHotspotCommands.stopHostapdGracefully` 的说明）。
     */
    private fun awaitHostapdGone(prefix: String): Boolean {
        val deadline = System.nanoTime() + HOSTAPD_STOP_WAIT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (hostapdPidsOrEmpty(prefix).isEmpty()) return true
            runCatching { Thread.sleep(HOSTAPD_STOP_POLL_MS) }
        }
        return hostapdPidsOrEmpty(prefix).isEmpty()
    }

    private fun hostapdPidsOrEmpty(prefix: String): String =
        runCatching { session.runner.shell(CarAdbHotspotCommands.hostapdPids(prefix)) }
            .getOrNull()?.output?.trim().orEmpty()

    /**
     * 确认网卡**已退出 AP 模式**（`iw dev <iface> info` 里不再有 `type AP`）；必要时强制复位接口。
     *
     * ## 为什么必须校验（车主现场："点了关闭，手机上还能搜到自建热点"）
     *
     * 只要 hostapd 是被 `SIGKILL` 掉的（或它没来得及走 `stop_ap`），**驱动会一直停在
     * AP 模式**、beacon 继续发 —— 应用里显示"已停止"，手机上却还搜得到那个 SSID。
     * 这种情况除了**把接口放下再抬起**（`ip link set <iface> down/up`，会移除该接口上的 BSS）
     * 没有别的办法把 SSID 从空中收回去。
     *
     * ⚠️ 复位接口是"重锤"：只在本函数已经确认"网卡还是 AP"时才用（那时这张卡本来就是
     * 我们占着的 AP），绝不在正常拆除路径上顺手做。
     */
    private fun ensureInterfaceNotAp(prefix: String, iface: String): Boolean {
        val before = runCatching {
            session.runner.shell(CarAdbHotspotCommands.interfaceInfo(prefix, iface)).output
        }.getOrDefault("")
        if (!CarAdbHotspotCommands.iwDevShowsApMode(before)) return true
        android.util.Log.w(
            TAG,
            "拆除后网卡 $iface 仍是 AP 模式（手机还会搜到 SSID）—— 强制 down/up 复位接口",
        )
        session.runner.shell(CarAdbHotspotCommands.bounceInterface(prefix, iface))
        runCatching { Thread.sleep(BOUNCE_SETTLE_MS) }
        val after = runCatching {
            session.runner.shell(CarAdbHotspotCommands.interfaceInfo(prefix, iface)).output
        }.getOrDefault("")
        return !CarAdbHotspotCommands.iwDevShowsApMode(after)
    }

    private fun tearDown() {
        val network = activeNetwork
        // 只读缓存，**不触发重新获取**：缓存已经没了就说明本轮没建过 shell，
        // 为了"拆"再去起一次 adb server 是荒谬的。
        val prefix = session.peek()?.prefixOrEmpty.orEmpty()
        if (network != null) {
            // ⚠️ 2026-10-11：**先 TERM、再（必要才）KILL**。
            //
            // 原来只有 `killall -s KILL hostapd busybox`。车主现场现象：
            // 「点了关闭，手机上**还能搜到**自建热点」——因为 SIGKILL 不给 hostapd
            // 走 nl80211 `stop_ap` 的机会：进程没了，**驱动仍停在 AP 模式**，beacon 照旧。
            // 现在先 SIGTERM 让它自己把 BSS 拆掉，等它退出；只有还活着才升级到 SIGKILL。
            session.runner.shell(CarAdbHotspotCommands.stopHostapdGracefully(prefix))
            val graceful = awaitHostapdGone(prefix)
            if (!graceful) {
                android.util.Log.w(
                    TAG,
                    "hostapd 收到 SIGTERM 后仍在跑 —— 升级为 SIGKILL（注意它不会拆 AP，" +
                        "随后必须校验网卡模式）",
                )
                session.runner.shell(CarAdbHotspotCommands.killHostapdAndUdhcpd(prefix))
            }
            // DHCP 单独收：`udhcpd` 是 busybox 的 applet，杀 busybox 不影响 hostapd。
            session.runner.shell("$prefix/system/bin/killall -s TERM busybox")
            session.runner.shell(
                "$prefix/system/bin/ip rule del to " +
                    "${network.hostAddress}/${network.prefixLength} priority 200",
            )
            // ⚠️ 只**删掉我们加的那个地址**，绝不 `ifconfig down`：
            // 万一选中的网卡同时也是车机的上行，down 掉会直接掐断车机自己的网络。
            session.runner.shell(
                "$prefix/system/bin/ip addr del " +
                    "${network.hostAddress}/${network.prefixLength} dev ${network.interfaceName}",
            )
        }
        // 最后一道判据：**网卡真的不再是 AP 了吗**。
        // 不是才叫"关闭"；否则手机照样能搜到 SSID（车主报的就是这个）。
        val apStopped = network == null || ensureInterfaceNotAp(prefix, network.interfaceName)
        // 收尾只回收热点派生的客户端及热点 owner。
        // 即使热点是最后一个临时 owner，设备侧 5556 初始化仍有效；关闭本地 adb server
        // 只影响当前 host 进程，下一次 ADB 消费者可在固定 5556 上重新连接。
        session.runner.stopDaemons()
        session.invalidate()
        session.close(CarAdbHotspotDefaults.OWNER_HOTSPOT)
        activeNetwork = null
        activeConfPath = null
        // 足迹的清理要**先确认地址真的没了**（同上：非阻塞契约）。
        // 还在就保留记录 —— 下一次模式切换 / 冷启动会用 ADB 工具再清一次
        // （否则复用分支会把我们的残留当成车机热点地址通告出去）。
        if (network != null) {
            val stillThere = CarNetworkInterfaces.addressPresentAnywhere(appContext, network.hostAddress)
            if (stillThere == null) {
                CarHotspotFootprintStore.clear(appContext)
            } else {
                android.util.Log.w(
                    TAG,
                    "自建地址 ${network.hostAddress} 拆除后仍在网卡 ${stillThere.first} 上，" +
                        "足迹保留待下次清理（否则复用会把它当成车机热点地址）",
                )
            }
        }
        publish(null) {
            it.copy(
                phase = CarAdbHotspotPhase.STOPPED,
                // 网卡占用情况是"上一轮"的结论，收干净之后就不能再留着——
                // 留着会让下一次拉起读到一个过期的"别人的 AP"名字。
                foreignApSsid = null,
                message = if (apStopped) {
                    "热点已停止（网卡已退出 AP 模式，手机不会再搜到）"
                } else {
                    "⚠️ 热点进程已停，但网卡仍是 AP 模式 —— 手机可能仍搜到该 SSID"
                },
            )
        }
    }

    // ------------------------------------------------------------ 杂项

    /**
     * 读设置页的热点凭据。
     *
     * ## 为什么在这里直读、而不是让调用方传进来
     *
     * `hostapd` 实际使用的凭据与通告给 iPhone 的凭据**必须是同一个值**
     * （否则 iPhone 会拿着 A 密码去连 B 密码的 AP）。两处都要用，而
     * `CarPlayConfigBuilder` 那条链路只吃 `CarHotspotPlan`，把凭据再穿一层
     * 会让已经很宽的签名更宽。
     *
     * 这里复用同一个 `CarSettingsStore` 实现（同一模块内可见），
     * **不另抄一份 SharedPreferences 键名**——抄一份就意味着以后改键名会漏。
     */
    private fun readHotspotSettings(): HotspotCredentials {
        val settings = SharedPreferencesCarSettingsStore(appContext).load()
        return HotspotCredentials(
            ssid = settings.manualHotspotSsid.ifBlank { CarAdbHotspotDefaults.SSID },
            passphrase = settings.manualHotspotPassphrase
                .ifBlank { CarAdbHotspotDefaults.PASSPHRASE },
            open = settings.manualHotspotSecurity == CarHotspotSecurity.OPEN,
        )
    }

    /**
     * 设置页里手动指定的 AP 网卡名。
     *
     * 空 = 交给 [CarAdbHotspotPlanner] 自动选择。真机上这张网卡叫什么由厂商决定
     * （`wlan0` / `wlan1` / `wlan2` / `ap0` / `softap0` 都见过），所以必须留一个
     * 人工兜底口——否则探测猜错时用户只能改代码。
     */
    private fun configuredInterface(): String =
        SharedPreferencesCarSettingsStore(appContext).load().adbHotspotInterface

    private class HotspotCredentials(
        val ssid: String,
        val passphrase: String,
        val open: Boolean,
    )

    private fun waitForRunning(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_FOR_RUNNING_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (!running.get()) return snapshot.isRunning
            Thread.sleep(WAIT_POLL_MILLIS)
        }
        return snapshot.isRunning
    }

    private fun publish(
        onState: ((CarAdbHotspotState) -> Unit)?,
        transform: (CarAdbHotspotState) -> CarAdbHotspotState,
    ) {
        val next = synchronized(lock) {
            snapshot = transform(snapshot)
            snapshot
        }
        if (onState == null) return
        mainHandler.post { onState(next) }
    }

    private companion object {
        /** logcat 标签。跨进程复用那条日志靠它定位。 */
        const val TAG = "XCExt.hotspot.manager"

        /** 等 hostapd 体面退出的总时长（含 nl80211 `stop_ap`）。 */
        const val HOSTAPD_STOP_WAIT_MS = 1_500L

        /** 等 hostapd 退出的轮询间隔。 */
        const val HOSTAPD_STOP_POLL_MS = 150L

        /** `ip link set down/up` 之后等驱动收拾完的时间。 */
        const val BOUNCE_SETTLE_MS = 600L

        const val WAIT_FOR_RUNNING_MILLIS = 30_000L
        const val WAIT_POLL_MILLIS = 200L

        /** 守护进程的启动重试次数（参考实现是 5，我们叠了 1.5s 观察窗口，见函数注释）。 */
        const val DAEMON_ATTEMPTS = 3

        /** 重试间隔。与参考实现一致。 */
        const val DAEMON_RETRY_BACKOFF_MILLIS = 500L

        /**
         * 「安装开机自启服务」那一步的超时（2026-10-06 新增）。
         *
         * 比普通命令宽：这一步内含 `remount rw` + `remount ro` 两次全分区
         * 挂载操作，老车机上单次 `remount` 可能到秒级。给足 20s，避免
         * 「其实装上了但命令被掐断」的假失败。
         */
        const val BOOT_INSTALL_TIMEOUT_MILLIS = 20_000L

        /**
         * 把脚本正文编成 base64（单行，无换行）。
         *
         * ## 为什么必须 base64
         *
         * 脚本里有引号、`$`、换行、heredoc 终止符。要走三层 shell 送到车机
         * （本机 → 车机 adbd → 车机 sh），裸文本几乎必坏 —— 本工程已有前车之鉴：
         * `su 0 for p in …` 在车机上得到 `for: not found`
         * （见 `CarAdbHotspotCommands.hostapdOwners` 的注释）。
         *
         * base64 字母表只含 `A-Za-z0-9+/=`，不含任何 shell 元字符，
         * 因此可以直接裸拼进 `echo <b64> | base64 -d > <file>`。
         *
         * ⚠️ Android 的 `Base64.encodeToString` 默认**每 76 字符插换行**，
         * 换行会让 `echo` 断成多条命令 —— 必须用 `NO_WRAP`。
         */
        fun base64(text: String): String =
            android.util.Base64.encodeToString(
                text.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
    }
}
