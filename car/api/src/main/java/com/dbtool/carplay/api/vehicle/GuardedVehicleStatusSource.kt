package com.dbtool.carplay.api.vehicle

/** Switch-off/trip closes ONLY reader leases. It neither restarts nor closes CarPlay itself. */
class GuardedVehicleStatusSource(
    private val delegate: CarVehicleStatusSource,
    private val protection: CarVehicleReadProtection,
) : CarVehicleStatusSource {
    private val lock = Any()
    private val leases = mutableSetOf<Lease>()
    @Suppress("unused")
    private val observer = protection.observe {
        // Notifications can be reentrant or arrive out of order. Retire by the LIVE gate revision,
        // never let an old "off" notification close a newly enabled controller's leases.
        synchronized(lock) { leases.filterNot { allowed(it.generation) } }.forEach { it.close() }
    }
    override val enabled: Boolean get() = protection.state().enabled

    override fun forSession(): CarVehicleStatusSource {
        val generation = protection.state().generation
        return object : CarVehicleStatusSource {
            override val enabled: Boolean get() = allowed(generation)
            override fun open(fields: Set<CarVehicleStatusField>) =
                this@GuardedVehicleStatusSource.open(fields, generation)
        }
    }

    override fun open(fields: Set<CarVehicleStatusField>): CarVehicleStatusSubscription =
        open(fields, protection.state().generation)

    private fun allowed(generation: Long): Boolean =
        protection.state().let { it.enabled && it.generation == generation }

    private fun open(fields: Set<CarVehicleStatusField>, generation: Long): CarVehicleStatusSubscription {
        val lease = Lease(generation)
        synchronized(lock) {
            if (!allowed(generation)) return lease.apply { close() }
            leases += lease
        }
        try {
            lease.attach(delegate.open(fields))
        } catch (error: Throwable) {
            protection.trip("车辆读取初始化异常，已自动关闭：${error.javaClass.simpleName}")
            lease.close()
        }
        return lease
    }

    private inner class Lease(val generation: Long) : CarVehicleStatusSubscription {
        private var closed = false
        private var target: CarVehicleStatusSubscription? = null
        fun attach(value: CarVehicleStatusSubscription) {
            val accepted = synchronized(lock) {
                if (closed || !allowed(generation)) false else {
                    target = value
                    true
                }
            }
            if (!accepted) retire(value)
        }
        override fun latest(): CarVehicleStatusSnapshot {
            val reader = synchronized(lock) {
                if (closed || !allowed(generation)) return CarVehicleStatusSnapshot()
                target
            } ?: return CarVehicleStatusSnapshot()
            val value = try {
                reader.latest()
            } catch (error: Throwable) {
                protection.trip("车辆读取快照异常，已自动关闭：${error.javaClass.simpleName}")
                close()
                return CarVehicleStatusSnapshot()
            }
            return synchronized(lock) {
                if (closed || target !== reader || !allowed(generation)) CarVehicleStatusSnapshot() else value
            }
        }
        override fun close() {
            val retired = synchronized(lock) {
                if (closed) return
                closed = true
                leases -= this
                target.also { target = null }
            }
            retired?.let(::retire)
        }

        private fun retire(value: CarVehicleStatusSubscription) {
            try {
                value.close()
            } catch (error: Throwable) {
                protection.trip("车辆读取清理异常，已自动关闭：${error.javaClass.simpleName}")
            }
        }
    }
}
