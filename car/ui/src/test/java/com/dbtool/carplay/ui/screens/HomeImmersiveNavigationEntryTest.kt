package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.ui.CarUiModel
import org.junit.Assert.*
import org.junit.Test

class HomeImmersiveNavigationEntryTest {
    private fun connected(): CarUiModel {
        val base = CarUiModel()
        return base.copy(
            session = base.session.copy(showVideo = true),
            immersiveNavigationActive = true,
            settings = base.settings.copy(immersiveNavigationEnabled = true),
        )
    }
    @Test fun onlyConnectedNegotiatedImmersiveSessionCanEnter111() {
        assertTrue(canEnterImmersiveNavigation(connected()))
        assertFalse(canEnterImmersiveNavigation(CarUiModel()))
        assertFalse(canEnterImmersiveNavigation(connected().copy(immersiveNavigationActive = false)))
        val c = connected()
        assertFalse(canEnterImmersiveNavigation(c.copy(session = c.session.copy(showVideo = false))))
        assertFalse(canEnterImmersiveNavigation(c.copy(settings = c.settings.copy(immersiveNavigationEnabled = false))))
    }
    @Test fun unrelatedPendingSettingsDoNotDisableExistingImmersiveConnection() {
        assertTrue(canEnterImmersiveNavigation(connected().copy(settingsPendingReconnect = true)))
    }
}
