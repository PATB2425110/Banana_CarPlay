package com.dbtool.carplay.api.hotspot

/**
 * 网卡上有没有「**我们自己自建热点留下的残留**」。
 *
 * ## 为什么需要这条判据（2026-10-09 实测）
 *
 * 「复用车机热点」与「自建热点」共用同一张物理网卡（本车 `wlan0`）。
 * 自建时 DBPlay 会 `ifconfig wlan0 <网关地址>` 上去（实车 `192.168.175.1`，
 * 且**网段每次可能不同**）；切回复用时，这个地址**不会自动消失**。
 *
 * 而「我们现在是不是有一张热点在跑」的判据（上游与 `CarPlaySessionBridge`
 * 各有一份）都是**按我们自己 planner 算出来的网关地址**在网卡上找地址：
 * 找到 ⇒ 认为"我们的热点在跑"。
 *
 * ⇒ 于是出现这个必错组合：
 *
 * ```
 * ① 用户先用「自建热点」 → wlan0 = 192.168.176.1（我们配的）
 * ② 用户切到「复用车机热点」
 * ③ 残留的 192.168.176.1 还在 → 判据命中 → 复用分支以为"车机热点就是这个网卡"
 * ④ 把 SSID + 192.168.176.1 通告给 iPhone
 * ⑤ 而 192.168.176.x 是**我们上次自建的网段**，车机热点的网是 192.168.175.x
 *    ⇒ iPhone 关联上了 SSID（那是车机广播的），却按 192.168.176.1 找 7000 端口
 *    ⇒ 到不了 ⇒ 40 秒 attach timed out ⇒ wireless bring-up failed
 * ```
 *
 * 真车日志（设备 `e3de67df72a0`，2026-10-09 09:33）：
 * ```
 * hotspot plan: 复用车机热点「Lynk&Co」（手动指定）
 * wireless hotspot backend=Manual hotspot iface=wlan0 host=192.168.176.1 …
 * wireless attach timed out: iPhone did not open the AirPlay connection within 40000ms
 * ```
 *
 * ## 本判据只做一件事：把"这是别人留的地址"这件事**说出来**
 *
 * 它**不**替调用方决定要不要清理 —— 清理是实体操作（`ip addr del`），
 * 属于自建模块的权限；判据只负责让上层能**在切换模式时先处理掉**。
 *
 * ## 为什么是纯函数
 *
 * 输入只有三个：网卡名、该网卡上的地址、我们 planner 算出的期望网关。
 * 判错一次就是 40 秒超时（上面那条链），所以必须能被单测钉死；
 * 而本模块（`:car:api`）没有 Robolectric，要 `Context` 的东西测不了。
 *
 * ## ⚠️ 判据刻意写得"窄"：只认**我们自己规划的那个地址**
 *
 * 网卡上出现**别的**地址（车机自己配的、厂商预留的）一律**不算残留**。
 * 理由与 [CarHotspotEnabledState.UNKNOWN] 一致：**凭缺失/宽泛下结论
 * 会把无害情况判成需要清理**，而"误清车机的地址"比"多等一次超时"糟糕得多。
 * 本车 planner 的网段是**冲突规避**选出来的（见 `CarHotspotSubnet`），
 * 车机原生热点不会用同一个网关地址 —— 这条前提是本判据成立的基础。
 *
 * ## 🔴 2026-10-11：`plannedGateway` 必须传「**我们实际配上去的那个地址**」
 *
 * 真车日志（设备 `67b3c24af6b5`）证明：调用方若拿 `CarAdbHotspotPlanner.plan(...)`
 * **这一刻重算**出来的地址当 `plannedGateway`，本判据就永远在找一个"已经不在了"的地址
 * —— planner 是冲突规避的，上一轮占了 176，这一轮它就改选 175：
 *
 * ```
 * 08:52 自建 → wlan0 = 192.168.175.1
 * 08:55 自建 → 175 被上一轮占着，改选 → wlan0 = 192.168.176.1
 * 08:57 切复用 → 判据拿到"现算的 175" → ["原本无残留"]（网上躺着的是 176）
 *               → 复用读到 192.168.176.1 通告给 iPhone → 40 秒 attach timed out
 * ```
 *
 * ⇒ 正确的 `plannedGateway` 是 **`CarAdbHotspotState.hostAddress`**：`CarAdbHotspotManager`
 * 实际 `ifconfig` 上去的那个地址（运行时选出来的，不是常量）。拿不到（进程重启后没记录）
 * 时**宁可返回 [Result.NONE]** —— 与"宁可放过不可错杀"同一条纪律。
 *
 * ⚠️ 2026-10-11（PATCH-0086）起，**按我方候选网段判也可以了**（见 [detectWithinSubnets]）：
 * 原来不能这么判，是因为车机网段未知、池里的 `192.168.175.0/24` 有可能正是车机自己
 * （见上面 2026-10-09 那条链）；现在车主给了固定网段 `192.168.15.0/24`，与池不相交
 * ⇒ 按池判不会误伤车机，而且能在"足迹丢失"时照样认出残留。两条判据并列：
 * [detect]（足迹地址，最准）与 [detectWithinSubnets]（池，兜底）。
 */
object CarHotspotResidue {

    /**
     * 残留判定的结论。
     *
     * @param leftoverAddress 判为残留的那个地址（没有则为 null）
     * @param interfaceName 承载它的网卡（没有则为 null）
     */
    data class Result(
        /** true = 网卡上确实存在「我们规划过、但当前不该在」的网关地址。 */
        val detected: Boolean,
        val leftoverAddress: String? = null,
        val interfaceName: String? = null,
        /**
         * 一句话，可直接进诊断日志 / 界面。未检出时为 null。
         *
         * 写成"哪个网卡上躺着哪个地址、它为什么会坏事"，因为读这句话的人
         * 正要据此决定"要不要先清理再复用"。
         */
        val note: String? = null,
    ) {
        companion object {
            val NONE = Result(detected = false)
        }
    }

    /**
     * @param interfaceName 目标网卡名（如 `wlan0`）。null/空 = 没读到网卡 ⇒ 判不了。
     * @param addressesOnInterface 该网卡上**当前**的全部 IPv4 字符串。
     * @param plannedGateway 我们 planner 算出的网关地址（即自建时会配上去的那个）。
     *        传 null/空 = 没有规划值可比 ⇒ 判不了。
     */
    fun detect(
        interfaceName: String?,
        addressesOnInterface: List<String>,
        plannedGateway: String?,
    ): Result {
        val iface = interfaceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Result.NONE
        val planned = plannedGateway?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Result.NONE

        // ⚠️ 只在**地址完全相等**时算命中，不做网段匹配。
        //
        // 网段匹配（`192.168.175.0/24` 里任一地址都算）会把"车机自己正好
        // 用了同段"的情况误判成我们的残留 ⇒ 去清理一张不是我们建的网。
        // 我们配的地址是**确定的一个值**，就按这一个值找。
        val hit = addressesOnInterface
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it == planned }
            ?: return Result.NONE

        return Result(
            detected = true,
            leftoverAddress = hit,
            interfaceName = iface,
            note = "检测到「自建热点」留下的网关地址 $hit 仍在网卡 $iface 上。" +
                "复用模式下它会让 iPhone 拿到一个连不上的地址" +
                "（车机热点不在这个网段），切换模式时应先清理。",
        )
    }

    /**
     * 诊断用的一行（不需要两个字段的调用方）。
     */
    /**
     * 按「**我方候选网段**」判残留（2026-10-11，车主口径给出车机固定网段之后）。
     *
     * ## 什么时候用它
     *
     * 1. **没有落盘足迹时**（进程被杀过）—— 那时"我们实际配过的地址"无从得知，
     *    只能用网段判；
     * 2. 作为 [detect] 的兜底。
     *
     * ## 为什么现在按网段判是安全的
     *
     * 车主明确（2026-10-11）：**车机自带热点固定 `192.168.15.0/24`（网关 `.1`）、固定网口
     * `wlan0`、随车自启**；而我们的候选池是 `192.168.175~179` / `172.20` / `10.42`
     * （[CarAdbHotspotDefaults.SUBNET_CANDIDATES]）—— **两者不相交**。
     * ⇒ 落在我们池子里的一定是自建残留，不会误判车机自己的地址。
     *
     * ⚠️ 2026-10-11 早先版本曾用"现算的 planner 地址"，也曾在不确定车机网段时
     * 刻意**不**按网段判（怕误伤）。现在有了车机固定网段这条事实，按池判是**更强**的判据：
     * 落盘足迹丢失后它照样有效。
     */
    fun detectWithinSubnets(
        interfaceName: String?,
        addressesOnInterface: List<String>,
        selfSubnets: List<String> = CarAdbHotspotDefaults.SUBNET_CANDIDATES,
    ): Result {
        val iface = interfaceName?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.NONE
        if (selfSubnets.isEmpty()) return Result.NONE
        val hit = addressesOnInterface
            .asSequence()
            .map { it.trim() }
            .firstOrNull { address ->
                selfSubnets.any { CarHotspotApInterface.inSubnet(address, it) }
            } ?: return Result.NONE
        return Result(
            detected = true,
            leftoverAddress = hit,
            interfaceName = iface,
            note = "网卡 $iface 上还躺着我方候选网段里的地址 $hit —— 那是「自建热点」的残留" +
                "（本车车机热点固定在 192.168.15.0/24，与我们的池不相交）。" +
                "复用模式下它会把 iPhone 引到一个连不上的地址（真车 08:57 就是 192.168.176.1）。",
        )
    }
    /**
     * 诊断用的一行（不需要两个字段的调用方）。
     */
    fun debugLine(
        interfaceName: String?,
        addressesOnInterface: List<String>,
        plannedGateway: String?,
    ): String {
        val result = detect(interfaceName, addressesOnInterface, plannedGateway)
        return "hotspotResidue: detected=${result.detected}" +
            " iface=${interfaceName ?: "(无)"}" +
            " planned=${plannedGateway ?: "(无)"}" +
            " addr=${result.leftoverAddress ?: "(无)"}"
    }
}
