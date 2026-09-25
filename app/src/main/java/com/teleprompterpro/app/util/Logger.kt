package com.teleprompterpro.app.util

import android.util.Log
import com.teleprompterpro.app.BuildConfig

/** Thin logging facade (ported from creator-cam). Debug-only info/debug output. */
object Logger {
    private const val PREFIX = "TeleprompterPro"

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d("$PREFIX:$tag", message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i("$PREFIX:$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w("$PREFIX:$tag", message)
        else Log.w("$PREFIX:$tag", message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e("$PREFIX:$tag", message)
        else Log.e("$PREFIX:$tag", message, throwable)
    }
}
