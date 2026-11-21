package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWifiP2pGroupOwnershipTest {
    @Test
    fun removesOnlyTheRecordedGroupOwner() {
        assertTrue(legacyP2pGroupCanBeRemoved(true, "DIRECT-example", "DIRECT-example"))
        assertFalse(legacyP2pGroupCanBeRemoved(false, "DIRECT-example", "DIRECT-example"))
        assertFalse(legacyP2pGroupCanBeRemoved(true, "DIRECT-other", "DIRECT-example"))
        assertFalse(legacyP2pGroupCanBeRemoved(true, "DIRECT-example", null))
        assertFalse(legacyP2pGroupCanBeRemoved(true, null, "DIRECT-example"))
        // Blank is a distinct branch from null (`isNullOrBlank`); never treat "" as a known identity.
        assertFalse(legacyP2pGroupCanBeRemoved(true, "", ""))
        assertFalse(legacyP2pGroupCanBeRemoved(true, "DIRECT-example", "  "))
    }
}
