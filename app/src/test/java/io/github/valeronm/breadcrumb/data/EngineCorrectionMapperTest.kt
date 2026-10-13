package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.EngineUserCorrectionEntity
import io.github.valeronm.breadcrumb.locomotion.BoundaryKind
import io.github.valeronm.breadcrumb.locomotion.TransportMode
import io.github.valeronm.breadcrumb.locomotion.UserCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineCorrectionMapperTest {
    @Test fun `maps confirmed transport visit and boundary rows`() {
        val rows = listOf(
            EngineUserCorrectionEntity("transport", "op-1", "legacy-track:7", "CONFIRM_TRANSPORT", 10, transportMode = "TRAIN"),
            EngineUserCorrectionEntity("visit", "op-2", "legacy-track:8", "CONFIRM_VISIT", 11),
            EngineUserCorrectionEntity(
                "boundary", "op-3", "legacy-track:9", "LOCK_BOUNDARY", 12,
                boundaryKind = "END", atWallTimeMs = 1200,
            ),
        )

        assertEquals(
            UserCorrection.ConfirmTransport("transport", 10, "legacy-track:7", TransportMode.TRAIN),
            rows[0].toEngineCorrection(),
        )
        assertEquals(UserCorrection.ConfirmVisit("visit", 11, "legacy-track:8"), rows[1].toEngineCorrection())
        assertEquals(
            UserCorrection.LockBoundary("boundary", 12, "legacy-track:9", BoundaryKind.END, 1200),
            rows[2].toEngineCorrection(),
        )
    }

    @Test fun `ignores malformed correction rows`() {
        assertNull(
            EngineUserCorrectionEntity("bad", "op", "segment", "LOCK_BOUNDARY", 1).toEngineCorrection(),
        )
        assertNull(
            EngineUserCorrectionEntity(
                "bad-mode", "op", "segment", "CONFIRM_TRANSPORT", 1, transportMode = "NOT_A_MODE",
            ).toEngineCorrection(),
        )
    }
}
