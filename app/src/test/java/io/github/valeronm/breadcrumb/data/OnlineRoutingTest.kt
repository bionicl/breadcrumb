package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the shape [OnlineRouting.parse] expects of an OSRM response, with no network near. */
class OnlineRoutingTest {

    @Test fun `parses the first route's time and shape, longitude first as GeoJSON orders it`() {
        val json = """
            {"code":"Ok","routes":[{"legs":[{"steps":[],"weight":1.0,"summary":"","duration":551.9,"distance":4813.8}],
              "weight_name":"routability","weight":551.9,"duration":551.9,"distance":4813.8,
              "geometry":{"type":"LineString","coordinates":[[-2.0,1.0],[-2.001,1.0005],[-2.01,1.002]]}}],
             "waypoints":[{"hint":"x","distance":3.2,"name":"","location":[-2.0,1.0]}]}
        """.trimIndent()

        val route = OnlineRouting.parse(json.reader())!!

        assertEquals(551_900L, route.durationMs)
        assertEquals(listOf(Coordinate(1.0, -2.0), Coordinate(1.0005, -2.001), Coordinate(1.002, -2.01)), route.shape)
    }

    @Test fun `a response that is not Ok is no route`() {
        val json = """{"code":"NoRoute","message":"Impossible route between points","routes":[]}"""
        assertNull(OnlineRouting.parse(json.reader()))
    }

    @Test fun `the request names the profile's server and the ends longitude first`() {
        val url = OnlineRouting.url(OnlineRouting.Profile.BIKE, Coordinate(1.0, -2.0), Coordinate(1.5, -1.0))
        assertEquals(
            "https://routing.openstreetmap.de/routed-bike/route/v1/driving/-2.0,1.0;-1.0,1.5?overview=full&geometries=geojson",
            url,
        )
    }

    @Test fun `only a type a road carries has a profile`() {
        val expected = mapOf(
            ActivityType.DRIVING to OnlineRouting.Profile.CAR,
            ActivityType.TAXI to OnlineRouting.Profile.CAR,
            ActivityType.CYCLING to OnlineRouting.Profile.BIKE,
            ActivityType.WALKING to OnlineRouting.Profile.FOOT,
        )
        ActivityType.entries.forEach { type ->
            assertEquals(type.name, expected[type], OnlineRouting.Profile.of(type))
        }
    }
}
