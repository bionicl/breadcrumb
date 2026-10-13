package io.github.valeronm.breadcrumb.data.db

import io.github.valeronm.breadcrumb.locomotion.Observation

/** Converts a core observation to durable scalar columns without converting absence into zero. */
fun Observation.toEntity(): EngineObservationEntity {
    val sensor = sensors
    val missingMask = sensor?.missingFeatures.orEmpty().fold(0) { mask, feature ->
        mask or feature.bitMask
    }
    return EngineObservationEntity(
        id = id,
        sessionId = sessionId,
        bootEpochId = bootEpochId,
        wallTimeMs = wallTimeMs,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        source = source.name,
        provenance = provenance.name,
        latitudeDegrees = location?.latitudeDegrees,
        longitudeDegrees = location?.longitudeDegrees,
        horizontalAccuracyMeters = location?.horizontalAccuracyMeters,
        speedMetersPerSecond = location?.speedMetersPerSecond,
        bearingDegrees = location?.bearingDegrees,
        altitudeMeters = location?.altitudeMeters,
        verticalAccuracyMeters = location?.verticalAccuracyMeters,
        sensorWindowStartNanos = sensor?.startElapsedRealtimeNanos,
        sensorWindowEndNanos = sensor?.endElapsedRealtimeNanos,
        sensorSampleCount = sensor?.sampleCount,
        accelerationHorizontalMean = sensor?.accelerationHorizontalMeanMetersPerSecondSquared,
        accelerationHorizontalStdDev = sensor?.accelerationHorizontalStdDevMetersPerSecondSquared,
        accelerationVerticalMean = sensor?.accelerationVerticalMeanMetersPerSecondSquared,
        accelerationVerticalStdDev = sensor?.accelerationVerticalStdDevMetersPerSecondSquared,
        stepCadenceHz = sensor?.stepCadenceHz,
        barometricAltitudeDeltaMeters = sensor?.barometricAltitudeDeltaMeters,
        missingFeaturesMask = missingMask,
        detectedActivity = detectedActivity?.activity?.name,
        detectedActivityConfidence = detectedActivity?.confidencePercent,
        confirmedTransportMode = null,
        qualityFlags = 0,
        trainingEligible = false,
    )
}
