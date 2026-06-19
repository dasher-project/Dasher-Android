package at.dasher.android

/**
 * Thin Kotlin facade over the DasherCore C API (`dasher.h`).
 *
 * Each `external fun` maps 1:1 onto a `Java_org_dasherproject_android_NativeBridge_*`
 * symbol in `jni_bridge.cpp`, which in turn wraps the corresponding `dasher_*`
 * symbol exported by `libdasher.so`.
 *
 * Session model: [nativeCreate] returns an opaque [Long] handle (a pointer to the
 * native `dasher_ctx`). All other functions take it as their first argument. Passing
 * an invalid handle is safe — the native layer null-checks and no-ops.
 *
 * The native library `dasher_jni` pulls in its dependency `dasher` automatically
 * (recorded as a DT_NEEDED entry), so a single [System.loadLibrary] is enough.
 */
object NativeBridge {
    private var loaded = false

    /** Loads libdasher_jni.so (and libdasher.so as a dependency). Safe to call repeatedly. */
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("dasher_jni")
            loaded = true
        }
    }

    /** Human-readable identifier for the bundled DasherCore build. */
    @JvmStatic external fun nativeVersion(): String

    /**
     * Creates a Dasher session.
     *
     * @param dataDir Absolute path containing DasherCore's Data/ contents (alphabets/,
     *   colours/, training/, …). On Android this is [android.content.Context.filesDir]
     *   after [DataInstaller] has copied the bundled assets there.
     * @param userDir Writable directory for `dasher_settings.xml` and user data. May equal
     *   [dataDir].
     * @return Opaque session handle (non-zero on success, 0 on failure).
     */
    @JvmStatic external fun nativeCreate(dataDir: String, userDir: String): Long

    /** Releases all native resources for [handle]. The handle is invalid afterwards. */
    @JvmStatic external fun nativeDestroy(handle: Long)

    /**
     * Enables low-memory mode (loads only the selected alphabet + default input filter).
     * Must be called before [nativeSetScreenSize]. Used by the IME process.
     */
    @JvmStatic external fun nativeSetLowMemoryMode(handle: Long, enabled: Int)

    /** Notifies the engine of canvas dimensions. Call on init and on resize. */
    @JvmStatic external fun nativeSetScreenSize(handle: Long, width: Int, height: Int)

    /** Feed pointer coordinates (touch/mouse/eye-gaze) in pixels, origin top-left. */
    @JvmStatic external fun nativeMouseMove(handle: Long, x: Float, y: Float)

    /** Pointer press — starts Dasher zooming. */
    @JvmStatic external fun nativeMouseDown(handle: Long)

    /** Pointer release — pauses Dasher zooming. */
    @JvmStatic external fun nativeMouseUp(handle: Long)

    /**
     * Advance one frame and return the draw-command buffer.
     *
     * Each command is 6 ints: `[op, a, b, c, d, argb]`.
     * | op | Primitive        | Meaning of a–d                     |
     * |----|------------------|------------------------------------|
     * | 0  | Clear            | argb = background colour           |
     * | 1  | Circle           | a,cx b,cy c,radius d,filled(0/1)   |
     * | 2  | Line             | x1,y1 → x2,y2                      |
     * | 3  | Rectangle stroke | x1,y1 → x2,y2                      |
     * | 4  | Rectangle fill   | x1,y1 → x2,y2                      |
     * | 5  | Text             | a,x b,y c,fontSize d,stringIndex   |
     *
     * The accompanying string labels are returned by [nativeGetFrameStrings].
     */
    @JvmStatic external fun nativeFrame(handle: Long, timeMs: Long): IntArray

    /** Returns and clears the string labels queued during the last [nativeFrame]. */
    @JvmStatic external fun nativeGetFrameStrings(handle: Long): Array<String>

    /** Accumulated output text for this session. */
    @JvmStatic external fun nativeGetOutputText(handle: Long): String

    /** Clears the output text buffer. */
    @JvmStatic external fun nativeResetOutputText(handle: Long)

    /** Current alphabet identifier (e.g. "English with limited punctuation"). */
    @JvmStatic external fun nativeGetAlphabetId(handle: Long): String

    /** Switches alphabet. Takes effect on the next frame. */
    @JvmStatic external fun nativeSetAlphabetId(handle: Long, alphabetId: String)

    /** Active language-model id (0 = PPM, 2 = Word, 5 = KenLM, …). */
    @JvmStatic external fun nativeGetLanguageModelId(handle: Long): Int

    /** Switches language model. */
    @JvmStatic external fun nativeSetLanguageModelId(handle: Long, id: Int)

    /** Movement-speed percentage (100 = default, clamped to 20–400 by the engine). */
    @JvmStatic external fun nativeGetSpeedPercent(handle: Long): Int

    /** Sets the movement-speed percentage. */
    @JvmStatic external fun nativeSetSpeedPercent(handle: Long, percent: Int)

    // ── Generic parameter surface (mirror of Dasher-Windows NativeBridge.cs) ──
    // Use [nativeFindParameterKey] to resolve a "BP_*"/"LP_*"/"SP_*" enum name to a key,
    // then get/set by key. See DasherCore/.../Parameters.h for the enum values.

    /** Resolve a parameter enum name (e.g. "SP_INPUT_FILTER") to its numeric key, or -1. */
    @JvmStatic external fun nativeFindParameterKey(enumName: String): Int

    @JvmStatic external fun nativeGetBoolParameter(handle: Long, key: Int): Int
    @JvmStatic external fun nativeSetBoolParameter(handle: Long, key: Int, value: Int)
    @JvmStatic external fun nativeGetLongParameter(handle: Long, key: Int): Long
    @JvmStatic external fun nativeSetLongParameter(handle: Long, key: Int, value: Long)
    @JvmStatic external fun nativeGetStringParameter(handle: Long, key: Int): String
    @JvmStatic external fun nativeSetStringParameter(handle: Long, key: Int, value: String)

    // ── Alphabets ──
    /** Number of available alphabets (WorldAlphabets, 622 in v6). */
    @JvmStatic external fun nativeGetAlphabetCount(handle: Long): Int
    /** Alphabet display name at [index] (0..count-1). */
    @JvmStatic external fun nativeGetAlphabetName(handle: Long, index: Int): String

    // ── Colour palettes ──
    @JvmStatic external fun nativeGetPaletteCount(handle: Long): Int
    @JvmStatic external fun nativeGetPaletteName(handle: Long, index: Int): String
    @JvmStatic external fun nativeGetCurrentPalette(handle: Long): String
    @JvmStatic external fun nativeSetPalette(handle: Long, name: String)

    // ── Persistence ──
    /** Flush current settings to dasher_settings.xml in the user dir. */
    @JvmStatic external fun nativeSaveSettings(handle: Long)

    // ── Engine callbacks (DasherCore/docs/CUSTOM_ACTIONS.md) ──
    // The C wrappers in jni_bridge.cpp marshal these onto NativeBridge.onX(...).

    /** Installs the clipboard callback (engine → copy control action / copy-on-stop). */
    @JvmStatic external fun nativeSetClipboardCallback(handle: Long)
    /** Installs the speak callback (engine → TTS via speak control action / speak-on-stop). */
    @JvmStatic external fun nativeSetSpeakCallback(handle: Long)
    /** Installs the message callback (engine warnings/info as native UI). */
    @JvmStatic external fun nativeSetMessageCallback(handle: Long)
    /** Installs the real-time output callback (per-char insert/delete; enables reactive output). */
    @JvmStatic external fun nativeSetOutputCallback(handle: Long)

    // ── Callback listeners (set by the app; invoked on the main thread) ──
    @JvmStatic var onClipboardListener: ((String) -> Unit)? = null
    @JvmStatic var onSpeakListener: ((text: String, interrupt: Boolean) -> Unit)? = null
    @JvmStatic var onMessageListener: ((type: Int, text: String) -> Unit)? = null
    @JvmStatic var onOutputListener: ((type: Int, text: String) -> Unit)? = null

    @JvmStatic fun onClipboard(text: String) { onClipboardListener?.invoke(text) }
    @JvmStatic fun onSpeak(text: String, interrupt: Int) {
        onSpeakListener?.invoke(text, interrupt != 0)
    }
    @JvmStatic fun onMessage(type: Int, text: String) { onMessageListener?.invoke(type, text) }
    @JvmStatic fun onOutput(type: Int, text: String) { onOutputListener?.invoke(type, text) }
}
