package com.dbtool.carplay.geely

/** Sender-owned delivery barrier: dequeuing a stop is not a receiver acknowledgement. */
internal class HudStopBarrier {
    private var requested = 0L
    private var acknowledged = 0L
    val isPending: Boolean get() = requested != acknowledged
    fun request(): Long = ++requested
    fun acknowledge(token: Long) {
        if (token == requested) acknowledged = token
    }
}
