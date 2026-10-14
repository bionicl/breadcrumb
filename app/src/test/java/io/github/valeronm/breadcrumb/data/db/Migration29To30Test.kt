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
class Migration29To30Test {
    private val fixture = MigrationDb(29) { db ->
        db.execSQL("CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Suppress("DEPRECATION")
    @Test
    fun `creates durable merge provenance without changing existing tracks`() {
        db.execSQL("INSERT INTO tracks VALUES (7)")
        AppDatabase.MIGRATION_29_30.migrate(db)
        db.execSQL(
            "INSERT INTO track_compositions VALUES (7, 1, 2, 1, 100, 200, 'correction')",
        )
        db.query("SELECT mergedTrackId, earlierTrackId, laterTrackId, visitStartMs, visitEndMs, visitCorrectionId FROM track_compositions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals(2L, cursor.getLong(2))
            assertEquals(100L, cursor.getLong(3))
            assertEquals(200L, cursor.getLong(4))
            assertEquals("correction", cursor.getString(5))
        }
        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertEquals(
                TableInfo.read(room.openHelper.writableDatabase, "track_compositions"),
                TableInfo.read(db, "track_compositions"),
            )
        } finally {
            room.close()
        }
    }
}
