package org.dasherproject.android

import android.util.Log
import android.view.Choreographer

/** Physical input mechanism driving the Dasher cursor. */
enum class InputMode { TOUCH, TILT }

/**
 * Drives a single DasherCore session frame-by-frame via Android's [Choreographer].
 *
 * Translates touch input into the C-API pointer surface
 * ([NativeBridge.nativeMouseDown]/[nativeMouseUp]/[nativeMouseMove]), runs the
 * per-vsync frame loop, and forwards rendered draw-commands and output text to
 * registered consumers.
 *
 * All public methods must be called on the main thread (the same thread on which
 * [Choreographer.getInstance] is obtained).
 *
 * @param nativeHandle opaque session handle from [NativeBridge.nativeCreate].
 * @param frameConsumer invoked every rendered frame with the draw-command buffer
 *   and its accompanying string labels.
 */
class DasherEngine(
    private val nativeHandle: Long,
    frameConsumer: (IntArray, Array<String>) -> Unit
) : Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var destroyed = false
    private var hasSurface = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var isPaused = true
    private var isTouching = false
    private var inputMode = InputMode.TOUCH
    private var tiltActive = false

    /** Invoked on the main thread whenever the accumulated output text changes. */
    var onTextUpdate: ((String) -> Unit)? = null

    @Volatile
    private var frameConsumer: (IntArray, Array<String>) -> Unit = frameConsumer

    /** Starts the vsync frame loop. No-op if already running or destroyed. */
    fun start() {
        if (running || destroyed) return
        running = true
        choreographer.postFrameCallback(this)
    }

    /** Stops the frame loop without releasing native resources. Restartable via [start]. */
    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
    }

    /** Permanently tears down the engine and frees the native session. */
    fun destroy() {
        if (destroyed) return
        stop()
        destroyed = true
        if (nativeHandle != 0L) NativeBridge.nativeDestroy(nativeHandle)
    }

    /** Notifies the engine of new canvas dimensions. Call on init and on resize. */
    fun onSurfaceSizeChanged(width: Int, height: Int) {
        if (destroyed || nativeHandle == 0L || width <= 0 || height <= 0) return
        hasSurface = true
        surfaceWidth = width
        surfaceHeight = height
        NativeBridge.nativeSetScreenSize(nativeHandle, width, height)
    }

    /** Active input mode (touch or tilt). */
    fun getInputMode(): InputMode = inputMode

    /**
     * Switches input mode. When leaving tilt the active tilt pointer is released.
     * The caller is responsible for registering/unregistering the tilt sensor.
     */
    fun setInputMode(mode: InputMode) {
        if (inputMode == mode) return
        if (inputMode == InputMode.TILT && tiltActive) {
            NativeBridge.nativeMouseUp(nativeHandle)
            tiltActive = false
        }
        inputMode = mode
    }

    /**
     * Delivers a normalised tilt position `(0..1, 0..1)` where `(0.5, 0.5)` is neutral.
     * Drives a continuously-pressed pointer in TILT mode. No-op in TOUCH mode, before
     * the surface is sized, or when destroyed.
     */
    fun onTiltNormalized(normalizedX: Float, normalizedY: Float) {
        if (destroyed || nativeHandle == 0L) return
        if (inputMode != InputMode.TILT || !hasSurface) return
        val x = normalizedX.coerceIn(0f, 1f) * surfaceWidth
        val y = normalizedY.coerceIn(0f, 1f) * surfaceHeight
        NativeBridge.nativeMouseMove(nativeHandle, x, y)
        if (!tiltActive) {
            NativeBridge.nativeMouseDown(nativeHandle)
            tiltActive = true
            isPaused = false
        }
    }

    /** Releases an active tilt pointer (call when unregistering the sensor). */
    fun clearTiltInput() {
        if (destroyed || nativeHandle == 0L || !tiltActive) return
        NativeBridge.nativeMouseUp(nativeHandle)
        tiltActive = false
        isPaused = true
    }

    /**
     * Forwards a touch event to DasherCore.
     *
     * In continuous (touch) mode:
     *  - DOWN resumes the engine and starts zooming,
     *  - MOVE updates the pointer position,
     *  - UP/CANCEL pauses zooming.
     *
     * @param action Android [android.view.MotionEvent] action masked value
     *   (0 = DOWN, 1 = MOVE, 2 = UP/CANCEL).
     */
    fun onTouch(action: Int, x: Float, y: Float) {
        if (destroyed || nativeHandle == 0L) return
        if (inputMode != InputMode.TOUCH) return
        when (action) {
            0 -> { // DOWN
                isPaused = false
                isTouching = true
                NativeBridge.nativeMouseMove(nativeHandle, x, y)
                NativeBridge.nativeMouseDown(nativeHandle)
            }
            1 -> { // MOVE
                if (!isPaused && isTouching) {
                    NativeBridge.nativeMouseMove(nativeHandle, x, y)
                }
            }
            2 -> { // UP / CANCEL
                if (isTouching) {
                    NativeBridge.nativeMouseUp(nativeHandle)
                    isTouching = false
                }
                isPaused = true
            }
        }
    }

    fun setSpeedPercent(percent: Int) {
        if (destroyed || nativeHandle == 0L) return
        NativeBridge.nativeSetSpeedPercent(nativeHandle, percent)
    }

    fun setAlphabet(alphabetId: String) {
        if (destroyed || nativeHandle == 0L) return
        NativeBridge.nativeSetAlphabetId(nativeHandle, alphabetId)
    }

    fun setLanguageModelId(id: Int) {
        if (destroyed || nativeHandle == 0L) return
        NativeBridge.nativeSetLanguageModelId(nativeHandle, id)
    }

    fun resetOutputText() {
        if (destroyed || nativeHandle == 0L) return
        NativeBridge.nativeResetOutputText(nativeHandle)
    }

    // ── Status-bar surface (Phase 1) ──────────────────────────────────────────

    fun getSpeedPercent(): Int {
        if (destroyed || nativeHandle == 0L) return 100
        return NativeBridge.nativeGetSpeedPercent(nativeHandle)
    }

    /** Display name of the currently active alphabet. */
    fun getCurrentAlphabet(): String {
        if (destroyed || nativeHandle == 0L) return ""
        return NativeBridge.nativeGetAlphabetId(nativeHandle)
    }

    /** All available alphabet display names (for the alphabet picker). */
    fun getAlphabetNames(): List<String> {
        if (destroyed || nativeHandle == 0L) return emptyList()
        val count = NativeBridge.nativeGetAlphabetCount(nativeHandle)
        return (0 until count).map { NativeBridge.nativeGetAlphabetName(nativeHandle, it) }
    }

    fun getCurrentPalette(): String {
        if (destroyed || nativeHandle == 0L) return ""
        return NativeBridge.nativeGetCurrentPalette(nativeHandle)
    }

    fun getPaletteNames(): List<String> {
        if (destroyed || nativeHandle == 0L) return emptyList()
        val count = NativeBridge.nativeGetPaletteCount(nativeHandle)
        return (0 until count).map { NativeBridge.nativeGetPaletteName(nativeHandle, it) }
    }

    fun setPalette(name: String) {
        if (destroyed || nativeHandle == 0L) return
        NativeBridge.nativeSetPalette(nativeHandle, name)
    }

    /** Flush settings to dasher_settings.xml in the user dir. */
    fun saveSettings() {
        if (destroyed || nativeHandle == 0L) return
        NativeBridge.nativeSaveSettings(nativeHandle)
    }

    /**
     * Installs the engine→frontend callbacks (clipboard/speak/message/output).
     * Call after the engine is created; the listeners live on [NativeBridge].
     * See DasherCore/docs/CUSTOM_ACTIONS.md.
     */
    fun installEngineCallbacks() {
        if (destroyed || nativeHandle == 0L) return
        NativeBridge.nativeSetClipboardCallback(nativeHandle)
        NativeBridge.nativeSetSpeakCallback(nativeHandle)
        NativeBridge.nativeSetMessageCallback(nativeHandle)
        NativeBridge.nativeSetOutputCallback(nativeHandle)
    }

    // ── Parameter helpers (resolve BP_*/LP_*/SP_* names to keys internally) ─────

    /** Reads a boolean parameter by enum name (e.g. "BP_CONTROL_MODE"). */
    fun getBoolParam(name: String): Boolean {
        if (destroyed || nativeHandle == 0L) return false
        val key = NativeBridge.nativeFindParameterKey(name)
        if (key < 0) return false
        return NativeBridge.nativeGetBoolParameter(nativeHandle, key) != 0
    }

    /** Sets a boolean parameter by enum name and persists. */
    fun setBoolParam(name: String, value: Boolean) {
        if (destroyed || nativeHandle == 0L) return
        val key = NativeBridge.nativeFindParameterKey(name)
        if (key < 0) return
        NativeBridge.nativeSetBoolParameter(nativeHandle, key, if (value) 1 else 0)
        saveSettings()
    }

    /** Reads a long parameter by enum name (e.g. "LP_DASHER_FONTSIZE"). */
    fun getLongParam(name: String): Long {
        if (destroyed || nativeHandle == 0L) return 0L
        val key = NativeBridge.nativeFindParameterKey(name)
        if (key < 0) return 0L
        return NativeBridge.nativeGetLongParameter(nativeHandle, key)
    }

    /** Sets a long parameter by enum name and persists. */
    fun setLongParam(name: String, value: Long) {
        if (destroyed || nativeHandle == 0L) return
        val key = NativeBridge.nativeFindParameterKey(name)
        if (key < 0) return
        NativeBridge.nativeSetLongParameter(nativeHandle, key, value)
        saveSettings()
    }

    /** [Choreographer.FrameCallback] — one render step per vsync. */
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (!destroyed && nativeHandle != 0L && hasSurface) {
            val timeMs = frameTimeNanos / 1_000_000L
            // When paused we still tick the engine so the canvas redraws; the engine
            // itself won't advance the zoom without a pressed pointer.
            val commands = NativeBridge.nativeFrame(nativeHandle, timeMs)
            val strings = NativeBridge.nativeGetFrameStrings(nativeHandle)
            frameConsumer(commands, strings)
            onTextUpdate?.invoke(NativeBridge.nativeGetOutputText(nativeHandle))
        }
        if (running) {
            choreographer.postFrameCallback(this)
        }
    }

    /** Replaces the frame consumer (e.g. across configuration changes). */
    fun setFrameConsumer(consumer: (IntArray, Array<String>) -> Unit) {
        frameConsumer = consumer
    }

    companion object {
        private const val TAG = "DasherEngine"

        /**
         * Convenience factory: ensures the native library is loaded, extracts data,
         * and creates the engine session.
         *
         * @return a ready-to- [start] engine, or null if the session could not be created.
         */
        fun create(dataDir: String, userDir: String,
                   frameConsumer: (IntArray, Array<String>) -> Unit): DasherEngine? {
            NativeBridge.ensureLoaded()
            val handle = NativeBridge.nativeCreate(dataDir, userDir)
            if (handle == 0L) {
                Log.e(TAG, "nativeCreate returned 0 — dataDir=$dataDir")
                return null
            }
            return DasherEngine(handle, frameConsumer)
        }
    }
}
