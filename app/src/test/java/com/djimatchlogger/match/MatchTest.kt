package com.djimatchlogger.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules here come from the original browser-based match logger.
 * Timestamps are handed in, so a two-period match with
 * pauses runs in microseconds rather than in real time.
 */
class MatchTest {

    private val minute = 60_000L

    @Test
    fun `a fresh match has not started`() {
        val match = Match()
        assertFalse(match.started)
        assertEquals(0, match.currentPeriod)
        assertEquals(0L, match.liveElapsedMs(nowMs = 10 * minute))
    }

    @Test
    fun `goals count per team`() {
        val match = Match()
            .logGoal(MatchTeam.HOME, 1 * minute)
            .logGoal(MatchTeam.AWAY, 2 * minute)
            .logGoal(MatchTeam.HOME, 3 * minute)

        assertEquals(2, match.goals(MatchTeam.HOME))
        assertEquals(1, match.goals(MatchTeam.AWAY))
    }

    @Test
    fun `the clock runs from the first period start`() {
        val match = Match().startNextPeriod(atMs = 5 * minute)
        assertEquals(3 * minute, match.liveElapsedMs(nowMs = 8 * minute))
        assertEquals(5 * minute, match.firstPeriodStartMs())
    }

    @Test
    fun `the clock stands still while paused`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 10 * minute)

        // Ten minutes on the clock, and it stays there however long the pause
        // lasts.
        assertEquals(10 * minute, match.liveElapsedMs(nowMs = 10 * minute))
        assertEquals(10 * minute, match.liveElapsedMs(nowMs = 25 * minute))
    }

    @Test
    fun `time spent paused is not counted after resuming`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 10 * minute)
            .togglePause(atMs = 15 * minute)

        // Twenty minutes of wall time, five of them paused.
        assertEquals(15 * minute, match.liveElapsedMs(nowMs = 20 * minute))
    }

    @Test
    fun `periods accumulate and the break between them does not count`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 20 * minute)
            .startNextPeriod(atMs = 35 * minute)

        // Twenty minutes played, a fifteen-minute break, then five more.
        assertEquals(25 * minute, match.liveElapsedMs(nowMs = 40 * minute))
        assertEquals(2, match.currentPeriod)
    }

    @Test
    fun `starting a period while paused closes the pause automatically`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 20 * minute)
            .startNextPeriod(atMs = 35 * minute)

        assertFalse(match.paused)
        val pauseEnd = match.events.single { it.type == MatchEventType.PAUSE_END }
        assertTrue("the generated pause end must be marked auto", pauseEnd.auto)
    }

    @Test
    fun `the clock stops at the end of the match`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .endMatch(atMs = 20 * minute)

        assertEquals(20 * minute, match.liveElapsedMs(nowMs = 90 * minute))
    }

    @Test
    fun `undo on an empty match does nothing`() {
        val (match, removed) = Match().undo()
        assertNull(removed)
        assertEquals(0, match.events.size)
    }

    @Test
    fun `undo reverses what the event changed`() {
        val (match, removed) = Match().startNextPeriod(atMs = 0).undo()

        assertEquals(MatchEventType.PERIOD_START, removed?.type)
        assertEquals(0, match.currentPeriod)
        assertFalse(match.started)
    }

    /**
     * Starting a period out of an open pause writes two events for one tap.
     * Undo has to take both, or the match is left paused with no way back.
     */
    @Test
    fun `undo removes an auto pause end together with its period start`() {
        val paused = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 20 * minute)
        val (match, removed) = paused.startNextPeriod(atMs = 35 * minute).undo()

        assertEquals(MatchEventType.PERIOD_START, removed?.type)
        assertEquals(1, match.currentPeriod)
        assertTrue("the pause must be open again", match.paused)
        assertEquals(paused.events, match.events)
    }

    @Test
    fun `undo does not touch a pause end the operator made themselves`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 10 * minute)
            .togglePause(atMs = 15 * minute)
            .startNextPeriod(atMs = 20 * minute)

        val (undone, _) = match.undo()
        assertEquals(1, undone.currentPeriod)
        assertFalse("a manual resume must stay resumed", undone.paused)
    }

    @Test
    fun `undoing the end of a match reopens it`() {
        val (match, _) = Match()
            .startNextPeriod(atMs = 0)
            .endMatch(atMs = 20 * minute)
            .undo()

        assertFalse(match.ended)
        assertEquals(20 * minute, match.liveElapsedMs(nowMs = 20 * minute))
    }

    @Test
    fun `added time does nothing before the match starts`() {
        val match = Match().stepAddedTime(1, atMs = 0)
        assertEquals(0, match.events.size)
        assertEquals(0, match.addedTimeThisPeriod)
    }

    @Test
    fun `added time accumulates and never goes below zero`() {
        var match = Match().startNextPeriod(atMs = 0)
        match = match.stepAddedTime(1, atMs = 1 * minute)
        match = match.stepAddedTime(1, atMs = 2 * minute)
        assertEquals(2, match.addedTimeThisPeriod)

        match = match.stepAddedTime(-1, atMs = 3 * minute)
        assertEquals(1, match.addedTimeThisPeriod)

        val before = match.events.size
        match = match.stepAddedTime(-1, atMs = 4 * minute)
        match = match.stepAddedTime(-1, atMs = 5 * minute)
        assertEquals(0, match.addedTimeThisPeriod)
        // The step that would have gone negative logs nothing at all.
        assertEquals(before + 1, match.events.size)
    }

    @Test
    fun `a new period resets added time`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .stepAddedTime(2, atMs = 1 * minute)
            .startNextPeriod(atMs = 20 * minute)

        assertEquals(0, match.addedTimeThisPeriod)
    }

    @Test
    fun `notes are truncated rather than rejected`() {
        val long = "x".repeat(Match.MAX_NOTE_LENGTH * 2)
        val match = Match().logNote(long, atMs = 0)
        assertEquals(Match.MAX_NOTE_LENGTH, match.events.single().note.length)
    }

    @Test
    fun `camera confirmation is recorded separately from whether it applies`() {
        val match = Match().logVideoStart(
            atMs = 0,
            camerasConfirmedValid = true,
            camerasConfirmed = false,
        )
        val event = match.events.single()

        // "We asked and they did not confirm" must not read the same as
        // "we never asked at all".
        assertTrue(event.camerasConfirmedValid)
        assertFalse(event.camerasConfirmed)
    }

    /**
     * The event log puts a match time next to every entry, which means asking
     * the clock about a moment that already has later events after it.
     */
    @Test
    fun `the clock can be read at a moment in the past`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .logGoal(MatchTeam.HOME, atMs = 8 * minute)
            .togglePause(atMs = 20 * minute)
            .startNextPeriod(atMs = 35 * minute)
            .logGoal(MatchTeam.AWAY, atMs = 40 * minute)

        val first = match.events.first { it.type == MatchEventType.GOAL }
        val second = match.events.last { it.type == MatchEventType.GOAL }

        assertEquals(8 * minute, match.liveElapsedMs(first.atMs))
        // Twenty minutes in period one, five into period two.
        assertEquals(25 * minute, match.liveElapsedMs(second.atMs))
    }

    @Test
    fun `reading the clock at a period start gives that period's kick-off time`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 20 * minute)
            .startNextPeriod(atMs = 35 * minute)

        val second = match.events.last { it.type == MatchEventType.PERIOD_START }
        assertEquals(20 * minute, match.liveElapsedMs(second.atMs))
    }

    @Test
    fun `the period clock restarts every period while the match clock runs on`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 20 * minute)
            .startNextPeriod(atMs = 35 * minute)

        val now = 40 * minute
        assertEquals(25 * minute, match.liveElapsedMs(now))
        assertEquals(5 * minute, match.periodElapsedMs(now))
    }

    @Test
    fun `the period clock excludes pauses and stands still during one`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 10 * minute)

        assertEquals(10 * minute, match.periodElapsedMs(nowMs = 25 * minute))

        val resumed = match.togglePause(atMs = 15 * minute)
        assertEquals(15 * minute, resumed.periodElapsedMs(nowMs = 20 * minute))
    }

    @Test
    fun `the period clock reads zero before kick-off and stops at full time`() {
        assertEquals(0L, Match().periodElapsedMs(nowMs = 10 * minute))

        val match = Match()
            .startNextPeriod(atMs = 0)
            .endMatch(atMs = 20 * minute)
        assertEquals(20 * minute, match.periodElapsedMs(nowMs = 90 * minute))
    }

    /**
     * Ending a match without resuming first used to hand back the pause as
     * playing time, on both clocks.
     */
    @Test
    fun `ending the match while paused does not count the pause`() {
        val match = Match()
            .startNextPeriod(atMs = 0)
            .togglePause(atMs = 20 * minute)
            .endMatch(atMs = 30 * minute)

        assertEquals(20 * minute, match.liveElapsedMs(nowMs = 40 * minute))
        assertEquals(20 * minute, match.periodElapsedMs(nowMs = 40 * minute))
    }
}
