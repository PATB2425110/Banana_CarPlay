package com.shilapi.xcertplay.power

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

data class PhoneCommunicationsState(
    val signalStrength: Int? = null,
    val registrationStatus: Int? = null,
    val airplaneMode: Boolean? = null,
    val carrierName: String? = null,
    val microphoneMuted: Boolean? = null,
    val currentCallCount: Int? = null,
) {
    fun merge(delta: PhoneCommunicationsState) = PhoneCommunicationsState(
        delta.signalStrength ?: signalStrength, delta.registrationStatus ?: registrationStatus,
        delta.airplaneMode ?: airplaneMode, delta.carrierName ?: carrierName,
        delta.microphoneMuted ?: microphoneMuted, delta.currentCallCount ?: currentCallCount,
    )
}

data class PhonePowerState(
    val level: Int? = null,
    val chargingState: Int? = null,
    val maxCurrentDrawnMa: Int? = null,
    val willChargeIfPowerPresent: Boolean? = null,
    val externalChargerConnected: Boolean? = null,
    val communications: PhoneCommunicationsState? = null,
)

/** Session-owned incremental PowerUpdate/CommunicationsUpdate, using caller-supplied definitions. */
object CarPlayPhonePowerBridge {
    private var owner: Any? = null
    private var value: PhonePowerState? = null
    private val observers = LinkedHashSet<(PhonePowerState?) -> Unit>()

    @Synchronized fun current(): PhonePowerState? = value

    @Synchronized fun observe(listener: (PhonePowerState?) -> Unit): () -> Unit {
        observers += listener
        runCatching { listener(value) }
        return { synchronized(this) { observers -= listener }; Unit }
    }

    @Synchronized fun attach(token: Any) {
        owner = token
        publish(null)
    }

    @Synchronized fun detach(token: Any) {
        if (owner !== token) return
        owner = null
        publish(null)
    }

    @Synchronized fun accept(token: Any, frame: Iap2Frame) {
        if (owner !== token) return
        val previous = value ?: PhonePowerState()
        when (frame.messageId) {
            0xae01 -> {
                val delta = decodePhonePowerUpdate(frame.payload) ?: return
                publish(previous.copy(
                    level = delta.level ?: previous.level,
                    chargingState = delta.chargingState ?: previous.chargingState,
                    maxCurrentDrawnMa = delta.maxCurrentDrawnMa ?: previous.maxCurrentDrawnMa,
                    willChargeIfPowerPresent = delta.willChargeIfPowerPresent ?: previous.willChargeIfPowerPresent,
                    externalChargerConnected = delta.externalChargerConnected ?: previous.externalChargerConnected,
                ))
            }
            0x4158 -> {
                val delta = decodePhoneCommunicationsUpdate(frame.payload) ?: return
                publish(previous.copy(communications =
                    (previous.communications ?: PhoneCommunicationsState()).merge(delta)))
            }
        }
    }

    private fun publish(next: PhonePowerState?) {
        value = next
        observers.toList().forEach { runCatching { it(next) } }
    }
}

/** Missing, malformed, duplicate or out-of-range parameters never fabricate a battery reading. */
internal fun decodePhonePowerParameter6(body: ByteArray): Int? {
    return decodePhonePowerUpdate(body)?.level
}

internal fun decodePhonePowerUpdate(body: ByteArray): PhonePowerState? {
    val fields = phoneStatusTlvs(body) ?: return null
    val known = fields.filter { it.id in listOf(0, 1, 4, 5, 6) }
    if (known.isEmpty() || known.map { it.id }.distinct().size != known.size) return null
    if (known.any { field -> when (field.id) {
            0 -> field.bytes.size != 2
            1, 4 -> field.bytes.size != 1 || field.u8() !in 0..1
            5 -> field.bytes.size != 1 || field.u8() !in 0..2
            6 -> field.bytes.size != 2 || field.u16() !in 0..100
            else -> false
        } }) return null
    fun field(id: Int) = known.firstOrNull { it.id == id }
    return PhonePowerState(
        level = field(6)?.u16(), chargingState = field(5)?.u8(),
        maxCurrentDrawnMa = field(0)?.u16(),
        willChargeIfPowerPresent = field(1)?.let { it.u8() == 1 },
        externalChargerConnected = field(4)?.let { it.u8() == 1 },
    )
}

internal fun decodePhoneCommunicationsUpdate(body: ByteArray): PhoneCommunicationsState? {
    val fields = phoneStatusTlvs(body) ?: return null
    // Legacy ids 4/5 are intentionally not reinterpreted as carrier/SIM state.
    val known = fields.filter { it.id in listOf(0, 1, 2, 3, 8, 9) }
    if (known.isEmpty() || known.map { it.id }.distinct().size != known.size) return null
    if (known.any { field -> when (field.id) {
            0 -> field.bytes.size != 1 || field.u8() !in 0..5
            1 -> field.bytes.size != 1 || field.u8() !in 0..6
            2, 8 -> field.bytes.size != 1 || field.u8() !in 0..1
            9 -> field.bytes.size != 1
            else -> false
        } }) return null
    fun field(id: Int) = known.firstOrNull { it.id == id }
    return PhoneCommunicationsState(
        signalStrength = field(0)?.u8(), registrationStatus = field(1)?.u8(),
        airplaneMode = field(2)?.let { it.u8() == 1 },
        carrierName = field(3)?.let { String(it.bytes, Charsets.UTF_8).trimEnd('\u0000') },
        microphoneMuted = field(8)?.let { it.u8() == 1 }, currentCallCount = field(9)?.u8(),
    )
}

private data class PhoneStatusTlv(val id: Int, val bytes: ByteArray) {
    fun u8() = bytes[0].toInt() and 255
    fun u16() = (u8() shl 8) or (bytes[1].toInt() and 255)
}

private fun phoneStatusTlvs(body: ByteArray): List<PhoneStatusTlv>? {
    fun u16(at: Int) = ((body[at].toInt() and 255) shl 8) or (body[at + 1].toInt() and 255)
    var offset = 0
    val fields = ArrayList<PhoneStatusTlv>()
    if (body.size > 65529) return null
    while (offset < body.size) {
        if (body.size - offset < 4) return null
        val length = u16(offset)
        if (length < 4 || length > body.size - offset) return null
        fields += PhoneStatusTlv(u16(offset + 2), body.copyOfRange(offset + 4, offset + length))
        offset += length
    }
    return fields
}
