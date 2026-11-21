package com.dbtool.carplay.bridge

/** Delayed USB teardown callbacks must not replace a newer/user-stopped/wireless controller. */
internal fun shouldRecoverWiredController(
    bridgeClosed: Boolean,
    wireless: Boolean,
    expectedGeneration: Int,
    currentGeneration: Int,
    sameController: Boolean,
    controllerClosed: Boolean,
): Boolean = !bridgeClosed && !wireless && expectedGeneration == currentGeneration &&
    sameController && controllerClosed
