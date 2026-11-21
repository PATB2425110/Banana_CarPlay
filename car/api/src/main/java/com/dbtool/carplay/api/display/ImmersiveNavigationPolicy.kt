package com.dbtool.carplay.api.display

/** Connection-local display intent. No renderer, theme or OEM mode dependencies. */
class ImmersiveNavigationPolicy {
    enum class Screen { MAIN_110, NAVIGATION_111 }
    private var enabled = false
    private var ready110 = false
    private var ready111 = false
    private var requested = Screen.MAIN_110
    private var displayed = Screen.MAIN_110

    @Synchronized fun configure(value: Boolean): Screen {
        enabled = value
        requested = if (value) Screen.NAVIGATION_111 else Screen.MAIN_110
        if (!value) displayed = Screen.MAIN_110
        return settle()
    }
    @Synchronized fun onFirstFrame(type: Int): Screen {
        if (type == 110) ready110 = true
        if (type == 111) ready111 = true
        return settle()
    }
    @Synchronized fun requestNavigation(show: Boolean): Screen {
        if (!enabled) return displayed
        requested = if (show) Screen.NAVIGATION_111 else Screen.MAIN_110
        return settle()
    }
    @Synchronized fun connectionReset() {
        ready110 = false; ready111 = false; displayed = Screen.MAIN_110
        requested = if (enabled) Screen.NAVIGATION_111 else Screen.MAIN_110
    }
    @Synchronized fun current(): Screen = displayed
    @Synchronized fun waiting(): Boolean = enabled && requested != displayed
    private fun settle(): Screen {
        if (requested == Screen.NAVIGATION_111 && enabled && ready111) displayed = requested
        if (requested == Screen.MAIN_110 && ready110) displayed = requested
        return displayed
    }
}
