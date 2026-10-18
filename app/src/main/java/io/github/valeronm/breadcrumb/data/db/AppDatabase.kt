package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Track::class, TrackPoint::class, Place::class, PlaceIdentity::class,
        DerivedCluster::class, ClusterMember::class, DerivedInterval::class,
        EngineObservationEntity::class, EngineUserCorrectionEntity::class,
        RouteAnalysisJobEntity::class, ReviewQueueEntity::class,
        TrackCompositionEntity::class, TrackCompositionPartEntity::class,
    ],
    version = 34,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun placeDao(): PlaceDao
    abstract fun placeIdentityDao(): PlaceIdentityDao
    abstract fun derivedDao(): DerivedDao
    abstract fun locomotionDao(): LocomotionDao

    /** Every table in this database is history. Settings live elsewhere and stay. */
    suspend fun clearHistory() = useWriterConnection { connection ->
        val secureDelete = connection.usePrepared("PRAGMA secure_delete") {
            it.step()
            it.getLong(0)
        }
        // A table that is a foreign-key parent or child is deleted a row at a time, through the
        // cascades and every index, where with the keys off SQLite truncates it. The pragma holds
        // per connection and is a no-op inside a transaction.
        connection.execSQL("PRAGMA foreign_keys = OFF")
        // Under the file's auto_vacuum the freed pages are cut off its end at checkpoint, so
        // zeroing them first protects nothing.
        connection.execSQL("PRAGMA secure_delete = FAST")
        try {
            connection.immediateTransaction {
                for (table in HISTORY_TABLES) execSQL("DELETE FROM `$table`")
            }
        } finally {
            connection.execSQL("PRAGMA secure_delete = $secureDelete")
            connection.execSQL("PRAGMA foreign_keys = ON")
        }
    }

    companion object {
        private val HISTORY_TABLES = listOf(
            "tracks", "track_points", "places", "place_identities",
            "derived_clusters", "cluster_members", "derived_intervals",
            "engine_observations", "engine_user_corrections", "route_analysis_jobs", "review_queue", "track_compositions",
            "track_composition_parts",
        )

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v19 retires the liveness log: the `liveness_events` table goes, and `derived_intervals`
         * loses its `provenance` column — observed/inferred described the app's attention, not
         * where anyone was, and nothing rendered it. The trailing stay's disarm bound, the one
         * user-visible fact the log fed, now lives as a timestamp in [Settings]. The log's own
         * trailing disarm is deliberately not carried into that timestamp: an install upgrading
         * while disarmed shows its trailing stay open until the next disarm writes one, which was
         * judged cheaper than a one-shot backfill for a value the next toggle re-establishes.
         * minSdk 26's SQLite
         * predates `ALTER TABLE … DROP COLUMN`, so the table is rebuilt and its rows copied — they
         * are unchanged by the column going, so no re-derivation follows.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS liveness_events")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS derived_intervals_new (
                      type TEXT NOT NULL,
                      start INTEGER NOT NULL,
                      endedAt INTEGER NOT NULL,
                      afterTrackId INTEGER NOT NULL,
                      clusterId INTEGER,
                      reason TEXT,
                      fromClusterId INTEGER, toClusterId INTEGER,
                      fromLat REAL, fromLon REAL,
                      toLat REAL, toLon REAL,
                      PRIMARY KEY(afterTrackId) )
                    """,
                )
                db.execSQL(
                    """
                    INSERT INTO derived_intervals_new
                        (type, start, endedAt, afterTrackId, clusterId, reason,
                         fromClusterId, toClusterId, fromLat, fromLon, toLat, toLon)
                    SELECT type, start, endedAt, afterTrackId, clusterId, reason,
                           fromClusterId, toClusterId, fromLat, fromLon, toLat, toLon
                    FROM derived_intervals
                    """,
                )
                db.execSQL("DROP TABLE derived_intervals")
                db.execSQL("ALTER TABLE derived_intervals_new RENAME TO derived_intervals")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_derived_intervals_start " +
                        "ON derived_intervals(start)",
                )
            }
        }

        /**
         * A place may have no name and may carry where it came from; a track's ends may be stated
         * to a place. minSdk 26's SQLite cannot relax a NOT NULL in place, so `places` is rebuilt;
         * the track columns are nullable references, which `ADD COLUMN` accepts.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS places_new (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      label TEXT,
                      lat REAL NOT NULL,
                      lon REAL NOT NULL,
                      createdAt INTEGER NOT NULL,
                      radiusM REAL NOT NULL,
                      category TEXT,
                      externalProvider TEXT,
                      externalId TEXT )
                    """,
                )
                db.execSQL(
                    "INSERT INTO places_new (id, label, lat, lon, createdAt, radiusM, category) " +
                        "SELECT id, label, lat, lon, createdAt, radiusM, category FROM places",
                )
                db.execSQL("DROP TABLE places")
                db.execSQL("ALTER TABLE places_new RENAME TO places")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_places_externalProvider_externalId " +
                        "ON places(externalProvider, externalId)",
                )
                for (column in listOf("startPlaceId", "endPlaceId")) {
                    db.execSQL(
                        "ALTER TABLE tracks ADD COLUMN $column INTEGER " +
                            "REFERENCES places(id) ON UPDATE NO ACTION ON DELETE SET NULL",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_$column ON tracks($column)")
                }
            }
        }

        /**
         * A place's identities at other sources move to `place_identities`, where a place can hold
         * several and a merge can hand them on, and `places` names who last wrote the row instead.
         * The migration runs before Room turns foreign keys on, so rebuilding `places` leaves the
         * track ends stated to it in place.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS place_identities (
                      provider TEXT NOT NULL,
                      externalId TEXT NOT NULL,
                      placeId INTEGER NOT NULL,
                      PRIMARY KEY(provider, externalId),
                      FOREIGN KEY(placeId) REFERENCES places(id) ON UPDATE NO ACTION ON DELETE CASCADE )
                    """,
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_identities_placeId ON place_identities(placeId)")
                db.execSQL(
                    "INSERT INTO place_identities (provider, externalId, placeId) " +
                        "SELECT externalProvider, externalId, id FROM places " +
                        "WHERE externalProvider IS NOT NULL AND externalId IS NOT NULL",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS places_new (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      label TEXT,
                      lat REAL NOT NULL,
                      lon REAL NOT NULL,
                      createdAt INTEGER NOT NULL,
                      radiusM REAL NOT NULL,
                      category TEXT,
                      source TEXT )
                    """,
                )
                // The Google Timeline import is the only writer that ever set a provider.
                db.execSQL(
                    "INSERT INTO places_new (id, label, lat, lon, createdAt, radiusM, category, source) " +
                        "SELECT id, label, lat, lon, createdAt, radiusM, category, " +
                        "CASE WHEN externalProvider = 'google' THEN 'google_timeline' ELSE 'manual' END FROM places",
                )
                db.execSQL("DROP TABLE places")
                db.execSQL("ALTER TABLE places_new RENAME TO places")
            }
        }

        /** Persist engine observations independently from the legacy track history. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS engine_observations (
                      id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, bootEpochId TEXT NOT NULL,
                      wallTimeMs INTEGER NOT NULL, elapsedRealtimeNanos INTEGER NOT NULL,
                      source TEXT NOT NULL, provenance TEXT NOT NULL, latitudeDegrees REAL, longitudeDegrees REAL,
                      horizontalAccuracyMeters REAL, speedMetersPerSecond REAL, bearingDegrees REAL,
                      altitudeMeters REAL, verticalAccuracyMeters REAL, sensorWindowStartNanos INTEGER,
                      sensorWindowEndNanos INTEGER, sensorSampleCount INTEGER, accelerationHorizontalMean REAL,
                      accelerationHorizontalStdDev REAL, accelerationVerticalMean REAL,
                      accelerationVerticalStdDev REAL, stepCadenceHz REAL, barometricAltitudeDeltaMeters REAL,
                      missingFeaturesMask INTEGER NOT NULL, detectedActivity TEXT, detectedActivityConfidence INTEGER,
                      confirmedTransportMode TEXT, qualityFlags INTEGER NOT NULL, trainingEligible INTEGER NOT NULL)
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_engine_observations_sessionId_elapsedRealtimeNanos_source " +
                        "ON engine_observations(sessionId, elapsedRealtimeNanos, source)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_engine_observations_wallTimeMs " +
                        "ON engine_observations(wallTimeMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_engine_observations_provenance_trainingEligible_source " +
                        "ON engine_observations(provenance, trainingEligible, source)",
                )
            }
        }

        /** Keep the engine's accepted state alongside the observations it has consumed. */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS engine_checkpoints (
                      sessionId TEXT NOT NULL PRIMARY KEY, bootEpochId TEXT NOT NULL, lastObservationId TEXT,
                      lastElapsedRealtimeNanos INTEGER, lastWallTimeMs INTEGER, motionState TEXT NOT NULL,
                      recordingState TEXT NOT NULL, filteredXmeters REAL, filteredYmeters REAL,
                      filteredVelocityXMetersPerSecond REAL, filteredVelocityYMetersPerSecond REAL,
                      covarianceXX REAL, covarianceYY REAL, activeSegmentId TEXT, configurationVersion INTEGER NOT NULL,
                      modelVersion TEXT, updatedAtWallTimeMs INTEGER NOT NULL)
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_engine_checkpoints_updatedAtWallTimeMs " +
                        "ON engine_checkpoints(updatedAtWallTimeMs)",
                )
            }
        }

        /** Preserve reported velocity uncertainty for the ENU filter (LK-02). */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE engine_observations ADD COLUMN speedAccuracyMetersPerSecond REAL")
            }
        }

        /** Separate explicit transport confirmation and model suggestion from the accepted label. */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN activityConfirmedByUser INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN engineSuggestedActivityType TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN engineSuggestionConfidence REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN engineSuggestionModelVersion TEXT")
                db.execSQL("ALTER TABLE engine_observations ADD COLUMN confirmedTripId TEXT")
            }
        }

        /** Keep human transport, visit and boundary assertions available to future engine replay. */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS engine_user_corrections (" +
                        "id TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, " +
                        "targetSegmentId TEXT NOT NULL, kind TEXT NOT NULL, " +
                        "createdAtWallTimeMs INTEGER NOT NULL, transportMode TEXT, boundaryKind TEXT, " +
                        "atWallTimeMs INTEGER, referenceCorrectionId TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_engine_user_corrections_operationId " +
                        "ON engine_user_corrections(operationId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_engine_user_corrections_targetSegmentId_createdAtWallTimeMs " +
                        "ON engine_user_corrections(targetSegmentId, createdAtWallTimeMs)",
                )
            }
        }

        /** Store the full local projection and covariance required to resume the LK-02 filter exactly. */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE engine_checkpoints ADD COLUMN filterCovariance BLOB")
                db.execSQL("ALTER TABLE engine_checkpoints ADD COLUMN filterProjectionOriginLatitudeDegrees REAL")
                db.execSQL("ALTER TABLE engine_checkpoints ADD COLUMN filterProjectionOriginLongitudeDegrees REAL")
            }
        }

        /** Store detector hysteresis and cached sensor/activity evidence with the location filter. */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE engine_checkpoints ADD COLUMN engineStateBlob BLOB")
            }
        }

        /** Durable per-visit corrections and explicit local place-learning labels. */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE engine_user_corrections ADD COLUMN correctedPlaceId INTEGER")
                db.execSQL("ALTER TABLE engine_user_corrections ADD COLUMN visitStartWallTimeMs INTEGER")
                db.execSQL("ALTER TABLE engine_user_corrections ADD COLUMN visitEndWallTimeMs INTEGER")
                db.execSQL("ALTER TABLE engine_observations ADD COLUMN confirmedPlaceId INTEGER")
                db.execSQL("ALTER TABLE engine_observations ADD COLUMN confirmedLocationClass TEXT")
                db.execSQL("ALTER TABLE engine_observations ADD COLUMN placeTrainingEligible INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Preserve the component tracks and stationary interval behind a merged movement row. */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_compositions (
                      mergedTrackId INTEGER NOT NULL,
                      earlierTrackId INTEGER NOT NULL,
                      laterTrackId INTEGER NOT NULL,
                      visitAfterTrackId INTEGER NOT NULL,
                      visitStartMs INTEGER NOT NULL,
                      visitEndMs INTEGER NOT NULL,
                      visitCorrectionId TEXT,
                      PRIMARY KEY(mergedTrackId),
                      FOREIGN KEY(mergedTrackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """,
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_compositions_earlierTrackId " +
                        "ON track_compositions(earlierTrackId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_compositions_laterTrackId " +
                        "ON track_compositions(laterTrackId)",
                )
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_composition_parts (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      mergedTrackId INTEGER NOT NULL,
                      ordinal INTEGER NOT NULL,
                      kind TEXT NOT NULL,
                      sourceTrackId INTEGER,
                      activityType TEXT,
                      startedAt INTEGER NOT NULL,
                      endedAt INTEGER NOT NULL,
                      beforeTrackId INTEGER,
                      afterTrackId INTEGER,
                      visitCorrectionId TEXT,
                      FOREIGN KEY(mergedTrackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_composition_parts_mergedTrackId ON track_composition_parts(mergedTrackId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_composition_parts_sourceTrackId ON track_composition_parts(sourceTrackId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_composition_parts_beforeTrackId ON track_composition_parts(beforeTrackId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_composition_parts_afterTrackId ON track_composition_parts(afterTrackId)")
                // Existing two-path compositions keep the same undo and editor behavior, represented
                // now as an ordered path / stationary / path sequence.
                db.execSQL(
                    """
                    INSERT INTO track_composition_parts
                      (mergedTrackId, ordinal, kind, sourceTrackId, activityType, startedAt, endedAt,
                       beforeTrackId, afterTrackId, visitCorrectionId)
                    SELECT c.mergedTrackId, 0, 'PATH', c.earlierTrackId, t.activityType, t.startedAt, COALESCE(t.endedAt, t.startedAt),
                           NULL, NULL, NULL
                    FROM track_compositions c JOIN tracks t ON t.id = c.earlierTrackId
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO track_composition_parts
                      (mergedTrackId, ordinal, kind, sourceTrackId, activityType, startedAt, endedAt,
                       beforeTrackId, afterTrackId, visitCorrectionId)
                    SELECT c.mergedTrackId, 1, 'STATIONARY', NULL, 'STILL', c.visitStartMs, c.visitEndMs,
                           c.earlierTrackId, c.laterTrackId, c.visitCorrectionId
                    FROM track_compositions c
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO track_composition_parts
                      (mergedTrackId, ordinal, kind, sourceTrackId, activityType, startedAt, endedAt,
                       beforeTrackId, afterTrackId, visitCorrectionId)
                    SELECT c.mergedTrackId, 2, 'PATH', c.laterTrackId, t.activityType, t.startedAt, COALESCE(t.endedAt, t.startedAt),
                           NULL, NULL, NULL
                    FROM track_compositions c JOIN tracks t ON t.id = c.laterTrackId
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN pathProcessed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE track_points ADD COLUMN smoothedLatitude REAL")
                db.execSQL("ALTER TABLE track_points ADD COLUMN smoothedLongitude REAL")
            }
        }

        /** Retire live engine checkpoints and add resumable analysis jobs and human review items. */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN pathProcessorVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN pathRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN pathProcessedRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP TABLE IF EXISTS engine_checkpoints")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS route_analysis_jobs (" +
                        "trackId INTEGER NOT NULL, processorVersion INTEGER NOT NULL, inputRevision INTEGER NOT NULL, " +
                        "state TEXT NOT NULL, attempts INTEGER NOT NULL, updatedAtWallTimeMs INTEGER NOT NULL, " +
                        "lastError TEXT, PRIMARY KEY(trackId, processorVersion, inputRevision))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_route_analysis_jobs_state_updatedAtWallTimeMs ON route_analysis_jobs(state, updatedAtWallTimeMs)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS review_queue (" +
                        "id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL, " +
                        "primaryTrackId INTEGER, secondaryTrackId INTEGER, startWallTimeMs INTEGER NOT NULL, " +
                        "endWallTimeMs INTEGER, suggestedValue TEXT, currentValue TEXT, reasonCode TEXT NOT NULL, " +
                        "evidenceKey TEXT NOT NULL, inputRevision INTEGER NOT NULL, createdAtWallTimeMs INTEGER NOT NULL, " +
                        "updatedAtWallTimeMs INTEGER NOT NULL, decision TEXT, correctedValue TEXT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_review_queue_evidenceKey ON review_queue(evidenceKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_queue_state_updatedAtWallTimeMs ON review_queue(state, updatedAtWallTimeMs)")
                db.execSQL(
                    "CREATE TRIGGER track_revision_update AFTER UPDATE OF activityType, startedAt, endedAt, discardedAt, " +
                        "startLat, startLon, endLat, endLon, startPlaceId, endPlaceId ON tracks " +
                        "BEGIN UPDATE tracks SET pathRevision = pathRevision + 1 WHERE id = NEW.id; END",
                )
                db.execSQL("CREATE TRIGGER point_revision_insert AFTER INSERT ON track_points BEGIN UPDATE tracks SET pathRevision = pathRevision + 1 WHERE id = NEW.trackId; END")
                db.execSQL("CREATE TRIGGER point_revision_delete AFTER DELETE ON track_points BEGIN UPDATE tracks SET pathRevision = pathRevision + 1 WHERE id = OLD.trackId; END")
                db.execSQL(
                    "CREATE TRIGGER point_revision_update AFTER UPDATE OF trackId, timestamp, latitude, longitude, altitude, " +
                        "accuracy, speed, bearing, ignored, ignoreReason, segmentStart ON track_points " +
                        "WHEN OLD.trackId IS NOT NEW.trackId OR OLD.timestamp IS NOT NEW.timestamp OR " +
                        "OLD.latitude IS NOT NEW.latitude OR OLD.longitude IS NOT NEW.longitude OR " +
                        "OLD.accuracy IS NOT NEW.accuracy OR OLD.speed IS NOT NEW.speed OR " +
                        "OLD.bearing IS NOT NEW.bearing OR OLD.ignored IS NOT NEW.ignored OR " +
                        "OLD.ignoreReason IS NOT NEW.ignoreReason OR OLD.segmentStart IS NOT NEW.segmentStart " +
                        "BEGIN UPDATE tracks SET pathRevision = pathRevision + 1 WHERE id = NEW.trackId; END",
                )
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN pathParametersVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE route_analysis_jobs ADD COLUMN parametersVersion INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * The list the builder spreads, in order; the next migration is appended here.
         *
         * v18 is the floor: a database older than that fails to open rather than migrating.
         * Deliberately — the crash leaves `tracks.db` intact for the previous build to read and
         * export, where a destructive fallback would discard a history nothing else holds. The
         * `version` above never renumbers downward for the same reason, since every installed
         * database would then present itself as a downgrade.
         */
        private val MIGRATIONS = arrayOf(
            MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
            MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
            MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32,
            MIGRATION_32_33, MIGRATION_33_34,
        )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tracks.db",
                ).addMigrations(*MIGRATIONS).build().also { instance = it }
            }
    }
}
