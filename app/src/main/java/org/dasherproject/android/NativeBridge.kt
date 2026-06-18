package org.dasherproject.android

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
}
