package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.IOException
import kotlin.math.min

private fun ByteArray.toHex(): String = joinToString(separator = " ") { "%02x".format(it.toInt() and 0xff) }

/** The wireless transport identity advertised on the Bluetooth identification session. */
class Iap2WirelessIdentification(
    val bluetoothMac: String,
    val ssid: String,
) {
    private val macBytes: ByteArray

    init {
        require(MAC_ADDRESS.matches(bluetoothMac)) {
            "bluetoothMac must be six colon-separated hexadecimal bytes"
        }
        require(ssid.isNotBlank()) { "Wireless SSID must not be blank" }
        require('\u0000' !in ssid) { "Wireless SSID must not contain U+0000" }
        macBytes = ByteArray(6) { index ->
            bluetoothMac.substring(index * 3, index * 3 + 2).toInt(16).toByte()
        }
    }

    internal fun bluetoothMacBytes(): ByteArray = macBytes.copyOf()

    private companion object {
        private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/** Required identity for the minimal wired or wireless iAP2 identification exchange. */
data class Iap2IdentificationConfig(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
    /** The iPhone USB interface used by CarPlay, supplied explicitly by the deployment. */
    val carPlayUsbInterfaceNumber: Int,
    val language: String = "en",
    /** This app's own EA protocol identifier; it deliberately does not claim a CarPlay EA flag. */
    val externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    /** Non-null selects the wireless Bluetooth and WirelessCarPlay transport components. */
    val wireless: Iap2WirelessIdentification? = null,
    /** Advertises and enables iAP2 LocationInformation from the accessory to the phone. */
    val locationInformationEnabled: Boolean = false,
    /** USB-IF identity used by StartHID; defaults match this app's existing AirPlay HID identity. */
    val hidVendorIdentifier: Int = 2,
    val hidProductIdentifier: Int = 1,
    /** Enabled only when the deployment supplies the implemented read-only vehicle source. */
    val vehicleStatusUpdatesEnabled: Boolean = false,
) {
    constructor(
        name: String,
        modelIdentifier: String,
        manufacturer: String,
        serialNumber: String,
        firmwareVersion: String,
        hardwareVersion: String,
        wireless: Iap2WirelessIdentification,
        language: String = "en",
        externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    ) : this(
        name = name,
        modelIdentifier = modelIdentifier,
        manufacturer = manufacturer,
        serialNumber = serialNumber,
        firmwareVersion = firmwareVersion,
        hardwareVersion = hardwareVersion,
        carPlayUsbInterfaceNumber = 0,
        language = language,
        externalAccessoryProtocol = externalAccessoryProtocol,
        wireless = wireless,
    )

    init {
        listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion).forEach {
            require(it.isNotBlank()) { "Identification identity strings must not be blank" }
        }
        require(language.isNotBlank()) { "Identification language must not be blank" }
        require(externalAccessoryProtocol.isNotBlank()) { "External accessory protocol must not be blank" }
        listOf(
            name,
            modelIdentifier,
            manufacturer,
            serialNumber,
            firmwareVersion,
            hardwareVersion,
            language,
            externalAccessoryProtocol,
        ).forEach {
            require('\u0000' !in it) { "NUL-terminated identification strings must not contain U+0000" }
        }
        require(carPlayUsbInterfaceNumber in 0..0xff) {
            "carPlayUsbInterfaceNumber must be in 0..255"
        }
        require(hidVendorIdentifier in 0..0xffff) { "hidVendorIdentifier must fit in u16" }
        require(hidProductIdentifier in 0..0xffff) { "hidProductIdentifier must fit in u16" }
    }
}

/** Identification failures distinguished from the underlying iAP2 transport failure. */
sealed class Iap2IdentificationException(message: String) : IOException(message) {
    class Rejected(parameterIds: Set<Int>, rawBody: ByteArray) : Iap2IdentificationException(
        "iAP2 identification rejected parameters ${parameterIds.sorted().joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }}; " +
            "raw 0x1d03 body=${rawBody.toHex()}; " +
            "this minimal identification profile has no optional components to remove",
    ) {
        val parameterIds: Set<Int> = parameterIds.toSet()
    }

    class UnexpectedMessage(messageId: Int) : Iap2IdentificationException(
        "Unexpected iAP2 identification message 0x${messageId.toString(16).padStart(4, '0')}",
    )
}

/**
 * Synchronous accessory-side wired or wireless identification over an already owned CSM channel.
 *
 * This is intentionally only identification: it neither invokes MFi nor itself starts any
 * CarPlay, subscription, power, media, or UI service.
 */
class Iap2IdentificationClient(private val session: Iap2Session) {
    /** Waits for link negotiation, then completes the 1D00/1D01/1D02 exchange. */
    @Throws(IphoneUsbException::class, Iap2IdentificationException::class)
    fun identify(config: Iap2IdentificationConfig, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAXIMUM_TIMEOUT_MILLIS"
        }
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        if (!session.awaitReady(remainingMillis(deadlineNanos))) {
            throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 control session readiness")
        }

        while (true) {
            val frame = session.recv(remainingMillis(deadlineNanos))
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 identification")
            when (frame.messageId) {
                START_IDENTIFICATION -> session.send(identificationInformation(config), remainingMillis(deadlineNanos))
                IDENTIFICATION_ACCEPTED -> return
                IDENTIFICATION_REJECTED -> {
                    val rejected = Iap2BodyReader.of(frame).list().mapTo(LinkedHashSet()) { it.id }
                    throw Iap2IdentificationException.Rejected(rejected, frame.payload)
                }
                else -> throw Iap2IdentificationException.UnexpectedMessage(frame.messageId)
            }
        }
    }

    companion object {
        const val START_IDENTIFICATION = 0x1d00
        const val IDENTIFICATION_INFORMATION = 0x1d01
        const val IDENTIFICATION_ACCEPTED = 0x1d02
        const val IDENTIFICATION_REJECTED = 0x1d03

        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Builds the smallest honest LIVI-compatible wired or wireless IdentificationInformation. */
        fun identificationInformation(config: Iap2IdentificationConfig): Iap2Frame {
            val wireless = config.wireless
            val sentMessages = (if (config.locationInformationEnabled) {
                MESSAGES_SENT_BY_ACCESSORY + LOCATION_INFORMATION
            } else {
                MESSAGES_SENT_BY_ACCESSORY
            }) + if (config.vehicleStatusUpdatesEnabled) intArrayOf(Iap2VehicleStatusClient.UPDATE) else intArrayOf()
            val receivedMessages = (if (config.locationInformationEnabled) {
                MESSAGES_RECEIVED_FROM_PHONE + START_LOCATION_INFORMATION + STOP_LOCATION_INFORMATION
            } else {
                MESSAGES_RECEIVED_FROM_PHONE
            }) + if (config.vehicleStatusUpdatesEnabled) {
                intArrayOf(Iap2VehicleStatusClient.START, Iap2VehicleStatusClient.STOP)
            } else intArrayOf()
            val frame = Iap2Messages.build(Iap2Endpoints.IDENTIFICATION_INFORMATION) {
                string(0, config.name)
                string(1, config.modelIdentifier)
                string(2, config.manufacturer)
                string(3, config.serialNumber)
                string(4, config.firmwareVersion)
                string(5, config.hardwareVersion)
                u16List(
                    6,
                    if (wireless == null) {
                        sentMessages.asIterable()
                    } else {
                        sentMessages.filterNot { it == POWER_SOURCE_UPDATE }.toIntArray().asIterable() +
                            ACCESSORY_WIFI_CONFIGURATION_INFORMATION
                    },
                )
                u16List(
                    7,
                    if (wireless == null) {
                        receivedMessages.asIterable()
                    } else {
                        receivedMessages.asIterable() + WIRELESS_PHONE_MESSAGES.asIterable()
                    },
                )
                u8(8, if (wireless == null) 2 else 0)
                u16(9, 20)
                group(10) {
                    u8(0, 1)
                    string(1, config.externalAccessoryProtocol)
                    u8(2, 0)
                }
                string(12, config.language)
                strings(13, listOf(config.language))
                if (wireless == null) {
                    group(16) {
                        u16(0, 0)
                        string(1, "USBHostTransport")
                        void(2)
                        u8(3, config.carPlayUsbInterfaceNumber)
                        void(4)
                    }
                } else {
                    group(17) {
                        u16(0, 0)
                        string(1, "blue")
                        void(2)
                        bytes(3, wireless.bluetoothMacBytes())
                        string(4, "blue")
                        void(5)
                    }
                }
                group(18) {
                    u16(0, Iap2HidMessages.MEDIA_PLAYBACK_COMPONENT_ID)
                    string(1, "Media Playback Remote")
                    u8(2, 1)
                }
                if (config.locationInformationEnabled) {
                    group(22) {
                        u16(0, 0)
                        string(1, config.name)
                        void(17)
                        void(18)
                    }
                }
                if (wireless != null) {
                    group(24) {
                        u16(0, 1)
                        string(1, wireless.ssid)
                        void(2)
                        u16(3, 1)
                        void(4)
                        void(5)
                    }
                }
            }
            return frame.withRgdComponentDeclaration()
        }

        /**
         * PATCH-0111（移植自 dbplay v40-road-20261013）：在 IdentificationInformation 末尾
         * **追加 RGD（Route Guidance Display）组件宣告**。
         *
         * ## 为什么必须追加
         *
         * 只在 `MESSAGES_RECEIVED_FROM_PHONE` 里声明 `0x5201/0x5202/0x5204` 还不够 ——
         * iPhone 只有在识别帧里**看到组件本体**（component id `0x1E`，componentId `0x2A`=42）
         * 才会真正开始下发导航数据。现场日志实证：
         * ```
         * RGD 0x1E appended (componentId=0x2a)   ← 本函数
         * identify: ACCEPTED by iPhone
         * iAP2 frame msgId=20993/20994 (0x5201/0x5202)  ← 导航/转向数据开始下发
         * ```
         * 没有这一步，HUD 桥绑定成功也收不到任何帧。
         *
         * ## 为什么是"追加裸字节"而不是 `group(...)`
         *
         * 现场补丁就是这么干的（在 `build()` 之后把 86 字节拼到 payload 尾部），
         * 且车机验证通过。CSM body 本身就是若干参数按序拼接，追加合法。
         * **字节表逐位照抄，不要"重排/美化"** —— 位置错一位车机就静默不认。
         */
        private fun Iap2Frame.withRgdComponentDeclaration(): Iap2Frame =
            Iap2Frame(messageId, payload + RGD_COMPONENT_DECLARATION)

        /**
         * RGD 组件宣告的完整 86 字节（`0x56`）参数：`长度 u16 | id u16 | 组件字段…`。
         *
         * 非零位如下（其余为 0），逐位对应现场补丁：
         * - `0x01` = 0x56 参数总长
         * - `0x03` = 0x1E 参数 id（RGD 组件宣告）
         * - `0x05` = 0x06、`0x09` = 0x2A（componentId 42）、`0x0B` = 0x22、`0x0D` = 0x01
         * - `0x0E..0x2A` = `"RouteGuidanceDisplayComponent"`（29 字节 ASCII）
         * - `0x2D/0x33/0x39/0x3F/0x45/0x47/0x49/0x4B/0x51/0x55` = 0x06（字段分隔）
         * - 紧随其后的 `0x2F/0x35/0x3B/0x41/0x4D/0x53` = 0x02/0x03/0x04/0x05/0x07/0x08
         * - `0x30/0x36/0x3C/0x42/0x4E` = 0x01
         */
        private val RGD_COMPONENT_DECLARATION: ByteArray = ByteArray(0x56).also { bytes ->
            bytes[0x01] = 0x56
            bytes[0x03] = 0x1e
            bytes[0x05] = 0x06
            bytes[0x09] = 0x2a
            bytes[0x0b] = 0x22
            bytes[0x0d] = 0x01
            RGD_COMPONENT_NAME.encodeToByteArray().copyInto(bytes, 0x0e)
            bytes[0x2d] = 0x06
            bytes[0x2f] = 0x02
            bytes[0x30] = 0x01
            bytes[0x33] = 0x06
            bytes[0x35] = 0x03
            bytes[0x36] = 0x01
            bytes[0x39] = 0x06
            bytes[0x3b] = 0x04
            bytes[0x3c] = 0x01
            bytes[0x3f] = 0x06
            bytes[0x41] = 0x05
            bytes[0x42] = 0x01
            bytes[0x45] = 0x06
            bytes[0x47] = 0x06
            bytes[0x49] = 0x06
            bytes[0x4b] = 0x06
            bytes[0x4d] = 0x07
            bytes[0x4e] = 0x01
            bytes[0x51] = 0x06
            bytes[0x53] = 0x08
            bytes[0x55] = 0x06
        }

        /** 组件名，必须与车机侧声明**逐字符**一致。 */
        private const val RGD_COMPONENT_NAME = "RouteGuidanceDisplayComponent"

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw IphoneUsbException.TimedOut("iAP2 identification timed out")
            return min(MAXIMUM_TIMEOUT_MILLIS, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }

        /* Keep this list paired with Iap2WiredControlClient; no stop or AA messages are claimed. */
        private val MESSAGES_SENT_BY_ACCESSORY = intArrayOf(
            0x5000, // StartNowPlayingUpdates
            0x5002, // StopNowPlayingUpdates
            0x5200, // StartRouteGuidanceUpdates
            0x5203, // StopRouteGuidanceUpdates
            0xae00, // StartPowerUpdates
            0xae02, // StopPowerUpdates
            0x4157, // StartCommunicationsUpdates
            0x4159, // StopCommunicationsUpdates
            0x4154, // StartCallStateUpdates
            0x4156, // StopCallStateUpdates
            0xae03, // PowerSourceUpdate
            0x4301, // CarPlayStartSession
            0x6800, // StartHID
            0x6802, // AccessoryHIDReport
            0x6803, // StopHID
        )
        /*
         * PATCH-0039: `0x5204` (LaneGuidanceInfoUpdate) was missing here, so the
         * phone never sent lane guidance and the cluster lane strip could never
         * draw more than the one lane the old decoder salvaged. The stock app
         * declares it in its own `:array_7c`.
         *
         * The `0x6800/0x6802/0x6803` HID entries are DBPlay's own additions on
         * top of the stock list and are kept: the stock app reaches HID through
         * a different route, but HID is already working here and removing the
          * declarations would break it. The `0xaa**` family is MFi authentication,
          * not Vehicle Status. Vehicle Status uses A100/A101/A102 and is appended
          * separately only when its read-only provider is supplied.
         */
        private val MESSAGES_RECEIVED_FROM_PHONE = intArrayOf(
            0xea00, // StartExternalAccessoryProtocolSession
            0xea01, // StopExternalAccessoryProtocolSession
            0x5001, // NowPlayingUpdate
            0x5201, // RouteGuidanceUpdate
            0x5202, // RouteGuidanceManeuverUpdate
            0x5204, // LaneGuidanceInfoUpdate (PATCH-0039)
            0xae01, // PowerUpdate
            0x4158, // CommunicationsUpdate
            0x4155, // CallStateUpdate
            0x4300, // CarPlayAvailability
            0x6801, // DeviceHIDReport (phone→accessory reply required once StartHID is declared)
        )
        private const val POWER_SOURCE_UPDATE = 0xae03
        private const val ACCESSORY_WIFI_CONFIGURATION_INFORMATION = 0x5703
        private const val LOCATION_INFORMATION = 0xfffb
        private const val START_LOCATION_INFORMATION = 0xfffa
        private const val STOP_LOCATION_INFORMATION = 0xfffc
        private val WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)
    }
}
