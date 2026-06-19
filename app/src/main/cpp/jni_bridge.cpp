// jni_bridge.cpp — thin JNI shim over the DasherCore C API (dasher.h).
//
// Every Java_at_dasher_android_NativeBridge_* function maps ~1:1 onto a
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

// ── JVM + engine-callback infrastructure ───────────────────────────────────
// Engine callbacks (clipboard/speak/message/output) fire on the thread that calls
// dasher_frame(), i.e. the main thread here. We cache the JavaVM and NativeBridge
// method IDs in JNI_OnLoad so the C callback wrappers can call back into Kotlin.
static JavaVM* g_jvm = nullptr;
static jclass g_nbClass = nullptr;
static jmethodID g_onClipboard = nullptr;
static jmethodID g_onSpeak = nullptr;
static jmethodID g_onMessage = nullptr;
static jmethodID g_onOutput = nullptr;
static jmethodID g_onParameterChanged = nullptr;

// Returns an env for the current thread, attaching it if necessary.
// [attached] is set true when the caller must DetachCurrentThread afterwards.
static JNIEnv* attachEnv(bool& attached) {
    JNIEnv* env = nullptr;
    if (g_jvm && g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        attached = false;
        return env;
    }
    if (!g_jvm) return nullptr;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "DasherCallback", nullptr};
    // Android NDK: AttachCurrentThread(JNIEnv**, void*).
    if (g_jvm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
    attached = true;
    return env;
}

static void clipboardCallback(const char* text, void*) {
    if (!g_nbClass || !g_onClipboard) return;
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) return;
    jstring jtext = env->NewStringUTF(text ? text : "");
    env->CallStaticVoidMethod(g_nbClass, g_onClipboard, jtext);
    env->DeleteLocalRef(jtext);
    if (attached) g_jvm->DetachCurrentThread();
}

static void speakCallback(const char* text, int interrupt, void*) {
    if (!g_nbClass || !g_onSpeak || !text) return;
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) return;
    jstring jtext = env->NewStringUTF(text);
    env->CallStaticVoidMethod(g_nbClass, g_onSpeak, jtext, static_cast<jint>(interrupt));
    env->DeleteLocalRef(jtext);
    if (attached) g_jvm->DetachCurrentThread();
}

static void messageCallback(int type, const char* text, void*) {
    if (!g_nbClass || !g_onMessage || !text) return;
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) return;
    jstring jtext = env->NewStringUTF(text);
    env->CallStaticVoidMethod(g_nbClass, g_onMessage, static_cast<jint>(type), jtext);
    env->DeleteLocalRef(jtext);
    if (attached) g_jvm->DetachCurrentThread();
}

static void outputCallback(int type, const char* text, void*) {
    if (!g_nbClass || !g_onOutput || !text) return;
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) return;
    jstring jtext = env->NewStringUTF(text);
    env->CallStaticVoidMethod(g_nbClass, g_onOutput, static_cast<jint>(type), jtext);
    env->DeleteLocalRef(jtext);
    if (attached) g_jvm->DetachCurrentThread();
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("at/dasher/android/NativeBridge");
    if (!cls) return JNI_ERR;
    g_nbClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
    g_onClipboard = env->GetStaticMethodID(g_nbClass, "onClipboard", "(Ljava/lang/String;)V");
    g_onSpeak = env->GetStaticMethodID(g_nbClass, "onSpeak", "(Ljava/lang/String;I)V");
    g_onMessage = env->GetStaticMethodID(g_nbClass, "onMessage", "(ILjava/lang/String;)V");
    g_onOutput = env->GetStaticMethodID(g_nbClass, "onOutput", "(ILjava/lang/String;)V");
    g_onParameterChanged = env->GetStaticMethodID(g_nbClass, "onParameterChanged", "(I)V");
    LOGI("JNI_OnLoad: callbacks resolved (clipboard=%p speak=%p msg=%p out=%p param=%p)",
         (void*)g_onClipboard, (void*)g_onSpeak, (void*)g_onMessage, (void*)g_onOutput,
         (void*)g_onParameterChanged);
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("DasherCore CAPI");
}

JNIEXPORT jlong JNICALL
Java_at_dasher_android_NativeBridge_nativeCreate(JNIEnv* env, jclass,
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
Java_at_dasher_android_NativeBridge_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s) return;
    if (s->ctx) dasher_destroy(s->ctx);
    delete s;
}

// Low-memory mode for memory-constrained hosts (e.g. an IME process). The C API
// requires this be called before dasher_set_screen_size().
JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetLowMemoryMode(JNIEnv*, jclass,
                                                            jlong handle, jint enabled) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_low_memory_mode(s->ctx, enabled);
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetScreenSize(JNIEnv*, jclass,
                                                                 jlong handle,
                                                                 jint width, jint height) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return;
    s->width = std::max(0, width);
    s->height = std::max(0, height);
    dasher_set_screen_size(s->ctx, width, height);
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeMouseMove(JNIEnv*, jclass,
                                                             jlong handle, jfloat x, jfloat y) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_mouse_move(s->ctx, x, y);
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeMouseDown(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_mouse_down(s->ctx);
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeMouseUp(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_mouse_up(s->ctx);
}

JNIEXPORT jintArray JNICALL
Java_at_dasher_android_NativeBridge_nativeFrame(JNIEnv* env, jclass,
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
Java_at_dasher_android_NativeBridge_nativeGetFrameStrings(JNIEnv* env, jclass,
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
Java_at_dasher_android_NativeBridge_nativeGetOutputText(JNIEnv* env, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_output_text(s->ctx));
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeResetOutputText(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_reset_output_text(s->ctx);
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeGetAlphabetId(JNIEnv* env, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_alphabet_id(s->ctx));
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetAlphabetId(JNIEnv* env, jclass,
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
Java_at_dasher_android_NativeBridge_nativeGetLanguageModelId(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? dasher_get_language_model_id(s->ctx) : 0;
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetLanguageModelId(JNIEnv*, jclass,
                                                                     jlong handle, jint id) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_language_model_id(s->ctx, id);
}

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeGetSpeedPercent(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? dasher_get_speed_percent(s->ctx) : 100;
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetSpeedPercent(JNIEnv*, jclass,
                                                                  jlong handle, jint percent) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_speed_percent(s->ctx, percent);
}

// ── Generic parameter surface (mirrors Dasher-Windows NativeBridge.cs) ──────

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeFindParameterKey(JNIEnv* env, jclass,
                                                                   jstring jName) {
    if (!jName) return -1;
    const char* name = env->GetStringUTFChars(jName, nullptr);
    if (!name) return -1;
    int key = dasher_find_parameter_key(name);
    env->ReleaseStringUTFChars(jName, name);
    return key;
}

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeGetBoolParameter(JNIEnv*, jclass,
                                                                   jlong handle, jint key) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? dasher_get_bool_parameter(s->ctx, key) : 0;
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetBoolParameter(JNIEnv*, jclass,
                                                                   jlong handle, jint key, jint value) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_bool_parameter(s->ctx, key, value);
}

JNIEXPORT jlong JNICALL
Java_at_dasher_android_NativeBridge_nativeGetLongParameter(JNIEnv*, jclass,
                                                                   jlong handle, jint key) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? static_cast<jlong>(dasher_get_long_parameter(s->ctx, key)) : 0L;
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetLongParameter(JNIEnv*, jclass,
                                                                   jlong handle, jint key, jlong value) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_long_parameter(s->ctx, key, static_cast<long>(value));
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeGetStringParameter(JNIEnv* env, jclass,
                                                                     jlong handle, jint key) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_string_parameter(s->ctx, key));
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetStringParameter(JNIEnv* env, jclass,
                                                                     jlong handle, jint key, jstring jValue) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx || !jValue) return;
    const char* v = env->GetStringUTFChars(jValue, nullptr);
    if (v) {
        dasher_set_string_parameter(s->ctx, key, v);
        env->ReleaseStringUTFChars(jValue, v);
    }
}

// ── Alphabets ───────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeGetAlphabetCount(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? dasher_get_alphabet_count(s->ctx) : 0;
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeGetAlphabetName(JNIEnv* env, jclass,
                                                                  jlong handle, jint index) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_alphabet_name(s->ctx, index));
}

// ── Colour palettes ─────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeGetPaletteCount(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    return (s && s->ctx) ? dasher_get_palette_count(s->ctx) : 0;
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeGetPaletteName(JNIEnv* env, jclass,
                                                                 jlong handle, jint index) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_palette_name(s->ctx, index));
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeGetCurrentPalette(JNIEnv* env, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("");
    return env->NewStringUTF(dasher_get_current_palette(s->ctx));
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetPalette(JNIEnv* env, jclass,
                                                             jlong handle, jstring jName) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx || !jName) return;
    const char* n = env->GetStringUTFChars(jName, nullptr);
    if (n) {
        dasher_set_palette(s->ctx, n);
        env->ReleaseStringUTFChars(jName, n);
    }
}

// ── Persistence ─────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSaveSettings(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_save_settings(s->ctx);
}

// ── Parameter schema introspection (manifest-driven settings UI) ────────────
// dasher_get_parameter_count / _info / enum accessors are NOT context-bound.

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeGetParameterCount(JNIEnv*, jclass) {
    return dasher_get_parameter_count();
}

JNIEXPORT jobject JNICALL
Java_at_dasher_android_NativeBridge_nativeGetParameterInfo(JNIEnv* env, jclass, jint index) {
    dasher_parameter_info info;
    if (dasher_get_parameter_info(index, &info) != 0) return nullptr;
    static jclass cls = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("at/dasher/android/ParameterInfo")));
    static jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIJJJI)V");
    jstring jname = env->NewStringUTF(info.name ? info.name : "");
    jstring jdesc = env->NewStringUTF(info.desc ? info.desc : "");
    jstring jgroup = env->NewStringUTF(info.group ? info.group : "");
    jstring jsub = env->NewStringUTF(info.subgroup ? info.subgroup : "");
    jobject obj = env->NewObject(cls, ctor,
        static_cast<jint>(info.key), jname, jdesc, jgroup, jsub,
        static_cast<jint>(info.type), static_cast<jint>(info.ui_type),
        static_cast<jlong>(info.min_val), static_cast<jlong>(info.max_val),
        static_cast<jlong>(info.step), static_cast<jint>(info.advanced));
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jdesc);
    env->DeleteLocalRef(jgroup);
    env->DeleteLocalRef(jsub);
    return obj;
}

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeGetParameterEnumCount(JNIEnv*, jclass, jint key) {
    return dasher_get_parameter_enum_count(key);
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeGetParameterEnumName(JNIEnv* env, jclass, jint key, jint index) {
    return env->NewStringUTF(dasher_get_parameter_enum_name(key, index));
}

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeGetParameterEnumValue(JNIEnv*, jclass, jint key, jint index) {
    return dasher_get_parameter_enum_value(key, index);
}

JNIEXPORT jobjectArray JNICALL
Java_at_dasher_android_NativeBridge_nativeGetParameterStringValues(JNIEnv* env, jclass, jlong handle, jint key) {
    auto* s = fromHandle(handle);
    jclass strClass = env->FindClass("java/lang/String");
    if (!s || !s->ctx) return env->NewObjectArray(0, strClass, nullptr);
    constexpr int kMax = 1024;
    const char* buf[kMax] = {};
    int count = dasher_get_parameter_string_values(s->ctx, key, buf, kMax);
    if (count < 0) count = 0;
    if (count > kMax) count = kMax;
    auto arr = env->NewObjectArray(static_cast<jsize>(count), strClass, nullptr);
    for (int i = 0; i < count; ++i) {
        jstring js = env->NewStringUTF(buf[i] ? buf[i] : "");
        env->SetObjectArrayElement(arr, i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

// ── Locale (RFC 0003) ───────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_at_dasher_android_NativeBridge_nativeSetLocale(JNIEnv* env, jclass, jlong handle, jstring jLocale) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx || !jLocale) return -1;
    const char* loc = env->GetStringUTFChars(jLocale, nullptr);
    int rc = dasher_set_locale(s->ctx, loc);
    env->ReleaseStringUTFChars(jLocale, loc);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_at_dasher_android_NativeBridge_nativeGetLocale(JNIEnv* env, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (!s || !s->ctx) return env->NewStringUTF("en");
    return env->NewStringUTF(dasher_get_locale(s->ctx));
}

// ── Parameter-change callback (two-way sync: settings <-> toolbar) ──────────

static void parameterCallback(int key, void*) {
    if (!g_nbClass || !g_onParameterChanged) return;
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) return;
    env->CallStaticVoidMethod(g_nbClass, g_onParameterChanged, static_cast<jint>(key));
    if (attached) g_jvm->DetachCurrentThread();
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetParameterCallback(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_parameter_callback(s->ctx, parameterCallback, nullptr);
}

// ── Engine callbacks (see DasherCore/docs/CUSTOM_ACTIONS.md) ────────────────
// Each installs a C wrapper that marshals back into NativeBridge.onX(...).

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetClipboardCallback(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_clipboard_callback(s->ctx, clipboardCallback, nullptr);
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetSpeakCallback(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_speak_callback(s->ctx, speakCallback, nullptr);
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetMessageCallback(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_message_callback(s->ctx, messageCallback, nullptr);
}

JNIEXPORT void JNICALL
Java_at_dasher_android_NativeBridge_nativeSetOutputCallback(JNIEnv*, jclass, jlong handle) {
    auto* s = fromHandle(handle);
    if (s && s->ctx) dasher_set_output_callback(s->ctx, outputCallback, nullptr);
}

}  // extern "C"
