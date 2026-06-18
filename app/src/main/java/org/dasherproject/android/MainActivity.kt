package org.dasherproject.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dasherproject.android.ui.DasherCanvasView
import org.dasherproject.android.ui.theme.DasherAndroidTheme

class MainActivity : ComponentActivity() {

    private var engine: DasherEngine? = null
    private var canvasView: DasherCanvasView? = null
    private var tiltProvider: TiltInputProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Observable UI state.
    private var outputText by mutableStateOf("")
    private var alphabets by mutableStateOf<List<String>>(emptyList())
    private var currentAlphabet by mutableStateOf("")
    private var palettes by mutableStateOf<List<String>>(emptyList())
    private var currentPalette by mutableStateOf("")
    private var speedPercent by mutableStateOf(100)
    private var inputMode by mutableStateOf(InputMode.TOUCH)
    private var tiltAvailable by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tilt provider forwards normalised coords to the engine on the main thread
        // (the C API context is single-threaded; sensor callbacks arrive elsewhere).
        tiltProvider = TiltInputProvider(this) { nx, ny ->
            mainHandler.post { engine?.onTiltNormalized(nx, ny) }
        }
        tiltAvailable = tiltProvider?.hasSensor() == true

        lifecycleScope.launch {
            val dataDir = withContext(Dispatchers.IO) {
                DataInstaller.ensureInstalled(this@MainActivity)
            }
            val eng = DasherEngine.create(dataDir, dataDir) { commands, strings ->
                canvasView?.submitFrame(commands, strings)
            }
            if (eng == null) {
                Log.e(TAG, "Dasher engine creation failed (dataDir=$dataDir)")
                return@launch
            }
            eng.onTextUpdate = { text -> outputText = text }
            engine = eng
            alphabets = eng.getAlphabetNames()
            currentAlphabet = eng.getCurrentAlphabet()
            palettes = eng.getPaletteNames()
            currentPalette = eng.getCurrentPalette()
            speedPercent = eng.getSpeedPercent()
            canvasView?.let { v ->
                if (v.width > 0 && v.height > 0) eng.onSurfaceSizeChanged(v.width, v.height)
            }
            eng.start()
        }

        setContent {
            DasherAndroidTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(
                        output = outputText,
                        alphabets = alphabets,
                        currentAlphabet = currentAlphabet,
                        palettes = palettes,
                        currentPalette = currentPalette,
                        speedPercent = speedPercent,
                        inputMode = inputMode,
                        tiltAvailable = tiltAvailable,
                        onClear = { engine?.resetOutputText(); outputText = "" },
                        onCopyAll = { copyToClipboard(outputText) },
                        onAlphabetSelected = { name ->
                            engine?.setAlphabet(name); currentAlphabet = name; engine?.saveSettings()
                        },
                        onPaletteSelected = { name ->
                            engine?.setPalette(name); currentPalette = name; engine?.saveSettings()
                        },
                        onSpeedChanged = { pct ->
                            speedPercent = pct; engine?.setSpeedPercent(pct); engine?.saveSettings()
                        },
                        onToggleMode = { toggleInputMode() },
                        onCalibrate = {
                            tiltProvider?.calibrate()
                            // After recalibration the user wants to resume zooming.
                            engine?.clearTiltInput()
                            engine?.setInputMode(InputMode.TILT)
                        },
                        onOpenSettings = { showSettings = true }
                    )
                    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
                }
            }
        }
    }

    @Composable
    private fun SettingsDialog(onDismiss: () -> Unit) {
        val eng = engine ?: run { onDismiss(); return }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Quick settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BoolToggle("Control mode (edit via Dasher nodes)", "BP_CONTROL_MODE")
                    BoolToggle("Auto speed control", "BP_AUTO_SPEEDCONTROL")
                    BoolToggle("Adaptive learning", "BP_LM_ADAPTIVE")
                    BoolToggle("Left-handed layout", "BP_ORIENT_L_R")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
        )
    }

    @Composable
    private fun BoolToggle(label: String, paramName: String) {
        val eng = engine ?: return
        var checked by remember { mutableStateOf(eng.getBoolParam(paramName)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = { v ->
                checked = v
                eng.setBoolParam(paramName, v)
            })
        }
    }

    private fun toggleInputMode() {
        val newMode = if (inputMode == InputMode.TOUCH) InputMode.TILT else InputMode.TOUCH
        when (newMode) {
            InputMode.TILT -> {
                engine?.setInputMode(InputMode.TILT)
                tiltProvider?.register()
            }
            InputMode.TOUCH -> {
                tiltProvider?.unregister()
                engine?.clearTiltInput()
                engine?.setInputMode(InputMode.TOUCH)
            }
        }
        inputMode = newMode
    }

    private fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dasher", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun AppScreen(
        output: String,
        alphabets: List<String>,
        currentAlphabet: String,
        palettes: List<String>,
        currentPalette: String,
        speedPercent: Int,
        inputMode: InputMode,
        tiltAvailable: Boolean,
        onClear: () -> Unit,
        onCopyAll: () -> Unit,
        onAlphabetSelected: (String) -> Unit,
        onPaletteSelected: (String) -> Unit,
        onSpeedChanged: (Int) -> Unit,
        onToggleMode: () -> Unit,
        onCalibrate: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        Scaffold { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                    OutlinedButton(onClick = onCopyAll) { Text("Copy all") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = output.ifEmpty { "Touch the canvas to start writing." },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        DasherCanvasView(ctx).also { view ->
                            canvasView = view
                            view.onSurfaceSizeChanged = { w, h -> engine?.onSurfaceSizeChanged(w, h) }
                            view.onTouchInput = { action, x, y -> engine?.onTouch(action, x, y) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                StatusBar(
                    alphabets = alphabets,
                    currentAlphabet = currentAlphabet,
                    onAlphabetSelected = onAlphabetSelected,
                    palettes = palettes,
                    currentPalette = currentPalette,
                    onPaletteSelected = onPaletteSelected,
                    speedPercent = speedPercent,
                    onSpeedChanged = onSpeedChanged,
                    inputMode = inputMode,
                    tiltAvailable = tiltAvailable,
                    onToggleMode = onToggleMode,
                    onCalibrate = onCalibrate
                )
            }
        }
    }

    @Composable
    private fun StatusBar(
        alphabets: List<String>,
        currentAlphabet: String,
        onAlphabetSelected: (String) -> Unit,
        palettes: List<String>,
        currentPalette: String,
        onPaletteSelected: (String) -> Unit,
        speedPercent: Int,
        onSpeedChanged: (Int) -> Unit,
        inputMode: InputMode,
        tiltAvailable: Boolean,
        onToggleMode: () -> Unit,
        onCalibrate: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DropdownPicker(
                    options = alphabets,
                    selected = currentAlphabet.ifEmpty { "—" },
                    onSelect = onAlphabetSelected,
                    modifier = Modifier.weight(1f)
                )
                DropdownPicker(
                    options = palettes,
                    selected = currentPalette.ifEmpty { "—" },
                    onSelect = onPaletteSelected,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (tiltAvailable) {
                    OutlinedButton(onClick = onToggleMode) {
                        Text(if (inputMode == InputMode.TILT) "Tilt" else "Touch")
                    }
                    if (inputMode == InputMode.TILT) {
                        OutlinedButton(onClick = onCalibrate) { Text("Calibrate") }
                    }
                }
                Text("Speed", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = speedPercent.toFloat(),
                    onValueChange = { onSpeedChanged(it.toInt()) },
                    valueRange = 20f..400f,
                    modifier = Modifier.weight(1f)
                )
                Text("$speedPercent%", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    /** LazyColumn-backed dropdown so large lists (622 alphabets) scroll cheaply. */
    @Composable
    private fun DropdownPicker(
        options: List<String>,
        selected: String,
        onSelect: (String) -> Unit,
        modifier: Modifier = Modifier
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(options) { opt ->
                        DropdownMenuItem(
                            text = { Text(opt, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { onSelect(opt); expanded = false }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        engine?.start()
        if (inputMode == InputMode.TILT) tiltProvider?.register()
    }

    override fun onPause() {
        super.onPause()
        tiltProvider?.unregister()
        engine?.clearTiltInput()
        engine?.stop()
    }

    override fun onDestroy() {
        tiltProvider?.unregister()
        tiltProvider = null
        engine?.destroy()
        engine = null
        canvasView = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
