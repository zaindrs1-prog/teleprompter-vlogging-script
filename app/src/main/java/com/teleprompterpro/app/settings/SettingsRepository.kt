package com.teleprompterpro.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teleprompterpro.app.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "teleprompterpro")

/** DataStore-backed settings (creator-cam pattern). */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val CAMERA = stringPreferencesKey("default_camera")
        val RESOLUTION = stringPreferencesKey("resolution")
        val FRAME_RATE = stringPreferencesKey("frame_rate")
        val STABILIZATION = stringPreferencesKey("stabilization")
        val MIRROR = booleanPreferencesKey("mirror_front_preview")
        val MIC = booleanPreferencesKey("mic_enabled")
        val MIC_ID = intPreferencesKey("preferred_mic_id")
        val FONT = floatPreferencesKey("default_font_sp")
        val WPM = intPreferencesKey("default_wpm")
        val OPACITY = floatPreferencesKey("default_opacity")
        val SCROLL_MODE = stringPreferencesKey("default_scroll_mode")
        val VOICE_LANG = stringPreferencesKey("voice_language")
        val COUNTDOWN = intPreferencesKey("countdown_seconds")
        val OVERLAY_POS = stringPreferencesKey("overlay_position")
        val THEME = stringPreferencesKey("theme")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e ->
            Logger.w("Settings", "Failed to read settings, using defaults", e)
            emit(emptyPreferences())
        }
        .map { p ->
            AppSettings(
                defaultCamera = p.enum(Keys.CAMERA, CameraFacing.REAR),
                resolution = p.enum(Keys.RESOLUTION, VideoResolution.AUTO),
                frameRate = p.enum(Keys.FRAME_RATE, FrameRate.AUTO),
                stabilization = p.enum(Keys.STABILIZATION, StabilizationMode.STANDARD),
                mirrorFrontPreview = p[Keys.MIRROR] ?: true,
                micEnabled = p[Keys.MIC] ?: true,
                preferredMicId = p[Keys.MIC_ID] ?: -1,
                defaultFontSp = (p[Keys.FONT] ?: 30f).coerceIn(14f, 96f),
                defaultWpm = (p[Keys.WPM] ?: 140).coerceIn(40, 400),
                defaultOpacity = (p[Keys.OPACITY] ?: 0.55f).coerceIn(0f, 1f),
                defaultScrollMode = p.enum(Keys.SCROLL_MODE, ScrollMode.TIMED),
                voiceLanguage = p.enum(Keys.VOICE_LANG, VoiceLanguage.ENGLISH),
                countdownSeconds = (p[Keys.COUNTDOWN] ?: 3).coerceIn(0, 10),
                overlayPosition = p.enum(Keys.OVERLAY_POS, OverlayPosition.TOP),
                theme = p.enum(Keys.THEME, AppTheme.DARK),
            )
        }

    suspend fun setDefaultCamera(v: CameraFacing) = context.dataStore.edit { it[Keys.CAMERA] = v.name }
    suspend fun setResolution(v: VideoResolution) = context.dataStore.edit { it[Keys.RESOLUTION] = v.name }
    suspend fun setFrameRate(v: FrameRate) = context.dataStore.edit { it[Keys.FRAME_RATE] = v.name }
    suspend fun setStabilization(v: StabilizationMode) = context.dataStore.edit { it[Keys.STABILIZATION] = v.name }
    suspend fun setMirrorFrontPreview(v: Boolean) = context.dataStore.edit { it[Keys.MIRROR] = v }
    suspend fun setMicEnabled(v: Boolean) = context.dataStore.edit { it[Keys.MIC] = v }
    suspend fun setPreferredMicId(v: Int) = context.dataStore.edit { it[Keys.MIC_ID] = v }
    suspend fun setDefaultFontSp(v: Float) = context.dataStore.edit { it[Keys.FONT] = v.coerceIn(14f, 96f) }
    suspend fun setDefaultWpm(v: Int) = context.dataStore.edit { it[Keys.WPM] = v.coerceIn(40, 400) }
    suspend fun setDefaultOpacity(v: Float) = context.dataStore.edit { it[Keys.OPACITY] = v.coerceIn(0f, 1f) }
    suspend fun setDefaultScrollMode(v: ScrollMode) = context.dataStore.edit { it[Keys.SCROLL_MODE] = v.name }
    suspend fun setVoiceLanguage(v: VoiceLanguage) = context.dataStore.edit { it[Keys.VOICE_LANG] = v.name }
    suspend fun setCountdown(v: Int) = context.dataStore.edit { it[Keys.COUNTDOWN] = v.coerceIn(0, 10) }
    suspend fun setOverlayPosition(v: OverlayPosition) = context.dataStore.edit { it[Keys.OVERLAY_POS] = v.name }
    suspend fun setTheme(v: AppTheme) = context.dataStore.edit { it[Keys.THEME] = v.name }

    private inline fun <reified T : Enum<T>> Preferences.enum(
        key: Preferences.Key<String>,
        default: T,
    ): T = get(key)?.let { name ->
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
    } ?: default
}
