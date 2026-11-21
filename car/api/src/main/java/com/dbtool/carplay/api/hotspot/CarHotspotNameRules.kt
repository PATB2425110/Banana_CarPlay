package com.dbtool.carplay.api.hotspot

/**
 * 热点名称（SSID）的**本地**合法性判据。
 *
 * ## 为什么要有这个（2026-09-30）
 *
 * 上游 `CarPlayRuntimeConfig` 只校验 SSID **非空**与**不含 `U+0000`**，
 * **不校验长度**；`ManualHotspotManager` 也不校验。也就是说一个超过 32 字节的名字
 * 会一路传到 `hostapd.conf` / `SoftApConfiguration`，由厂商驱动在某个我们看不见的地方
 * 失败——现象是"连不上"，而错误文案是别人的英文原话，对现场毫无帮助。
 *
 * 同源二开 DiPlay（v0.2.6）在它自己的 `ManualHotspotValidation` 里加了这条
 * （`LONG_NAME("The hotspot name must be at most 32 bytes")`）。
 * 我们在**自己这一侧**补上，好处有两个：
 * 1. 设置页能当场用中文说清"哪里不对、差多少"，而不是等连接失败；
 * 2. 判据是纯函数 ⇒ 能单测（本模块没有 Robolectric，要 `Context` 的东西测不了）。
 *
 * ## ⚠️ 32 是**字节**不是字符
 *
 * 802.11 的 SSID 上限是 **32 个八位组**。UTF-8 下**一个汉字占 3 字节** ⇒
 * 名字里放 11 个汉字就已经 33 字节、**超了**。所以这里一律用
 * [String.encodeToByteArray] 的 `size` 比较，**不能**用 `length`。
 * （这正是最容易写错的一处：`"联通热点".length == 4` 但它是 12 字节。）
 *
 * ## 空名字为什么不在这里判
 *
 * 空 SSID 在**不同模式下含义不同**：`LOCAL_ONLY_HOTSPOT` 下"留空"表示
 * "让系统随机生成"（设置页就是这么写的），而 `MANUAL` 下"留空"是错误。
 * 这条模式相关的规则属于上游（`manualHotspotSsid is required in manual hotspot mode`
 * 已由 `CarErrorText` 译成中文），不在这里重复判，免得把合法的"留空"判成错误。
 */
object CarHotspotNameRules {

    /** IEEE 802.11 的 SSID 上限：**32 字节**（不是 32 个字符）。 */
    const val MAX_BYTES = 32

    /** 名字的合法性问题。[NUL] 上游也会判，但那条错误要等启动会话才出现，这里提前暴露。 */
    enum class Problem {
        /** UTF-8 字节数超过 [MAX_BYTES]。 */
        TOO_LONG,

        /** 含 `U+0000`（`hostapd` / `SoftApConfiguration` 都不接受）。 */
        NUL,
    }

    /**
     * @return `null` = 可以用；否则是**第一个**命中的问题。
     *
     * 顺序刻意是 `NUL` 在前：一个既含非法字符又超长的名字，说"含非法字符"比说
     * "太长"更能指导用户（把非法字符删掉之后才轮到长度问题）。
     */
    fun problemOf(ssid: String): Problem? = when {
        '\u0000' in ssid -> Problem.NUL
        byteLength(ssid) > MAX_BYTES -> Problem.TOO_LONG
        else -> null
    }

    /** SSID 的 UTF-8 字节数。汉字按 3 字节算。 */
    fun byteLength(ssid: String): Int = ssid.encodeToByteArray().size

    /** 给设置页看的中文说明。**必须能直接告诉用户差多少**，不要只说"非法"。 */
    fun describe(problem: Problem, ssid: String): String = when (problem) {
        Problem.TOO_LONG ->
            "热点名称最长 $MAX_BYTES 字节，当前 ${byteLength(ssid)} 字节" +
                "（一个汉字占 3 字节，最多约 10 个汉字）"
        Problem.NUL -> "热点名称含非法字符（U+0000）"
    }
}
