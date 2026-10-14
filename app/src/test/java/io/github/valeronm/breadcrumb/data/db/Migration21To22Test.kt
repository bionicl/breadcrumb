package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v22 adds engine observations and checkpoints while leaving every legacy row in place. */
@RunWith(RobolectricTestRunner::class)
class Migration21To22Test {
    private val fixture = MigrationDb(21) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, activityType TEXT NOT NULL, " +
                "startedAt INTEGER NOT NULL, endedAt INTEGER, source TEXT, distanceMeters REAL NOT NULL, " +
                "pointCount INTEGER NOT NULL, ignoredCount INTEGER NOT NULL, startLat REAL, startLon REAL, " +
                "endLat REAL, endLon REAL, discardedAt INTEGER, discardReason TEXT, needsReview INTEGER NOT NULL, " +
                "startPlaceId INTEGER, endPlaceId INTEGER)",
        )
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, distanceMeters, pointCount, ignoredCount, needsReview) " +
                "VALUES (7, 'DRIVING', 100, 812.5, 40, 2, 0)",
        )
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `adds empty engine tables and preserves legacy history`() {
        AppDatabase.MIGRATION_21_22.migrate(db)

        db.query("SELECT id, activityType, distanceMeters FROM tracks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(7L, c.getLong(0))
            assertEquals("DRIVING", c.getString(1))
            assertEquals(812.5, c.getDouble(2), 0.0)
            assertFalse(c.moveToNext())
        }
        for (table in listOf("engine_observations")) {
            db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `full upgrade brings new tables to the current Room entity shape`() {
        AppDatabase.MIGRATION_21_22.migrate(db)
        AppDatabase.MIGRATION_22_23.migrate(db)
        AppDatabase.MIGRATION_23_24.migrate(db)
        AppDatabase.MIGRATION_24_25.migrate(db)
        AppDatabase.MIGRATION_25_26.migrate(db)
        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            for (table in listOf("engine_observations", "engine_user_corrections")) {
                assertSchemaContains(generated, db, table)
            }
        } finally {
            room.close()
        }
    }
}
