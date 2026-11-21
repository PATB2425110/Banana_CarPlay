package com.dbtool.carplay.geely

/**
 * 把 iPhone 的转向类型码翻译成吉利 HUD 服务认识的转向编号。
 *
 * ## 为什么单独成一个对象
 *
 * 这三张表（语义 / kind / OneOS 转向 id）**全是纯算术**，不含任何 Android 类型。
 * 抽出来就能在 JVM 上跑单测——映射错一个数字，表现是"HUD 显示错的转向箭头"，
 * 那是最难在现场发现、也最难复现的一类 bug。用单测钉死比上车试便宜得多。
 *
 * ## 证据来源
 *
 * 全部来自 `GeelyHudAdapter.smali` 的反编译实证，见 `.ai/HUD-BRIDGE-REVERSE.md` §7。
 * 只有 `turnKind` 表格的**中文含义**那一列是解读（未确认），**数字本身是实证的**。
 */
object GeelyHudTurnMapping {

    /**
     * 转向类型码 → 语义串（原版 `semantic(type)`）。
     *
     * 语义串会原样写进 Parcel 的 `writeString`，所以**大小写与拼写必须逐字符一致**。
     */
    fun semantic(type: Int?): String = when (type) {
        1, 20, 22, 52 -> "LEFT"
        2, 21, 23, 53 -> "RIGHT"
        3 -> "STRAIGHT_AHEAD"
        5 -> "FOLLOW_ROAD"
        51 -> "HIGHWAY_CHANGE"
        4, 18, 26 -> "UTURN_LEFT"
        6, 19 -> "ENTER_ROUNDABOUT"
        7, in 28..46 -> "EXIT_ROUNDABOUT"
        10, 12, 24, 25, 27 -> "ARRIVAL"
        13 -> "KEEP_LEFT"
        14 -> "KEEP_RIGHT"
        11 -> "ROUTE_START"
        0, null -> "CONTEXT"
        else -> "UNKNOWN"
    }

    /**
     * 转向类型码 → 转向 kind（原版 `turnKind(type)`）。
     *
     * 逐支对应 `GeelyHudAdapter$Companion.smali` 的寄存器控制流。未匹配值返回 **0**；
     * 不能把它改成直行，因为 `payload()` 会把 kind 0 转成原版的 `0x9c` 无转向哨兵。
     */
    fun turnKind(type: Int): Int = when (type) {
        1, 20, 22, 52 -> 7
        2, 21, 23, 53 -> 3
        3, 5 -> 1
        4, 18, 26 -> 5
        6, 19 -> 9
        7, in 28..46 -> 10
        10, 12, 24, 25, 27 -> 24
        11, 51 -> 0
        13 -> 11
        14 -> 12
        47 -> 3
        48 -> 4
        49 -> 8
        50 -> 2
        else -> 0
    }

    /**
     * 转向 kind → OneOS 转向 id（原版 `oneOsTurnId(kind)`）。
     *
     * 未知 kind 返回 **-1**（原版的哨兵值），由 [nativeTurnId] 负责兜底。
     */
    fun oneOsTurnId(kind: Int): Int = when (kind) {
        1 -> 0x6d
        2 -> 0x69
        3 -> 0x67
        4 -> 0x6b
        5 -> 0x6c
        6 -> 0x6a
        7 -> 0x66
        8 -> 0x68
        9 -> 0x6f
        10 -> 0x70
        11 -> 0x93
        12 -> 0x94
        24 -> 0x73
        156 -> 0x65
        else -> -1
    }

    /**
     * 最终写进 Parcel 的转向编号（原版 `nativeTurnId(type, description)`）。
     *
     * ## 优先顺序（不可调换）
     *
     * 1. **隧道特判**：`type ∈ {0, 3, 5}` 且指令文本是"进入隧道/驶入隧道/通过隧道"
     *    ⇒ 返回 `0x74`。此项**优先于** `type == 5` 的"沿路"，因为原版就是这么写的。
     * 2. 原版专用编号：`type == 5` ⇒ `0x78`、`13` ⇒ `0x95`、`14` ⇒ `0x96`、`19` ⇒ `0x75`。
     * 3. 其余走 [turnKind] 和 [oneOsTurnId]，且结果必须落在 `0x65..0x78`，否则兜底 `0x65`。
     *
     * @param description HUD 指令文本（原版从导航快照的 instruction 取）。空白会被
     *   **先剔除**再去比对隧道词——原版是 `replace(Regex("\\s+"), "")`，不是 trim。
     */
    fun nativeTurnId(type: Int?, description: String): Int {
        val instruction = description.replace(WHITESPACE, "")
        if (type != null && type in TUNNEL_TYPES && instruction in TUNNEL_PHRASES) return 0x74
        when (type) {
            5 -> return 0x78
            13 -> return 0x95
            14 -> return 0x96
            19 -> return 0x75
        }
        val mapped = type?.let { oneOsTurnId(turnKind(it)) } ?: -1
        return if (mapped in 0x65..0x78) mapped else 0x65
    }

    /**
     * 原版 `payload()` 里的转向裁决：把类型码转成最终要发的 kind。
     *
     * - 未知类型 ⇒ `0`，再被替换成 `0x9c`（原版的"无转向"哨兵）。
     * - 结果的 [oneOsTurnId] 若不在 `0x65..0xc0`，原版**放弃发送** ⇒ 这里返回 null，
     *   由调用方走 `clearLanes`。
     *
     * @return 要写入 payload 的 turnKind，或 null 表示"这一帧不支持，别发"。
     */
    fun resolvableTurnKind(type: Int?): Int? {
        val mapped = type?.let { turnKind(it) } ?: 0
        val turn = if (mapped == 0) 0x9c else mapped
        return if (oneOsTurnId(turn) in 0x65..0xc0) turn else null
    }

    private val WHITESPACE = Regex("\\s+")
    private val TUNNEL_TYPES = setOf(0, 3, 5)
    private val TUNNEL_PHRASES = setOf("进入隧道", "驶入隧道", "通过隧道")
}
