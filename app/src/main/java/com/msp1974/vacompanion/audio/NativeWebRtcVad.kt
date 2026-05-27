package com.msp1974.vacompanion.audio

class NativeWebRtcVad {
    companion object {
        init {
            System.loadLibrary("vaca_webrtc_vad")
        }
    }

    external fun nativeCreate(mode: Int): Long
    external fun nativeProcess(handle: Long, sampleRateHz: Int, input: ShortArray): Int
    external fun nativeDestroy(handle: Long)
}

