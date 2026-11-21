package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbHotspotCommands
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机侧 shell 命令字符串的单测。
 *
 * ## 为什么这几行字符串值得单独钉
 *
 * 它们要经 `adb shell <一串字符串>` 发到设备上，**由设备侧 shell 再解析一次**。
 * 于是"提权前缀 + 复合语句 + 引号"的组合有几种**不会报错的错法**：
 *
 * - `su 0 for f in ...; do ...; done` ⇒ `su` 把 `for` 当命令名 ⇒ `for: not found`。
 *   调用方若不看输出，就会把"探测不到"误读成"设备上没有 hostapd"。
 * - 复合语句不套 `sh -c '...'` ⇒ 只有**第一条**命令以 root 跑，
 *   `;` 后面那些**悄悄退回原 uid** —— 这类错最难发现。
 *
 * 这些都不是靠"上车试"能高效发现的（现象与原因完全脱节），所以在这里钉死。
 */
class CarAdbHotspotCommandsTest {

    private val candidates = CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES

    @Test
    fun `无提权前缀时直接交给 sh -c`() {
        val command = CarAdbHotspotCommands.hostapdProbe("", candidates)
        assertTrue(command.startsWith("/system/bin/sh -c '"))
    }

    @Test
    fun `有提权前缀时 su 在前、sh -c 在后`() {
        val command = CarAdbHotspotCommands.hostapdProbe("su 0 ", candidates)
        assertTrue(command.startsWith("su 0 /system/bin/sh -c '"))
    }

    /**
     * 🔴 反向断言：**绝不能**出现 `su 0 for`。
     *
     * `su 0 <cmd>` 把后面的参数当**一个命令**执行，`for` 不是命令名。
     * 写成这样只会得到 `for: not found`，然后"探测不到"被误读成"设备上没有"。
     */
    @Test
    fun `绝不把复合语句直接跟在 su 后面`() {
        val command = CarAdbHotspotCommands.hostapdProbe("su 0 ", candidates)
        assertFalse(command.contains("su 0 for"))
        assertFalse(command.contains("su 0 test"))
        // 复合语句必须整体在单引号里
        assertTrue(command.contains("sh -c 'for f in "))
        assertTrue(command.endsWith("done; true'"))
    }

    /** 所有候选都要出现在探测脚本里（漏一个就等于少试一条路）。 */
    @Test
    fun `所有候选路径都进脚本`() {
        val command = CarAdbHotspotCommands.hostapdProbe("su 0 ", candidates)
        candidates.forEach { path ->
            assertTrue("候选 $path 未出现在探测命令里", command.contains(path))
        }
    }

    /**
     * 脚本里对 `$f` 必须是**双引号**：它要由**内层** `sh` 展开。
     * 若用单引号或裸写，外层就会先替换（替换成空串）或根本不展开。
     */
    @Test
    fun `脚本内的 f 用双引号以便内层 sh 展开`() {
        val command = CarAdbHotspotCommands.hostapdProbe("su 0 ", candidates)
        assertTrue(command.contains("test -x \"\$f\""))
        assertTrue(command.contains("echo \"OK \$f\""))
    }

    /** 收尾 `true`：最后一个候选不存在时不要让整条命令返回非零。 */
    @Test
    fun `脚本以 true 收尾`() {
        assertTrue(CarAdbHotspotCommands.hostapdProbe("", candidates).endsWith("done; true'"))
    }

    /**
     * busybox 探测**不带提权前缀**：`/data/local/tmp/busybox` 已被 `chmod 0755`，
     * shell uid 直接可执行；自检承诺只读，不该为此动系统状态。
     */
    @Test
    fun `busybox 探测不带提权前缀`() {
        val command = CarAdbHotspotCommands.stagedBusyboxProbe("/data/local/tmp/busybox")
        assertEquals("/data/local/tmp/busybox udhcpd --help", command)
        assertFalse(command.contains("su 0"))
    }

    // ------------------------------------------------------- hostapd 占用者

    @Test
    fun `占用者查询无前缀时直接交给 sh -c`() {
        val command = CarAdbHotspotCommands.hostapdOwners("")
        assertTrue(command.startsWith("/system/bin/sh -c '"))
    }

    @Test
    fun `占用者查询有前缀时 su 在前、sh -c 在后`() {
        val command = CarAdbHotspotCommands.hostapdOwners("su 0 ")
        assertTrue(command.startsWith("su 0 /system/bin/sh -c '"))
        assertFalse(command.contains("su 0 for"))
    }

    /**
     * ⚠️ 2026-10-04 重写：原实现把脚本直接塞进 `sh -c '<脚本>'`，而脚本内部为了
     * `tr '\000' ' '` 又用了一对单引号 —— 与外层定界单引号撞车，把脚本从中间
     * 截断，实测报 `syntax error: unmatched '('` / `unexpected 'do'`。
     *
     * 现在整段脚本走 **base64**：命令里只出现 base64 字母表（`A-Za-z0-9+/=`），
     * 既不含单引号也不含 `$`，因此外层设备 shell 与内层 `sh` 都不会误展开。
     *
     * 这里钉住三件事：① 是 base64 解码管道；② 解码后的原文里 `$p`/`$(pidof)`
     * 是**真正的 `$`**（留给内层 sh 展开）；③ 命令里不再出现裸的 `$(pidof hostapd)`。
     */
    @Test
    fun `占用者查询的变量在脚本内展开`() {
        val command = CarAdbHotspotCommands.hostapdOwners("")
        // ① 走 base64 解码管道交给内层 sh
        assertTrue(command.contains("| base64 -d | sh"))
        assertFalse(command.contains("sh -c 'for p in "))

        // ② 解码原文，检查脚本内容本身
        val b64 = Regex("echo ([A-Za-z0-9+/=]+) \\| base64 -d").find(command)?.groupValues?.get(1)
        assertNotNull(b64)
        val script = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
        assertTrue(script.contains("\$(pidof hostapd)"))
        assertTrue(script.contains("/proc/\$p/cmdline"))
        assertTrue(script.contains("\\000"))
        assertFalse(script.contains("\\\$"))
        // ③ 命令里不能再有裸的 `$(pidof hostapd)`（否则外层会先展开成空串）
        assertFalse(command.contains("\$(pidof hostapd)"))
    }

    /** 一个 hostapd 都没有时不该记成失败：那是结论，不是错误。 */
    @Test
    fun `占用者查询以 true 收尾`() {
        val command = CarAdbHotspotCommands.hostapdOwners("su 0 ")
        val b64 = Regex("echo ([A-Za-z0-9+/=]+) \\| base64 -d").find(command)?.groupValues?.get(1)
        assertNotNull(b64)
        val script = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
        assertTrue(script.endsWith("done; true"))
        assertTrue(command.startsWith("su 0 "))
    }

    // ------------------------------------------------- 停止热点（2026-10-11 回归：手机还能搜到）

    /**
     * 车主现场：「点了关闭，手机上**还能搜到**自建热点」。
     *
     * 根因：`killall -s KILL` 不给 hostapd 走 nl80211 `stop_ap` 的机会 ⇒ 进程没了、
     * **驱动仍停在 AP 模式**、beacon 照发。所以停止的顺序必须是
     * **先 TERM（体面拆 AP）→ 只有还活着才 KILL → 再校验网卡模式**。
     */
    @Test
    fun `停止 hostapd 先 TERM 再按需强杀`() {
        val graceful = CarAdbHotspotCommands.stopHostapdGracefully("su 0 ")
        assertTrue("必须带提权前缀：$graceful", graceful.startsWith("su 0 "))
        assertTrue("体面停止必须是 SIGTERM：$graceful", graceful.contains("-s TERM hostapd"))
        assertFalse("这一步不能是 KILL（KILL 不拆 AP）：$graceful", graceful.contains("KILL"))

        val force = CarAdbHotspotCommands.killHostapdAndUdhcpd("su 0 ")
        assertTrue("兜底强杀才是 SIGKILL：$force", force.contains("-s KILL hostapd busybox"))
    }

    @Test
    fun `iw dev 输出判定网卡是否仍是 AP`() {
        // 真机 `iw dev wlan0 info` 的样子（还在当 AP）
        val ap = "Interface wlan0\n\tifindex 12\n\taddr a4:04:50:b9:b9:9e\n" +
            "\tssid DBPlay\n\ttype AP\n\tchannel 36 (5180 MHz), width: 20 MHz"
        assertTrue(CarAdbHotspotCommands.iwDevShowsApMode(ap))

        val managed = "Interface wlan0\n\tifindex 12\n\ttype managed\n\tchannel 36 (5180 MHz)"
        assertFalse(CarAdbHotspotCommands.iwDevShowsApMode(managed))

        // 读不到（空输出）不能当成"还在 AP" ⇒ 宁可放过不可错杀
        assertFalse(CarAdbHotspotCommands.iwDevShowsApMode(""))
    }

    @Test
    fun `复位接口是放下再抬起，且只在必要时用`() {
        val bounce = CarAdbHotspotCommands.bounceInterface("su 0 ", "wlan0")
        assertTrue("要先把接口放下（移除该接口上的 BSS）：$bounce", bounce.contains("ip link set wlan0 down"))
        assertTrue("再抬起，恢复接口可用：$bounce", bounce.contains("ip link set wlan0 up"))
        assertTrue("down 必须在 up 之前", bounce.indexOf("down") < bounce.indexOf("up"))
    }
}
