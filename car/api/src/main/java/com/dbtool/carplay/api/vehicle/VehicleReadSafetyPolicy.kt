package com.dbtool.carplay.api.vehicle

data class VehicleReadSafetyState(
    val enabled: Boolean = false,
    val interruptedSession: Boolean = false,
    val disabledReason: String? = null,
    /** Process-local gate revision; old CarPlay sessions cannot reopen after off/on. Not a pref. */
    val generation: Long = 0,
)

interface VehicleReadSafetyStore {
    fun load(): VehicleReadSafetyState
    /** Must durably save before true is returned; failure must not permit OEM access. */
    fun save(state: VehicleReadSafetyState): Boolean
}

interface CarVehicleReadProtection {
    fun state(): VehicleReadSafetyState
    /** Called on the OEM worker, BEFORE constructing/loading/calling vendor clients. */
    fun beginSession(): Long?
    fun endSession(token: Long)
    fun trip(reason: String)
    fun observe(listener: (VehicleReadSafetyState) -> Unit): AutoCloseable
}

/**
 * Process-owned circuit breaker. One durable marker per active reading period, not per sample.
 * An uncleared marker means an interrupted process/session, NOT proof that this feature crashed.
 */
class VehicleReadSafetyPolicy(private val store: VehicleReadSafetyStore) : CarVehicleReadProtection {
    private val lock = Any()
    private val listeners = mutableSetOf<(VehicleReadSafetyState) -> Unit>()
    private val sessions = mutableSetOf<Long>()
    private var sequence = 0L
    private var current = runCatching { store.load() }.getOrElse {
        VehicleReadSafetyState(disabledReason = "无法读取车辆读取保护设置，已关闭")
    }

    init {
        if (current.interruptedSession) {
            current = VehicleReadSafetyState(
                enabled = false,
                disabledReason = current.disabledReason ?: if (current.enabled) {
                    "上次车辆读取未正常结束，已保护性关闭（也可能是系统杀进程或断电）"
                } else null,
            )
            runCatching { store.save(current) }
        }
    }

    override fun state(): VehicleReadSafetyState = synchronized(lock) { current }

    fun setEnabled(enabled: Boolean): Boolean {
        val saved: Boolean
        synchronized(lock) {
            if (enabled && sessions.isNotEmpty()) return false // Wait for retired IPC/cleanup.
            val next = VehicleReadSafetyState(
                enabled = enabled,
                interruptedSession = sessions.isNotEmpty(),
                generation = current.generation + 1,
            )
            saved = save(next)
            current = if (saved) next else current.copy(
                enabled = false, disabledReason = "无法保存车辆读取保护设置，读取保持关闭",
                generation = next.generation,
            )
        }
        notifyListeners()
        return saved
    }

    override fun beginSession(): Long? {
        var changed = false
        val token = synchronized(lock) {
            if (!current.enabled) return@synchronized null
            if (sessions.isEmpty()) {
                val armed = current.copy(interruptedSession = true)
                if (!save(armed)) {
                    current = current.copy(
                        enabled = false, disabledReason = "无法写入车辆读取保护标记，未启动读取",
                        generation = current.generation + 1,
                    )
                    changed = true
                    return@synchronized null
                }
                current = armed
                changed = true
            }
            (++sequence).also { sessions += it }
        }
        if (changed) notifyListeners()
        return token
    }

    override fun endSession(token: Long) {
        var changed = false
        synchronized(lock) {
            if (!sessions.remove(token)) return
            if (sessions.isEmpty()) {
                val clean = current.copy(interruptedSession = false)
                if (save(clean)) {
                    current = clean
                } else {
                    // Leave the durable pending marker as-is: restart will conservatively disable.
                    current = current.copy(
                        enabled = false, disabledReason = "车辆读取保护标记未能清除，已关闭",
                        generation = current.generation + 1,
                    )
                }
                changed = true
            }
        }
        if (changed) notifyListeners()
    }

    override fun trip(reason: String) {
        synchronized(lock) {
            val next = current.copy(
                enabled = false,
                interruptedSession = sessions.isNotEmpty(),
                disabledReason = reason.take(240),
                generation = current.generation + 1,
            )
            save(next)
            current = next // In-memory fail closed even if storage itself failed.
        }
        notifyListeners()
    }

    override fun observe(listener: (VehicleReadSafetyState) -> Unit): AutoCloseable {
        synchronized(lock) { listeners += listener }
        runCatching { listener(state()) }
        return AutoCloseable { synchronized(lock) { listeners -= listener } }
    }

    private fun save(state: VehicleReadSafetyState): Boolean =
        runCatching { store.save(state) }.getOrDefault(false)

    private fun notifyListeners() {
        val (value, callbacks) = synchronized(lock) { current to listeners.toList() }
        callbacks.forEach { runCatching { it(value) } }
    }
}
