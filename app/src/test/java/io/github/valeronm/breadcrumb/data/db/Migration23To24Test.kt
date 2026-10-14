package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration23To24Test {
    private val fixture = MigrationDb(23) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY, activityType TEXT NOT NULL, " +
                "startedAt INTEGER NOT NULL, endedAt INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE engine_observations (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, " +
                "bootEpochId TEXT NOT NULL, wallTimeMs INTEGER NOT NULL, elapsedRealtimeNanos INTEGER NOT NULL, " +
                "source TEXT NOT NULL, provenance TEXT NOT NULL, latitudeDegrees REAL, longitudeDegrees REAL, " +
                "horizontalAccuracyMeters REAL, speedMetersPerSecond REAL, bearingDegrees REAL, altitudeMeters REAL, " +
                "verticalAccuracyMeters REAL, sensorWindowStartNanos INTEGER, sensorWindowEndNanos INTEGER, " +
                "sensorSampleCount INTEGER, accelerationHorizontalMean REAL, accelerationHorizontalStdDev REAL, " +
                "accelerationVerticalMean REAL, accelerationVerticalStdDev REAL, stepCadenceHz REAL, " +
                "barometricAltitudeDeltaMeters REAL, missingFeaturesMask INTEGER NOT NULL, detectedActivity TEXT, " +
                "detectedActivityConfidence INTEGER, confirmedTransportMode TEXT, qualityFlags INTEGER NOT NULL, " +
                "trainingEligible INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX index_engine_observations_sessionId_elapsedRealtimeNanos_source " +
                "ON engine_observations(sessionId, elapsedRealtimeNanos, source)",
        )
        db.execSQL("CREATE INDEX index_engine_observations_wallTimeMs ON engine_observations(wallTimeMs)")
        db.execSQL(
            "CREATE INDEX index_engine_observations_provenance_trainingEligible_source " +
                "ON engine_observations(provenance, trainingEligible, source)",
        )
        db.execSQL(
            "INSERT INTO engine_observations (id, sessionId, bootEpochId, wallTimeMs, elapsedRealtimeNanos, " +
                "source, provenance, missingFeaturesMask, qualityFlags, trainingEligible) " +
                "VALUES ('obs-1', 'session-1', 'boot-1', 1000, 2000, 'RAW_GNSS', 'LIVE', 0, 0, 0)",
        )
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `adds optional speed accuracy and keeps earlier observations`() {
        AppDatabase.MIGRATION_23_24.migrate(db)
        db.query("SELECT id, speedMetersPerSecond, speedAccuracyMetersPerSecond FROM engine_observations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("obs-1", c.getString(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `migrated observation table matches Room`() {
        AppDatabase.MIGRATION_23_24.migrate(db)
        AppDatabase.MIGRATION_24_25.migrate(db)
        AppDatabase.MIGRATION_25_26.migrate(db)
        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertSchemaContains(room.openHelper.writableDatabase, db, "engine_observations")
            assertSchemaContains(room.openHelper.writableDatabase, db, "engine_user_corrections")
        } finally {
            room.close()
        }
    }
}
