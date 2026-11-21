package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarAdbHotspotBootInstaller
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「DBPlay 热点随车机开机自启」的**契约测试**（2026-10-06 新增）。
 *
 * ## 为什么要有这些断言
 *
 * 这个功能的失败模式很隐蔽：装机后**当场看起来一切正常**（热点确实开了），
 * 只有**整机重启后**才暴露（热点不回来）。如果脚本有语法错、开关读错、
 * 路径写错，现场排查要从车上拉日志、复现重启 —— 代价极高。
 *
 * 所以这里把「脚本必须满足的性质」全部固化成断言，任何一条被改坏
 * 在 CI 就红，不上车。
 *
 * 断言分五组：
 * 1. 脚本基本结构（shebang / 可执行 / 关键外部命令都在）；
 * 2. 权威口径：`sys.boot_completed` 触发、落盘开关为 0 时**必须直接退出**；
 * 3. 与车机实测对齐（网卡名、SSID、信道、不依赖 ADB）；
 * 4. 幂等与清理（不重复起 hostapd）；
 * 5. 安装/卸载/探测命令的组装正确性（含 base64 单行约束）。
 */
class CarAdbHotspotBootInstallerTest {

    private val script = CarAdbHotspotBootInstaller.BOOT_SCRIPT
    private val rc = CarAdbHotspotBootInstaller.BOOT_RC

    // ---------------------------------------------------------------- 1. 基本结构

    @Test
    fun `脚本是可执行的 sh 脚本`() {
        assertTrue(
            "脚本必须以 #!/system/bin/sh 开头，否则 init 起不来",
            script.startsWith("#!/system/bin/sh"),
        )
    }

    @Test
    fun `脚本用系统自带命令，不依赖 ADB`() {
        // 开机那一刻 ADB 通道还没建（App 端 20 步的第 1 步才是开通 ADB），
        // 所以脚本绝不能调用 adb / am / pm 这类依赖 ADB 或 App 框架的东西。
        listOf("adb ", "am start", "pm grant").forEach { forbidden ->
            assertFalse(
                "脚本不能依赖「$forbidden」——开机时 ADB 通道尚未建立",
                script.contains(forbidden),
            )
        }
        // 必须用系统自带命令
        listOf("ifconfig", "getprop", "killall").forEach { required ->
            assertTrue("脚本应使用系统自带命令 $required", script.contains(required))
        }
    }

    @Test
    fun `脚本必须有 log 落盘，否则现场无法排查`() {
        assertTrue(script.contains(CarAdbHotspotDefaults.BOOT_LOG_PATH))
        assertTrue(script.contains("log()"))
    }

    // -------------------------------------------- 2. 权威口径：永久开关语义

    @Test
    fun `落盘开关不为 ON 时脚本必须直接退出`() {
        // 斌哥口径：「两者都要：开机自动回来，但设置页仍能永久关掉
        // （关掉后重启也不回来）」。
        // 实现就是这个：脚本第一步读 persist 属性，不等 ON 就 exit 0。
        val prop = CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY
        assertTrue("脚本必须读永久开关属性 $prop", script.contains("getprop \$ENABLED_PROP"))

        // exit 0 必须出现在「检查开关」之后、「建热点」之前
        val idxCheck = script.indexOf("ENABLED_PROP=")
        val idxExit = script.indexOf("exit 0")
        val idxBuild = script.indexOf("killall -s KILL hostapd")
        assertTrue("必须先读开关", idxCheck in 1 until idxExit)
        assertTrue("关掉时必须早于建热点就退出", idxExit in 1 until idxBuild)
    }

    @Test
    fun `落盘属性名是 persist 前缀，才可能跨重启存活`() {
        // persist.* 存放在 /data/property，随重启保留；
        // 用普通属性（如 service.*、sys.*）重启就丢，开关语义立刻失效。
        assertTrue(
            "永久开关必须是 persist.* 属性，否则重启即丢",
            CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY.startsWith("persist."),
        )
    }

    @Test
    fun `init 服务由 sys_boot_completed 触发`() {
        // 这是 BootReceiver 收不到 BOOT_COMPLETED 时唯一可靠的自动拉起点。
        assertTrue(
            "init 服务必须在 sys.boot_completed=1 时启动",
            rc.contains("on property:sys.boot_completed=1"),
        )
        assertTrue("init 服务要 start dbplay-hotspot", rc.contains("start dbplay-hotspot"))
    }

    // ------------------------------------------------- 3. 与车机实测对齐

    @Test
    fun `具体网络参数取自默认配置，不写死`() {
        // 网段是运行时由 planner 算的，这里只能校验「用的常量确实是默认源」。
        val vars = CarAdbHotspotBootInstaller.buildNetworkVariables(
            hostAddress = "192.168.176.1",
            subnet = "192.168.176.0/24",
            dhcpStart = "192.168.176.2",
            dhcpEnd = "192.168.176.254",
        )
        assertTrue(vars.contains("HOST_ADDRESS=192.168.176.1"))
        assertTrue(vars.contains("SUBNET=192.168.176.0/24"))
        assertTrue(vars.contains("DHCP_START=192.168.176.2"))
        assertTrue(vars.contains("write_udhcpd_conf"))
        // DHCP 池上限应等于区间大小，且为正值
        val maxLeases = Regex("MAX_LEASES=(\\d+)").find(vars)!!.groupValues[1].toInt()
        assertTrue("MAX_LEASES 应为正数，实际 $maxLeases", maxLeases > 0)
    }

    @Test
    fun `SSID 与信道沿用 DBPlay 默认`() {
        assertTrue(script.contains("ssid=" + CarAdbHotspotDefaults.SSID))
        assertTrue(script.contains("channel=" + CarAdbHotspotDefaults.DEFAULT_CHANNEL))
    }

    @Test
    fun `开机脚本的 softap 配置绝不落在车机自己的 softap_conf`() {
        // 2026-09-30 事故根因：车机自己的 hostapd（wifi uid）就在读
        // /data/softap.conf，覆盖它会让「存储 SSID」与「实际广播 SSID」漂移。
        assertTrue(
            "开机脚本要用私有配置路径，不能碰车机的 /data/softap.conf",
            CarAdbHotspotDefaults.BOOT_SOFTAP_CONF != "/data/softap.conf",
        )
        assertTrue(
            "私有配置应落在 /data/local/tmp 下",
            CarAdbHotspotDefaults.BOOT_SOFTAP_CONF.startsWith("/data/local/tmp/"),
        )
    }

    @Test
    fun `脚本等待网卡就绪后才动手`() {
        // 开机早期 wlan0 可能还没出现，直接配地址会失败。
        assertTrue("脚本应有等待网卡的循环", script.contains("while [ \$i -lt"))
        assertTrue(script.contains("[ -e /sys/class/net/\$IFACE ]"))
    }

    @Test
    fun `hostapd 路径逐个候选探测，不假定单一位置`() {
        // 不同 ROM 放 /vendor/bin/hw 或 /system/bin，写死一个必然有车挂。
        assertTrue("脚本应遍历候选路径", script.contains("for p in \$HOSTAPD_CANDIDATES"))
        assertTrue(CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES.size >= 1)
    }

    // ---------------------------------------------------- 4. 幂等与清理

    @Test
    fun `脚本每次先清旧的 hostapd，保证可重复执行`() {
        // 同一张 wlan0 上不能同时有两张 AP；不做清理的话第二次跑会静默失败。
        assertTrue(script.contains("killall -s KILL hostapd"))
        assertTrue(script.contains("ip rule del"))
    }

    @Test
    fun `长驻进程用 nohup 加后台，脚本自己能退出`() {
        // init 服务是 oneshot：脚本必须退出，hostapd/udhcpd 必须活下来。
        assertTrue("hostapd 应 nohup 后台", script.contains("nohup \"\$HOSTAPD\""))
        assertTrue("udhcpd 应 nohup 后台", script.contains("nohup \"\$BUSYBOX\""))
        assertTrue("脚本末尾应 exit 0", script.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun `init 服务是 oneshot 且不在 class 启动时自动起`() {
        assertTrue("应声明 oneshot", rc.contains("oneshot"))
        assertTrue("应声明 disabled，避免 class 启动时误触发", rc.contains("disabled"))
        assertTrue("应以 root 运行", rc.contains("user root"))
    }

    // ------------------------------------------- 5. 安装 / 卸载 / 探测命令

    @Test
    fun `安装命令先验 system 可写，验不过就整步跳过`() {
        val cmd = CarAdbHotspotBootInstaller.buildInstallScript("QUJD", "REVG", "su 0 ")
        assertTrue("安装前应探测 /system 可写", cmd.contains("mount -o remount,rw /system"))
        assertTrue("不可写时应回 SYSTEM_NOT_RW", cmd.contains("SYSTEM_NOT_RW"))
        assertTrue("成功时应回 INSTALL_OK", cmd.contains("INSTALL_OK"))
        assertTrue("应以 su 0 提权", cmd.startsWith("su 0 "))
    }

    /**
     * 🔴 可写性判据必须是「实测写入」，**不得**再去解析 `mount` 输出。
     *
     * ## 这条测的是一个已经真实发生过的严重故障（2026-10-07）
     *
     * 旧实现是 `mount | grep -q " /system .* rw,"`。这个正则**永远不命中**：
     * 真实 `mount` 输出里 `/system` 与 `rw,` 之间隔着 ` type ext4 (`，
     * 而正则只允许中间是空白。后果是**开机自启服务从来没能装上**——
     * 每次走到这一步都报 `SYSTEM_NOT_RW`，而用户看到的只是一句
     * "可能有 dm-verity 保护" 的假提示，完全指向了错误的方向。
     *
     * 只有老式 `/proc/mounts` 格式（`… /system ext4 rw,…`）才碰巧命中，
     * 所以这个 bug 在开发机上完全不显形。
     *
     * ⇒ 判据改成 `touch /system/.dbplay-rwtest`：能不能写，事实说话。
     */
    @Test
    fun `可写性判据是实测写入而非解析mount`() {
        val install = CarAdbHotspotBootInstaller.buildInstallScript("QUJD", "REVG", "su 0 ")
        val probe = CarAdbHotspotBootInstaller.buildProbeScript("su 0 ")

        listOf("安装" to install, "探测" to probe).forEach { (what, cmd) ->
            assertTrue(
                "$what 命令应实测写入 /system 来判定可写性",
                cmd.contains("touch /system/.dbplay-rwtest"),
            )
            assertTrue(
                "$what 命令测试后必须清掉临时文件，不留垃圾",
                cmd.contains("rm -f /system/.dbplay-rwtest"),
            )
        }

        // 反向断言：这个永远不命中的正则绝不能再出现（它就是这个 bug 本身）
        assertFalse(
            "不得再用会永久失配的 mount 正则判可写性（/system 与 rw, 之间隔着 type xxx (）",
            install.contains("/system .* rw,") || probe.contains("/system .* rw,"),
        )
    }

    @Test
    fun `安装命令无论成败都挂回只读`() {
        val cmd = CarAdbHotspotBootInstaller.buildInstallScript("QUJD", "REVG", "su 0 ")
        assertTrue("必须挂回 ro，不能长期留 rw", cmd.contains("mount -o remount,ro /system"))
        // ⚠️ 别用 "INSTALL" 定位写文件位置 —— 那句其实是回读校验里的
        // `echo "INSTALL_OK"`，它本身就在挂回 ro 之后。要用 chmod（真写文件的一步）。
        assertTrue(
            "挂回 ro 必须发生在写完文件（chmod）之后",
            cmd.indexOf("remount,ro") > cmd.indexOf("chmod 0755"),
        )
    }

    @Test
    fun `安装命令把脚本与 rc 落到约定路径`() {
        val cmd = CarAdbHotspotBootInstaller.buildInstallScript("QUJD", "REVG", "su 0 ")
        assertTrue(cmd.contains(CarAdbHotspotDefaults.BOOT_SCRIPT_PATH))
        assertTrue(cmd.contains(CarAdbHotspotDefaults.BOOT_RC_PATH))
        assertTrue("脚本应 0755 可执行", cmd.contains("chmod 0755"))
        assertTrue("rc 应 0644", cmd.contains("chmod 0644"))
    }

    @Test
    fun `安装与卸载命令里不得出现裸单引号`() {
        // 整条命令包在 sh -c '...' 里；内部再有单引号会把这个引号提前闭合，
        // 命令从中间被拆断 —— 这是最难查的一类车机故障。
        listOf(
            CarAdbHotspotBootInstaller.buildInstallScript("QUJD", "REVG", "su 0 "),
            CarAdbHotspotBootInstaller.buildUninstallScript("su 0 "),
            CarAdbHotspotBootInstaller.buildProbeScript("su 0 "),
        ).forEach { cmd ->
            assertEquals(
                "命令内部只应有首尾两个单引号",
                2,
                cmd.count { it == '\'' },
            )
        }
    }

    @Test
    fun `卸载只删本方案的两个文件与私有产物`() {
        val cmd = CarAdbHotspotBootInstaller.buildUninstallScript("su 0 ")
        // ⚠️ 两个路径写在**同一条** `rm -f a b;` 里，所以不能按
        // `contains("rm -f " + rc)` 判（只有紧跟 `rm -f ` 的那个才匹配得上）。
        // 统一用「删除动作的参数集合」判定。
        val removedPaths = Regex("rm -f ([^;]+)").findAll(cmd)
            .flatMap { it.groupValues[1].trim().split(Regex("\\s+")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        assertTrue(
            "卸载必须删掉自启脚本，实际删了：" + removedPaths.joinToString(" "),
            CarAdbHotspotDefaults.BOOT_SCRIPT_PATH in removedPaths,
        )
        assertTrue(
            "卸载必须删掉 init rc，实际删了：" + removedPaths.joinToString(" "),
            CarAdbHotspotDefaults.BOOT_RC_PATH in removedPaths,
        )
        assertTrue("应回 UNINSTALL_OK", cmd.contains("UNINSTALL_OK"))
        // ⚠️ busybox 是 App 自身功能的依赖，不属本方案，绝不能顺手删
        // ⚠️ 也不能用 `contains("rm -f /data/local/tmp/busybox")` 判 ——
        // 卸载命令里有 `rm -f /data/local/tmp/dbplay-hostapd.log`，
        // 而 busybox 路径是那个字符串的**前缀**，contains 会假阳性。
        assertFalse(
            "卸载不得删除 " + CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH +
                "（那是 App 自身依赖），实际删了：" + removedPaths.joinToString(" "),
            CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH in removedPaths,
        )
    }

    @Test
    fun `探测命令是只读的，不改任何文件`() {
        val cmd = CarAdbHotspotBootInstaller.buildProbeScript("su 0 ")
        assertTrue("探测应回 PROBE_RW_OK / PROBE_RW_FAIL", cmd.contains("PROBE_RW_OK"))
        assertTrue("探测应顺便报 hostapd 路径", cmd.contains("HOSTAPD: "))
        assertTrue("探测应报永久开关当前值", cmd.contains("BOOT_PROP: "))
        assertFalse("探测不得写文件", cmd.contains("> " + CarAdbHotspotDefaults.BOOT_SCRIPT_PATH))
        // ⚠️ 这条断言的意图是「不得动**系统/方案文件**」，不是「一个 rm 都不许有」。
        // 探测要判 /system 可写，判据是实测写入（touch）→ 必须自建自删那个临时文件，
        // 否则会在 /system 里留垃圾。所以按「删了什么」判定，而不是「有没有 rm」。
        val removedPaths = Regex("rm -f ([^;]+)").findAll(cmd)
            .flatMap { it.groupValues[1].trim().split(Regex("\\s+")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        assertTrue(
            "探测只允许删自己的可写性探针，实际删了：" + removedPaths.joinToString(" "),
            removedPaths.all { it == "/system/.dbplay-rwtest" },
        )
        assertFalse(
            "探测不得删自启脚本",
            CarAdbHotspotDefaults.BOOT_SCRIPT_PATH in removedPaths,
        )
        assertFalse(
            "探测不得删 init rc",
            CarAdbHotspotDefaults.BOOT_RC_PATH in removedPaths,
        )
        // 即便探测中临时挂了 rw，也必须挂回去
        assertTrue(cmd.contains("mount -o remount,ro /system"))
    }

    @Test
    fun `不传前缀时命令不带 su，兼容已是 root 的场景`() {
        val cmd = CarAdbHotspotBootInstaller.buildProbeScript("")
        assertTrue("空前缀时命令应直接以 /system/bin/sh 开头", cmd.startsWith("/system/bin/sh"))
        assertFalse(cmd.startsWith("su"))
    }
}
