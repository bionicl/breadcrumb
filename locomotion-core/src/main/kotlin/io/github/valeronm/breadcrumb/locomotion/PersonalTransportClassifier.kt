package io.github.valeronm.breadcrumb.locomotion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt
import kotlin.random.Random

/** Versioned, nullable feature vector; null means unavailable and remains distinct from measured zero. */
data class TransportFeatureVector(
    val speedMetersPerSecond: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val stepCadenceHz: Double? = null,
    val accelerationStdDevMetersPerSecondSquared: Double? = null,
    val barometricAltitudeDeltaMeters: Double? = null,
    val coarseActivity: CoarseActivity? = null,
) {
    internal fun values(): List<Double?> = buildList {
        add(speedMetersPerSecond?.valid())
        add(speedAccuracyMetersPerSecond?.valid())
        add(horizontalAccuracyMeters?.valid())
        add(stepCadenceHz?.valid())
        add(accelerationStdDevMetersPerSecondSquared?.valid())
        add(barometricAltitudeDeltaMeters?.valid())
        CoarseActivity.entries.forEach { add(if (it == coarseActivity) 1.0 else 0.0) }
        add(if (coarseActivity == null) 0.0 else 1.0)
    }

    private fun Double.valid() = takeIf { isFinite() }
}

data class ConfirmedTransportWindow(
    val tripId: String,
    val mode: TransportMode,
    val features: TransportFeatureVector,
    val confirmedByUser: Boolean,
    val provenance: ObservationProvenance,
)

enum class ModelTrainingState {
    ACTIVE,
    INSUFFICIENT_CONFIRMED_TRIPS,
    INSUFFICIENT_VALIDATION_TRIPS,
    VALIDATION_FAILED,
}

data class PersonalTrainingReport(
    val state: ModelTrainingState,
    val includedWindows: Int,
    val excludedWindows: Int,
    val independentTripsByMode: Map<TransportMode, Int>,
    val validationAccuracy: Float?,
    val classifier: PersonalTransportClassifier? = null,
)

/** Local Extra Trees model trained only on explicit live user confirmations (LocoKit2 LK-08/09). */
class PersonalTransportClassifier private constructor(
    private val forest: List<ExtraTree>,
    private val independentTripSupport: Int,
) {
    fun predict(features: TransportFeatureVector): TransportPrediction {
        val totals = mutableMapOf<TransportMode, Double>()
        forest.forEach { tree ->
            tree.predict(features.values()).forEach { (mode, probability) ->
                totals[mode] = (totals[mode] ?: 0.0) + probability
            }
        }
        val scores = totals.map { (mode, total) ->
            TransportScore(mode, (total / forest.size).toFloat().coerceIn(0f, 1f))
        }.sortedByDescending { it.probability }
        return TransportPrediction(
            scores = scores,
            modelVersion = MODEL_VERSION,
            featureSchemaVersion = FEATURE_SCHEMA_VERSION,
            independentTripSupport = independentTripSupport,
            reasonCode = "classifier.personal_extra_trees",
        )
    }

    /** Versioned payload for private, on-device model storage. */
    fun encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MODEL_MAGIC)
            output.writeInt(FEATURE_SCHEMA_VERSION)
            output.writeInt(independentTripSupport)
            output.writeInt(forest.size)
            forest.forEach { writeNode(output, it.root) }
        }
        bytes.toByteArray()
    }

    private class ExtraTree(internal val root: Node) {
        fun predict(values: List<Double?>): Map<TransportMode, Double> {
            var node = root
            while (node is Branch) {
                val value = values[node.featureIndex]
                node = if (value == null) {
                    if (node.missingGoesLeft) node.left else node.right
                } else if (value <= node.threshold) {
                    node.left
                } else {
                    node.right
                }
            }
            node as Leaf
            return node.probabilities
        }
    }

    private sealed interface Node
    private data class Leaf(val probabilities: Map<TransportMode, Double>) : Node
    private data class Branch(
        val featureIndex: Int,
        val threshold: Double,
        val missingGoesLeft: Boolean,
        val left: Node,
        val right: Node,
    ) : Node

    companion object {
        const val FEATURE_SCHEMA_VERSION = 1
        private const val MODEL_VERSION = "personal-extra-trees-v1"
        private const val MODEL_MAGIC = 0x4C4B4D31
        private const val TREES = 32
        private const val MAX_DEPTH = 8
        private const val MIN_LEAF_SAMPLES = 2
        private const val THRESHOLDS_PER_FEATURE = 5
        private const val VALIDATION_STRIDE = 5
        private const val MIN_TRIPS_PER_MODE = 3
        private const val MIN_VALIDATION_ACCURACY = 0.55f
        private const val MAX_WINDOWS_PER_TRIP = 256
        private const val MAX_TRAINING_WINDOWS = 20_000
        private const val MAX_MODEL_BYTES = 2_000_000
        private val FEATURE_COUNT = 6 + CoarseActivity.entries.size + 1
        private const val RANDOM_SEED = 0x4C4B32

        fun train(windows: List<ConfirmedTransportWindow>): PersonalTrainingReport {
            val eligible = windows.filter {
                it.confirmedByUser && it.provenance == ObservationProvenance.LIVE && it.tripId.isNotBlank() &&
                    it.mode != TransportMode.UNKNOWN && it.features.values().any { value -> value != null }
            }
            val byTrip = eligible.groupBy { it.tripId }
                .mapValues { (_, tripWindows) -> sampleEvenly(tripWindows, MAX_WINDOWS_PER_TRIP) }
            val totalAfterTripCaps = byTrip.values.sumOf { it.size }
            val cappedTrips = byTrip.values
            val perTripBudget = if (totalAfterTripCaps > MAX_TRAINING_WINDOWS) {
                (MAX_TRAINING_WINDOWS / cappedTrips.size).coerceAtLeast(1)
            } else {
                MAX_WINDOWS_PER_TRIP
            }
            // The number of independent trips can itself exceed the window cap. Keep this a hard
            // bound even in that case, rather than letting a one-window-per-trip fallback overflow.
            val perTrip = cappedTrips.flatMap { sampleEvenly(it, perTripBudget) }.take(MAX_TRAINING_WINDOWS)
            val tripsByMode = perTrip.groupBy { it.mode }
                .mapValues { (_, examples) -> examples.map { it.tripId }.distinct().size }
            val excluded = windows.size - perTrip.size
            if (tripsByMode.size < MINIMUM_CLASSES || tripsByMode.values.any { it < MIN_TRIPS_PER_MODE }) {
                return PersonalTrainingReport(
                    ModelTrainingState.INSUFFICIENT_CONFIRMED_TRIPS,
                    perTrip.size,
                    excluded,
                    tripsByMode,
                    null,
                )
            }

            val trips = perTrip.map { it.tripId }.distinct().sorted()
            val validationTrips = trips.filterIndexed { index, _ -> (index + 1) % VALIDATION_STRIDE == 0 }
            if (validationTrips.isEmpty()) {
                return PersonalTrainingReport(
                    ModelTrainingState.INSUFFICIENT_VALIDATION_TRIPS,
                    perTrip.size,
                    excluded,
                    tripsByMode,
                    null,
                )
            }
            val training = perTrip.filterNot { it.tripId in validationTrips }
            val validation = perTrip.filter { it.tripId in validationTrips }
            val validationForest = buildForest(training)
            val correct = validation.count { example ->
                predict(validationForest, example.features).maxByOrNull { it.value }?.key == example.mode
            }
            val accuracy = correct.toFloat() / validation.size
            if (accuracy < MIN_VALIDATION_ACCURACY) {
                return PersonalTrainingReport(
                    ModelTrainingState.VALIDATION_FAILED,
                    perTrip.size,
                    excluded,
                    tripsByMode,
                    accuracy,
                )
            }
            return PersonalTrainingReport(
                state = ModelTrainingState.ACTIVE,
                includedWindows = perTrip.size,
                excludedWindows = excluded,
                independentTripsByMode = tripsByMode,
                validationAccuracy = accuracy,
                classifier = PersonalTransportClassifier(
                    forest = buildForest(perTrip),
                    independentTripSupport = trips.size,
                ),
            )
        }

        /** Returns null for corrupt, oversized or schema-incompatible local model data. */
        fun decode(bytes: ByteArray): PersonalTransportClassifier? = runCatching {
            require(bytes.size in 16..MAX_MODEL_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MODEL_MAGIC)
                require(input.readInt() == FEATURE_SCHEMA_VERSION)
                val trips = input.readInt().also { require(it > 0) }
                val treeCount = input.readInt().also { require(it == TREES) }
                val forest = List(treeCount) { ExtraTree(readNode(input, 0)) }
                require(input.available() == 0)
                PersonalTransportClassifier(forest, trips)
            }
        }.getOrNull()

        private fun writeNode(output: DataOutputStream, node: Node) {
            when (node) {
                is Leaf -> {
                    output.writeByte(0)
                    output.writeInt(node.probabilities.size)
                    node.probabilities.toSortedMap(compareBy { it.name }).forEach { (mode, probability) ->
                        output.writeUTF(mode.name)
                        output.writeDouble(probability)
                    }
                }
                is Branch -> {
                    output.writeByte(1)
                    output.writeInt(node.featureIndex)
                    output.writeDouble(node.threshold)
                    output.writeBoolean(node.missingGoesLeft)
                    writeNode(output, node.left)
                    writeNode(output, node.right)
                }
            }
        }

        private fun readNode(input: DataInputStream, depth: Int): Node {
            require(depth <= MAX_DEPTH)
            return when (input.readUnsignedByte()) {
                0 -> {
                    val count = input.readInt().also { require(it in 1..TransportMode.entries.size) }
                    val probabilities = buildMap {
                        repeat(count) {
                            val mode = TransportMode.valueOf(input.readUTF())
                            val probability = input.readDouble().also { require(it.isFinite() && it in 0.0..1.0) }
                            put(mode, probability)
                        }
                    }
                    require(probabilities.isNotEmpty())
                    Leaf(probabilities)
                }
                1 -> {
                    val feature = input.readInt().also { require(it in 0 until FEATURE_COUNT) }
                    val threshold = input.readDouble().also { require(it.isFinite()) }
                    val missingLeft = input.readBoolean()
                    Branch(feature, threshold, missingLeft, readNode(input, depth + 1), readNode(input, depth + 1))
                }
                else -> error("Unknown personal-model node")
            }
        }

        private fun sampleEvenly(
            examples: List<ConfirmedTransportWindow>,
            maximum: Int,
        ): List<ConfirmedTransportWindow> = if (examples.size <= maximum) {
            examples
        } else {
            List(maximum) { index -> examples[index * (examples.size - 1) / (maximum - 1)] }
        }

        private fun buildForest(examples: List<ConfirmedTransportWindow>): List<ExtraTree> {
            val windowsByTrip = examples.groupingBy { it.tripId }.eachCount()
            val tripWeights = examples.map { 1.0 / windowsByTrip.getValue(it.tripId) }
            return List(TREES) { treeIndex ->
                val random = Random(RANDOM_SEED + treeIndex * 997 + examples.size)
                ExtraTree(buildNode(examples, tripWeights, (0 until examples.size).toList(), 0, random))
            }
        }

        private fun buildNode(
            examples: List<ConfirmedTransportWindow>,
            tripWeights: List<Double>,
            indices: List<Int>,
            depth: Int,
            random: Random,
        ): Node {
            val classCounts = indices.groupingBy { examples[it].mode }.eachCount()
            if (depth >= MAX_DEPTH || indices.size < MIN_LEAF_SAMPLES * 2 || classCounts.size == 1) {
                return leaf(indices, examples, tripWeights)
            }
            val featureCount = examples.first().features.values().size
            val candidates = (0 until featureCount).shuffled(random).take(sqrt(featureCount.toDouble()).toInt().coerceAtLeast(1))
            var best: Candidate? = null
            for (featureIndex in candidates) {
                val observed = indices.mapNotNull { index ->
                    examples[index].features.values()[featureIndex]?.let { value -> index to value }
                }
                if (observed.map { it.second }.distinct().size < 2) continue
                val low = observed.minOf { it.second }
                val high = observed.maxOf { it.second }
                repeat(THRESHOLDS_PER_FEATURE) {
                    val threshold = random.nextDouble(low, high)
                    for (missingGoesLeft in listOf(true, false)) {
                        val left = indices.filter { index ->
                            val value = examples[index].features.values()[featureIndex]
                            if (value == null) missingGoesLeft else value <= threshold
                        }
                        val right = indices - left.toSet()
                        if (left.size < MIN_LEAF_SAMPLES || right.size < MIN_LEAF_SAMPLES) continue
                        val leftWeight = left.sumOf { tripWeights[it] }
                        val rightWeight = right.sumOf { tripWeights[it] }
                        val totalWeight = leftWeight + rightWeight
                        val score = (leftWeight * gini(left, examples, tripWeights) +
                            rightWeight * gini(right, examples, tripWeights)
                            ) / totalWeight
                        if (best == null || score < best.score) {
                            best = Candidate(featureIndex, threshold, missingGoesLeft, left, right, score)
                        }
                    }
                }
            }
            val split = best ?: return leaf(indices, examples, tripWeights)
            return Branch(
                featureIndex = split.featureIndex,
                threshold = split.threshold,
                missingGoesLeft = split.missingGoesLeft,
                left = buildNode(examples, tripWeights, split.left, depth + 1, random),
                right = buildNode(examples, tripWeights, split.right, depth + 1, random),
            )
        }

        private fun gini(
            indices: List<Int>,
            examples: List<ConfirmedTransportWindow>,
            tripWeights: List<Double>,
        ): Double {
            val total = indices.sumOf { tripWeights[it] }
            val weightsByMode = indices.groupBy { examples[it].mode }.mapValues { (_, modeIndices) ->
                modeIndices.sumOf { tripWeights[it] }
            }
            return 1.0 - weightsByMode.values.sumOf { weight ->
                val probability = weight / total
                probability * probability
            }
        }

        private fun leaf(
            indices: List<Int>,
            examples: List<ConfirmedTransportWindow>,
            tripWeights: List<Double>,
        ): Leaf {
            val total = indices.sumOf { tripWeights[it] }
            val weightsByMode = indices.groupBy { examples[it].mode }.mapValues { (_, modeIndices) ->
                modeIndices.sumOf { tripWeights[it] } / total
            }
            return Leaf(weightsByMode)
        }

        private fun predict(forest: List<ExtraTree>, features: TransportFeatureVector): Map<TransportMode, Double> {
            val scores = mutableMapOf<TransportMode, Double>()
            forest.forEach { tree ->
                tree.predict(features.values()).forEach { (mode, probability) ->
                    scores[mode] = (scores[mode] ?: 0.0) + probability
                }
            }
            return scores.mapValues { (_, score) -> score / forest.size }
        }

        private data class Candidate(
            val featureIndex: Int,
            val threshold: Double,
            val missingGoesLeft: Boolean,
            val left: List<Int>,
            val right: List<Int>,
            val score: Double,
        )

        private const val MINIMUM_CLASSES = 2
    }
}
