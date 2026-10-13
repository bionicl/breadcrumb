package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration22To23Test {
    private val fixture = MigrationDb(22) { db ->
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
        db.execSQL("INSERT INTO engine_observations VALUES ('o1','s1','b1',100,200,'RAW_GNSS','LIVE',1,-2,5,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,0,0)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `checkpoint table is added without changing persisted observations`() {
        AppDatabase.MIGRATION_22_23.migrate(db)
        db.query("SELECT id, speedMetersPerSecond FROM engine_observations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("o1", c.getString(0))
            assertTrue(c.isNull(1))
        }
        db.query("SELECT COUNT(*) FROM engine_checkpoints").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `checkpoint table after exact state migration matches current Room entity`() {
        AppDatabase.MIGRATION_22_23.migrate(db)
        AppDatabase.MIGRATION_26_27.migrate(db)
        AppDatabase.MIGRATION_27_28.migrate(db)
        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertEquals(
                TableInfo.read(room.openHelper.writableDatabase, "engine_checkpoints"),
                TableInfo.read(db, "engine_checkpoints"),
            )
        } finally {
            room.close()
        }
    }
}
