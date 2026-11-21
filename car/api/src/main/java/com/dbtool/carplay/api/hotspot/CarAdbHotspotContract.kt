package com.dbtool.carplay.api.hotspot

/** 当前设备内置 ADB 通道的实测状态；与用户是否曾执行初始化分开记录。 */
enum class CarAdbChannelStatus {
    UNKNOWN,
    CHECKING,
    INITIALIZING,
    READY,
    NOT_INITIALIZED,
    UNAVAILABLE,
    PERSISTENCE_UNVERIFIED,
}

data class CarAdbInitializationState(
    /** 用户曾主动执行初始化流程；这是历史记录，不证明当前端口可用。 */
    val initializedByUser: Boolean = false,
    /** 上次初始化时 5556 当前通道可用。 */
    val initializationSucceeded: Boolean = false,
    /** 上次初始化时持久端口属性确实读回为 5556。 */
    val persistentPortVerified: Boolean = false,
    /** 当前检测结果，应用重启后必须重新检测。 */
    val channelStatus: CarAdbChannelStatus = CarAdbChannelStatus.UNKNOWN,

    /**
     * 用户主动「关闭 ADB」落盘的口径（2026-10-15，斌哥：「永久开启 ADB 旁新增关闭 ADB 按钮」）。
     *
     * ⚠️ 与 [channelStatus] **分工不同**，不要互相代替：
     * [channelStatus] 回答"现在连得上吗"（一次实测的结果，重启即失效）；
     * 本字段回答"用户还允许 DBPlay 用这条通道吗"（用户口径，重启后仍然生效）。
     *
     * 为 true 时：不预热、不自愈、看门狗不巡检、不跑启动性能优化，且**不再实连探测 5556**
     * —— 否则界面会把"用户自己关的"渲染成"出故障了"，把用户引去点「永久开启 ADB」，
     * 一点就把刚关掉的通道又打开。
     *
     * 只有用户在设置页重新点「永久开启 ADB（5556）」才会清掉本标记。
     */
    val disabledByUser: Boolean = false,
    val detail: String = "尚未检测 ADB 端口。",
)

/**
 * 「DBPlay 自建热点」的固定参数与**候选池**。
 *
 * ## 只固定两件事
 *
 * 产品要求：SSID `DBPlay`、密码 `12345678`。其余**全部在运行时按车机实际
 * 环境选**——接口、网段、网关、DHCP 池、路由、信道、`hostapd` 路径。
 *
 * 历史教训：早期版本把参考实现（`FUCK-Lynk&Co-ADB.apk`）的
 * `192.168.175.1` / `wlan0` / 信道 36 **当成常量写死**，结果换一台车就废——
 * 网卡名不是 `wlan0`、网段与车机上某个已存在的网段冲突、
 * `hostapd` 不在 `/vendor/bin/hw/`。参考实现那些值是**针对某一批车机调出来的**，
 * 可以当"首选候选"，但**不能当事实**。所以现在它们是候选列表，
 * 由 [CarNativeHotspotInfo] 探测到的真实环境来决定用哪个。
 *
 * ## 候选列表的排序原则
 *
 * 每个候选列表的第一项都是"参考实现的值"——它在一部分车机上已被验证过，
 * 所以有冲突时优先试它；后续项是**避开常见冲突**的备选。
 */
object CarAdbHotspotDefaults {

    /** 热点名。产品硬要求，不要改。 */
    const val SSID: String = "DBPlay"

    /** 热点密码。产品硬要求，不要改。8 位，正好是 WPA2-PSK 的下限。 */
    const val PASSPHRASE: String = "12345678"

    // -------------------------------------------------------------- 网段

    /**
     * 参考实现用的网卡名。
     *
     * ⚠️ **这不是"默认值"，只是最后的兜底**：正常情况下 AP 网卡由
     * `CarAdbHotspotPlanner` 按探测结果选（`wlan*` / `ap*` / `softap*` 里
     * 排除上行后的第一张）。只有连一张 Wi-Fi 网卡都枚举不到时才会用到它，
     * 而那种情况下热点基本也起不来——写进日志是为了让人知道这是"猜的"。
     */
    const val DEFAULT_INTERFACE: String = "wlan0"

    /**
     * 网段候选，按优先级。选中的那个必须**与本机现有网段都不冲突**。
     *
     * 排序理由：
     * 1. `192.168.175.0/24` —— 参考实现的值，在目标车机上已验证；
     * 2. `192.168.176~179.0/24` —— 同段位的邻居，避开"车机自己也在用 175"的情况；
     * 3. `10.42.0.0/24` / `172.20.0.0/24` —— 完全换段位。
     *    车机蜂窝网段常见 `10.x`（所以 `10.42` 排在靠后：**先试不冲突的私有段**，
     *    只有前面全被占了才用它，且仍会做冲突检测）；
     * 4. 刻意**不包含** `192.168.43.0/24` / `192.168.14.0/24`：
     *    它们是 Android 自建热点（`192.168.43.x`）与 LocalOnlyHotspot 的默认池，
     *    撞上会留下 `ip rule` 残留（见 `ADB-HOTSPOT.md` §2.8）。
     */
    val SUBNET_CANDIDATES: List<String> = listOf(
        "192.168.175.0/24",
        "192.168.176.0/24",
        "192.168.177.0/24",
        "192.168.178.0/24",
        "192.168.179.0/24",
        "172.20.0.0/24",
        "10.42.0.0/24",
    )

    /**
     * 自建热点一律用 /24。
     *
     * 这是**设计选择**而不是"抄来的固定值"：DHCP 池、路由、网关都按 /24 推导，
     * 换前缀长度要同时改这三处，没有收益。
     */
    const val PREFIX_LENGTH: Int = 24

    /**
     * 网段里给 DHCP 客户端的地址范围（相对主机号的起止）。
     *
     * `.1` 留给 AP 自己、`.255` 是广播地址，所以池是 `.2 ~ .254`。
     */
    const val DHCP_HOST_START: Int = 2

    const val DHCP_HOST_END: Int = 254

    // -------------------------------------------------------------- 信道

    /**
     * 2.4 GHz 信道候选：`1` / `6` / `11`（**已停用，仅作记录**）。
     *
     * ## ⚠️ 2026-10-07 起本产品只走 5 GHz，这个列表不再参与任何选择
     *
     * 原设计把它当"5 GHz 起不来"时的兜底频段；但车主口径已明确：
     * **自建热点默认且仅用 5 GHz，频段自动选择与 2.4 GHz 一并隐藏**。
     * 保留常量只为一件事：将来要回退时，不必再翻历史，直接把它接回
     * [CHANNEL_CANDIDATES] 即可。
     *
     * ## 取值理由（存档）
     *
     * 只取 1/6/11，是因为它们在 2.4 GHz 上**互不重叠**（每信道占 22 MHz，
     * 中心频率要隔开 5 个信道才不串扰）；中间那些（2~5、7~10）必然与其中
     * 某一个重叠，做候选没有意义。
     */
    @Deprecated("2026-10-07 起只走 5 GHz，本列表不参与选择（详见 CHANNEL_CANDIDATES）")
    val CHANNEL_CANDIDATES_2G: List<String> = listOf("1", "6", "11")

    /**
     * 5 GHz 信道候选：`36` / `40` / `44` / `48`（UNII-1）。
     *
     * ## ✅ 2026-10-07 起**这是唯一在用的候选池**
     *
     * 车主口径变更：自建热点**默认且仅走 5 GHz**，频段自动选择与 2.4 GHz
     * 一并隐藏。此前"车机射频只有 2.4 GHz（用户 2026-09-29 反馈）"的判定
     * 已被推翻——参考实现（`FUCK-Lynk&Co-ADB.apk`）在目标车机上确实以
     * `hw_mode=a` + `channel=36` 成功起过热点。
     *
     * 参考实现写死 `hw_mode=a` + `channel=36`；本实现不写死单值，而是
     * 在下面 4 个信道里按顺序试，起不来就换下一个。
     * （`hw_mode` / `ieee80211ac` 由 [hwModeOfChannel] / [isFiveGigahertz]
     * 按信道自动推导，配置生成代码无需改动。）
     *
     * 只取 UNII-1 这 4 个，**不含 `149/153/157/161`（UNII-3）**：
     * 后者在部分车机固件的监管域（`country_code`）里是关闭的。
     */
    val CHANNEL_CANDIDATES_5G: List<String> = listOf("36", "40", "44", "48")

    /**
     * 自建热点实际尝试的**信道顺序**。
     *
     * ## 2026-10-07 起：只有 5 GHz
     *
     * 车主口径：**默认且仅用 5 GHz**，不做频段自动选择，不再退 2.4 GHz。
     * 顺序取 UNII-1 的 `36 → 40 → 44 → 48`（与参考实现同频段，从 36 起）。
     *
     * ## 顺序的含义
     *
     * [CarAdbHotspotPlanner] 取第一项作为**首选**；
     * `CarAdbHotspotManager` 在首选信道起不来时**按同一张表逐个再试** ——
     * 某个信道被监管域（`country_code`）或驱动拒绝时，换一个是有意义的。
     * 全部失败即热点起不来（不再有 2.4 GHz 退路，这是车主要求的取舍）。
     *
     * ## 回退办法
     *
     * 若要恢复 2.4 GHz 兜底：把 [CHANNEL_CANDIDATES_2G] 拼到本列表**后面**
     * 即可（`hw_mode` / `ieee80211ac` 会按信道自动推导，无需其他改动）。
     */
    val CHANNEL_CANDIDATES: List<String> = CHANNEL_CANDIDATES_5G

    /**
     * 最终一个都挑不出来时的兜底信道：`36`（5 GHz 的第一个可用信道）。
     *
     * ⚠️ 2026-10-07 由 `1` 改为 `36`：本产品只走 5 GHz，兜底也必须留在 5 GHz，
     * 否则会写出 `hw_mode=g` + `channel=1` 这种与"仅 5 GHz"口径矛盾的自启配置。
     */
    const val DEFAULT_CHANNEL: Int = 36

    /**
     * 该信道是否属于 5 GHz。
     *
     * 分界线取 14：2.4 GHz 是 1~14，5 GHz 从 32 起（`36` 是第一个可用信道）。
     *
     * 频段**不是独立参数**，只能由信道推导——它同时决定 [hwModeOfChannel]
     * 与要不要带 `ieee80211ac=1`（VHT 是 5 GHz 专属；2.4 GHz 下带上它，
     * `hostapd` 会因为"网卡在该频段不具备 VHT 能力"退出）。写成两个字段必然漂移。
     */
    fun isFiveGigahertz(channel: Int): Boolean = channel > 14

    /**
     * 信道 → `hostapd` 的 `hw_mode`。
     *
     * `a` = 5 GHz（802.11a/ac），`g` = 2.4 GHz（802.11b/g）。
     *
     * ⚠️ **必须与信道自洽**：`hw_mode=g` + `channel=36` 会让 `hostapd`
     * 直接拒绝启动。所以这里只从 [isFiveGigahertz] 推导，不接受外部传入。
     */
    fun hwModeOfChannel(channel: Int): String = if (isFiveGigahertz(channel)) "a" else "g"

    // -------------------------------------------------------------- 设备路径

    /**
     * `hostapd` 的候选路径，按优先级探测。
     *
     * 参考实现用的是车机 vendor 分区里的 `/vendor/bin/hw/hostapd`，但不同 ROM
     * 把它放在不同地方（AOSP 在 `/apex/com.android.wifi/bin/hostapd`）。
     * 所以启动前逐个 `ls` 探测，取第一个存在的。
     */
    val HOSTAPD_PATH_CANDIDATES: List<String> = listOf(
        "/vendor/bin/hw/hostapd",
        "/vendor/bin/hostapd",
        "/system/bin/hostapd",
        "/apex/com.android.wifi/bin/hostapd",
        "/system_ext/bin/hostapd",
    )

    /**
     * 热点配置文件在设备上的落点候选，按优先级探测。
     *
     * ## ⚠️ 为什么**不含** `/data/softap.conf`（2026-09-30 实车修正，根因）
     *
     * 参考实现（`FUCK-Lynk&Co-ADB.apk`）写到 `/data/softap.conf`，本工程早期照抄了
     * 它，把它排在第一位。实车 `ps -A` + `/proc/<pid>/cmdline` 证明**这个路径上
     * 有一个 hostapd 正在运行**：
     *
     * ```
     * wifi  11487  5327  … hostapd
     *   cmdline: /vendor/bin/hw/hostapd /data/softap.conf
     * ```
     *
     * 也就是说它不是"一块可以随便用的空地"，而是**一张正在被别人读着的运行配置**。
     *
     * 后果（这就是 2026-09-30 那次"封面能投、音视频上不来、卡在无线通道就绪"的根因）：
     * 我们把自建热点的配置写进去，等于**覆盖了别人那张 AP 的凭据**。于是
     *
     * - 车机**存储**的热点配置仍是 `Lynk`（`getWifiApConfiguration()` 读到的值，
     *   也是上游 `ManualHotspotManager` 校验、并最终发给 iPhone 的那个名字）；
     * - 而网卡**实际广播**的变成了写进去的 `car`；
     * - `hostapd` 只在启动时读一次配置，改名不会让它重启 ⇒ 两者**长期漂移**，
     *   且漂移方向永远是"发给 iPhone 的名字没人广播"。
     *
     * ⇒ **绝不写别人的配置文件**。自建热点只写属于我们自己的路径。
     *
     * ## 候选为什么是这两个
     *
     * 1. `/data/local/tmp/…`：SELinux 标签是 `shell_data_file`，shell 可写、
     *    hostapd 可读，且**是"我们的"目录**，不会撞上任何厂商配置；
     * 2. `/data/misc/wifi/…`：`wifi` uid 的目录，hostapd 以 `wifi` 身份运行时
     *    同 uid 可读，作为 SELinux 更严的 ROM 上的备选。
     *
     * 文件名一律带 `dbplay-` 前缀，是为了让现场一眼看出"这是我们的文件"——
     * 而不是又一次把它误认成车机自己的热点配置。
     */
    val SOFTAP_CONF_CANDIDATES: List<String> = listOf(
        "/data/local/tmp/dbplay-softap.conf",
        "/data/misc/wifi/dbplay-softap.conf",
    )

    // -------------------------------------------------------------- ADB

    /**
     * 自己起的 adb server 端口。
     *
     * 必须避开系统 `adbd` 的 `5555`，否则会去抢系统 adb server 的 socket。
     * 参考实现用 5038，保留。
     */
    const val ADB_SERVER_PORT: Int = 5038

    /**
     * **DBPlay 自己开的** adbd 端口（`service.adb.tcp.port`）。
     *
     * ## 为什么是 5556 而不是 5555（2026-10-05 定案，斌哥口径）
     *
     * `5555` 是**系统 adbd 的口**——车机厂商固件自己就占着它（
     * 实车日志可见 `persist.adb.tcp.port=5555`、`init.svc.adbd=running`，
     * 且系统侧有独立的 ADB 引导组件在反复操作 adbd）。我们若也去写 `5555`，
     * 就是**跟厂商抢同一个口**：厂商组件一旦重启/改写属性，我们的 ADB 通道
     * 就被掐断，换一台车（厂商组件版本不同）更是完全不可控。
     *
     * `5556` 是**我们自己的专用口**：拿到 root（见 `AdbShellRunner.requestAdbRoot`）
     * 之后由 DBPlay 显式 `setprop service.adb.tcp.port 5556` + `ctl.start adbd` 拉起，
     * 与厂商的 5555 井水不犯河水。这样"每台同车型都能开"才成立——
     * 同车型（CS11 / 新龙鹰家族）都是 userdebug 固件，`adb root` 通吃，
     * 提到 root 后就能把 adbd 固定到我们自己的 5556。
     *
     * 连不上的老车仍有 [DEVICE_ADB_PORT_CANDIDATES] 逐个兜底（其中含 5555）。
     */
    const val DEVICE_ADB_PORT: Int = 5556

    /**
     * 车机 adbd 端口的**候选集**，按优先级（2026-10-05 加，为"每台车都能连"）。
     *
     * ## 为什么不能只认 5555
     *
     * 参考实现与早期版本都写死 `5555`（AOSP 的 `service.adb.tcp.port` 默认值）。
     * 但这只是个**约定**，不是协议：厂商固件完全可以把 adbd 挪到别的端口
     * （5037 是 adb server 的旧默认、有些车机用 5556~5560、MTK/展锐方案见过 5566）。
     * 写死 5555 的车机一旦不是这个值，`adb connect 127.0.0.1` 就**永远连不上**，
     * 表现是"这台车怎么也开不了热点"——而日志里只有一句 `failed to connect`。
     *
     * ## 用法（顺序很重要）
     *
     * 1. **先读系统属性** `service.adb.tcp.port`（[ADB_PORT_PROPERTY]），拿到什么用什么
     *    —— 这是唯一权威值，读到了就不必猜；
     * 2. 读不到（属性空 / 非 root 读不到）才按本列表逐个试，**`5556` 排第一**
     *    （我们自己的专用口，见 [DEVICE_ADB_PORT]），`5555` 次之（系统 adbd 的口）。
     *
     * ⚠️ **不要**把 `5037` 放进候选：那是 *adb server* 的老默认端口，撞上会把
     * 我们的 `-P 5038` server 与系统 adb server 混在一起。
     */
    val DEVICE_ADB_PORT_CANDIDATES: List<Int> = listOf(5556, 5555, 5557, 5558, 5560, 5566)

    /** 读 adbd 真实端口的系统属性名。 */
    const val ADB_PORT_PROPERTY: String = "service.adb.tcp.port"

    /**
     * adbd 端口的**持久化**属性名（2026-10-06 加）。
     *
     * ## 为什么必须同时写它
     *
     * [ADB_PORT_PROPERTY]（`service.adb.tcp.port`）是**瞬态**属性——它是 init 的
     * `service` 命名空间，**重启即丢**。所以只写它的后果是：
     * 「这次开好 5556、车机一重启，adbd 又回到厂商的 5555（或根本没开）」
     * ⇒ 用户下次上车又要从头走一遍开 ADB 的流程。
     *
     * `persist.*` 是 Android 里唯一**有持久化语义**的属性前缀：init 会把它们
     * 存进 `/data/property/`，开机时**在所有 `init.rc` 之前**回填。
     * 因此写 `persist.adb.tcp.port` 等于把"adbd 该听哪个口"钉进 ROM 记忆里，
     * 重启后由系统自己把 `service.adb.tcp.port` 恢复成同一个值。
     *
     * ## 与厂商的关系（为什么安全）
     *
     * 实车（CS11）系统侧的持久化属性是 `persist.adb.tcp.port=5555`
     * —— 厂商用它来表达"我就用 5555"。我们写的是**同一个 key**，
     * 所以这不是"再抢一个口"，而是**把车机自己的持久口改成我们商定好的 5556**：
     * 车机重启后 init 回填 5556，adbd 也按 5556 起，全车只有一套口径，
     * 不存在两套配置打架。这也是 [DEVICE_ADB_PORT] 选 5556 的逻辑延续。
     *
     * ⚠️ **写入需要 root**（`persist.*` 的 selinux 标签是 `persist_prop`，
     * 普通应用无权写）。调用点 [AdbShellRunner.switchToDedicatedPort] 本身
     * 就要求调用方先拿到提权前缀，所以这里不额外加权限门控。
     */
    const val ADB_PORT_PROPERTY_PERSIST: String = "persist.adb.tcp.port"

    /**
     * Flyme/Ecarx 一脉车机**自家**的 ADB 持久属性名（2026-10-15 加）。
     *
     * ## 为什么还需要第三条
     *
     * 实车（CS11）开机快照同时存在两条持久属性：
     * `persist.adb.tcp.port=5556`（我们写的、AOSP 名）与
     * `persist.adb.tcpip=5555`（厂商自有的名字），而开机后 adbd 听的是**厂商口**
     * ⇒ 这 ROM 的开机链路读的不是 AOSP 那条，而是厂商这条。
     * 所以只写 [ADB_PORT_PROPERTY_PERSIST] 治不了本，必须把厂商这条也写成 5556 并读回。
     *
     * ⚠️ 这是**推断**（系统日志没拍到开机段、没抓到是谁写回的），
     * 写它是最低成本的实验：可回滚、读回可验证；不生效也由自愈兜底。
     */
    const val ADB_VENDOR_TCPIP_PROPERTY: String = "persist.adb.tcpip"

    /**
     * 自建热点"专用口自愈"的**冷却窗口**（毫秒，2026-10-15 加）。
     *
     * 自愈会 `ctl.stop/ctl.start adbd`，会掐断此刻任何在厂商口上的连接；
     * 但厂商组件又会把 adbd 打回厂商口 —— 所以既不能"一进程只修一次"
     * （那会漏掉厂商的回打），也不能在重连循环里**每一轮都修**（等于反复重启 adbd）。
     * 冷却窗口就是在这两者之间划一条线：同窗口内只修一次，窗口过后口又不对就再修。
     */
    const val ADB_REPAIR_COOLDOWN_MILLIS: Long = 15_000L

    /**
     * ADB 专用口**看门狗**的巡检周期（毫秒，2026-10-15 加）。
     *
     * 每周期只读一条瞬态属性（毫秒级、无副作用），发现专用口不是 5556 才把
     * 修复动作交给 [ADB_REPAIR_COOLDOWN_MILLIS] 限速的修复路径。
     * 周期刻意压到 30 秒：足够在"车机重启 / 厂商把 adbd 打回厂商口"之后、
     * 用户点连接**之前**把口修回来，又不会高频重启 adbd。
     */
    const val ADB_WATCHDOG_PERIOD_MILLIS: Long = 30_000L


    /** adbd 运行状态的系统属性名（`init.svc.adbd`，值为 `running`）。 */
    const val ADB_STATE_PROPERTY: String = "init.svc.adbd"

    /**
     * 随包的 busybox 与 adb 在 `jniLibs` 里的文件名。
     *
     * ⚠️ 必须叫 `lib*.so` 且放在 `jniLibs/<abi>/`：这样它们会落到
     * `/data/app/…/lib/arm/`，SELinux 标签是 `apk_data_file`（**可执行**）。
     * 若按参考实现那样释放到 `filesDir`，在 targetSdk ≥ 29 的设备上会被
     * `app_data_file` 的 `execute` 禁令挡掉（见 `ADB-HOTSPOT.md` §2.2 / §3.2）。
     */
    const val ADB_LIBRARY_NAME: String = "libdbplayadb.so"

    const val BUSYBOX_LIBRARY_NAME: String = "libdbplaybusybox.so"

    /**
     * busybox 在设备上的**部署路径**（由 adb shell 从 lib 目录拷过来）。
     *
     * ⚠️ **这一步不是可选的**，原因在文件名上：
     * busybox 是 multi-call 二进制，它靠 `argv[0]` 的 basename 决定跑哪个 applet。
     * 随包文件必须叫 `lib*.so`（AGP 只把 `lib*.so` 当原生库打包），
     * 于是 `argv[0]` 是 `libdbplaybusybox.so` ⇒ busybox 去找一个叫
     * `libdbplaybusybox.so` 的 applet ⇒ **`applet not found`**，
     * 连 `busybox udhcpd …` 这种显式指定 applet 的写法也不认（v1.28 的行为：
     * `argv[0]` 不是 `busybox` 就不再看 `argv[1]`）。
     *
     * 已实测：`libdbplaybusybox.so --list` / `libdbplaybusybox.so udhcpd --help`
     * 一律 `applet not found`；`cp` 成 `busybox` 之后 `udhcpd --help` 正常。
     *
     * `/data/local/tmp` 是 `shell_data_file`，**允许执行**，且 shell 可写。
     */
    const val REMOTE_BUSYBOX_PATH: String = "/data/local/tmp/busybox"

    /** 应用私有目录里生成的两份配置文件名（相对 `filesDir`）。 */
    const val LOCAL_SOFTAP_CONF: String = "softap.conf"

    const val LOCAL_UDHCPD_CONF: String = "udhcpd.conf"

    /**
     * `udhcpd` 的租约文件落点。
     *
     * ⚠️ **必须显式指定，不能用它的编译期默认值**：busybox `udhcpd` 打不开租约文件
     * 时**会直接退出**，而默认值是 `/var/lib/misc/udhcpd.leases` —— Android 上
     * 这个目录不存在。参考实现（`FUCK-Lynk&Co-ADB.apk`）不写这一行还能跑通，
     * 说明它那份 busybox 的默认值恰好可用，**那是它的运气**。
     *
     * 选 `/data/` 根目录的理由：`udhcpd` 是以 root 跑的（`su 0`），
     * 该目录可写；且不在应用私有目录里，避免 targetSdk ≥ 29 的 `app_data_file`
     * 限制（`udhcpd` 是 native 进程，不受应用沙箱约束，但少一层不确定更好）。
     */
    const val REMOTE_LEASE_FILE: String = "/data/udhcpd.leases"

    /**
     * 唯一被验证过的 ABI。两个二进制都是 32 位 ARM 静态链接。
     */
    const val REQUIRED_ABI: String = "armeabi-v7a"

    /** 配置文件的权限。hostapd 会以 root 读它，0644 足够。 */
    const val REMOTE_CONF_MODE: String = "0644"

    // -------------------------------------------------- 开机自启（内置安装）

    /**
     * 开机自启脚本在车机上的安装路径。
     *
     * ## 为什么装在 `/system/bin` 而不是 App 私有目录
     *
     * 脚本必须由 **init 在开机时执行**，而那一刻 App 的数据目录还没解密、
     * App 进程也可能被 ROM 拦掉。`/system/bin` 是 init 能直接访问的位置，
     * 且随系统分区一起在开机最早阶段就可读。
     *
     * ⚠️ 写它要求 `/system` 可 remount rw —— 由 `CarAdbHotspotBootInstaller`
     * 先探测再写，探不到就**整步跳过、不改任何系统文件**（对老车零风险）。
     */
    const val BOOT_SCRIPT_PATH: String = "/system/bin/dbplay-hotspot.sh"

    /**
     * init 服务定义文件的安装路径。
     *
     * `on property:sys.boot_completed=1` 触发 —— 这是 [BootReceiver] 收到不到
     * `BOOT_COMPLETED` 时唯一还能自动起来的机制（2026-10-06 实测车机
     * `carplay-boot` 标签零命中，即那个广播根本没送到 App）。
     */
    const val BOOT_RC_PATH: String = "/system/etc/init/dbplay-hotspot.rc"

    /**
     * 开机自启脚本用的 softap 配置落点。
     *
     * ⚠️ **绝不能用 `/data/softap.conf`**：实车 `ps -A` 证明车机自己的
     * hostapd（`wifi` uid）正在读那个文件，覆盖它会让「存储的 SSID」与
     * 「实际广播的 SSID」长期漂移（2026-09-30 事故根因，详见
     * [SOFTAP_CONF_CANDIDATES] 的注释）。
     */
    const val BOOT_SOFTAP_CONF: String = "/data/local/tmp/dbplay-softap.conf"

    const val BOOT_UDHCPD_CONF: String = "/data/local/tmp/dbplay-udhcpd.conf"

    const val BOOT_LOG_PATH: String = "/data/local/tmp/dbplay-hotspot-boot.log"

    /**
     * 开机自启的**落盘开关**（Android 属性，随 `/data/property` 持久化）。
     *
     * 斌哥口径：「热点是永久开启，跟随车机自启动；关掉热点是永久关闭」。
     * 所以设置页关闭热点时把它置 `0`，开机脚本第一步检查它 ——
     * **为 0 就直接退出，不建热点**。这就是"关掉后重启也不回来"的机制。
     */
    const val BOOT_ENABLED_PROPERTY: String = "persist.dbplay.hotspot.boot"

    /**
     * App 每次成功建立热点后写这个属性的值。
     *
     * 写 `1` 的时机：`setupHotspotAndAdbNow()` 成功 + 安装自启服务成功
     * （或已装过）。用户点「关闭热点」时写 `0`。
     */
    const val BOOT_ENABLED_ON: String = "1"

    const val BOOT_ENABLED_OFF: String = "0"

    // ---------------------------------------- 共享 adb 通道的 owner 标签（改动 C）

    /**
     * `AdbShellSession` 的 owner 记账标签（§5 改动 C，2026-10-15）。
     *
     * ⚠️ 标签是**跨模块**的实参（car:api 的常量 → car:core-bridge 的调用点），
     * 各写一遍字面量时"少写一个字符"不会编译报错、只会让 `close()` 注销不掉
     * ⇒ 最后一个 owner 退不出去、adb server（5038）收不掉。所以收敛到契约常量。
     */

    /** 用户在设置页开着「允许内置 ADB」时的长期 owner。 */
    const val OWNER_USER_ADB: String = "user-adb"

    /** 自建热点在跑期间持有的 owner（拉起成功处登记，`tearDown()` 收尾时撤）。 */
    const val OWNER_HOTSPOT: String = "hotspot"

    /** 车机原生热点探测这条有界链路持有的 owner。 */
    const val OWNER_PROBE: String = "probe"

    /** 在线更新（静默安装）这条有界链路持有的 owner。 */
    const val OWNER_UPDATE: String = "update"

    /** 设置页自检 / 手动自愈这条有界链路持有的 owner。 */
    const val OWNER_SELFCHECK: String = "selfcheck"
}

/**
 * 车机侧 shell 命令的**"无害非零退出码"**判据。
 *
 * ## 为什么需要它
 *
 * 参考实现（`FUCK-Lynk&Co-ADB.apk`）的 `Adbtool` 只读输出、**不看退出码**
 * （见 `ADB-HOTSPOT.md` §2.4），所以下面这些"重复操作"的报错它全都无声跳过。
 * 我们逐条判退出码，于是把**无害的重复操作**当成了致命错误，整条热点链放弃
 * ⇒ 回落 `LocalOnlyHotspot` ⇒ 这台车机的 LOH 回调不来 ⇒ 60 秒超时 ⇒ LOH 中毒。
 * 现象就是"**第一次连得上，之后再也起不来**"。
 *
 * ⇒ 把"哪些报错其实等价于成功"显式列出来，并配单测钉死。
 *
 * ## 为什么做成纯函数
 *
 * 这几条判据藏在 `CarAdbHotspotManager` 里时**没法单测**（那一层要
 * `Context` + 真机 adb）。抽成纯字符串判定后，规则本身就有了回归测试，
 * 而调用点只剩"要不要用它"这一个决定。
 */
object CarAdbShellQuirks {

    /**
     * `ip rule add` / `ip addr add` 的 `RTNETLINK answers: File exists`。
     *
     * 含义是"要加的东西已经在里面了"——与"加成功"**结果等价**。
     * 触发条件：上一次没有走到 `tearDown`（进程被杀、会话异常结束），
     * `ip rule del` / `ip addr del` 没执行，残留留到了下一次拉起。
     *
     * ⚠️ 只认这一句。其它 `RTNETLINK` 报错（`Network is unreachable`、
     * `Operation not permitted`）**必须继续算失败**——那些是真的没配上。
     */
    fun isBenignIpConflict(output: String): Boolean =
        output.contains("File exists", ignoreCase = true)
}

/**
 * `adb devices` 输出的解析。
 *
 * ## 为什么单独成对象
 *
 * 与 [CarAdbShellQuirks]（"哪些非零退出码无害"）是两件事：那个判"命令算不算成功"，
 * 这个判"连上的是哪台设备"。混在一个对象里以后必然有人往错的那边加东西。
 *
 * ## 为什么放在 `:car:api`
 *
 * 它是**纯字符串解析**，与进程管理、`Context` 都无关。留在
 * `AdbShellRunner`（要 `Context`）里就只能靠上车试；抽出来之后判据本身有单测。
 */
object CarAdbDeviceList {

    /**
     * 从 `adb devices` 的输出里挑出**可用的设备序列号**。
     *
     * 输出形如：
     * ```
     * List of devices attached
     * 127.0.0.1:5555	device
     * emulator-5554	offline
     * ```
     *
     * ## 只认 `device` 状态
     *
     * `offline`（握手没完成）与 `unauthorized`（adbd 要求授权）都**不能**当成连上了
     * ——用它们当 `-s` 的值，后续每条 `shell` 都会失败，日志里却只看到"连上了"。
     *
     * ⚠️ 但也正因为 `offline` 是**瞬时**状态，调用方在拿到 null 时应当**重试**
     * 而不是立刻判失败（见 `AdbShellRunner.connect`）：参考实现**调了**
     * `devices -l`，但它的返回值只被 `StringBuilder.append` 拼进日志字符串，
     * **没有任何 `if` 判断**（字节码见 `ADB-HOTSPOT.md` §3.8.5）。我们把它当闸门，
     * 就必须给它一点时间。
     *
     * ## 为什么必须优先 loopback，不能取"第一行"
     *
     * 自建 adb server 的 `devices` 输出里**可能有别的设备**：本项目实测在测试机上
     * 除了 `127.0.0.1:5555` 还列出了 `emulator-5554`。取第一行可能拿到别人，
     * 之后所有 `-s <错序列号> shell` 都会去操作一台**不相干**的设备——
     * 而我们要执行的是 `ifconfig` / `killall hostapd` / `ip rule add`，
     * **打错设备比直接失败危险得多**。
     *
     * 这也解释了参考实现里 `-s 127.0.0.1` 那种写法为什么不可靠：
     * `connect` 注册的序列号是 `127.0.0.1:5555`（带端口），
     * 能不能命中完全取决于 adb 对"主机名匹配 host:port"的宽容度。
     *
     * @param loopbackSerial `connect` 用的那个目标的完整序列号（如 `127.0.0.1:5555`）。
     * @return 可用序列号；null = 一行 `device` 都没有。
     */
    fun pickSerial(output: String, loopbackSerial: String): String? {
        val attached = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val parts = line.split('\t', ' ').filter { it.isNotEmpty() }
                if (parts.size < 2) null else parts[0] to parts[1]
            }
            .filter { (_, state) -> state == "device" }
            .map { it.first }
            .toList()
        if (attached.isEmpty()) return null

        val host = loopbackSerial.substringBefore(':')
        return attached.firstOrNull { it == loopbackSerial }
            ?: attached.firstOrNull { it.startsWith("$host:") }
            ?: attached.first()
    }

    /**
     * `adb connect` 的输出里有没有"已连上"的标志。
     *
     * 判据与参考实现**逐字一致**：它对 `connect` 的返回值判
     * `output.contains("connected")`（字节码 `invoke-virtual String.contains`，
     * 见 `ADB-HOTSPOT.md` §3.8.5）。两种情况都命中：
     *
     * - `connected to 127.0.0.1:5555`
     * - `already connected to 127.0.0.1:5555`（重复连接时 adb 的实际输出）
     *
     * 而 `failed to connect to 127.0.0.1:5555` **不命中**——`connect to` ≠
     * `connected`，所以这个判据能区分成功与失败，不是"含 connect 就算过"。
     */
    fun looksConnected(connectOutput: String): Boolean = connectOutput.contains(CONNECTED_MARKER)

    /**
     * 解析不到序列号时的**兜底序列号**：直接用 `127.0.0.1`（不含端口）。
     *
     * ## 为什么兜底值长这样
     *
     * 这正是参考实现的写法：它从头到尾 `-s 127.0.0.1`，**从不解析序列号**
     * （`Adbtool.command` 把 `"127.0.0.1"` 直接拼进 `-s ` 后面）。
     * adb 对 `-s <host>` 会自动补默认端口 `5555`，所以能命中注册成
     * `127.0.0.1:5555` 的那台设备。
     *
     * ## 为什么需要它（这是"不比参考更严"的兜底）
     *
     * [pickSerial] 是**我们发明**的判据，参考没有。重试若干次仍读不到
     * `device` 行时，若因此判失败，我们就又一次"比参考更严"——参考会继续往下走。
     * 所以这里退回参考的做法：给它一个序列号，让后面的命令自己去报错。
     *
     * ⚠️ 但**只在真的解析不到时**才用。能解析到就用解析结果，因为
     * [pickSerial] 会优先挑 loopback、排除 `emulator-*` 之类的无关设备——
     * 用错序列号去跑 `ifconfig` / `killall` 比直接失败危险得多。
     */
    fun referenceSerial(loopbackSerial: String): String = loopbackSerial.substringBefore(':')

    /** `adb connect` 成功时输出里的标志（参考实现逐字用的就是这个）。 */
    private const val CONNECTED_MARKER = "connected"
}

/**
 * 「自建热点起没起来」的**唯一判据**。
 *
 * ## 为什么是这四项、少一项都不行
 *
 * 参考实现（`FUCK-Lynk&Co-ADB.apk`）在字节码层面的做法是：7 条 `su 0` 配置命令
 * **全都不看退出码**，最后只判一句
 * `/system/bin/ifconfig <网卡>` 的输出里有没有 `UP`。
 *
 * 我们照它的思路走，但把判据做得更严一点——因为它的判据有两个真实漏洞：
 *
 * 1. `ifconfig` 里的 `UP` 只说明**网卡是启用的**，不说明 `hostapd` 和 `udhcpd`
 *    还活着。曾经出现过的现象正是"hostapd 已经退出、地址还在、udhcpd 还活着"
 *    ⇒ 判成就绪 ⇒ 会话继续 ⇒ **iPhone 根本搜不到 SSID**，而日志里只有一句
 *    "热点已就绪"。
 * 2. `ifconfig` 也**不说明这张网卡上广播的是谁的 SSID**。2026-09-30 实车踩到：
 *    hostapd 起了、udhcpd 起了、地址也配上了，三项全真 ⇒ 判成就绪，
 *    而 `iw dev` 里 `wlan0` 广播的仍是**别人的** `car`（我们配的是 `Lynk`）
 *    ⇒ 我们把 `Lynk` 发给了 iPhone，而它**没有被广播** ⇒ iPhone 永远关联不上
 *    ⇒ 界面停在「无线通道就绪」，**整份诊断包里没有一句报错**。
 *
 * 所以这里要求四项同时成立：
 *
 * | 条件 | 缺了会怎样 |
 * |---|---|
 * | `hostapdAlive` | 没有 AP ⇒ iPhone 搜不到 SSID（最难查的一类） |
 * | `dhcpAlive` | 有 AP 但拿不到 IP ⇒ iPhone 关联后停在"获取地址中" |
 * | `addressConfigured` | 有 AP 有 DHCP 但没有网关地址 ⇒ 拿到 IP 也 ping 不通 |
 * | `noForeignApOnInterface` | 广播的是别人的名字 ⇒ 我们发给 iPhone 的那个名字没人广播 |
 *
 * ## ⚠️ 第四项是**三态**的，读不到不算"有别人的 AP"
 *
 * `iw dev` 在部分厂商 ROM 上不打印 `ssid` 行，或网卡还没起信标时读不到。
 * **不能凭"读不到"就否决**——那正是本工程反复栽跟头的"自造判据比参考更严"。
 * 所以第四项只表达"**明确读到**了别人的名字"，调用方必须把"读不到"折算成
 * `true`（放行）后再传进来。判定与折算的写法见 `CarAdbHotspotManager`。
 *
 * ## 为什么做成纯函数
 *
 * 这段判断原先内联在 `CarAdbHotspotManager` 里，那一层要 `Context` + 真机 adb
 * ⇒ **没法单测**。抽出来之后，"少一项就不算就绪"这条规则有了回归测试，
 * 调用点只剩"四项从哪来"。
 */
object CarAdbHotspotReadiness {

    /**
     * @param hostapdAlive `hostapd` 进程在跑（启动命令返回 0 或超时未退出）。
     * @param dhcpAlive `busybox udhcpd` 进程在跑。
     * @param addressConfigured 网卡上确实出现了我们配的地址（`ifconfig` 输出里
     *        含 [CarHotspotNetwork.hostAddress]）。
     * @param noForeignApOnInterface 网卡上**没有**别人的 AP：`iw dev` 读到的实际
     *        广播名等于我们配的那个，**或者读不到**（读不到不算否决，见 KDoc）。
     */
    fun isReady(
        hostapdAlive: Boolean,
        dhcpAlive: Boolean,
        addressConfigured: Boolean,
        noForeignApOnInterface: Boolean,
    ): Boolean =
        hostapdAlive && dhcpAlive && addressConfigured && noForeignApOnInterface

    /**
     * 没就绪时的一句话原因，直接进日志/自检页。
     *
     * 四项逐项点明"在跑 / 已退出"、"已配上 / 没配上"、"没有别人的 AP / 被占着"，
     * 避免只看到一句"热点没起来"却不知道差在哪一项。
     */
    fun explain(
        hostapdAlive: Boolean,
        dhcpAlive: Boolean,
        addressConfigured: Boolean,
        noForeignApOnInterface: Boolean,
    ): String = "hostapd=" + (if (hostapdAlive) "在跑" else "已退出") +
        "，DHCP=" + (if (dhcpAlive) "在跑" else "已退出") +
        "，地址=" + (if (addressConfigured) "已配上" else "没配上") +
        "，网卡=" + (
            if (noForeignApOnInterface) {
                "广播的是我们的 SSID"
            } else {
                "被别人的 AP 占着（广播的不是我们要的 SSID）"
            }
            )
}

/**
 * /24 网段的纯计算工具。
 *
 * 放在 `:car:api` 是因为两边都要用：探测层要**判断**"某张网卡的地址属于哪个网段"
 * （用来避开冲突），自建层要**推导**网关与 DHCP 池。写成一份避免两处口径不一致。
 */
object CarHotspotSubnet {

    /**
     * `192.168.175.0/24` → `192.168.175.1`。
     *
     * @return null 表示入参不是合法的 `/24` 网段写法。
     */
    fun hostAddress(subnet: String): String? = withNetwork(subnet) { it + ".1" }

    /** `192.168.175.0/24` → `192.168.175.2`。 */
    fun dhcpStart(subnet: String): String? =
        withNetwork(subnet) { "$it.${CarAdbHotspotDefaults.DHCP_HOST_START}" }

    /** `192.168.175.0/24` → `192.168.175.254`。 */
    fun dhcpEnd(subnet: String): String? =
        withNetwork(subnet) { "$it.${CarAdbHotspotDefaults.DHCP_HOST_END}" }

    /** `192.168.175.0/24` → `24`；非法返回 null。 */
    fun prefixLength(subnet: String): Int? =
        subnet.substringAfterLast('/', "").toIntOrNull()?.takeIf { it in 0..32 }

    /**
     * `192.168.43.7` + 24 → `192.168.43.0/24`。
     *
     * 用于把探测到的网卡地址归到它所属的网段，从而判断候选网段是否已被占用。
     */
    fun networkOf(address: String, prefix: Int): String? {
        val octets = octets(address) ?: return null
        if (prefix != 24) return null
        return "${octets[0]}.${octets[1]}.${octets[2]}.0/24"
    }

    /** 取地址的前三段（`192.168.43.7` → `192.168.43`）；非法返回 null。 */
    fun firstThreeOctets(address: String): String? {
        val octets = octets(address) ?: return null
        return "${octets[0]}.${octets[1]}.${octets[2]}"
    }

    /**
     * 两个地址是否落在同一个 /24。
     *
     * 判断"候选网段是否与某张已有网卡冲突"用的就是它。
     */
    fun sameSlash24(a: String, b: String): Boolean {
        val left = firstThreeOctets(a) ?: return false
        val right = firstThreeOctets(b) ?: return false
        return left == right
    }

    private inline fun withNetwork(subnet: String, build: (String) -> String): String? =
        firstThreeOctets(subnet.substringBefore('/'))?.let(build)

    private fun octets(address: String): List<Int>? {
        val parts = address.trim().substringBefore('/').split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        if (values.any { it !in 0..255 }) return null
        return values
    }
}

/**
 * 自建热点实际采用的一组网络参数。
 *
 * 这是**探测/选择的结果**，不是常量：每次拉起热点时按车机当前环境算一次。
 * 把它单独成类型是为了让"我们到底配了哪张网、哪个网段、哪个信道"在日志与
 * 诊断里是一个可回显的完整对象，而不是散落在几处拼接的字符串。
 */
data class CarHotspotNetwork(
    val interfaceName: String,
    val subnet: String,
    val hostAddress: String,
    val prefixLength: Int,
    val dhcpStart: String,
    val dhcpEnd: String,
    val channel: Int,
    /** 人类可读的选择理由，进日志（"为什么是这张网卡 / 这个网段"）。 */
    val rationale: String,
)

/**
 * ADB 热点的执行阶段。
 *
 * ⚠️ **穷尽式 `when`，不写 `else`**：新增阶段时让编译失败，强制显式决策。
 */
enum class CarAdbHotspotPhase {
    IDLE,

    /** 校验 ABI 与两个二进制是否可执行。 */
    CHECKING_BINARIES,

    /** 开通车机 ADB（`FxBridge` 写 `service.adb.tcp.port` 并拉起 `adbd`）。 */
    ENABLING_ADB,

    /** 起自己的 adb server 并 `connect 127.0.0.1`。 */
    CONNECTING_ADB,

    /** 探测 AP 网卡。 */
    PROBING_INTERFACE,

    /** 读现有网段/信道，挑一组不冲突的网络参数（网段 / 信道 / hostapd 路径）。 */
    SELECTING_NETWORK,

    /** 写配置、拷到设备、配地址、加路由规则。 */
    CONFIGURING,

    /** 起 hostapd。 */
    STARTING_HOSTAPD,

    /** 起 busybox udhcpd。 */
    STARTING_DHCP,

    RUNNING,
    FAILED,
    STOPPED,
}

/**
 * 自检/拉起过程中的一条步骤记录。
 *
 * 为什么要记这个：这套流程有 6 个车机相关的不确定点（见 `ADB-HOTSPOT.md` §4），
 * 盲写代码不可能一次成。把每一步的**原始命令输出**留在这里，上车跑一次自检
 * 就能定位到具体是哪一条挂的，而不是只看到一句"开热点失败"。
 */
data class CarAdbHotspotStep(
    /** 1 起的序号，与 `ADB-HOTSPOT.md` §3.6 的自检编号一致。 */
    val index: Int,
    val title: String,
    /**
     * 这一步是否**没有挡住流程**。
     *
     * ⚠️ 语义是"放行"而不是"一定做到了"：例如「开通车机 ADB」这一步，
     * 车机上没有 `android.fxbridge.FxBridge` 时会记 `ok = true` 并把
     * "跳过自动开通，请在车机设置里手动打开 ADB" 写进 [detail]——
     * 因为很多车机本来就默认开着 ADB，真正的判据是下一步能不能连上。
     * 把它记成失败会让自检页出现一堆"红叉"却仍能跑通，反而更难读。
     */
    val ok: Boolean,
    /** 原始输出或结论。**直接展示给用户，所以必须中文 + 不截断关键信息。** */
    val detail: String,
)

/**
 * ADB 热点的可观测状态。
 *
 * 与 `CarAdbHotspotHost` 分开的理由同 `CarUpdateState`：`:car:ui` 不依赖
 * `:car:core-bridge`，UI 只认这个纯数据类。
 *
 * ## 只放**真的有人读**的字段
 *
 * 这里刻意收得很窄。曾经有 `dhcpRange` / `hostapdPath` / `adbReady` / `rooted` /
 * `hostapdRunning` / `dhcpRunning` 六个字段，写得很全但**没有任何一处读它们**
 * ——因为同样的信息已经逐条记在 [steps] 里（"网络参数选择" / "定位 hostapd" /
 * "adb 通道" / "启动 hostapd" / "启动 DHCP"），而 [steps] 是会展示出来的。
 * 留着就是两份会漂移的真相，故删除。要加字段时请一并想清楚**谁来读**。
 */
data class CarAdbHotspotState(
    val phase: CarAdbHotspotPhase = CarAdbHotspotPhase.IDLE,
    /** 实际使用的 AP 网卡名，如 `wlan2`。空 = 还没探测出来。 */
    val interfaceName: String = "",
    /** 实际配置到网卡上的地址。**运行时选出来的**，不是常量。 */
    val hostAddress: String = "",
    /** 实际采用的网段，如 `192.168.176.0/24`。 */
    val subnet: String = "",
    /** 实际采用的信道。 */
    val channel: Int = 0,
    /**
     * 网卡上**别人的 AP** 的 SSID（`iw dev` 读到的、且不等于我们配的那个）。
     *
     * 非 null 表示"这张网卡现在不属于我们"：自建 hostapd 没能抢到它，网卡上广播
     * 的是别人的名字。此时**绝不能**把设置页那个 SSID 发给 iPhone——它没有被广播，
     * iPhone 必然关联不上，界面会停在「无线通道就绪」**且全链路无一句报错**
     * （2026-09-30 实车，见 `CarAdbHotspotReadiness` 的 KDoc）。
     *
     * 读它的人只有一个：`CarPlaySessionBridge.ensureAdbHotspot`——用它在"回落
     * LocalOnlyHotspot"之前刹一脚。理由：网卡被占着时 LOH 用同一张网卡，
     * 一定也起不来；而本车 Android 9 上 LOH **一次即永久中毒**，
     * 白等 60 秒还落下一个不可用的状态。
     */
    val foreignApSsid: String? = null,
    /** 一句话结论，进设置页提示与首页诊断。 */
    val message: String = "",
    val steps: List<CarAdbHotspotStep> = emptyList(),
) {
    /** 热点是否真的在跑（`CarAdbHotspotManager.ensureStarted` 的快路径判据）。 */
    val isRunning: Boolean get() = phase == CarAdbHotspotPhase.RUNNING

    /** 正在做耗时操作（设置页据此禁用按钮）。 */
    val isBusy: Boolean
        get() = phase != CarAdbHotspotPhase.IDLE &&
            phase != CarAdbHotspotPhase.RUNNING &&
            phase != CarAdbHotspotPhase.FAILED &&
            phase != CarAdbHotspotPhase.STOPPED

    /** 第一个失败的步骤，供"失败原因"一行直接显示。 */
    val firstFailure: CarAdbHotspotStep?
        get() = steps.firstOrNull { !it.ok }
}

/**
 * ADB 独立热点的宿主。
 *
 * ## 线程约定（与 `CarUpdateHost` 一致）
 *
 * 本工程全链路没有协程。`ensureStarted` / `selfCheck` 是**阻塞**的
 * （adb 交互 + hostapd 重试，实测秒级到十几秒），**只能在 worker 线程调用**；
 * `onState` 回调由实现投递回主线程。`state()` 可在任意线程读。
 */
interface CarAdbHotspotHost {

    fun state(): CarAdbHotspotState

    /**
     * 确保热点在跑。已跑则直接返回 true。
     *
     * @param network 已经选好的网络参数（网卡 / 网段 / 信道）。
     *        由 `CarHotspotResolver` 在解析热点方案时算好并放进
     *        `CarHotspotPlan.adbNetwork`——**必须传进来**，因为同一组
     *        参数还要用于给 iPhone 的 AirPlay 通告（`0x5703` 里带信道），
     *        两边不一致会让 iPhone 反复重试关联。
     *        传 null 时实现会自己算一份（只用于自检等非会话路径）。
     * @return false 表示**这一步没做成**，调用方应当回落其它热点方案，
     *         而不是把整个会话判失败——车机差异大，回落是正常路径而非异常。
     */
    fun ensureStarted(
        network: CarHotspotNetwork?,
        onState: (CarAdbHotspotState) -> Unit,
    ): Boolean

    /**
     * 只做自检，**不改系统状态**（不写配置、不 ifconfig、不起守护进程）。
     *
     * 供开发者页定位车机差异用。第 3 步（网卡探测）之后的分支全部只读。
     *
     * @param interfaceOverride 非空时跳过自动探测，直接用这个名字。
     */
    fun selfCheck(interfaceOverride: String, onState: (CarAdbHotspotState) -> Unit)

    /**
     * 是否允许**自动拉起**热点（2026-10-04）。
     *
     * 供 `CarPlaySessionBridge.ensureAdbHotspot` 在会话启动时问一句：
     * "现在允许自动拉起热点吗？"
     *
     * 斌哥口径：**"热点是永久开启，跟随车机自启动；关掉热点是永久关闭"**，
     * 所以判据就是持久化设置 `keepHotspotAlive`（真实现里经 gate 注入）。
     *
     * 默认 `true`（无状态实现、测试替身都不受影响）。做成默认方法而非
     * 抽象方法，是为了不让已有的测试替身与空实现被迫跟着改。
     */
    fun isAutoStartAllowed(): Boolean = true

    /**
     * 用户**手动关闭**热点（设置页按钮专用，2026-10-04）。
     *
     * 语义是**永久关闭**（斌哥口径）：调用方除了调本方法停硬件，
     * 还要把 `keepHotspotAlive` 落盘成 false，否则下次开机预热又会拉起来。
     *
     * 默认实现直接调 [stop]（无状态替身不受影响）。
     */
    fun stopByUser() = stop()

    /**
     * 收干净：`killall -s KILL hostapd busybox`、撤掉 `ip rule`、删掉我们加的地址。
     *
     * **非阻塞**——内部提交到 worker 线程。会话收尾路径上不能等它，
     * 那会把"返回上一页"卡住十几秒。
     *
     * ⚠️ **只动我们自己加的东西**。绝不触碰车机原有热点的配置，
     * 也不调用任何 `stopSoftAp` —— 那是车机的网络，不是我们的。
     * 特别注意**不能 `ifconfig <if> down`**：万一探测选中的网卡同时也是
     * 车机的上行，down 掉会直接掐断车机自己的网络。
     */
    fun stop()

    /**
     * 清掉「自建热点」**留在网卡上的地址**（如果确实还在），返回一句话结论；null = 没有记录。
     *
     * ## 为什么单独一个方法（2026-10-11，车主口径：各热点模式必须独立、互不影响）
     *
     * 四种热点模式共用同一张网卡，而 `stop()` 的清理依赖**内存**里的
     * `activeNetwork` —— 进程被杀 / 被系统回收后它没了，网卡上我们配过的地址却还在。
     * 之后切到「复用」时，那个地址会被当成"车机热点地址"通告给 iPhone：
     * 真车日志 08:57 `advertise SSID="Lynk" ip=192.168.176.1` → 40 秒 `attach timed out`。
     *
     * 本方法读**落盘**的足迹（`CarHotspotFootprintStore`），确认那个地址确实还在网上后，
     * 用 **ADB 工具**（`su 0 ip addr del`）把它删掉并验证。
     *
     * ⚠️ ADB 是本工程的**共享设施**，不属于任何一个热点模式 —— 复用/局域网等模式同样
     * 可以借它做只读探测或这种"扫干净上一个模式的地"的清理；这不违反"模式独立"，
     * 反而正是"模式独立"的实现手段。
     *
     * 默认实现返回 null（不支持的实现无需关心）；调用方**不得**因为它返回 null 就
     * 认为"已经干净" —— null 只表示"没有记录可查"。
     */
    fun releaseLeftoverAddress(): String? = null
}

/**
 * `killall` 退出码的折算规则（2026-10-06）。
 *
 * ## 为什么要单独抽出来
 *
 * `killall` 的语义是"**一个都没杀到就退出 1**"。而"清理网卡占用者"这一步最常见的
 * 正常情况恰恰是"网卡上本来就没有 hostapd"（首次开机、上一轮已清理干净）——
 * 此时 `exit=1` + `killall: hostapd: No such process` 是**预期结果**，不是失败。
 *
 * 原样记成失败有两个代价：
 * ① 0.10.7 诊断包里这一步**每次都是 FAIL**，成为"第一个失败步"的常客，
 *    读日志的人要先花时间排除它；
 * ② 它掩盖了真正的"第一个失败步"——诊断页与失败页都按 `firstFailure` 定位。
 *
 * ## 判据刻意收窄
 *
 * 只有"输出里出现 `No such process`、**且不含任何其它错误关键词**"才折算成成功。
 * 出现 `Operation not permitted` / `Permission denied` / `Invalid argument` 等
 * 说明是**真的**没权限或参数错，必须原样保留失败——
 * 把真实失败一起吞掉，正是本项目反复栽跟头的"判据过宽"。
 *
 * 抽成纯函数（放 `:car:api`）是为了能单测：`CarAdbHotspotManager` 要 Context，
 * 本模块没有 Robolectric。
 */
object CarKillallExitCode {

    /** 其它错误关键词：出现即**不折算**，保留原始失败。 */
    private val OTHER_ERROR_MARKERS = listOf(
        "not permitted",
        "permission denied",
        "invalid argument",
        "invalid option",
        "not found",
    )

    /**
     * 判断这次 `killall` 的结果是不是"没有可杀的目标"。
     *
     * @param exitCode 进程退出码；0 或超时不算"无目标"。
     * @param output stdout + stderr 合并文本。
     * @return true = 属于"网卡上本来就没有占用者"，应当按成功处理。
     */
    fun isNoTarget(exitCode: Int, output: String): Boolean {
        if (exitCode == 0) return false
        val lower = output.lowercase()
        if ("no such process" !in lower) return false
        return OTHER_ERROR_MARKERS.none { it in lower }
    }
}
