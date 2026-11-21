package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbShellQuirks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "无害非零退出码"判据的单测。
 *
 * ## 为什么这条判据值得单独钉死
 *
 * 它决定**整条 ADB 热点链要不要放弃**：
 *
 * ```
 * ip rule add → File exists（退出码 2）
 *   判错（当成失败） ⇒「热点配置没写进去」⇒ 放弃 ADB 热点
 *                  ⇒ 回落 LocalOnlyHotspot ⇒ 本车机 LOH 回调不来
 *                  ⇒ 60 秒超时 ⇒ LOH 中毒 ⇒ 此后每次都秒失败
 * ```
 *
 * 而它**极容易触发**：只要上一次没走到 `tearDown`（进程被杀、会话异常结束），
 * `ip rule del` 就不会执行，规则留到下一次拉起。
 *
 * ⚠️ 反过来的风险同样要防：把**真的没配上**的报错当成无害，会让热点"假装就绪"。
 * 所以下面两条"必须继续算失败"的断言与"File exists 算成功"同等重要。
 */
class CarAdbShellQuirksTest {

    @Test
    fun `RTNETLINK File exists 判为无害`() {
        assertTrue(
            CarAdbShellQuirks.isBenignIpConflict(
                "RTNETLINK answers: File exists",
            ),
        )
    }

    /** 大小写不敏感：不同 toybox/iproute2 版本的输出大小写不一致。 */
    @Test
    fun `File exists 大小写不敏感`() {
        assertTrue(CarAdbShellQuirks.isBenignIpConflict("rtnetlink answers: file EXISTS"))
    }

    /** 真机上这条报错前面常带 `ip rule add to ... ` 的回显，所以要按"包含"判定。 */
    @Test
    fun `带前后文仍能识别`() {
        assertTrue(
            CarAdbShellQuirks.isBenignIpConflict(
                "ip rule add to 192.168.175.1/24 priority 200\nRTNETLINK answers: File exists\n",
            ),
        )
    }

    /** `Network is unreachable` = 网卡根本没配上网段，必须继续算失败。 */
    @Test
    fun `Network is unreachable 不算无害`() {
        assertFalse(
            CarAdbShellQuirks.isBenignIpConflict("RTNETLINK answers: Network is unreachable"),
        )
    }

    /** `Operation not permitted` = 提权没生效，必须继续算失败。 */
    @Test
    fun `Operation not permitted 不算无害`() {
        assertFalse(
            CarAdbShellQuirks.isBenignIpConflict("RTNETLINK answers: Operation not permitted"),
        )
    }

    /** `sh: ip: not found` = 命令不存在，必须继续算失败。 */
    @Test
    fun `命令不存在不算无害`() {
        assertFalse(CarAdbShellQuirks.isBenignIpConflict("/system/bin/sh: ip: not found"))
    }

    /** 空输出（正常成功、无 stderr）不能误判成无害——否则"静默失败"会被放过。 */
    @Test
    fun `空输出不算无害`() {
        assertFalse(CarAdbShellQuirks.isBenignIpConflict(""))
        assertFalse(CarAdbShellQuirks.isBenignIpConflict("   \n"))
    }
}
