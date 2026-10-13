package io.github.valeronm.breadcrumb.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration24To25Test {
    private val fixture = MigrationDb(24) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY, activityType TEXT NOT NULL, " +
                "startedAt INTEGER NOT NULL, endedAt INTEGER)",
        )
        db.execSQL("INSERT INTO tracks VALUES (1, 'DRIVING', 1000, 2000)")
        db.execSQL(
            "CREATE TABLE engine_observations (id TEXT NOT NULL PRIMARY KEY, provenance TEXT NOT NULL)",
        )
        db.execSQL("INSERT INTO engine_observations VALUES ('obs-1', 'LIVE')")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `adds correction and suggestion state without changing prior history`() {
        AppDatabase.MIGRATION_24_25.migrate(db)

        db.query(
            "SELECT activityType, startedAt, endedAt, activityConfirmedByUser, " +
                "engineSuggestedActivityType, engineSuggestionConfidence, engineSuggestionModelVersion " +
                "FROM tracks WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("DRIVING", cursor.getString(0))
            assertEquals(1000L, cursor.getLong(1))
            assertEquals(2000L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
        }
        db.query("SELECT id, confirmedTripId FROM engine_observations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("obs-1", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
    }
}
