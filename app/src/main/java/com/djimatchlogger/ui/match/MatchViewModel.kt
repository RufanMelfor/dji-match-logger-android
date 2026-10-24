package com.djimatchlogger.ui.match

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.djimatchlogger.data.MatchStore
import com.djimatchlogger.data.TeamDirectory
import com.djimatchlogger.match.Match
import com.djimatchlogger.match.MatchEvent
import com.djimatchlogger.match.MatchMeta
import com.djimatchlogger.match.MatchTeam
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the running match and stamps every event with the clock.
 *
 * [Match] itself never reads a clock — that is what makes it testable — so the
 * one decision that lives here is *which* clock. It is
 * [SystemClock.elapsedRealtime], which counts from boot and cannot be moved.
 * `System.currentTimeMillis` would let a network time correction, a timezone
 * change or a daylight-saving jump land in the middle of a match and shift
 * every timing derived from it.
 */
private const val TAG = "MatchViewModel"

class MatchViewModel(application: Application) : AndroidViewModel(application) {

    private val teamDirectory = TeamDirectory(application)
    private val store = MatchStore()

    private val _match = MutableStateFlow(Match())
    val match: StateFlow<Match> = _match.asStateFlow()

    /**
     * Team names offered in the setup screen, from `teams.json`.
     *
     * Re-read rather than cached for the life of the app: the file is meant to
     * be edited on a PC, and an operator who has just plugged the phone in
     * should not have to restart the app to see what they typed.
     */
    private val _teams = MutableStateFlow<List<String>>(emptyList())
    val teams: StateFlow<List<String>> = _teams.asStateFlow()

    /**
     * Events that were undone, so the screen can say what disappeared. Undo is
     * a silent operation otherwise: an event vanishes from a log the operator
     * was not necessarily looking at.
     */
    private val _undone = MutableSharedFlow<MatchEvent>(extraBufferCapacity = 4)
    val undone: SharedFlow<MatchEvent> = _undone.asSharedFlow()

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun update(block: (Match) -> Match) {
        val next = block(_match.value)
        _match.value = next
        save(next)
    }

    /**
     * Keeps the file on disk in step with the match.
     *
     * Opened at the kick-off, because that is when there is something worth
     * keeping and when the wall clock has to be anchored; rewritten after every
     * change after that. Failures are swallowed on purpose: a card that cannot
     * be written to storage is still a card, and a match is no place for a
     * dialog about a filesystem.
     */
    private fun save(match: Match) {
        if (!match.started) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!store.isOpen) {
                store.open(match, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
            store.write(match).onFailure { Log.w(TAG, "match file could not be written", it) }
            _savedFile.value = store.currentFile?.absolutePath
        }
    }

    /** The file the match is being written to, for the share action. */
    private val _savedFile = MutableStateFlow<String?>(null)
    val savedFile: StateFlow<String?> = _savedFile.asStateFlow()

    /** The most recent match file, whether or not this run wrote it. */
    fun latestMatchFile(): String? = (store.currentFile ?: store.latestFile())?.absolutePath

    fun refreshTeams() {
        viewModelScope.launch {
            _teams.value = withContext(Dispatchers.IO) { teamDirectory.load() }
        }
    }

    /**
     * Takes the setup, and remembers the names for next time.
     *
     * Typing a club name once should be enough; the file is there so it does
     * not have to be typed again on a cold morning.
     */
    fun setMeta(meta: MatchMeta) {
        update { it.copy(meta = meta) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                teamDirectory.add(meta.home)
                teamDirectory.add(meta.away)
            }
            refreshTeams()
        }
    }

    /** Merges a list the operator picked with the file picker. */
    fun importTeams(uri: Uri, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { teamDirectory.importFrom(uri) }
            refreshTeams()
            onResult(result)
        }
    }

    val teamsFilePath: String get() = teamDirectory.displayPath

    fun goal(team: MatchTeam) = update { it.logGoal(team, now()) }

    fun yellowCard(team: MatchTeam) = update { it.logYellowCard(team, now()) }

    fun redCard(team: MatchTeam) = update { it.logRedCard(team, now()) }

    fun note(text: String) {
        if (text.isBlank()) return
        update { it.logNote(text.trim(), now()) }
    }

    fun startNextPeriod() = update { it.startNextPeriod(now()) }

    fun togglePause() = update { it.togglePause(now()) }

    fun endMatch() = update { it.endMatch(now()) }

    fun stepAddedTime(deltaMinutes: Int) = update { it.stepAddedTime(deltaMinutes, now()) }

    fun undo() {
        val (next, removed) = _match.value.undo()
        _match.value = next
        removed?.let { _undone.tryEmit(it) }
    }

    /**
     * Throws the match away and starts over, keeping the team names and the
     * rest of the setup — the next match is usually against the same two
     * teams' names being retyped otherwise.
     */
    fun newMatch() {
        // A new match is a new file. The old one stays where it is.
        store.close()
        _match.value = Match(meta = _match.value.meta)
    }

    /**
     * Logs that the cameras started rolling.
     *
     * [confirmed] is what the cameras' own status pushes said, not whether the
     * command was accepted; the distinction is the whole reason the event
     * carries two booleans. Not wired to the camera layer yet.
     */
    fun videoStarted(confirmed: Boolean) = update {
        it.logVideoStart(now(), camerasConfirmedValid = true, camerasConfirmed = confirmed)
    }
}
