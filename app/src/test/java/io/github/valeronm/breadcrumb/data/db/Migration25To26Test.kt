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
class Migration25To26Test {
    private val fixture = MigrationDb(25) { db ->
        db.execSQL(
            "CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY, activityType TEXT NOT NULL, " +
                "startedAt INTEGER NOT NULL, endedAt INTEGER)",
        )
        db.execSQL("INSERT INTO tracks VALUES (7, 'WALKING', 1000, 3000)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `adds correction storage and preserves existing track history`() {
        AppDatabase.MIGRATION_25_26.migrate(db)

        db.query("SELECT activityType, startedAt, endedAt FROM tracks WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("WALKING", cursor.getString(0))
            assertEquals(1000L, cursor.getLong(1))
            assertEquals(3000L, cursor.getLong(2))
        }
        db.query("SELECT COUNT(*) FROM engine_user_corrections").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `correction table matches Room including indexes`() {
        AppDatabase.MIGRATION_25_26.migrate(db)
        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertSchemaContains(room.openHelper.writableDatabase, db, "engine_user_corrections")
        } finally {
            room.close()
        }
    }
}
