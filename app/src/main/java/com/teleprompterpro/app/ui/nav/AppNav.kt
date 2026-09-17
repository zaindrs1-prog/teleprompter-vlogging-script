package com.teleprompterpro.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.teleprompterpro.app.ui.camera.CameraScreen
import com.teleprompterpro.app.ui.editor.EditorScreen
import com.teleprompterpro.app.ui.permissions.PermissionsScreen
import com.teleprompterpro.app.ui.recordings.RecordingsScreen
import com.teleprompterpro.app.ui.scripts.ScriptListScreen
import com.teleprompterpro.app.ui.settings.SettingsScreen
import com.teleprompterpro.app.ui.trim.TrimScreen

object Routes {
    const val SCRIPTS = "scripts"
    const val EDITOR = "editor/{id}"
    const val CAMERA = "camera/{id}"
    const val PERMISSIONS = "permissions/{id}"
    const val RECORDINGS = "recordings"
    const val TRIM = "trim/{uri}"
    const val SETTINGS = "settings"

    fun editor(id: Long) = "editor/$id"
    fun camera(id: Long) = "camera/$id"
    fun permissions(id: Long) = "permissions/$id"
    fun trim(uri: String) = "trim/${android.net.Uri.encode(uri)}"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SCRIPTS) {
        composable(Routes.SCRIPTS) {
            ScriptListScreen(
                onOpenEditor = { nav.navigate(Routes.editor(it)) },
                onRecord = { nav.navigate(Routes.permissions(it)) },
                onRecordings = { nav.navigate(Routes.RECORDINGS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments?.getLong("id") ?: -1L
            EditorScreen(
                scriptId = id,
                onBack = { nav.popBackStack() },
                onRecord = { nav.navigate(Routes.permissions(id)) },
            )
        }
        composable(Routes.PERMISSIONS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments?.getLong("id") ?: -1L
            PermissionsScreen(
                onGranted = {
                    nav.navigate(Routes.camera(id)) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CAMERA, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments?.getLong("id") ?: -1L
            CameraScreen(
                scriptId = id,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.editor(id)) },
                onTrim = { uri -> nav.navigate(Routes.trim(uri)) },
            )
        }
        composable(Routes.RECORDINGS) {
            RecordingsScreen(
                onBack = { nav.popBackStack() },
                onTrim = { uri -> nav.navigate(Routes.trim(uri)) },
            )
        }
        composable(Routes.TRIM, arguments = listOf(navArgument("uri") { type = NavType.StringType })) {
            val uri = it.arguments?.getString("uri").orEmpty()
            TrimScreen(uriString = uri, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
