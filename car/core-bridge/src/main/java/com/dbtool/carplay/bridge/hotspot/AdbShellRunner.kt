package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.dbtool.carplay.api.hotspot.CarAdbDeviceList
import com.dbtool.carplay.api.hotspot.CarAdbHotspotCommands
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.AdbPortVerdict
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用**随包的 adb 客户端**在车机自己身上拿到一个 shell。
 *
 * ## 为什么需要这一步
 *
 * DBPlay 是普通应用，`Runtime.exec` 出来的进程跑在应用自己的 uid 下，
 * 没有权限配网卡、起 hostapd。参考实现（`FUCK-Lynk&Co-ADB.apk`）的做法是
 * **在车机上把 adb 打开，再让 adb 自己连自己**：`adb shell` 拿到的进程跑在
 * `adbd` 的 uid 下（车机上通常是 shell，配合 `su 0` 到 root），
 * 于是那些特权命令就能执行了。本类是这套做法的全部实现。
 *
 * ## 两个二进制从哪来
 *
 * 放在 `jniLibs/armeabi-v7a/` 下（`libdbplayadb.so` / `libdbplaybusybox.so`），
 * 安装后落到 `/data/app/…/lib/arm/`，**权限是 `0755`、SELinux 标签是
 * `apk_data_file`，可以直接 `execve`**（已在真机上用别的应用核实过）。
 *
 * ⚠️ **不要**改成"释放到 `filesDir` 再执行"——参考实现就是这么干的，
 * 但在 `targetSdk >= 29` 上 `app_data_file` 不允许 `execute`，会直接 EACCES。
 * 详见 `ADB-HOTSPOT.md` §2.2 / §3.2。
 *
 * ## 端口
 *
 * 自己的 adb server 用 [CarAdbHotspotDefaults.ADB_SERVER_PORT]（5038），
 * **绝不碰**系统 adbd 的 5555。
 *
 * ## 线程
 *
 * 所有方法都是**阻塞**的，只能在 worker 线程调用。本工程没有协程。
 */
internal class AdbShellRunner(private val appContext: Context) {

    /** 一条命令的执行结果。 */
    data class CommandResult(
        /** 进程退出码；超时被强杀时为 [TIMEOUT_EXIT_CODE]。 */
        val exitCode: Int,
        /** stdout + stderr（`redirectErrorStream` 已合并）。 */
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0

        val timedOut: Boolean get() = exitCode == TIMEOUT_EXIT_CODE

        /** 单行摘要，用于自检页展示。 */
        val summary: String
            get() = when {
                timedOut -> "超时（按「已在后台运行」处理）"
                output.isBlank() -> "退出码 $exitCode，无输出"
                else -> "退出码 $exitCode：${output.trim()}"
            }
    }

    private val nativeLibDir: File? = runCatching {
        File(appContext.applicationInfo.nativeLibraryDir)
    }.getOrNull()

    /** 应用私有目录，充当 adb 的 `HOME`（放 `adbkey`）与 `TMPDIR`。 */
    private val homeDir: File = appContext.filesDir

    /**
     * 实际可执行路径。
     *
     * 正常情况就是 `nativeLibraryDir` 里那两个文件。只有在 ABI 匹配但
     * `nativeLibraryDir` 不可执行的极端情况下，才回落到"拷进 filesDir + chmod"
     * （老系统上这条路是通的，新系统上会在 [prepare] 里如实报失败）。
     */
    private var adbPath: String? = null
    private var busyboxPath: String? = null

    /** 已解析出的 adb 设备序列号（形如 `127.0.0.1:5555`）。 */
    @Volatile
    private var serial: String? = null

    /**
     * **实际连上的**车机 adbd 端口（2026-10-05 加，为"每台车都能开"）。
     *
     * 初值是 AOSP 默认值 [CarAdbHotspotDefaults.DEVICE_ADB_PORT]（5555），
     * [connect] 里会先尝试读系统属性 `service.adb.tcp.port` 校正，连不上再按
     * [CarAdbHotspotDefaults.DEVICE_ADB_PORT_CANDIDATES] 逐个试，命中的写在这里。
     *
     * 上层（`AdbShellSession`）把它写进诊断日志，便于"这台车到底在哪个端口"留证。
     */
    @Volatile
    var resolvedDevicePort: Int = CarAdbHotspotDefaults.DEVICE_ADB_PORT
        private set

    /**
     * `adb connect` 的目标（`127.0.0.1:<port>`）。
     *
     * 显式带端口，不再依赖 adb 的"无端口默认 5555"——那层默认值与
     * [CarAdbHotspotDefaults.DEVICE_ADB_PORT] 只是巧合相等。
     */
    private val connectTarget: String
        get() = "$LOOPBACK:$resolvedDevicePort"

    /**
     * 读车机 adbd 的**真实端口**，校正 [resolvedDevicePort]（2026-10-05 加）。
     *
     * ## 做法
     *
     * 优先 `adb shell getprop service.adb.tcp.port`（走已连上的 adb，1 次调用）；
     * 拿不到值就保持默认 5555——不影响正确性，因为 [connect] 后面还有候选端口轮试。
     *
     * ⚠️ 读的是**瞬态**属性 [CarAdbHotspotDefaults.ADB_PORT_PROPERTY]，不是
     * `persist.adb.tcp.port`：只有瞬态那条等于 adbd **本次**监听的口。
     * 切过端口但尚未重启时，persist 值可能与本次监听值不同（见 2026-10-06 持久化改动）。
     *
     * ⚠️ **不额外开进程**：本方法只在 [connect] 内、adb server 已起之后调用，
     * 复用同一条 adb 通道；失败静默（属性多为空/无权限读）。
     */
    private fun resolveDevicePort() {
        val adb = adbPath ?: return
        val serial = serial
        val result = runCatching {
            run(
                command = buildList {
                    add(adb)
                    add("-P"); add(PORT.toString())
                    if (serial != null) { add("-s"); add(serial) }
                    add("shell"); add("getprop"); add(CarAdbHotspotDefaults.ADB_PORT_PROPERTY)
                },
                timeoutMillis = PRIVILEGE_TIMEOUT_MILLIS,
            )
        }.getOrNull() ?: return
        if (result.exitCode != 0) return
        val port = result.output.trim().lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toIntOrNull()
            ?: return
        if (port in 1..65535 && port != resolvedDevicePort) {
            android.util.Log.i(
                "XCExt.hotspot.probe",
                "adbd 端口按系统属性校正：$port（属性 " +
                    "${CarAdbHotspotDefaults.ADB_PORT_PROPERTY}）",
            )
            resolvedDevicePort = port
        }
    }

    /** 长驻进程（hostapd / udhcpd）的本地 adb 客户端进程，[stop] 时清理。 */
    private val daemons = mutableListOf<Process>()

    private val serverStarted = AtomicBoolean(false)

    // ------------------------------------------------------------ 准备

    /**
     * 校验 ABI、定位两个二进制，并确认它们**真的能执行**。
     *
     * @return 人类可读的结论（成功或失败原因），直接进自检页。
     */
    fun prepare(): StepOutcome {
        val abiList = Build.SUPPORTED_ABIS.toList()
        if (CarAdbHotspotDefaults.REQUIRED_ABI !in abiList) {
            return StepOutcome(
                ok = false,
                detail = "本机 ABI 列表为 ${abiList.joinToString()}，不含 " +
                    "${CarAdbHotspotDefaults.REQUIRED_ABI}；" +
                    "随包的 adb / busybox 是 32 位 ARM 静态二进制，无法在这台设备上运行。",
            )
        }
        val dir = locateBinaryDir()
            ?: return StepOutcome(
                ok = false,
                detail = buildString {
                    append("随包二进制**没有落到磁盘上**，ADB 独立热点无法启动。\n")
                    append("nativeLibraryDir=").append(nativeLibDir?.absolutePath ?: "null")
                    append('\n')
                    append("SUPPORTED_ABIS=").append(Build.SUPPORTED_ABIS.joinToString())
                    append('\n')
                    append("原因：**安装器只解压首选 ABI 那一个 lib/<abi>/ 目录**。")
                    append("这两个二进制是 32 位 ARM 静态 ELF，")
                    append("所以仓库里把同一份文件同时放进了 ")
                    append("`jniLibs/arm64-v8a/` 与 `jniLibs/armeabi-v7a/`")
                    append("（首选 arm64 时解压成 `lib/arm64/`，首选 32 位时解压成 `lib/arm/`）。\n")
                    append("⇒ 先确认这个 APK 里到底带了什么：")
                    append("`unzip -l <apk> | grep libdbplay`。\n")
                    append("⇒ 一个都没有 = 打包时漏了（检查 car/core-bridge/src/main/jniLibs/ 下两个目录）；")
                    append("只有一份但装不上 = 逃生开关：加 Gradle 参数 ")
                    append("`-Pdbplay.abi=$REQUIRED_ABI_TEXT` 重建")
                    append("（会把整个应用压成 32 位进程，慎用；详见 ADB-HOTSPOT.md §3.2）。")
                },
            )

        val adb = File(dir, CarAdbHotspotDefaults.ADB_LIBRARY_NAME)
        val busybox = File(dir, CarAdbHotspotDefaults.BUSYBOX_LIBRARY_NAME)
        val missing = listOf(adb, busybox).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            return StepOutcome(
                ok = false,
                detail = "目录 ${dir.absolutePath} 里缺少：${missing.joinToString { it.name }}。" +
                    "目录存在但文件不全，说明 APK 打包阶段漏了这两个库" +
                    "（检查 car/core-bridge/src/main/jniLibs/ 下的 " +
                    "arm64-v8a/ 与 armeabi-v7a/ 两个目录，**两份内容应当逐字节相同**）。",
            )
        }
        adbPath = ensureExecutable(adb)
        busyboxPath = ensureExecutable(busybox)
        if (adbPath == null || busyboxPath == null) {
            return StepOutcome(
                ok = false,
                detail = "两个二进制都无法执行（已尝试 nativeLibraryDir 直接执行、" +
                    "chmod 0755、以及拷到 filesDir 后执行）。" +
                    "adb=${adb.absolutePath}、busybox=${busybox.absolutePath}",
            )
        }
        return StepOutcome(
            ok = true,
            detail = "$REQUIRED_ABI_TEXT 可用；adb=${adbPath}、busybox=${busyboxPath}",
        )
    }

    fun adbExecutable(): String? = adbPath

    fun busyboxExecutable(): String? = busyboxPath

    /**
     * 找出两个二进制**实际**落在哪个目录。
     *
     * ⚠️ 两个坑叠在一起，都不能靠"猜一个标准路径"绕过：
     *
     * 1. `nativeLibraryDir` 返回的是**首选 ABI** 的目录。车机基本是 arm64
     *    （`SUPPORTED_ABIS` 第一项 `arm64-v8a`）⇒ 拿到 `…/lib/arm64`，
     *    而我们的 32 位二进制按 `armeabi-v7a` 打包。
     * 2. 目录名用的是**旧式 ABI 名**。实测（小米 Android 12，`primaryCpuAbi=armeabi-v7a`）：
     *    解压结果是 `…/lib/arm/`，**不是** `…/lib/armeabi-v7a/`。
     *    且 `lib/` 下**只有一个** ABI 目录——安装器只解压首选 ABI。
     *
     * ⇒ 先看首选目录，再在同级里按旧式名找 32 位 ARM 的目录。
     *
     * ⚠️ 如果首选 ABI 是 arm64，`lib/arm/` 根本不会被创建（安装器不解压它），
     * 这里就会返回 null。那不是本函数的缺陷，是**架构层面的前提不成立**——
     * 由 [prepare] 如实报出，并给出解决办法（见 `ADB-HOTSPOT.md` §3.2）。
     */
    private fun locateBinaryDir(): File? {
        val names = listOf(
            CarAdbHotspotDefaults.ADB_LIBRARY_NAME,
            CarAdbHotspotDefaults.BUSYBOX_LIBRARY_NAME,
        )
        fun holds(folder: File?): Boolean =
            folder != null && names.all { File(folder, it).isFile }

        val primary = nativeLibDir
        if (holds(primary)) return primary

        val parent = primary?.parentFile ?: return null
        return THIRTY_TWO_BIT_ABI_DIRS
            .map { File(parent, it) }
            .firstOrNull { holds(it) }
    }

    /**
     * 把随包 busybox 部署成 [CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH]。
     *
     * ⚠️ **必须改名部署**：busybox 靠 `argv[0]` 的 basename 找 applet，而随包文件名
     * 只能是 `lib*.so` ⇒ 直接执行会 `applet not found`（连 `busybox udhcpd` 的
     * 显式写法也不认）。理由与实测记录见 [CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH]。
     *
     * 校验方式刻意用 `udhcpd --help` 而不是 `ls -l`：`ls` 只能证明文件在，
     * 而这里要证明的恰恰是 **applet 分发通了**——那才是原来坏掉的东西。
     *
     * @param prefix 提权前缀（`""` 或 `"su 0 "`）。
     */
    fun stageBusybox(prefix: String): CommandResult {
        val source = busyboxPath
            ?: return CommandResult(1, "随包 busybox 不可用（见「随包二进制与 ABI」那一步的结论）")
        val target = CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH

        val copy = shell("${prefix}/system/bin/cp $source $target")
        if (!copy.ok) return copy
        val chmod = shell("${prefix}/system/bin/chmod 0755 $target")
        if (!chmod.ok) return chmod

        val probe = shell(CarAdbHotspotCommands.stagedBusyboxProbe(target))
        val dispatchOk = probe.output.contains("Usage:", ignoreCase = true)
        return CommandResult(
            exitCode = if (dispatchOk) 0 else 1,
            output = buildString {
                append("已部署到 $target（源 $source）")
                append('\n')
                append(probe.output.trim())
                if (!dispatchOk) {
                    append("\n⚠️ udhcpd --help 没有打印用法，applet 分发可能仍然不正常。")
                }
            },
        )
    }

    fun resolvedSerial(): String? = serial

    /**
     * 拿到一个可执行路径。
     *
     * 顺序：① 原地可执行就直接用；② 试着 `chmod 0755`（`/data/app/…/lib` 属于
     * `system:system`，多半失败，但试一次成本极低）；③ 拷到 `filesDir` 再 chmod。
     * ③ 在 Android 10+ 上会被 `app_data_file` 的 execute 禁令挡住，
     * 所以它只是"老系统上的兜底"，不是主路径。
     */
    private fun ensureExecutable(source: File): String? {
        if (canExecute(source)) return source.absolutePath

        runCatching { Os.chmod(source.absolutePath, MODE_0755) }
        if (canExecute(source)) return source.absolutePath

        val fallback = File(homeDir, source.name)
        val copied = runCatching {
            if (!fallback.isFile || fallback.length() != source.length()) {
                source.inputStream().use { input ->
                    fallback.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Os.chmod(fallback.absolutePath, MODE_0755)
            true
        }.getOrDefault(false)
        if (copied && canExecute(fallback)) return fallback.absolutePath

        return null
    }

    private fun canExecute(file: File): Boolean = runCatching {
        file.isFile && Os.access(file.absolutePath, OsConstants.X_OK)
    }.getOrDefault(false)

    // ------------------------------------------------------------ adb server

    /**
     * `<adb> -P 5038 start-server`。
     *
     * ⚠️ **2026-10-05 实测更正**：先前一版改成 `runUntilOutput`（等输出命中即
     * 提前返回），依据是"adb 起完 daemon 后自己不退出、傻等会烧满 20 秒"。
     * 该推断**已被实验证伪** —— 在本机用真 adb 二进制做对照（旧 `run()` vs
     * 新 `runUntilOutput()`），两种写法都是 **0.33 秒**：`adb start-server`
     * 起完 daemon 就正常退出了，不存在等待。
     *
     * 而车机上那 20 秒的**真实落点**在别处：`DeviceAdbEnabler.enable()` 里的
     * `Class.forName("android.fxbridge.FxBridge")`（详见该文件的注释与
     * `DeviceAdbEnablerPlatformGateTest`）。本函数在实车日志里是**同一毫秒
     * 内完成**的（`17:53:57.222` 发起并返回退出码 0）。
     *
     * ⇒ 因此这里回到 `run()`：语义最简单，也少一个轮询线程。
     * `runUntilOutput` 本身保留（见其注释）：它解决的是"命令输出即结论、
     * 进程却可能不退出"这一类**未来**的用例，目前没有调用点。
     */
    fun startServer(): CommandResult {
        val adb = adbPath ?: return missingBinary()
        val result = run(
            command = listOf(adb, "-P", PORT.toString(), "start-server"),
            timeoutMillis = SERVER_TIMEOUT_MILLIS,
        )
        serverStarted.set(true)
        return result
    }

    /**
     * `adb connect 127.0.0.1`，随后用 `devices` 把真实序列号解析出来。
     *
     * ⚠️ **不能**直接把 `127.0.0.1` 当成 `-s` 的值：`connect` 注册的序列号是
     * `127.0.0.1:5555`。参考实现写的是 `-s 127.0.0.1`，能不能命中取决于
     * adb 版本对"主机名匹配 host:port"的宽容度。这里改成先 `devices` 解析，
     * 拿到什么用什么，不赌。
     *
     * ⚠️⚠️ **但解析必须重试，不能读一次就定生死。**
     * `connect` 返回之后，设备在 adb 侧可能还短暂处于 `offline`（握手尚未完成），
     * 这时 `devices` 读到的就是离线 ⇒ 一次瞬时状态就掐断整条链。
     * 参考实现**不把 `devices` 当判据**（它调了 `devices -l`，但返回值只被拼进
     * 日志字符串，没有任何判断），我们把它当闸门——这与"逐条判退出码"是同一类
     * 错误：**把一个自己发明的、比参考实现更严的判据放在了关键路径上。**
     * 所以这里重试 [SERIAL_ATTEMPTS] 次，实在读不到才判失败。
     *
     * ## 判据是"两条取或"，永远不会比参考更严
     *
     * 参考实现对 `connect` 的返回值判 `contains("connected")`。我们额外解析
     * 序列号（因为后续要 `-s <真实序列号>`，比 `-s 127.0.0.1` 稳），但**不能**
     * 因此把"解析不到"变成致命错误——那又是自造判据。所以：
     *
     * ```
     * ok = 解析到序列号  ||  connect 输出含 "connected"
     * ```
     *
     * 两者都不成立才判失败（那时参考也会失败，只是晚一步）。解析不到序列号时
     * 用 [CarAdbDeviceList.referenceSerial] 兜底（参考的写法），
     * 让后面的命令自己去报错，而不是由我们在这里否决。
     */
    /** Connect only to DBPlay's dedicated device port; no port scan or mutation. */
    fun connectDedicatedPort(): CommandResult {
        val adb = adbPath ?: return missingBinary()
        val targetPort = CarAdbHotspotDefaults.DEVICE_ADB_PORT
        val target = "$LOOPBACK:$targetPort"
        resolvedDevicePort = targetPort
        val connect = run(
            command = listOf(adb, "-P", PORT.toString(), "connect", target),
            timeoutMillis = SERVER_TIMEOUT_MILLIS,
        )
        var devices = run(
            command = listOf(adb, "-P", PORT.toString(), "devices"),
            timeoutMillis = SERVER_TIMEOUT_MILLIS,
        )
        repeat(SERIAL_ATTEMPTS - 1) { attempt ->
            if (hasOnlineSerial(devices.output, target)) return@repeat
            if (attempt < SERIAL_ATTEMPTS - 2) {
                Thread.sleep(SERIAL_RETRY_BACKOFF_MILLIS)
                devices = run(
                    command = listOf(adb, "-P", PORT.toString(), "devices"),
                    timeoutMillis = SERVER_TIMEOUT_MILLIS,
                )
            }
        }
        if (!hasOnlineSerial(devices.output, target)) {
            serial = null
            return CommandResult(
                exitCode = 1,
                output = "5556 未返回 online 设备。connect: ${connect.output.trim()}；devices: ${devices.output.trim()}",
            )
        }
        serial = target
        val probe = run(
            command = listOf(adb, "-P", PORT.toString(), "-s", target, "shell", "echo", "DBPLAY_ADB_OK"),
            timeoutMillis = PRIVILEGE_TIMEOUT_MILLIS,
        )
        if (!probe.ok || !probe.output.contains("DBPLAY_ADB_OK")) {
            return CommandResult(1, "5556 已列出但 shell 验活失败：${probe.summary}")
        }
        return CommandResult(0, "5556 通道可用：$target")
    }

    /** A non-mutating liveness check; it never enables adbd or changes ports. */
    fun checkDedicatedPort(): CommandResult = connectDedicatedPort()

    private fun hasOnlineSerial(devicesOutput: String, target: String): Boolean =
        devicesOutput.lineSequence().any { line ->
            val fields = line.trim().split(Regex("\\s+"))
            fields.size >= 2 && fields[0] == target && fields[1] == "device"
        }

    fun connect(): CommandResult {
        val adb = adbPath ?: return missingBinary()
        // ⚠️ 2026-10-05：实车实测本步骤耗时 20.2 秒（acquire 打点），
        //    而 connect() 里有多条 adb 命令，必须逐条计时才知道是哪一条。
        fun tick(label: String, block: () -> CommandResult): CommandResult {
            val t = System.currentTimeMillis()
            val r = block()
            android.util.Log.i(
                "XCExt.hotspot.probe",
                "connect 子步骤｜$label｜${System.currentTimeMillis() - t}ms｜exit=${r.exitCode}",
            )
            return r
        }
        // ⚠️ 2026-10-05（为"每台车都能开"）：连之前先**把端口问清楚**。
        //
        // 读系统属性 `service.adb.tcp.port`——这是 adbd **本次实际监听**的口（唯一权威）。
        // 读到就按它连；读不到（属性空/无权限）才用默认 5555，后面还有候选轮试兜底。
        //
        // ⚠️ 别改成读 `persist.adb.tcp.port`：那条是"下次开机回填源"（见 2026-10-06
        // 持久化改动），本次监听未必等于它——切过端口但还没重启时，两者故意不同。
        resolveDevicePort()

        val connect = tick("adb connect $connectTarget") {
            run(
                command = listOf(adb, "-P", PORT.toString(), "connect", connectTarget),
                timeoutMillis = SERVER_TIMEOUT_MILLIS,
            )
        }
        fun readDevices() = run(
            command = listOf(adb, "-P", PORT.toString(), "devices"),
            timeoutMillis = SERVER_TIMEOUT_MILLIS,
        )
        var devices = tick("adb devices (第 1 次)", ::readDevices)
        var round = 1
        while (parseSerial(devices.output) == null && round < SERIAL_ATTEMPTS) {
            Thread.sleep(SERIAL_RETRY_BACKOFF_MILLIS)
            devices = tick("adb devices (第 ${round + 1} 次)", ::readDevices)
            round += 1
        }
        var resolved = parseSerial(devices.output)
        var connected = CarAdbDeviceList.looksConnected(connect.output)

        // ⚠️ 2026-10-05（为"每台车都能开"）：**默认端口连不上时，逐个候选端口再试**。
        //
        // 只认 5555 会把"adbd 在别的端口"的车机一刀切死，日志里只有 failed to connect。
        // 这里彻底穷尽候选：每换一个端口都 `connect <ip>:<port>` + 读 `devices`，
        // 命中（解析到 device 或输出含 connected）就定案，并把端口记进 [resolvedDevicePort]。
        //
        // 只在首次全败时才进入；成功路径一行都不多跑。
        if (resolved == null && !connected) {
            for (candidate in CarAdbHotspotDefaults.DEVICE_ADB_PORT_CANDIDATES) {
                if (candidate == CarAdbHotspotDefaults.DEVICE_ADB_PORT) continue
                val target = "$LOOPBACK:$candidate"
                val attempt = tick("adb connect $target（候选端口）") {
                    run(
                        command = listOf(adb, "-P", PORT.toString(), "connect", target),
                        timeoutMillis = SERVER_TIMEOUT_MILLIS,
                    )
                }
                val probe = tick("adb devices（候选端口 $candidate）", ::readDevices)
                val hit = parseSerial(probe.output) ?: if (
                    CarAdbDeviceList.looksConnected(attempt.output)
                ) probe.output else null
                if (hit != null) {
                    resolved = parseSerial(probe.output)
                    connected = true
                    resolvedDevicePort = candidate
                    android.util.Log.i(
                        "XCExt.hotspot.probe",
                        "端口候选命中：adbd 在 $candidate（不是默认 5555）",
                    )
                    devices = probe
                    break
                }
            }
        }

        serial = resolved ?: CarAdbDeviceList.referenceSerial(LOOPBACK_SERIAL)

        // ⚠️ Bug 修复：清掉那个"半死不活"的 loopback 连接。
        //
        // 实测形态（14:09 日志）：`connect 127.0.0.1` 之后 devices 里
        // `127.0.0.1:5555` 是 `offline`、真正在线的是 `192.168.175.1:5555`。
        // 我们选对了序列号，但 adb 内部仍保留着那条 offline 的 loopback 记录，
        // 后续少数命令会落到它身上报 `error: device offline`（第 11~22 步全挂）。
        // 这里在选到"非 loopback"的序列号时，主动断开 loopback，避免它继续被选中。
        if (serial != null && !serial!!.startsWith(LOOPBACK)) {
            tick("adb disconnect $LOOPBACK_SERIAL") {
                run(
                    command = listOf(adb, "-P", PORT.toString(), "disconnect", LOOPBACK_SERIAL),
                    timeoutMillis = SERVER_TIMEOUT_MILLIS,
                )
            }
        }

        // 二次确认：选中的序列号能不能真的执行命令。不能则换下一个候选。
        val verified = serial?.let { s ->
            val t = System.currentTimeMillis()
            val v = confirmUsable(s, devices.output)
            android.util.Log.i(
                "XCExt.hotspot.probe",
                "connect 子步骤｜confirmUsable($s)｜${System.currentTimeMillis() - t}ms",
            )
            v
        }
        if (verified != null) serial = verified

        val detail = buildString {
            append(connect.output.trim())
            append('\n')
            append(devices.output.trim())
            if (resolved != null) {
                append("\n序列号解析为 $resolved")
                if (round > 1) append("（第 $round 次读到 `device`）")
                if (verified != null && verified != resolved) {
                    append("\n⚠️ $resolved 实测不可用，改选 $verified")
                } else if (verified == null) {
                    append("\n⚠️ $resolved 实测无响应，后续命令可能失败")
                }
            } else {
                append("\n读了 $round 次都没解析到 `device` 行；")
                if (connected) {
                    append("但 `connect` 输出含 `connected`，按参考实现的判据算连上，")
                    append("退回它的写法用 `-s $serial`（adb 会自动补 :")
                    append(CarAdbHotspotDefaults.DEVICE_ADB_PORT)
                    append("）")
                } else {
                    append("且 `connect` 输出里也没有 `connected`")
                }
            }
        }
        return CommandResult(
            exitCode = if (resolved != null || connected) 0 else 1,
            output = detail,
        )
    }

    /**
     * 确认某个序列号真的能执行命令（Bug 修复）。
     *
     * `adb devices` 里的 `device` 状态只说明 adbd 握手完成，不代表这条连接
     * 现在能用——实测存在"标着 device 但一发命令就 offline"的瞬时态。
     * 这里发一条最廉价的 `echo` 验活，避免把整条 hotspot 链挂在一个假序列号上。
     *
     * @return 能用的序列号（原样，或从 `devices` 输出里挑的下一个候选）；都不行则 null。
     */
    private fun confirmUsable(candidate: String, devicesOutput: String): String? {
        val adb = adbPath ?: return null
        fun probe(s: String): CommandResult {
            val t = System.currentTimeMillis()
            val r = run(
                command = listOf(adb, "-P", PORT.toString(), "-s", s, "shell", "echo", "dbplay-ok"),
                timeoutMillis = PRIVILEGE_TIMEOUT_MILLIS,
            )
            android.util.Log.i(
                "XCExt.hotspot.probe",
                "confirmUsable｜probe($s)｜${System.currentTimeMillis() - t}ms｜" +
                    "命中=${r.output.contains("dbplay-ok")}",
            )
            return r
        }
        if (probe(candidate).output.contains("dbplay-ok")) return candidate
        // 原候选不可用：从 devices 输出里挑其他 `device` 状态的候选再试。
        val others = devicesOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val p = line.split('\t', ' ').filter { it.isNotEmpty() }
                if (p.size < 2) null else p[0] to p[1]
            }
            .filter { (s, st) -> st == "device" && s != candidate }
            .map { it.first }
            .toList()
        for (alt in others) {
            if (probe(alt).output.contains("dbplay-ok")) return alt
        }
        return null
    }

    /**
     * 在设备上执行一条 shell 命令。
     *
     * @param privileged true 时自动加 `su 0 ` 前缀；调用方应当在 [probeRoot]
     *        之后按结果决定传什么。这里不做自动回落——回落逻辑集中在
     *        [CarAdbHotspotManager]，避免"看起来成功了其实没提权"。
     */
    fun shell(command: String, timeoutMillis: Long = SHELL_TIMEOUT_MILLIS): CommandResult {
        val adb = adbPath ?: return missingBinary()
        val target = serial
            ?: return CommandResult(1, "还没连上 adb（序列号未知），先调用 connect()。")
        val result = run(
            command = listOf(
                adb, "-P", PORT.toString(), "-s", target, "shell", command,
            ),
            timeoutMillis = timeoutMillis,
        )
        // ⚠️ Bug 修复：离线自愈。
        //
        // 实测（devlog_d5d，设备 d5ddb4312da1）热点的 22 步里，第 9 步
        // 「拷贝配置到设备」以 `error: device offline` 失败，而 connect() 那一刻
        // 明明验活通过。根因：`-s <host:port>` 指向的 adb 连接是**会漂的**——
        // 探测热点本身耗时 22.6s，等轮到拷贝配置时该连接已掉成 offline。
        // 之前的自愈只做在 connect() 里（只在会话开头一次），中段没人管。
        //
        // 现在把自愈下沉到每次 shell：一旦这次结果里出现 offline，就地重连
        // 并原样重跑一次。只重试一次，避免把真正的错误刷成循环。
        if (isDeviceOffline(result)) {
            logWarn("device offline during shell, reconnecting: $target")
            val healed = reconnect()
            if (healed) {
                val fresh = serial
                if (fresh != null) {
                    val retry = run(
                        command = listOf(
                            adb, "-P", PORT.toString(), "-s", fresh, "shell", command,
                        ),
                        timeoutMillis = timeoutMillis,
                    )
                    if (!isDeviceOffline(retry)) return retry
                    logWarn("offline persists after reconnect, giving up on: $command")
                    return retry
                }
            }
        }
        return result
    }

    /** 结果是否属于「设备离线」类失败（adb 客户端侧的可恢复错误）。 */
    private fun isDeviceOffline(result: CommandResult): Boolean {
        val text = result.output.lowercase()
        return "device offline" in text ||
            "device not found" in text ||
            "no such device" in text ||
            "error: closed" in text
    }

    /**
     * 就地重连 adb：断开僵死的序列号后重新 `connect` 并解析可用序列号。
     *
     * ⚠️ 2026-10-06 实车修正（0.10.7 诊断包 `1791279270080`）：
     *   **必须 `connect` 带端口的 [connectTarget]，不能只 `connect 127.0.0.1`。**
     *
     * 原实现发的是裸 `connect 127.0.0.1`，adb 据此注册出的序列号**不带端口**
     * （形如 `127.0.0.1`，而非 `127.0.0.1:5556`）。后果不是"连不上"，而是
     * **"连上了一条另一个名字的通道"**——此后所有 `-s 127.0.0.1:5556` 的命令
     * 都报 `error: device '127.0.0.1:5556' not found`。
     *
     * 这条 bug 在 [requestAdbRoot] 的验证循环里是**致命的**：循环每轮都
     * `reconnect()` 再 `shell("id")`，于是
     *   ① 第 N 轮 `reconnect()` 成功（但注册的是裸 `127.0.0.1`）；
     *   ② 紧接着的 `shell("id")`（走 `-s 127.0.0.1:5556`）**必然** `device not found`；
     *   ③ 循环把这个"验证失败"当成"adbd 还没切到 root"，等下一轮；
     *   ④ 预算耗尽 ⇒ 报「既不是 root，也没有可用的 su」⇒ 整条自建热点链放弃。
     *
     * 而实车证据（同一份诊断包）恰好相反：
     *   `service.adb.root = 1`、`init.svc.adbd = running`、
     *   `service.adb.tcp.port = 5556` —— **`adb root` 明明是成功的**。
     * 也就是说：**是验证步骤把自己判死了，不是车机不给权限。**
     *
     * 修法就是让它连自己那条带端口的通道，与 [connect] 用同一个 [connectTarget]，
     * 并对解析出的序列号做"是否就是目标口"的确认（见 [pickResolvedSerial]）。
     *
     * @return 是否恢复到一条可用的连接。
     */
    private fun reconnect(): Boolean = checkDedicatedPort().ok

    /**
     * 从 `adb devices` 输出里挑一个**就是目标口**的序列号。
     *
     * ⚠️ 2026-10-06 为什么不能直接用 [parseSerial]：那个函数把 `LOOPBACK_SERIAL`
     * （写死 `127.0.0.1:5556`）当首选，**但它的兜底分支会接受任意 `127.0.0.1:`
     * 或第一行**。重连场景里 devices 里可能**同时**躺着裸 `127.0.0.1`、厂商口
     * `192.168.175.1:5555` 和我们自己的 `127.0.0.1:5556`——兜底挑错就把后续命令
     * 发到一条不该用的通道上，报出一句与真实原因无关的 `device not found`。
     *
     * 所以这里用**本次实际解析到的端口** [connectTarget] 作首选，语义与 [connect]
     * 的 `LOOPBACK_SERIAL` 对齐；实在挑不到精确口才退回宽松的 [parseSerial]——
     * **保底**，绝不让"挑不到精确口"变成重连失败（那正是"自造判据比参考更严"
     * 那一类错误，本项目已因此栽过多次）。
     */
    private fun pickResolvedSerial(devicesOutput: String): String? {
        CarAdbDeviceList.pickSerial(
            output = devicesOutput,
            loopbackSerial = connectTarget,
        )?.let { return it }
        return parseSerial(devicesOutput)
    }

    /**
     * 起一个**长驻**命令（hostapd / udhcpd 都是前台运行、不会退出）。
     *
     * 不能像普通命令那样等退出——它们永远不退。这里改成"启动 + 观察
     * [settleMillis] 毫秒的早期输出"：这段时间足够 hostapd 报出
     * "interface not found" 之类的致命错误，也足够 udhcpd 报出配置解析失败。
     *
     * ⚠️ 本地 adb 客户端进程会一直挂着（远端进程由 adbd 持有），
     * 由 [stop] 统一回收。
     */
    fun shellDetached(command: String, settleMillis: Long = DETACHED_SETTLE_MILLIS): CommandResult {
        val adb = adbPath ?: return missingBinary()
        val target = serial
            ?: return CommandResult(1, "还没连上 adb（序列号未知），先调用 connect()。")
        val result = runDetached(
            command = listOf(adb, "-P", PORT.toString(), "-s", target, "shell", command),
            settleMillis = settleMillis,
        )
        // 同 [shell]：中段连接漂成 offline 时不放弃，先自愈再重来一次。
        if (isDeviceOffline(result)) {
            logWarn("device offline during detached shell, reconnecting: $target")
            if (reconnect()) {
                val fresh = serial
                if (fresh != null) {
                    val retry = runDetached(
                        command = listOf(adb, "-P", PORT.toString(), "-s", fresh, "shell", command),
                        settleMillis = settleMillis,
                    )
                    if (!isDeviceOffline(retry)) return retry
                    return retry
                }
            }
        }
        return result
    }

    /**
     * 把本机文件推到设备（`adb push`）。
     *
     * ## 为什么必须有这一步（2026-10-11，ADB 静默安装失败的根因）
     *
     * `pm install <路径>` 里那个路径是**由 adbd 起的 shell 进程去读**的。
     * 而 DBPlay 下载的安装包落在**应用私有目录**
     * （`/data/user/0/<pkg>/files/update/…`，mode 0700，owner = 应用 uid）。于是：
     *
     * - adbd 是 `shell`(uid=2000) 时 —— **读不到**，`pm install` 必然失败；
     * - 只有 adbd 恰好是 root 才读得到，而"这台车 adbd 是不是 root"不该是安装的前提。
     *
     * 之前那条实现（`CarApkInstaller.installViaAdb`）直接把私有路径塞给
     * `pm install`，正是踩在这里。
     *
     * `adb push` 的**读方是 adb 客户端进程**（= 本应用，对自己 filesDir 有完全权限），
     * **写方是 adbd**（写到 `/data/local/tmp`，shell 可读可执行）。这一读一写
     * 正好把"应用私有文件"变成"shell 能读的文件"，而且**不需要 root**。
     *
     * ## 线程
     *
     * 阻塞（几十 MB 实车几十秒），只能在 worker 线程调用。
     *
     * @param local 本机文件（应用私有目录里的 APK）。
     * @param remotePath 设备上的目标路径，如 `/data/local/tmp/dbplay-update.apk`。
     */
    fun push(local: File, remotePath: String, timeoutMillis: Long = PUSH_TIMEOUT_MILLIS): CommandResult {
        val adb = adbPath ?: return missingBinary()
        val target = serial
            ?: return CommandResult(1, "还没连上 adb（序列号未知），先调用 connect()。")
        if (!local.isFile) {
            return CommandResult(1, "本机文件不存在：${local.absolutePath}")
        }
        return run(
            command = listOf(
                adb, "-P", PORT.toString(), "-s", target,
                "push", local.absolutePath, remotePath,
            ),
            timeoutMillis = timeoutMillis,
        )
    }

    /**
     * 探测 adb shell 能不能到 root。
     *
     * 两种车机形态都要覆盖：
     * - **adbd 本身就是 root**（userdebug 车机）：裸命令就是 root，`su` 反而不一定存在；
     * - **adbd 是 shell，但设备有 `su`**：必须加 `su 0 ` 前缀。
     *
     * @return 应该给其它命令加的前缀（`""` 或 `"su 0 "`），null 表示两条都不通。
     */
    fun probePrivilege(allowAdbRoot: Boolean = false): PrivilegeProbe {
        val bare = shell("id", PRIVILEGE_TIMEOUT_MILLIS)
        if (bare.ok && bare.output.contains("uid=0")) {
            return PrivilegeProbe(prefix = "", detail = "adb shell 本身就是 root：${bare.output.trim()}")
        }

        // ⚠️ 2026-10-05（照 DBTool3.0 的安卓 9 方案重写，只照思路）：
        //    **主动请求 adbd 切到 root —— 这一步以前没有，是"换台车就开不了热点"的根因之一。**
        //
        // 背景（实测 + 3.0 实车结论）：
        //   本平台（领克 CS11 / 新龙鹰家族）是 **userdebug 固件**（`ro.build.type=userdebug`、
        //   `ro.debuggable=1`），adbd **允许被 `root:` 请求切成 root 模式**——这是 PC 端
        //   `adb root` 的等价物，不需要任何厂商后门或 root 管理器。
        //   但 `ro.secure=1` 让 adbd **默认以 shell(uid=2000) 跑**，所以"能 root"不等于"已是 root"。
        //
        //   以前我们只"看" adbd 是不是 root（裸 id）和有没有 su —— 两个都没有就判失败。
        //   于是换一台车，只要它的 adbd 还没被切成 root，我们就地放弃，热点永远开不起来。
        //   3.0 的结论：**缺的就是"主动 adb root"这一环**。
        //
        // 做法（4 步，与 PC 端 `adb root` 同义）：
        //   1) 环境判定：`ro.debuggable=1` 或 `ro.build.type=userdebug/eng` 才试，
        //      否则直接跳过（普通 user 固件发了也没用，白等 2.5s×N）；
        //   2) 发 `adb root`（adbd 会**重启自己**，旧连接断开属预期，不是失败）；
        //   3) 等 adbd 起来（首次 2.5s），重连；
        //   4) 重连后验 `uid=0`，最多重试 [SERIAL_ATTEMPTS] 次。
        val rooted = if (allowAdbRoot) requestAdbRoot() else null
        if (!allowAdbRoot) {
            logInfo("日常 ADB 检查不请求 adb root；仅初始化操作允许显式提权")
        } else if (rooted == null) {
            // 环境不支持 → 不再等，直接走原有的 su 兜底
            logInfo("adb root：本机环境不支持（非 userdebug/debuggable），跳过主动提权")
        } else if (rooted.prefix != null) {
            return rooted
        }

        val su = shell("su 0 id", PRIVILEGE_TIMEOUT_MILLIS)
        if (su.ok && su.output.contains("uid=0")) {
            return PrivilegeProbe(prefix = "su 0 ", detail = "经 `su 0` 提权成功：${su.output.trim()}")
        }
        return PrivilegeProbe(
            prefix = null,
            detail = "既不是 root，也没有可用的 su。\n" +
                "裸 `id` → ${bare.summary}\n" +
                "`su 0 id` → ${su.summary}",
        )
    }

    /**
     * 主动请求 adbd 切到 root（等价 PC 端 `adb root`）—— 照 DBTool3.0 安卓 9 方案重写。
     *
     * ## 为什么要它
     *
     * 本平台车机（领克 CS11 / 新龙鹰家族）是 **userdebug 固件**：
     * `ro.build.type=userdebug`、`ro.debuggable=1`。这类固件的 adbd
     * **允许被 `root:` 请求切成 root 模式**，不需要 root 管理器、不需要厂商后门。
     * 但 `ro.secure=1` 让 adbd **默认跑在 shell(uid=2000)**，"能 root"≠"已是 root"。
     *
     * 3.0 的实车结论很明确：缺的就是**主动 `adb root` 这一环**。
     * 只"看"现状（裸 id / su）而不主动提权，换一台车只要 adbd 还没切成 root 就地失败。
     *
     * ## 三步（与 PC 端同义）
     *
     * 1. 环境判定：`ro.debuggable=1` 或 `ro.build.type` = userdebug/eng 才试；
     * 2. 发 `adb root`——**adbd 会重启自己**，命令的退出码/输出不可靠，不做判据；
     * 3. 等 adbd 起来（首次 2.5s）→ `connect` 重连 → 验 `uid=0`，最多 [SERIAL_ATTEMPTS] 轮。
     *
     * ## 返回值三态
     *
     * - `null`：**本机环境不支持**（非 userdebug/debuggable）→ 上层不等待、直接走 su 兜底；
     * - `prefix != null`：提权成功，后续命令要带这个前缀；
     * - `prefix == null`：环境支持但没切成 root → 上层继续 su 兜底。
     *
     * 全程**不抛异常、不阻塞超过约 [ROOT_NEGOTIATION_BUDGET_MILLIS]**，失败即静默降级。
     */
    fun requestAdbRoot(): PrivilegeProbe? {
        if (adbPath == null) return null
        if (serial == null) return null

        // ---- 1. 环境判定：只有 userdebug / debuggable 固件才允许 root ----
        val debuggable = shell("getprop ro.debuggable", PRIVILEGE_TIMEOUT_MILLIS)
            .output.trim()
        val buildType = shell("getprop ro.build.type", PRIVILEGE_TIMEOUT_MILLIS)
            .output.trim()
        val secure = shell("getprop ro.secure", PRIVILEGE_TIMEOUT_MILLIS)
            .output.trim()
        val capable = secure == "0" ||
            debuggable == "1" ||
            buildType.equals("userdebug", ignoreCase = true) ||
            buildType.equals("eng", ignoreCase = true)
        if (!capable) {
            logInfo(
                "adb root 不适用：ro.debuggable=$debuggable ro.build.type=$buildType " +
                    "ro.secure=$secure（非 userdebug/eng，跳过）",
            )
            return null
        }

        val started = System.currentTimeMillis()
        logInfo("adb root：环境可提权（ro.debuggable=$debuggable type=$buildType），开始请求")

        // ---- 2. 发 `adb root`：adbd 会重启，这条命令失败属预期 ----
        val adb = adbPath ?: return null
        val target = serial ?: return null
        val rootCmd = run(
            command = listOf(adb, "-P", PORT.toString(), "-s", target, "root"),
            timeoutMillis = ROOT_REQUEST_TIMEOUT_MILLIS,
        )
        logInfo(
            "adb root 已发出（${System.currentTimeMillis() - started}ms，exit=${rootCmd.exitCode}）；" +
                "adbd 重启属正常，继续重连验证",
        )

        // ---- 3. 等 adbd 起来 → 重连 → 验 uid=0 ----
        var attempt = 0
        while (attempt < SERIAL_ATTEMPTS) {
            Thread.sleep(if (attempt == 0) ROOT_FIRST_WAIT_MILLIS else ROOT_RETRY_WAIT_MILLIS)
            attempt += 1
            if (System.currentTimeMillis() - started > ROOT_NEGOTIATION_BUDGET_MILLIS) {
                logWarn("adb root：重试预算耗尽（${System.currentTimeMillis() - started}ms），放弃")
                break
            }
            reconnect()
            val id = shell("id", PRIVILEGE_TIMEOUT_MILLIS)
            if (id.ok && id.output.contains("uid=0")) {
                val cost = System.currentTimeMillis() - started
                logInfo("adb root 成功（第 $attempt 次验证，共 ${cost}ms）：${id.output.trim()}")
                return PrivilegeProbe(
                    prefix = "",
                    detail = "经 `adb root` 提权成功（第 $attempt 次验证，${cost}ms）：${id.output.trim()}",
                )
            }
            logInfo("adb root 第 $attempt 次验证未生效：${id.summary}")
        }

        logWarn("adb root：${System.currentTimeMillis() - started}ms 内未切成 root，回落 su 兜底")
        return PrivilegeProbe(
            prefix = null,
            detail = "已请求 `adb root` 但 adbd 未切到 root（${System.currentTimeMillis() - started}ms）",
        )
    }

    /**
     * 【独占专用口】把车机 adbd 切到 [CarAdbHotspotDefaults.DEVICE_ADB_PORT]（5556），再重连验证。
     *
     * ⚠️ 2026-10-05（斌哥定案·最高口径）：
     *   「Root 都是我们自己搞的，防止出意外还是改成 5556，因为有的车型还没开启 ADB，避免出问题。」
     *
     * ## 为什么
     *
     * 5555 是**车机厂商自己的口**（实车 `persist.adb.tcp.port=5555`，且车机里还有独立
     * 的 ADB 引导组件在反复操作 adbd）。我们蹭它有两个风险：
     *   ① 厂商组件一重启 adbd，我们的通道就断；
     *   ② **换一台 ADB 没开的车**，5555 压根没人听 —— 我们连入口都没有。
     * 所以拿到 root 后立刻把 adbd 切到 **我们自己的 5556**，从此不碰厂商的口。
     *
     * ## 前提与幂等
     *
     * 需要 root（调用方先 [probePrivilege] 拿到 `prefix`，如 `"su 0 "` 或 `""`）。
     * 已是 5556 就**一条都不写**（只读回 `init.svc.adbd`）；值不同才 `setprop` 改写
     * 并**重启 adbd**——端口属性只在 adbd 启动时被读取，不重启它就不会真的换口。
     *
     * ⚠️ 2026-10-16 修正：早先这里**刻意不 stop**，理由是"stop 会掐断我们自己这条连接"。
     * 那个顾虑对，但结论错了——不重启 adbd 就换不了口，于是换口只能靠
     * `DeviceAdbEnabler` 那条**连接之前**的反射路径（它确实会 stop/start）。
     * 现在改为：**重启动作脱离当前 shell**（见 [restartAdbdDetached]），
     * 既换得成口，又不会被自己的 stop 掐死。
     *
     * ## 判据（2026-10-16 修正，见 [AdbPortVerdict]）
     *
     * [DedicatedPortResult.ok] = **实连 127.0.0.1:5556 + shell 验活通过**，
     * 不是"属性读回对不对"，也不是"脚本退出码是不是 0"。
     * 属性读回 / adbd 状态 / 退出码全部只进 [DedicatedPortResult.detail] 作解释。
     * 这条修正是针对实车那种"三条属性都是 5556、却因为一条子命令 not found
     * 导致整条脚本退出码 127 而被判失败"的误报（需求第 3、4 条）。
     *
     * ## 持久化（2026-10-06 加）
     *
     * 除了瞬态的 `service.adb.tcp.port`，**同时写 `persist.adb.tcp.port`**
     * （[CarAdbHotspotDefaults.ADB_PORT_PROPERTY_PERSIST]）。
     * 前者重启即丢，只写它的话"这次开好 5556、重启又回厂商的 5555"，
     * 用户每次上车都要重开一遍 ADB。`persist.*` 由 init 存盘、开机回填，
     * 写一次就长期生效。详见该常量的 KDoc。
     *
     * ⚠️ 两条属性必须**写成同一个值**：若只改瞬态的不改持久的，
     * 重启后会出现"持久值 5555、本次值 5556"的撕裂，届时 adbd 按哪个起
     * 取决于 init 的实现细节，排障时极难解释。
     *
     * （2026-10-16 措辞修正：这两条现在是**读回不同才写**，而不是"无条件两条都写"。
     * 语义没变 —— 只要不同就一定会被写成目标口；改成条件写只是为了不再做无谓写入，
     * 因为复用路径（端口已可用）也要走这段，而那时**不允许**留下任何多余动作。）
     *
     * ## 失败不阻断
     *
     * 属性只读 / SELinux 拒绝 / 这台车不吃这套 ⇒ 返回 `ok=false`，
     * 调用方保留原 [resolvedDevicePort] 继续用（老路照走），
     * 不会因为"切不过去"把整条热点流程拖死。
     *
     * @param prefix 已确认的提权前缀（`"su 0 "` 或 `""`）
     */
    fun switchToDedicatedPort(prefix: String): DedicatedPortResult {
        val target = CarAdbHotspotDefaults.DEVICE_ADB_PORT
        val targetText = target.toString()
        val prop = CarAdbHotspotDefaults.ADB_PORT_PROPERTY
        val adb = adbPath

        // ⚠️ 2026-10-16 为什么把「一条大脚本」改成「多条单命令」：
        //
        // 旧实现把读值 / 写值 / 起 adbd / 复检压进**一条** sh 脚本（含 $() 命令替换、
        // if…then…fi 分支、反斜杠转义），再用这条脚本的**进程退出码**当判据。
        // 两个后果都真实发生过：
        //
        // ① shell 只要有一条子命令 not found（现场就是 /system/bin/sh: 5556: not found），
        //    整条脚本就非零退出 ⇒ 明明三条属性都已读回 5556，却被判成「切换失败」；
        // ② 端口号是拼进脚本正文的**裸值**，拼接/引号只要错位一处，它就会落到命令位，
        //    被 shell 当成「要执行的程序名」—— 这正是日志里那条 127 的形态。
        //
        // 现在每一步都是**单命令**：getprop <key> / setprop <key> <value> /
        // setprop ctl.start adbd。端口只出现在 setprop 的**参数位**，不可能被当成命令；
        // 判据取**读回值 + 实连验活**，退出码只作证据（见 AdbPortVerdict）。
        val before = readProp(prefix, prop).output.trim()
        var switchWrite: CommandResult? = null
        if (before != targetText) {
            switchWrite = writeProp(prefix, prop, targetText)
            restartAdbdDetached(prefix)
        }
        // 持久化两条：**非破坏**（不重启 adbd，本次不生效就等下次开机）。
        val align = alignPersistentPort(prefix)
        val nowPort = readProp(prefix, prop).output.trim()
        val adbdState = if (switchWrite != null) {
            awaitAdbdState(prefix)
        } else {
            readProp(prefix, CarAdbHotspotDefaults.ADB_STATE_PROPERTY).output.trim()
        }
        // adbd 已切到新口：把本地 server 与旧口断开，重连（复用 [connect]，它自带
        // 端口解析 + 候选轮试 + 序列号解析，不必另造一套）。
        //
        // ⚠️ 2026-10-16：这里原本漏了 adb 可执行文件名（`listOf("-P", …)`），
        //    于是每次都以 `启动失败：IOException` 收场 —— 一条永远失败、又没人
        //    读返回值的命令。它是"旧口残留 + 新口重连"之间的清理动作，
        //    失败不影响后续 connect（那条自己会重连），但不能继续假装它在跑。
        if (adb != null) {
            run(
                command = listOf(adb, "-P", PORT.toString(), "disconnect", connectTarget),
                timeoutMillis = SERVER_TIMEOUT_MILLIS,
            )
        }
        val reconnect = connectDedicatedPort()

        // **权威判据：能不能连上 + 能不能跑命令**。属性读回、adbd 状态、脚本退出码
        // 都只用来解释「为什么」，不参与「成不成」（需求原文：不能仅凭配置属性或
        // 端口号判断 ADB 已成功开启）。
        val verdict = AdbPortVerdict.judge(
            snapshot = DeviceAdbEnabler.PortSnapshot(
                transient = nowPort,
                persist = align.persistReadback,
                vendor = align.vendorReadback,
                adbdState = adbdState,
            ).toVerdictSnapshot(
                // 退出码取「真正会改端口的那条命令」的结果；本次没写就是 0。
                shellExitCode = switchWrite?.exitCode ?: 0,
                connectionVerified = reconnect.ok,
            ),
            targetPort = target,
        )
        if (verdict.currentUsable) resolvedDevicePort = target
        val switchNote = buildString {
            if (before != targetText) {
                append("检测到运行端口=").append(before.ifEmpty { "未读到" })
                append(" ⇒ 已写 $targetText 并重启 adbd；")
            } else {
                append("运行端口本就是 $targetText；")
            }
            if (align.written) append("持久属性已按需对齐；")
        }
        return DedicatedPortResult(
            ok = verdict.currentUsable,
            detail = switchNote + verdict.detail + " 重连结果：${reconnect.summary}",
            port = if (verdict.currentUsable) target else resolvedDevicePort,
            persistentPortVerified = verdict.persistentVerified,
        )
    }

    /**
     * **只读**：读一条系统属性（2026-10-16）。
     *
     * 命令是**一条单命令**：`<prefix>/system/bin/getprop <key>`。键名来自常量，
     * 读回值**不参与任何拼接 / 分支**，所以不存在「值被当成命令执行」的可能；
     * 属性不存在时输出为空、退出码 0。
     */
    fun readProp(prefix: String, key: String): CommandResult =
        shell("${prefix}/system/bin/getprop $key", PRIVILEGE_TIMEOUT_MILLIS)

    /**
     * 写一条系统属性（2026-10-16）。
     *
     * ⚠️ 值只出现在 `setprop` 的**参数位**，绝不拼进命令位。幂等由调用方保证
     * （先读回、不同才写），避免反复重写同一条持久属性。
     */
    fun writeProp(prefix: String, key: String, value: String): CommandResult =
        shell("${prefix}/system/bin/setprop $key $value", PRIVILEGE_TIMEOUT_MILLIS)

    /**
     * **重启 adbd，但脱离当前这条 shell**（2026-10-16）。
     *
     * ## 为什么必须脱离
     *
     * `setprop ctl.stop adbd` 会停掉 adbd，而**我们这条 shell 就挂在 adbd 上**。
     * 写在同一条命令里，`ctl.start` 那一半根本没机会执行 —— 现场表现是
     * 「端口写进去了、adbd 却再没起来」，比不写更糟。
     *
     * ## 做法
     *
     * 把 stop/start 丢进 `( … ) &` 子 shell：父 shell 立刻返回，子 shell 被 init
     * 收养后继续跑；开头的 `sleep 1` 是留给客户端把本条命令的输出收完。
     * 这与本工程起 hostapd / udhcpd 的写法同源 —— 都是「父通道会消失，
     * 子进程必须活下来」。
     *
     * ⚠️ 返回值**不代表 adbd 已起来**（它只表示重启请求已发出）。
     * 真正的判据是随后的 [awaitAdbdState] + 重连验活。
     */
    private fun restartAdbdDetached(prefix: String) {
        val setprop = "${prefix}/system/bin/setprop"
        shell(
            "( sleep 1; $setprop ctl.stop adbd; $setprop ctl.start adbd ) >/dev/null 2>&1 &",
            PRIVILEGE_TIMEOUT_MILLIS,
        )
    }

    /**
     * 等 adbd 离开中间态（有界轮询，2026-10-16）。
     *
     * `ctl.stop` / `ctl.start` 之后 `init.svc.adbd` 会依次经过 `stopping` →
     * `starting` → `running`。**读到一次 `stopping` 就判失败**，正是
     * 「三条属性全对却报未通过读回验证」的直接成因，所以这里最多轮询
     * [ADBD_WAIT_ATTEMPTS] 次、每次间隔 [ADBD_WAIT_INTERVAL_MILLIS]。
     *
     * @return 最后一次读到的 `init.svc.adbd`（空串 = 未读到，**不当作 running**）。
     */
    private fun awaitAdbdState(prefix: String): String {
        val key = CarAdbHotspotDefaults.ADB_STATE_PROPERTY
        var state = ""
        var attempt = 0
        while (attempt <= ADBD_WAIT_ATTEMPTS) {
            val read = readProp(prefix, key)
            state = read.output.trim()
            // 到 running ⇒ 结束。
            if (state == AdbPortVerdict.ADBD_RUNNING) return state
            // ⚠️ 读**成功**但值为空 = 这台 ROM 没有这条属性（或 adbd 服务没定义），
            //    再等也不会变 —— 立刻退出，如实报「未读到」。
            if (read.ok && state.isEmpty()) return ""
            // 其余情况继续等：
            //   · 读到了 stopping / restarting 等中间态；
            //   · **命令本身就失败**（adbd 正在重启，这一条 adb shell 连不上）——
            //     这是最该等的时刻，早退就等于"重启没等完就下结论"。
            if (attempt == ADBD_WAIT_ATTEMPTS) break
            try {
                Thread.sleep(ADBD_WAIT_INTERVAL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            attempt += 1
        }
        return state
    }

    /** [alignPersistentPort] 的结果。 */
    data class PersistentAlignResult(
        /** 本轮是否真的写过至少一条属性。 */
        val written: Boolean,
        /** `persist.adb.tcp.port` 的读回值。 */
        val persistReadback: String,
        /** `persist.adb.tcpip`（厂商那条）的读回值。 */
        val vendorReadback: String,
    )

    /**
     * **只读**：回填源两条（`persist.adb.tcp.port` / `persist.adb.tcpip`）的当前值
     * （2026-10-16）。
     *
     * 与 [alignPersistentPort] 的分工：本方法**一个字都不写**，因此**不需要 root**
     * （`getprop` 普通 shell 就能读）。它的用途是回答「重启后还能不能用」——
     * 没有 root 时也照样能答，只要那两条本来就已经对齐。
     */
    fun readPersistentPorts(prefix: String = ""): PersistentAlignResult = PersistentAlignResult(
        written = false,
        persistReadback = readProp(
            prefix,
            CarAdbHotspotDefaults.ADB_PORT_PROPERTY_PERSIST,
        ).output.trim(),
        vendorReadback = readProp(
            prefix,
            CarAdbHotspotDefaults.ADB_VENDOR_TCPIP_PROPERTY,
        ).output.trim(),
    )

    /**
     * **只对齐「重启回填源」两条属性**（2026-10-16）。
     *
     * 与 [switchToDedicatedPort] 里换口那段的区别：本方法**不碰瞬态口、不重启 adbd**，
     * 因此对**当下正在用的通道零影响**。这正是「检测到 5556 已经可用 ⇒ 直接复用」
     * 那条路径需要的能力 —— 复用不能再换口，但可以顺手把持久属性补齐，
     * 让「重启后还能不能用」有一个可读回的答案（需求：端口已开时要区分持久性）。
     *
     * ⚠️ 写 `persist.*` 需要 root；只读请用 [readPersistentPorts]。
     *
     * 幂等：读回已是目标口就一条都不写。
     */
    fun alignPersistentPort(prefix: String): PersistentAlignResult {
        val target = CarAdbHotspotDefaults.DEVICE_ADB_PORT.toString()
        val persistProp = CarAdbHotspotDefaults.ADB_PORT_PROPERTY_PERSIST
        val vendorProp = CarAdbHotspotDefaults.ADB_VENDOR_TCPIP_PROPERTY
        val before = readPersistentPorts(prefix)
        var written = false
        var persistReadback = before.persistReadback
        if (persistReadback != target) {
            writeProp(prefix, persistProp, target)
            persistReadback = readProp(prefix, persistProp).output.trim()
            written = true
        }
        var vendorReadback = before.vendorReadback
        if (vendorReadback != target) {
            writeProp(prefix, vendorProp, target)
            vendorReadback = readProp(prefix, vendorProp).output.trim()
            written = true
        }
        return PersistentAlignResult(
            written = written,
            persistReadback = persistReadback,
            vendorReadback = vendorReadback,
        )
    }

    /** [switchToDedicatedPort] 的结果。 */
    data class DedicatedPortResult(
        /** 固定端口配置并通过 5556 重连是否成功。 */
        val ok: Boolean,
        /** 给自检页看的单行摘要。 */
        val detail: String,
        /** 切换后**实际**在用的端口（失败时为原端口）。 */
        val port: Int,
        /** persist.adb.tcp.port 是否在 ADB shell 写入后确实读回 5556。 */
        val persistentPortVerified: Boolean,
    )

    /**
     * 只读探测：部署路径上的 busybox 是否可用。
     *
     * 与 [stageBusybox] 的区别是**它不部署**——自检页承诺"只读"，不能一边自检
     * 一边往 `/data/local/tmp` 写文件。所以这里只问一句"现在能用吗"。
     */
    fun probeStagedBusybox(): CommandResult {
        val target = CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH
        val probe = shell(CarAdbHotspotCommands.stagedBusyboxProbe(target))
        val ok = probe.output.contains("Usage:", ignoreCase = true)
        return CommandResult(
            exitCode = if (ok) 0 else 1,
            output = if (ok) {
                "$target 可用，applet 分发正常。"
            } else {
                "$target 尚不可用（自检只读、不会去部署它）。真正拉起热点时才会拷贝：\n" +
                    probe.output.trim()
            },
        )
    }

    // ------------------------------------------------------------ 收尾

    /** `adb -P 5038 kill-server`，并回收所有长驻客户端进程。 */
    fun killServer() {
        val adb = adbPath
        if (adb != null) {
            runCatching {
                run(
                    command = listOf(adb, "-P", PORT.toString(), "kill-server"),
                    timeoutMillis = SERVER_TIMEOUT_MILLIS,
                )
            }
        }
        stopDaemons()
        serial = null
        serverStarted.set(false)
    }

    /**
     * 只回收**本地** adb 客户端进程。
     *
     * ⚠️ 这**不会**停掉车机上已经起来的 hostapd / udhcpd——它们由 adbd 持有。
     * 要停远端进程必须显式执行 `killall`（见 [CarAdbHotspotManager.stop]）。
     */
    fun stopDaemons() {
        synchronized(daemons) {
            daemons.forEach { process -> runCatching { process.destroy() } }
            daemons.clear()
        }
    }

    // ------------------------------------------------------------ 底层

    private fun missingBinary(): CommandResult =
        CommandResult(1, "随包二进制不可用，先调用 prepare() 并检查它的结论。")

    private fun run(command: List<String>, timeoutMillis: Long): CommandResult {
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment().putAll(childEnvironment()) }
                .start()
        } catch (error: Exception) {
            return CommandResult(
                exitCode = 1,
                output = "启动失败：${error.javaClass.simpleName}: ${error.message}",
            )
        }
        return try {
            process.outputStream.close()
            // ⚠️ 必须先读完再 waitFor：输出超过管道缓冲区（64KB）时，
            // 先 waitFor 会死锁。读与等待并行，读线程负责把流读干。
            val sink = StringBuilder()
            val reader = drain(process.inputStream, sink)
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(READER_JOIN_MILLIS)
                return CommandResult(TIMEOUT_EXIT_CODE, sink.toString())
            }
            reader.join(READER_JOIN_MILLIS)
            CommandResult(process.exitValue(), sink.toString())
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            CommandResult(1, "被中断：${interrupted.message}")
        }
    }

    /**
     * 执行命令，但在**输出命中 [successPattern] 时立即判成功并返回**，不等进程退出。
     *
     * ## ⚠️ 2026-10-05 实测更正（重要，别再把这里当"启动慢"的修复）
     *
     * 这个函数**不是**「启动还是比较慢」那个 20 秒的修复。它当初是照着
     * 「adb start-server 打印就绪后自己不退出、`run()` 傻等烧满
     * SERVER_TIMEOUT_MILLIS」这个推断写的，而该推断**已被实验证伪**：
     *
     * 在本机用真 adb 二进制做对照实验（旧 `run()` vs 本函数），两种写法都是
     * **0.33 秒** —— `adb start-server` 起完 daemon 就正常退出了。
     *
     * 车机上那 20 秒的真实落点在 `DeviceAdbEnabler.enable()` 的
     * `Class.forName("android.fxbridge.FxBridge")`，见该文件注释与
     * `DeviceAdbEnablerPlatformGateTest`。
     *
     * ## 本函数保留的理由
     *
     * 它解决的是**另一类**问题："命令的**输出**就是结论，而进程未必退出"
     * （典型如某些常驻式 CLI、或未来换成不 fork 的 adb 版本）。
     * 目前**没有调用点** —— 保留为工具箱能力，而不是当某个 bug 的修复。
     *
     * 语义：命中输出即判成功，随后 `destroyForcibly()` 收掉可能挂着的进程。
     */
    private fun runUntilOutput(
        command: List<String>,
        timeoutMillis: Long,
        successPattern: Regex,
    ): CommandResult {
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment().putAll(childEnvironment()) }
                .start()
        } catch (error: Exception) {
            return CommandResult(
                exitCode = 1,
                output = "启动失败：${error.javaClass.simpleName}: ${error.message}",
            )
        }
        val sink = StringBuilder()
        return try {
            process.outputStream.close()
            val reader = drain(process.inputStream, sink)
            val deadline = System.currentTimeMillis() + timeoutMillis
            var matched = false
            while (System.currentTimeMillis() < deadline) {
                if (successPattern.containsMatchIn(snapshot(sink))) {
                    matched = true
                    break
                }
                if (!process.isAlive) break
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
            // 命中后把读线程收干净，但不为它无限等：daemon 还挂着，流不会 EOF。
            if (matched) {
                collectUntilQuiet(reader, sink)
                process.destroyForcibly()
                return CommandResult(0, snapshot(sink))
            }
            // 没命中：退回常规等待，超时行为与 [run] 一致。
            val finished = process.waitFor(
                (deadline - System.currentTimeMillis()).coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
            if (!finished) {
                process.destroyForcibly()
                reader.join(READER_JOIN_MILLIS)
                return CommandResult(TIMEOUT_EXIT_CODE, snapshot(sink))
            }
            reader.join(READER_JOIN_MILLIS)
            CommandResult(process.exitValue(), snapshot(sink))
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            CommandResult(1, "被中断：${interrupted.message}")
        }
    }

    private fun snapshot(sink: StringBuilder): String = synchronized(sink) { sink.toString() }

    /**
     * 命中成功标记后，再给读线程一小段时间把**紧随其后**的输出捞进来，
     * 然后返回。daemon 挂着的进程不会 EOF，所以不能 `join()` 到底。
     */
    private fun collectUntilQuiet(reader: Thread, sink: StringBuilder) {
        var lastLength = -1
        val quietDeadline = System.currentTimeMillis() + QUIET_SETTLE_MILLIS
        while (System.currentTimeMillis() < quietDeadline && reader.isAlive) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            val now = snapshot(sink).length
            if (now == lastLength && now > 0) return
            lastLength = now
        }
    }

    private fun runDetached(command: List<String>, settleMillis: Long): CommandResult {
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment().putAll(childEnvironment()) }
                .start()
        } catch (error: Exception) {
            return CommandResult(
                exitCode = 1,
                output = "启动失败：${error.javaClass.simpleName}: ${error.message}",
            )
        }
        process.outputStream.close()
        val sink = StringBuilder()
        val reader = drain(process.inputStream, sink)
        synchronized(daemons) { daemons += process }
        // 观察一小段：既拿到致命错误，也不至于把会话启动拖太久。
        runCatching { reader.join(settleMillis) }
        return CommandResult(if (process.isAlive) 0 else process.exitValue(), sink.toString())
    }

    /**
     * 起一个 daemon 线程把流读干，返回该线程。
     *
     * 必须用独立线程：调用方要能同时"等超时"和"读流"。
     */
    private fun drain(stream: InputStream, sink: StringBuilder): Thread {
        val thread = Thread({
            runCatching {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(sink) { sink.append(buffer, 0, read) }
                    }
                }
            }
        }, "dbplay-adb-drain")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * 子进程环境。
     *
     * `HOME` / `TMPDIR` 必须指向**可写**目录：adb 要在这里生成并缓存
     * `adbkey` / `adbkey.pub`，否则每次 `start-server` 都会重新生成密钥，
     * 车机侧就要反复授权。
     */
    private fun childEnvironment(): Map<String, String> = mapOf(
        "HOME" to homeDir.absolutePath,
        "TMPDIR" to homeDir.absolutePath,
        // ⚠️ 2026-10-05：实车 20 秒（后测 10 秒）真凶 = adb 的 USB 设备轮询。
        //
        // 20:01 日志铁证：
        //   20:01:13.087  adb connect 127.0.0.1 开始
        //   20:01:16.610  adb(device poll 线程) avc: denied open /dev/bus/usb/002/001
        //   20:01:24.620  adb(device poll)     avc: denied read usb
        //   20:01:29.630  同上（每 5 秒重试一次，一直挂着）
        //   20:01:23.071  adb connect 返回，本段 9983ms
        //
        // adb 启动后必开一个 `device poll` 后台线程枚举 USB 设备；车机 SELinux
        // 不许 adb 读 /dev/bus/usb ⇒ 线程卡在重试上，`adb connect` 等它超时才返回。
        // 我们只用 TCP 连本机 adbd，**根本不需要 USB 传输**，关掉它就绕开这 10 秒。
        //
        // 两个开关都设上（不同 adb 版本认不同的那个）：
        //   ADB_LIBUSB=0   —— 回退到"不枚举 USB 的"旧传输路径
        //   ADB_MDNS=0     —— 顺带关掉 mDNS 自动发现（车机上同样会去扫网络）
        "ADB_LIBUSB" to "0",
        "ADB_MDNS" to "0",
    )

    /**
     * 从 `adb devices` 输出里挑出序列号。
     *
     * 判据本体在 [CarAdbDeviceList.pickSerial]（纯函数、有单测）——
     * 放在 `:car:api` 是因为它是"解析一段 shell 输出"，与本类的进程管理无关，
     * 留在这一层就只能靠上车试。
     */
    private fun parseSerial(output: String): String? =
        CarAdbDeviceList.pickSerial(
            output = output,
            loopbackSerial = LOOPBACK_SERIAL,
        )

    /** 一步的结论。 */
    data class StepOutcome(val ok: Boolean, val detail: String)

    /** 提权探测结果。 */
    data class PrivilegeProbe(val prefix: String?, val detail: String)

    companion object {
        private const val PORT = CarAdbHotspotDefaults.ADB_SERVER_PORT
        private const val LOOPBACK = "127.0.0.1"

        /**
         * 进程内日志出口。
         *
         * `car:core-bridge` 是**纯 JVM 模块**（不依赖 `android.util.Log`，以便
         * 单测直接跑），所以这里不引 Log，改用一个可替换的函数引用：上层
         * （`car:app`）在自己模块里把它指向 `Log.i/w`，单测里保持为 no-op。
         */
        @JvmStatic
        @Volatile
        var logger: (level: String, message: String) -> Unit = { _, _ -> }

        private fun logInfo(message: String) = logger("I", message)

        private fun logWarn(message: String) = logger("W", message)

        /**
         * `connect` 注册出来的完整序列号（带端口）。
         *
         * [CarAdbDeviceList.pickSerial] 用它优先匹配；解析不到时
         * [CarAdbDeviceList.referenceSerial] 会把它切成 `127.0.0.1` 当兜底。
         *
         * ⚠️ 2026-10-05：**不再写死 5555**——按本次实际解析到的 [resolvedDevicePort]
         * 拼接，否则车机 adbd 在非默认端口时会 disconnect/匹配错目标。
         */
        private val LOOPBACK_SERIAL: String
            get() = LOOPBACK + ":" + CarAdbHotspotDefaults.DEVICE_ADB_PORT

        /**
         * `0755`。
         *
         * Kotlin 没有八进制字面量，所以写十六进制。`0x1ED == 493 == 0755`，
         * 与参考实现里那个 `Os.chmod(path, 493)` 是同一个值。
         */
        private const val MODE_0755 = 0x1ED

        private const val REQUIRED_ABI_TEXT = CarAdbHotspotDefaults.REQUIRED_ABI

        /**
         * `jniLibs` 下 **32 位 ARM** 的目录名，按优先级。
         *
         * 第一个是**安装后实际用的旧式名**（实测确认），后两个是 `jniLibs` 源码目录
         * 里用的现代名——两种都列上，因为不同 Android 版本/厂商的解压命名不完全一致。
         * `armeabi` 是远古 ABI，列出来只是兜底：文件在不在是逐个探测的，列错不会有害。
         */
        private val THIRTY_TWO_BIT_ABI_DIRS = listOf("arm", "armeabi-v7a", "armeabi")

        /**
         * 命令被超时强杀时的退出码。
         *
         * 用 `-1` 而不是 `124`（`timeout(1)` 的约定）：这里要能与 adb 自己
         * 返回的码区分开，且调用方靠 [CommandResult.timedOut] 判断，不靠数值。
         */
        const val TIMEOUT_EXIT_CODE = -1

        /**
         * 等 adbd 离开 `stopping`/`restarting` 的轮询节奏（2026-10-16 加）。
         *
         * 8 × 250ms ≈ **2 秒上限**。取值理由：`adb root` 的重启观察是 2.5 秒量级
         * （见 [ROOT_FIRST_WAIT_MILLIS]），纯 `ctl.stop`+`ctl.start` 明显更快；
         * 2 秒足够覆盖它，又不至于让「开启永久 ADB」这颗按钮的等待变长。
         *
         * ⚠️ 这段等待替代了旧的 `DEDICATED_PORT_TIMEOUT_MILLIS`：那时候整条判断
         * 压在一次 `shell()` 上、靠 15 秒超时兜底；现在每一步都是独立单命令，
         * 各自用普通超时（[PRIVILEGE_TIMEOUT_MILLIS]），**等待**则由本常量显式表达。
         */
        private const val ADBD_WAIT_ATTEMPTS = 8
        private const val ADBD_WAIT_INTERVAL_MILLIS = 250L

        private const val SERVER_TIMEOUT_MILLIS = 20_000L
        private const val SHELL_TIMEOUT_MILLIS = 15_000L
        private const val PRIVILEGE_TIMEOUT_MILLIS = 8_000L

        /**
         * 「主动 `adb root`」的预算与节奏（见 [requestAdbRoot]）。
         *
         * `adb root` 发出去后 adbd **会重启自己**，必须等它起来再重连验证——
         * 首次等 [ROOT_FIRST_WAIT_MILLIS]（对齐 PC 端 adb 的 2.5s 习惯），
         * 之后每轮等 [ROOT_RETRY_WAIT_MILLIS]。
         *
         * 总预算 [ROOT_NEGOTIATION_BUDGET_MILLIS] 是硬上限：超过就放弃、
         * 回落 su 兜底，绝不让"提权"这一步拖成用户可感知的卡顿。
         */
        // adbd restarting may leave the client waiting for a response that will never arrive.
        // Success is still decided by reconnect + id, never by this command's exit status.
        private const val ROOT_REQUEST_TIMEOUT_MILLIS = 3_000L
        private const val ROOT_FIRST_WAIT_MILLIS = 500L
        private const val ROOT_RETRY_WAIT_MILLIS = 1_000L
        private const val ROOT_NEGOTIATION_BUDGET_MILLIS = 12_000L

        /** [runUntilOutput] 的轮询间隔。 */
        private const val POLL_INTERVAL_MILLIS = 40L

        /**
         * 命中成功标记后再等这么久，把紧随其后的输出捞进来（见 [collectUntilQuiet]）。
         * 只影响摘要文案，不拖连接速度。
         */
        private const val QUIET_SETTLE_MILLIS = 300L

        /**
         * `adb start-server` 的「服务已可用」标记。
         *
         * - `daemon not running; starting now at tcp:5038` 后跟 `daemon started successfully`
         * - 已在跑时只打印 `* daemon started successfully`
         */
        private val DAEMON_READY_PATTERN =
            Regex("daemon started successfully|already running", RegexOption.IGNORE_CASE)

        /**
         * `devices` 解析的重试次数与间隔（见 [connect]）。
         *
         * 4 × 500ms = 最多多等 1.5 秒。**只有真读不到才走这一步**，正常情况一次就中，
         * 所以不会给快路径加延迟；而它换来的是"一次瞬时 offline 不再掐断整条链"。
         */
        private const val SERIAL_ATTEMPTS = 4
        private const val SERIAL_RETRY_BACKOFF_MILLIS = 500L

        /** 长驻命令的"观察窗口"：够看到致命错误，又不至于拖慢会话启动。 */
        private const val DETACHED_SETTLE_MILLIS = 1_500L

        /**
         * `adb push` 的超时（2026-10-11 加）。
         *
         * APK 有几十 MB，走 127.0.0.1 回环 + adbd 写盘，实车几十秒级；
         * 按 3 分钟给。**这是"安装包能不能被 shell 读到"的唯一通路**，
         * 给短了会在慢车机上误判失败（见 [push] 的类注释）。
         */
        private const val PUSH_TIMEOUT_MILLIS = 180_000L

        private const val READER_JOIN_MILLIS = 500L
    }
}
