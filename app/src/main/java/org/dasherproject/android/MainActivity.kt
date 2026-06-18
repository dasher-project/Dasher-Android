package org.dasherproject.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    // Observable UI state. Updated on the main thread by the engine's frame loop
    // (output) and once after engine creation (alphabets/palettes/speed).
    private var outputText by mutableStateOf("")
    private var alphabets by mutableStateOf<List<String>>(emptyList())
    private var currentAlphabet by mutableStateOf("")
    private var palettes by mutableStateOf<List<String>>(emptyList())
    private var currentPalette by mutableStateOf("")
    private var speedPercent by mutableStateOf(100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            // Seed status-bar state from the engine.
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
                        onClear = { engine?.resetOutputText(); outputText = "" },
                        onCopyAll = { copyToClipboard(outputText) },
                        onAlphabetSelected = { name ->
                            engine?.setAlphabet(name)
                            currentAlphabet = name
                            engine?.saveSettings()
                        },
                        onPaletteSelected = { name ->
                            engine?.setPalette(name)
                            currentPalette = name
                            engine?.saveSettings()
                        },
                        onSpeedChanged = { pct ->
                            speedPercent = pct
                            engine?.setSpeedPercent(pct)
                            engine?.saveSettings()
                        }
                    )
                }
            }
        }
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
        onClear: () -> Unit,
        onCopyAll: () -> Unit,
        onAlphabetSelected: (String) -> Unit,
        onPaletteSelected: (String) -> Unit,
        onSpeedChanged: (Int) -> Unit
    ) {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                // Output panel + actions.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                    OutlinedButton(onClick = onCopyAll) { Text("Copy all") }
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

                // Dasher canvas.
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

                // Bottom status bar (DESIGN.md §Status Bar): alphabet, palette, speed.
                StatusBar(
                    alphabets = alphabets,
                    currentAlphabet = currentAlphabet,
                    onAlphabetSelected = onAlphabetSelected,
                    palettes = palettes,
                    currentPalette = currentPalette,
                    onPaletteSelected = onPaletteSelected,
                    speedPercent = speedPercent,
                    onSpeedChanged = onSpeedChanged
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
        onSpeedChanged: (Int) -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DropdownPicker(
                    label = "Alphabet",
                    options = alphabets,
                    selected = currentAlphabet.ifEmpty { "—" },
                    onSelect = onAlphabetSelected,
                    modifier = Modifier.weight(1f)
                )
                DropdownPicker(
                    label = "Palette",
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

    /**
     * Minimal dropdown picker. Uses a LazyColumn inside DropdownMenu so large lists
     * (e.g. the 622-alphabet set) scroll without materialising every row at once.
     */
    @Composable
    private fun DropdownPicker(
        label: String,
        options: List<String>,
        selected: String,
        onSelect: (String) -> Unit,
        modifier: Modifier = Modifier
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selected,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
    }

    override fun onPause() {
        super.onPause()
        engine?.stop()
    }

    override fun onDestroy() {
        engine?.destroy()
        engine = null
        canvasView = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
