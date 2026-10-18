package io.github.valeronm.breadcrumb.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration32To33Test {
    private val fixture = MigrationDb(32) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY, activityType TEXT NOT NULL, " +
                "startedAt INTEGER NOT NULL, endedAt INTEGER, discardedAt INTEGER, startLat REAL, " +
                "startLon REAL, endLat REAL, endLon REAL, startPlaceId INTEGER, endPlaceId INTEGER, " +
                "pathProcessed INTEGER NOT NULL DEFAULT 1)",
        )
        db.execSQL(
            "CREATE TABLE track_points (id INTEGER NOT NULL PRIMARY KEY, trackId INTEGER NOT NULL, " +
                "timestamp INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, " +
                "altitude REAL, accuracy REAL, speed REAL, bearing REAL, ignored INTEGER NOT NULL, " +
                "ignoreReason TEXT, segmentStart INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE engine_checkpoints (trackId INTEGER NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE engine_observations (id TEXT NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE engine_user_corrections (id TEXT NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE track_compositions (mergedTrackId INTEGER NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE track_composition_parts (id INTEGER NOT NULL PRIMARY KEY)")
        db.execSQL(
            "INSERT INTO tracks(id, activityType, startedAt, endedAt, startLat, startLon, endLat, endLon) " +
                "VALUES (7, 'WALKING', 100, 200, 1, 2, 3, 4)",
        )
        db.execSQL(
            "INSERT INTO track_points(id, trackId, timestamp, latitude, longitude, ignored, segmentStart) " +
                "VALUES (8, 7, 100, 1, 2, 0, 0)",
        )
        db.execSQL("INSERT INTO engine_observations(id) VALUES ('observation')")
        db.execSQL("INSERT INTO engine_user_corrections(id) VALUES ('correction')")
        db.execSQL("INSERT INTO track_compositions(mergedTrackId) VALUES (9)")
        db.execSQL("INSERT INTO track_composition_parts(id) VALUES (10)")
        db.execSQL("INSERT INTO engine_checkpoints(trackId) VALUES (7)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `retires checkpoints while preserving routes observations corrections and composition`() {
        AppDatabase.MIGRATION_32_33.migrate(db)
        db.query(
            "SELECT pathProcessed, pathProcessorVersion, pathRevision, pathProcessedRevision FROM tracks WHERE id = 7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
        }
        assertCount("track_points", 1)
        assertCount("engine_observations", 1)
        assertCount("engine_user_corrections", 1)
        assertCount("track_compositions", 1)
        assertCount("track_composition_parts", 1)
        assertCount("route_analysis_jobs", 0)
        assertCount("review_queue", 0)
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'engine_checkpoints'").use { cursor ->
            assertEquals(false, cursor.moveToFirst())
        }
    }

    private fun assertCount(table: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getInt(0))
        }
    }
}
