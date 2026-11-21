package com.dbtool.carplay.geely.lynk

/**
 * DBPlay **iAP2 端点字典 + CSM 帧格式**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `iap2/catalog/Iap2Endpoints.java`(64KB)、
 * `iap2/wire/Iap2CsmFramer.java`(14KB)、`iap2/wire/Iap2WireType.java`、
 * `iap2/catalog/Iap2Direction.java` 提取。
 *
 * ## 这是「iPhone 到底发什么」的权威字典
 *
 * iAP2 通信由**端点（Endpoint）**构成，每个端点是「一方要求另一方订阅/上报」的消息类型。
 * 按 ID 就能在日志里认出 iPhone 或车机在做什么。
 *
 * ## CSM 帧格式（iAP2 链路层之上的控制消息层）
 *
 * ```
 * 帧头     0x4040（CSM frame must start with 0x4040）
 * 帧长     6..65535
 * 消息体   ≤ 65529
 * 参数体   ≤ 65531
 * link 分片 1..65525
 * ```
 *
 * ## 线类型（Iap2WireType）
 *
 * `I8=1 I16=... U16=2 I16=3 U32=4 I32=5 U64=6 U16_LIST=7 BYTES=8
 *  STRING=9 STRING_LIST=10 GROUP=11 OPAQUE=12`
 *
 * ⚠️ 未实机验证。ID 值来自 DiPlay 的端点表，与苹果 iAP2 规范一致。
 */
object LynkIap2Endpoints {

    /** 通信方向。 */
    enum class Direction { ACCESSORY_TO_IPHONE, IPHONE_TO_ACCESSORY }

    /** 端点定义。 */
    data class Endpoint(
        val id: Int,
        val name: String,
        val group: String,
        val direction: Direction,
    )

    // ---- 关键端点在下方命名，全表见 ALL ----

    /** CarPlay 协商起点：iPhone 告知「CarPlay 可用」。 */
    const val CARPLAY_AVAILABILITY = 17152
    /** CarPlay 协商起点：车机发起会话。 */
    const val CARPLAY_START_SESSION = 17153
    /** iPhone 通知无线 CarPlay 状态变化（掉线时 iPhone 会发这条）。 */
    const val WIRELESS_CARPLAY_UPDATE = 19981
    /** 供电更新（iPhone 报电流）。 */
    const val POWER_UPDATE = 44545
    /** 配件电流维持。 */
    const val ACCESSORY_POWER_UPDATE = 44549

    /** 全部 53 个端点（ID → 定义）。 */
    val ALL: List<Endpoint> = listOf(
        // ---- Identification ----
        Endpoint(7429, "CancelIdentification", "Identification", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(7430, "IdentificationInformationUpdate", "Identification", Direction.ACCESSORY_TO_IPHONE),

        // ---- AccAuthentication（配件认证：MFI 芯片参与）----
        Endpoint(43520, "RequestAuthenticationCertificate", "AccAuthentication", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(43521, "AuthenticationCertificate", "AccAuthentication", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(43522, "RequestAuthenticationChallengeResponse", "AccAuthentication", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(43523, "AuthenticationResponse", "AccAuthentication", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(43524, "AuthenticationFailed", "AccAuthentication", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(43525, "AuthenticationSucceeded", "AccAuthentication", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(43526, "AuthenticationCertificateSerial", "AccAuthentication", Direction.ACCESSORY_TO_IPHONE),

        // ---- DeviceAuthentication ----
        Endpoint(43536, "RequestDeviceCertificate", "DeviceAuthentication", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(43537, "AuthenticationCertificate", "DeviceAuthentication", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(43538, "RequestDeviceChallengeResponse", "DeviceAuthentication", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(43539, "AuthenticationResponse", "DeviceAuthentication", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(43540, "DeviceAuthenticationFailed", "DeviceAuthentication", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(43541, "DeviceAuthenticationSucceeded", "DeviceAuthentication", Direction.ACCESSORY_TO_IPHONE),

        // ---- CarPlay 建链 ----
        Endpoint(CARPLAY_AVAILABILITY, "CarPlayAvailability", "CarPlayConnectionRequest", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(CARPLAY_START_SESSION, "CarPlayStartSession", "CarPlayConnectionRequest", Direction.ACCESSORY_TO_IPHONE),

        // ---- 数字车钥匙 ----
        Endpoint(17154, "AvailableDigitalCarKeys", "DigitalCarKey_Matching", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(17155, "MatchedDigitalCarKeys", "DigitalCarKey_Matching", Direction.IPHONE_TO_ACCESSORY),

        // ---- WiFi 共享 ----
        Endpoint(22272, "RequestWiFiInformation", "WiFiSharing", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(22273, "WiFiInformation", "WiFiSharing", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(22274, "RequestAccessoryWiFiConfigurationInformation", "WiFiSharing", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(22275, "AccessoryWiFiConfigurationInformation", "WiFiSharing", Direction.ACCESSORY_TO_IPHONE),

        // ---- 供电 ----
        Endpoint(44544, "StartPowerUpdates", "Power", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(POWER_UPDATE, "PowerUpdate", "Power", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(44546, "StopPowerUpdates", "Power", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(44547, "PowerSourceUpdate", "Power", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(ACCESSORY_POWER_UPDATE, "AccessoryPowerUpdate", "Power", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(44550, "AccessoryResetBaseCurrent", "Power", Direction.ACCESSORY_TO_IPHONE),

        // ---- 导航 ----
        Endpoint(20992, "StartRouteGuidanceUpdates", "Navigation", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(20995, "StopRouteGuidanceUpdates", "Navigation", Direction.ACCESSORY_TO_IPHONE),

        // ---- 正在播放 ----
        Endpoint(20481, "StartNowPlayingUpdates", "Now Playing", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(20482, "StopNowPlayingUpdates", "Now Playing", Direction.ACCESSORY_TO_IPHONE),

        // ---- 通信 ----
        Endpoint(16724, "startCallStateUpdatesHandler", "Communications", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(16726, "stopCallStateUpdatesHandler", "Communications", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(16727, "startCommunicationsUpdatesHandler", "Communications", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(16729, "stopCommunicationsUpdatesHandler", "Communications", Direction.ACCESSORY_TO_IPHONE),

        // ---- 设备通知（无线状态在这）----
        Endpoint(19977, "DeviceInformationUpdate", "DeviceNotifications", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(19978, "DeviceLanguageUpdate", "DeviceNotifications", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(19979, "DeviceTimeUpdate", "DeviceNotifications", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(19980, "DeviceUuidUpdate", "DeviceNotifications", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(WIRELESS_CARPLAY_UPDATE, "WirelessCarPlayUpdate", "DeviceNotifications", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(19982, "DeviceTransportIdentifierNotification", "DeviceNotifications", Direction.IPHONE_TO_ACCESSORY),

        // ---- 外部配件 ----
        Endpoint(59904, "externalAccessory_openEASession", "ExternalAccessory", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(59905, "externalAccessory_closeEASession", "ExternalAccessory", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(59907, "externalaccessory_statusEASessionHandler", "ExternalAccessory", Direction.ACCESSORY_TO_IPHONE),

        // ---- 测试/心跳 ----
        Endpoint(0, "pingHandler", "Test", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(1, "userNotificationHandler", "Test", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(4, "sendPingAccHandler", "Test", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(5, "pingAccHandler", "Test", Direction.ACCESSORY_TO_IPHONE),

        // ---- 数字音频 ----
        Endpoint(55808, "digitalAudio_start", "DigitalAudio", Direction.ACCESSORY_TO_IPHONE),
        Endpoint(55809, "DigitalAudioInformationUpdate", "DigitalAudio", Direction.IPHONE_TO_ACCESSORY),
        Endpoint(55810, "digitalAudio_stop", "DigitalAudio", Direction.ACCESSORY_TO_IPHONE),
    )

    /** 按 ID 查端点。 */
    fun byId(id: Int): Endpoint? = ALL.firstOrNull { it.id == id }

    /** 按组查端点。 */
    fun byGroup(group: String): List<Endpoint> = ALL.filter { it.group.equals(group, true) }

    /** 日志用：把 ID 翻译成可读名。 */
    fun describe(id: Int): String = byId(id)?.let { "${it.name}(0x${id.toString(16)})" } ?: "0x${id.toString(16)}"

    // ---- CSM 帧限制（DiPlay 原文）----
    /** CSM 帧头魔数。 */
    const val CSM_FRAME_MAGIC = 0x4040
    /** CSM 帧最小/最大长度。 */
    const val CSM_FRAME_MIN = 6
    const val CSM_FRAME_MAX = 65535
    /** 消息体上限（字节）。 */
    const val CSM_MESSAGE_BODY_MAX = 65529
    /** 单参数体上限（字节）。 */
    const val CSM_PARAMETER_MAX = 65531
    /** link 层分片大小范围。 */
    const val LINK_CHUNK_MIN = 1
    const val LINK_CHUNK_MAX = 65525

    /** 校验 CSM 帧头。 */
    fun isCsmFrame(prefix: Int): Boolean = prefix == CSM_FRAME_MAGIC
}
