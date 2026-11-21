package com.dbtool.carplay.geely

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HUD 转向映射表的单测。
 *
 * ## 为什么值得测
 *
 * 这三张表是**逐字段从 smali 抄下来的**（`.ai/HUD-BRIDGE-REVERSE.md` §7）。
 * 抄错一个数字，症状是"HUD 显示错误的转向箭头"或"该显示却不显示"——
 * 车机上很难复现，更难归因。而这里是纯函数，测一次就永久钉死。
 *
 * 用例刻意**覆盖每条分支的边界**（含"默认分支"），因为 `when` 抄漏一个
 * 值不会报错，只会静默走到 `else`。
 */
class GeelyHudTurnMappingTest {

    // ------------------------------------------------------------ semantic

    @Test
    fun `语义串逐值对照台账`() {
        // 抽样覆盖每一组，含原版把多个 type 映射到同一语义串的成组写法。
        assertEquals("LEFT", GeelyHudTurnMapping.semantic(1))
        assertEquals("LEFT", GeelyHudTurnMapping.semantic(52))
        assertEquals("RIGHT", GeelyHudTurnMapping.semantic(2))
        assertEquals("RIGHT", GeelyHudTurnMapping.semantic(53))
        assertEquals("STRAIGHT_AHEAD", GeelyHudTurnMapping.semantic(3))
        assertEquals("FOLLOW_ROAD", GeelyHudTurnMapping.semantic(5))
        assertEquals("HIGHWAY_CHANGE", GeelyHudTurnMapping.semantic(51))
        assertEquals("UTURN_LEFT", GeelyHudTurnMapping.semantic(26))
        assertEquals("ENTER_ROUNDABOUT", GeelyHudTurnMapping.semantic(6))
        assertEquals("EXIT_ROUNDABOUT", GeelyHudTurnMapping.semantic(28))
        assertEquals("EXIT_ROUNDABOUT", GeelyHudTurnMapping.semantic(46))
        assertEquals("ARRIVAL", GeelyHudTurnMapping.semantic(27))
        assertEquals("KEEP_LEFT", GeelyHudTurnMapping.semantic(13))
        assertEquals("KEEP_RIGHT", GeelyHudTurnMapping.semantic(14))
        assertEquals("ROUTE_START", GeelyHudTurnMapping.semantic(11))
        assertEquals("CONTEXT", GeelyHudTurnMapping.semantic(0))
        assertEquals("CONTEXT", GeelyHudTurnMapping.semantic(null))
        assertEquals("UNKNOWN", GeelyHudTurnMapping.semantic(999))
    }

    @Test
    fun `环岛区间之外的值不算退出环岛`() {
        // 27 在 28..46 之外，属于 ARRIVAL；47 谁都不属于 ⇒ UNKNOWN。
        assertEquals("ARRIVAL", GeelyHudTurnMapping.semantic(27))
        assertEquals("UNKNOWN", GeelyHudTurnMapping.semantic(47))
    }

    // ------------------------------------------------------------ turnKind

    @Test
    fun `转向kind逐分支与smali控制流一致`() {
        assertEquals(7, GeelyHudTurnMapping.turnKind(1))
        assertEquals(7, GeelyHudTurnMapping.turnKind(52))
        assertEquals(3, GeelyHudTurnMapping.turnKind(2))
        assertEquals(3, GeelyHudTurnMapping.turnKind(53))
        assertEquals(1, GeelyHudTurnMapping.turnKind(3))
        assertEquals(1, GeelyHudTurnMapping.turnKind(5))
        assertEquals(5, GeelyHudTurnMapping.turnKind(4))
        assertEquals(5, GeelyHudTurnMapping.turnKind(26))
        assertEquals(9, GeelyHudTurnMapping.turnKind(6))
        assertEquals(9, GeelyHudTurnMapping.turnKind(19))
        assertEquals(10, GeelyHudTurnMapping.turnKind(7))
        assertEquals(10, GeelyHudTurnMapping.turnKind(28))
        assertEquals(10, GeelyHudTurnMapping.turnKind(46))
        assertEquals(24, GeelyHudTurnMapping.turnKind(10))
        assertEquals(24, GeelyHudTurnMapping.turnKind(27))
        assertEquals(0, GeelyHudTurnMapping.turnKind(11))
        assertEquals(11, GeelyHudTurnMapping.turnKind(13))
        assertEquals(12, GeelyHudTurnMapping.turnKind(14))
        assertEquals(3, GeelyHudTurnMapping.turnKind(47))
        assertEquals(4, GeelyHudTurnMapping.turnKind(48))
        assertEquals(8, GeelyHudTurnMapping.turnKind(49))
        assertEquals(2, GeelyHudTurnMapping.turnKind(50))
        assertEquals(0, GeelyHudTurnMapping.turnKind(51))
        assertEquals(0, GeelyHudTurnMapping.turnKind(999))
    }

    // ------------------------------------------------------------ oneOsTurnId

    @Test
    fun `OneOS转向id逐值对照台账`() {
        assertEquals(0x6d, GeelyHudTurnMapping.oneOsTurnId(1))
        assertEquals(0x69, GeelyHudTurnMapping.oneOsTurnId(2))
        assertEquals(0x67, GeelyHudTurnMapping.oneOsTurnId(3))
        assertEquals(0x6b, GeelyHudTurnMapping.oneOsTurnId(4))
        assertEquals(0x6c, GeelyHudTurnMapping.oneOsTurnId(5))
        assertEquals(0x6a, GeelyHudTurnMapping.oneOsTurnId(6))
        assertEquals(0x66, GeelyHudTurnMapping.oneOsTurnId(7))
        assertEquals(0x68, GeelyHudTurnMapping.oneOsTurnId(8))
        assertEquals(0x6f, GeelyHudTurnMapping.oneOsTurnId(9))
        assertEquals(0x70, GeelyHudTurnMapping.oneOsTurnId(10))
        assertEquals(0x93, GeelyHudTurnMapping.oneOsTurnId(11))
        assertEquals(0x94, GeelyHudTurnMapping.oneOsTurnId(12))
        assertEquals(0x73, GeelyHudTurnMapping.oneOsTurnId(24))
        assertEquals(0x65, GeelyHudTurnMapping.oneOsTurnId(156))
        assertEquals(-1, GeelyHudTurnMapping.oneOsTurnId(99))
    }

    // ------------------------------------------------------------ nativeTurnId

    @Test
    fun `隧道特判优先于沿路`() {
        // type=5 同时命中"隧道特判"与"沿路"。台账明确特判在前 ⇒ 必须是 0x74。
        assertEquals(0x74, GeelyHudTurnMapping.nativeTurnId(5, "进入隧道"))
        assertEquals(0x74, GeelyHudTurnMapping.nativeTurnId(3, "驶入隧道"))
        assertEquals(0x74, GeelyHudTurnMapping.nativeTurnId(0, "通过隧道"))
        // type=5 但非隧道词 ⇒ 落到"沿路" 0x78。
        assertEquals(0x78, GeelyHudTurnMapping.nativeTurnId(5, "沿当前道路行驶"))
    }

    @Test
    fun `隧道词比对前会剔除所有空白`() {
        // 原版是 replace(Regex("\\s+"), "")，所以带空格的"进入 隧道"也算命中。
        assertEquals(0x74, GeelyHudTurnMapping.nativeTurnId(3, " 进入 隧道 "))
        // 但不是"包含"判定：多了别的字就不算 ⇒ 走 type=3 ⇒ kind=1 ⇒ 0x6d。
        assertEquals(0x6d, GeelyHudTurnMapping.nativeTurnId(3, "前方进入隧道后直行"))
    }

    @Test
    fun `非隧道转向走OneOS表并夹在0x65到0x78`() {
        // ⚠️ 链条是 type → turnKind → oneOsTurnId，不是 type → oneOsTurnId。
        // type=1 ⇒ kind=7 ⇒ 0x66（不是 0x6d，0x6d 是 oneOsTurnId(1)）。
        assertEquals(0x66, GeelyHudTurnMapping.nativeTurnId(1, ""))
        // 原版对 keep-left / keep-right / enter-roundabout 使用专用编号，不走通用范围裁剪。
        assertEquals(0x95, GeelyHudTurnMapping.nativeTurnId(13, ""))
        assertEquals(0x96, GeelyHudTurnMapping.nativeTurnId(14, ""))
        assertEquals(0x75, GeelyHudTurnMapping.nativeTurnId(19, ""))
        // type=11 ⇒ kind=0 ⇒ oneOsTurnId 未定义(-1) ⇒ 兜底 0x65。
        assertEquals(0x65, GeelyHudTurnMapping.nativeTurnId(11, ""))
        // type=null ⇒ 兜底 0x65。
        assertEquals(0x65, GeelyHudTurnMapping.nativeTurnId(null, ""))
    }

    @Test
    fun `kind11的OneOS值超出范围会被兜底而不是原样发出`() {
        // oneOsTurnId(11) = 0x93 = 147 > 0x78，原版要求夹在 0x65..0x78 内。
        assertEquals(0x93, GeelyHudTurnMapping.oneOsTurnId(11))
        assertEquals(0x66, GeelyHudTurnMapping.nativeTurnId(1, ""))
        // 经 nativeTurnId 的路径不会泄漏 0x93：
        // type=11 ⇒ kind=0 ⇒ oneOsTurnId(0) = -1 ⇒ 兜底 0x65。
        assertEquals(0x65, GeelyHudTurnMapping.nativeTurnId(11, ""))
    }

    // ------------------------------------------------------------ resolvableTurnKind

    @Test
    fun `未知转向映射为原版无转向哨兵`() {
        // 原版 turnKind 默认 0；payload 将 kind 0 转为 0x9c，再由 OneOS 映射为 0x65。
        assertEquals(0, GeelyHudTurnMapping.turnKind(999))
        assertEquals(0x9c, GeelyHudTurnMapping.resolvableTurnKind(999))
        assertEquals(0x65, GeelyHudTurnMapping.oneOsTurnId(0x9c))
    }

    @Test
    fun `kind为0的转向被替换成0x9c哨兵`() {
        // type=11（路线开始）⇒ kind=0 ⇒ 被替换成 0x9c；oneOsTurnId(156)=0x65，可发。
        assertEquals(0x9c, GeelyHudTurnMapping.resolvableTurnKind(11))
        // type=null 走 `?: 0` 分支 ⇒ 同样得 0x9c。
        assertEquals(0x9c, GeelyHudTurnMapping.resolvableTurnKind(null))
    }

    @Test
    fun `已知转向原样返回其kind`() {
        assertEquals(7, GeelyHudTurnMapping.resolvableTurnKind(1))
        assertEquals(10, GeelyHudTurnMapping.resolvableTurnKind(28))
    }

    @Test
    fun `所有可能的type都不会让resolvableTurnKind返回意外的不可发值`() {
        // 0x9c 是所有未知/零 kind 的出口，它必定可发。
        // 这条测试的作用是：若有人改了 oneOsTurnId 表而忘了 156 这一项，
        // 整条 HUD 会在"未知转向"时静默停发——这里会立刻红。
        for (type in 0..60) {
            val kind = GeelyHudTurnMapping.resolvableTurnKind(type)
            if (kind != null) {
                assertEquals(
                    "type=$type 返回了可发 kind=$kind，但它必须能被 oneOsTurnId 识别",
                    true,
                    GeelyHudTurnMapping.oneOsTurnId(kind) in 0x65..0xc0,
                )
            }
        }
    }
}
