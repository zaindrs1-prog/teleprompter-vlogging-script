package com.teleprompterpro.app.reader

import android.view.KeyEvent

/** Actions a Bluetooth remote / keyboard / headset button can trigger. */
enum class RemoteAction { PLAY_PAUSE, REWIND, FORWARD, SPEED_UP, SLOW_DOWN, RESTART, TOGGLE_RECORD }

/**
 * Maps hardware key codes to reader actions. Bluetooth teleprompter remotes,
 * media buttons and selfie-stick shutters all arrive as plain [KeyEvent]s —
 * there is no connection to manage, so a remote dropping off mid-script simply
 * stops sending keys; nothing can crash.
 */
object RemoteKeys {
    fun actionFor(keyCode: Int): RemoteAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> RemoteAction.PLAY_PAUSE

        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> RemoteAction.REWIND

        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> RemoteAction.FORWARD

        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> RemoteAction.SPEED_UP
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MINUS -> RemoteAction.SLOW_DOWN
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME -> RemoteAction.RESTART
        KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_BUTTON_B -> RemoteAction.TOGGLE_RECORD
        else -> null
    }
}
