package io.github.valeronm.breadcrumb.domain

/**
 * Turns the jittery Activity-Recognition stream into a *trusted* activity signal: raw readings
 * arrive as transitions and snapshots, out of order and repeated, and this reports only when the
 * trusted activity actually changes. Also the one place a second witness may overrule that stream:
 * a reading the ground cannot vouch for is **parked**, not dropped — see [onReading] — and lands
 * later, either because the ground came round ([onMotion]) or because the recorder stopped waiting
 * ([releaseHeld]). Which of those two may end a hold is decided by its kind; see [Hold].
 *
 * A pure signal filter — no timing, windows, or track vocabulary: whether a change opens a track or
 * closes one is a track-lifecycle question [TrackController] owns; *when* a parked reading is
 * reconsidered is the recorder's, stamped with its own clock.
 *
 * [footCeiling] is the fastest any label in the foot family claims to go — supplied rather than
 * looked up, so the rule stays pure and its suite needs no ceiling table.
 */
class ActivityGate(private val footCeiling: Speed = Speed.UNLIMITED) {

    /** The trusted activity — STILL until a moving activity is confirmed. */
    var confirmed: ActivityType = ActivityType.STILL
        private set

    /**
     * Why a reading is being held, which decides how it may be released. **The two are not the same
     * kind of doubt**: [CONTRADICTED] is the ground positively disagreeing, and no deadline may end
     * it; [UNCORROBORATED] is the ground saying nothing at all, and silence must not hold a reading
     * forever, so the recorder caps it with its own clock. A hold's kind follows the latest verdict
     * ([onMotion]), whichever kind it was raised as.
     */
    enum class Hold { CONTRADICTED, UNCORROBORATED }

    /**
     * A reading waiting for the ground. One slot: a newer reading always supersedes an older one,
     * since the older describes a moment that has passed.
     */
    data class Held(val activity: ActivityType, val kind: Hold)

    var held: Held? = null
        private set

    /** The held reading, or null. */
    val parked: ActivityType? get() = held?.activity

    /**
     * Null when [raw] leaves the trusted activity unchanged — or when the ground cannot vouch for
     * it, in which case the reading is parked to land later. A doubted reading is parked rather
     * than refused because the Activity-Recognition stream is edge-triggered: Play Services
     * announces that the user *became* still and, having announced it, will not say so again —
     * discard the edge and the recorder never learns of the stop at all, the track running until
     * something else happens to close it; holding the reading keeps the edge and costs only the
     * delay.
     *
     * **Whether a stop arriving takes positive corroboration is the caller's policy**, carried by
     * [requireCorroboration], because the waiting it implies is measured on a clock this filter
     * does not have; a hold already raised follows [onMotion] instead. With it set, ground that
     * says nothing holds a STILL as [Hold.UNCORROBORATED] — the two errors are not symmetric, and a
     * stop applied early ends a journey and loses the rest of it while one applied late costs a
     * little GPS and a tail the edge-stay rule trims. Left
     * unset, a reading under [Motion.Unknown] lands exactly as it did before there was a second
     * witness, which is what its own suite pins.
     */
    fun onReading(
        raw: ActivityType,
        motion: Motion = Motion.Unknown,
        requireCorroboration: Boolean = false,
    ): ActivityType? {
        // Nothing to park when the gate already believes the reading: there is no edge to lose.
        if (raw == confirmed) {
            held = null
            return null
        }
        val doubt = when {
            tooFastFor(raw, motion) -> Hold.CONTRADICTED
            raw == ActivityType.STILL && motion is Motion.Unknown && requireCorroboration ->
                Hold.UNCORROBORATED
            else -> null
        }
        if (doubt != null) {
            held = Held(raw, doubt)
            return null
        }
        return land(raw)
    }

    /**
     * Reconsider the parked reading against a fresh [motion], returning it once the ground vouches
     * for it — null when nothing is parked or it still stands:
     *
     *  - Ground too fast for the reading makes the hold [Hold.CONTRADICTED], whatever it was.
     *  - [Motion.Unknown] makes it [Hold.UNCORROBORATED] rather than landing it: a walk's window
     *    abstains for a moment on a turn, its halves drawing closer than the moving bar.
     *  - Anything else lands it: [Motion.Stopped], or ground slow enough to explain the reading. That
     *    bounds the hold on a foot reading — the walk that follows a drive waits only for the
     *    trailing window to slow to a human pace.
     */
    fun onMotion(motion: Motion): ActivityType? {
        val waiting = held ?: return null
        val kind = when {
            tooFastFor(waiting.activity, motion) -> Hold.CONTRADICTED
            motion is Motion.Unknown -> Hold.UNCORROBORATED
            else -> return land(waiting.activity)
        }
        held = waiting.copy(kind = kind)
        return null
    }

    /**
     * Land an [Hold.UNCORROBORATED] hold although the ground never vouched for it. Null when nothing
     * is held.
     *
     * **Refuses a [Hold.CONTRADICTED] hold outright**, and that refusal is the whole reason the two
     * are told apart: the ground positively disagreeing is evidence a journey is under way, and a
     * deadline cannot distinguish a stale hold from a long one — which is why a cap applies only
     * once the ground has fallen silent.
     */
    fun releaseHeld(): ActivityType? {
        val waiting = held ?: return null
        if (waiting.kind == Hold.CONTRADICTED) return null
        return land(waiting.activity)
    }

    /** On (re)arm: the trusted activity resets to STILL and any held reading is dropped. */
    fun onArmed() {
        confirmed = ActivityType.STILL
        held = null
    }

    /**
     * Take [activity] as trusted on someone else's evidence — a trigger that saw the ground move
     * without Play Services saying anything. **Not a shortcut for a reading**: the stream is
     * edge-triggered, so leaving [confirmed] at STILL while a track runs would make the STILL that
     * ends the journey no change at all ([onReading] returns null on `raw == confirmed`), and the
     * stop edge was already spent before the journey began. Adopting the activity restores the edge
     * the ordinary stop path needs. Any held reading goes with it — it describes a moment this
     * supersedes.
     */
    fun adopt(activity: ActivityType) {
        land(activity)
    }

    /** A held reading is never the trusted one, so landing one always empties the slot. */
    private fun land(activity: ActivityType): ActivityType {
        held = null
        confirmed = activity
        return activity
    }

    /**
     * Whether the ground is moving faster than [raw] can explain — the one question behind both
     * contradictions, asked of the reading's own family:
     *
     *  - **STILL cannot explain any movement.** Aboard something that carries the phone the body
     *    really is still while the journey is not, and acting on the label would end the recording
     *    mid-journey and turn GPS off for the rest of it.
     *  - **A foot label cannot explain ground its own fix rule would disbelieve.** Play Services
     *    jitters mid-journey, and because the groups differ a stray walking reading does not merely
     *    mislabel — it *closes* the track and opens another, cutting one journey into rows the user
     *    has to merge back. [footCeiling] is not a claim about how fast a body travels: it is the
     *    most permissive *jump* ceiling in the foot family, the bar above which a fix under that
     *    label is already refused as noise — and the same bar the carrier case measures against, so
     *    the two rules agree about when ground has left a label behind.
     *
     * **This reaches motorway speeds and nothing slower**, which is a limit rather than a setting.
     * A car crawling in traffic moves at a pace the bar comfortably explains, and a stray foot
     * reading there is indistinguishable from the genuine drive-to-walk that ends every trip and
     * must be acted on at once — the ground says the same thing in both cases, and so does the
     * track's history. Only what follows tells them apart, and waiting for it would delay every
     * real disembark. Urban splits therefore stand, repaired by merging.
     *
     * Anything else the ground cannot contradict: a vehicle label explains any ground speed the
     * jump ceiling already let through.
     */
    private fun tooFastFor(raw: ActivityType, motion: Motion): Boolean {
        if (raw == ActivityType.STILL && motion == Motion.MovingEvidence) return true
        val ceiling = when {
            raw == ActivityType.STILL -> Speed.ZERO
            raw.trackGroup == TrackGroup.FOOT -> footCeiling
            else -> return false
        }
        return motion is Motion.Moving && motion.speed > ceiling
    }
}
