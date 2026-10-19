package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.placeCategory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** One geographical scene, focused on the question rather than the full extent of neighboring trips. */
@Composable
internal fun ReviewMap(
    context: ReviewContext,
    isTrip: Boolean,
    missingRecording: Boolean,
    focusLabel: String,
    suggestedPlace: Place?,
    modifier: Modifier = Modifier,
) {
    val arrivalColor = activityColor(context.primary?.activityType?.let(ActivityType::ofName)).toArgb()
    val departureColor = activityColor(context.secondary?.activityType?.let(ActivityType::ofName)).toArgb()
    val routes = remember(context, isTrip, arrivalColor, departureColor) {
        FeatureCollection.fromFeatures(
            buildList {
                addAll(routeFeatures(context.primaryPoints, arrivalColor))
                if (!isTrip) addAll(routeFeatures(context.secondaryPoints, departureColor))
            },
        )
    }
    val focus = context.focus ?: if (!isTrip) {
        context.primaryPoints.lastOrNull()?.let { Coordinate(it.latitude, it.longitude) }
    } else {
        null
    }
    MapLibreStyledMap(
        modifier = modifier,
        onStyleLoaded = { ctx, map, style, dark ->
            style.addSource(GeoJsonSource("review-routes", routes))
            style.addLayer(
                LineLayer("review-route-outline", "review-routes").withProperties(
                    lineColor(if (dark) "#14211A" else "#FFFFFF"), lineWidth(7f), lineOpacity(0.8f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer("review-route-lines", "review-routes").withProperties(
                    lineColor(Expression.get("color")), lineWidth(4f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            val gaps = reviewGapPairs(context.primaryPoints).toMutableList()
            if (!isTrip) gaps += reviewGapPairs(context.secondaryPoints)
            if (missingRecording) {
                val from = context.primaryPoints.lastOrNull()
                val to = context.secondaryPoints.firstOrNull()
                if (from != null && to != null) gaps += from to to
            }
            if (gaps.isNotEmpty()) {
                style.addSource(
                    GeoJsonSource(
                        "review-gap",
                        FeatureCollection.fromFeatures(
                            gaps.map { (from, to) ->
                                Feature.fromGeometry(
                                    LineString.fromLngLats(
                                        listOf(
                                            Point.fromLngLat(from.longitude, from.latitude), Point.fromLngLat(to.longitude, to.latitude),
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                )
                style.addLayer(
                    LineLayer("review-gap-dashes", "review-gap").withProperties(
                        lineColor("#D3A154"), lineWidth(3f), lineDasharray(arrayOf(2f, 2f)),
                    ),
                )
            }
            if (focus != null) {
                val iconId = placePinImage(null, withGlyph = true)
                style.addImage(iconId, glyphedPinBitmap(ctx, null))
                style.addSource(GeoJsonSource("review-focus", endpointFeature(focus, iconId, focusLabel)))
                style.addLayer(
                    CircleLayer("review-focus-halo", "review-focus").withProperties(
                        circleRadius(30f), circleColor("#36B7B0"), circleOpacity(0.2f),
                        circleStrokeColor("#36B7B0"), circleStrokeWidth(2f), circleStrokeOpacity(0.7f),
                    ),
                )
                if (suggestedPlace != null) {
                    val candidateIcon = placePinImage(suggestedPlace.placeCategory, withGlyph = true)
                    style.addImage(candidateIcon, glyphedPinBitmap(ctx, suggestedPlace.placeCategory))
                    style.addSource(
                        GeoJsonSource(
                            "review-candidate",
                            endpointFeature(
                                Coordinate(suggestedPlace.lat, suggestedPlace.lon), candidateIcon, suggestedPlace.label, muted = true,
                            ),
                        ),
                    )
                    style.addLayer(labeledSymbolLayer(dark, "review-candidate-pin", "review-candidate"))
                }
                style.addLayer(labeledSymbolLayer(dark, "review-focus-pin", "review-focus"))
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(focus.lat, focus.lon)).zoom(14.4).build()
            } else {
                frameTo(map, context.primaryPoints.map { LatLng(it.latitude, it.longitude) }, singlePointZoom = 15.0)
            }
        },
        onUpdate = { _, _ -> },
    )
}

private fun routeFeatures(points: List<TrackPoint>, color: Int): List<Feature> =
    reviewPathRuns(points).map { run ->
        Feature.fromGeometry(LineString.fromLngLats(run.map { Point.fromLngLat(it.longitude, it.latitude) }))
            .apply { addStringProperty("color", org.maplibre.android.utils.ColorUtils.colorToRgbaString(color)) }
    }
