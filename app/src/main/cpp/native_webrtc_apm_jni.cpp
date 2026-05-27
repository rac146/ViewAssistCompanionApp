#include <jni.h>
#include <stdint.h>

#include <memory>

#include <android/log.h>

#include "modules/audio_processing/ns/noise_suppressor.h"

#define TAG "WEBRTC_APM_JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kFrameMs = 10;

struct VacaWebRtcNsState {
  int sample_rate_hz = 16000;
  int channels = 1;
  int frame_size = 160;
  std::unique_ptr<webrtc::AudioBuffer> audio;
  std::unique_ptr<webrtc::NoiseSuppressor> ns;
};

webrtc::NsConfig::SuppressionLevel ToSuppressionLevel(int level) {
  switch (level) {
    case 0:
      return webrtc::NsConfig::SuppressionLevel::k6dB;
    case 1:
      return webrtc::NsConfig::SuppressionLevel::k12dB;
    case 2:
      return webrtc::NsConfig::SuppressionLevel::k18dB;
    case 3:
    default:
      return webrtc::NsConfig::SuppressionLevel::k21dB;
  }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_msp1974_vacompanion_audio_NativeWebRtcApm_nativeCreate(
    JNIEnv* env,
    jobject thiz,
    jint sampleRateHz,
    jint channels,
    jint suppressionLevel) {
  (void)env;
  (void)thiz;

  if (channels <= 0) {
    LOGE("Invalid channel count: %d", channels);
    return 0;
  }

  if (sampleRateHz != 16000) {
    LOGE("Unsupported sample rate for current NS path: %d (expected 16000)",
         sampleRateHz);
    return 0;
  }

  auto state = std::make_unique<VacaWebRtcNsState>();
  state->sample_rate_hz = sampleRateHz;
  state->channels = channels;
  state->frame_size = sampleRateHz / (1000 / kFrameMs);

  const size_t num_bands = static_cast<size_t>(sampleRateHz / 16000);
  state->audio = std::make_unique<webrtc::AudioBuffer>(
      static_cast<size_t>(channels), num_bands, static_cast<size_t>(state->frame_size));

  webrtc::NsConfig config;
  config.target_level = ToSuppressionLevel(suppressionLevel);
  state->ns = std::make_unique<webrtc::NoiseSuppressor>(
      config, static_cast<size_t>(sampleRateHz), static_cast<size_t>(channels));

  return reinterpret_cast<jlong>(state.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_msp1974_vacompanion_audio_NativeWebRtcApm_nativeProcess(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jshortArray input,
    jshortArray output) {
  (void)thiz;
  if (handle == 0 || input == nullptr || output == nullptr) {
    return -1;
  }

  auto* state = reinterpret_cast<VacaWebRtcNsState*>(handle);
  const jsize input_len = env->GetArrayLength(input);
  const jsize output_len = env->GetArrayLength(output);
  const int frame_samples = state->frame_size * state->channels;
  if (input_len != frame_samples) {
    return -5;
  }
  if (output_len < frame_samples) {
    return -2;
  }

  jshort* in_ptr = env->GetShortArrayElements(input, nullptr);
  if (in_ptr == nullptr) {
    return -3;
  }
  jshort* out_ptr = env->GetShortArrayElements(output, nullptr);
  if (out_ptr == nullptr) {
    env->ReleaseShortArrayElements(input, in_ptr, JNI_ABORT);
    return -4;
  }

  for (int ch = 0; ch < state->channels; ++ch) {
    float* band0 = state->audio->split_bands(static_cast<size_t>(ch))[0];
    for (int i = 0; i < state->frame_size; ++i) {
      const int idx = (i * state->channels) + ch;
      band0[i] = static_cast<float>(in_ptr[idx]);
    }
  }

  state->ns->Analyze(*state->audio);
  state->ns->Process(state->audio.get());

  for (int ch = 0; ch < state->channels; ++ch) {
    float* band0 = state->audio->split_bands(static_cast<size_t>(ch))[0];
    for (int i = 0; i < state->frame_size; ++i) {
      const int idx = (i * state->channels) + ch;
      float v = band0[i];
      if (v > 32767.0f) v = 32767.0f;
      if (v < -32768.0f) v = -32768.0f;
      out_ptr[idx] = static_cast<jshort>(v);
    }
  }

  env->ReleaseShortArrayElements(input, in_ptr, JNI_ABORT);
  env->ReleaseShortArrayElements(output, out_ptr, 0);
  return frame_samples;
}

extern "C" JNIEXPORT void JNICALL
Java_com_msp1974_vacompanion_audio_NativeWebRtcApm_nativeDestroy(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
  (void)env;
  (void)thiz;
  if (handle == 0) return;
  auto* state = reinterpret_cast<VacaWebRtcNsState*>(handle);
  delete state;
}
