package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.ImmersiveNavigationPolicy
import com.dbtool.carplay.api.display.ImmersiveNavigationPolicy.Screen.*
import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.*
import org.junit.Test

class ImmersiveNavigationPolicyTest {
    @org.junit.Test fun recreatedViewRestoresEitherWindowAfterItsFirstFrame() {
        for (navigation in listOf(false, true)) {
            val restored = com.dbtool.carplay.api.display.ImmersiveNavigationPolicy()
            restored.configure(true)
            restored.requestNavigation(navigation)
            restored.onFirstFrame(111)
            restored.onFirstFrame(110)
            org.junit.Assert.assertEquals(
                if (navigation) com.dbtool.carplay.api.display.ImmersiveNavigationPolicy.Screen.NAVIGATION_111
                else com.dbtool.carplay.api.display.ImmersiveNavigationPolicy.Screen.MAIN_110, restored.current())
        }
    }
    @Test fun enabledWaitsForNavigationFirstFrame() {
        val p = ImmersiveNavigationPolicy()
        assertEquals(MAIN_110, p.configure(true)); assertTrue(p.waiting())
        assertEquals(NAVIGATION_111, p.onFirstFrame(111)); assertFalse(p.waiting())
    }
    @Test fun swipeBackWaitsForMainFirstFrame() {
        val p = ImmersiveNavigationPolicy(); p.configure(true); p.onFirstFrame(111)
        assertEquals(NAVIGATION_111, p.requestNavigation(false))
        assertEquals(MAIN_110, p.onFirstFrame(110))
    }
    @Test fun pendingSwipeCanBeCancelledBeforeFirstFrame() {
        val p = ImmersiveNavigationPolicy(); p.configure(true); p.onFirstFrame(110)
        p.requestNavigation(false)
        assertEquals(MAIN_110, p.onFirstFrame(111))
    }
    @Test fun disabledNeverShowsNavigationEvenWithLateFrame() {
        val p = ImmersiveNavigationPolicy(); p.configure(false)
        p.requestNavigation(true); assertEquals(MAIN_110, p.onFirstFrame(111))
    }
    @Test fun connectionResetInvalidatesOldReadiness() {
        val p = ImmersiveNavigationPolicy(); p.configure(true); p.onFirstFrame(111); p.connectionReset()
        assertEquals(MAIN_110, p.current()); assertTrue(p.waiting())
    }
    @Test fun copiedCardAndSafeAreaSettingsAreIndependent() {
        val s = CarSessionSettings.default()
        val next = s.copy(immersiveNavigationEnabled = true, immersiveNavigationCardEnabled = !s.clusterNavigationCard)
        assertEquals(s.clusterNavigationCard, next.clusterNavigationCard)
        assertEquals(s.safeArea, next.safeArea)
        assertEquals(s.projectionEnabled, next.projectionEnabled)
    }
}
