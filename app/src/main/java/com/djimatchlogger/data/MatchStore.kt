package com.djimatchlogger.data

import android.os.Environment
import com.djimatchlogger.match.Match
import com.djimatchlogger.match.MatchEvent
import com.djimatchlogger.match.MatchEventType
import com.djimatchlogger.match.MatchTeam
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The saved form of a match.
 *
 * Written from the kick-off onwards and rewritten after every change, rather
 * than exported once at the end. A match that ends because the phone ran out
 * of battery still happened, and an export that only exists if somebody
 * remembers to ask for it is an export that will one day be missing.
 *
 * Every event carries two times. `clock` is the match clock, which is what a
 * report gets written from. `time` is the time of day, which is what pairs the
 * event with a point in the footage — the cameras know nothing about periods
 * or stoppages.
 *
 * Times of day are derived, not stored. The model runs on a monotonic clock so
 * that a network time correction mid-match cannot move anything; the wall clock
 * is anchored once, when the file is opened, and every timestamp is worked out
 * from that.
 */
class MatchStore(private val rootDir: File? = null) {

    /** Where the files go. */
    val directory: File get() = File(
        rootDir ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        DIRECTORY
    )

    private var file: File? = null
    private var wallClockAtAnchorMs: Long = 0
    private var monotonicAtAnchorMs: Long = 0

    val currentFile: File? get() = file

    /** True once [open] has been called for the match now being logged. */
    val isOpen: Boolean get() = file != null

    /**
     * Opens a file for a match that has just kicked off.
     *
     * [nowWallMs] and [nowMonotonicMs] are read together and are what every
     * time of day in the file is worked out from.
     */
    fun open(match: Match, nowWallMs: Long, nowMonotonicMs: Long): File? {
        val dir = directory
        dir.mkdirs()
        wallClockAtAnchorMs = nowWallMs
        monotonicAtAnchorMs = nowMonotonicMs
        file = File(dir, fileName(match, nowWallMs))
        return file
    }

    fun close() {
        file = null
    }

    /**
     * Rewrites the whole file.
     *
     * It is a few kilobytes, so there is nothing to gain from appending — and
     * a rewrite cannot leave a half-updated log behind the way an append can.
     */
    fun write(match: Match): Result<File> = runCatching {
        val target = file ?: error("no match file is open")
        target.parentFile?.mkdirs()
        target.writeText(encode(match).toString(2))
        target
    }

    fun latestFile(): File? = directory
        .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
        ?.maxByOrNull { it.lastModified() }

    private fun encode(match: Match): JSONObject = JSONObject().apply {
        put("meta", JSONObject().apply {
            put("home", match.meta.home)
            put("away", match.meta.away)
            put("periods", match.meta.periods)
        })
        put("events", JSONArray().apply { match.events.forEach { put(encode(it)) } })
    }

    private fun encode(event: MatchEvent): JSONObject = JSONObject().apply {
        put("type", event.type.name.lowercase(Locale.US))
        put("ts", timestamp(wallClockOf(event.atMs)))
        if (event.team != MatchTeam.NONE) put("team", event.team.name.lowercase(Locale.US))
        if (event.period > 0) put("period", event.period)
        if (event.type == MatchEventType.ADDED_TIME) put("minutes", event.minutes)
        if (event.note.isNotEmpty()) put("note", event.note)
    }

    private fun wallClockOf(monotonicMs: Long): Long =
        wallClockAtAnchorMs + (monotonicMs - monotonicAtAnchorMs)

    private fun fileName(match: Match, atWallMs: Long): String {
        val stamp = SimpleDateFormat(FILE_STAMP, Locale.US).format(Date(atWallMs))
        val home = slug(match.meta.home.ifBlank { "home" })
        val away = slug(match.meta.away.ifBlank { "away" })
        return "$stamp-$home-vs-$away.json"
    }

    /** Team names come from an operator, not from a filesystem. */
    private fun slug(name: String): String = name.trim()
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .take(24)
        .ifEmpty { "team" }

    companion object {
        const val DIRECTORY = "DJIMatchLogger"
        const val FORMAT_VERSION = 1
        private const val FILE_STAMP = "yyyy-MM-dd_HHmm"
        private const val TIMESTAMP = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

        fun timestamp(millis: Long): String =
            SimpleDateFormat(TIMESTAMP, Locale.US).format(Date(millis))

        fun clock(ms: Long): String {
            val seconds = ms / 1000
            return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        }
    }
}
