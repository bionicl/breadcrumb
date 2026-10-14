package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryVisitPolicyTest {
    @Test fun `a pause under four minutes does not create a standalone visit`() {
        assertFalse(StationaryVisitPolicy.qualifies(0, 4 * MINUTE - 1))
    }

    @Test fun `a pause of exactly four minutes qualifies`() {
        assertTrue(StationaryVisitPolicy.qualifies(0, 4 * MINUTE))
    }

    @Test fun `an ongoing pause qualifies after it has lasted four minutes`() {
        assertTrue(StationaryVisitPolicy.qualifies(0, null, nowMs = 4 * MINUTE))
        assertFalse(StationaryVisitPolicy.qualifies(0, null, nowMs = 2 * MINUTE))
    }

    @Test fun `unknown or reversed bounds do not qualify`() {
        assertFalse(StationaryVisitPolicy.qualifies(0, null))
        assertFalse(StationaryVisitPolicy.qualifies(10, 9))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
