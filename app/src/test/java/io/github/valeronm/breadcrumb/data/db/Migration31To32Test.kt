package io.github.valeronm.breadcrumb.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration31To32Test {
    private val fixture = MigrationDb(31) { db ->
        db.execSQL("CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE track_points (id INTEGER NOT NULL PRIMARY KEY)")
        db.execSQL("INSERT INTO tracks(id) VALUES (1)")
        db.execSQL("INSERT INTO track_points(id) VALUES (1)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `adds a false marker and nullable output coordinates without changing raw data`() {
        AppDatabase.MIGRATION_31_32.migrate(db)
        db.query("SELECT pathProcessed FROM tracks WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT smoothedLatitude, smoothedLongitude FROM track_points WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
    }
}
