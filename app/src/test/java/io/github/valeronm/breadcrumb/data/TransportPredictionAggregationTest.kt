package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.locomotion.TransportMode
import io.github.valeronm.breadcrumb.locomotion.TransportPrediction
import io.github.valeronm.breadcrumb.locomotion.TransportScore
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPredictionAggregationTest {
    @Test fun `a class missing from a window contributes zero to its trip mean`() {
        val predictions = listOf(
            prediction(TransportScore(TransportMode.BUS, 0.9f), TransportScore(TransportMode.CAR, 0.1f)),
            prediction(TransportScore(TransportMode.CAR, 1.0f)),
        )

        val averaged = averagePredictionScores(predictions)

        assertEquals(0.45, averaged.getValue(TransportMode.BUS), 1e-6)
        assertEquals(0.55, averaged.getValue(TransportMode.CAR), 1e-6)
    }

    @Test fun `empty window list has no averaged prediction`() {
        assertEquals(emptyMap<TransportMode, Double>(), averagePredictionScores(emptyList()))
    }

    private fun prediction(vararg scores: TransportScore) = TransportPrediction(
        scores = scores.toList(),
        modelVersion = "test-model",
        featureSchemaVersion = 1,
        independentTripSupport = 6,
        reasonCode = "test",
    )
}
