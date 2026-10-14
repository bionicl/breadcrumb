package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * An in-memory database opened at a pre-migration schema version, so one migration can run against rows
 * it must carry across. [createSchema] writes the *before* schema by hand — only the tables the migration
 * touches — rather than driving Room's MigrationTestHelper, which reads the schema JSON of the version
 * being migrated *from*, and only v19 onward is exported. `onUpgrade` is deliberately a no-op: the test
 * calls the migration itself, so it sees the same DDL Room would run and can assert on the result.
 * Closed by the test's `@After`.
 */
class MigrationDb(version: Int, createSchema: (SupportSQLiteDatabase) -> Unit) {

    private val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext<Context>())
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = createSchema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build(),
    )

    val db: SupportSQLiteDatabase = helper.writableDatabase

    fun close() = helper.close()
}

/** Current Room schemas may add nullable columns after the migration under test. */
@Suppress("DEPRECATION")
fun assertSchemaContains(currentDb: SupportSQLiteDatabase, migratedDb: SupportSQLiteDatabase, table: String) {
    val current = TableInfo.read(currentDb, table)
    val migrated = TableInfo.read(migratedDb, table)
    assertTrue("$table contains columns unknown to Room", current.columns.keys.containsAll(migrated.columns.keys))
    assertEquals("$table indexes changed", current.indices, migrated.indices)
}
