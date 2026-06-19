package at.dasher.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import at.dasher.android.ui.DasherCanvasView

/**
 * Android system IME (keyboard) that lets the user write with Dasher into *any* app.
 *
 * This is the Android analog of the iOS keyboard extension (feature-matrix row
 * `ios-keyboard-extension`) and delivers `direct-text-injection` parity: the engine's
 * per-character output callback is forwarded to the host app's [android.view.inputmethod.InputConnection].
 *
 * Runs in low-memory mode (the C API's `dasher_set_low_memory_mode` — the engine loads only
 * the selected alphabet + the default input filter), since IME processes are memory-constrained.
 * Shares `dasher_settings.xml` + the bundled data with the main app (same `filesDir`).
 */
class DasherImeService : InputMethodService() {

    private var engine: DasherEngine? = null
    private var canvasView: DasherCanvasView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val imeHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F7F6.toInt())
        }

        // Minimal chrome: a Hide button (Dasher itself is driven from the canvas).
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
        }
        top.addView(Button(this).apply {
            text = "Hide"
            setOnClickListener { requestHideSelf(0) }
        })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val canvas = DasherCanvasView(this).apply {
            onSurfaceSizeChanged = { w, h -> engine?.onSurfaceSizeChanged(w, h) }
            onTouchInput = { action, x, y -> engine?.onTouch(action, x, y) }
        }
        canvasView = canvas
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imeHeight)
        root.minimumHeight = imeHeight

        createEngine()
        return root
    }

    private fun createEngine() {
        // The main app already ran DataInstaller (shared filesDir), so this is a fast
        // marker check. Create the engine synchronously — coroutine scheduling in an
        // InputMethodService context is unreliable.
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
        // Real-time output -> host app. type 0 = insert, 1 = delete (backspace run).
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
        canvasView?.let { v ->
            if (v.width > 0 && v.height > 0) eng.onSurfaceSizeChanged(v.width, v.height)
        }
        eng.start()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        engine?.start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine?.stop()
    }

    override fun onDestroy() {
        NativeBridge.onOutputListener = null
        NativeBridge.onMessageListener = null
        NativeBridge.onClipboardListener = null
        NativeBridge.onSpeakListener = null
        scope.cancel()
        engine?.destroy()
        engine = null
        canvasView = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DasherImeService"
    }
}
