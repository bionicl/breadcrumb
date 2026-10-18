package io.github.valeronm.breadcrumb.debug

import io.github.valeronm.breadcrumb.data.StoredDerivation
import io.github.valeronm.breadcrumb.data.db.DerivedCluster
import io.github.valeronm.breadcrumb.data.db.DerivedInterval
import io.github.valeronm.breadcrumb.data.db.EngineUserCorrectionEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugDayJsonTest {
    @Test fun `day dump includes related intervals clusters and user visit corrections`() {
        val stored = StoredDerivation(
            clusters = listOf(
                cluster(id = 1, placeId = 12),
                cluster(id = 2, placeId = 13),
                cluster(id = 3, placeId = 14),
            ),
            members = emptyList(),
            intervals = listOf(
                interval(afterTrackId = 10, start = 100, end = 200, clusterId = 1),
                interval(afterTrackId = 11, start = 1_100, end = 1_200, clusterId = 2),
                interval(afterTrackId = 99, start = 4_000, end = 4_200, clusterId = 3),
            ),
            places = emptyList(),
            visitCorrections = listOf(
                correction(id = "assigned", trackId = 10, kind = "ASSIGN_VISIT_PLACE", placeId = 12),
                correction(id = "deleted", trackId = 11, kind = "DELETE_VISIT"),
                correction(id = "unrelated", trackId = 99, kind = "ASSIGN_VISIT_PLACE", placeId = 14),
            ),
        )

        val json = stored.toDayJson(trackIds = setOf(10), startMs = 1_000, endMs = 2_000)

        val intervalTrackIds = json.getJSONArray("derivedIntervals").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getLong("afterTrackId") }
        }
        val clusterIds = json.getJSONArray("derivedClusters").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getLong("id") }
        }
        assertEquals(listOf(10L, 11L), intervalTrackIds)
        assertEquals(listOf(1L, 2L), clusterIds)
        val corrections = json.getJSONArray("visitCorrections")
        assertEquals(2, corrections.length())
        assertEquals("ASSIGN_VISIT_PLACE", corrections.getJSONObject(0).getString("kind"))
        assertEquals(12L, corrections.getJSONObject(0).getLong("correctedPlaceId"))
        assertEquals("DELETE_VISIT", corrections.getJSONObject(1).getString("kind"))
    }

    private fun cluster(id: Long, placeId: Long) = DerivedCluster(
        id = id,
        placeId = placeId,
        anchorLat = 1.0,
        anchorLon = 2.0,
        radiusM = 100.0,
        sumLat = 1.0,
        sumLon = 2.0,
        memberCount = 1,
    )

    private fun interval(afterTrackId: Long, start: Long, end: Long, clusterId: Long) = DerivedInterval(
        type = DerivedInterval.TYPE_STAY,
        start = start,
        endedAt = end,
        afterTrackId = afterTrackId,
        clusterId = clusterId,
    )

    private fun correction(id: String, trackId: Long, kind: String, placeId: Long? = null) =
        EngineUserCorrectionEntity(
            id = id,
            operationId = "op-$id",
            targetSegmentId = "derived-visit:$trackId",
            kind = kind,
            createdAtWallTimeMs = 1,
            correctedPlaceId = placeId,
        )
}
