package io.github.valeronm.breadcrumb.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.valeronm.breadcrumb.data.DerivationStore
import io.github.valeronm.breadcrumb.data.ROUTE_ANALYSIS_PARAMETERS_VERSION
import io.github.valeronm.breadcrumb.data.ROUTE_ANALYSIS_VERSION
import io.github.valeronm.breadcrumb.data.StoredDerivation
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.EngineObservationEntity
import io.github.valeronm.breadcrumb.data.db.IDS_PER_STATEMENT
import io.github.valeronm.breadcrumb.data.db.ReviewQueueEntity
import io.github.valeronm.breadcrumb.data.db.RouteAnalysisJobEntity
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackCompositionEntity
import io.github.valeronm.breadcrumb.data.db.TrackCompositionPartEntity
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Read-only bridge used by the host-side debug CLI. This receiver is declared in src/debug only. */
class DebugBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(ID_PATTERN::matches) ?: return
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val directory = File(appContext.cacheDir, DIRECTORY).apply { mkdirs() }
                val output = File(directory, "$requestId.json")
                val temporary = File(directory, "$requestId.tmp")
                val result = runCatching { response(appContext, intent, command, requestId) }
                    .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
                temporary.writeText(result.toString(), Charsets.UTF_8)
                check(temporary.renameTo(output)) { "Could not publish bridge response" }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun response(context: Context, intent: Intent, command: String, requestId: String): JSONObject {
        val db = AppDatabase.get(context)
        val locomotion = db.locomotionDao()
        return when (command) {
            "status" -> {
                val status = locomotion.observeAnalysisQueueStatus(
                    ROUTE_ANALYSIS_VERSION,
                    ROUTE_ANALYSIS_PARAMETERS_VERSION,
                ).first()
                JSONObject().put("ok", true).put("command", command)
                    .put("queued", status.queued).put("running", status.running)
                    .put("failed", status.failed).put("unprocessed", status.unprocessed)
                    .put("processorVersion", ROUTE_ANALYSIS_VERSION)
                    .put("parametersVersion", ROUTE_ANALYSIS_PARAMETERS_VERSION)
            }
            "suggestions" -> {
                val all = locomotion.allReviewItems().sortedByDescending(ReviewQueueEntity::updatedAtWallTimeMs)
                val state = intent.getStringExtra(EXTRA_STATE)
                val limited = all.asSequence().filter { state == null || it.state == state }
                    .take(intent.getIntExtra(EXTRA_LIMIT, DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)).toList()
                JSONObject().put("ok", true).put("command", command).put("count", limited.size)
                    .put("items", JSONArray().apply { limited.forEach { put(it.toJson()) } })
            }
            "day" -> dayResponse(context, intent)
            "logs" -> {
                val log = File(context.cacheDir, "$DIRECTORY/logs-$requestId.txt")
                try {
                    DebugLog.snapshotTo(log)
                    JSONObject().put("ok", true).put("command", command).put("text", log.readText())
                } finally {
                    log.delete()
                }
            }
            else -> JSONObject().put("ok", false).put("error", "Unsupported read-only command")
        }
    }

    private suspend fun dayResponse(context: Context, intent: Intent): JSONObject {
        val day = LocalDate.parse(intent.getStringExtra(EXTRA_DAY) ?: error("Missing day (YYYY-MM-DD)"))
        val zone = ZoneId.of(intent.getStringExtra(EXTRA_TIMEZONE) ?: ZoneId.systemDefault().id)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val db = AppDatabase.get(context)
        val tracks = db.trackDao().tracksOverlapping(start, end, System.currentTimeMillis())
        val ids = tracks.map(Track::id)
        val trackDao = db.trackDao()
        val locomotionDao = db.locomotionDao()
        val points = mutableListOf<TrackPoint>()
        val jobs = mutableListOf<RouteAnalysisJobEntity>()
        val relatedCompositions = mutableListOf<TrackCompositionEntity>()
        val relatedParts = mutableListOf<TrackCompositionPartEntity>()
        for (chunk in ids.chunked(IDS_PER_STATEMENT)) {
            points += trackDao.pointsForTracks(chunk)
            jobs += locomotionDao.analysisJobsForTracks(chunk)
            relatedCompositions += trackDao.compositionsTouching(chunk)
            relatedParts += trackDao.compositionPartsTouching(chunk)
        }
        val observations = db.locomotionDao().observationsBetween(start, end)
        val reviews = db.locomotionDao().reviewItemsBetween(start, end)
        val places = db.placeDao().allPlaces()
        val derivation = DerivationStore(context, db).observeStored().first()
        val derivedJson = derivation.toDayJson(ids.toSet(), start, end)
        val uniqueCompositions = relatedCompositions.distinctBy(TrackCompositionEntity::mergedTrackId)
        val uniqueParts = relatedParts.distinctBy(TrackCompositionPartEntity::id)
        return JSONObject().put("ok", true).put("command", "day").put("date", day.toString())
            .put("timezone", zone.id).put("rangeStartMs", start).put("rangeEndExclusiveMs", end)
            .put("tracks", tracks.toJsonArray { it.toJson() })
            .put("points", points.toJsonArray { it.toJson() })
            .put("engineObservations", observations.toJsonArray { it.toJson() })
            .put("reviewItems", reviews.toJsonArray { it.toJson() })
            .put("analysisJobs", jobs.toJsonArray { it.toJson() })
            .put("derivedIntervals", derivedJson.getJSONArray("derivedIntervals"))
            .put("derivedClusters", derivedJson.getJSONArray("derivedClusters"))
            .put("visitCorrections", derivedJson.getJSONArray("visitCorrections"))
            .put(
                "places",
                places.toJsonArray { place ->
                    JSONObject().put("id", place.id).putNullable("label", place.label)
                        .put("lat", place.lat).put("lon", place.lon).put("radiusM", place.radiusM)
                        .putNullable("category", place.category)
                },
            )
            .put("compositions", uniqueCompositions.toJsonArray { it.toJson() })
            .put("compositionParts", uniqueParts.toJsonArray { it.toJson() })
    }

    private fun Track.toJson() = JSONObject().put("id", id).put("activityType", activityType)
        .put("startedAt", startedAt).putNullable("endedAt", endedAt).putNullable("source", source)
        .put("distanceMeters", distanceMeters).put("pointCount", pointCount).put("ignoredCount", ignoredCount)
        .putNullable("startLat", startLat).putNullable("startLon", startLon)
        .putNullable("endLat", endLat).putNullable("endLon", endLon)
        .putNullable("discardedAt", discardedAt).putNullable("discardReason", discardReason)
        .put("startPlaceId", startPlaceId ?: JSONObject.NULL).put("endPlaceId", endPlaceId ?: JSONObject.NULL)
        .put("activityConfirmedByUser", activityConfirmedByUser)
        .putNullable("engineSuggestedActivityType", engineSuggestedActivityType)
        .putNullable("engineSuggestionConfidence", engineSuggestionConfidence)
        .putNullable("engineSuggestionModelVersion", engineSuggestionModelVersion)
        .put("pathProcessorVersion", pathProcessorVersion).put("pathParametersVersion", pathParametersVersion)
        .put("pathRevision", pathRevision).put("pathProcessedRevision", pathProcessedRevision)

    private fun TrackPoint.toJson() = JSONObject().put("id", id).put("trackId", trackId)
        .put("timestamp", timestamp).put("latitude", latitude).put("longitude", longitude)
        .putNullable("smoothedLatitude", smoothedLatitude).putNullable("smoothedLongitude", smoothedLongitude)
        .putNullable("altitude", altitude).putNullable("accuracy", accuracy).putNullable("speed", speed)
        .putNullable("bearing", bearing).putNullable("verticalAccuracy", verticalAccuracy)
        .putNullable("speedAccuracy", speedAccuracy).putNullable("bearingAccuracy", bearingAccuracy)
        .putNullable("satellitesInFix", satellitesInFix).putNullable("cn0", cn0)
        .put("ignored", ignored).putNullable("ignoreReason", ignoreReason).put("segmentStart", segmentStart)

    private fun EngineObservationEntity.toJson() = JSONObject().put("id", id).put("sessionId", sessionId)
        .put("bootEpochId", bootEpochId).put("wallTimeMs", wallTimeMs)
        .put("elapsedRealtimeNanos", elapsedRealtimeNanos).put("source", source).put("provenance", provenance)
        .putNullable("latitudeDegrees", latitudeDegrees).putNullable("longitudeDegrees", longitudeDegrees)
        .putNullable("horizontalAccuracyMeters", horizontalAccuracyMeters)
        .putNullable("speedMetersPerSecond", speedMetersPerSecond).putNullable("bearingDegrees", bearingDegrees)
        .putNullable("altitudeMeters", altitudeMeters).putNullable("verticalAccuracyMeters", verticalAccuracyMeters)
        .putNullable("sensorWindowStartNanos", sensorWindowStartNanos).putNullable("sensorWindowEndNanos", sensorWindowEndNanos)
        .putNullable("sensorSampleCount", sensorSampleCount).putNullable("accelerationHorizontalMean", accelerationHorizontalMean)
        .putNullable("accelerationHorizontalStdDev", accelerationHorizontalStdDev)
        .putNullable("accelerationVerticalMean", accelerationVerticalMean)
        .putNullable("accelerationVerticalStdDev", accelerationVerticalStdDev).putNullable("stepCadenceHz", stepCadenceHz)
        .putNullable("barometricAltitudeDeltaMeters", barometricAltitudeDeltaMeters).put("missingFeaturesMask", missingFeaturesMask)
        .putNullable("detectedActivity", detectedActivity).putNullable("detectedActivityConfidence", detectedActivityConfidence)
        .putNullable("confirmedTransportMode", confirmedTransportMode).put("qualityFlags", qualityFlags)
        .put("trainingEligible", trainingEligible).putNullable("speedAccuracyMetersPerSecond", speedAccuracyMetersPerSecond)
        .putNullable("confirmedTripId", confirmedTripId).putNullable("confirmedPlaceId", confirmedPlaceId)
        .putNullable("confirmedLocationClass", confirmedLocationClass).put("placeTrainingEligible", placeTrainingEligible)

    private fun ReviewQueueEntity.toJson() = JSONObject().put("id", id).put("kind", kind).put("state", state)
        .putNullable("primaryTrackId", primaryTrackId).putNullable("secondaryTrackId", secondaryTrackId)
        .put("startWallTimeMs", startWallTimeMs).putNullable("endWallTimeMs", endWallTimeMs)
        .putNullable("suggestedValue", suggestedValue).putNullable("currentValue", currentValue)
        .put("reasonCode", reasonCode).put("evidenceKey", evidenceKey).put("inputRevision", inputRevision)
        .put("createdAtWallTimeMs", createdAtWallTimeMs).put("updatedAtWallTimeMs", updatedAtWallTimeMs)
        .putNullable("decision", decision).putNullable("correctedValue", correctedValue)

    private fun RouteAnalysisJobEntity.toJson() = JSONObject().put("trackId", trackId)
        .put("processorVersion", processorVersion).put("parametersVersion", parametersVersion)
        .put("inputRevision", inputRevision).put("state", state).put("attempts", attempts)
        .put("updatedAtWallTimeMs", updatedAtWallTimeMs).putNullable("lastError", lastError)

    private fun TrackCompositionEntity.toJson() = JSONObject().put("mergedTrackId", mergedTrackId)
        .put("earlierTrackId", earlierTrackId).put("laterTrackId", laterTrackId)
        .putNullable("visitAfterTrackId", visitAfterTrackId).putNullable("visitStartMs", visitStartMs)
        .putNullable("visitEndMs", visitEndMs).putNullable("visitCorrectionId", visitCorrectionId)

    private fun TrackCompositionPartEntity.toJson() = JSONObject().put("id", id).put("mergedTrackId", mergedTrackId)
        .put("ordinal", ordinal).put("kind", kind).putNullable("sourceTrackId", sourceTrackId)
        .putNullable("activityType", activityType).put("startedAt", startedAt).putNullable("endedAt", endedAt)
        .putNullable("beforeTrackId", beforeTrackId).putNullable("afterTrackId", afterTrackId)
        .putNullable("visitCorrectionId", visitCorrectionId)

    private fun <T> List<T>.toJsonArray(encode: (T) -> JSONObject) = JSONArray().also { array -> forEach { array.put(encode(it)) } }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private companion object {
        const val ACTION = "io.github.valeronm.breadcrumb.DEBUG_BRIDGE"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_DAY = "day"
        const val EXTRA_TIMEZONE = "timezone"
        const val EXTRA_STATE = "state"
        const val EXTRA_LIMIT = "limit"
        const val DIRECTORY = "debug-bridge"
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 2000
        val ID_PATTERN = Regex("[a-f0-9-]{36}")
    }
}

/** Derived context and user decisions needed to explain a day's review suggestions. */
internal fun StoredDerivation.toDayJson(trackIds: Set<Long>, startMs: Long, endMs: Long): JSONObject {
    val relevantIntervals = intervals.filter {
        it.afterTrackId in trackIds || it.start < endMs && it.endedAt >= startMs
    }
    val relatedAnchors = trackIds + relevantIntervals.map { it.afterTrackId }
    val relatedCorrections = visitCorrections.filter { correction ->
        correction.targetSegmentId.removePrefix("derived-visit:").toLongOrNull() in relatedAnchors
    }
    val relatedClusterIds = relevantIntervals.flatMap { interval ->
        listOfNotNull(interval.clusterId, interval.fromClusterId, interval.toClusterId)
    }.toSet()
    fun <T> List<T>.jsonArray(encode: (T) -> JSONObject) = JSONArray().also { array ->
        forEach { array.put(encode(it)) }
    }
    fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
    val intervalJson = relevantIntervals.jsonArray { interval ->
        JSONObject().put("type", interval.type).put("start", interval.start)
            .put("endedAt", interval.endedAt).put("afterTrackId", interval.afterTrackId)
            .putNullable("clusterId", interval.clusterId).putNullable("reason", interval.reason)
            .putNullable("fromClusterId", interval.fromClusterId).putNullable("toClusterId", interval.toClusterId)
            .putNullable("fromLat", interval.fromLat).putNullable("fromLon", interval.fromLon)
            .putNullable("toLat", interval.toLat).putNullable("toLon", interval.toLon)
    }
    val clusterJson = clusters.filter { it.id in relatedClusterIds }.jsonArray { cluster ->
        JSONObject().put("id", cluster.id).putNullable("placeId", cluster.placeId)
            .put("anchorLat", cluster.anchorLat).put("anchorLon", cluster.anchorLon)
            .put("radiusM", cluster.radiusM).put("memberCount", cluster.memberCount)
    }
    val correctionJson = relatedCorrections.jsonArray { correction ->
        JSONObject().put("id", correction.id).put("operationId", correction.operationId)
            .put("targetSegmentId", correction.targetSegmentId).put("kind", correction.kind)
            .put("createdAtWallTimeMs", correction.createdAtWallTimeMs)
            .putNullable("referenceCorrectionId", correction.referenceCorrectionId)
            .putNullable("correctedPlaceId", correction.correctedPlaceId)
            .putNullable("visitStartWallTimeMs", correction.visitStartWallTimeMs)
            .putNullable("visitEndWallTimeMs", correction.visitEndWallTimeMs)
            .putNullable("boundaryKind", correction.boundaryKind)
    }
    return JSONObject().put("derivedIntervals", intervalJson)
        .put("derivedClusters", clusterJson).put("visitCorrections", correctionJson)
}
