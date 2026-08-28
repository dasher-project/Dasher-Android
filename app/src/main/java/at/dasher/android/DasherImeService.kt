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
    private var floatingParams: WindowManager.LayoutParams? = null
    private val windowManager get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = if (nightMode) 0xFF1E262B.toInt() else 0xFFF4F7F6.toInt()
        val imeHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(canvasHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, imeHeight)
        root.minimumHeight = imeHeight
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

        // Drag handle bar with a Dock button.
        val dragBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(if (nightMode) 0xFF2A353D.toInt() else 0xFFE0E6E8.toInt())
            setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density))
        }
        val dockBtn = Button(this).apply { text = "Dock" }
        dragBar.addView(dockBtn)

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
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - floatW) / 2
            y = resources.displayMetrics.heightPixels - floatH - dp(48, resources.displayMetrics.density)
        }

        try {
            windowManager.addView(floating, params)
        } catch (e: Exception) {
            Log.e(TAG, "Floating overlay failed: ${e.message}")
            // Fall back: put canvas back in docked
            floating.removeView(canvasHost)
            dockedRoot?.addView(canvasHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            return
        }

        floatingView = floating
        floatingParams = params
        this.floating = true

        // Shrink the docked view so the system doesn't reserve full keyboard space.
        dockedRoot?.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(40, density))
        floatBtn.text = "Dock"
        floatBtn.setOnClickListener { exitFloatingMode(floatBtn) }

        // Drag handling.
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
                    params.y = initY - (ev.rawY - touchY).toInt()
                    windowManager.updateViewLayout(floating, params)
                    true
                }
                else -> false
            }
        }
        // Also wire the dock button.
        dockBtn.setOnClickListener { exitFloatingMode(floatBtn) }

        // Notify the canvas of its new size.
        canvasView?.let { if (it.width > 0 && it.height > 0) engine?.onSurfaceSizeChanged(it.width, it.height) }
    }

    private fun exitFloatingMode(floatBtn: Button) {
        if (!floating) return
        val fv = floatingView ?: return
        val fp = floatingParams ?: return
        try { windowManager.removeView(fv) } catch (_: Exception) {}
        // Reparent canvas back to docked.
        (canvasHost?.parent as? ViewGroup)?.removeView(canvasHost)
        dockedRoot?.addView(canvasHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // Restore docked height.
        val imeHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        dockedRoot?.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, imeHeight)
        floatingView = null
        floatingParams = null
        floating = false
        floatBtn.text = "Float"
        floatBtn.setOnClickListener { enterFloatingMode(floatBtn) }
        canvasView?.let { if (it.width > 0 && it.height > 0) engine?.onSurfaceSizeChanged(it.width, it.height) }
    }

    // ── Engine ─────────────────────────────────────────────────────────────

    private fun createEngine() {
        val dataDir = DataInstaller.ensureInstalled(this)
        val eng = DasherEngine.create(dataDir, dataDir) { commands, strings ->
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
        NativeBridge.onOutputListener = { type, text ->
            val ic = currentInputConnection
            if (ic != null) {
                if (type == 0) ic.commitText(text, 1)
                else if (text.isNotEmpty()) ic.deleteSurroundingText(text.length, 0)
            }
        }
        NativeBridge.onMessageListener = { _, msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        NativeBridge.onClipboardListener = { text ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Dasher", text))
        }
        NativeBridge.onSpeakListener = null
        engine = eng
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

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // The main app's engine writes to the shared dasher_settings.xml
        // while the IME engine holds its own in-memory copy — reload on
        // every show so speed, alphabet, palette etc. match what the user
        // last set (dasher_reload_settings, DasherCore v0.2.11). Cheap:
        // one file read + diff, only changed parameters fire callbacks.
        if (restarting) engine?.reloadSettings()
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
        NativeBridge.onOutputListener = null
        NativeBridge.onMessageListener = null
        NativeBridge.onClipboardListener = null
        NativeBridge.onSpeakListener = null
        engine?.destroy()
        engine = null
        canvasView = null
        canvasHost = null
        loadingOverlay = null
        dockedRoot = null
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
