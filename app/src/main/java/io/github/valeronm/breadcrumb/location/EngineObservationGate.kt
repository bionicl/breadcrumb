package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.locomotion.LocomotionEngine
import io.github.valeronm.breadcrumb.locomotion.Observation
import io.github.valeronm.breadcrumb.locomotion.ObservationProcessingResult

/** Routes existing recorder observations only when the session selected the analysis engine. */
internal class EngineObservationGate(private val engine: LocomotionEngine) {
    fun process(
        generation: Settings.RecorderEngineGeneration,
        observation: Observation,
    ): ObservationProcessingResult? =
        if (generation == Settings.RecorderEngineGeneration.LOCOMOTION_V2) {
            engine.process(observation)
        } else {
            null
        }
}
