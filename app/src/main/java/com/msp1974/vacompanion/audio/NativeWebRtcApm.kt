package com.msp1974.vacompanion.audio

class NativeWebRtcApm {
    companion object {
        init {
            System.loadLibrary("vaca_webrtc_apm")
        }
    }

    external fun nativeCreate(sampleRateHz: Int, channels: Int, suppressionLevel: Int): Long
    external fun nativeProcess(handle: Long, input: ShortArray, output: ShortArray): Int
    external fun nativeDestroy(handle: Long)
}

