package com.teleprompterpro.app.script

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teleprompterpro.app.settings.ScrollMode
import com.teleprompterpro.app.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

// Separate DataStore file from app settings so a settings reset can never
// touch scripts.
private val Context.scriptStore: DataStore<Preferences> by preferencesDataStore(name = "scripts")

/**
 * Permanent, offline script storage on Jetpack DataStore.
 *
 * Guarantees (each maps to a competitor complaint):
 *  - Scripts persist until the user deletes them. There is no expiry, no
 *    cleanup job, no "free tier" purge.
 *  - No limit on number of scripts or characters per script.
 *  - The body is saved exactly as given; whitespace is never normalized here.
 *  - Writes are atomic (DataStore) so a crash mid-save cannot corrupt the list.
 */
class ScriptRepository(private val context: Context) {

    private object Keys {
        val SCRIPTS = stringPreferencesKey("scripts_json")
        val NEXT_ID = longPreferencesKey("next_id")
        val LAST_OPENED = longPreferencesKey("last_opened_id")
    }

    val scripts: Flow<List<Script>> = context.scriptStore.data
        .catch { e ->
            Logger.w("Scripts", "Read failed; showing empty list (data is NOT deleted)", e)
            emit(emptyPreferences())
        }
        .map { p -> decode(p[Keys.SCRIPTS]) }

    val lastOpenedId: Flow<Long> = context.scriptStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.LAST_OPENED] ?: -1L }

    fun script(id: Long): Flow<Script?> = scripts.map { list -> list.firstOrNull { it.id == id } }

    suspend fun get(id: Long): Script? = scripts.first().firstOrNull { it.id == id }

    suspend fun create(title: String = "Untitled script", body: String = "", settings: ReaderSettings = ReaderSettings()): Script {
        var created: Script? = null
        context.scriptStore.edit { p ->
            val list = decode(p[Keys.SCRIPTS]).toMutableList()
            val id = (p[Keys.NEXT_ID] ?: 1L)
            val now = System.currentTimeMillis()
            val s = Script(id, title, body, now, now, settings)
            list.add(0, s)
            p[Keys.SCRIPTS] = encode(list)
            p[Keys.NEXT_ID] = id + 1
            created = s
        }
        return created!!
    }

    /** Full upsert: body, title and settings are replaced as given. */
    suspend fun save(script: Script) {
        context.scriptStore.edit { p ->
            val list = decode(p[Keys.SCRIPTS]).toMutableList()
            val idx = list.indexOfFirst { it.id == script.id }
            val updated = script.copy(updatedAt = System.currentTimeMillis())
            if (idx >= 0) list[idx] = updated else list.add(0, updated)
            p[Keys.SCRIPTS] = encode(list)
        }
    }

    suspend fun updateBody(id: Long, title: String, body: String) {
        context.scriptStore.edit { p ->
            val list = decode(p[Keys.SCRIPTS]).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@edit
            list[idx] = list[idx].copy(title = title, body = body, updatedAt = System.currentTimeMillis())
            p[Keys.SCRIPTS] = encode(list)
        }
    }

    suspend fun updateSettings(id: Long, settings: ReaderSettings) {
        context.scriptStore.edit { p ->
            val list = decode(p[Keys.SCRIPTS]).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@edit
            list[idx] = list[idx].copy(settings = settings)
            p[Keys.SCRIPTS] = encode(list)
        }
    }

    suspend fun rename(id: Long, title: String) {
        context.scriptStore.edit { p ->
            val list = decode(p[Keys.SCRIPTS]).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@edit
            list[idx] = list[idx].copy(title = title, updatedAt = System.currentTimeMillis())
            p[Keys.SCRIPTS] = encode(list)
        }
    }

    suspend fun duplicate(id: Long): Script? {
        val src = get(id) ?: return null
        return create(title = "${src.title} (copy)", body = src.body, settings = src.settings)
    }

    /** The only code path that ever removes a script: an explicit user action. */
    suspend fun delete(id: Long) {
        context.scriptStore.edit { p ->
            val list = decode(p[Keys.SCRIPTS]).filterNot { it.id == id }
            p[Keys.SCRIPTS] = encode(list)
        }
    }

    suspend fun setLastOpened(id: Long) {
        context.scriptStore.edit { it[Keys.LAST_OPENED] = id }
    }

    // ------------------------------------------------------------ JSON codec

    private fun encode(list: List<Script>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("body", s.body)
                    .put("createdAt", s.createdAt)
                    .put("updatedAt", s.updatedAt)
                    .put("fontSp", s.settings.fontSp.toDouble())
                    .put("wpm", s.settings.wpm)
                    .put("opacity", s.settings.backgroundOpacity.toDouble())
                    .put("scrollMode", s.settings.scrollMode.name)
                    .put("mirror", s.settings.mirrorText)
            )
        }
        return arr.toString()
    }

    private fun decode(json: String?): List<Script> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Script(
                    id = o.optLong("id", -1L).takeIf { it >= 0 } ?: return@mapNotNull null,
                    title = o.optString("title", "Untitled script"),
                    body = o.optString("body", ""),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    settings = ReaderSettings(
                        fontSp = o.optDouble("fontSp", 30.0).toFloat(),
                        wpm = o.optInt("wpm", 140),
                        backgroundOpacity = o.optDouble("opacity", 0.55).toFloat(),
                        scrollMode = runCatching { ScrollMode.valueOf(o.optString("scrollMode", "TIMED")) }
                            .getOrDefault(ScrollMode.TIMED),
                        mirrorText = o.optBoolean("mirror", false),
                    ),
                )
            }
        } catch (e: Exception) {
            Logger.e("Scripts", "Corrupt script store; refusing to overwrite", e)
            emptyList()
        }
    }
}
