package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自建热点把配置写到哪个文件」这条不变量的单测。
 *
 * ## 为什么这条要单独钉死（2026-09-30 实车根因）
 *
 * 早期版本照抄参考实现（`FUCK-Lynk&Co-ADB.apk`），把 `/data/softap.conf` 排在
 * 候选表第一位。实车 `ps -A` + `/proc/<pid>/cmdline` 证明**这个路径上有一个
 * hostapd 正在运行**（`/vendor/bin/hw/hostapd /data/softap.conf`）——
 * 也就是说它不是"一块空地"，而是**一张正在被别人读着的运行配置**。
 *
 * 写它的后果是一条完整的失败链，而且**每一环都不报错**：
 *
 * 1. 车机**存储**的热点配置仍是 `Lynk`（`getWifiApConfiguration()` 读到的值，
 *    也是上游校验、并最终发进 `0x5703` 给 iPhone 的那个名字）；
 * 2. 网卡**实际广播**的变成了被写进去的 `car`（hostapd 只在启动时读一次配置，
 *    改名不会让它重启 ⇒ 长期漂移）；
 * 3. iPhone 拿着 `Lynk` 找不到网络 ⇒ iAP2 无线隧道起不来；
 * 4. 界面停在「无线通道就绪」（那句话在 `onStatus(RunningWireless)` 处发出，
 *    **早于** AirPlay 真正握手）——而封面照旧能投（走蓝牙 iAP2），
 *    于是现象看起来像"只是音视频上不来"。
 *
 * 所以这条不变量必须由测试兜着，而不是靠注释提醒：
 * **我们只写自己的文件**。
 */
class CarAdbHotspotConfPathTest {

    /**
     * 车机上**别人的**（车机自己的 / 第三方留下的）热点运行配置路径。
     *
     * 这些路径上都有 hostapd 在读，写它们 = 覆盖别人的凭据。
     */
    private val foreignPaths = listOf(
        "/data/softap.conf",
        "/vendor/etc/softap.conf",
        "/data/misc/wifi/softap.conf",
        "/data/vendor/wifi/softap.conf",
        "/data/vendor/wifi/hostapd/hostapd.conf",
        "/data/misc/wifi/Tcamsoftap.conf",
    )

    @Test
    fun `候选表不得包含车机上任何别人的热点配置路径`() {
        val offenders = CarAdbHotspotDefaults.SOFTAP_CONF_CANDIDATES
            .filter { it in foreignPaths }
        assertTrue(
            "这些路径上有别人的 hostapd 在读，写进去会覆盖它的凭据（2026-09-30 实车根因）：$offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * 每个候选都必须是"一眼看出是我们的"文件。
     *
     * 用文件名前缀而不是"硬编码两个路径"来判：这样将来有人加候选时，
     * 只要忘了加 `dbplay-` 前缀就会红——而"这个文件是谁的"正是这次事故的根源。
     */
    @Test
    fun `每个候选的文件名都带 dbplay 前缀`() {
        CarAdbHotspotDefaults.SOFTAP_CONF_CANDIDATES.forEach { path ->
            assertTrue(
                "候选 $path 的名字里没有 dbplay- 前缀：现场会把它误认成车机自己的配置",
                path.substringAfterLast('/').contains("dbplay-"),
            )
        }
    }

    @Test
    fun `候选表非空且都是绝对路径`() {
        assertFalse(
            "候选表空了的话自建热点没有地方写配置",
            CarAdbHotspotDefaults.SOFTAP_CONF_CANDIDATES.isEmpty(),
        )
        CarAdbHotspotDefaults.SOFTAP_CONF_CANDIDATES.forEach { path ->
            assertTrue("候选 $path 不是以 / 开头的绝对路径", path.startsWith("/"))
        }
    }

    /**
     * 第一候选必须在 `/data/local/tmp` 下：那里是 `shell_data_file`，
     * shell 可写、hostapd 可读，是这台车机上唯一被验证过能用的落点。
     */
    @Test
    fun `第一候选落在 data local tmp`() {
        assertTrue(
            CarAdbHotspotDefaults.SOFTAP_CONF_CANDIDATES.first()
                .startsWith("/data/local/tmp/"),
        )
    }
}
