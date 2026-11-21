package com.dbtool.carplay.api.hotspot

/**
 * 配置文件 dump 的「有效行压缩」—— 别让样板注释吃掉诊断预算。
 *
 * ## 为什么需要它
 *
 * 2026-09-29 实车诊断包里，探测步骤 `[15] 运行中的热点配置（hostapd.conf，含明文密码）`
 * 的输出是这样结束的：
 *
 * ```
 * [15/1] === /data/vendor/wifi/hostapd/hostapd.conf ===
 * interface=wlan1
 * driver=nl80211
 * …
 * ssid2=416e64726f696453686172655f34363832
 * …（本条已裁剪到 4000 字符，完整内容见原始日志）
 * ```
 *
 * `/data/vendor/wifi/hostapd/hostapd.conf` 是 **AOSP 的模板**：前面两百多行全是注释，
 * 有效设置只有二十来行。而 `clipForDisplay` 按**字符数**裁剪 ⇒ 4000 字符的预算
 * 被注释吃光 ⇒ `wpa_passphrase=` / `channel=` / `hw_mode=` / `wpa=` **全被裁掉**，
 * 偏偏这一步的标题承诺的就是「含明文密码」。
 *
 * 后果不是"少看几行"：运行中 AP 的 **SSID 与密码**正是我们发给 iPhone 的 `0x5703`
 * 里那两个值，任一个与网卡实际在用的不一致，iPhone 都关联不上，
 * **而链路上不会报任何错**（同 `CarIwDevOutput` 记的那次，两个真相）。
 *
 * ## 做法
 *
 * 在裁剪**之前**先去掉整行注释与空行，并把略去的行数写在最前面（可审计），
 * 于是同样的 4000 字符预算能装下全部有效设置。
 *
 * 刻意保守的三条：
 *
 * 1. **只认整行注释**（`#` / `;` / `<!--`）。`wpa_passphrase=abc#def` 里的 `#`
 *    是值的一部分，绝不动。
 * 2. **`=== 路径 ===` 分隔行必须留**——`catMany` 一次 cat 多个候选路径，
 *    丢了分隔就分不清哪一行来自哪个文件。
 * 3. **全是注释/空行时返回原文**。宁可长，也不能把一整步显示成空白
 *    （那正是本工程反复处理的那类"错了也不报错"）。
 */
object CarConfigFileDigest {

    /** `catMany` 打的分隔行前缀，必须原样保留。 */
    private const val SEPARATOR_PREFIX = "==="

    /**
     * 被"粘"在上一行末尾的分隔行。
     *
     * `catMany` 现在会在每个文件后补一个 `echo`，但**配置文件本身结尾没有换行**时
     * （实车 `/data/misc/wifi/softap.conf` 就是），老版本的输出会长这样：
     *
     * ```
     * ������LYNK&CO������������12345678=== /data/vendor/wifi/softap.conf ===
     * ```
     *
     * 于是 `12345678` 与"下一个文件的标题"挤成一行，**归属当场串掉**——
     * 而"这行来自哪个文件"正是 dump 唯一的产出。这里把它拆回行首做兜底。
     *
     * 只认「`=== ` 后面紧跟 `/`」的形状（我们的候选路径都是绝对路径），
     * 这样不会把 `=== pid 2724 ===` 这种自家标题误拆。
     */
    private val GLUED_SEPARATOR_RE = Regex("""(?<=[^\n])[ \t]*(===[ \t]+/[^\n]*?[ \t]*===)""")

    /** `ssid2=<hex>`：hostapd 里 SSID 的十六进制形式。 */
    private val SSID2_RE = Regex("""^[ \t]*ssid2[ \t]*=[ \t]*([0-9A-Fa-f]+)[ \t]*$""")

    /**
     * 去掉整行注释与空行，只留有效行。
     *
     * 略去的行数写在第一行（`…（已略去 N 行注释、M 行空行）`），
     * 免得"看起来少了几行"又被当成"文件本来就这么短"。
     */
    fun compact(output: String): String {
        if (output.isBlank()) return output

        val repaired = splitGluedSeparators(output)
        val kept = ArrayList<String>()
        var comments = 0
        var blanks = 0

        for (raw in repaired.split('\n')) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.isEmpty() -> blanks++
                trimmed.startsWith(SEPARATOR_PREFIX) -> kept += line
                isComment(trimmed) -> comments++
                else -> kept += annotateSsid2(line)
            }
        }

        // 一行有效的都没有 ⇒ 返回原文。宁可长，也不能把一步显示成空白。
        if (kept.isEmpty()) return repaired

        // 本来就没有可略去的 ⇒ 不做归一化（避免无谓 diff）。
        if (comments == 0 && blanks == 0) return repaired

        val head = "…（已略去 $comments 行注释、$blanks 行空行）"
        return (listOf(head) + kept).joinToString("\n")
    }

    /** 把粘在上一行末尾的 `=== /路径 ===` 拆回行首。 */
    private fun splitGluedSeparators(output: String): String =
        GLUED_SEPARATOR_RE.replace(output) { "\n" + it.groupValues[1] }

    /**
     * `ssid2=<hex>` 人眼读不出来（本次实车就是靠手工 `bytes.fromhex` 才认出
     * `AndroidShare_4682`）。**保留原行**，只在其后追加一段解码注释。
     *
     * 解不出来就原样返回，**不猜**。
     */
    private fun annotateSsid2(line: String): String {
        val hex = SSID2_RE.find(line)?.groupValues?.get(1) ?: return line
        val text = decodeSsid2(hex) ?: return line
        return "$line    ← SSID \"$text\""
    }

    /** 十六进制 SSID → 字符串；解不出来（奇数长度 / 非十六进制 / 非法 UTF-8）返回 null。 */
    fun decodeSsid2(value: String): String? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        if (!value.all { it.isHexDigit() }) return null

        val bytes = ByteArray(value.length / 2)
        for (i in bytes.indices) {
            val hi = value[i * 2].digitToIntOrNull(16) ?: return null
            val lo = value[i * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }

        val text = String(bytes, Charsets.UTF_8)
        // 严格：出现替换字符说明这些字节不是合法 UTF-8，那就不是一个 SSID。
        if (text.contains('\uFFFD')) return null
        return text.ifBlank { null }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun isComment(trimmed: String): Boolean =
        trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("<!--")
}
