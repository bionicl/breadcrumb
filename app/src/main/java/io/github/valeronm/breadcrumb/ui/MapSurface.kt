package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.ui.theme.LocalDarkTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import java.util.WeakHashMap

/**
 * The map every screen draws on, and nothing drawn on it: the [MapView] and its lifecycle, the
 * basemap flavor, and the camera. Nothing here knows which map is on top of it.
 */

/** Measured to what the eye sees of a control, not to its touch target ([touchHalo]). */
internal val MapInset = 12.dp

/**
 * Shared host for the map composables: owns the [MapView], loads the Protomaps style once, routes
 * later recompositions to [onUpdate], and runs [onMapReady] before the style loads (one-time map
 * setup like click listeners); callers keep their own last-applied state — this only removes boilerplate.
 */
@Composable
internal fun MapLibreStyledMap(
    modifier: Modifier = Modifier,
    camera: CameraCarry? = null,
    /** Pixels of the map's bottom the screen covers right now, read on every frame of a drag. */
    obscuredBottom: () -> Int = { 0 },
    /** What the screen floats over the part of the map [MapCredit] leaves showing. */
    controls: @Composable BoxScope.() -> Unit = {},
    onMapReady: (MapLibreMap) -> Unit = {},
    onStyleLoaded: (ctx: Context, map: MapLibreMap, style: Style, dark: Boolean) -> Unit,
    onUpdate: (MapLibreMap, Style) -> Unit,
) {
    val dark = isMapDark()
    // A map's style loads once per MapView.
    key(dark) {
        val currentCamera by rememberUpdatedState(camera)
        val mapView = rememberMapLibreMapView(dark, onDestroying = { currentCamera?.detach() })
        val host = remember(mapView) { MapHost() }
        // The style loads asynchronously; inputs that arrive in the meantime recompose while the
        // style is still null, so their update is skipped. Route the callback through the host so the
        // load applies the *latest* composition's data, not what the first composition captured.
        host.onStyleLoaded = onStyleLoaded
        val zoom = remember { mutableFloatStateOf(0f) }
        Box(modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { view ->
                    if (!host.inited) {
                        host.inited = true
                        view.getMapAsync { map ->
                            host.map = map
                            creditStrips[map] = host.credit
                            currentCamera?.attach(map)
                            val readZoom = { zoom.floatValue = map.cameraPosition.zoom.toFloat() }
                            if (BuildConfig.DEV_TOOLS) map.addOnCameraMoveListener(readZoom)
                            onMapReady(map)
                            map.setStyle(Style.Builder().fromJson(styleFlavor(view.context, dark).json)) { style ->
                                host.onStyleLoaded(view.context, map, style, dark)
                                // The opening frame is a moveCamera, which lands before this listener
                                // exists to hear it.
                                if (BuildConfig.DEV_TOOLS) readZoom()
                            }
                        }
                    } else {
                        val map = host.map
                        val style = map?.style ?: return@AndroidView
                        onUpdate(map, style)
                    }
                },
            )
            MapOverlay(zoom, host.credit, obscuredBottom, controls)
        }
    }
}

/** Its own scope, so the map host recomposing for a caller's [MapLibreStyledMap] inputs skips it. */
@Composable
private fun MapOverlay(
    zoom: FloatState,
    credit: CreditStrip,
    obscuredBottom: () -> Int,
    controls: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().offset { IntOffset(0, -obscuredBottom()) }) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (BuildConfig.DEV_TOOLS) {
                // The bottom-left corner is the one no screen puts a control in.
                ZoomReadout(zoom, Modifier.align(Alignment.BottomStart).padding(MapInset))
            }
            controls()
        }
        val color = mapOverlayColor
        Surface(
            // What covers the map below the credit shows the map only through its cutouts, such as
            // a sheet's rounded corners.
            Modifier.fillMaxWidth().onSizeChanged { credit.heightPx = it.height }.drawBehind {
                drawRect(color, Offset(0f, size.height), Size(size.width, obscuredBottom().toFloat()))
            },
            color = color,
        ) {
            MapCredit(Modifier.padding(horizontal = MapInset, vertical = 4.dp))
        }
    }
}

/**
 * The live camera zoom, dev builds only — a marker size ramp and the zoom a pin earns its glyph at
 * are zoom numbers, and they can't be judged against a map that won't say which zoom it is at.
 *
 * It takes the state rather than its value so the read happens *here*: read a level up and every
 * camera frame would invalidate the map host, whose `update` lambda then re-runs each caller's
 * whole diff mid-pinch — in the `perf` build, which exists to measure exactly that.
 */
@Composable
private fun ZoomReadout(zoom: FloatState, modifier: Modifier = Modifier) {
    LegendSurface(modifier) {
        Text("z %.1f".format(zoom.floatValue), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Per-[MapView] mutable state held outside Compose's snapshot system. Same role as each caller's
 * `Applied*Inputs` holder: a remembered plain object, not a snapshot state, so writing it never
 * recomposes.
 */
private class MapHost {
    var map: MapLibreMap? = null
    val credit = CreditStrip()
    var inited = false
    var onStyleLoaded: (Context, MapLibreMap, Style, Boolean) -> Unit = { _, _, _, _ -> }
}

/**
 * How much basemap MapLibre may keep in its ambient tile cache (`files/mbgl-offline.db`). The
 * library default (~a tenth of this) is a few screenfuls: Protomaps vector tiles average ~45 KiB,
 * so it fills at roughly a thousand and LRU-evicts what a session just viewed — reopening a track
 * an hour later re-downloads its tiles, and with map data the app's only network use, that
 * eviction *is* the data bill. This holds several times a day's worth of viewing. Nothing is
 * pinned: a ceiling raise, not an offline region — the cache stays opportunistic, and ground the
 * map has never shown still needs a connection.
 */
private const val AMBIENT_CACHE_BYTES = 250L * 1024 * 1024

/** Set once per process — the ceiling is a property of the shared file source, not of a map. */
@Volatile private var ambientCacheCeilingRaised = false

private fun raiseAmbientCacheCeiling(ctx: Context) {
    if (ambientCacheCeilingRaised) return
    ambientCacheCeilingRaised = true
    OfflineManager.getInstance(ctx.applicationContext)
        .setMaximumAmbientCacheSize(
            AMBIENT_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit

                // Not fatal: the cache keeps working at whatever ceiling it already had.
                override fun onError(message: String) {
                    Log.w("Breadcrumb", "Could not raise the map tile cache ceiling: $message")
                }
            },
        )
}

/** A MapLibre [MapView] whose lifecycle follows the composition's [LocalLifecycleOwner]. */
@Composable
private fun rememberMapLibreMapView(dark: Boolean, onDestroying: () -> Unit): MapView {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        raiseAmbientCacheCeiling(ctx)
        // Texture mode instead of the default SurfaceView: a SurfaceView composites in its own
        // layer and ignores Compose clipping, so it would bleed over rounded card corners. The
        // cards' side padding also keeps the map out of the back-gesture edge strips, so no
        // edge-swipe handling is needed on the view itself.
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            // OSM's attribution guideline wants the credit readable without a tap, which MapLibre's
            // attribution button is not; [MapCredit] carries it instead.
            .attributionEnabled(false)
            .logoEnabled(false)
            // The default white shows until the first frame and flashes hard against a dark basemap.
            // Taken from the style rather than spelled here, so a style refresh keeps it matching.
            .foregroundLoadColor(styleFlavor(ctx, dark).backgroundColor)
        MapView(ctx, options).apply {
            onCreate(null)
            onStart()
            onResume()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            onDestroying()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

/** These bounds with their half-spans scaled by [factor] around the center. */
private fun LatLngBounds.scaled(factor: Double): LatLngBounds {
    val centerLat = (latitudeNorth + latitudeSouth) / 2
    val centerLon = (longitudeEast + longitudeWest) / 2
    val halfLat = (latitudeNorth - latitudeSouth) / 2 * factor
    val halfLon = (longitudeEast - longitudeWest) / 2 * factor
    return LatLngBounds.from(
        centerLat + halfLat, centerLon + halfLon,
        centerLat - halfLat, centerLon - halfLon,
    )
}

/**
 * Fits the camera to the part of the map [covered] leaves showing: ≥2 [positions] → a bounds fit
 * [FRAME_MARGIN_PX] in from its edges, exactly 1 → [singlePointZoom].
 * [headroom] > 1 zooms out beyond the exact fit (half-spans scaled around the center), which is what
 * a caller re-framing a moving position needs: an exact fit lands it on the viewport edge, due to
 * re-frame again on the next fix.
 */
internal fun frameTo(
    map: MapLibreMap,
    positions: List<LatLng>,
    singlePointZoom: Double,
    headroom: Double = 1.0,
    obscured: MapInsets = MapInsets.NONE,
) {
    when {
        // moveCamera (not easeCamera): the map should open already framed, with no zoom animation.
        positions.size >= 2 -> {
            val bounds = LatLngBounds.Builder().includes(positions).build()
            fitBounds(map, if (headroom > 1.0) bounds.scaled(headroom) else bounds, FRAME_MARGIN_PX, obscured)
        }
        positions.size == 1 -> map.cameraPosition = paddedCamera(map, positions[0], singlePointZoom, obscured)
    }
}

/** Fits [bounds] [marginPx] in from the edges of the part of the map [covered] leaves showing. */
internal fun fitBounds(map: MapLibreMap, bounds: LatLngBounds, marginPx: Int, obscured: MapInsets = MapInsets.NONE) {
    val shown = covered(map, obscured)
    map.moveCamera(
        CameraUpdateFactory.newLatLngBounds(bounds, marginPx, marginPx + shown.top, marginPx, marginPx + shown.bottom),
    )
}

/** [at] at [zoom], in the middle of the part of the map [covered] leaves showing. */
internal fun paddedCamera(map: MapLibreMap, at: LatLng, zoom: Double, obscured: MapInsets = MapInsets.NONE): CameraPosition {
    val shown = covered(map, obscured)
    return CameraPosition.Builder()
        .target(at)
        .zoom(zoom)
        .padding(0.0, shown.top.toDouble(), 0.0, shown.bottom.toDouble())
        .build()
}

/** Puts [at] at the middle of the part of the map [covered] leaves showing, keeping the zoom. */
internal fun centerOn(map: MapLibreMap, at: LatLng, obscured: MapInsets) {
    val shown = covered(map, obscured)
    map.moveCamera(
        CameraUpdateFactory.newLatLngPadding(at, 0.0, shown.top.toDouble(), 0.0, shown.bottom.toDouble()),
    )
}

/** Whether every one of [positions] sits inside the middle of the part of the map [covered] leaves
 *  showing, [EDGE_MARGIN] in from each edge. */
internal fun showsWell(map: MapLibreMap, positions: List<LatLng>, obscured: MapInsets = MapInsets.NONE): Boolean {
    val shown = covered(map, obscured)
    val top = shown.top.toFloat()
    val bottom = map.height - shown.bottom
    val marginX = map.width * EDGE_MARGIN.toFloat()
    val marginY = (bottom - top) * EDGE_MARGIN.toFloat()
    return positions.all {
        val at = map.projection.toScreenLocation(it)
        at.x in marginX..(map.width - marginX) && at.y in (top + marginY)..(bottom - marginY)
    }
}

/** How far, in pixels, a screen's own controls cover a map from its top and bottom edges. */
internal data class MapInsets(val top: Int, val bottom: Int) {
    companion object {
        val NONE = MapInsets(0, 0)
    }
}

private const val FRAME_MARGIN_PX = 96

/** The measured height of a map's [MapCredit] strip, which hides the map beneath it. */
private class CreditStrip {
    var heightPx = 0
}

/** Weak on the map, and a [CreditStrip] holds nothing that reaches back to it. */
private val creditStrips = WeakHashMap<MapLibreMap, CreditStrip>()

/** What hides [map]: the screen's own [obscured] controls and the [MapCredit] strip above them. */
internal fun covered(map: MapLibreMap, obscured: MapInsets = MapInsets.NONE): MapInsets =
    obscured.copy(bottom = obscured.bottom + (creditStrips[map]?.heightPx ?: 0))

/** How far in from each edge, as a share of the part of the map left showing, a position still reads
 *  as comfortably on screen. */
private const val EDGE_MARGIN = 0.1

internal enum class MapShade(@StringRes val labelRes: Int, val dark: Boolean?) {
    THEME(R.string.settings_map_shade_theme, null),
    LIGHT(R.string.settings_map_shade_light, false),
    DARK(R.string.settings_map_shade_dark, true),
    ;

    companion object {
        fun of(dark: Boolean?): MapShade = entries.first { it.dark == dark }
    }
}

internal val LocalMapShade = compositionLocalOf { MapShade.THEME }

@Composable
@ReadOnlyComposable
internal fun isMapDark(): Boolean = LocalMapShade.current.dark ?: LocalDarkTheme.current

private val BACKGROUND_COLOR_RE = Regex("\"background-color\":\\s*\"(#[0-9a-fA-F]{6})\"")

/** One flavor's resolved style: the key-injected JSON and its own background color. */
private class StyleFlavor(val json: String, val backgroundColor: Int)

/** A style is read for every map creation. */
private val cachedStyles = HashMap<Boolean, StyleFlavor>()

/**
 * The bundled official Protomaps style for the [dark] or light shade
 * (assets/protomaps-{dark,light}.json) with the hosted-API key injected. The background color is
 * scanned out on the same cache miss: it is a constant of the flavor, and the document is ~268 KB —
 * far too big to re-scan per map.
 */
private fun styleFlavor(ctx: Context, dark: Boolean): StyleFlavor =
    cachedStyles.getOrPut(dark) {
        val asset = if (dark) "protomaps-dark.json" else "protomaps-light.json"
        val json = ctx.assets.open(asset).bufferedReader().use { it.readText() }
            .replace("{PROTOMAPS_KEY}", BuildConfig.PROTOMAPS_API_KEY)
        val background = BACKGROUND_COLOR_RE.find(json)
            ?.groupValues?.get(1)?.let(android.graphics.Color::parseColor)
            ?: android.graphics.Color.DKGRAY
        StyleFlavor(json, background)
    }
