package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.EngineObservationEntity
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.locomotion.CoarseActivity
import io.github.valeronm.breadcrumb.locomotion.ConfirmedTransportWindow
import io.github.valeronm.breadcrumb.locomotion.ObservationProvenance
import io.github.valeronm.breadcrumb.locomotion.TransportFeatureVector
import io.github.valeronm.breadcrumb.locomotion.TransportMode
import io.github.valeronm.breadcrumb.locomotion.TransportPrediction

/** Only explicit transport labels map to trainable classes; stationary/unknown labels are excluded. */
internal fun ActivityType.toTransportMode(): TransportMode? = when (this) {
    ActivityType.WALKING -> TransportMode.WALKING
    ActivityType.RUNNING -> TransportMode.RUNNING
    ActivityType.CYCLING -> TransportMode.CYCLING
    ActivityType.DRIVING -> TransportMode.CAR
    ActivityType.TAXI -> TransportMode.TAXI
    ActivityType.MOTORCYCLE -> TransportMode.MOTORCYCLE
    ActivityType.SCOOTER -> TransportMode.SCOOTER
    ActivityType.BUS -> TransportMode.BUS
    ActivityType.TRAM -> TransportMode.TRAM
    ActivityType.TRAIN -> TransportMode.TRAIN
    ActivityType.METRO -> TransportMode.METRO
    ActivityType.FERRY -> TransportMode.BOAT
    ActivityType.FLIGHT -> TransportMode.AIRPLANE
    ActivityType.TRANSIT -> TransportMode.TRANSIT
    ActivityType.STILL, ActivityType.UNKNOWN -> null
}

internal fun TransportMode.toActivityType(): ActivityType? = when (this) {
    TransportMode.WALKING -> ActivityType.WALKING
    TransportMode.RUNNING -> ActivityType.RUNNING
    TransportMode.CYCLING -> ActivityType.CYCLING
    TransportMode.CAR -> ActivityType.DRIVING
    TransportMode.TAXI -> ActivityType.TAXI
    TransportMode.MOTORCYCLE -> ActivityType.MOTORCYCLE
    TransportMode.SCOOTER -> ActivityType.SCOOTER
    TransportMode.BUS -> ActivityType.BUS
    TransportMode.TRAM -> ActivityType.TRAM
    TransportMode.TRAIN -> ActivityType.TRAIN
    TransportMode.METRO -> ActivityType.METRO
    TransportMode.BOAT -> ActivityType.FERRY
    TransportMode.AIRPLANE -> ActivityType.FLIGHT
    TransportMode.TRANSIT -> ActivityType.TRANSIT
    TransportMode.UNKNOWN -> null
}

internal fun EngineObservationEntity.toConfirmedWindow(): ConfirmedTransportWindow? {
    val tripId = confirmedTripId ?: return null
    val mode = confirmedTransportMode?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() } ?: return null
    if (!trainingEligible || provenance != ObservationProvenance.LIVE.name) return null
    val coarse = detectedActivity?.let { runCatching { CoarseActivity.valueOf(it) }.getOrNull() }
    return ConfirmedTransportWindow(
        tripId = tripId,
        mode = mode,
        confirmedByUser = true,
        provenance = ObservationProvenance.LIVE,
        features = TransportFeatureVector(
            speedMetersPerSecond = speedMetersPerSecond,
            speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            stepCadenceHz = stepCadenceHz,
            accelerationStdDevMetersPerSecondSquared = accelerationHorizontalStdDev,
            barometricAltitudeDeltaMeters = barometricAltitudeDeltaMeters,
            coarseActivity = coarse,
        ),
    )
}

internal fun EngineObservationEntity.toPredictionFeatures() = TransportFeatureVector(
    speedMetersPerSecond = speedMetersPerSecond,
    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
    horizontalAccuracyMeters = horizontalAccuracyMeters,
    stepCadenceHz = stepCadenceHz,
    accelerationStdDevMetersPerSecondSquared = accelerationHorizontalStdDev,
    barometricAltitudeDeltaMeters = barometricAltitudeDeltaMeters,
    coarseActivity = detectedActivity?.let { runCatching { CoarseActivity.valueOf(it) }.getOrNull() },
)

/** A mode omitted by a prediction contributes zero to the trip-wide mean, never a missing sample. */
internal fun averagePredictionScores(predictions: List<TransportPrediction>): Map<TransportMode, Double> {
    if (predictions.isEmpty()) return emptyMap()
    return TransportMode.entries.associateWith { mode ->
        predictions.sumOf { prediction ->
            prediction.scores.firstOrNull { it.mode == mode }?.probability?.toDouble() ?: 0.0
        } / predictions.size
    }
}
