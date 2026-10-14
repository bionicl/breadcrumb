package io.github.valeronm.breadcrumb.domain

/** Decides when a detected pause deserves its own place row on the timeline. */
object StationaryVisitPolicy {
    const val MIN_SEPARATE_VISIT_MS = 4 * 60_000L

    /** Unknown or reversed bounds are not enough evidence to create a separate visit. */
    fun qualifies(startMs: Long, endMs: Long?): Boolean =
        endMs != null && endMs >= startMs && endMs - startMs >= MIN_SEPARATE_VISIT_MS

    /** An open stay can qualify once the current clock has observed four complete minutes. */
    fun qualifies(startMs: Long, endMs: Long?, nowMs: Long): Boolean =
        qualifies(startMs, endMs ?: nowMs)
}
