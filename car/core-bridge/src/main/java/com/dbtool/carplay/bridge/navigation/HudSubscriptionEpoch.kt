package com.dbtool.carplay.bridge.navigation

/** Unsubscribe cannot revoke callbacks already copied by a publisher. Guard each subscription. */
internal class HudSubscriptionEpoch {
    private var epoch = 0L
    @Synchronized fun next(): Long = ++epoch
    @Synchronized fun isCurrent(token: Long): Boolean = token == epoch
}
