package io.github.valeronm.breadcrumb.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration27To28Test {
    private val fixture = MigrationDb(27) { db ->
        db.execSQL(
            "CREATE TABLE engine_checkpoints (sessionId TEXT NOT NULL PRIMARY KEY, bootEpochId TEXT NOT NULL, " +
                "lastObservationId TEXT, lastElapsedRealtimeNanos INTEGER, lastWallTimeMs INTEGER, " +
                "motionState TEXT NOT NULL, recordingState TEXT NOT NULL, filteredXmeters REAL, " +
                "filteredYmeters REAL, filteredVelocityXMetersPerSecond REAL, " +
                "filteredVelocityYMetersPerSecond REAL, covarianceXX REAL, covarianceYY REAL, " +
                "filterCovariance BLOB, filterProjectionOriginLatitudeDegrees REAL, " +
                "filterProjectionOriginLongitudeDegrees REAL, activeSegmentId TEXT, " +
                "configurationVersion INTEGER NOT NULL, modelVersion TEXT, updatedAtWallTimeMs INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX index_engine_checkpoints_updatedAtWallTimeMs ON engine_checkpoints(updatedAtWallTimeMs)")
        db.execSQL(
            "INSERT INTO engine_checkpoints VALUES " +
                "('session', 'boot', 'observation', 42, 100, 'MOVING', 'RECORDING', 1, 2, 3, 4, 5, 6, " +
                "NULL, NULL, NULL, 'segment', 1, NULL, 101)",
        )
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test
    fun `adds engine snapshot payload without changing checkpoint metadata`() {
        AppDatabase.MIGRATION_27_28.migrate(db)
        db.query("SELECT sessionId, lastObservationId, engineStateBlob FROM engine_checkpoints").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session", cursor.getString(0))
            assertEquals("observation", cursor.getString(1))
            assertNull(cursor.getBlob(2))
        }
        db.query("SELECT sessionId, filterCovariance FROM engine_checkpoints").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session", cursor.getString(0))
            assertNull(cursor.getBlob(1))
        }
    }
}
