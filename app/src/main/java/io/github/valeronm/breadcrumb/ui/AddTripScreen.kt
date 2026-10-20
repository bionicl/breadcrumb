package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.OnlineRouting
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CityAtlas
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.ManualPath
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.domain.TripPlaces
import io.github.valeronm.breadcrumb.domain.isNamed
import io.github.valeronm.breadcrumb.domain.pin
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/** What the add-trip form offers: every movement type, carried transport first — a trip goes
 *  missing because nothing recorded it, and that happens to walks as readily as to flights. */
private val MANUAL_TRIP_TYPES = listOf(
    ActivityType.FLIGHT,
    ActivityType.TRANSIT,
    ActivityType.FERRY,
    ActivityType.TAXI,
    ActivityType.DRIVING,
    ActivityType.WALKING,
    ActivityType.RUNNING,
    ActivityType.CYCLING,
    ActivityType.UNKNOWN,
)

private const val HOUR_MS = 3_600_000L

/** Where an empty end's pickers open: noon of [day] when the Timeline supplied the day the user
 *  was looking at, today's noon with nothing better to go on. */
private fun noonOf(day: LocalDate?, zone: ZoneId): ZonedDateTime =
    (day ?: LocalDate.now(zone)).atTime(12, 0).atZone(zone)

/**
 * What the form opens holding. The Timeline's top bar knows only which day was on screen; a gap
 * row knows the places the absence lies between and when it ran, and hands over whichever of its
 * two ends that row itself speaks for — both where the absence sat inside one day, otherwise only
 * the end on its own side of the cut, since the other is a fact about a different day's row and
 * would arrive here as an unread guess.
 */
internal class TripDraft(
    /** The day an end with no time of its own opens its pickers on. */
    val day: LocalDate?,
    val origin: TripDraftEnd? = null,
    val destination: TripDraftEnd? = null,
    /** Set where the form is restating a trip that already exists — see [EditedTrip]. */
    val editing: EditedTrip? = null,
)

/**
 * The manual track a form was opened on: its row is rewritten in place rather than a new one
 * inserted, and [activity] — what it currently says it was — is the type the chips open on. That is
 * the one exception to the form's rule that nothing preselects a type: an existing trip already
 * carries an answer, and offering it as unset would ask the user to re-state what they are not here
 * to change.
 */
internal class EditedTrip(
    val trackId: Long,
    val activity: ActivityType?,
    /** The road shape stored between the trip's ends, or empty for a trip of its ends alone. */
    val path: List<Coordinate>,
)

/**
 * One end a caller can already fill in.
 *
 * [timeMs] is an instant rather than a wall clock, because neither half of a wall clock is
 * available at the point one would be built: the zone comes from resolving the pin, which the form
 * does after it opens, and the pickers' minute resolution would round the bound off the
 * neighbouring track — a departure rounded back past that track's last fix overlaps it, and the
 * insert refuses an overlap. Held exactly until a picked time replaces it, so a trip committed as
 * drafted meets the recording on either side of the absence rather than a minute inside it.
 */
internal class TripDraftEnd(
    /** Null where the caller knows when but not where — a gap side the recorder never got a fix for. */
    val at: Coordinate?,
    /** The user's own name for the spot, never a derived one — see [TripEnd.placeName]. */
    val placeName: String?,
    val timeMs: Long,
    /** The recorded track this end meets — the one before a departure, after an arrival — where the
     *  caller knows it. */
    val trackId: Long? = null,
)

/** One end of the trip being entered — a pin and a time, each settable in any order, and either of
 *  them possibly already known to whoever opened the form ([TripDraftEnd]). */
private class TripEnd(drafted: TripDraftEnd?) {
    var pin by mutableStateOf(drafted?.at)

    /** The name the pin was picked *by* — a saved place's label, or an online hit's name — which
     *  [TripPlaces.plan] makes a place of. Null for a hand-placed pin, which has no name to give,
     *  and for a picked *city*, which must not become a capture circle at its centroid. */
    var placeName by mutableStateOf(drafted?.placeName)
    var date by mutableStateOf<LocalDate?>(null)
    var time by mutableStateOf<LocalTime?>(null)

    private val draftedAt = drafted?.timeMs

    /** Placed from the other end by a road's duration; held apart from [ownEpochIn] because a lifted
     *  coupling must give back the time that was recorded or typed. */
    var coupledMs by mutableStateOf<Long?>(null)

    fun epochIn(zone: ZoneId): Long? = coupledMs ?: ownEpochIn(zone)

    /** Its wall clock read in [zone] once one is set, until then the instant it was drafted with
     *  (see [TripDraftEnd]); null when it holds no time of its own. */
    fun ownEpochIn(zone: ZoneId): Long? {
        val d = date ?: return draftedAt
        val t = time ?: return draftedAt
        return ZonedDateTime.of(d, t, zone).toInstant().toEpochMilli()
    }

    /** Where this end's pickers open: on the time it already holds, else [fallback]. */
    fun pickerStart(zone: ZoneId, fallback: ZonedDateTime): ZonedDateTime =
        epochIn(zone)?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: fallback
}

/**
 * A road path with the pins and profile it was asked for, drawn only while those are what the form
 * says; switching away from a type and back brings it back.
 */
private class RoutedLeg(
    val profile: OnlineRouting.Profile,
    val from: Coordinate,
    val to: Coordinate,
    val shape: List<Coordinate>,
    val durationMs: Long,
) {
    /** The length of the line the trip will be stored along — the saved row's figure, not the
     *  router's, which measures between road-snapped ends. */
    val lengthM: Double by lazy { ManualPath.lengthM(ManualPath.line(from, to, shape), AndroidDistance) }
}

/**
 * The path a trip being edited was saved with, as the leg it would be asked for again; its
 * duration is the row's span, which is what the saved times were coupled by.
 */
private fun TripDraft.storedRoute(): RoutedLeg? {
    val edited = editing ?: return null
    if (edited.path.isEmpty()) return null
    val profile = edited.activity?.let(OnlineRouting.Profile::of) ?: return null
    val from = origin ?: return null
    val to = destination ?: return null
    return RoutedLeg(profile, from.at ?: return null, to.at ?: return null, edited.path, to.timeMs - from.timeMs)
}

/** One end's time being edited: which, under which name, on whose clock, and where the pickers
 *  open ([TripEnd.pickerStart]). */
private class TimeEdit(
    val which: ManualPath.End,
    /** The picker's heading, whole rather than a noun the dialog appends "time" to. */
    @StringRes val titleRes: Int,
    val zone: ZoneId,
    val start: ZonedDateTime,
)

/**
 * A recorded track a trip being entered meets at one of its ends: its path, and the fix and the
 * bound it meets the trip at — its end where it comes before the trip, else its start.
 */
internal class TouchingTrack(
    val line: JourneyLine,
    val touching: Coordinate,
    val boundMs: Long,
) {
    val activity: ActivityType? get() = ActivityType.ofName(line.activityType)
}

/** Where the controls floating over the map and the sheet's top edge sit, in window pixels. */
private class MapCover {
    var mapTop by mutableFloatStateOf(0f)
    var mapBottom by mutableFloatStateOf(0f)
    var headerBottom by mutableFloatStateOf(0f)

    /** Unset until the sheet is first laid out, when nothing is known to cover the map. */
    var sheetTop by mutableFloatStateOf(Float.NaN)

    fun insets(): MapInsets = MapInsets(
        top = (headerBottom - mapTop).roundToInt().coerceAtLeast(0),
        bottom = if (sheetTop.isNaN()) 0 else (mapBottom - sheetTop).roundToInt().coerceAtLeast(0),
    )
}

/**
 * Enter a trip nothing recorded — a flight, a leg with the phone dead — as two pins and two times,
 * inserted as a manual track, with a router's road shape between them where one was asked for. Each
 * end's typed time is read in the IANA zone of the city the atlas resolves its pin to (shown on the
 * end's card, never silently applied), so a past flight's times can be entered as the boarding pass
 * states them; with no pin placed yet the time row stays disabled rather than guessing a zone.
 * Everything is local until the check mark commits, so backing out discards by construction — the
 * [PlaceEditScreen] pattern.  What the form opens holding is [draft]'s — the ends a gap row could
 * already fill in, or nothing but the day the Timeline was showing. **It is also how a manual trip
 * is edited** ([TripDraft.editing]): the same two pins, two times and road shape, rewritten onto the
 * row they came from, since what a hand-entered trip *is* and what the form asks for are the same
 * thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTripScreen(
    viewModel: TrackListViewModel,
    draft: TripDraft,
    onClose: () -> Unit,
) {
    // Keyed on the draft: a second gap's form can open while the first is still animating out, and
    // an unkeyed remember would hand it the ends the last one was filled with.
    val origin = remember(draft) { TripEnd(draft.origin) }
    val destination = remember(draft) { TripEnd(draft.destination) }
    // Unset unless the trip already exists (see [EditedTrip]). How a missing leg was made is the one
    // thing nothing here can work out — the two ends are known on the gap path and say nothing about
    // it — so a default would be a guess the user commits without reading.
    var activity by remember(draft) { mutableStateOf(draft.editing?.activity) }
    // Which end the next long-press (or quick-pick) places: the one still missing a pin, and the
    // origin where both need one. Placing the origin advances to the destination once, so the
    // common path is two presses with no toggling.
    var placingDestination by remember(draft) {
        mutableStateOf(origin.pin != null && destination.pin == null)
    }
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
    var editingDate by remember { mutableStateOf<LocalDate?>(null) }
    var centerRequest by remember(draft) { mutableStateOf<MapCenterRequest?>(null) }
    var frameKey by remember(draft) { mutableIntStateOf(0) }
    // Where the map is looking, as of its last settle — what the place search sorts around. Keyed
    // like the ends beside it: a second form opening while the first animates out gets a map of its
    // own, and would otherwise sort around wherever that one was left.
    var mapCenter by remember(draft) { mutableStateOf<Coordinate?>(null) }
    var routed by remember(draft) { mutableStateOf(draft.storedRoute()) }
    // The tracks the form was opened between, whatever is typed after: a gap's draft names them, and
    // a trip being edited sits between its neighbors on the timeline.
    val touching by produceState<Pair<TouchingTrack?, TouchingTrack?>>(null to null, draft) {
        val (before, after) = draft.editing?.let { viewModel.tracksAround(it.trackId) }
            ?: (draft.origin?.trackId to draft.destination?.trackId)
        value = viewModel.touchingTracks(before, after)
    }
    var fetchingRoute by remember(draft) { mutableStateOf(false) }
    val profile = activity?.let(OnlineRouting.Profile::of)
    val leg = routed?.takeIf { it.profile == profile && it.from == origin.pin && it.to == destination.pin }
    val context = LocalContext.current
    val routingOn = remember { AppSettings.isOnlineRouting(context) }
    val scope = rememberCoroutineScope()

    val originCity by produceState<CityAtlas.City?>(null, origin.pin) {
        value = origin.pin?.let { viewModel.cityAt(it) }
    }
    val destinationCity by produceState<CityAtlas.City?>(null, destination.pin) {
        value = destination.pin?.let { viewModel.cityAt(it) }
    }
    val originZone = zoneOrDevice(originCity?.zoneId)
    val destinationZone = zoneOrDevice(destinationCity?.zoneId)
    val departMs = origin.epochIn(originZone)
    val arriveMs = destination.epochIn(destinationZone)
    val continuing = ManualPath.continuing(
        departMs,
        arriveMs,
        previousEndMs = touching.first?.boundMs,
        nextStartMs = touching.second?.boundMs,
    )

    // With a road path attached the two times are one fact and its duration: the end at [from]
    // keeps its own time and the other is placed from it ([ManualPath.coupledEnd]).
    fun coupleTimes(from: ManualPath.End, durationMs: Long) {
        val forward = from == ManualPath.End.ORIGIN
        val kept = if (forward) origin else destination
        val other = if (forward) destination else origin
        val at = kept.ownEpochIn(if (forward) originZone else destinationZone) ?: return
        kept.coupledMs = null
        other.coupledMs = ManualPath.coupledEnd(at, durationMs, forward)
    }

    fun keptEnd(): ManualPath.End? = when {
        destination.coupledMs != null -> ManualPath.End.ORIGIN
        origin.coupledMs != null -> ManualPath.End.DESTINATION
        else -> null
    }

    // Offered only where both ends hold a time of their own: with one, that one is all the
    // coupling can keep.
    val keepChoice = leg?.takeIf {
        origin.ownEpochIn(originZone) != null && destination.ownEpochIn(destinationZone) != null
    }

    fun toggleKeep(end: ManualPath.End) {
        val durationMs = keepChoice?.durationMs ?: return
        if (keptEnd() == end) {
            origin.coupledMs = null
            destination.coupledMs = null
        } else {
            coupleTimes(end, durationMs)
        }
    }

    // Resolved here: the result lands outside the composition.
    val noRoadsMessage = stringResource(R.string.addtrip_no_roads)

    fun followRoads() {
        val by = profile ?: return
        val from = origin.pin ?: return
        val to = destination.pin ?: return
        fetchingRoute = true
        scope.launch {
            val found = viewModel.routeOnline(by, from, to)
            fetchingRoute = false
            if (found == null) {
                viewModel.say(noRoadsMessage)
            } else {
                routed = RoutedLeg(by, from, to, found.shape, found.durationMs)
                frameKey++
                // The end already kept may be the user's choice, which a new road leaves standing.
                val keep = keptEnd() ?: ManualPath.anchor(
                    originTimed = origin.ownEpochIn(originZone) != null,
                    destinationTimed = destination.ownEpochIn(destinationZone) != null,
                    originNamed = origin.placeName != null,
                    destinationNamed = destination.placeName != null,
                )
                keep?.let { coupleTimes(it, found.durationMs) }
            }
        }
    }

    val error = when {
        departMs != null && arriveMs != null && arriveMs <= departMs ->
            stringResource(R.string.addtrip_error_order)
        departMs != null && departMs > System.currentTimeMillis() ->
            stringResource(R.string.addtrip_error_future)
        else -> null
    }
    // Resolved here: the save callback below runs outside the composition.
    val overlapsMessage = stringResource(R.string.addtrip_overlaps)
    val goneMessage = stringResource(R.string.addtrip_gone)
    // The pins are asked about separately rather than taken as implied by the times: a typed time
    // does wait for its pin, but a drafted one arrives without asking (a gap side the recorder
    // never fixed knows when it was and not where).
    val ready = activity != null &&
        origin.pin != null &&
        destination.pin != null &&
        departMs != null &&
        arriveMs != null &&
        error == null

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The saved places are on the map to be picked as an end, so they stand only while one is
    // being placed: an end with no pin, or one whose card was tapped to move it.
    var repinning by remember(draft) { mutableStateOf(false) }
    val showPlaces = repinning || origin.pin == null || destination.pin == null

    fun placePin(at: Coordinate, placeName: String? = null) {
        // Placing a pin — from a search result, a map pin or a long press — is done with the
        // field: drop its focus and the keyboard with it, or the results list closes onto a
        // keyboard covering the cards it just filled in.
        focusManager.clearFocus()
        keyboard?.hide()
        val end = if (placingDestination) destination else origin
        end.pin = at
        end.placeName = placeName
        if (end === origin && destination.pin == null) placingDestination = true
        repinning = false
    }

    // Tapping an end makes it the one the map places, and where it already has a pin takes the
    // camera there: the end you can see is the one you are navigating away from to find the other,
    // and on a form opened from a gap it is the only ground on screen worth starting from. Asked
    // again on every tap, including a tap on the end that is already active — panning away and
    // tapping the card is the way back.
    fun activate(end: TripEnd) {
        placingDestination = end === destination
        end.pin?.let { centerRequest = MapCenterRequest(it) }
        repinning = true
    }

    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    val (originPlace, destinationPlace) = remember(
        storedPlaces, origin.pin, origin.placeName, destination.pin, destination.placeName,
    ) {
        TripPlaces.plan(
            listOf(TripPlaces.End(origin.pin, origin.placeName), TripPlaces.End(destination.pin, destination.placeName)),
            storedPlaces,
            AndroidDistance,
            // The preview writes nothing, so its rows are never stamped.
            createdAt = 0,
        )
    }
    val cover = remember { MapCover() }
    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(
            rememberStandardBottomSheetState(initialValue = SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.background,
        sheetPeekHeight = BottomSheetDefaults.SheetPeekHeight +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(
                Modifier.onGloballyPositioned { cover.sheetTop = it.boundsInWindow().top },
            )
        },
        sheetContent = {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Owed while a road the servers drew is on the map.
                if (leg != null) OsmDataCredit(Modifier.padding(horizontal = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TripEndCard(
                        shape = groupedRowShape(0, 3),
                        mark = endMark(originPlace, ManualPath.End.ORIGIN in continuing),
                        atRes = R.string.addtrip_departure_at,
                        setTimeRes = R.string.addtrip_set_departure,
                        end = origin,
                        city = originCity,
                        zone = originZone,
                        active = !placingDestination,
                        onActivate = { activate(origin) },
                        onEditTime = {
                            editing = TimeEdit(
                                ManualPath.End.ORIGIN, R.string.addtrip_departure_time_title, originZone,
                                origin.pickerStart(originZone, noonOf(draft.day, originZone)),
                            )
                        },
                        kept = keepChoice?.let { keptEnd() == ManualPath.End.ORIGIN },
                        keepRes = R.string.addtrip_keep_departure,
                        onToggleKeep = { toggleKeep(ManualPath.End.ORIGIN) },
                    )
                    TripLegRow(
                        shape = groupedRowShape(1, 3),
                        activity = activity,
                        lengthM = leg?.lengthM ?: origin.pin?.let { from ->
                            destination.pin?.let { to -> ManualPath.lengthM(listOf(from, to), AndroidDistance) }
                        },
                        departMs = departMs,
                        departZone = originZone,
                        arriveMs = arriveMs,
                        arriveZone = destinationZone,
                    )
                    TripEndCard(
                        shape = groupedRowShape(2, 3),
                        mark = endMark(destinationPlace, ManualPath.End.DESTINATION in continuing),
                        atRes = R.string.addtrip_arrival_at,
                        setTimeRes = R.string.addtrip_set_arrival,
                        end = destination,
                        city = destinationCity,
                        zone = destinationZone,
                        active = placingDestination,
                        onActivate = { activate(destination) },
                        onEditTime = {
                            // An hour past the departure, on the arrival's own clock: the pickers open a
                            // short scroll from any real arrival instead of at an arbitrary noon.
                            editing = TimeEdit(
                                ManualPath.End.DESTINATION, R.string.addtrip_arrival_time_title, destinationZone,
                                destination.pickerStart(
                                    destinationZone,
                                    departMs?.let { Instant.ofEpochMilli(it + HOUR_MS).atZone(destinationZone) }
                                        ?: noonOf(draft.day, destinationZone),
                                ),
                            )
                        },
                        kept = keepChoice?.let { keptEnd() == ManualPath.End.DESTINATION },
                        keepRes = R.string.addtrip_keep_arrival,
                        onToggleKeep = { toggleKeep(ManualPath.End.DESTINATION) },
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Text(
                        stringResource(
                            if (draft.editing != null) {
                                R.string.addtrip_title_edit
                            } else {
                                R.string.addtrip_title_add
                            },
                        ),
                    )
                },
                navigationIcon = { BackNavIcon(onClose) },
                actions = {
                    SaveAction(enabled = ready) {
                        val type = activity ?: return@SaveAction
                        val from = origin.pin ?: return@SaveAction
                        val to = destination.pin ?: return@SaveAction
                        if (departMs == null || arriveMs == null) return@SaveAction
                        viewModel.saveManualTrack(
                            draft.editing?.trackId,
                            TrackListViewModel.ManualTrip(
                                type,
                                TrackListViewModel.ManualTripEnd(
                                    TrackRepository.ManualEnd(from, departMs),
                                    origin.placeName,
                                ),
                                TrackListViewModel.ManualTripEnd(
                                    TrackRepository.ManualEnd(to, arriveMs),
                                    destination.placeName,
                                ),
                                leg?.shape.orEmpty(),
                            ),
                        ) { result ->
                            val refusal = when (result) {
                                is TrackRepository.ManualTrackResult.Saved -> null
                                TrackRepository.ManualTrackResult.Overlapping -> overlapsMessage
                                TrackRepository.ManualTrackResult.NotEditable -> goneMessage
                            }
                            if (refusal == null) {
                                onClose()
                            } else {
                                viewModel.say(refusal)
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            val queryState = remember { TextFieldState() }
            val query by remember(queryState) { derivedStateOf { queryState.text.toString() } }
            // An unnamed row reads as the unnamed cluster it seeds, which this form neither draws
            // nor offers.
            val savedPlaces = remember(storedPlaces) { storedPlaces.filter { it.isNamed } }
            val placeField = remember(savedPlaces) {
                savedPlaces.map { place ->
                    OverviewPlace(
                        marker = PlaceMarker(place.pin, place),
                        key = PlaceResolver.keyOf(place.id),
                    )
                }
            }
            // Pre-folded once per list — PlaceSearch's contract for filtering per keystroke.
            val foldedLabels = remember(savedPlaces) { savedPlaces.map { PlaceSearch.fold(it.label.orEmpty()) } }
            // Nearest what the map is looking at first: a name is rarely unique across a history —
            // "Home", "Hotel", the same shop in three cities — and the ground on screen is the only
            // thing said about which one is meant. Falls back to the table's order until the map has
            // settled once, which is the order this list had before there was anything to sort by.
            val placeMatches = remember(query, savedPlaces, foldedLabels, mapCenter) {
                val needle = PlaceSearch.fold(query)
                if (needle.isEmpty()) {
                    emptyList()
                } else {
                    val matches = savedPlaces.filterIndexed { i, _ -> foldedLabels[i].contains(needle) }
                    val from = mapCenter
                    if (from == null) {
                        matches
                    } else {
                        // Measured once per place and sorted on the answer: a comparator that
                        // measured would run an ellipsoidal solve on both sides of every
                        // comparison, and a one-letter query can match hundreds of places.
                        matches.map { it to AndroidDistance.meters(from.lat, from.lon, it.lat, it.lon) }
                            .sortedBy { (_, meters) -> meters }
                            .map { (place, _) -> place }
                    }
                }
            }
            // Where each matched place sits, so a saved pin says as much about itself as a city or
            // an online hit does — two spots the user called "Mum's" are told apart by nothing else.
            // Filled in as rows appear and kept for the form's life: typing a name walks the same
            // handful of places on every keystroke, and a stored null is an answer (nothing in the
            // atlas reaches there), not a miss to retry.
            val placeCities = remember { mutableStateMapOf<Long, CityAtlas.City?>() }
            LaunchedEffect(placeMatches) {
                for (place in placeMatches) {
                    if (place.id in placeCities) continue
                    placeCities[place.id] = viewModel.cityAt(place.pin)
                }
            }
            val cityHits by produceState(emptyList<CityAtlas.Hit>(), query) {
                value = if (query.isBlank()) {
                    emptyList()
                } else {
                    // Let typing settle before a scan of 160k names; the field filter above is
                    // cheap enough to run unthrottled.
                    delay(150)
                    viewModel.searchCities(query, limit = 6)
                }
            }
            fun pickResult(at: Coordinate, placeName: String? = null) {
                placePin(at, placeName)
                queryState.clearText()
                // A pin picked by name can land anywhere relative to the other, at a zoom that
                // shows neither; one placed on the map is already where the user is looking.
                if (origin.pin != null && destination.pin != null) frameKey++
            }
            val onlineHits by rememberOnlineHits(query, near = origin.pin ?: destination.pin, viewModel)
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        cover.mapTop = bounds.top
                        cover.mapBottom = bounds.bottom
                    },
            ) {
                MapLibreTripMap(
                    origin = origin.pin,
                    destination = destination.pin,
                    places = placeField,
                    showPlaces = showPlaces,
                    shape = leg?.shape.orEmpty(),
                    activity = activity,
                    touching = touching.toList().filterNotNull(),
                    originCategory = (originPlace as? TripPlaces.Outcome.Held)?.place?.placeCategory,
                    destinationCategory = (destinationPlace as? TripPlaces.Outcome.Held)?.place?.placeCategory,
                    continuing = continuing,
                    onLongPress = ::placePin,
                    onPlaceTap = ::placePin,
                    center = centerRequest,
                    frameKey = frameKey,
                    onCenterSettled = { mapCenter = it },
                    obscured = cover::insets,
                    modifier = Modifier.fillMaxSize(),
                )
                // Its corner rises with the sheet, which would otherwise cover it.
                Box(Modifier.fillMaxSize().offset { IntOffset(0, -cover.insets().bottom) }) {
                    FrameControl(stringResource(R.string.addtrip_show_whole_trip)) {
                        frameKey++
                    }
                }
                val halo = touchHalo(FilterChipDefaults.Height)
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(top = MapInset)
                        .onGloballyPositioned { cover.headerBottom = it.boundsInWindow().bottom },
                    // Negative where the halos exceed the gap: the touch targets overlap, the
                    // chips do not.
                    verticalArrangement = Arrangement.spacedBy(8.dp - halo * 2),
                ) {
                    SearchDropdown(
                        query = query,
                        open = query.isNotBlank(),
                        field = { anchor ->
                            SearchField(
                                state = queryState,
                                placeholder = stringResource(R.string.addtrip_search_placeholder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MapInset)
                                    .padding(bottom = halo)
                                    .shadow(3.dp, CircleShape)
                                    .then(anchor),
                            )
                        },
                    ) {
                        for (place in placeMatches) {
                            val label = place.label ?: continue
                            SearchResultRow(
                                icon = Icons.Filled.Place,
                                label = label,
                                detail = placeCities[place.id]?.let { localityLabel(it) },
                            ) {
                                pickResult(place.pin, label)
                            }
                        }
                        for (hit in cityHits) {
                            SearchResultRow(
                                icon = Icons.Filled.LocationCity,
                                label = hit.name,
                                detail = countryDisplayName(hit.country),
                            ) {
                                pickResult(Coordinate(hit.lat, hit.lon))
                            }
                        }
                        // Below the local sections, minus what they already show — the
                        // geocoder returns big cities too, and "Lisbon" twice reads as a
                        // glitch, not as two sources.
                        val online = remember(cityHits, onlineHits) {
                            val cities = cityHits.map { PlaceSearch.fold(it.name) }.toSet()
                            OnlinePlaceSearch.distinctRows(
                                onlineHits.filter { PlaceSearch.fold(it.name) !in cities },
                            )
                        }
                        for (hit in online) {
                            SearchResultRow(
                                icon = Icons.Filled.TravelExplore,
                                label = hit.name,
                                detail = hit.locality,
                            ) {
                                // The one pick that knows its own name — carried so the
                                // commit can create the place this end will have stayed at.
                                pickResult(Coordinate(hit.lat, hit.lon), hit.name)
                            }
                        }
                        if (online.isNotEmpty()) OsmCredit()
                        if (placeMatches.isEmpty() && cityHits.isEmpty() && online.isEmpty()) {
                            Text(
                                stringResource(R.string.addtrip_no_matches),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = MapInset),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (type in MANUAL_TRIP_TYPES) {
                            MapFilterChip(
                                selected = activity == type,
                                label = stringResource(type.labelRes),
                                leadingIcon = {
                                    Icon(activityIcon(type), contentDescription = null, Modifier.size(18.dp))
                                },
                            ) { activity = type }
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = MapInset),
                        verticalArrangement = Arrangement.spacedBy(8.dp - halo * 2),
                    ) {
                        // The switch on the Privacy page takes the offer away entirely rather than
                        // leaving a chip that does nothing. On, the chip is the path's own
                        // description.
                        val roadTrip = profile != null && origin.pin != null && destination.pin != null
                        if (routingOn && roadTrip) {
                            MapFilterChip(
                                selected = leg != null,
                                enabled = !fetchingRoute,
                                label = if (leg == null) {
                                    stringResource(R.string.addtrip_follow_roads)
                                } else {
                                    stringResource(
                                        R.string.addtrip_along_roads,
                                        distanceText(leg.lengthM),
                                        durationText(leg.durationMs),
                                    )
                                },
                            ) {
                                if (leg == null) {
                                    followRoads()
                                } else {
                                    routed = null
                                    frameKey++
                                }
                            }
                        }
                        MapStackLegend {
                            Text(
                                stringResource(
                                    if (placingDestination) {
                                        R.string.addtrip_tap_destination
                                    } else {
                                        R.string.addtrip_tap_origin
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { edit ->
        val end = if (edit.which == ManualPath.End.ORIGIN) origin else destination
        val closeDialogs = {
            editing = null
            editingDate = null
        }
        // Keyed on the end being edited: the picker states live at one call site, and without the
        // key, opening the arrival picker would show whatever the departure picker last held.
        key(end) {
            val chosenDate = editingDate
            if (chosenDate == null) {
                LocalDateDialog(
                    initial = edit.start.toLocalDate(),
                    confirmLabel = stringResource(R.string.addtrip_next),
                    onConfirm = { editingDate = it },
                    onDismiss = closeDialogs,
                )
            } else {
                val initial = edit.start.toLocalTime()
                val timeState = rememberTimePickerState(
                    initialHour = initial.hour,
                    initialMinute = initial.minute,
                )
                TimePickerDialog(
                    onDismissRequest = closeDialogs,
                    title = { Text(stringResource(edit.titleRes)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                end.date = chosenDate
                                end.time = LocalTime.of(timeState.hour, timeState.minute)
                                end.coupledMs = null
                                leg?.let { coupleTimes(edit.which, it.durationMs) }
                                closeDialogs()
                            },
                        ) { Text(stringResource(R.string.common_ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = closeDialogs) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    },
                ) { TimePicker(timeState) }
            }
        }
    }
}

/**
 * How an end's row is marked, as the timeline will mark the stay there: the place it will show, if
 * any ([TripPlaces.plan]) — or a point where it carries straight on from a recorded track and no
 * stay will stand.
 */
private class EndMark(val icon: ImageVector, val disc: DiscStyle, val name: String?)

@Composable
private fun endMark(place: TripPlaces.Outcome, continues: Boolean): EndMark {
    val neutral = placeDiscStyle(null)
    val name = place.name
    return when {
        continues -> EndMark(Icons.Filled.FiberManualRecord, neutral, name)
        place is TripPlaces.Outcome.Held ->
            EndMark(place.place.placeCategory.discIcon, placeDiscStyle(place.place.placeCategory), name)
        name != null -> EndMark(Icons.Filled.Place, neutral, name)
        else -> EndMark(Icons.Outlined.Place, neutral, null)
    }
}

/** Where a spot is, as this form says it throughout: the atlas's city and its country spelled
 *  out. One spelling, so a place named in the results list and the same place named on an end's
 *  card cannot read as two different answers. */
@Composable
private fun localityLabel(city: CityAtlas.City): String =
    "${city.name}, ${countryDisplayName(city.country)}"

/**
 * One end of the trip as the timeline row of the stay it will leave or arrive at. The time line
 * waits for the pin, because until one is placed there is no zone to read the time in.
 */
@Composable
private fun TripEndCard(
    shape: RoundedCornerShape,
    mark: EndMark,
    /** "Departure %s, " — prefixes the date, ahead of a clock time this card draws itself. */
    @StringRes atRes: Int,
    /** The whole invitation ("Set departure time"), not a noun something appends "time" to. */
    @StringRes setTimeRes: Int,
    end: TripEnd,
    city: CityAtlas.City?,
    zone: ZoneId,
    active: Boolean,
    onActivate: () -> Unit,
    onEditTime: () -> Unit,
    /** Whether this end's time is the one a road's duration places the other from; null where
     *  there is no choice to offer. */
    kept: Boolean?,
    @StringRes keepRes: Int,
    onToggleKeep: () -> Unit,
) {
    val pin = end.pin
    val title = when {
        mark.name != null -> mark.name
        city != null -> localityLabel(city)
        pin != null -> stringResource(R.string.addtrip_pin_placed)
        else -> stringResource(R.string.addtrip_no_pin)
    }
    Card(
        onClick = onActivate,
        shape = shape,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconDisc(mark.icon, mark.disc, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = placeTitleColor(mark.name != null),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Rendered off the same instant the row will store, in the app's own formats — the
                // year included, because a hand-entered trip is usually months old — and the time
                // marked with its shift from the reader's clock, exactly as the timeline will mark
                // the committed trip's.
                val at = end.epochIn(zone)
                val reader = timelineZone()
                val shiftColor = zoneShiftColor
                // The line is a control, not a caption — it opens both pickers — so it takes a row's
                // worth of height and a bounded ripple rather than the glyph box a clickable Text
                // would leave for a finger.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (at != null) {
                            annotatedStringResource(
                                atRes,
                                at.toLocalDate(zone).format(compactDayYearFormat),
                                markedTime(at, zone, reader, shiftColor, LocalReaderClock.current),
                            )
                        } else {
                            AnnotatedString(stringResource(setTimeRes))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pin != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = pin != null, role = Role.Button, onClick = onEditTime)
                            .heightIn(min = 48.dp)
                            .wrapContentHeight(),
                    )
                    if (kept != null) {
                        IconToggleButton(checked = kept, onCheckedChange = { onToggleKeep() }) {
                            Icon(
                                if (kept) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                                contentDescription = stringResource(keepRes),
                            )
                        }
                    }
                }
                // Which clock the pickers will write on, said before a time exists to carry the
                // mark — as an offset from the reader's own, the way the timeline says it, never as
                // a zone id. Gone once a time is set (the superscript above takes over), and absent
                // entirely on the reader's own clock, where there is nothing to warn about.
                if (pin != null && at == null) {
                    zoneShiftLabel(System.currentTimeMillis(), zone, reader)?.let { shift ->
                        Text(
                            stringResource(R.string.addtrip_local_clock, shift),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The trip as its timeline track row will read once saved. */
@Composable
private fun TripLegRow(
    shape: RoundedCornerShape,
    activity: ActivityType?,
    lengthM: Double?,
    departMs: Long?,
    departZone: ZoneId,
    arriveMs: Long?,
    arriveZone: ZoneId,
) {
    val name = activity?.let { stringResource(it.labelRes) }
    val length = if (departMs != null && arriveMs != null && arriveMs > departMs) durationText(departMs, arriveMs) else null
    ListRowCard(
        shape = shape,
        icon = activityIcon(activity),
        disc = activityDiscStyle(activity),
        iconDescription = name,
        title = name?.let { trackRowTitle(it, lengthM) } ?: stringResource(R.string.addtrip_choose_type),
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitle = trackRowSpan(departMs, departZone, arriveMs, arriveZone, length),
    )
}
