package com.djimatchlogger.data

import com.djimatchlogger.match.Match
import com.djimatchlogger.match.MatchMeta
import com.djimatchlogger.match.MatchTeam
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The file is the deliverable, not a side effect: it outlives the app and gets
 * read on a PC months later. These pin the shape it is read by.
 */
class MatchStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val minute = 60_000L

    /** A fixed pair, so every derived time of day is arithmetic, not a clock. */
    private val wallAtKickoff = 1_757_170_800_000L
    private val monotonicAtKickoff = 5_000_000L

    private fun store() = MatchStore(folder.root)

    private fun playedMatch(): Match = Match(meta = MatchMeta(home = "Ajax", away = "Feyenoord"))
        .startNextPeriod(atMs = monotonicAtKickoff)
        .logGoal(MatchTeam.HOME, atMs = monotonicAtKickoff + 4 * minute)
        .logYellowCard(MatchTeam.AWAY, atMs = monotonicAtKickoff + 7 * minute)

    private fun write(match: Match): JSONObject {
        val store = store()
        store.open(match, wallAtKickoff, monotonicAtKickoff)
        return JSONObject(store.write(match).getOrThrow().readText())
    }

    @Test
    fun `the file carries the metadata and the whole log`() {
        val json = write(playedMatch())
        val meta = json.getJSONObject("meta")

        assertEquals("Ajax", meta.getString("home"))
        assertEquals("Feyenoord", meta.getString("away"))
        assertEquals(3, json.getJSONArray("events").length())
    }

    @Test
    fun `every event has a timestamp and lowercase fields`() {
        val goal = write(playedMatch()).getJSONArray("events").getJSONObject(1)

        assertEquals("goal", goal.getString("type"))
        assertEquals("home", goal.getString("team"))
        assertFalse(goal.has("clock"))
        // Four minutes after the anchor, derived from it rather than read from
        // a clock — that is what makes the footage line up afterwards.
        assertEquals(
            MatchStore.timestamp(wallAtKickoff + 4 * minute),
            goal.getString("ts"),
        )
    }

    /**
     * Confirmation and auto flags are no longer written to the file, as they
     * are not needed by the receiver.
     */
    @Test
    fun `unnecessary flags are not written`() {
        val json = write(
            playedMatch().logVideoStart(
                atMs = monotonicAtKickoff,
                camerasConfirmedValid = true,
                camerasConfirmed = false,
            ),
        )
        val events = json.getJSONArray("events")
        val start = events.getJSONObject(events.length() - 1)
        assertFalse(start.has("camerasConfirmed"))
        
        val pause = write(playedMatch().togglePause(monotonicAtKickoff + 10 * minute))
            .getJSONArray("events").getJSONObject(3)
        assertFalse(pause.has("auto"))
    }

    @Test
    fun `the file is named after the day and the two teams`() {
        val file = store().open(playedMatch(), wallAtKickoff, monotonicAtKickoff)!!

        assertTrue(file.name, file.name.endsWith("-Ajax-vs-Feyenoord.json"))
        assertEquals(MatchStore.DIRECTORY, file.parentFile?.name)
    }

    /** Team names come from an operator; file names go to a filesystem. */
    @Test
    fun `awkward team names cannot break the file name`() {
        val match = Match(meta = MatchMeta(home = "S.V. 't Gooi/1", away = ""))
            .startNextPeriod(atMs = monotonicAtKickoff)
        val file = store().open(match, wallAtKickoff, monotonicAtKickoff)!!

        assertTrue(file.name, file.name.endsWith("-S_V_t_Gooi_1-vs-away.json"))
        assertFalse(file.name.contains("/"))
    }

    @Test
    fun `rewriting replaces the file rather than appending to it`() {
        val store = store()
        val match = playedMatch()
        store.open(match, wallAtKickoff, monotonicAtKickoff)
        store.write(match).getOrThrow()

        val later = match.logGoal(MatchTeam.AWAY, atMs = monotonicAtKickoff + 9 * minute)
        val json = JSONObject(store.write(later).getOrThrow().readText())

        assertEquals(4, json.getJSONArray("events").length())
    }

    @Test
    fun `the newest file is the one offered for sharing`() {
        val store = store()
        store.open(playedMatch(), wallAtKickoff, monotonicAtKickoff)
        val written = store.write(playedMatch()).getOrThrow()

        assertEquals(written.absolutePath, store.latestFile()?.absolutePath)
    }

    @Test
    fun `writing without a file open fails rather than writing somewhere`() {
        assertTrue(store().write(playedMatch()).isFailure)
    }
}
