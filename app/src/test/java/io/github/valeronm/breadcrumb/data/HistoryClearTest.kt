package io.github.valeronm.breadcrumb.data

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.EngineUserCorrectionEntity
import io.github.valeronm.breadcrumb.data.db.PlaceIdentity
import io.github.valeronm.breadcrumb.data.db.TrackCompositionDraft
import io.github.valeronm.breadcrumb.data.db.toEntity
import io.github.valeronm.breadcrumb.locomotion.EngineCheckpoint
import io.github.valeronm.breadcrumb.locomotion.LocationMeasurement
import io.github.valeronm.breadcrumb.locomotion.MotionState
import io.github.valeronm.breadcrumb.locomotion.Observation
import io.github.valeronm.breadcrumb.locomotion.ObservationSource
import io.github.valeronm.breadcrumb.locomotion.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryClearTest {

    private val test = TestDb()
    private val repository get() = test.repository

    @After fun tearDown() = test.close()

    @Test fun `clearing the history empties every table in the database`() = runBlocking {
        fillEveryTable()
        val tables = tables()
        for (table in tables) assertTrue("$table holds a row before the clear", long("SELECT COUNT(*) FROM `$table`") > 0)

        repository.clearHistory()

        for (table in tables) assertEquals(table, 0L, long("SELECT COUNT(*) FROM `$table`"))
        assertEquals("foreign keys enforced again", 1L, long("PRAGMA foreign_keys"))
    }

    @Test fun `an observer sees the history cleared`() = runBlocking {
        fillEveryTable()
        val scope = CoroutineScope(Dispatchers.IO)
        val anyRows = repository.observeAnyRows().stateIn(scope)
        withTimeout(TIMEOUT_MS) { anyRows.first { it } }

        repository.clearHistory()

        withTimeout(TIMEOUT_MS) { anyRows.first { !it } }
        scope.cancel()
    }

    private suspend fun fillEveryTable() {
        val deleted = test.walk(TEST_START, 0, 5)
        val first = test.walk(TEST_START + 120_000L, 6, 11)
        val second = test.walk(TEST_START + 240_000L, 11, 16)
        test.walk(TEST_START + 360_000L, 16, 21)
        repository.mergeTracks(
            first,
            second,
            TrackCompositionDraft(first, TEST_START + 180_000L, TEST_START + 240_000L),
        )
        repository.deleteTrack(deleted)
        val home = test.db.placeDao().insert(test.place("Home", 1.0, -2.0))
        test.db.placeIdentityDao().upsert(listOf(PlaceIdentity("google_timeline", "home", home)))
        DerivationStore(ApplicationProvider.getApplicationContext(), test.db).reconcile()
        test.db.locomotionDao().insertObservation(
            Observation(
                id = "history-clear-observation",
                sessionId = "history-clear-session",
                bootEpochId = "history-clear-boot",
                wallTimeMs = TEST_START,
                elapsedRealtimeNanos = 1L,
                source = ObservationSource.RAW_GNSS,
                location = LocationMeasurement(1.0, -2.0, 5.0),
            ).toEntity(),
        )
        test.db.locomotionDao().saveCheckpoint(
            EngineCheckpoint(
                sessionId = "history-clear-session",
                bootEpochId = "history-clear-boot",
                lastObservationId = "history-clear-observation",
                lastElapsedRealtimeNanos = 1L,
                lastWallTimeMs = TEST_START,
                motionState = MotionState.UNCERTAIN,
                recordingState = RecordingState.IDLE,
                filter = null,
                activeSegmentId = null,
                configurationVersion = 1,
                modelVersion = null,
                updatedAtWallTimeMs = TEST_START,
            ).toEntity(),
        )
        test.db.locomotionDao().insertUserCorrection(
            EngineUserCorrectionEntity(
                id = "history-clear-correction",
                operationId = "history-clear-operation",
                targetSegmentId = "legacy-track:$deleted",
                kind = "CONFIRM_TRANSPORT",
                createdAtWallTimeMs = TEST_START,
                transportMode = "WALKING",
            ),
        )
    }

    private suspend fun tables(): List<String> = test.db.useReaderConnection { connection ->
        connection.usePrepared(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata', 'room_master_table')",
        ) { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }
    }

    private suspend fun long(sql: String): Long = test.db.useWriterConnection { connection ->
        connection.usePrepared(sql) {
            it.step()
            it.getLong(0)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
