cmake_minimum_required(VERSION 3.22.1)
project("vaca_native_audio")

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

find_library(log-lib log)
find_library(android-lib android)
find_library(m-lib m)

set(WEBRTC_ROOT ${CMAKE_SOURCE_DIR}/../../../../third_party/webrtc-audio-processing/webrtc)
set(WEBRTC_SHIM_ROOT ${CMAKE_SOURCE_DIR}/webrtc_shim)

file(GLOB WEBRTC_SPL_SOURCES
    ${WEBRTC_ROOT}/common_audio/signal_processing/*.c
)
list(FILTER WEBRTC_SPL_SOURCES EXCLUDE REGEX ".*_(mips|neon)\\.c$")

add_library(
    vaca_webrtc_vad
    SHARED
    native_webrtc_vad_jni.c
    webrtc_checks_stub.c
    ${WEBRTC_ROOT}/common_audio/vad/webrtc_vad.c
    ${WEBRTC_ROOT}/common_audio/vad/vad_core.c
    ${WEBRTC_ROOT}/common_audio/vad/vad_filterbank.c
    ${WEBRTC_ROOT}/common_audio/vad/vad_gmm.c
    ${WEBRTC_ROOT}/common_audio/vad/vad_sp.c
    ${WEBRTC_SPL_SOURCES}
)

target_include_directories(
    vaca_webrtc_vad
    PRIVATE
    ${WEBRTC_ROOT}
    ${WEBRTC_ROOT}/common_audio/vad/include
    ${WEBRTC_ROOT}/common_audio/signal_processing/include
)

target_compile_options(
    vaca_webrtc_vad
    PRIVATE
    -O3
    -fno-math-errno
)

target_link_libraries(
    vaca_webrtc_vad
    ${log-lib}
    ${android-lib}
    ${m-lib}
)

set(WEBRTC_NS_SOURCES
    ${WEBRTC_ROOT}/modules/audio_processing/ns/fast_math.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/histograms.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/noise_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/noise_suppressor.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/ns_fft.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/prior_signal_model.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/prior_signal_model_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/quantile_noise_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/signal_model.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/signal_model_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/speech_probability_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/suppression_params.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/wiener_filter.cc
    ${WEBRTC_ROOT}/common_audio/third_party/ooura/fft_size_256/fft4g.cc
)

add_library(
    vaca_webrtc_apm
    SHARED
    native_webrtc_apm_jni.cpp
    ${WEBRTC_NS_SOURCES}
)

target_include_directories(
    vaca_webrtc_apm
    PRIVATE
    ${WEBRTC_SHIM_ROOT}
    ${WEBRTC_ROOT}
)

target_compile_definitions(
    vaca_webrtc_apm
    PRIVATE
    WEBRTC_LIBRARY_IMPL
    WEBRTC_ENABLE_SYMBOL_EXPORT
    WEBRTC_POSIX
    WEBRTC_ANDROID
    WEBRTC_LINUX
    NDEBUG
)

target_compile_options(
    vaca_webrtc_apm
    PRIVATE
    -O3
    -fno-math-errno
)

target_link_libraries(
    vaca_webrtc_apm
    ${log-lib}
    ${android-lib}
    ${m-lib}
)

