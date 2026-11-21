package com.dbtool.carplay.api.hotspot

import com.dbtool.carplay.api.settings.CarSessionSettings

/**
 * 「复用车机热点」(`CarHotspotMode.MANUAL`) 下，**用户手填的 SSID 能不能真的用**。
 *
 * ## 为什么需要这条判据（2026-10-06 测试发现）
 *
 * 这条链此前的行为是：用户在设置页手填一个名字 → 原样发给 iPhone →
 * **无论那个名字对不对，链路上都没有任何反馈**。
 *
 * 而填错名字时是**必然失败**的，且失败得完全静默。原因在上游
 * `ManualHotspotManager.start()`：
 *
 * ```kotlin
 * val apConfiguration = readApConfiguration()?.takeIf { it.ssid == expectedSsid }
 * validateApConfiguration(apConfiguration)
 * ```
 *
 * 那个 `takeIf` 造成两种截然相反的结果：
 *
 * | 用户填的名字 | 后果 |
 * |---|---|
 * | 与车机热点**一致** | 会去校验信道/频段/加密，任一不符直接抛 `IOException` |
 * | 与车机热点**不一致** | `apConfiguration = null` ⇒ **校验全跳过**，只靠"找到一张像 AP 的网卡"就返回成功 |
 *
 * 也就是说：**填错时反而"不报错"**。上游会拿用户填的那个名字去 `0x5703` 通告，
 * 而那张网**根本没人广播** ⇒ iPhone 找不到网络 ⇒ iAP2 无线隧道起不来
 * ⇒ 界面永远停在「无线通道就绪」，全程无一句报错。
 * （与 `ADB-HOTSPOT.md` §3.1.2 记的「广播名 vs 配置名」是同一个坑的两种入口。）
 *
 * ## 为什么不做自动填充
 *
 * 用户明确要求「改成用户手动输入即可」——不从探测结果里替用户写值。
 * 但**必须把填错的后果当场说出来**，否则用户只能靠"连不上"去倒推。
 * 所以本判据只做一件事：**比对 + 给一句人话**，不改任何输入。
 *
 * ## 判不准时一律不报警
 *
 * 探测不到车机热点（`detected = false`）、或读不到名字时，**返回 null 不告警**。
 * 理由与 [CarNativeHotspotInfo.ssidMismatch] 一致：**凭缺失下结论会把无害情况
 * 判成致命错误**（本仓反复踩过）。车机热点可能只是还没开，用户过一会儿就开。
 *
 * 本对象是纯函数（只吃两个字符串），所以可以脱离 Android 环境单测。
 */
object CarManualHotspotMatch {

    /**
     * 比对结果。
     *
     * @param expected 用户手填的 SSID（设置页「热点名称 (SSID)」）
     * @param actual 探测到的车机热点名（**存储配置名**，即上游会去校验的那个）
     */
    data class Result(
        /** 用户填的名字与车机热点名一致 ⇒ 可以放心用。 */
        val matches: Boolean,
        /**
         * 不一致时的一句话，直接进设置页红字；一致或无法判定时为 null。
         *
         * 写成"你填的是哪个 / 车机实际是哪个"两个值并列，而不是只说"不一致"
         * ——用户要据此**照着改**，所以必须把正确值给出来。
         */
        val note: String?,
    )

    /**
     * @param typedSsid 用户在手填框里写的名字（可为空 = 还没填）
     * @param nativeInfo 探测结果；null = 没探测过
     */
    fun evaluate(
        typedSsid: String,
        nativeInfo: CarNativeHotspotInfo?,
    ): Result = evaluateStored(
        typedSsid = typedSsid,
        // 没探测到 / 没读到名字 ⇒ 判不了，不告警。
        // ⚠️ 这一步**不能省**：车机热点完全可能还没开，此时任何"不匹配"
        //    都是假警报，会把用户赶去改一个本来正确的名字。
        storedSsid = nativeInfo?.takeIf { it.detected }?.ssid,
    )

    /**
     * 与 [evaluate] 同一条判据，只是输入换成**已经读到的存储配置名**。
     *
     * 连接前的预检（`CarPlaySessionBridge.ensureManualHotspotReady`）手里本来就有一份
     * 进程内反射读到的车机热点配置，不需要、也不该为了比对再去拿一个 [CarNativeHotspotInfo]。
     * `storedSsid` 为 null / 空白 ⇒ 判不了，不告警（与 [evaluate] 一致）。
     */
    fun evaluateStored(
        typedSsid: String,
        storedSsid: String?,
    ): Result {
        val actual = storedSsid?.takeIf { it.isNotBlank() }
            ?: return Result(matches = true, note = null)

        // 用户还没填 ⇒ 上游会用它的默认值（不是车机热点名），同样是必然失败，
        // 但这条由 SSID 输入框自己的 placeholder/空值校验负责，不在这里重复。
        val typed = typedSsid.trim()
        if (typed.isEmpty()) return Result(matches = true, note = null)

        if (typed == actual) return Result(matches = true, note = null)

        // 只差空格/大小写时点破一句：这类差异肉眼最难发现（`Lynk&Co` ↔ `Lynk & Co`）。
        val spacingHint = if (differsOnlyInSpacingOrCase(typed, actual)) {
            "（两者只差空格或大小写，Wi-Fi 名称对这两点都敏感）"
        } else {
            ""
        }
        return Result(
            matches = false,
            note = "⚠️ 你填的热点名「$typed」与车机实际的热点名「$actual」不一致$spacingHint —— " +
                "发给 iPhone 的会是「$typed」，而那张网没有被广播，iPhone 必然连不上。" +
                "请把这里改成「$actual」，或在车机设置里把热点名字改成「$typed」。",
        )
    }

    /**
     * 连接那一刻对"通告给 iPhone 的热点名"的处置（复用专属，见
     * `CarPlaySessionBridge.reconcileReusedSsid`）。
     *
     * ## 为什么连接时也要核对（日志 4，2026-10-12，CS11）
     *
     * 设置页那条警告只在 `CarNativeHotspotProbe` 缓存有效期内（2 分钟）才有数据，而
     * `buildSession` 又**故意跳过**原生热点探测 ⇒ 连接那一刻没有任何人核对名字。
     * 实测填的是 `Lynk&Co`、车机真名 `Lynk & Co`：0x5703 通告了不存在的网络，
     * 两次 40 秒都没有 iPhone 回连 AirPlay，界面停在「无线通道就绪」，日志里一句原因都没有。
     * 同一台车 9/30 用真名 `Lynk & Co` 时 StartSession 后约 0.2 秒 iPhone 就回连了。
     *
     * ## 处置分三档（宁可放过，不可错杀）
     *
     * - [Reconcile.Keep]：一致，或判不了（没读到车机热点名 / 用户没填）⇒ 照用户填的发。
     * - [Reconcile.UseStored]：只差空白或大小写 ⇒ **本次**改用车机真名。这一档不会认错：
     *   两个名字抹掉空白、忽略大小写后相同，不可能是另一张网。设置里的值不动。
     * - [Reconcile.Mismatch]：实质不同 ⇒ 只告警，**不改不拦**。用户手填为准（2026-10-06 口径：
     *   "改成用户手动输入即可"），且存储配置名未必等于实际广播名，读到的名字可能是残留。
     */
    sealed interface Reconcile {
        /** 一致，或判不了 ⇒ 不动。 */
        data object Keep : Reconcile

        /** 只差空白/大小写 ⇒ 本次用 [ssid]（车机存储配置里的真名）；[note] 给日志与界面日志行。 */
        data class UseStored(val ssid: String, val note: String) : Reconcile

        /** 实质不同 ⇒ 只告警。 */
        data class Mismatch(val note: String) : Reconcile
    }

    /**
     * @param typedSsid 用户手填的热点名（可为空）
     * @param storedSsid 进程内反射读到的车机热点**存储配置名**；null / 空白 = 没读到
     */
    fun reconcile(typedSsid: String, storedSsid: String?): Reconcile {
        val actual = storedSsid?.takeIf { it.isNotBlank() } ?: return Reconcile.Keep
        val typed = typedSsid.trim()
        if (typed.isEmpty() || typed == actual) return Reconcile.Keep
        // 出厂默认名是 App 自己的占位值（自建热点的名字），不是用户对车机热点的选择。
        // 车机存储名已知且不同 ⇒ 它不可能是车机热点，通告出去 iPhone 必然连不上
        // （日志 5：复用模式下 SSID 仍是 DBPlay）。本次改用车机真名，设置不改。
        if (typed == CarSessionSettings.DEFAULT_HOTSPOT_SSID) {
            return Reconcile.UseStored(
                ssid = actual,
                note = "热点名已按车机校正：「$typed」是 DBPlay 自建热点的出厂名，不是车机热点；" +
                    "本次按车机热点真名「$actual」通告给 iPhone，设置里的值没有改动，" +
                    "建议改成「$actual」。",
            )
        }
        if (differsOnlyInSpacingOrCase(typed, actual)) {
            return Reconcile.UseStored(
                ssid = actual,
                note = "热点名已按车机校正：你填的「$typed」与车机热点真名「$actual」只差空格或大小写，" +
                    "iPhone 找不到前者；本次按「$actual」通告给 iPhone，设置里的值没有改动，" +
                    "建议也改成「$actual」。",
            )
        }
        return Reconcile.Mismatch(evaluateStored(typedSsid, storedSsid).note.orEmpty())
    }

    /**
     * 连接那一刻对"通告给 iPhone 的热点密码"的处置（复用专属，2026-10-13）。
     *
     * 返回非 null = 本次改用这个密码（车机热点存储配置里的真实密码）；null = 照用户填的发。
     *
     * ## 只在两种"占位"情形下替换
     *
     * - 用户没填（空串）：车机热点既然有密码，空串必然错；
     * - 用户填的是 DBPlay 自建热点的出厂密码 [CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE]：
     *   那是 App 自己的占位值，不是用户对车机热点的选择（日志 5/6 通告的就是它，iPhone 关联不上）。
     *
     * 用户**自己填了别的密码**时一律不动（与热点名 [Reconcile.Mismatch] 同口径：手填为准）。
     * 读不到密码 / 长度不在 WPA 的 8~63 位 / 与填的一致，都返回 null。
     *
     * 设置里的值不改；调用方只在本次会话的方案里替换，且**不得把密码写进日志**。
     */
    fun reconcilePassphrase(typedPassphrase: String, storedPassphrase: String?): String? {
        val stored = storedPassphrase?.takeIf { it.length in 8..63 } ?: return null
        if (typedPassphrase == stored) return null
        val placeholder = typedPassphrase.isEmpty() ||
            typedPassphrase == CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE
        return stored.takeIf { placeholder }
    }

    /**
     * 把"车机热点密码读到了什么"写成一句**可判定**的话（PATCH-0116，2026-10-13）。
     *
     * ## 为什么需要它（日志 9 卡住的那一步）
     *
     * 复用模式下那句告警 —— `passphrase is DBPlay factory default (reuse mode)` ——
     * 有 **三种**成因，而原先它们在日志里长得**一模一样**：
     *
     * | 成因 | 本次通告的密码 | 下一步该查什么 |
     * | --- | --- | --- |
     * | 车机没给出明文（ROM 隐藏 / WPA3-SAE） | **错的** | 解决"读车机密码" |
     * | 读到的长度不在 8~63 位 | **错的** | 同上 |
     * | 读到了、而且**就等于**出厂默认值 | **对的** | 与密码无关 ⇒ 查关联与网络路径 |
     *
     * 2026-10-13 的复用失败正卡在这三种之间：分不清是"读不到密码"还是
     * "车机的密码本来就是它"，也就无法决定下一步是修密码读取、还是查关联与路由。
     *
     * ## 纪律
     *
     * ⚠️ **密码本身绝不入日志**（同 [reconcilePassphrase]）：这里只输出
     * "读到了没 / 长度 / 是否等于出厂默认值"。最后一项不泄露任何秘密
     * （出厂值是本仓库的公开常量），却是决定性的一条。
     */
    fun describeCarPassphraseRead(storedPassphrase: String?): String = when {
        storedPassphrase.isNullOrEmpty() ->
            "unreadable（ROM 未给出明文）⇒ 本次通告的是出厂默认值"

        storedPassphrase.length !in 8..63 ->
            "unusable（长度 ${storedPassphrase.length} 不在 WPA 的 8~63 位）⇒ 本次通告的是出厂默认值"

        storedPassphrase == CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE ->
            "read length=${storedPassphrase.length} == factoryDefault" +
                " ⇒ 本次通告的值与车机一致（若仍连不上，与密码无关）"

        // 理论上不可达（读到、长度合法、也不等于出厂值 ⇒ reconcilePassphrase 必替换）。
        // 留着是为了将来判据变了，不至于又退化成"看不出来是哪一种"。
        else ->
            "read length=${storedPassphrase.length} != factoryDefault，但本次**没有**替换 ⇒ 判据需复核"
    }

    /**
     * 两个名字是否**只差空白或大小写**（`Lynk&Co` ↔ `Lynk & Co`）。
     *
     * 这是最容易手滑、也最难肉眼看出来的一类：日志 4（2026-10-12，CS11）里设置填的是
     * `Lynk&Co`，车机热点真名是 `Lynk & Co`，iPhone 被告知去连一张不存在的网 ⇒
     * 一直停在「无线通道就绪」。只用来让提示文案能点破"差在哪"，不参与放行/拦截判断。
     */
    fun differsOnlyInSpacingOrCase(typedSsid: String, actualSsid: String): Boolean {
        fun squash(value: String) = value.filterNot { it.isWhitespace() }.lowercase()
        val typed = typedSsid.trim()
        return typed != actualSsid && squash(typed) == squash(actualSsid)
    }

    /**
     * 一句话总结当前 SSID 是否与车机一致（给诊断日志用，不需要两个字段）。
     */
    fun debugLine(typedSsid: String, nativeInfo: CarNativeHotspotInfo?): String {
        val result = evaluate(typedSsid, nativeInfo)
        val actual = nativeInfo?.ssid?.takeIf { it.isNotBlank() }
        return "manualHotspotMatch: matches=${result.matches}" +
            " typed=${typedSsid.ifBlank { "(空)" }}" +
            " car=${actual ?: "(未探测到)"}"
    }
}
