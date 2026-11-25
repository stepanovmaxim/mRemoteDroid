package com.mremotedroid.app.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved terminal command; [enter] sends it right away, otherwise it is only typed. */
data class Snippet(val id: String, val name: String, val command: String, val enter: Boolean = true)

/**
 * Saved commands for SSH terminals, shared by all connections. A short list, so it lives
 * as JSON in SharedPreferences.
 */
class SnippetStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("snippets", Context.MODE_PRIVATE)
    private val _snippets = MutableStateFlow(load())
    val snippets: StateFlow<List<Snippet>> = _snippets

    private fun load(): List<Snippet> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Snippet(o.getString("id"), o.getString("name"), o.getString("command"), o.optBoolean("enter", true))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Snippet>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("command", it.command).put("enter", it.enter))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        _snippets.value = list
    }

    /** Adds a snippet, or replaces the one with the same id. */
    fun put(name: String, command: String, enter: Boolean, id: String? = null) {
        val snippet = Snippet(id ?: UUID.randomUUID().toString(), name.ifBlank { command.lineSequence().first() }, command, enter)
        val list = _snippets.value
        save(if (list.any { it.id == snippet.id }) list.map { if (it.id == snippet.id) snippet else it } else list + snippet)
    }

    fun delete(id: String) = save(_snippets.value.filterNot { it.id == id })

    companion object {
        private const val KEY = "list"
        @Volatile private var instance: SnippetStore? = null

        fun get(context: Context): SnippetStore =
            instance ?: synchronized(this) { instance ?: SnippetStore(context).also { instance = it } }

        /** The bytes a terminal should receive: line breaks as Enter, plus Enter when [Snippet.enter]. */
        fun keystrokes(snippet: Snippet): ByteArray {
            val text = snippet.command.replace("\r\n", "\n").trimEnd('\n').replace('\n', '\r')
            return (if (snippet.enter) "$text\r" else text).toByteArray(Charsets.UTF_8)
        }
    }
}
