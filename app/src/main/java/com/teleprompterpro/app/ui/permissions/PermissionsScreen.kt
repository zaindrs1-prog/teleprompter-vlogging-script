package com.teleprompterpro.app.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.teleprompterpro.app.ui.components.TopBar
import com.teleprompterpro.app.ui.theme.AppColors

/**
 * Exactly two permissions, each explained. No "draw over other apps": the
 * script overlay is part of this app's own camera screen.
 */
@Composable
fun PermissionsScreen(onGranted: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var camera by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }
    var mic by remember { mutableStateOf(has(Manifest.permission.RECORD_AUDIO)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        camera = result[Manifest.permission.CAMERA] ?: has(Manifest.permission.CAMERA)
        mic = result[Manifest.permission.RECORD_AUDIO] ?: has(Manifest.permission.RECORD_AUDIO)
        asked = true
    }

    LaunchedEffect(camera, mic) { if (camera && mic) onGranted() }
    LaunchedEffect(Unit) {
        if (!(camera && mic)) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    Scaffold(topBar = { TopBar("Permissions", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("TeleprompterPro needs two permissions", style = MaterialTheme.typography.titleLarge)
            PermissionRow(Icons.Default.Videocam, "Camera", "Live preview and video recording.", camera)
            PermissionRow(Icons.Default.Mic, "Microphone", "Audio for your recordings, the mic test, and voice-sync scrolling.", mic)
            Text(
                "That's all. The script overlay is drawn inside this app's own camera screen, so no \"display over other apps\" permission is needed. " +
                    "Videos are saved through the system gallery (MediaStore), so no storage permission is needed either.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Grant permissions")
            }
            if (asked && !(camera && mic)) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open app settings") }
            }
        }
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, why: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) Icon(Icons.Default.Check, "Granted", tint = AppColors.Ready)
    }
}
