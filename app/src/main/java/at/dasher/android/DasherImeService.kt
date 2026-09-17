package at.dasher.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import at.dasher.android.ui.DasherCanvasView

/**
 * Android system IME (keyboard) that lets the user write with Dasher into *any* app.
 *
 * Supports a **floating mode** (like Gboard): the Dasher canvas detaches from the
 * docked position and floats as a draggable overlay — useful on tablets/large screens.
 * Toggle via the float/dock button in the IME's top bar.
 */
class DasherImeService : InputMethodService() {

    private var engine: DasherEngine? = null
    private var canvasView: DasherCanvasView? = null
    private var canvasHost: android.widget.FrameLayout? = null
    private var dockedRoot: LinearLayout? = null
    private var loadingOverlay: android.widget.FrameLayout? = null

    // Floating mode
    private var floating = false
    private var floatingView: LinearLayout? = null

    // The docked root's parent is a FrameLayout inside the IME window's
    // decor. setLayoutParams performs NO type conversion — LinearLayout
    // .LayoutParams survives until the next measure pass casts them and
    // crashes (PostHog #43). This helper makes the wrong type impossible.
    private fun setDockedHeight(heightPx: Int) {
        dockedRoot?.layoutParams = android.widget.FrameLayout.LayoutParams(MATCH_PARENT, heightPx)
    }

    // Shared height formula (was duplicated between onCreateInputView and
    // exitFloatingMode — drift would give different dock heights).
    // #48: 35% on screens ≥600dp (tablets/foldables — Heide: "you can't even
    // see what you're zooming"), 42% on phones.
    private fun imeHeightPx(): Int {
        val fraction = if (resources.displayMetrics.widthPixels >= dp(600, resources.displayMetrics.density)) 0.35f else 0.42f
        return (resources.displayMetrics.heightPixels * fraction).toInt()
    }
    private val windowManager get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var editingToolbar: EditingToolbar? = null

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = if (nightMode) 0xFF1E262B.toInt() else 0xFFF4F7F6.toInt()

        // Dasher canvas — shared between docked and floating modes.
        val canvas = DasherCanvasView(this).apply {
            onSurfaceSizeChanged = { w, h -> engine?.onSurfaceSizeChanged(w, h) }
            onTouchInput = { action, x, y -> engine?.onTouch(action, x, y) }
        }
        canvasView = canvas

        // Loading overlay: on first-ever show (user enabled the keyboard
        // before opening the app) DataInstaller must extract the bundled
        // data before the engine can render — without this the keyboard
        // area sits black/empty for seconds (Heide's report).
        val loading = android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            val spinner = android.widget.ProgressBar(this@DasherImeService)
            val label = android.widget.TextView(this@DasherImeService).apply {
                text = getText(R.string.preparing_dasher)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(8, density), 0, 0)
            }
            val inner = LinearLayout(this@DasherImeService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            inner.addView(spinner)
            inner.addView(label)
            addView(inner, android.widget.FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        loadingOverlay = loading

        val canvasHost = android.widget.FrameLayout(this).apply {
            addView(canvas, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(loading, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        this.canvasHost = canvasHost

        // Top bar (shared look): Hide + Float toggle.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8, density), dp(2, density), dp(8, density), dp(2, density))
        }
        val floatBtn = Button(this).apply { text = "Float" }
        val hideBtn = Button(this).apply {
            text = "Hide"
            setOnClickListener { requestHideSelf(0) }
        }
        top.addView(floatBtn)
        top.addView(hideBtn)

        // RFC 0019 editing toolbar (#50): backspace, cursor, clipboard.
        // Acts on the TARGET app via InputConnection; after each action,
        // re-read the target text and re-anchor the engine so predictions
        // follow the edit (RFC 0015 tier 2).
        val toolbar = EditingToolbar(
            context = this,
            inputConnection = { currentInputConnection },
            onBufferChanged = { reanchorEngineToTarget() },
        )
        editingToolbar = toolbar

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(canvasHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // The root is the IME window's content view — its parent is the
        // window's FrameLayout-based decor. LinearLayout.LayoutParams here
        // survives until a re-measure casts them and crashes (Float's
        // docked-shrink relayout did exactly that).
        setDockedHeight(imeHeightPx())
        root.minimumHeight = imeHeightPx()
        dockedRoot = root

        floatBtn.setOnClickListener { enterFloatingMode(floatBtn) }

        Handler(Looper.getMainLooper()).post { createEngine() }
        return root
    }

    // ── Floating mode ──────────────────────────────────────────────────────

    private fun enterFloatingMode(floatBtn: Button) {
        if (floating) return
        // Floating mode draws over other apps (TYPE_APPLICATION_OVERLAY) —
        // a special-app-access permission the user must grant. Without it
        // addView throws BadTokenException and the tap silently did nothing.
        // Point the user at the grant screen instead.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.float_needs_permission),
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent) } catch (_: Exception) {}
            return
        }
        val density = resources.displayMetrics.density
        val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = if (nightMode) 0xFF1E262B.toInt() else 0xFFF4F7F6.toInt()
        val screenW = resources.displayMetrics.widthPixels
        val floatW = minOf(screenW - dp(32, density), dp(600, density))
        val floatH = dp(280, density)

        // Drag handle bar with a Dock button and a resize handle (#49).
        val dragBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(if (nightMode) 0xFF2A353D.toInt() else 0xFFE0E6E8.toInt())
            setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density))
        }
        val dockBtn = Button(this).apply { text = "Dock" }
        dragBar.addView(dockBtn)
        // Resize handle (#49): drag right/bottom edge to grow, left/top to shrink.
        val resizeHandle = Button(this).apply {
            text = "⟷" // horizontal resize indicator
            contentDescription = "Resize"
            layoutParams = LinearLayout.LayoutParams(dp(44, density), WRAP_CONTENT)
        }
        dragBar.addView(resizeHandle)

        // Floating container: drag bar + canvas.
        val floating = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            // Rounded corners
            background = GradientDrawable().apply {
                cornerRadius = dp(12, density).toFloat()
                setColor(bg)
            }
            setPadding(dp(2, density), dp(2, density), dp(2, density), dp(2, density))
        }
        floating.addView(dragBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // RFC 0019 editing toolbar in floating mode too (#50). Must detach
        // from the docked root FIRST — addView on an attached child throws
        // IllegalStateException (review C1: Float crashed on first tap).
        editingToolbar?.let { toolbar ->
            (toolbar.parent as? ViewGroup)?.removeView(toolbar)
            floating.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // Reparent the canvas (host carries the loading overlay) from docked to floating.
        (canvasHost?.parent as? ViewGroup)?.removeView(canvasHost)
        floating.addView(canvasHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val params = WindowManager.LayoutParams(
            floatW, floatH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (screenW - floatW) / 2
            y = resources.displayMetrics.heightPixels - floatH - dp(48, resources.displayMetrics.density)
        }

        try {
            windowManager.addView(floating, params)
        } catch (e: Exception) {
            Log.e(TAG, "Floating overlay failed: ${e.message}")
            // Fall back: put canvas and toolbar back in docked
            floating.removeView(canvasHost)
            dockedRoot?.addView(canvasHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            editingToolbar?.let { toolbar ->
                floating.removeView(toolbar)
                dockedRoot?.addView(toolbar, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            return
        }

        floatingView = floating
        this.floating = true

        // Shrink the docked view so the system doesn't reserve full keyboard space.
        // FrameLayout.LayoutParams: the docked root's parent is the window's
        // FrameLayout decor — LinearLayout.LayoutParams crash on re-measure.
        setDockedHeight(dp(40, density))
        floatBtn.text = "Dock"
        floatBtn.setOnClickListener { exitFloatingMode(floatBtn) }

        // Drag handling. Gravity.TOP or Gravity.LEFT — LEFT is explicit;
        // START can resolve to RIGHT on Samsung (overlay windows), inverting
        // the x-axis (Heide: "it moves the opposite way horizontally"). Both
        // axes use + (finger right → window right, finger down → window
        // down); the old y formula had a compensating - that only worked
        // by accident on the same Samsung devices that broke x.
        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        dragBar.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (ev.rawX - touchX).toInt()
                    params.y = initY + (ev.rawY - touchY).toInt()
                    try { windowManager.updateViewLayout(floating, params) } catch (_: Exception) { }
                    true
                }
                else -> false
            }
        }
        // Also wire the dock button.
        dockBtn.setOnClickListener { exitFloatingMode(floatBtn) }

        // Resize handling (#49): drag the ⟷ handle to adjust the floating
        // window's width (height follows proportionally). Pin the handle's
        // initial touch position and the window's initial size; the delta
        // applies to both dimensions with a 0.47 height-to-width ratio.
        var initW = 0; var resizeTouchX = 0f
        val minW = dp(240, density)
        val maxW = screenW - dp(16, density)
        resizeHandle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initW = params.width
                    resizeTouchX = ev.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (initW + (ev.rawX - resizeTouchX).toInt()).coerceIn(minW, maxW)
                    params.width = newW
                    params.height = (newW * 0.47f).toInt().coerceIn(dp(180, density), dp(420, density))
                    try { windowManager.updateViewLayout(floating, params) } catch (_: Exception) { }
                    // Notify the canvas of its new size.
                    canvasView?.let { v ->
                        v.post { engine?.onSurfaceSizeChanged(v.width, v.height) }
                    }
                    true
                }
                else -> false
            }
        }

        // Notify the canvas of its new size.
        canvasView?.let { if (it.width > 0 && it.height > 0) engine?.onSurfaceSizeChanged(it.width, it.height) }
    }

    private fun exitFloatingMode(floatBtn: Button) {
        if (!floating) return
        val fv = floatingView ?: return
        try { windowManager.removeView(fv) } catch (_: Exception) {}
        // Detach the toolbar from floating; re-insert at the TOP of the
        // docked root (index 0, above the Float/Hide bar — where it was
        // before floating). Review I1: the toolbar was being orphaned.
        editingToolbar?.let { toolbar ->
            (toolbar.parent as? ViewGroup)?.removeView(toolbar)
            dockedRoot?.addView(toolbar, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // Reparent canvas back to docked.
        (canvasHost?.parent as? ViewGroup)?.removeView(canvasHost)
        dockedRoot?.addView(canvasHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // Restore docked height (#48: adaptive — smaller on tablets).
        setDockedHeight(imeHeightPx())
        floatingView = null
        floating = false
        floatBtn.text = "Float"
        floatBtn.setOnClickListener { enterFloatingMode(floatBtn) }
        canvasView?.let { if (it.width > 0 && it.height > 0) engine?.onSurfaceSizeChanged(it.width, it.height) }
    }

    // ── Engine ─────────────────────────────────────────────────────────────

    private fun createEngine() {
        val dataDir = DataInstaller.ensureInstalled(this)
        // User state (settings, training) lives outside the data dir so a
        // data re-extraction can never wipe it.
        val userDir = DataInstaller.userDir(this).apply { mkdirs() }
        val eng = DasherEngine.create(dataDir, userDir.absolutePath) { commands, strings ->
            canvasView?.submitFrame(commands, strings)
        }
        if (eng == null) {
            Log.e(TAG, "IME engine creation failed (dataDir=$dataDir)")
            return
        }
        eng.setLowMemoryMode(true)
        eng.installEngineCallbacks()
        // Real label metrics (DasherCore v0.2.4 / upstream #56), measured with
        // THIS keyboard's canvas paint. NativeBridge listeners are static, so
        // the IME must reinstall its own on every engine creation rather than
        // inherit the (possibly stale) app Activity's.
        canvasView?.onGlyphFontChanged = { eng.textMetricsChanged() }
        eng.installTextSizeCallback { text, fontSize, out ->
            val dims = canvasView?.measureGlyphText(text, fontSize) ?: return@installTextSizeCallback false
            out[0] = dims.first
            out[1] = dims.second
            true
        }
        // RFC 0007: push the OS dark-mode state so a SYSTEM-mode user gets the right
        // derived palette (mirrors MainActivity; settings/appearance persist on disk
        // and are read at engine creation).
        val nightMode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        eng.setSystemAppearance(nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        // Install ALL IME-owned static listeners. The statics on NativeBridge
        // are process-wide — MainActivity's installAppListeners() overwrites
        // them when the user opens the main app, and the IME engine is
        // created once per process (not per show), so without re-asserting
        // on every onStartInputView the IME's handlers are silently dead
        // after any visit to the main app (#53 and its siblings: output,
        // clipboard, messages, speak, text-measurement).
        engine = eng
        installImeListeners()
        // The canvas laid out during onCreateInputView — BEFORE this handler
        // posted — so its size callback hit a null engine and was dropped.
        // Without a screen size the engine never realizes and renders
        // nothing (the black-keyboard bug). Push the size explicitly, the
        // same way MainActivity does after engine creation.
        canvasView?.let { v ->
            if (v.width > 0 && v.height > 0) eng.onSurfaceSizeChanged(v.width, v.height)
        }
        // Engine is live and rendering — drop the first-show loading overlay.
        loadingOverlay?.visibility = View.GONE
        eng.start()
    }

    // ── Engine re-anchoring (RFC 0015 tier 2) ────────────────────────────

    /**
     * After an editing action (backspace, cursor move, paste), the target
     * field's text changed. Re-read it and re-anchor the engine so
     * predictions follow (RFC 0015 tier 2 + RFC 0019 clause 2). Reads a
     * trailing window (not the full field — sentence-window, governance#40).
     */
    private fun reanchorEngineToTarget() {
        val eng = engine ?: return
        val ic = currentInputConnection ?: return
        // Read a trailing window of text before the cursor (the engine's
        // context is what's BEFORE the caret — predictions continue from it).
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: return
        if (before.isEmpty()) {
            // Empty window: the engine's buffer is stale (user backspaced
            // to the start or the field is empty) — reset rather than
            // predict from deleted text (review I2).
            eng.newSession()
            return
        }
        // seedBuffer converts the UTF-16 caret to UTF-8 bytes internally —
        // do NOT pre-convert (review C2: double-conversion shifted the
        // anchor past the caret for any non-ASCII text).
        eng.seedBuffer(before, before.length)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // #48: fold/unfold changes the screen dimensions — recompute the
        // docked height so the adaptive fraction (35%/42%) matches the
        // current device state. Only when not floating (floating has its
        // own window size, managed by the resize handle).
        if (!floating) {
            setDockedHeight(imeHeightPx())
            dockedRoot?.minimumHeight = imeHeightPx()
        } else {
            // Floating: the recreated docked root gets full height at
            // line 143 — re-shrink it (review 2: fold-while-floating
            // produced a full-height empty dock behind the overlay).
            setDockedHeight(dp(40, resources.displayMetrics.density))
        }
    }

    /**
     * Asserts ALL NativeBridge static listeners to the IME's handlers.
     * Called from createEngine() AND onStartInputView() — the main app's
     * installAppListeners() (MainActivity) overwrites every static when it
     * creates its engine, and the IME's engine is created once per process,
     * not per show. Without this re-assert on every show, output (#53),
     * clipboard, messages, speak, and text-measurement are silently dead
     * after any visit to the main app.
     */
    private fun installImeListeners() {
        val eng = engine ?: return
        // Per-engine-instance listeners (#56): the JNI dispatch passes the
        // handle; only THIS engine's listener is called. No cross-talk with
        // the main app's engine.
        eng.setOutputListener { type, text ->
            val ic = currentInputConnection
            if (ic != null) {
                if (type == 0) ic.commitText(text, 1)
                else if (text.isNotEmpty()) ic.deleteSurroundingText(text.length, 0)
            }
        }
        eng.setMessageListener { _, msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        eng.setClipboardListener { text ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Dasher", text))
        }
        eng.clearSpeakListener() // IME doesn't speak (the app does)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Re-assert ALL IME-owned static listeners — the main app may have
        // overwritten them (#53: output, clipboard, messages, speak).
        installImeListeners()
        // #54: always reload — the main app's engine writes to the shared
        // dasher_settings.xml while the IME engine holds its own in-memory
        // copy. The old `if (restarting)` check only covered re-showing for
        // the SAME target field, missing the common case of switching from
        // the main app to the IME with changed settings (Heide: "don't
        // remember the setting that you set"). Cheap: one file read +
        // diff, only changed parameters fire callbacks.
        engine?.reloadSettings()
        engine?.start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine?.stop()
    }

    override fun onDestroy() {
        if (floating) {
            floatingView?.let { fv -> try { windowManager.removeView(fv) } catch (_: Exception) {} }
        }
        // Per-engine-instance cleanup (#56): destroy() unregisters all
        // listeners for THIS engine's handle. No static cleanup needed —
        // the main app's engine has its own registration.
        engine?.destroy()
        engine = null
        canvasView = null
        canvasHost = null
        loadingOverlay = null
        dockedRoot = null
        editingToolbar = null
        super.onDestroy()
    }

    // ── Utils ──────────────────────────────────────────────────────────────

    private fun dp(value: Int, density: Float) = (value * density).toInt()

    private companion object {
        const val TAG = "DasherImeService"
        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
