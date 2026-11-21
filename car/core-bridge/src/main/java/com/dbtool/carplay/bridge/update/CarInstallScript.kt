package com.dbtool.carplay.bridge.update

/**
 * 设备端静默安装脚本 —— **六级降级**，逐级放宽系统对安装的限制。
 *
 * ## 为什么要有这一层（2026-10-11）
 *
 * 原来的 `CarApkInstaller.installViaAdb` 只发一条
 * `pm install -r -d '<apk>'`，三个问题叠在一起：
 *
 * 1. 🔴 **路径在应用私有目录**（`/data/user/0/<pkg>/files/update/…`），
 *    而 `pm install` 是 **adbd 起的 shell 进程**去读这个文件 ——
 *    adbd 不是 root 时**根本读不到**。修法见 `AdbShellRunner.push`：
 *    先 `adb push` 到 `/data/local/tmp`。
 * 2. 🔴 **没有降级**。车机 ROM 对安装的限制五花八门（多用户、应用商店来源校验、
 *    安装校验器、OEM 白名单），一条命令打不通就永远打不通。
 * 3. 🔴 **结果不可知**。`shellDetached` 只观察 1.5 秒，而 `pm install` 要跑几十秒，
 *    于是它永远返回 `exitCode = 0`（进程还活着），调用方据此报"已提交安装" ——
 *    **是个假阳性**，失败被完全吞掉。
 *
 * 本脚本把 1、2 交给**设备端**解决：脚本在 adbd 里跑完所有降级，
 * 每一步的输出写进 [REMOTE_LOG]，最后写一行 [RESULT_OK] / [RESULT_FAIL]。
 * 3 由"下次启动读回这个日志"解决（见 `CarUpdateManager.settlePendingInstall`）。
 *
 * ## 为什么在设备端跑，而不是在 App 里逐级重试
 *
 * 装的是**自己**：安装成功那一刻本进程会被系统杀掉。App 侧的重试循环
 * 会在最后一刻断掉，而**脚本是 adbd 的子进程，不受影响**。
 *
 * ## 降级顺序（照 DBtool3.0 `LocalAdbBridge.installWithRetry`，只照思路）
 *
 * | # | 手段 | 需要 root |
 * | --- | --- | --- |
 * | 1 | `pm install -r` | 否 |
 * | 2 | `--user <uid>`（多用户车机，实测 user=11） | 否 |
 * | 3 | `-i com.android.vending`（伪装安装来源） | 否 |
 * | 5 | `-w`（OEM 商店白名单） | 否 |
 * | 4 | 临时关掉安装校验器（装完恢复原值） | 否 |
 * | 6 | 关 Flyme 商店来源校验属性 / `su` 兜底 | 是 |
 *
 * ⚠️ 顺序里 **5 排在 4 前面**：`-w` 只是一次命令调用，而改全局设置要写盘再恢复、
 * 代价更高，先试便宜的。
 *
 * ⚠️ **每一步都必须恢复系统状态**（verifier 设置、Flyme 属性）：失败退出时留下一个
 * 被改过的全局设置，会让这台车机以后**所有**安装都异常，而且没人会联想到是我们干的。
 *
 * ## ⚠️ 关于 `$`（写这个文件前必读）
 *
 * 脚本正文里**所有 shell 的 `$` 都写成 `${'$'}`**，Kotlin 的插值只用 `$常量名`。
 * 不这么做的话，两种 `$` 混在一起根本分不清谁归谁，而"shell 变量拼错"的后果是
 * **脚本静默不干活**（`set -u` 没开，未定义变量展开成空串），极难发现。
 */
internal object CarInstallScript {

    /** 推到设备上的安装包路径（`/data/local/tmp` 是 shell 可读可写的公共暂存区）。 */
    const val REMOTE_APK: String = "/data/local/tmp/dbplay-update.apk"

    /** 脚本日志。既是排障材料，也是**结果判定的唯一依据**。 */
    const val REMOTE_LOG: String = "/data/local/tmp/dbplay-install.log"

    /** 成功标记。 */
    const val RESULT_OK: String = "RESULT=OK"

    /** 失败标记。 */
    const val RESULT_FAIL: String = "RESULT=FAIL"

    /** 脚本里"当前用户 id"的占位符（唯一的动态值，替换掉就与 Kotlin 无关了）。 */
    private const val USER_PLACEHOLDER = "__DBPLAY_USER__"

    /** 生成脚本正文。 */
    fun build(userId: Int): String = TEMPLATE.replace(USER_PLACEHOLDER, userId.toString())

    /**
     * 从日志判定安装是否成功。
     *
     * 只认**整行等于** [RESULT_OK]：脚本中间某一步的输出里可能恰好含
     * "Success" 字样，按子串匹配会误判。
     */
    fun parseSucceeded(log: String): Boolean =
        log.lineSequence().any { it.trim() == RESULT_OK }

    /**
     * 从日志提炼一句给用户看的失败原因。
     *
     * 优先取 `Failure [INSTALL_FAILED_XXX]` —— 那是系统给的**可检索的错误码**，
     * 比整段输出有用得多。取**最后一个**：前面的降级步骤各自都会失败一次，
     * 最后那条才代表"所有手段都用完了"的结论。
     */
    fun parseFailureReason(log: String): String {
        if (log.isBlank()) return "设备上没有留下安装日志（脚本可能没跑起来）"
        val codes = FAILURE_PATTERN.findAll(log).map { it.groupValues[1] }.toList()
        if (codes.isNotEmpty()) return "系统拒绝安装：${codes.last()}"
        val tail = log.lineSequence()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("===") &&
                    !it.startsWith("---") &&
                    // `RESULT=` 是我们自己的标记行，不是"原因"。
                    !it.startsWith("RESULT=")
            }
            .lastOrNull()
        return tail ?: "安装失败，原因未知"
    }

    private val FAILURE_PATTERN = Regex("""Failure\s*\[([A-Z_]+)]""")

    /**
     * 脚本正文。
     *
     * ⚠️ 保持 `private`：它含未替换的占位符，不能直接拿去执行。
     */
    private val TEMPLATE: String = """
#!/system/bin/sh
# DBPlay 静默安装（六级降级）。由 CarApkInstaller 经 adb shell 提交，结果写 LOG。
APK=$REMOTE_APK
LOG=$REMOTE_LOG
U=$USER_PLACEHOLDER

: > "${'$'}LOG"
log() { echo "${'$'}1" >> "${'$'}LOG"; }
finish_ok() { log "RESULT=OK"; exit 0; }
finish_fail() { log "RESULT=FAIL"; exit 1; }

log "=== BEGIN ${'$'}(date) uid=${'$'}(id -u) ==="
[ -f "${'$'}APK" ] || { log "APK 不存在：${'$'}APK"; finish_fail; }
log "apk 大小：${'$'}(wc -c < "${'$'}APK") 字节"

# 跑一条安装命令；输出里出现 Success 就收工。
attempt() {
  label="${'$'}1"; shift
  out=${'$'}("${'$'}@" 2>&1)
  log "--- ${'$'}label"
  log "${'$'}out"
  case "${'$'}out" in
    *uccess*) finish_ok ;;
  esac
  return 1
}

attempt "1) pm install -r" pm install -r "${'$'}APK"
attempt "2) --user ${'$'}U" pm install -r --user "${'$'}U" "${'$'}APK"
attempt "3) 伪装安装来源 -i com.android.vending" pm install -r --user "${'$'}U" -i com.android.vending "${'$'}APK"
attempt "5) OEM 白名单 -w" pm install -r -w --user "${'$'}U" "${'$'}APK"

# 4) 临时关掉安装校验器（shell 权限即可改）。装完**必须**恢复原值。
V1=${'$'}(settings get global verifier_verify_adb_installs)
V2=${'$'}(settings get global package_verifier_enable)
settings put global verifier_verify_adb_installs 0 2>/dev/null
settings put global package_verifier_enable 0 2>/dev/null
out=${'$'}(pm install -r --user "${'$'}U" "${'$'}APK" 2>&1)
settings put global verifier_verify_adb_installs "${'$'}V1" 2>/dev/null
settings put global package_verifier_enable "${'$'}V2" 2>/dev/null
log "--- 4) 临时关闭安装校验（原值已恢复）"
log "${'$'}out"
case "${'$'}out" in
  *uccess*) finish_ok ;;
esac

# 6) 以下需要 root。非 root 环境整段跳过（不报错，只留痕）。
if [ "${'$'}(id -u)" = "0" ]; then
  old=${'$'}(getprop persist.flyme.appstore.verify)
  setprop persist.flyme.appstore.verify false 2>/dev/null
  out=${'$'}(pm install -r --user "${'$'}U" "${'$'}APK" 2>&1)
  setprop persist.flyme.appstore.verify "${'$'}old" 2>/dev/null
  log "--- 6a) 关闭 Flyme 商店来源校验（原值已恢复）"
  log "${'$'}out"
  case "${'$'}out" in
    *uccess*) finish_ok ;;
  esac

  for mode in "su 0 sh -c" "su -c"; do
    out=${'$'}(${'$'}{mode} "pm install -r --user ${'$'}U '${'$'}APK'" 2>&1)
    log "--- 6b) ${'$'}mode 安装"
    log "${'$'}out"
    case "${'$'}out" in
      *uccess*) finish_ok ;;
    esac
  done
fi

log "所有通道均未成功"
finish_fail
""".trimIndent()
}
