package io.github.valeronm.breadcrumb.data

import android.content.Context
import io.github.valeronm.breadcrumb.data.export.JsonPullReader
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import java.io.Reader

/**
 * A road route between a manual trip's two ends — its shape and how long it takes — from
 * FOSSGIS's OSRM servers (routing.openstreetmap.de, OpenStreetMap data, no API key), one server per
 * profile. The two points leave the device, which is why the whole feature sits behind
 * [Settings.isOnlineRouting] and its switch on the Privacy page.
 *
 * Every failure — offline, timeout, no road between the points, a malformed body — reads as no
 * route ([fetchJson]).
 */
object OnlineRouting {

    /** The servers' profiles, by the path each is served under. */
    enum class Profile(val path: String) {
        CAR("routed-car"),
        BIKE("routed-bike"),
        FOOT("routed-foot"),
        ;

        companion object {
            /**
             * The profile a trip type travels by, or null where no road carries it. Running is
             * out: the foot profile times a walk, and a scaled guess would be worse than none.
             */
            fun of(type: ActivityType): Profile? = when (type) {
                ActivityType.DRIVING, ActivityType.TAXI -> CAR
                ActivityType.CYCLING -> BIKE
                ActivityType.WALKING -> FOOT
                else -> null
            }
        }
    }

    class Route(
        val durationMs: Long,
        /** The road geometry, the ends snapped to the nearest road. */
        val shape: List<Coordinate>,
    )

    private const val TIMEOUT_MS = 8_000
    private const val MS_PER_S = 1_000.0

    /**
     * The route from [from] to [to] by [profile]; null on any failure — and null without touching
     * the network when the Privacy switch is off. The gate lives here, with the socket, so no later
     * caller can put the points on the wire around it.
     */
    suspend fun route(context: Context, profile: Profile, from: Coordinate, to: Coordinate): Route? {
        if (!Settings.isOnlineRouting(context)) return null
        return fetchJson(url(profile, from, to), TIMEOUT_MS, "online routing", ::parse)
    }

    internal fun url(profile: Profile, from: Coordinate, to: Coordinate): String =
        "https://routing.openstreetmap.de/${profile.path}/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=geojson"

    /**
     * Parses an OSRM route response: the first route's duration and geometry, or null
     * where the server answered with no route — its `code` is then not `Ok`, and `routes` empty.
     * Separate from the fetch so the shape is pinned by a host test with no network.
     */
    internal fun parse(reader: Reader): Route? {
        var ok = false
        var route: Route? = null
        val json = JsonPullReader(reader)
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "code" -> ok = json.nextString() == "Ok"
                "routes" -> route = parseRoutes(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        json.expectEnd()
        return route.takeIf { ok }
    }

    /** The first route of the array; the rest, alternatives OSRM was not asked for, are walked past. */
    private fun parseRoutes(json: JsonPullReader): Route? {
        var first: Route? = null
        json.beginArray()
        while (json.hasNext()) {
            val parsed = parseRoute(json)
            if (first == null) first = parsed
        }
        json.endArray()
        return first
    }

    private fun parseRoute(json: JsonPullReader): Route? {
        var duration: Double? = null
        var shape: List<Coordinate>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "duration" -> duration = json.nextNumber().toDouble()
                "geometry" -> shape = parseGeometry(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Route(
            durationMs = ((duration ?: return null) * MS_PER_S).toLong(),
            shape = shape ?: return null,
        )
    }

    /** A GeoJSON LineString's positions. */
    private fun parseGeometry(json: JsonPullReader): List<Coordinate> {
        var out: List<Coordinate> = emptyList()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "coordinates" -> out = parsePositions(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return out
    }

    private fun parsePositions(json: JsonPullReader): List<Coordinate> {
        val out = mutableListOf<Coordinate>()
        json.beginArray()
        while (json.hasNext()) json.nextPosition()?.let(out::add)
        json.endArray()
        return out
    }
}
