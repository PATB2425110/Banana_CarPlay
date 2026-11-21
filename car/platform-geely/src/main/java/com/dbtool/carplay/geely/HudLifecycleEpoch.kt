package com.dbtool.carplay.geely

import java.util.concurrent.atomic.AtomicLong

/** A new start cannot make a previously captured batch/connection valid again. */
internal class HudLifecycleEpoch {
    private val value = AtomicLong()
    fun next(): Long = value.incrementAndGet()
    fun current(): Long = value.get()
    fun isCurrent(epoch: Long): Boolean = value.get() == epoch
}
