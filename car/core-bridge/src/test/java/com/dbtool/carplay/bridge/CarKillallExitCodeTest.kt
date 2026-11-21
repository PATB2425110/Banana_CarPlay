package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarKillallExitCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `killall` 退出码折算的单测（2026-10-06）。
 *
 * ## 为什么这条规则值得钉死
 *
 * "清理网卡占用者"（`killall -s KILL hostapd busybox`）在**网卡上本来就没有
 * 占用者**时必然退出 1（`No such process`）。0.10.7 实车诊断包里这一步
 * **每一次都是 FAIL**——它是"第一个失败步"的常客，而诊断页与失败页都按
 * `firstFailure` 定位 ⇒ 读日志的人先被它带偏，真正的失败步反而被推到后面。
 *
 * ## 两个方向都要钉
 *
 * - **判宽了**（只要退出码非 0 就吞掉）⇒ 真的没权限时也被当成成功，
 *   现场看到"清理成功"却依然抢不到网卡，现象与原因脱节；
 * - **判严了**（不折算）⇒ 每次诊断都多一条假失败，见上。
 *
 * 所以下面既有"该折算"的正例，也有"不该折算"的反例。
 */
class CarKillallExitCodeTest {

    /** 实车原文（0.10.7 诊断包 `1791279270080`，每一次都出现）。 */
    @Test
    fun `两个目标都不存在时折算成成功`() {
        val output = "killall: hostapd: No such process\nkillall: busybox: No such process"
        assertTrue(CarKillallExitCode.isNoTarget(exitCode = 1, output = output))
    }

    /** 只有一个目标不存在（另一个真被杀了）：同样是"没有可清理的残留"。 */
    @Test
    fun `只有一个目标不存在时也折算成成功`() {
        assertTrue(CarKillallExitCode.isNoTarget(1, "killall: hostapd: No such process"))
    }

    @Test
    fun `退出码为 0 不算无目标`() {
        // 0 本身就是成功，折算函数不该插手（避免语义重叠）。
        assertFalse(CarKillallExitCode.isNoTarget(0, "killall: hostapd: No such process"))
    }

    // ------------------------------------------------------------- 反例

    /** ⚠️ 真没权限：必须原样保留失败，不能吞掉。 */
    @Test
    fun `权限不足时不折算`() {
        val output = "killall: hostapd: Operation not permitted"
        assertFalse(CarKillallExitCode.isNoTarget(1, output))
    }

    @Test
    fun `permission denied 时不折算`() {
        assertFalse(CarKillallExitCode.isNoTarget(1, "killall: busybox: Permission denied"))
    }

    @Test
    fun `参数非法时不折算`() {
        assertFalse(CarKillallExitCode.isNoTarget(1, "killall: invalid option -- 's'"))
    }

    /**
     * ⚠️ 最容易误判的一例：`killall: /system/bin/killall: not found` 里的
     * `not found` **不含** `No such process`，但也绝不能折算成成功
     * ——那是 killall 这个二进制本身不存在，不是"没有可杀的目标"。
     */
    @Test
    fun `killall 自身不存在时不折算`() {
        assertFalse(CarKillallExitCode.isNoTarget(127, "sh: /system/bin/killall: not found"))
    }

    /**
     * 反向断言：只有 `not found`、没有 `no such process` —— 不折算。
     * 这条与上一条一起说明"not found"不在折算白名单里。
     */
    @Test
    fun `只有 not found 没有 no such process 时不折算`() {
        assertFalse(CarKillallExitCode.isNoTarget(1, "/system/bin/killall: not found"))
    }

    /** 混合场景：既有 No such process、又有别的错误 ⇒ 以错误为准，不折算。 */
    @Test
    fun `同时存在其它错误时不折算`() {
        val output = "killall: hostapd: No such process\nkillall: busybox: Operation not permitted"
        assertFalse(CarKillallExitCode.isNoTarget(1, output))
    }

    @Test
    fun `空输出不折算`() {
        assertFalse(CarKillallExitCode.isNoTarget(1, ""))
    }

    /** 大小写不敏感（不同 busybox 版本的措辞大小写不一）。 */
    @Test
    fun `大小写不敏感`() {
        assertTrue(CarKillallExitCode.isNoTarget(1, "killall: hostapd: NO SUCH PROCESS"))
        assertFalse(CarKillallExitCode.isNoTarget(1, "killall: hostapd: OPERATION NOT PERMITTED"))
    }
}
