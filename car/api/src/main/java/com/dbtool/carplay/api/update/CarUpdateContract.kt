package com.dbtool.carplay.api.update

/**
 * 远程在线更新的契约。
 *
 * 服务端（`DBapi`）本来就有一套多产品更新通道，DBPlay 只需要接上去——
 * 本契约里的字段名、取值、路径都逐字对齐
 * `dbapi/interfaces/rest/app_update.py` 的 `/api/app/check_update` 与
 * `/api/app/update_status`，服务端**无需任何改动**。
 *
 * 为什么单独立一个包（而不是塞进 `settings`）：
 * 更新不是用户设置，它是一次**联网动作 + 一次安装动作**，
 * 生命周期横跨"检查 → 下载 → 安装"三个阶段，与设置草稿的语义完全不同。
 */

/**
 * 自动检查的最小间隔。
 *
 * 取 6 小时：车机一天点火很多次，每次启动都联网查版本既浪费流量
 * 也让服务端平白多出一堆请求；而新版本对车机来说不是紧急事件，
 * 半天内知道就够了。用户想立刻知道就手动点「检查更新」。
 */
const val UPDATE_AUTO_CHECK_INTERVAL_MILLIS: Long = 6L * 60 * 60 * 1000

/**
 * 更新所处的阶段。
 *
 * 穷尽式 `when` 消费这个枚举（不写 `else`），新增阶段时编译失败，
 * 强制在界面与控制器两处都做出显式决策。
 */
enum class CarUpdatePhase {
    /** 还没查过，或查完没有新版。 */
    IDLE,

    /** 正在向服务端查询。 */
    CHECKING,

    /** 查询完成，已是最新版本。 */
    UP_TO_DATE,

    /** 发现新版本，等待用户决定是否下载。 */
    AVAILABLE,

    /** 正在下载 APK。 */
    DOWNLOADING,

    /**
     * 安装包已下好，**在等一个空闲窗口**（后台静默更新专用，2026-10-11）。
     *
     * 只有静默开关打开时才会出现这个阶段。它与 [READY] 的区别是**"等谁"**：
     * - [READY] 等的是**用户**点「立即安装」；
     * - [PENDING_INSTALL] 等的是**会话空闲 ≥30 秒**（见 `CarSilentUpdatePolicy`）。
     *
     * 之所以要单独立一个阶段而不是复用 [READY]：装的是 DBPlay 自己，
     * 一旦动手就会杀进程重建，**绝不能**在用户正连手机时发生。
     * 状态分开才能让界面说清"已经下好了，等没连手机的时候自己装"。
     */
    PENDING_INSTALL,

    /** APK 已下载完成，等待安装。 */
    READY,

    /** 已把安装交给系统（静默提交或拉起安装器）。 */
    INSTALLING,

    /** 失败，[CarUpdateState.message] 是原因。 */
    FAILED,
}

/**
 * 远程更新的全部状态。
 *
 * 界面**只读**这个类型；所有推进都由 `CarUpdateHost` 在后台线程完成、
 * 经主线程回调写回。
 */
data class CarUpdateState(
    val phase: CarUpdatePhase = CarUpdatePhase.IDLE,

    /** 本机当前版本（来自 `PackageManager`，与「关于」页显示的是同一个值）。 */
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0L,

    /** 服务端发布的版本。 */
    val latestVersionName: String = "",
    val latestVersionCode: Long = 0L,

    /** 更新说明，逐条显示。 */
    val releaseNotes: List<String> = emptyList(),

    /**
     * 强制更新。
     *
     * 服务端有两种来源会置位：通道里的 `force_update`，
     * 以及"当前版本低于 `min_supported_version_code`"——后者意味着
     * 这个版本已经被服务端判定为不再支持（例如协议栈有安全缺陷），
     * 不是"建议升级"而是"必须升级"。
     */
    val forceUpdate: Boolean = false,

    /**
     * 服务端是否允许**后台自动更新**（后台静默下载 + 静默安装，用户不参与）。
     *
     * 由后台「应用更新」页的「允许后台自动更新」开关决定（`data/app_update/<app_id>.json`
     * 的 `auto_update`），随 `/api/app/check_update` 下发。
     *
     * ⚠️ 与 [forceUpdate] **正交**，别混：
     * - [forceUpdate] 管"用户能不能跳过"；
     * - 本字段管"用户要不要参与"。
     *
     * ⚠️ 默认 `false`：服务端不显式开启时，客户端**不得**自作主张静默安装 ——
     * 车机静默装的是它自己，推错包会让车队反复重启。
     */
    val autoUpdate: Boolean = false,

    /**
     * 本机用户是否开启了「后台自动更新」（「关于」页那颗开关，2026-10-11）。
     *
     * ⚠️ 与 [autoUpdate] **是两把不同的锁，缺一不可**：
     * - [autoUpdate] 是**服务端**的态度（"这个包允不允许无人值守地装"）；
     * - 本字段是**车主**的态度（"我这台车要不要参与后台自动更新"）。
     *
     * 只有两者都为真才静默：服务端没点头 ⇒ 客户端不得自作主张；
     * 车主没点头 ⇒ 不装（车主的态度见 `CarUpdatePrefs.silentUpdateEnabled`）。
     *
     * ⚠️ 本字段**默认 `true`**（2026-10-13 车主口径改；PATCH-0115）—— 它只是
     * `CarUpdateState` 的**初始快照**，真正的值由 `CarUpdateManager` 启动时从
     * `CarUpdatePrefs` 读回并覆盖（`prefs.silentUpdateEnabled` 的默认同为 `true`）。
     * 两处默认值**必须一致**，否则界面会先闪一下"关"再变"开"。
     *
     * 落盘在 `CarUpdatePrefs`，不进会话设置（免得被"恢复默认设置"清掉）。
     */
    val silentUpdateEnabled: Boolean = true,

    /** 命中后台的定向灰度（`app_update_targets` 里指定了本机 deviceId）。 */
    val isTargeted: Boolean = false,

    /** APK 下载地址。服务端未填时为空串，此时不能下载。 */
    val downloadUrl: String = "",

    /** 0f..1f，仅 [CarUpdatePhase.DOWNLOADING] 时有意义。 */
    val progress: Float = 0f,

    /** 给用户看的一句话。界面一律中文。 */
    val message: String = "",

    /** 最近一次检查完成的时间（本地格式化）。 */
    val lastCheckedAt: String = "",

    /**
     * 安装这一步是否需要用户在**系统界面**上再点一次确认。
     *
     * 由实现侧在运行时探测：车机把 DBPlay 装成系统应用（有
     * `INSTALL_PACKAGES`）时可以直接静默装完；否则只能拉起系统安装器，
     * 用户必须点一下「安装」。对话框据此决定文案——
     * 说"会自动装好"却弹出系统框，比不解释更糟。
     */
    val installNeedsUserConfirm: Boolean = true,

    /**
     * 是否应当把更新对话框显示出来。
     *
     * 由实现侧决定，而不是界面自己按 [phase] 猜：只有"用户需要做决定"
     * 或"有进度值得看"的阶段才弹（[CarUpdatePhase.AVAILABLE] 及之后），
     * 而"已是最新""检查失败"这类结论只回显在「关于」页的那一行上，
     * 不打断用户。
     */
    val dialogVisible: Boolean = false,
) {
    /** 是否存在一个"待处理"的更新（用于「关于」页那行的语义色）。 */
    val hasPendingUpdate: Boolean
        get() = phase == CarUpdatePhase.AVAILABLE ||
            phase == CarUpdatePhase.DOWNLOADING ||
            phase == CarUpdatePhase.PENDING_INSTALL ||
            phase == CarUpdatePhase.READY ||
            phase == CarUpdatePhase.INSTALLING
}

/**
 * 远程更新服务的接入点。
 *
 * 与 `CarDeveloperEndpoints` 同理：这些常量是**与服务端的契约**，
 * 放在契约层才能保证"改一处、两边都看得见"。
 */
object CarUpdateEndpoints {

    /** 服务端站点。与日志上传共用同一个入口。 */
    const val BASE_URL: String = "https://p.sosun.cc"

    /** 查询更新（GET，免登录）。 */
    const val CHECK_PATH: String = "/api/app/check_update"

    /** 上报升级状态（POST JSON，免登录）。 */
    const val STATUS_PATH: String = "/api/app/update_status"

    /**
     * 产品标识。
     *
     * 服务端据此选通道（`data/app_update/dbplay.json`）。
     * 与日志上传的 `product` 是同一套命名，不要写成 `DBPlay`。
     */
    const val APP_ID: String = "dbplay"

    /** 查询超时（秒）。只读一个小 JSON，给 15 秒足够。 */
    const val CHECK_TIMEOUT_SECONDS: Int = 15

    /** 下载超时（秒）。APK 有几十 MB，按 10 分钟给。 */
    const val DOWNLOAD_TIMEOUT_SECONDS: Int = 600

    /**
     * 上报状态时用的取值，与服务端 `api_app_update_status` 的校验一致。
     * 写错会被服务端 400 拒掉。
     */
    const val STATUS_PUSHED: String = "pushed"
    const val STATUS_DOWNLOADED: String = "downloaded"
    const val STATUS_INSTALLED: String = "installed"
    const val STATUS_FAILED: String = "failed"
}

/**
 * 安装动作的结果。
 *
 * 单独返回而不是只写进 [CarUpdateState.message]：控制器需要据此决定
 * 对话框上说什么（"正在安装"还是"先去授权再回来点一次"），
 * 而字符串是给人看的，不该被拿来当判断依据。
 */
enum class CarInstallOutcome {
    /** 已静默提交给系统（本机有 `INSTALL_PACKAGES`）。 */
    SILENT_COMMITTED,

    /**
     * 已通过 **ADB** 静默提交安装（本机自带 adb 客户端 + `su 0` 提权）。
     *
     * 与 [SILENT_COMMITTED] 分开是因为两者的**用户可见后果不同**：
     * ADB 这条路走的是本机 adbd，安装过程中 adbd 可能与 DBPlay 抢占
     * 同一有线 CarPlay 会话；排障时要能从结论直接分辨走的哪条路。
     *
     * 同样拿不到安装结果（装自身会杀掉本进程），成败靠下次启动比版本号。
     */
    ADB_COMMITTED,

    /** 已拉起系统安装器，等待用户在系统界面上确认。 */
    LAUNCHER_OPENED,

    /**
     * 没有"安装未知应用"的授权。
     *
     * 实现侧**已经**把用户送去授权页了，这里只是把结论告诉控制器，
     * 让界面说清楚"回来还要再点一次"——否则用户会以为按钮坏了。
     */
    NEEDS_UNKNOWN_SOURCES,

    /** 失败。 */
    FAILED,
}

/**
 * 远程更新宿主。
 *
 * 与 `CarDeveloperHost` / `CarSessionHost` 同样是**回调风格**：
 * 本工程全链路没有引入协程，检查/下载又在后台线程跑，
 * 因此用"提交任务 + 主线程回调"而不是 Flow。
 * 实现方负责把回调切回主线程（Compose 状态只能在主线程写）。
 */
interface CarUpdateHost {

    /** 当前状态快照。 */
    fun state(): CarUpdateState

    /**
     * 查询更新。
     *
     * @param manual true = 用户在「关于」页主动点的。
     *        手动查询无论结果如何都要更新 [CarUpdateState.message]，
     *        让用户看得见"查了、结果是什么"；自动查询则安静地失败。
     * @param onState 主线程回调，保证在 Compose 里可以直接写状态。
     */
    fun checkAsync(manual: Boolean, onState: (CarUpdateState) -> Unit)

    /**
     * 启动时的自动检查：距上次检查不足
     * [UPDATE_AUTO_CHECK_INTERVAL_MILLIS] 时直接跳过。
     */
    fun maybeAutoCheck(onState: (CarUpdateState) -> Unit)

    /**
     * 下载 APK。
     *
     * @param silent true = **后台静默下载**（2026-10-11）：不置
     *        [CarUpdateState.dialogVisible]，进度只回显在「关于」页那一行上，
     *        下完进 [CarUpdatePhase.PENDING_INSTALL] 等空闲窗口，而不是
     *        [CarUpdatePhase.READY] 等用户点按钮。
     * @return 提交是否成功（false = 已在下载中、或没有下载地址）。
     */
    fun download(onState: (CarUpdateState) -> Unit, silent: Boolean = false): Boolean

    /**
     * 安装已下载的 APK。
     *
     * ⚠️ 会拉起系统安装器，**必须在主线程调用**（它要 `startActivity`）。
     *
     * ⚠️ 手动路径**不受熔断限制**，并且会**解除**熔断（用户明确点了安装，
     * 那是在他的判断下装这个包）。
     */
    fun install(): CarInstallOutcome

    /**
     * 通知"现在会话已连续空闲 ≥30 秒"（2026-10-11，后台静默更新）。
     *
     * 由 `CarAppController` 在会话状态变化时按 `CarSilentUpdatePolicy` 计时，
     * 凑满稳定窗口才调用。实现侧收到后：若当前处于
     * [CarUpdatePhase.PENDING_INSTALL] 且静默开关打开且该版本未被熔断，
     * 就在 worker 线程发起 ADB 静默安装。
     *
     * ⚠️ **幂等**：空闲期间可能被多次通知（每次状态变化都算一次），
     * 实现侧必须自己保证"一个待安装版本只发起一次"。
     */
    fun notifyIdleWindow()

    /**
     * 开/关「后台自动更新」（「关于」页那颗开关）。
     *
     * 落盘后立刻生效：
     * - 打开时若已有下载好的包且当前空闲，会经 [notifyIdleWindow] 自行装；
     * - 关闭时清空待安装状态，回到"只提示不自动装"。
     *
     * ⚠️ 关闭再打开 = **解除熔断**（这是用户唯一能在车上自救的手段，
     * 见 `CarSilentUpdatePolicy.silentBlocked`）。
     *
     * @return 操作后的最新状态。
     */
    fun setSilentUpdateEnabled(enabled: Boolean): CarUpdateState

    /** 用户关掉更新对话框。 */
    fun dismiss(): CarUpdateState

    /** 主动上报一次升级状态（下载完成 / 安装失败等）。 */
    fun reportStatus(status: String, detail: String)
}


/**
 * 安装通道，按**尝试顺序**声明（2026-10-07，车主需求「优先用 ADB 安装，
 * 失败就退到系统安装器」）。
 *
 * 顺序即契约：枚举的**声明次序**就是 `CarApkInstaller` 的尝试次序，
 * [CarInstallChannel.next] 依赖 `entries` 保持这个次序。改动顺序必须同步改
 * `CarInstallChannelOrderTest` —— 那是这条需求的守门测试。
 */
enum class CarInstallChannel {
    /** ① 平台签名 / priv-app 通道（`PackageInstaller`）。实车上基本不成立。 */
    PLATFORM_SILENT,

    /** ② ADB 静默安装（本机自带 adb + `su 0`）。**车主指定的首选**。 */
    ADB_SILENT,

    /** ③ 系统安装器（弹窗，用户手点）。兜底，永远存在。 */
    SYSTEM_INSTALLER,
    ;

    /** 下一个可尝试的通道；[SYSTEM_INSTALLER] 已是最后一步，返回 null。 */
    fun next(): CarInstallChannel? = entries.getOrNull(ordinal + 1)

    companion object {
        /** 首选通道。车主口径：ADB 优先。 */
        val PREFERRED: CarInstallChannel = ADB_SILENT
    }
}

/**
 * 某个通道提交后拿到的结果（[CarInstallOutcome]）→ 该通道**是否已委托成功**。
 *
 * 只有"已经交出去"的结果才算成功；`FAILED` 必须落到下一个通道。
 * 注意 [CarInstallOutcome.NEEDS_UNKNOWN_SOURCES] **不算**委托成功，
 * 但它也不该触发"继续往下试"——它已经把用户送到授权页了（见 installer 注释）。
 */
fun CarInstallOutcome.isDelegated(): Boolean = when (this) {
    CarInstallOutcome.SILENT_COMMITTED,
    CarInstallOutcome.ADB_COMMITTED,
    CarInstallOutcome.LAUNCHER_OPENED,
    -> true

    CarInstallOutcome.NEEDS_UNKNOWN_SOURCES,
    CarInstallOutcome.FAILED,
    -> false
}
