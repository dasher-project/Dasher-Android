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
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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

    // Bug #5 ("letters get completely jumbled up in OneNote and Word"):
    // engine deltas coalesce here and flush ONCE per frame as one atomic
    // batch edit — see ImeOutputCoalescer.
    private val outputCoalescer = ImeOutputCoalescer()

    private var canvasView: DasherCanvasView? = null
    private var canvasHost: android.widget.FrameLayout? = null
    private var dockedRoot: LinearLayout? = null
    private var loadingOverlay: android.widget.FrameLayout? = null

    // Floating mode
    private var floating = false
    private var floatingView: LinearLayout? = null
    private var floatDockBtn: Button? = null
    private var emojiToggleBtn: Button? = null

    // Last-used mode (dock/float), restored on the next keyboard show so
    // the IME opens where the user left it (Heide: "start it directly as
    // docked, like other keyboards do"). Docked is the default. Keys and
    // values: [ImePrefs] — shared with MainActivity and SettingsScreen.
    private val imePrefs get() = getSharedPreferences(ImePrefs.NAME, MODE_PRIVATE)

    private fun saveStartMode(floatingNow: Boolean) {
        Log.d(TAG, "saveStartMode($floatingNow)")
        imePrefs.edit().putString(
            ImePrefs.START_MODE,
            if (floatingNow) ImePrefs.START_MODE_FLOATING else ImePrefs.START_MODE_DOCKED
        ).apply()
    }

    /**
     * #75 escape hatch: users who ALWAYS want docked (Heide reported the
     * float restore three times) pin it in Settings → Input; any saved
     * float preference is then ignored on every keyboard show. The decision
     * itself is [shouldRestoreFloating] (pure, unit-tested).
     */
    private fun startModeIsFloating(): Boolean =
        shouldRestoreFloating(
            savedMode = imePrefs.getString(ImePrefs.START_MODE, null),
            alwaysDocked = imePrefs.getBoolean(ImePrefs.ALWAYS_DOCKED, false),
            overlayPermissionGranted = android.provider.Settings.canDrawOverlays(this),
        )

    // ── Emoji alphabet toggle (#61 option 2) ───────────────────────────────

    // The alphabet to return to after emoji mode. Persisted so a keyboard
    // process death mid-emoji-mode doesn't strand the user without a way
    // back (the engine persists its own alphabet choice in settings — if it
    // restarts in Emoji, the previous one must still be knowable).
    private var emojiReturnAlphabet: String? = null

    private fun toggleEmojiMode() {
        val eng = engine ?: return
        val btn = emojiToggleBtn ?: return
        val current = eng.getCurrentAlphabet()
        if (current == "Emoji") {
            val target = emojiReturnAlphabet
            if (target.isNullOrEmpty() || target == "Emoji") {
                // No remembered alphabet (e.g. pref lost) — fall back to the
                // engine default rather than a no-op toggle.
                eng.setAlphabet("English with limited punctuation")
            } else {
                eng.setAlphabet(target)
            }
            emojiReturnAlphabet = null
            imePrefs.edit().remove(ImePrefs.EMOJI_RETURN_ALPHABET).apply()
            btn.text = "😀"
            btn.contentDescription = "Switch to emoji keyboard"
            // The alphabet switch rebuilt the model from scratch — re-seed
            // from the target field so predictions continue mid-sentence
            // (same machinery as the editing actions, RFC 0015 tier 2).
            reanchorEngineToTarget()
        } else {
            emojiReturnAlphabet = current
            imePrefs.edit().putString(ImePrefs.EMOJI_RETURN_ALPHABET, current).apply()
            eng.setAlphabet("Emoji")
            btn.text = "Abc"
            btn.contentDescription = "Switch back to text keyboard"
        }
    }

    // The docked root's parent is a FrameLayout inside the IME window's
    // decor. setLayoutParams performs NO type conversion — LinearLayout
    // .LayoutParams survives until the next measure pass casts them and
    // crashes (PostHog #43). This helper makes the wrong type impossible.
    //
    // Gravity.BOTTOM is load-bearing (#75): a no-gravity FrameLayout child
    // lands TOP|START in the decor — on edge-to-edge-enforced Android
    // (15/16) nothing re-docks it, so the "docked" keyboard rendered as a
    // ~250px strip at the TOP of the screen over the app. To users that
    // presented exactly as "the IME still starts in float mode" (Heide,
    // three consecutive reports, repro'd on the API 35 emulator).
    private fun setDockedHeight(heightPx: Int) {
        dockedRoot?.layoutParams = android.widget.FrameLayout.LayoutParams(
            MATCH_PARENT, heightPx, Gravity.BOTTOM
        )
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
        Log.d(TAG, "onCreateInputView (instance=${System.identityHashCode(this)}, floating=$floating)")
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

        // Top bar (shared look): Float toggle + emoji mode + Hide. Mode
        // controls cluster here so the editing strip below stays a pure
        // edit-action row (Heide feedback: too many buttons up top).
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8, density), dp(2, density), dp(8, density), dp(2, density))
        }
        val floatBtn = Button(this).apply { text = "Float" }
        // Emoji toggle (#61 option 2): GONE until createEngine() confirms
        // the data bundle ships the Emoji alphabet (DasherCore b00a4c8a+).
        val emojiBtn = Button(this).apply {
            text = "😀"
            contentDescription = "Switch to emoji keyboard"
            visibility = View.GONE
            setOnClickListener { toggleEmojiMode() }
        }
        emojiToggleBtn = emojiBtn
        val hideBtn = Button(this).apply {
            text = "Hide"
            setOnClickListener { requestHideSelf(0) }
        }
        top.addView(floatBtn)
        top.addView(emojiBtn)
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
        dockedRoot = root
        setDockedHeight(imeHeightPx())
        root.minimumHeight = imeHeightPx()

        floatDockBtn = floatBtn
        floatBtn.setOnClickListener { enterFloatingMode() }

        Handler(Looper.getMainLooper()).post {
            createEngine()
            // Self-heal a stale floating state BEFORE evaluating the saved
            // preference (#73). onCreateInputView can run again on a LIVE
            // instance (configuration change; some OEM IME-switch flows keep
            // the service alive) — `floating` is then still true from the
            // previous view generation while the overlay holds a dead canvas,
            // and the restore below would early-return inside
            // enterFloatingMode(): the keyboard presented as floating over a
            // full-height docked root. Heide (v0.1.21): "The IME still starts
            // in float mode, then you have to tap on dock. Then it's fine
            // unless you switch keyboards." Exit to a clean docked state
            // first (without touching the saved preference — that is decided
            // right after), then let the preference speak.
            // Capture the PRE-self-heal state (#75 review I-1): the stale
            // floating flag is the single most valuable signal for the next
            // report (#73's exact path) — reading it after the exit below
            // would always report false.
            val floatingBefore = floating
            if (floatingBefore) exitFloatingMode(persist = false, reason = "selfheal")
            // Restore the user's last mode. Only when the overlay permission
            // is already granted — without it enterFloatingMode would bounce
            // the user to system settings on every keyboard show (the
            // permission prompt belongs to an explicit Float tap).
            val alwaysDocked = imePrefs.getBoolean(ImePrefs.ALWAYS_DOCKED, false)
            val overlayPerm = android.provider.Settings.canDrawOverlays(this)
            val savedModeRaw = imePrefs.getString(ImePrefs.START_MODE, null)
            val restoreFloating = shouldRestoreFloating(
                savedMode = savedModeRaw,
                alwaysDocked = alwaysDocked,
                overlayPermissionGranted = overlayPerm,
            )
            val savedMode = savedModeRaw ?: "<unset>"
            Log.d(TAG, "mode restore: pref=$savedMode, " +
                "alwaysDocked=$alwaysDocked, floatingBefore=$floatingBefore, restoreFloating=$restoreFloating")
            // #75 diagnostics: three user reports could not be reproduced on
            // an emulator — capture the decision inputs so the next report
            // comes with the actual state machine. No-op unless opted in.
            AnalyticsService.capture("ime_mode_restore", mapOf(
                "saved_mode" to savedMode,
                "always_docked" to alwaysDocked,
                "floating_before" to floatingBefore,
                "restored_floating" to restoreFloating,
                "overlay_perm" to overlayPerm,
            ))
            if (restoreFloating) {
                enterFloatingMode(reason = "restore")
            }
        }
        return root
    }

    // ── Floating mode ──────────────────────────────────────────────────────

    private fun enterFloatingMode(reason: String = "button") {
        if (floating) return
        Log.d(TAG, "enterFloatingMode(reason=$reason)")
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
        saveStartMode(true)
        AnalyticsService.capture("ime_float_enter", mapOf("reason" to reason))
        floatDockBtn?.text = "Dock"
        floatDockBtn?.setOnClickListener { exitFloatingMode() }

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
        dockBtn.setOnClickListener { exitFloatingMode() }

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

    /**
     * @param persist also write "docked" to the saved start mode (default).
     *   Pass false when the exit is not the user's choice — self-healing a
     *   stale state (#73) or unbinding (#73): the saved mode stays what the
     *   user last chose deliberately.
     */
    private fun exitFloatingMode(persist: Boolean = true, reason: String = "button") {
        if (!floating) return
        Log.d(TAG, "exitFloatingMode(persist=$persist, reason=$reason, hadOverlay=${floatingView != null})")
        // The overlay window may already be gone (removeView failure on an
        // earlier exit — Samsung overlay quirks, cf. #46 — or a system
        // teardown). Never early-return on that (#73): the mode state and
        // the docked layout must be restored regardless, or a dead overlay
        // keeps presenting over every subsequent show.
        floatingView?.let { fv ->
            try { windowManager.removeView(fv) } catch (e: Exception) {
                Log.w(TAG, "Floating overlay removeView failed: ${e.message}")
            }
        }
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
        if (persist) saveStartMode(false)
        AnalyticsService.capture("ime_float_exit", mapOf(
            "persist" to persist, "reason" to reason,
        ))
        floatDockBtn?.text = "Float"
        floatDockBtn?.setOnClickListener { enterFloatingMode() }
        canvasView?.let { if (it.width > 0 && it.height > 0) engine?.onSurfaceSizeChanged(it.width, it.height) }
    }

    // ── Engine ─────────────────────────────────────────────────────────────

    private fun createEngine() {
        // Re-entry guard (#73): onCreateInputView can run again on a LIVE
        // instance (configuration change — rotation, fold, MuMu's resizable
        // window; some OEM IME-switch flows keep the service alive). The
        // engine is per-process by design ("created once per process",
        // #53/#56) — a second one would leak the first with BOTH running
        // dasher_frame loops into the same canvas. Reuse it.
        if (engine != null) {
            // #76: the recreated view tree built a FRESH loading overlay
            // (visible by default) — the hide at the end of this function
            // sits below the guard and never ran, so the keyboard came up
            // as a dead, overlay-covered panel ("a big float-like window
            // you can't type on"; repro'd via rotation on the API 33
            // emulator: zero colorful canvas pixels). Hide it now and
            // re-push the canvas geometry to the surviving engine.
            loadingOverlay?.visibility = View.GONE
            canvasView?.let { v ->
                if (v.width > 0 && v.height > 0) engine?.onSurfaceSizeChanged(v.width, v.height)
            }
            return
        }
        val dataDir = DataInstaller.ensureInstalled(this)
        // User state (settings, training) lives outside the data dir so a
        // data re-extraction can never wipe it.
        val userDir = DataInstaller.userDir(this).apply { mkdirs() }
        val eng = DasherEngine.create(dataDir, userDir.absolutePath) { commands, strings ->
            canvasView?.submitFrame(commands, strings)
            // All of this frame's engine deltas have now fired — apply them
            // to the target field as ONE atomic edit (bug #5: unbatched
            // deltas garble text in OneNote/Word's async editors).
            flushOutputToTarget()
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
        // Emoji toggle (#61): show the button only when the data bundle
        // actually ships the Emoji alphabet; if the engine restarted while
        // in emoji mode (alphabet persists in the engine's own settings),
        // restore the toggle state and the remembered return alphabet.
        val names = eng.getAlphabetNames()
        val emojiAvailable = names.contains("Emoji")
        emojiToggleBtn?.visibility = if (emojiAvailable) View.VISIBLE else View.GONE
        if (emojiAvailable && eng.getCurrentAlphabet() == "Emoji") {
            if (emojiReturnAlphabet == null) {
                emojiReturnAlphabet = imePrefs.getString(ImePrefs.EMOJI_RETURN_ALPHABET, null)
            }
            emojiToggleBtn?.text = "Abc"
            emojiToggleBtn?.contentDescription = "Switch back to text keyboard"
        }
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
        // Output deltas are COALESCED, not applied — see flushOutputToTarget
        // (bug #5: OneNote/Word jumbled unbatched per-delta edits).
        eng.setOutputListener { type, text ->
            if (type != 0 && type != 1 && type != 2) {
                Log.w(TAG, "Unknown engine output event type $type (text='${text.take(32)}')")
            }
            if (text.isNotEmpty()) {
                Log.d(TAG, "output event type=$type len=${text.length}")
            }
            outputCoalescer.onEngineOutput(type, text)
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

    /**
     * Applies the coalesced frame output to the target field as one atomic
     * batch edit (bug #5): first the deletion, then the insertion, inside a
     * beginBatchEdit/endBatchEdit pair so the editor cannot reformat between
     * the two halves.
     *
     * Deletion strategy: deleteSurroundingText first; if the editor reports
     * it did not handle it (WebView/custom editors — precisely the
     * OneNote/Word class), fall back to KEYCODE_DEL key events through the
     * target's own key pipeline (the same principle as Dasher-Windows #17's
     * deletion forwarding). DELs count CHARACTERS, not UTF-16 units
     * (delKeyEventCount), and are capped (delFallbackPlan): a bulk
     * control-mode delete must not flood the target with hundreds of key
     * events (jank/ANR). A skipped deletion re-anchors the engine from the
     * field (reanchorEngineToTarget) so the mismatch doesn't compound.
     *
     * No connection yet (field switch in flight): keep the deltas pending —
     * the next frame flushes them once a field is served. (Stranded deltas
     * are dropped on a fresh field start — onStartInput.)
     */
    private fun flushOutputToTarget() {
        val ic = currentInputConnection ?: return
        val flush = outputCoalescer.takeFlush() ?: return
        var skippedDeletion = false
        ic.beginBatchEdit()
        try {
            if (flush.deleteUnits > 0) {
                if (!ic.deleteSurroundingText(flush.deleteUnits, 0)) {
                    val dels = delKeyEventCount(
                        flush.deleteUnits,
                        ic.getTextBeforeCursor(flush.deleteUnits, 0)?.toString()
                    )
                    when (val plan = delFallbackPlan(dels, MAX_DEL_KEY_EVENTS)) {
                        is DelFallback.Send ->
                            repeat(plan.keyEvents) {
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                            }
                        is DelFallback.SkipOverCap ->
                            Log.w(TAG, "DEL fallback skipped: $plan — bulk delete over the " +
                                "${MAX_DEL_KEY_EVENTS}-event flood cap")
                        DelFallback.SkipNoText ->
                            Log.w(TAG, "DEL fallback skipped: editor returned no text before caret")
                    }
                    if (dels == null || dels > MAX_DEL_KEY_EVENTS) skippedDeletion = true
                }
            }
            if (flush.insert.isNotEmpty()) ic.commitText(flush.insert, 1)
        } finally {
            ic.endBatchEdit()
        }
        // Review M-1: a skipped deletion leaves engine and field desynced —
        // re-anchor from the field so the mismatch doesn't compound on every
        // later delete (seedBuffer re-reads the field and fires the type-2
        // clear, dropping any stale pending state).
        if (skippedDeletion) reanchorEngineToTarget()
    }

    // API 36+ (Android 16): measure the actual docked-root position in the
    // window so the insets animation has a concrete target (review C1/C3:
    // imeHeightPx() is a HEIGHT, not an offset — a constant created a dead
    // touch band above the keyboard and was wrong in floating mode).
    // Measuring handles both modes automatically: floating shrinks the
    // docked root to 40dp, and the measurement reflects that.
    // API 36+: force-show even if a hardware keyboard is attached — our users
    // (motor-impaired AAC) always want the soft keyboard (accessibility decision).
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = dockedRoot ?: return
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        outInsets.contentTopInsets = loc[1]
        outInsets.visibleTopInsets = loc[1]
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    /**
     * Bug #5b ("Dasher wouldn't pop up on Chrome… on the MuMu emulator, I had
     * to switch to Firefox"): while a hardware keyboard is attached (emulators
     * default to one), Chrome never asks the system to show the IME when a
     * field gains focus — Firefox asks regardless, which is why it worked
     * there. onEvaluateInputViewShown()=true only helps when something asks;
     * nobody asked. We serve motor-impaired AAC users who always want the
     * soft keyboard, so ask ourselves on every fresh focus of a real text
     * field (TYPE_NULL fields are non-editing views and stay untouched).
     *
     * Deliberate consequence (review M1): the Hide button dismisses the
     * keyboard only until the next field focus — on hardware-keyboard devices
     * nothing else would ever bring it back, and "keyboard follows focus" is
     * the accessibility stance this IME already takes. Hide still works
     * within a field (re-focuses of the same field arrive as restarting=true).
     */
    /**
     * The input binding is going away — an IME switch, or the served app
     * finished. Some OEMs keep the IME service alive across switches (#73):
     * the floating overlay must not keep hovering over whatever comes next
     * (another keyboard, the home screen). persist=false — the saved mode
     * stays the user's deliberate choice; it is re-evaluated on the next show.
     */
    override fun onUnbindInput() {
        super.onUnbindInput()
        Log.d(TAG, "onUnbindInput (floating=$floating)")
        if (floating) exitFloatingMode(persist = false, reason = "unbind")
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (!restarting) {
            // Fresh target field: any deltas still pending from the previous
            // field must NEVER land here (review I2 — for an AAC user, text
            // appearing in the wrong app's field is a trust failure).
            outputCoalescer.clear()
            if (info != null &&
                info.inputType != InputType.TYPE_NULL &&
                !isInputViewShown
            ) {
                requestShowSelf(0)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView(restarting=$restarting, floating=$floating)")
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
        Log.d(TAG, "onDestroy (floating=$floating)")
        if (floating) {
            // Event balance (#75 review M3): a destroy while floating ends
            // the float session without the unbind/button exit paths —
            // emit the exit so enter/exit correlation stays meaningful.
            AnalyticsService.capture("ime_float_exit", mapOf(
                "persist" to false, "reason" to "destroy",
            ))
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
        floatDockBtn = null
        emojiToggleBtn = null
        editingToolbar = null
        super.onDestroy()
    }

    // ── Utils ──────────────────────────────────────────────────────────────

    private fun dp(value: Int, density: Float) = (value * density).toInt()

    private companion object {
        const val TAG = "DasherImeService"
        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * Upper bound on KEYCODE_DEL fallback events per flush (review C2):
         * each is a key event the target must process — a bulk control-mode
         * delete beyond this cap is skipped with a log (engine/field desync
         * is recoverable; an ANR or an editor dropping half the flood is not).
         */
        const val MAX_DEL_KEY_EVENTS = 64
    }
}
