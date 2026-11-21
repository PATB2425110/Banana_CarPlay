package com.dbtool.carplay.api.developer

/**
 * 开发者模式（隐藏页）的契约。
 *
 * 为什么单独立一个包，而不是塞进 `session` / `settings`：
 * 它不是会话能力，也不是用户设置——它是一组**本机排障工具**。
 * 唯一入口是「关于」页的版本号连点 7 次，普通用户路径上根本不存在这个页面，
 * 因此把它与会话状态、用户设置混在一起只会让两者的生命周期互相污染。
 *
 * 与远程服务端的对应关系见 `memory/REF-dbapi.md`：车机侧只有三个免登录接口，
 * 本契约只覆盖其中的「上传日志包」（另外两个是后台下发任务时的轮询与回报，
 * 属于"自动上报"链路，不在本次范围内）。
 *
 * ⚠️ **"不在本次范围内"是刻意的范围边界，不是漏做** —— 所以服务端契约里那个
 * `system/diagnostic_result.json`（结构化诊断结论）**不要单独补**：它只有配合
 * "任务轮询 + 状态回报"才有意义（没有任务下发，就没有结果需要回报）。
 * 要做就作为一个完整功能一起做（轮询 → 按 `collectScope` 采集 → 上传带 `taskId`
 * → 回报 `sent`/`running`/`failed` → 生成结构化结果）。
 */

/**
 * 解锁开发者模式所需的「关于」页版本号连点次数。
 *
 * 放在契约层而不是各自的实现里，因为**两侧都要用**：
 * 控制器据此判定解锁，界面据此显示"还差几次"——写两份迟早会不一致。
 */
const val DEVELOPER_UNLOCK_TAP_COUNT: Int = 7

/**
 * 采集范围。
 *
 * [wireValue] 必须与服务端的 `collectScope` 取值一致
 * （`full` / `logcat_only` / `system_only` / `adb_diag`），
 * 这样将来接后台下发任务时不需要再做一层翻译。
 *
 * ⚠️ **服务端还有一个 `adb_diag`，本工程没有对应枚举值**（它属于"ADB 独立热点"的
 * 专项采集）。今天无害：手动上传时用户只能从下面三个里选，不会发出 `adb_diag`。
 * 但**将来接后台任务轮询时必须先把它补上** —— 否则收到 `collectScope=adb_diag`
 * 的任务会没有可映射的枚举值。
 */
enum class CarLogScope(
    val wireValue: String,
    val title: String,
    val detail: String,
) {
    FULL(
        wireValue = "full",
        title = "全量采集",
        detail = "运行日志 + 系统属性 + 应用日志，信息最全",
    ),
    LOGCAT_ONLY(
        wireValue = "logcat_only",
        title = "仅运行日志",
        detail = "只取 logcat，最快",
    ),
    SYSTEM_ONLY(
        wireValue = "system_only",
        title = "仅系统信息",
        detail = "系统属性 / 车型 / 安卓版本，不含日志",
    ),
}

/** 采集上传所处的阶段。界面据此显示进度条与按钮可用性。 */
enum class CarLogUploadPhase {
    /** 空闲，可以开始采集。 */
    IDLE,

    /** 正在采集（读 logcat / getprop / 应用日志）。 */
    COLLECTING,

    /** 正在打包成 zip。 */
    PACKAGING,

    /** 正在上传。 */
    UPLOADING,

    /** 上传成功。 */
    DONE,

    /** 失败，[CarDeveloperState.message] 是原因。 */
    FAILED,
}

/**
 * 开发者模式面板的全部状态。
 *
 * 刻意把「设备身份」与「系统信息」也放进来：现场排障时，
 * 工程师问的第一句永远是"这台车的设备号是多少、装的是哪版"，
 * 而这三行值恰恰是后台列表里用来定位这台车的字段。
 */
data class CarDeveloperState(
    /** 是否已解锁（版本号连点 7 次）。 */
    val unlocked: Boolean = false,

    /**
     * 本机设备短码。后台 `/admin/device-logs` 就是按它认车的。
     *
     * 一次生成、终身不变——设备指纹是后台识别这台车的唯一主键，
     * 变了就等于换了一台车（参考实现曾因"锚点升级"导致指纹漂移，
     * 现场表现是"重启后设备号又变了"）。
     */
    val deviceId: String = "",

    /** 服务端地址。 */
    val serverUrl: String = "",

    val phase: CarLogUploadPhase = CarLogUploadPhase.IDLE,

    /** 0f..1f。仅 [CarLogUploadPhase.UPLOADING] 时有意义。 */
    val progress: Float = 0f,

    /** 给用户看的一句话（进行中 / 成功 / 失败原因）。界面一律中文。 */
    val message: String = "",

    /** 最近一次成功上传返回的日志 ID，形如 `LOG-1759...-ab12cd34`。 */
    val lastLogId: String = "",

    /** 最近一次成功上传的时间（本地格式化）。 */
    val lastUploadAt: String = "",

    /** 最近一次成功上传的文件名，便于和后台列表核对。 */
    val lastFileName: String = "",

    // ------------------------------------------------------- 系统信息回显

    val appVersionName: String = "",
    val androidVersion: String = "",
    val systemVersion: String = "",
    val deviceModel: String = "",

    /** 调试反连隧道的实时状态（2026-10-16）。只读回显，不带行为。 */
    val debugTunnel: CarDebugTunnelStatus = CarDebugTunnelStatus(),

    /** 限时 AirPlay 7000 握手包头采集是否正在运行；只在用户主动启动后为 true。 */
    val airPlayTcpCaptureActive: Boolean = false,

    /** 最近一次 TCP 诊断采集的中文状态。 */
    val airPlayTcpCaptureStatus: String = "未启动",
    val audioScanBusy: Boolean = false,
    val audioScanReport: String = "",
)

/**
 * 远程设备日志服务的接入点。
 *
 * 单独抽成 object 而不是散在实现里：这些常量是**与服务端的契约**
 * （字段名、取值、路径都必须逐字对上 `dbapi/interfaces/rest/device_logs.py`），
 * 放在契约层才能保证"改一处、两边都看得见"。
 *
 * 服务端环境见 `memory/REF-dbapi.md`。
 */
object CarDeveloperEndpoints {

    /** 服务端站点。nginx → 127.0.0.1:5000，免登录的车机接口都在这里。 */
    const val BASE_URL: String = "https://p.sosun.cc"

    /** 旧诊断包地址（multipart）。日志上传已改走 [LOG_UPLOAD_URL]，这里不再使用。 */
    const val UPLOAD_PATH: String = "/api/device/log/upload"

    /**
     * 日志查看服务。JSON POST，带 `appId` 后按应用分目录。
     * 只替换诊断日志上传，不改心跳、档案、更新。
     */
    const val LOG_UPLOAD_URL: String =
        "https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/upload"

    /** 日志服务上的应用目录名。 */
    const val LOG_APP_ID: String = "dbplay"

    /**
     * 上报车辆档案（JSON，启动时自动调用）。
     *
     * 服务端 `dbapi/interfaces/rest/vehicle_profile.py` 的
     * `POST /api/device/profile/report`：把 VIN / 系统版本 / App 版本
     * 以 deviceId 为主键 upsert 进 `vehicle_profiles` 表，后台「DBPlay 车辆」
     * 页据此展示车辆列表并支持按 VIN 灰度。
     */
    const val PROFILE_PATH: String = "/api/device/profile/report"

    /** 车辆档案上报超时（秒）。只发一个小 JSON。 */
    const val PROFILE_TIMEOUT_SECONDS: Int = 10

    /**
     * 车机心跳：报个到（刷新后台「在线」列）并取回本机是否被拉黑。
     *
     * 服务端 `dbapi/interfaces/rest/vehicle_profile.py` 的
     * `POST /api/device/heartbeat`（**免登录、免 CSRF**，车机没有后台会话）：
     * 请求体 `{deviceId, vin, product}`，按 deviceId / VIN 双键匹配 `device_blocks`，
     * 返回 `{success, blocked, reason}`。
     *
     * ## 为什么"在线"与"拉黑"是同一个接口
     *
     * 两者都是**周期性事实**，而且必须同时成功才有意义。拆成两个接口 =
     * 两倍请求、两处超时、两处失败处理，还得处理"在线报上去了、拉黑没查到"的
     * 中间态。合成一个之后，后台看到的在线时间与拉黑判定天然同一时刻。
     *
     * ⚠️ 服务端保证**任何异常都回 `blocked=false`**——它的故障绝不能把整个
     * 车队锁死。客户端拿到 `blocked=true` 才拦；拿不到答案（超时 / 老服务端
     * 没这个字段）时沿用本地缓存（sticky，见 `CarDeviceStatusSync`）。
     */
    const val HEARTBEAT_PATH: String = "/api/device/heartbeat"

    /**
     * 心跳超时（秒）。
     *
     * 刻意比档案上报短：心跳**每 60 秒一次**，且其中一次（连接时的那次）
     * 卡在"用户点了连接之后、会话建立之前"——车机没网时多等一秒就是用户
     * 多盯一秒的"正在连接"。拿不到答案不会拦车（沿用缓存），所以宁可早失败。
     */
    const val HEARTBEAT_TIMEOUT_SECONDS: Int = 5

    /** 心跳间隔（毫秒）。60 秒：后台「在线」判定用 3 分钟窗口，容得下连丢两拍。 */
    const val HEARTBEAT_INTERVAL_MILLIS: Long = 60_000L

    /**
     * 拉黑原因兜底文案。
     *
     * 服务端 `is_device_blocked` 命中但没有 `reason` 时用的是
     * 「本车已被停用，请联系管理员」；这里再留一份，是为了**服务端老版本**
     * （或中间被代理改写）时用户仍能读到一句能照做的话，而不是空白。
     */
    const val BLOCKED_REASON_FALLBACK: String = "本机已被管理员停用，无法使用 CarPlay"

    /**
     * 上传方产品标识。
     *
     * 服务端据此选**版本门禁通道**（`data/app_update/dbplay.json`）。
     * 不带这个字段会被当成 dbtool，进而用 1.0.6 的门槛把 0.0.x 的包全部丢掉。
     */
    const val PRODUCT: String = "dbplay"

    /**
     * 上传来源 = 手动。
     *
     * 服务端对 `source=manual` 的包**跳过版本门禁**——门禁本意是拦
     * "老版本自动刷屏"，而开发者在车上主动点的那一下恰恰最需要日志。
     */
    const val SOURCE_MANUAL: String = "manual"

    /** 打包超时（秒）。日志包可能几十 MB，给足时间。 */
    const val UPLOAD_TIMEOUT_SECONDS: Int = 60
}

/**
 * 开发者模式宿主。
 *
 * 与 `CarSessionHost` 同样是**回调风格**：本工程全链路没有引入协程，
 * 采集/上传又在后台线程跑，因此用"提交任务 + 主线程回调"而不是 Flow。
 * 实现方负责把回调切回主线程（Compose 状态只能在主线程写）。
 */
interface CarDeveloperHost {
    /** Local read-only scan. Never uploads or enables privileged access. */
    fun scanAudioOutput(inputs: List<String>, onState: (CarDeveloperState) -> Unit): Boolean = false

    /** 当前状态快照。 */
    fun state(): CarDeveloperState

    /** 解锁（版本号连点 7 次命中时调用）。返回解锁后的状态。 */
    fun unlock(): CarDeveloperState

    /** 重新上锁（面板里的"退出开发者模式"）。 */
    fun lock(): CarDeveloperState

    /**
     * 重算设备号与系统信息回显。
     *
     * ⚠️ 会做 getprop / SharedPreferences 读取，**不要在渲染路径上调用**；
     * 只在进入面板、以及用户手动点"刷新"时调一次。
     * 界面路径一律用 [refreshAsync]。
     */
    fun refresh(): CarDeveloperState

    /**
     * [refresh] 的异步版本：把重活放到后台线程，结果投递回主线程。
     *
     * 界面进入开发者页时会调它——[refresh] 里可能有 `getprop` / `dumpsys`，
     * 在主线程调就是一次可见的卡顿。
     *
     * @param onState 主线程回调，保证在 Compose 里可以直接写状态。
     */
    fun refreshAsync(onState: (CarDeveloperState) -> Unit)

    /**
     * 采集 → 打包 → 上传。
     *
     * @param includeClientIpv4 true 时把 Ecarx SDK 当前报告的热点客户端 IPv4 写入包；不采 MAC。
     * @param onState 每次阶段推进都回调一次（实现方保证在主线程）。
     * @return 提交是否成功（false = 上一次上传/抓包还没跑完，本次被忽略）。
     */
    fun collectAndUpload(
        scope: CarLogScope,
        includeClientIpv4: Boolean,
        onState: (CarDeveloperState) -> Unit,
    ): Boolean

    /** 用户主动启动一次限时 AirPlay TCP 7000 SYN/RST 包头采集；不自动开启 ADB 或 root。 */
    fun startAirPlayTcpCapture(onState: (CarDeveloperState) -> Unit): Boolean
}
