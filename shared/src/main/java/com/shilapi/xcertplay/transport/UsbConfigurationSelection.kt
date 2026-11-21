package com.shilapi.xcertplay.transport

/**
 * Adapted from DiPlay's UsbConfigurationSelection (GPL-3.0), issue #518 recovery.
 * Forced claims detach kernel drivers holding the old configuration. Close drops
 * these temporary claims without releaseInterface() asking the driver to rebind.
 */
internal object UsbConfigurationSelection {
    data class Outcome(
        val target: Int,
        val selected: Boolean,
        val before: Int?,
        val after: Int?,
        val detached: List<Int>,
    ) {
        val wrongConfiguration: Boolean get() = !selected && after != null && after != target

        fun diagnostic(): String =
            "USB configuration select target=$target ok=$selected before=${before ?: "unknown"} " +
                "detached=${detached.joinToString(",").ifEmpty { "none" }} after=${after ?: "unknown"}"
    }

    fun select(
        target: Int,
        interfaceIds: List<Int>,
        setConfiguration: () -> Boolean,
        activeConfiguration: () -> Int?,
        detach: (Int) -> Boolean,
        reopen: () -> Unit,
    ): Outcome? {
        if (setConfiguration()) return null
        val before = activeConfiguration()
        if (before == target) return Outcome(target, false, before, before, emptyList())
        val detached = interfaceIds.distinct().sorted().filter(detach)
        if (detached.isNotEmpty()) reopen()
        val selected = setConfiguration()
        return Outcome(target, selected, before, activeConfiguration(), detached)
    }
}
