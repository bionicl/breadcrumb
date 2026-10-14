package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration28To29Test {
    private val fixture = MigrationDb(28) { db ->
        db.execSQL("CREATE TABLE engine_user_corrections (id TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, targetSegmentId TEXT NOT NULL, kind TEXT NOT NULL, createdAtWallTimeMs INTEGER NOT NULL, transportMode TEXT, boundaryKind TEXT, atWallTimeMs INTEGER, referenceCorrectionId TEXT)")
        db.execSQL("CREATE INDEX index_engine_user_corrections_operationId ON engine_user_corrections(operationId)")
        db.execSQL("CREATE INDEX index_engine_user_corrections_targetSegmentId_createdAtWallTimeMs ON engine_user_corrections(targetSegmentId, createdAtWallTimeMs)")
        db.execSQL("INSERT INTO engine_user_corrections VALUES ('correction', 'op', 'derived-visit:1', 'MARK_VISIT_BOGUS', 12, NULL, NULL, NULL, NULL)")
        db.execSQL("CREATE TABLE engine_observations (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, bootEpochId TEXT NOT NULL, wallTimeMs INTEGER NOT NULL, elapsedRealtimeNanos INTEGER NOT NULL, source TEXT NOT NULL, provenance TEXT NOT NULL, latitudeDegrees REAL, longitudeDegrees REAL, horizontalAccuracyMeters REAL, speedMetersPerSecond REAL, bearingDegrees REAL, altitudeMeters REAL, verticalAccuracyMeters REAL, sensorWindowStartNanos INTEGER, sensorWindowEndNanos INTEGER, sensorSampleCount INTEGER, accelerationHorizontalMean REAL, accelerationHorizontalStdDev REAL, accelerationVerticalMean REAL, accelerationVerticalStdDev REAL, stepCadenceHz REAL, barometricAltitudeDeltaMeters REAL, missingFeaturesMask INTEGER NOT NULL, detectedActivity TEXT, detectedActivityConfidence INTEGER, confirmedTransportMode TEXT, qualityFlags INTEGER NOT NULL, trainingEligible INTEGER NOT NULL, speedAccuracyMetersPerSecond REAL, confirmedTripId TEXT)")
        db.execSQL("CREATE INDEX index_engine_observations_sessionId_elapsedRealtimeNanos_source ON engine_observations(sessionId, elapsedRealtimeNanos, source)")
        db.execSQL("CREATE INDEX index_engine_observations_wallTimeMs ON engine_observations(wallTimeMs)")
        db.execSQL("CREATE INDEX index_engine_observations_provenance_trainingEligible_source ON engine_observations(provenance, trainingEligible, source)")
        db.execSQL("INSERT INTO engine_observations (id, sessionId, bootEpochId, wallTimeMs, elapsedRealtimeNanos, source, provenance, missingFeaturesMask, qualityFlags, trainingEligible) VALUES ('sample', 'session', 'boot', 12, 15, 'RAW_GNSS', 'LIVE', 0, 0, 0)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Suppress("DEPRECATION")
    @Test
    fun `adds correction and learning columns while retaining existing rows`() {
        AppDatabase.MIGRATION_28_29.migrate(db)
        db.query("SELECT id, targetSegmentId, correctedPlaceId, visitStartWallTimeMs FROM engine_user_corrections").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("correction", cursor.getString(0))
            assertEquals("derived-visit:1", cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
        }
        db.query("SELECT id, confirmedPlaceId, confirmedLocationClass, placeTrainingEligible FROM engine_observations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sample", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertFalse(cursor.getInt(3) != 0)
        }
        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertEquals(
                TableInfo.read(room.openHelper.writableDatabase, "engine_user_corrections"),
                TableInfo.read(db, "engine_user_corrections"),
            )
            assertEquals(
                TableInfo.read(room.openHelper.writableDatabase, "engine_observations"),
                TableInfo.read(db, "engine_observations"),
            )
        } finally {
            room.close()
        }
    }
}
