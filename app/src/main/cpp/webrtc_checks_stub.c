#include <android/log.h>
#include <stdlib.h>

void rtc_FatalMessage(const char* file, int line, const char* msg) {
    __android_log_print(
        ANDROID_LOG_ERROR,
        "WEBRTC_CHECK",
        "rtc_FatalMessage at %s:%d: %s",
        file ? file : "?",
        line,
        msg ? msg : "unknown"
    );
    abort();
}

