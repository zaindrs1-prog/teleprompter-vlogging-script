package com.teleprompterpro.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.teleprompterpro.app.reader.RemoteAction
import com.teleprompterpro.app.reader.RemoteKeys
import com.teleprompterpro.app.settings.AppSettings
import com.teleprompterpro.app.settings.SettingsRepository
import com.teleprompterpro.app.ui.nav.AppNav
import com.teleprompterpro.app.ui.theme.TeleprompterTheme

/**
 * Single-activity Compose host (creator-cam pattern). Camera + recording live
 * in ViewModels so rotation never tears down a take; the manifest declares
 * configChanges as a second line of defence while recording locks orientation.
 *
 * Bluetooth remotes / media buttons arrive here as plain key events and are
 * forwarded to whichever screen registered a handler. A remote disconnecting
 * simply stops sending events — there is nothing that can crash.
 */
class MainActivity : ComponentActivity() {

    /** Set by the camera screen while it is visible. */
    @Volatile
    var remoteHandler: ((RemoteAction) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepo = SettingsRepository(applicationContext)
        setContent {
            val settings by settingsRepo.settings.collectAsState(AppSettings())
            TeleprompterTheme(theme = settings.theme) {
                AppNav()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handler = remoteHandler
        if (handler != null && event?.repeatCount == 0) {
            val action = RemoteKeys.actionFor(keyCode)
            if (action != null && runCatching { handler(action) }.getOrDefault(false)) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
