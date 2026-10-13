package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalTransportClassifierTest {
    @Test fun `only confirmed live trips can activate the personal model`() {
        val examples = buildList {
            repeat(8) { index ->
                add(window("walk-$index", TransportMode.WALKING, walkingFeatures(), confirmed = index < 2))
                add(window("car-$index", TransportMode.CAR, carFeatures(), provenance = ObservationProvenance.DEMO))
            }
        }

        val report = PersonalTransportClassifier.train(examples)

        assertEquals(ModelTrainingState.INSUFFICIENT_CONFIRMED_TRIPS, report.state)
        assertNull(report.classifier)
        assertTrue(report.excludedWindows > 0)
    }

    @Test fun `extra trees learn separable labels with validation by whole trips`() {
        val examples = buildList {
            repeat(10) { index ->
                repeat(3) { add(window("walk-$index", TransportMode.WALKING, walkingFeatures(index))) }
                repeat(3) { add(window("car-$index", TransportMode.CAR, carFeatures(index))) }
            }
        }

        val report = PersonalTransportClassifier.train(examples)
        val model = assertNotNull(report.classifier)
        val walking = model.predict(walkingFeatures())
        val car = model.predict(carFeatures())

        assertEquals(ModelTrainingState.ACTIVE, report.state)
        assertTrue((report.validationAccuracy ?: 0f) >= 0.55f)
        assertEquals(20, walking.independentTripSupport)
        assertEquals(TransportMode.WALKING, walking.scores.first().mode)
        assertEquals(TransportMode.CAR, car.scores.first().mode)
        assertEquals("personal-extra-trees-v1", walking.modelVersion)

        val restored = assertNotNull(PersonalTransportClassifier.decode(model.encode()))
        assertEquals(walking, restored.predict(walkingFeatures()))
        assertEquals(car, restored.predict(carFeatures()))
    }

    @Test fun `zero values and missing features stay distinct`() {
        val measuredZero = TransportFeatureVector(speedMetersPerSecond = 0.0).values()
        val missing = TransportFeatureVector().values()

        assertEquals(0.0, measuredZero.first())
        assertNull(missing.first())
        assertEquals(0.0, measuredZero.last())
        assertEquals(0.0, missing.last())
    }

    @Test fun `corrupt and incompatible model payloads are rejected`() {
        assertNull(PersonalTransportClassifier.decode(byteArrayOf(1, 2, 3)))
        val valid = assertNotNull(
            PersonalTransportClassifier.train(buildList {
                repeat(10) { index ->
                    add(window("walk-$index", TransportMode.WALKING, walkingFeatures(index)))
                    add(window("car-$index", TransportMode.CAR, carFeatures(index)))
                }
            }).classifier,
        ).encode()
        valid[4] = 99 // feature schema version
        assertNull(PersonalTransportClassifier.decode(valid))
    }

    @Test fun `one long trip is capped and cannot impersonate independent support`() {
        val examples = buildList {
            repeat(500) { add(window("one-walk-trip", TransportMode.WALKING, walkingFeatures())) }
            repeat(500) { add(window("one-car-trip", TransportMode.CAR, carFeatures())) }
        }

        val report = PersonalTransportClassifier.train(examples)

        assertEquals(ModelTrainingState.INSUFFICIENT_CONFIRMED_TRIPS, report.state)
        assertEquals(512, report.includedWindows)
        assertEquals(2, report.independentTripsByMode.values.sum())
    }

    private fun window(
        tripId: String,
        mode: TransportMode,
        features: TransportFeatureVector,
        confirmed: Boolean = true,
        provenance: ObservationProvenance = ObservationProvenance.LIVE,
    ) = ConfirmedTransportWindow(tripId, mode, features, confirmed, provenance)

    private fun walkingFeatures(variant: Int = 0) = TransportFeatureVector(
        speedMetersPerSecond = 1.3 + (variant % 3) * 0.1,
        horizontalAccuracyMeters = 4.0,
        stepCadenceHz = 1.4,
        accelerationStdDevMetersPerSecondSquared = 0.6,
        coarseActivity = CoarseActivity.WALKING,
    )

    private fun carFeatures(variant: Int = 0) = TransportFeatureVector(
        speedMetersPerSecond = 12.0 + (variant % 3) * 0.5,
        speedAccuracyMetersPerSecond = 0.5,
        horizontalAccuracyMeters = 4.0,
        stepCadenceHz = 0.0,
        accelerationStdDevMetersPerSecondSquared = 0.2,
        coarseActivity = CoarseActivity.IN_VEHICLE,
    )
}
