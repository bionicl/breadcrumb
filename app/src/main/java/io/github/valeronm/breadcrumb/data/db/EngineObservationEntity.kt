package io.github.valeronm.breadcrumb.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A sensor/location observation independent of the legacy track rows (LocoKit2 LK-01). */
@Entity(
    tableName = "engine_observations",
    indices = [
        Index(value = ["sessionId", "elapsedRealtimeNanos", "source"]),
        Index(value = ["wallTimeMs"]),
        Index(value = ["provenance", "trainingEligible", "source"]),
    ],
)
data class EngineObservationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val bootEpochId: String,
    val wallTimeMs: Long,
    val elapsedRealtimeNanos: Long,
    val source: String,
    val provenance: String,
    val latitudeDegrees: Double?,
    val longitudeDegrees: Double?,
    val horizontalAccuracyMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val altitudeMeters: Double?,
    val verticalAccuracyMeters: Double?,
    val sensorWindowStartNanos: Long?,
    val sensorWindowEndNanos: Long?,
    val sensorSampleCount: Int?,
    val accelerationHorizontalMean: Double?,
    val accelerationHorizontalStdDev: Double?,
    val accelerationVerticalMean: Double?,
    val accelerationVerticalStdDev: Double?,
    val stepCadenceHz: Double?,
    val barometricAltitudeDeltaMeters: Double?,
    val missingFeaturesMask: Int,
    val detectedActivity: String?,
    val detectedActivityConfidence: Int?,
    val confirmedTransportMode: String?,
    val qualityFlags: Int,
    /** Only explicit user confirmations may set this true. */
    val trainingEligible: Boolean,
    val speedAccuracyMetersPerSecond: Double?,
    val confirmedTripId: String? = null,
    /** Explicit visit/place correction labels; only LIVE observations are eligible for learning. */
    val confirmedPlaceId: Long? = null,
    val confirmedLocationClass: String? = null,
    @ColumnInfo(defaultValue = "0") val placeTrainingEligible: Boolean = false,
)
