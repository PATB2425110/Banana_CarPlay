package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbDeviceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `adb devices` 解析的单测。
 *
 * ## 为什么这条判据必须钉死
 *
 * 它决定**后面每条 `shell` 命令打到哪台设备上**。我们执行的是
 * `ifconfig` / `killall hostapd` / `ip rule add` —— 打错设备比直接失败危险得多。
 *
 * 两个方向都有真实教训：
 *
 * - **判宽了**（把 `offline` / `unauthorized` 当成连上）⇒ 后续每条命令都失败，
 *   而日志里只看到"已连接 127.0.0.1:5555"，现象与原因完全脱节；
 * - **判严了**（读到 null 就放弃）⇒ `connect` 返回后设备短暂处于 `offline`
 *   那一下就把整条链掐断。参考实现根本不看 `devices` 的结论，
 *   我们把它当闸门就必须**重试**（重试逻辑在 `AdbShellRunner.connect`，
 *   这里钉的是"什么时候该返回 null"这个前提）。
 */
class CarAdbDeviceListTest {

    private val loopback = "127.0.0.1:5555"

    @Test
    fun `标准输出解析出 loopback 序列号`() {
        val output = """
            List of devices attached
            $loopback	device

        """.trimIndent()
        assertEquals(loopback, CarAdbDeviceList.pickSerial(output, loopback))
    }

    /** 实测过：自建 adb server 的 `devices` 里会混进别的设备。 */
    @Test
    fun `有别的设备时优先 loopback 而不是第一行`() {
        val output = """
            List of devices attached
            emulator-5554	device
            $loopback	device

        """.trimIndent()
        assertEquals(loopback, CarAdbDeviceList.pickSerial(output, loopback))
    }

    /** 同一台主机但端口不同（adb 换了端口）也要认得出来。 */
    @Test
    fun `同主机不同端口也能命中`() {
        val output = """
            List of devices attached
            127.0.0.1:5038	device

        """.trimIndent()
        assertEquals("127.0.0.1:5038", CarAdbDeviceList.pickSerial(output, loopback))
    }

    @Test
    fun `只有离线设备时返回 null`() {
        val output = """
            List of devices attached
            $loopback	offline

        """.trimIndent()
        assertNull(CarAdbDeviceList.pickSerial(output, loopback))
    }

    @Test
    fun `未授权设备返回 null`() {
        val output = """
            List of devices attached
            $loopback	unauthorized

        """.trimIndent()
        assertNull(CarAdbDeviceList.pickSerial(output, loopback))
    }

    @Test
    fun `只有表头时返回 null`() {
        assertNull(CarAdbDeviceList.pickSerial("List of devices attached\n\n", loopback))
    }

    @Test
    fun `空输出返回 null`() {
        assertNull(CarAdbDeviceList.pickSerial("", loopback))
    }

    /**
     * `offline` 与 `device` 同时存在（正是"刚 connect 完"的瞬时状态）：
     * 必须挑出那个 `device`，而不是因为存在 offline 行就整体判失败。
     */
    @Test
    fun `offline 与 device 并存时挑出 device`() {
        val output = """
            List of devices attached
            $loopback	offline
            emulator-5554	device

        """.trimIndent()
        assertEquals("emulator-5554", CarAdbDeviceList.pickSerial(output, loopback))
    }

    /** `-l`（长格式）会带 product/model 等附加列，状态仍是第 2 段。 */
    @Test
    fun `长格式附加列不影响解析`() {
        val output = """
            List of devices attached
            $loopback	device product:xx model:yy device:zz transport_id:1

        """.trimIndent()
        assertEquals(loopback, CarAdbDeviceList.pickSerial(output, loopback))
    }

    /** 冒号分隔的行不能因为 `substringBefore(':')` 把 IP 截断。 */
    @Test
    fun `冒号分隔的地址不被截断`() {
        val output = """
            List of devices attached
            192.168.10.12:5555	device

        """.trimIndent()
        assertEquals("192.168.10.12:5555", CarAdbDeviceList.pickSerial(output, loopback))
    }

    /**
     * ⚠️ 2026-10-06 实车（0.10.7 诊断包 `1791279270080`）根因回归钉。
     *
     * `reconnect()` 原实现 `connect 127.0.0.1`（不带端口），adb 会同时留下
     * **裸 `127.0.0.1`** 与厂商口 `192.168.175.1:5555` 两条记录。此时若
     * `pickSerial` 挑中的不是我们那条带端口的通道，后续 `-s <带端口>` 的
     * 每条命令都会 `error: device '127.0.0.1:5556' not found`——
     * `requestAdbRoot` 的验证循环正是这样自己把自己判死的
     * （而 `service.adb.root=1` 证明提权其实是成功的）。
     *
     * 钉两件事：**目标口优先**、**同主机的厂商口不会被误选**。
     */
    @Test
    fun `重连场景优先挑带端口的目标口而不是裸 loopback`() {
        val target = "127.0.0.1:5556"
        val output = """
            List of devices attached
            127.0.0.1	device
            $target	device

        """.trimIndent()
        assertEquals(target, CarAdbDeviceList.pickSerial(output, target))
    }

    /** 目标口不在时，同主机的其它端口是可接受的兜底（别判失败）。 */
    @Test
    fun `目标口缺失时同主机其它端口兜底`() {
        val target = "127.0.0.1:5556"
        val output = """
            List of devices attached
            127.0.0.1:5555	device

        """.trimIndent()
        assertEquals("127.0.0.1:5555", CarAdbDeviceList.pickSerial(output, target))
    }

    // ---------------------------------------------------------- looksConnected
    //
    // 判据抄自参考实现的字节码：`connect` 的返回值判 `contains("connected")`。
    // 这两条正/反向断言一起才说明它不是"含 connect 就算过"。

    @Test
    fun `首次连接成功的输出算已连上`() {
        assertTrue(CarAdbDeviceList.looksConnected("connected to 127.0.0.1:5555"))
    }

    /** ⚠️ 重复连接时 adb 的真实输出是 `already connected to …`，必须也算连上。 */
    @Test
    fun `重复连接 already connected 也算已连上`() {
        assertTrue(CarAdbDeviceList.looksConnected("already connected to 127.0.0.1:5555"))
    }

    /** 反向断言：`failed to connect to …` 里的 `connect to` 不是 `connected`。 */
    @Test
    fun `连接失败的输出不算已连上`() {
        assertFalse(CarAdbDeviceList.looksConnected("failed to connect to 127.0.0.1:5555"))
    }

    @Test
    fun `空输出不算已连上`() {
        assertFalse(CarAdbDeviceList.looksConnected(""))
    }

    // ------------------------------------------------------- referenceSerial
    //
    // 解析不到序列号时的兜底值必须与参考实现用的 `-s 127.0.0.1` 一致：
    // **不带端口**（adb 对 `-s <host>` 会自动补 5555）。

    @Test
    fun `兜底序列号是去掉端口的主机部分`() {
        assertEquals("127.0.0.1", CarAdbDeviceList.referenceSerial("127.0.0.1:5555"))
    }

    @Test
    fun `兜底序列号对不含端口的输入原样返回`() {
        assertEquals("127.0.0.1", CarAdbDeviceList.referenceSerial("127.0.0.1"))
    }
}
