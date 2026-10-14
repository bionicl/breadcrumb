package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration30To31Test {
    private val fixture = MigrationDb(30) { db ->
        db.execSQL("CREATE TABLE tracks (id INTEGER NOT NULL PRIMARY KEY, activityType TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER)")
        db.execSQL("CREATE TABLE track_compositions (mergedTrackId INTEGER NOT NULL PRIMARY KEY, earlierTrackId INTEGER NOT NULL, laterTrackId INTEGER NOT NULL, visitAfterTrackId INTEGER NOT NULL, visitStartMs INTEGER NOT NULL, visitEndMs INTEGER NOT NULL, visitCorrectionId TEXT)")
    }
    private val db get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Suppress("DEPRECATION")
    @Test fun `backfills legacy compositions as ordered parts and creates Room-compatible table`() {
        db.execSQL("INSERT INTO tracks VALUES (1, 'WALKING', 100, 200)")
        db.execSQL("INSERT INTO tracks VALUES (2, 'WALKING', 400, 500)")
        db.execSQL("INSERT INTO tracks VALUES (3, 'WALKING', 100, 500)")
        db.execSQL("INSERT INTO track_compositions VALUES (3, 1, 2, 1, 250, 350, 'visit')")

        AppDatabase.MIGRATION_30_31.migrate(db)

        db.query("SELECT ordinal, kind, sourceTrackId, startedAt, endedAt, visitCorrectionId FROM track_composition_parts WHERE mergedTrackId = 3 ORDER BY ordinal").use { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.moveToNext()) {
                rows += listOf(
                    cursor.getInt(0).toString(), cursor.getString(1),
                    if (cursor.isNull(2)) null else cursor.getLong(2).toString(),
                    cursor.getLong(3).toString(), cursor.getLong(4).toString(), cursor.getString(5),
                )
            }
            assertEquals(
                listOf(
                    listOf("0", "PATH", "1", "100", "200", null),
                    listOf("1", "STATIONARY", null, "250", "350", "visit"),
                    listOf("2", "PATH", "2", "400", "500", null),
                ),
                rows,
            )
        }

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertEquals(
                TableInfo.read(room.openHelper.writableDatabase, "track_composition_parts"),
                TableInfo.read(db, "track_composition_parts"),
            )
        } finally {
            room.close()
        }
    }
}
