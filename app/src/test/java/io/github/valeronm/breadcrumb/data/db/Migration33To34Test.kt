package io.github.valeronm.breadcrumb.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration33To34Test {
    private val fixture = MigrationDb(33) { db ->
        db.execSQL("CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE route_analysis_jobs (trackId INTEGER NOT NULL PRIMARY KEY)")
        db.execSQL("INSERT INTO tracks(id) VALUES (7)")
        db.execSQL("INSERT INTO route_analysis_jobs(trackId) VALUES (7)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `adds parameter versions and preserves existing route and job rows`() {
        AppDatabase.MIGRATION_33_34.migrate(db)
        db.query("SELECT id, pathParametersVersion FROM tracks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
        }
        db.query("SELECT trackId, parametersVersion FROM route_analysis_jobs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7, cursor.getLong(0))
            assertEquals(1, cursor.getInt(1))
        }
    }
}
