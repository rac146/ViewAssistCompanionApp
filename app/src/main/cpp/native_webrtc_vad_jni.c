#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>

#include "common_audio/vad/include/webrtc_vad.h"

#define TAG "WEBRTC_VAD_JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    VadInst* vad;
    int mode;
} VacaWebRtcVad;

JNIEXPORT jlong JNICALL
Java_com_msp1974_vacompanion_audio_NativeWebRtcVad_nativeCreate(JNIEnv* env, jobject thiz, jint mode) {
    (void)env;
    (void)thiz;

    VacaWebRtcVad* state = (VacaWebRtcVad*)calloc(1, sizeof(VacaWebRtcVad));
    if (state == NULL) {
        LOGE("calloc failed");
        return 0;
    }

    state->vad = WebRtcVad_Create();
    if (state->vad == NULL) {
        LOGE("WebRtcVad_Create failed");
        free(state);
        return 0;
    }
    if (WebRtcVad_Init(state->vad) != 0) {
        LOGE("WebRtcVad_Init failed");
        WebRtcVad_Free(state->vad);
        free(state);
        return 0;
    }

    int boundedMode = mode;
    if (boundedMode < 0) boundedMode = 0;
    if (boundedMode > 3) boundedMode = 3;
    if (WebRtcVad_set_mode(state->vad, boundedMode) != 0) {
        LOGE("WebRtcVad_set_mode failed");
        WebRtcVad_Free(state->vad);
        free(state);
        return 0;
    }
    state->mode = boundedMode;

    return (jlong)(intptr_t)state;
}

JNIEXPORT jint JNICALL
Java_com_msp1974_vacompanion_audio_NativeWebRtcVad_nativeProcess(JNIEnv* env, jobject thiz,
                                                                  jlong handle,
                                                                  jint sampleRateHz,
                                                                  jshortArray input) {
    (void)thiz;
    if (handle == 0 || input == NULL) {
        return -1;
    }
    VacaWebRtcVad* state = (VacaWebRtcVad*)(intptr_t)handle;
    if (state->vad == NULL) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, input);
    jshort* data = (*env)->GetShortArrayElements(env, input, NULL);
    if (data == NULL) {
        return -1;
    }

    int vad = WebRtcVad_Process(state->vad, sampleRateHz, (const int16_t*)data, (size_t)len);
    (*env)->ReleaseShortArrayElements(env, input, data, JNI_ABORT);
    return vad;
}

JNIEXPORT void JNICALL
Java_com_msp1974_vacompanion_audio_NativeWebRtcVad_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    if (handle == 0) {
        return;
    }
    VacaWebRtcVad* state = (VacaWebRtcVad*)(intptr_t)handle;
    if (state->vad != NULL) {
        WebRtcVad_Free(state->vad);
        state->vad = NULL;
    }
    free(state);
}

