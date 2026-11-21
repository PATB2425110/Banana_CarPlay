package com.dbtool.carplay.bridge.update

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dbtool.carplay.api.update.CarInstallChannel
import com.dbtool.carplay.api.update.CarInstallOutcome
import com.dbtool.carplay.api.update.isDelegated
import com.dbtool.carplay.bridge.hotspot.AdbShellSession
import java.io.File

/**
 * 把下载好的 APK 交给系统安装。
 *
 * **三条路径，按代价从低到高依次尝试**（车机之间的差异很大，写死任何一条都不成立）：
 *
 * - **① 静默安装**：本机持有 `INSTALL_PACKAGES` 时（DBPlay 被装进
 *   `/system/priv-app`、或有平台签名）用 `PackageInstaller` 会话直接提交，
 *   用户看不到任何界面。普通应用**拿不到**这个权限，所以这条在实车上基本不成立，
 *   但代码保留：将来若进了 priv-app 就自动生效。
 * - **② ADB 静默安装**（2026-10-07 新增，2026-10-11 重写，车主需求「优先用 ADB 安装」）：
 *   复用 DBPlay 自带的 adb 客户端 —— [AdbShellSession] 那条**共享**通道连的是
 *   `127.0.0.1:<devicePort>`，即**本机 adbd**。
 *
 *   ⚠️ **2026-10-11 重写**（旧实现实车装不上，见 [installViaAdb] 的对照表）：
 *   旧版把**应用私有目录**里的 APK 路径直接给 `pm install`，而 shell(uid=2000)
 *   读不到那个目录；且只用一条裸命令、结果还靠一个恒为 0 的 exitCode 判定。
 *   现在改成 `adb push` 到 `/data/local/tmp` + **设备端六级降级脚本**
 *   （见 [CarInstallScript]），结果写日志、下次启动读回。
 *
 *   ⚠️ **这条路径很慢**（push 几十 MB + 最多六级 `pm install`，实车可能几分钟），
 *   **必须在 worker 线程调用**（`CarUpdateManager` 已如此编排）。
 *   失败（adb 没起、连不上、push 不了）则退到 ③。
 * - **③ 系统安装器**：最后的兜底，拉起系统安装界面用户点一次「安装」。
 *   这条路径需要两样东西，缺一样都会失败，而且失败得很不直观：
 *   ① manifest 里的 `REQUEST_INSTALL_PACKAGES`；
 *   ② 用户为**本应用**打开的"安装未知应用"开关
 *   （`canRequestPackageInstalls()`）。
 *   第 ② 项没开时不能干等着——直接跳授权页，否则用户看到的就是"点了没反应"。
 *
 * ⚠️ 三条路径都**拿不到安装结果**：安装自身应用会让进程被杀掉重建。
 * 因此结果的判定放在下次启动时比对版本号（见 [CarUpdatePrefs.pendingInstallVersionCode]），
 * 失败原因再叠加读回的设备端日志（[readInstallLog]）。
 */
internal class CarApkInstaller(
    context: Context,
    /**
     * ADB 静默安装通道（2026-10-07 新增；2026-10-11 改为**复用共享会话**）。
     *
     * ## 为什么必须是共享的 `AdbShellSession`，而不是自己 new 一个 runner
     *
     * `AdbShellSession` 内部持有自建的 adb server（端口 5038）与一组长驻客户端。
     * 两个实例各自 `-P 5038 start-server` 虽然幂等（会连上已存在的 server），
     * 但 `killServer()` **不幂等** —— 一条链路收尾时 `kill-server` 会把另一条
     * 正在用的 server 一起干掉，表现是"探测好好的，紧接着拉起热点就报连不上"。
     *
     * 前台手动安装时两条链路很少同时活跃，所以这个缺陷一直没爆；
     * 但**后台静默安装会在"会话刚结束"的瞬间动手**，正好撞上热点/探测收尾
     * ⇒ 必踩。所以这里收拢到 `CarAppServices` 那条共享通道。
     *
     * 顺带收益：`ensure()` 内部尊重 `enableBuiltInAdb` 开关（每次现问），
     * 而旧实现自己 `prepare() + startServer()` 是**绕开**这个开关的 ——
     * 复用后"用户关了内置 ADB 就不该有后台 adb 动作"这条语义才真正成立。
     *
     * 传 `null` 表示「本机不支持 ADB 通道」（如单测环境），
     * 此时 [installViaAdb] 直接返回失败、静默退到系统安装器。
     */
    private val adbSession: AdbShellSession? = null,
) {

    private val appContext = context.applicationContext

    /** 本机是否具备静默安装能力。 */
    fun canInstallSilently(): Boolean = appContext.checkSelfPermission(
        Manifest.permission.INSTALL_PACKAGES,
    ) == PackageManager.PERMISSION_GRANTED

    /** 安装是否需要在系统界面上再点一次确认。 */
    fun needsUserConfirm(): Boolean = !canInstallSilently()

    /**
     * 读 APK manifest 并验证它确实是发给本应用的目标版本。
     * PackageInstaller/pm 仍会验证签名；这里先挡住损坏包、错包和错误的 versionCode，
     * 避免把服务端误配的其他 APK 交给系统安装器或记录成 DBPlay 更新。
     */
    fun validateUpdateApk(file: File, expectedVersionCode: Long): String? {
        if (!file.isFile || file.length() <= 0L) return "安装包不存在或为空"
        if (expectedVersionCode <= 0L) return "目标版本号无效"
        val archive = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return "安装包损坏或无法解析"
        return validateCarUpdateApkIdentity(
            actual = CarUpdateApkIdentity(archive.packageName, archive.longVersionCode),
            expectedPackageName = appContext.packageName,
            expectedVersionCode = expectedVersionCode,
        )
    }

    /**
     * 同步安装 —— **不含 ADB 通道**：`PackageInstaller` 静默 → 系统安装器。
     *
     * ## 为什么把 ADB 摘出去（2026-10-11）
     *
     * ADB 通道要 `adb push` 几十 MB 再跑六级降级，实车**几分钟**：它是
     * **阻塞且长**的，只能在 worker 线程跑；而本方法是同步入口（由主线程调用，
     * 最后一步 `startActivity` 也要求主线程）。两者线程模型对不上，
     * 硬塞在一起必然 ANR。
     *
     * 分工因此是：
     * - `CarUpdateManager` 先在 worker 线程调 [installViaAdb]；
     * - 失败（或本机根本没有 adb 通道）才回主线程走本方法 / [launchSystemInstaller]。
     *
     * ⚠️ 车主口径「**优先用 ADB 安装，失败就退到系统安装器**」仍然成立，
     * 只是 ADB 那一级由调用方在后台先试。次序契约仍由 [CarInstallChannel]
     * 的**声明次序**表达，由 `CarInstallChannelOrderTest` 守住。
     *
     * `SYSTEM_INSTALLER` 是最后一步，永远会执行；因此本函数**不会返回 null**，
     * 最差也是 `LAUNCHER_OPENED` / `NEEDS_UNKNOWN_SOURCES` / `FAILED` 之一。
     */
    fun installWithoutAdb(file: File, expectedVersionCode: Long): CarInstallOutcome {
        if (validateUpdateApk(file, expectedVersionCode) != null) return CarInstallOutcome.FAILED
        // 从 ① 开始：`canInstallSilently()` 为真时 ① 比 ② 更省事，
        // 这也是"按代价从低到高"。
        var channel: CarInstallChannel? = CarInstallChannel.PLATFORM_SILENT
        while (channel != null) {
            val outcome = when (channel) {
                CarInstallChannel.PLATFORM_SILENT -> installSilently(file)
                // ⚠️ ADB 已由调用方在后台试过；这里若再试就是同步阻塞（几分钟），
                // 必须跳过 —— 见上面的线程说明。
                CarInstallChannel.ADB_SILENT -> CarInstallOutcome.FAILED
                CarInstallChannel.SYSTEM_INSTALLER -> launchSystemInstaller(file)
            }
            // 系统安装器一旦接手就返回其自有结论（含 NEEDS_UNKNOWN_SOURCES），
            // 它是最后一步，不存在"再往下试"。
            if (outcome.isDelegated() || channel == CarInstallChannel.SYSTEM_INSTALLER) {
                return outcome
            }
            channel = channel.next()
        }
        return CarInstallOutcome.FAILED
    }

    // -------------------------------------------------------------- 静默安装

    private fun installSilently(file: File): CarInstallOutcome = try {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(appContext.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite(SESSION_NAME, 0L, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // ⚠️ 提交后**不要**在这里读结果：PendingIntent 是异步的，而这次
            // 提交会把本进程杀掉（自我更新）。结果靠下次启动比版本号。
            session.commit(resultSender())
        }
        CarInstallOutcome.SILENT_COMMITTED
    } catch (error: Throwable) {
        CarInstallOutcome.FAILED
    }

    /**
     * 提交结果用的 `PendingIntent`。
     *
     * 刻意**不注册接收者**：进程马上就会被系统杀掉，收到了也来不及上报；
     * 真正的判定在下次启动。这里只需要一个"合法但没人听"的 sender。
     */
    private fun resultSender(): IntentSender {
        val intent = Intent(ACTION_INSTALL_RESULT).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
    }

    // ------------------------------------------------------------ ADB 安装

    /**
     * ② ADB 静默安装（2026-10-11 重写）。
     *
     * ## 与旧实现的三处关键差别
     *
     * | | 旧 | 新 |
     * | --- | --- | --- |
     * | APK 位置 | 直接用**应用私有路径** ⇒ shell 读不到 | `adb push` 到 `/data/local/tmp` |
     * | 安装手段 | 一条 `pm install -r -d` | 设备端**六级降级**脚本 |
     * | 结果判定 | `shellDetached` 的 exitCode（**恒 0，假阳性**） | 落 log，下次启动读回 |
     *
     * ## 链路（2026-10-11 改为复用共享会话）
     *
     * 1. `session.ensure()` —— **一次调用顶旧版四步**：开通 ADB → 校验随包二进制
     *    → 起 5038 server → 自连本机 adbd → 提权探测（含主动 `adb root`）
     *    → 切独占端口。结果**有缓存**：已经连好就直接用，省掉十几秒。
     *    ⚠️ `ensure()` **阻塞**（实测秒级到十几秒），只能在 worker 线程调用。
     * 2. `push()` —— 把 APK 送到 shell 可读的位置（**这一步是修复的根**）；
     * 3. `shellDetached(脚本)` —— 交给设备端跑完六级降级，结果写 [CarInstallScript.REMOTE_LOG]。
     *
     * ⚠️ **拿不到 root 不判失败**：六级降级里的前五级都只需要 shell 权限，
     * 旧实现在提权那步 `return FAILED`，把"其实能装"的情况也一起毙了。
     *
     * ## 线程
     *
     * ⚠️ **阻塞，且很慢**（push 几十 MB + 最多六级各跑一次 `pm install`，
     * 实车可能几分钟）。**必须在 worker 线程调用**，绝不能留在主线程。
     *
     * ## 为什么仍然用 `shellDetached`
     *
     * 装的是**自己**，安装成功那一刻本进程会被系统杀掉 —— 在 App 里同步等结果
     * 会在最后一刻断掉。而脚本是 **adbd 的子进程**，不受本进程死活影响，
     * 会一路跑完并把结论写进 log；App 下次启动读回即可。
     *
     * @return [CarInstallOutcome.ADB_COMMITTED] 表示**已交给设备端**，
     *         **不是**"已经装成功"（成败由下次启动比版本号 + 读 log 判定）。
     */
    fun installViaAdb(file: File, expectedVersionCode: Long): CarInstallOutcome {
        val session = adbSession ?: return CarInstallOutcome.FAILED
        if (validateUpdateApk(file, expectedVersionCode) != null) return CarInstallOutcome.FAILED
        return try {
            // 1. 复用共享会话（内部含 开通 ADB → 二进制 → server → connect → 提权 → 切口）。
            //    已连好时直接命中缓存；没连好时这一句要十几秒。
            val access = session.ensure()
            if (!access.shellOk) return CarInstallOutcome.FAILED
            val runner = session.runner

            // 2. 🔴 把 APK 推到 shell 可读的位置。
            //    应用私有目录（`/data/user/0/<pkg>/files/…`）对 shell 不可读，
            //    而 `pm install` 正是由 adbd 起的 shell 进程去读这个文件。
            //    `adb push` 的读方是**本应用**（对自己 filesDir 有权限），
            //    写方是 adbd（落到 /data/local/tmp），正好绕开这个权限墙。
            val pushed = runner.push(file, CarInstallScript.REMOTE_APK)
            if (!pushed.ok) return CarInstallOutcome.FAILED

            // 3. 清掉上一轮结果，再把六级降级交给设备端。
            //    脚本内部自己判 `id -u` 决定要不要走需要 root 的那一级，
            //    所以这里**不需要**按 `access.prefix` 拼前缀。
            runner.shell("rm -f ${CarInstallScript.REMOTE_LOG}", LOG_TIMEOUT_MILLIS)
            runner.shellDetached(CarInstallScript.build(currentUserId()))

            CarInstallOutcome.ADB_COMMITTED
        } catch (error: Throwable) {
            // 任何异常都不能把更新流程炸掉 —— 退到系统安装器即可。
            CarInstallOutcome.FAILED
        }
    }

    /**
     * 本机是否具备走 ADB 通道的条件。
     *
     * 调用方（`CarUpdateManager`）据此决定"异步走 ADB"还是"同步走系统安装器"——
     * ADB 那条路要几十秒到几分钟，绝不能同步跑在主线程上。
     */
    fun canUseAdb(): Boolean = adbSession != null

    /**
     * 读回设备端安装日志（[CarInstallScript.REMOTE_LOG]）。
     *
     * ⚠️ 只在**已经判定安装失败之后**调用（见 `CarUpdateManager.settlePendingInstall`）：
     * 它要走一遍共享会话 + 一次 shell 往返，实车十几秒，不该出现在任何正常路径上。
     *
     * @return 日志正文；通道不通或没有日志时返回 null。
     */
    fun readInstallLog(): String? {
        val session = adbSession ?: return null
        return runCatching {
            val access = session.ensure()
            if (!access.shellOk) return null
            session.runner.shell("cat ${CarInstallScript.REMOTE_LOG}", LOG_TIMEOUT_MILLIS).output
        }.getOrNull()
    }

    /** 当前用户 id。多用户车机（实测 user=11）上 `pm install --user` 要用它。 */
    private fun currentUserId(): Int = android.os.Process.myUid() / 100000

    // ---------------------------------------------------------- 系统安装器

    /**
     * ③ 系统安装器兜底。
     *
     * ⚠️ **必须在主线程调用**（它要 `startActivity`）。ADB 通道在 worker 线程
     * 跑失败后，由 `CarUpdateManager` 回主线程调这里。
     */
    fun launchSystemInstaller(file: File): CarInstallOutcome {
        // 没打开"安装未知应用"时，拉安装器必然被系统拒掉，用户看到的是"没反应"。
        // 直接跳授权页，把原因说清楚。
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return CarInstallOutcome.NEEDS_UNKNOWN_SOURCES
        }
        val uri: Uri = try {
            // Android 7 起 `file://` 不能跨进程传给安装器，必须用 FileProvider
            // 的 `content://`；authority 与 :car:app 清单里声明的一致。
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
        } catch (error: Throwable) {
            return CarInstallOutcome.FAILED
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 从 applicationContext 拉起界面必须有这个标志。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            CarInstallOutcome.LAUNCHER_OPENED
        } catch (error: ActivityNotFoundException) {
            CarInstallOutcome.FAILED
        }
    }

    /** 跳到「安装未知应用」授权页（车机上一般叫「安装外部来源应用」）。 */
    private fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    private companion object {
        const val SESSION_NAME = "dbplay-update"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** 清日志 / 读日志这类短命令的超时（`adb shell` 往返，给 15 秒足够）。 */
        const val LOG_TIMEOUT_MILLIS = 15_000L

        /** 自定义 action：只为了让 `PendingIntent` 合法，没有接收者。 */
        const val ACTION_INSTALL_RESULT = "com.dbtool.carplay.UPDATE_INSTALL_RESULT"
    }
}
