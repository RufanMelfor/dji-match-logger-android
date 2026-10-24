package com.djimatchlogger.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "TeamDirectory"

/**
 * The list of team names, in a file the operator can edit on a PC.
 *
 * ```json
 * { "teams": ["Ajax", "Feyenoord", "PSV"] }
 * ```
 *
 * A bare array is accepted too, because that is what someone hand-editing the
 * file is most likely to type. Anything that fails to parse is left alone
 * rather than overwritten: losing a list to a stray comma would be a poor
 * trade for tidiness.
 *
 * The file lives in the app's external files directory, which is reachable
 * over USB on most phones. Where it is not, [importFrom] takes a file from
 * anywhere the system file picker can reach — which is the reason it exists.
 */
class TeamDirectory(private val context: Context) {

    val file: File get() = File(context.getExternalFilesDir(null), FILE_NAME)

    /** Where to tell the operator to look. Shown on the configuration page. */
    val displayPath: String get() = file.absolutePath

    fun load(): List<String> {
        val file = file
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }
            .onFailure { Log.w(TAG, "teams.json could not be read", it) }
            .getOrDefault(emptyList())
    }

    /** Adds a name if it is new, matching case-insensitively. */
    fun add(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = load()
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return
        save(current + trimmed)
    }

    fun save(names: List<String>) {
        val sorted = names.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

        val json = JSONObject().put(KEY_TEAMS, JSONArray().apply { sorted.forEach(::put) })
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2))
        }.onFailure { Log.w(TAG, "teams.json could not be written", it) }
    }

    /**
     * Merges a file the operator picked from somewhere else.
     *
     * Merges rather than replaces: a list edited on a PC and a name typed in
     * beside the pitch are both real, and there is nothing here that can tell
     * which of the two was the later intention.
     */
    fun importFrom(uri: Uri): Result<Int> = runCatching {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().decodeToString() }
            ?: error("could not open $uri")
        val incoming = parse(text)
        val before = load()
        save(before + incoming)
        load().size - before.size
    }.onFailure { Log.w(TAG, "import failed", it) }

    private fun parse(text: String): List<String> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray(KEY_TEAMS) ?: JSONArray()
        }
        return (0 until array.length())
            .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    companion object {
        const val FILE_NAME = "teams.json"
        private const val KEY_TEAMS = "teams"
    }
}
