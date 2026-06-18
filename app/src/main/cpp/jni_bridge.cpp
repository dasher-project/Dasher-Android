// jni_bridge.cpp — thin JNI shim over the DasherCore C API (dasher.h).
//
// Every Java_org_dasherproject_android_NativeBridge_* function maps ~1:1 onto a
// dasher_* symbol exported by libdasher.so. The shim's only responsibilities are:
//   * handle/pointer marshalling (jlong <-> dasher_ctx*),
//   * copying the transient C-API frame buffers into JNI-owned arrays, and
//   * caching one frame's string labels so they can be returned via a second
//     call (matches the Kotlin frame/strings two-call pattern).
//
// No DasherCore C++ headers are touched here — only the C ABI in dasher.h.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "dasher.h"

#define LOG_TAG "DasherJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

struct NativeSession {
    dasher_ctx* ctx = nullptr;
    std::vector<std::string> lastStrings;
    int width = 0;
    int height = 0;
};

inline NativeSession* fromHandle(jlong handle) {
    return reinterpret_cast<NativeSession*>(static_cast<uintptr_t>(handle));
}

inline jlong toHandle(NativeSession* s) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(s));
}

jintArray toJIntArray(JNIEnv* env, const int* data, int count) {
    jintArray out = env->NewIntArray(count);
    if (out && count > 0) {
        env->SetIntArrayRegion(out, 0, count, reinterpret_cast<const jint*>(data));
    }
    return out;
}

// Draws two placeholder rectangles + a centred crosshair when DasherCore produces
// no visible box commands during warm-up, so the canvas is never blank.
void appendFallbackBoxes(NativeSession& s, std::vector<jint>& cmds) {
    const int w = s.width;
    const int h = s.height;
    if (w <= 0 || h <= 0) return;
    const int left = w / 20;
    const int right = w - left;
    auto push = [&](int op, int a, int b, int c, int d, int argb) {
        cmds.insert(cmds.end(), {op, a, b, c, d, argb});
    };
    push(4, left, h / 8, right, h / 2 - h / 20, 0xFF1B5E20);
    push(3, left, h / 8, right, h / 2 - h / 20, 0xFF81C784);
    push(4, left, h / 2 + h / 20, right, h - h / 8, 0xFF0D47A1);
    push(3, left, h / 2 + h / 20, right, h - h / 8, 0xFF90CAF9);
    const int m = std::max(10, std::min(w, h) / 45);
    const int cx = w / 2, cy = h / 2;
    push(1, cx, cy, m, 1, 0xFFD50000);
    push(2, cx - m * 2, cy, cx + m * 2, cy, 0xFFFFCDD2);
    push(2, cx, cy - m * 2, cx, cy + m * 2, 0xFFFFCDD2);
}

bool hasVisibleBox(const std::vector<jint>& cmds) {
    for (size_t i = 0; i + 5 < cmds.size(); i += 6) {
        const int op = cmds[i];
        if (op == 3 || op == 4) {
            const int argb = cmds[i + 5];
            if (((argb >> 24) & 0xFF) < 24) continue;
            if (cmds[i + 1] == cmds[i + 3] || cmds[i + 2] == cmds[i + 4]) continue;
            return true;
        }
    }
    return false;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_org_dasherproject_android_NativeBridge_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("DasherCore CAPI");
}

JNIEXPORT jlong JNICALL
Java_org_dasherproject_android_NativeBridge_nativeCreate(JNIEnv* env, jclass,
                                                          jstring jDataDir,
                                                          jstring jUserDir) {
    const char* dataDir = env->GetStringUTFChars(jDataDir, nullptr);
    const char* userDir = jUserDir ? env->GetStringUTFChars(jUserDir, nullptr) : nullptr;
    if (!dataDir) return 0L;

    char* error = nullptr;
    dasher_ctx* ctx = dasher_create(dataDir, userDir, &error);

    env->ReleaseStringUTFChars(jDataDir, dataDir);
    if (userDir) env->ReleaseStringUTFChars(jUserDir, userDir);

    if (!ctx) {
        LOGW("dasher_create failed: %s", error ? error : "(no message)");
        return 0L;
    }
    auto* s = new NativeSession{ctx, {}, 0, 0};
    LOGI("nativeCreate ctx=%p", static_cast<void*>(ctx));
    return toHandle(s);
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s) return;
    if (s->ctx) dasher_destroy(s->ctx);
    delete s;
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeSetScreenSize(JNIEnv*, jclass,
                                                                 jlong handle,
                                                                 jint width, jint height) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return;
    s->width = std::max(0, width);
    s->height = std::max(0, height);
    dasher_set_screen_size(s->ctx, width, height);
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeMouseMove(JNIEnv*, jclass,
                                                             jlong handle, jfloat x, jfloat y) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_mouse_move(s->ctx, x, y);
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeMouseDown(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_mouse_down(s->ctx);
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeMouseUp(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_mouse_up(s->ctx);
}

JNIEXPORT jintArray JNICALL
Java_org_dasherproject_android_NativeBridge_nativeFrame(JNIEnv* env, jclass,
                                                        jlong handle, jlong timeMs) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewIntArray(0);

    int* commands = nullptr;
    int cmdCount = 0;
    char** strings = nullptr;
    int strCount = 0;

    dasher_frame(s->ctx, static_cast<int64_t>(timeMs), &commands, &cmdCount, &strings, &strCount);

    // Cache this frame's string labels (the C buffer is only valid until next call).
    s->lastStrings.clear();
    s->lastStrings.reserve(strCount);
    for (int i = 0; i < strCount; ++i) {
        s->lastStrings.emplace_back(strings[i] ? strings[i] : "");
    }

    std::vector<jint> out;
    out.reserve(cmdCount);
    for (int i = 0; i < cmdCount; ++i) {
        out.push_back(static_cast<jint>(commands[i]));
    }
    if (!hasVisibleBox(out)) {
        appendFallbackBoxes(*s, out);
    }
    return toJIntArray(env, out.data(), static_cast<int>(out.size()));
}

JNIEXPORT jobjectArray JNICALL
Java_org_dasherproject_android_NativeBridge_nativeGetFrameStrings(JNIEnv* env, jclass,
                                                                   jlong handle) {
    auto* s = fromHandle(handle);
    jclass strClass = env->FindClass("java/lang/String");
    if (!s) return env->NewObjectArray(0, strClass, nullptr);

    const auto& v = s->lastStrings;
    auto arr = env->NewObjectArray(static_cast<jsize>(v.size()), strClass, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(v.size()); ++i) {
        jstring js = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(arr, i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_org_dasherproject_android_NativeBridge_nativeGetOutputText(JNIEnv* env, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_output_text(s->ctx));
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeResetOutputText(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_reset_output_text(s->ctx);
}

JNIEXPORT jstring JNICALL
Java_org_dasherproject_android_NativeBridge_nativeGetAlphabetId(JNIEnv* env, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_alphabet_id(s->ctx));
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeSetAlphabetId(JNIEnv* env, jclass,
                                                                jlong handle, jstring jId) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx || !jId) return;
    const char* id = env->GetStringUTFChars(jId, nullptr);
    if (id) {
        dasher_set_alphabet_id(s->ctx, id);
        env->ReleaseStringUTFChars(jId, id);
    }
}

JNIEXPORT jint JNICALL
Java_org_dasherproject_android_NativeBridge_nativeGetLanguageModelId(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? dasher_get_language_model_id(s->ctx) : 0;
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeSetLanguageModelId(JNIEnv*, jclass,
                                                                     jlong handle, jint id) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_language_model_id(s->ctx, id);
}

JNIEXPORT jint JNICALL
Java_org_dasherproject_android_NativeBridge_nativeGetSpeedPercent(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? dasher_get_speed_percent(s->ctx) : 100;
}

JNIEXPORT void JNICALL
Java_org_dasherproject_android_NativeBridge_nativeSetSpeedPercent(JNIEnv*, jclass,
                                                                  jlong handle, jint percent) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_speed_percent(s->ctx, percent);
}

}  // extern "C"
