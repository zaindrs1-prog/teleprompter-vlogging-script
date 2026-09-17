package com.teleprompterpro.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teleprompterpro.app.BuildConfig
import com.teleprompterpro.app.settings.AppSettings
import com.teleprompterpro.app.settings.AppTheme
import com.teleprompterpro.app.settings.CameraFacing
import com.teleprompterpro.app.settings.ScrollMode
import com.teleprompterpro.app.settings.SettingsRepository
import com.teleprompterpro.app.settings.VideoResolution
import com.teleprompterpro.app.settings.VoiceLanguage
import com.teleprompterpro.app.ui.components.BigSlider
import com.teleprompterpro.app.ui.components.SectionTitle
import com.teleprompterpro.app.ui.components.TopBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context.applicationContext) }
    val settings by repo.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopBar("Settings", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            SectionTitle("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { t -> FilterChip(selected = settings.theme == t, onClick = { scope.launch { repo.setTheme(t) } }, label = { Text(t.label) }) }
            }

            SectionTitle("Default camera")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraFacing.entries.forEach { c -> FilterChip(selected = settings.defaultCamera == c, onClick = { scope.launch { repo.setDefaultCamera(c) } }, label = { Text(c.label) }) }
            }
            SwitchRow("Mirror front-camera preview", settings.mirrorFrontPreview) { scope.launch { repo.setMirrorFrontPreview(it) } }

            SectionTitle("Default resolution")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoResolution.entries.forEach { r -> FilterChip(selected = settings.resolution == r, onClick = { scope.launch { repo.setResolution(r) } }, label = { Text(r.label) }) }
            }
            Text("The camera screen only offers what the lens really supports; a higher default falls back to the best available.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Reader defaults for new scripts")
            BigSlider("Font size", "${settings.defaultFontSp.toInt()} sp", settings.defaultFontSp, 14f..96f, { scope.launch { repo.setDefaultFontSp(it) } })
            BigSlider("Speed", "${settings.defaultWpm} wpm", settings.defaultWpm.toFloat(), 40f..400f, { scope.launch { repo.setDefaultWpm(it.toInt()) } })
            BigSlider("Background dimness", "${(settings.defaultOpacity * 100).toInt()}%", settings.defaultOpacity, 0f..1f, { scope.launch { repo.setDefaultOpacity(it) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrollMode.entries.forEach { m -> FilterChip(selected = settings.defaultScrollMode == m, onClick = { scope.launch { repo.setDefaultScrollMode(m) } }, label = { Text(m.label) }) }
            }

            SectionTitle("Voice sync language")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceLanguage.entries.forEach { l -> FilterChip(selected = settings.voiceLanguage == l, onClick = { scope.launch { repo.setVoiceLanguage(l) } }, label = { Text(l.label) }) }
            }
            Text("Urdu depends on the speech recognizer installed on your phone. If it is missing, the app says so and uses timed scrolling.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Audio")
            SwitchRow("Record audio by default", settings.micEnabled) { scope.launch { repo.setMicEnabled(it) } }
            Text("Choose a specific microphone on the camera screen (Mic button); the choice is remembered.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("About")
            Text("TeleprompterPro ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Free, offline, no account, no ads, no subscription, no paywall. Permissions: Camera, Microphone — nothing else. " +
                    "Scripts stay on this phone until you delete them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
