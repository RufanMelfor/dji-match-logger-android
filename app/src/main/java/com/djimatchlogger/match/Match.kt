package com.djimatchlogger.match

/**
 * What happened during a match.
 *
 * A port of the `state` object in the original browser-based match logger,
 * which is the specification for all of this. This follows it closely
 * enough that the two can be compared side by side.
 *
 * Immutable: every operation returns a new [Match]. The original mutates in place,
 * but Compose wants a value it can compare, and undo costs nothing when no
 * state is shared.
 *
 * Timestamps are **monotonic** milliseconds, never wall clock. A match that
 * outlives a clock correction, a timezone change or a daylight-saving jump
 * must not have its running time leap, and the arithmetic below subtracts
 * timestamps from each other throughout. Callers pass the timestamp in rather
 * than the model reading a clock, which also makes every rule here testable
 * without waiting for real seconds to pass.
 */
enum class MatchEventType {
    VIDEO_START,
    PERIOD_START,
    MATCH_END,
    GOAL,
    YELLOW_CARD,
    RED_CARD,
    ADDED_TIME,
    PAUSE_START,
    PAUSE_END,
    NOTE,
}

enum class MatchTeam { NONE, HOME, AWAY }

data class MatchEvent(
    val type: MatchEventType,
    /** Monotonic milliseconds. */
    val atMs: Long,
    val team: MatchTeam = MatchTeam.NONE,
    /** Set on [MatchEventType.PERIOD_START] and [MatchEventType.ADDED_TIME]. */
    val period: Int = 0,
    /** Added time: cumulative minutes for that period. */
    val minutes: Int = 0,
    /**
     * A [MatchEventType.PAUSE_END] the app generated itself, by starting the
     * next period while a pause was still open. Undo removes it together with
     * the period start that caused it — one user action, two log entries.
     */
    val auto: Boolean = false,
    /** Whether the camera-confirmation fields below mean anything at all. */
    val camerasConfirmedValid: Boolean = false,
    /** And if they do, whether both cameras confirmed in time. */
    val camerasConfirmed: Boolean = false,
    val note: String = "",
)

data class MatchMeta(
    val home: String = "",
    val away: String = "",
    /** Four by default, as in the web app. */
    val periods: Int = DEFAULT_PERIODS,
    val cardsEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_PERIODS: Int = 4
    }
}

data class Match(
    val meta: MatchMeta = MatchMeta(),
    val events: List<MatchEvent> = emptyList(),
    /** Zero means the match has not started. */
    val currentPeriod: Int = 0,
    val ended: Boolean = false,
    val paused: Boolean = false,
    val addedTimeThisPeriod: Int = 0,
) {

    val started: Boolean get() = currentPeriod > 0

    fun goals(team: MatchTeam): Int =
        events.count { it.type == MatchEventType.GOAL && it.team == team }

    fun cards(team: MatchTeam, type: MatchEventType): Int =
        events.count { it.type == type && it.team == team }

    /** When period 1 began, or null if the match has not started. */
    fun firstPeriodStartMs(): Long? =
        events.firstOrNull { it.type == MatchEventType.PERIOD_START }?.atMs

    private fun add(event: MatchEvent) = copy(events = events + event)

    fun logVideoStart(
        atMs: Long,
        camerasConfirmedValid: Boolean = false,
        camerasConfirmed: Boolean = false,
    ): Match = add(
        MatchEvent(
            type = MatchEventType.VIDEO_START,
            atMs = atMs,
            camerasConfirmedValid = camerasConfirmedValid,
            camerasConfirmed = camerasConfirmed,
        ),
    )

    fun logGoal(team: MatchTeam, atMs: Long): Match =
        add(MatchEvent(MatchEventType.GOAL, atMs, team = team))

    fun logYellowCard(team: MatchTeam, atMs: Long): Match =
        add(MatchEvent(MatchEventType.YELLOW_CARD, atMs, team = team))

    fun logRedCard(team: MatchTeam, atMs: Long): Match =
        add(MatchEvent(MatchEventType.RED_CARD, atMs, team = team))

    fun logNote(text: String, atMs: Long): Match =
        add(MatchEvent(MatchEventType.NOTE, atMs, note = text.take(MAX_NOTE_LENGTH)))

    /**
     * Begins the next period, closing an open pause first.
     *
     * That auto-generated pause end is marked [MatchEvent.auto] so undo can
     * put both back in one step.
     */
    fun startNextPeriod(atMs: Long): Match {
        var next = this
        if (paused) {
            next = next.copy(paused = false)
                .add(MatchEvent(MatchEventType.PAUSE_END, atMs, auto = true))
        }
        val period = next.currentPeriod + 1
        return next
            .copy(currentPeriod = period, addedTimeThisPeriod = 0)
            .add(MatchEvent(MatchEventType.PERIOD_START, atMs, period = period))
    }

    fun togglePause(atMs: Long): Match = if (paused) {
        copy(paused = false).add(MatchEvent(MatchEventType.PAUSE_END, atMs))
    } else {
        copy(paused = true).add(MatchEvent(MatchEventType.PAUSE_START, atMs))
    }

    /**
     * Ends the match at [atMs].
     *
     * The timestamp is passed in because a match ends at the final whistle,
     * not when the cameras get round to confirming they stopped. The cameras
     * deliberately keep rolling for a while afterwards, and that gap would
     * otherwise land in every chapter timing derived from these events.
     */
    fun endMatch(
        atMs: Long,
        camerasConfirmedValid: Boolean = false,
        camerasConfirmed: Boolean = false,
    ): Match = copy(ended = true).add(
        MatchEvent(
            type = MatchEventType.MATCH_END,
            atMs = atMs,
            camerasConfirmedValid = camerasConfirmedValid,
            camerasConfirmed = camerasConfirmed,
        ),
    )

    /**
     * Nudges added time for the current period by a minute, never below zero.
     *
     * Does nothing at all before the match starts — not even a log entry,
     * matching the web app.
     */
    fun stepAddedTime(deltaMinutes: Int, atMs: Long): Match {
        if (currentPeriod == 0) return this
        if (deltaMinutes < 0 && addedTimeThisPeriod == 0) return this
        val minutes = (addedTimeThisPeriod + deltaMinutes).coerceAtLeast(0)
        return copy(addedTimeThisPeriod = minutes).add(
            MatchEvent(
                type = MatchEventType.ADDED_TIME,
                atMs = atMs,
                period = currentPeriod,
                minutes = minutes,
            ),
        )
    }

    /**
     * Removes the most recent event and whatever state it had set.
     *
     * Returns the new match and the event that was undone, so the operator can
     * be told what just disappeared. A period start that auto-resumed a pause
     * takes its paired pause end with it.
     */
    fun undo(): Pair<Match, MatchEvent?> {
        val last = events.lastOrNull() ?: return this to null

        var next = copy(events = events.dropLast(1)).reverse(last)

        val previous = next.events.lastOrNull()
        if (last.type == MatchEventType.PERIOD_START &&
            previous?.type == MatchEventType.PAUSE_END &&
            previous.auto
        ) {
            next = next.copy(events = next.events.dropLast(1)).reverse(previous)
        }

        return next to last
    }

    /** Undoes what a single event did to the match's flags. */
    private fun reverse(event: MatchEvent): Match = when (event.type) {
        MatchEventType.PERIOD_START -> copy(currentPeriod = currentPeriod - 1)
        MatchEventType.MATCH_END -> copy(ended = false)
        MatchEventType.PAUSE_START -> copy(paused = false)
        MatchEventType.PAUSE_END -> copy(paused = true)
        else -> this
    }

    /**
     * Playing time at [nowMs], frozen while paused and excluding time already
     * spent in earlier pauses.
     *
     * Walks the whole log rather than keeping a running total, so undo needs
     * no special handling: drop the event and the number is right again.
     * Returns 0 before the match starts.
     *
     * Events later than [nowMs] are ignored, so this also answers what the
     * clock read at any moment in the past — which is how the event log shows
     * a time next to each entry.
     */
    fun liveElapsedMs(nowMs: Long): Long {
        var elapsedBeforeCurrentPeriod = 0L
        var periodStart = -1L
        var accumulatedPause = 0L
        var pauseStart = -1L

        for (event in events) {
            // The log is chronological, so from here on everything lies in the
            // future of the moment being asked about.
            if (event.atMs > nowMs) break

            when (event.type) {
                MatchEventType.PERIOD_START -> {
                    if (periodStart >= 0) {
                        elapsedBeforeCurrentPeriod +=
                            (event.atMs - periodStart - accumulatedPause).coerceAtLeast(0)
                    }
                    periodStart = event.atMs
                    accumulatedPause = 0
                    pauseStart = -1
                }

                MatchEventType.PAUSE_START -> if (pauseStart < 0) pauseStart = event.atMs

                MatchEventType.PAUSE_END -> if (pauseStart >= 0) {
                    accumulatedPause += event.atMs - pauseStart
                    pauseStart = -1
                }

                MatchEventType.MATCH_END -> {
                    if (periodStart >= 0) {
                        // A match that ends during a pause closes that pause
                        // here; without this the clock would jump forward by
                        // the length of the pause at the final whistle.
                        if (pauseStart >= 0) {
                            accumulatedPause += event.atMs - pauseStart
                            pauseStart = -1
                        }
                        elapsedBeforeCurrentPeriod +=
                            (event.atMs - periodStart - accumulatedPause).coerceAtLeast(0)
                    }
                    periodStart = -1
                }

                else -> Unit
            }
        }

        if (periodStart < 0) return elapsedBeforeCurrentPeriod

        // While paused the clock reads the moment the pause began, so it holds
        // still instead of ticking on behind the referee's back.
        val effectiveNow = if (pauseStart >= 0) pauseStart else nowMs
        return elapsedBeforeCurrentPeriod +
            (effectiveNow - periodStart - accumulatedPause).coerceAtLeast(0)
    }

    /**
     * Playing time since the current period kicked off, on the same terms as
     * [liveElapsedMs] but reset at every period start.
     *
     * The match clock says how far the game is; this one says how far the
     * period is, which is what a referee's watch shows and what the operator
     * needs to know when added time is due. Returns 0 before the match starts.
     */
    fun periodElapsedMs(nowMs: Long): Long {
        var periodStart = -1L
        var accumulatedPause = 0L
        var pauseStart = -1L
        var endedAtMs = -1L

        for (event in events) {
            if (event.atMs > nowMs) break

            when (event.type) {
                MatchEventType.PERIOD_START -> {
                    periodStart = event.atMs
                    accumulatedPause = 0
                    pauseStart = -1
                    endedAtMs = -1
                }

                MatchEventType.PAUSE_START -> if (pauseStart < 0) pauseStart = event.atMs

                MatchEventType.PAUSE_END -> if (pauseStart >= 0) {
                    accumulatedPause += event.atMs - pauseStart
                    pauseStart = -1
                }

                MatchEventType.MATCH_END -> endedAtMs = event.atMs

                else -> Unit
            }
        }

        if (periodStart < 0) return 0

        // A pause outranks the final whistle: if the match was ended while
        // paused, the clock had already stopped at the pause.
        val effectiveNow = when {
            pauseStart >= 0 -> pauseStart
            endedAtMs >= 0 -> endedAtMs
            else -> nowMs
        }
        return (effectiveNow - periodStart - accumulatedPause).coerceAtLeast(0)
    }

    companion object {
        const val MAX_NOTE_LENGTH: Int = 64
    }
}
