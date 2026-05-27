package com.msp1974.vacompanion.broadcasts

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.utils.Logger

class BroadcastSender {
    companion object {
        private var log = Logger()

        internal const val WAKE_WORD_DETECTED = "WAKE_WORD_DETECTED"
        internal const val STOP_WORD_DETECTED = "STOP_WORD_DETECTED"
        internal const val SATELLITE_STARTED = "SATELLITE_STARTED"
        internal const val SATELLITE_CLIENT_UPDATED = "SATELLITE_CLIENT_UPDATED"
        internal const val SATELLITE_STOPPED = "SATELLITE_STOPPED"
        internal const val TOAST_MESSAGE = "TOAST_MESSAGE"
        internal const val WEBVIEW_CRASH = "WEBVIEW_CRASH"
        internal const val VERSION_MISMATCH = "VERSION_MISMATCH"
        internal const val REQUEST_MISSING_PERMISSIONS = "REQUEST_MISSING_PERMISSIONS"
        internal const val CLOSE_APP = "CLOSE_APP"
        internal const val DEBUG_AUDIO_CAPTURE_STARTED = "DEBUG_AUDIO_CAPTURE_STARTED"
        internal const val DEBUG_AUDIO_CAPTURE_STOPPED = "DEBUG_AUDIO_CAPTURE_STOPPED"


        fun sendBroadcast(context: Context, action: String, extra: String? = null) {
            val intent = Intent(action)
            if (extra != null) {
                intent.putExtra("extra", extra)
            }
            log.i("Sending broadcast: $action")
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}
