package com.dbtool.carplay.geely.vehicle

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class GeelyVehicleReadFailuresTest {
    class FatalProperty {
        fun getPropertyId(): Int = throw UnsatisfiedLinkError("synthetic; no native load")
    }

    @Test fun reflectionEnvelopeCannotHideFatalLinkageFailureFromCircuitBreaker() {
        val original = UnsatisfiedLinkError("synthetic")
        try {
            GeelyVehicleReadFailures.orUnknown<Int> {
                throw InvocationTargetException(InvocationTargetException(original))
            }
            fail("Fatal error must reach the protected client")
        } catch (actual: UnsatisfiedLinkError) {
            assertSame(original, actual)
        }
    }

    @Test fun missingApiPermissionAndBadSampleRemainUnknownNotAClaimOfCrash() {
        for (error in listOf(NoSuchMethodException(), SecurityException(), IllegalArgumentException())) {
            assertNull(GeelyVehicleReadFailures.orUnknown<Int> { throw InvocationTargetException(error) })
        }
    }

    @Test fun arbitraryExceptionCauseIsNotUnwrappedToReclassifyDenial() {
        assertNull(GeelyVehicleReadFailures.orUnknown<Int> {
            throw SecurityException("denied", UnsatisfiedLinkError("not a reflection envelope"))
        })
    }

    @Test fun bothMetadataDecodersPropagateFatalGetterFailuresInsteadOfReturningUnknown() {
        for (decode in listOf<() -> Any?>(
            { GeelyStandardPropertyDecoder.property(FatalProperty()) },
            { GeelyStandardPropertyDecoder.config(FatalProperty()) },
            { GeelyVehiclePropertyDecoder.decode(FatalProperty(), 1) },
        )) {
            try {
                decode()
                fail("Fatal getter error swallowed")
            } catch (_: UnsatisfiedLinkError) {
                // Expected synthetic JVM error, not a real native process crash test.
            }
        }
    }
}
