package com.teleprompterpro.app

import android.app.Application
import com.teleprompterpro.app.util.Logger

/**
 * Application entry point. Offline-first by design: no backend, no analytics
 * SDK, no ads, no account system, no network calls anywhere in the app.
 */
class TeleprompterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.i("App", "TeleprompterPro v${BuildConfig.VERSION_NAME} starting (debug=${BuildConfig.DEBUG})")
    }
}
