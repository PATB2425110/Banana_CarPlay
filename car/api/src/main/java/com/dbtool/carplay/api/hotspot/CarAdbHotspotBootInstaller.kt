package com.dbtool.carplay.api.hotspot

/**
 * 「DBPlay 热点开机自启」的**内置安装器**（纯字符串生成，无 IO、可单测）。
 *
 * ## 为什么要有它（2026-10-06 车机实测）
 *
 * 车机整机重启后热点**不回来**。查证结论：
 *
 * | 事实 | 证据 |
 * |---|---|
 * | `BOOT_COMPLETED` 没送到 App | 日志里 `carplay-boot` 标签**零命中** |
 * | 热点靠「App 起来时用 ADB 现搭」 | `adb hotspot step 1~20`（20 步） |
 * | 开机瞬间**连 ADB 都没有** | 20 步的第 1 步就是「开通车机 ADB」 |
 * | 车机自带 hostapd | `wifi 11487 … /vendor/bin/hw/hostapd` |
 * | root 可用、系统可写 | `adb shell uid=0(root)`、`ro.build.type=userdebug`、`selinux=permissive` |
 *
 * ⇒ 开机那一刻既没有 App 也没有 ADB，**唯一能起来的只有 init**。
 *   所以要在**首次成功建立热点时**，用已有的 root shell 把一个 init 服务
 *   装进 `/system`，之后每次开机由 init 自动拉起热点。
 *
 * ## 斌哥口径（产品逻辑，最高优先）
 *
 * > 「ADB 仅用于开启 DBPlay 永久热点，开启后热点随车机开机自动启动……
 * >   用户也可点击关闭 ADB 按钮，同步关闭热点。」
 * > 「确保 DBPlay 是永久开启」「两者都要：开机自动回来，但设置页仍能永久关掉
 * >   （关掉后重启也不回来）」
 *
 * 落到实现上就是 [BOOT_ENABLED_PROPERTY] 这个落盘属性：
 * **开 → 写 `1`；关 → 写 `0`；开机脚本第一步读它，为 `0` 直接退出。**
 *
 * ## 为什么脚本内容放在 Kotlin 里而不是 assets
 *
 * assets 里要多一套读取 + 写临时文件的路径，而脚本要写进 `/system`
 * 必须由 root shell 执行 —— 最省事且最可测的做法是把内容当**常量**，
 * 经 `base64` 传给车机解码（避免引号/换行被 shell 吃掉，见
 * [buildInstallScript] 的编码说明）。
 *
 * ## 绝不抄第三方
 *
 * 脚本逻辑照本工程 `CarAdbHotspotManager.configure()` 的 20 步流程重写
 * （那是本工程自己的实现），**不抄** `FUCK-Lynk&Co-ADB.apk` 或任何 GPL 代码。
 */
object CarAdbHotspotBootInstaller {

    /**
     * 开机自启脚本的**正文**（装到 [CarAdbHotspotDefaults.BOOT_SCRIPT_PATH]）。
     *
     * ## 与 App 端 20 步流程的对应关系
     *
     * | 脚本步骤 | 对应 App 端 |
     * |---|---|
     * | 等 `wlan0` 出现 | 无（App 起来时网卡早就在）|
     * | 探测 hostapd 路径 | `HOSTAPD_PATH_CANDIDATES` |
     * | 写 softap.conf / udhcpd.conf | `CarAdbHotspotConfig.softap/udhcpd` |
     * | `killall hostapd busybox` | `configure()` 的「清理网卡占用者」|
     * | `ifconfig wlan0 <host> up` | `assignAddress()` |
     * | `ip rule del` + `add` | `addIpRuleIdempotent()` |
     * | 起 hostapd | 「启动 hostapd」|
     * | 起 `busybox udhcpd -f` | 「启动 DHCP」|
     * | 收尾 `ifconfig` 校验 | 「校验网卡地址」|
     *
     * ## 三个必须注意的实现点
     *
     * 1. **不依赖 ADB**：开机时 ADB 通道还没建（App 端第 1 步就是开通它），
     *    所以脚本只用系统自带的 `ifconfig` / `ip` / `killall` 直启 hostapd。
     * 2. **`nohup ... &` 让长驻进程脱离**：init 服务按 `oneshot` 定义，
     *    脚本自己必须退出，hostapd/udhcpd 必须活下来。
     * 3. **幂等**：可重复执行，每次先清旧的再重建。
     */
    val BOOT_SCRIPT: String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# DBPlay 永久热点 —— 开机自启（由 DBPlay App 内置安装）")
        appendLine("# 本文件由 CarAdbHotspotBootInstaller 写入，请勿手工编辑。")
        appendLine("PATH=/system/bin:/system/xbin:/vendor/bin:/data/local/tmp")
        appendLine("export PATH")
        appendLine()
        appendLine("IFACE=" + CarAdbHotspotDefaults.DEFAULT_INTERFACE)
        appendLine("SOFTAP_CONF=" + CarAdbHotspotDefaults.BOOT_SOFTAP_CONF)
        appendLine("UDHCPD_CONF=" + CarAdbHotspotDefaults.BOOT_UDHCPD_CONF)
        appendLine("BUSYBOX=" + CarAdbHotspotDefaults.REMOTE_BUSYBOX_PATH)
        appendLine("LOG=" + CarAdbHotspotDefaults.BOOT_LOG_PATH)
        appendLine("ENABLED_PROP=" + CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY)
        appendLine("HOSTAPD_CANDIDATES=\"" +
            CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES.joinToString(" ") + "\"")
        appendLine()
        appendLine("log() { echo \"[\$(date '+%m-%d %H:%M:%S')] \$*\" >> \"\$LOG\"; }")
        appendLine("log \"===== dbplay-hotspot boot start =====\"")
        appendLine()
        // ---- 0. 落盘开关：关掉后重启也不回来（斌哥口径）
        appendLine("# 0. 用户是否永久开了热点？属性为 0 直接退出（这就是\"关掉后重启不回来\"）")
        appendLine("ENABLED=\$(getprop \$ENABLED_PROP)")
        appendLine("if [ \"\$ENABLED\" != \"" + CarAdbHotspotDefaults.BOOT_ENABLED_ON + "\" ]; then")
        appendLine("    log \"热点永久开关为 \$ENABLED（非 " +
            CarAdbHotspotDefaults.BOOT_ENABLED_ON + "），按用户意愿不启动\"")
        appendLine("    exit 0")
        appendLine("fi")
        appendLine("log \"永久开关=ON，开始建立热点\"")
        appendLine()
        // ---- 1. 等网卡
        appendLine("# 1. 等 wlan0 就绪（开机早期网卡可能还没起来）")
        appendLine("i=0")
        appendLine("while [ \$i -lt 30 ]; do")
        appendLine("    [ -e /sys/class/net/\$IFACE ] && break")
        appendLine("    sleep 1; i=\$((i + 1))")
        appendLine("done")
        appendLine("[ -e /sys/class/net/\$IFACE ] || { log \"✗ \$IFACE 30s 内未出现\"; exit 1; }")
        appendLine("log \"\$IFACE 已就绪（等待 \${i}s）\"")
        appendLine()
        // ---- 2. 找 hostapd
        appendLine("# 2. 探测 hostapd（不同 ROM 放不同路径）")
        appendLine("HOSTAPD=\"\"")
        appendLine("for p in \$HOSTAPD_CANDIDATES; do")
        appendLine("    if [ -x \"\$p\" ]; then HOSTAPD=\"\$p\"; break; fi")
        appendLine("done")
        appendLine("[ -n \"\$HOSTAPD\" ] || { log \"✗ 未找到 hostapd\"; exit 1; }")
        appendLine("log \"使用 hostapd：\$HOSTAPD\"")
        appendLine()
        // ---- 3. 写配置
        appendLine("# 3. 写热点配置（SSID/密码由 App 侧生成后下发，这里用 DBPlay 默认值）")
        appendLine("cat > \"\$SOFTAP_CONF\" <<'DBPLAY_EOF'")
        appendLine("interface=" + CarAdbHotspotDefaults.DEFAULT_INTERFACE)
        appendLine("driver=nl80211 ")
        appendLine("ieee80211n=1")
        // VHT（ieee80211ac）是 5 GHz 专属：2.4 GHz 下带上它 hostapd 会直接退出。
        // 与 CarAdbHotspotConfig 同源，用 isFiveGigahertz 判，不写死。
        if (CarAdbHotspotDefaults.isFiveGigahertz(CarAdbHotspotDefaults.DEFAULT_CHANNEL)) {
            appendLine("ieee80211ac=1")
        }
        appendLine("auth_algs=1")
        appendLine("wpa=2")
        appendLine("wpa_key_mgmt=WPA-PSK")
        appendLine("rsn_pairwise=CCMP")
        appendLine("ssid=" + CarAdbHotspotDefaults.SSID)
        appendLine("wpa_passphrase=" + CarAdbHotspotDefaults.PASSPHRASE)
        // hw_mode 由信道推导（a=5 GHz / g=2.4 GHz），不写死 —— 见 hwModeOfChannel。
        appendLine("hw_mode=" + CarAdbHotspotDefaults.hwModeOfChannel(CarAdbHotspotDefaults.DEFAULT_CHANNEL))
        appendLine("channel=" + CarAdbHotspotDefaults.DEFAULT_CHANNEL)
        appendLine("")
        appendLine("DBPLAY_EOF")
        appendLine("chmod " + CarAdbHotspotDefaults.REMOTE_CONF_MODE + " \"\$SOFTAP_CONF\"")
        appendLine()
        appendLine("cat > \"\$UDHCPD_CONF\" <<'DBPLAY_EOF2'")
        appendLine("interface=" + CarAdbHotspotDefaults.DEFAULT_INTERFACE)
        appendLine("DBPLAY_EOF2")
        appendLine("# udhcpd 的 start/end/interface/max_leases/opt router 由 App 侧按网段生成，")
        appendLine("# 这里在运行时用完整内容覆盖（见 buildConfWriteCommands）")
        appendLine()
        // ---- 4. 清占用者
        appendLine("# 4. 清理网卡占用者（同一张 wlan0 上不能同时有两张 AP）")
        appendLine("killall -s KILL hostapd 2>/dev/null && log \"已杀旧 hostapd\" || log \"无旧 hostapd（正常）\"")
        appendLine("killall -s KILL busybox 2>/dev/null || true")
        appendLine("sleep 1")
        appendLine()
        // ---- 5. 配地址
        appendLine("# 5. 给网卡配地址")
        appendLine("ifconfig \$IFACE \"\$HOST_ADDRESS\" up || {")
        appendLine("    ip link set \$IFACE up")
        appendLine("    ip addr add \"\$HOST_ADDRESS/" + CarAdbHotspotDefaults.PREFIX_LENGTH + "\" dev \$IFACE")
        appendLine("}")
        appendLine("log \"已配地址 \$HOST_ADDRESS\"")
        appendLine()
        // ---- 6. 路由
        appendLine("# 6. 路由规则（先删残留再加；`File exists` 等价成功）")
        appendLine("ip rule del to \"\$SUBNET\" priority 200 2>/dev/null")
        appendLine("ip rule add to \"\$SUBNET\" priority 200 2>/dev/null")
        appendLine("log \"已加 ip rule（\$SUBNET）\"")
        appendLine()
        // ---- 7. 起 hostapd
        appendLine("# 7. 起 hostapd（nohup 让它脱离 oneshot 脚本的生命周期）")
        appendLine("nohup \"\$HOSTAPD\" \"\$SOFTAP_CONF\" >/data/local/tmp/dbplay-hostapd.log 2>&1 &")
        appendLine("log \"hostapd 已拉起 pid=\$!\"")
        appendLine("sleep 3")
        appendLine()
        // ---- 8. 起 DHCP
        appendLine("# 8. 起 DHCP")
        appendLine("if [ -x \"\$BUSYBOX\" ]; then")
        appendLine("    nohup \"\$BUSYBOX\" udhcpd -f \"\$UDHCPD_CONF\" >/data/local/tmp/dbplay-udhcpd.log 2>&1 &")
        appendLine("    log \"udhcpd 已拉起 pid=\$!\"")
        appendLine("else")
        appendLine("    log \"⚠ \$BUSYBOX 不存在，跳过 DHCP\"")
        appendLine("fi")
        appendLine()
        // ---- 9. 校验
        appendLine("# 9. 收尾校验")
        appendLine("sleep 2")
        appendLine("if ifconfig \$IFACE 2>/dev/null | grep -q \"\$HOST_ADDRESS\"; then")
        appendLine("    log \"✅ 热点启动成功：\$IFACE = \$HOST_ADDRESS，广播 SSID=" +
            CarAdbHotspotDefaults.SSID + "\"")
        appendLine("else")
        appendLine("    log \"✗ 网卡地址校验失败\"")
        appendLine("    ifconfig \$IFACE >> \"\$LOG\" 2>&1")
        appendLine("fi")
        appendLine("log \"===== dbplay-hotspot boot end =====\"")
        appendLine("exit 0")
    }

    /**
     * 开机自启用的**网段配置变量**（追加在脚本头部，补上 udhcpd 完整内容）。
     *
     * 分成「正文」与「网段变量」两段，是因为网段是**运行时算出来的**
     * （`CarAdbHotspotPlanner` 会挑不冲突的段），不能写死在 [BOOT_SCRIPT] 里。
     *
     * @param hostAddress AP 自己的地址，如 `192.168.176.1`。
     * @param subnet      网段（CIDR），如 `192.168.176.0/24`。
     * @param dhcpStart   DHCP 池起始，如 `192.168.176.2`。
     * @param dhcpEnd     DHCP 池结束，如 `192.168.176.254`。
     */
    fun buildNetworkVariables(
        hostAddress: String,
        subnet: String,
        dhcpStart: String,
        dhcpEnd: String,
    ): String = buildString {
        appendLine("HOST_ADDRESS=" + hostAddress)
        appendLine("SUBNET=" + subnet)
        appendLine("DHCP_START=" + dhcpStart)
        appendLine("DHCP_END=" + dhcpEnd)
        appendLine("MAX_LEASES=" +
            (CarAdbHotspotDefaults.DHCP_HOST_END - CarAdbHotspotDefaults.DHCP_HOST_START + 1))
        appendLine("LEASE_FILE=" + CarAdbHotspotDefaults.REMOTE_LEASE_FILE)
        appendLine()
        // 完整的 udhcpd.conf 内容（在正文的第 3 步之后由这里覆盖写入）
        appendLine("write_udhcpd_conf() {")
        appendLine("    cat > \"\$UDHCPD_CONF\" <<DBPLAY_UDHCPD_EOF")
        appendLine("start \$DHCP_START")
        appendLine("end \$DHCP_END")
        appendLine("interface " + CarAdbHotspotDefaults.DEFAULT_INTERFACE)
        appendLine("max_leases \$MAX_LEASES")
        appendLine("opt router \$HOST_ADDRESS")
        appendLine("lease_file \$LEASE_FILE")
        appendLine("")
        appendLine("DBPLAY_UDHCPD_EOF")
        appendLine("    chmod " + CarAdbHotspotDefaults.REMOTE_CONF_MODE + " \"\$UDHCPD_CONF\"")
        appendLine("}")
        appendLine("write_udhcpd_conf")
        appendLine("log \"配置已写入 \$SOFTAP_CONF / \$UDHCPD_CONF\"")
    }

    /**
     * init 服务定义（装到 [CarAdbHotspotDefaults.BOOT_RC_PATH]）。
     *
     * ## 为什么是 `oneshot`
     *
     * init 若把脚本当常驻服务看着，脚本退出即被 init 判定为「服务已死」并可能
     * 重启它 —— 而我们的脚本本来就该退出（hostapd 用 `nohup` 活下来）。
     * `oneshot` 让 init 不追它。
     *
     * ## 为什么用 `on property:sys.boot_completed=1`
     *
     * 这是 AOSP 提供的稳定触发点。比 `on boot` 稳：`on boot` 时机太早，
     * 此时 `wlan0` 多半还没出现（脚本内部虽有 30s 等待，但早触发只会白等）。
     *
     * ## `disabled`
     *
     * 声明「不在 class 启动时自动起」——由下面那条 `on property` 显式 `start`。
     * 这样它也**不会**在开发者手工 `start dbplay-hotspot` 之外被误触发。
     */
    val BOOT_RC: String = buildString {
        appendLine("# DBPlay 永久热点 —— init 服务定义")
        appendLine("# 由 DBPlay App 内置安装（CarAdbHotspotBootInstaller），请勿手工编辑。")
        appendLine()
        appendLine("service dbplay-hotspot " + CarAdbHotspotDefaults.BOOT_SCRIPT_PATH)
        appendLine("    class main")
        appendLine("    user root")
        appendLine("    group root")
        appendLine("    oneshot")
        appendLine("    disabled")
        appendLine()
        appendLine("# 开机完成即拉起（脚本内部另有 30s 等待兜底网卡未就绪）")
        appendLine("on property:sys.boot_completed=1")
        appendLine("    start dbplay-hotspot")
    }

    /**
     * 生成**安装命令**（一条 root shell 里做完，避免多次走慢通道）。
     *
     * ## 为什么脚本内容走 `base64`
     *
     * 脚本里有大量引号、`$`、换行、heredoc 终止符。直接 `echo "..."` 传过去会
     * 被三层 shell（本机 adb → 车机 adbd → 车机 sh）反复解释，几乎必坏。
     * 实测本工程已有的坑：`su 0 for p in …` 得到 `for: not found`
     * （见 `CarAdbHotspotCommands.hostapdOwners` 的注释）。
     *
     * 所以：**本机 base64 编码 → 车机 `base64 -d` 解码落盘**，全程零解释。
     *
     * ## 步骤（全部在一条 shell 里，幂等）
     *
     * 1. `mount -o remount,rw /system` —— 尝试挂成可写；
     * 2. **实测写入**（`touch` 临时文件）确认真的能写 —— 见下；
     * 3. 写脚本与 rc 到 `/system`（`cat >` 直接覆盖）；
     * 4. `chmod 0755` / `0644`；
     * 5. `restorecon`（失败不阻断，permissive 下无影响）；
     * 6. `mount -o remount,ro /system` —— **无论成败都挂回只读**；
     * 7. 回读校验：`test -x` + `test -f`，打印结论。
     *
     * ## ⚠️ 为什么可写性判据是「实测 touch」而不是「解析 mount 输出」
     *
     * 2026-10-07 之前这里用的是 `mount | grep -q " /system .* rw,"`，
     * **它永远不命中**，导致自启服务从来没能安装（车友实测 step 17 次次 FAIL）。
     * 原因：那个正则要求 `/system` 与 `rw,` 之间只有空白，
     * 但真实输出里二者之间永远隔着 ` type ext4 (`：
     *
     * ```
     * /dev/block/dm-3 on /system type ext4 (rw,seclabel,relatime,data=ordered)
     *                                ↑ 中间是 " type ext4 ("，不是空格
     * ```
     *
     * 只有老式 `/proc/mounts` 格式（`… /system ext4 rw,…`）才碰巧命中。
     * 现在改成**直接往 `/system` 写一个临时文件**：能不能写，事实说话，
     * 不依赖任何 ROM 的输出格式。
     */
    fun buildInstallScript(
        scriptBase64: String,
        rcBase64: String,
        prefix: String,
    ): String = buildString {
        val sys = CarAdbHotspotDefaults.BOOT_SCRIPT_PATH
        val rc = CarAdbHotspotDefaults.BOOT_RC_PATH
        append(prefix)
        append("/system/bin/sh -c '")
        // ⚠️ 整段用一个 sh -c，内部不能再有单引号（base64 字母表里没有，安全）
        append("mount -o remount,rw /system 2>/dev/null; ")
        // 实测写入：touch 成功才算真的可写（不解析 mount 输出格式）。
        append("SYS_RW=0; ")
        append("if touch /system/.dbplay-rwtest 2>/dev/null; then ")
        append("SYS_RW=1; rm -f /system/.dbplay-rwtest; fi; ")
        append("if [ \$SYS_RW -ne 1 ]; then ")
        // 回落到"解析 mount"，给 dm-verity 之类的真只读场景一个更准的提示
        append("echo \"MOUNT_LINE: \$(mount | grep \" /system \" | head -1)\"; ")
        append("echo \"SYSTEM_NOT_RW\"; exit 3; ")
        append("fi; ")
        append("echo \"SYSTEM_RW_OK\"; ")
        append("echo ").append(scriptBase64).append(" | base64 -d > ").append(sys).append("; ")
        append("echo ").append(rcBase64).append(" | base64 -d > ").append(rc).append("; ")
        append("chmod 0755 ").append(sys).append("; ")
        append("chmod 0644 ").append(rc).append("; ")
        append("chown 0:0 ").append(sys).append(" ").append(rc).append(" 2>/dev/null; ")
        append("restorecon ").append(sys).append(" 2>/dev/null; ")
        append("restorecon ").append(rc).append(" 2>/dev/null; ")
        append("mount -o remount,ro /system 2>/dev/null; ")
        append("OK=1; ")
        append("test -x ").append(sys).append(" || OK=0; ")
        append("test -f ").append(rc).append(" || OK=0; ")
        append("if [ \$OK -eq 1 ]; then echo \"INSTALL_OK\"; ")
        append("else echo \"INSTALL_FAIL\"; fi'")
    }

    /**
     * 生成**卸载命令**（只删本方案写入的两个文件 + 清私有产物）。
     *
     * ⚠️ 不碰 `/data/local/tmp/busybox`：那是 App 自身功能的依赖，不属本方案。
     */
    fun buildUninstallScript(prefix: String): String = buildString {
        val sys = CarAdbHotspotDefaults.BOOT_SCRIPT_PATH
        val rc = CarAdbHotspotDefaults.BOOT_RC_PATH
        append(prefix)
        append("/system/bin/sh -c '")
        append("mount -o remount,rw /system 2>/dev/null; ")
        append("rm -f ").append(sys).append(" ").append(rc).append("; ")
        append("rm -f ").append(CarAdbHotspotDefaults.BOOT_SOFTAP_CONF).append("; ")
        append("rm -f ").append(CarAdbHotspotDefaults.BOOT_UDHCPD_CONF).append("; ")
        append("rm -f ").append(CarAdbHotspotDefaults.BOOT_LOG_PATH).append("; ")
        append("rm -f /data/local/tmp/dbplay-hostapd.log /data/local/tmp/dbplay-udhcpd.log; ")
        append("mount -o remount,ro /system 2>/dev/null; ")
        append("if [ -e ").append(sys).append(" ] || [ -e ").append(rc).append(" ]; then ")
        append("echo \"UNINSTALL_FAIL\"; else echo \"UNINSTALL_OK\"; fi'")
    }

    /**
     * 生成**探测命令**：只读，判断这台车能不能装（不改任何文件）。
     *
     * 用于「先探测再决定」的保守路径：装上之前先跑这条，看 `/system` 能不能
     * 挂成 rw。挂不成说明有 dm-verity 之类的保护，那就**整步跳过**，不硬来。
     */
    fun buildProbeScript(prefix: String): String = buildString {
        append(prefix)
        append("/system/bin/sh -c '")
        append("RO_MOUNT=\$(mount | grep \" /system \" | head -1); ")
        append("echo \"MOUNT: \$RO_MOUNT\"; ")
        // 与 install 同源：实测写入，不用 mount 输出格式判断（见 buildInstallScript 说明）
        append("mount -o remount,rw /system 2>/dev/null; ")
        append("if touch /system/.dbplay-rwtest 2>/dev/null; then ")
        append("  echo \"PROBE_RW_OK\"; ")
        append("  rm -f /system/.dbplay-rwtest; ")
        append("  mount -o remount,ro /system 2>/dev/null; ")
        append("else echo \"PROBE_RW_FAIL\"; fi; ")
        append("HOSTAPD=\"\"; ")
        append("for p in ")
        append(CarAdbHotspotDefaults.HOSTAPD_PATH_CANDIDATES.joinToString(" "))
        append("; do [ -x \"\$p\" ] && HOSTAPD=\"\$p\" && break; done; ")
        append("echo \"HOSTAPD: \$HOSTAPD\"; ")
        append("echo \"BOOT_PROP: \$(getprop ")
        append(CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY).append(")\"; ")
        append("echo \"SELINUX: \$(getenforce 2>/dev/null)\"'")
    }
}
