package at.dasher.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.composables.icons.lucide.ClipboardCopy
import com.composables.icons.lucide.Crosshair
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Gamepad2
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Smartphone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import at.dasher.android.ui.DasherCanvasView
import at.dasher.android.ui.theme.DasherAndroidTheme

class MainActivity : ComponentActivity() {

    private var engine: DasherEngine? = null
    private var canvasView: DasherCanvasView? = null
    private var tiltProvider: TiltInputProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    // Observable UI state.
    private var outputText by mutableStateOf("")
    /** Text loaded via "Open" — preserved as a prefix the engine appends to (Dasher writes forward). */
    private var loadedPrefix by mutableStateOf("")
    /** Combined display text = loaded prefix + engine output since the last reset/open. */
    private val fullText: String get() = loadedPrefix + outputText
    // Output-pane font (persisted locally; the canvas glyph font is SP_DASHER_FONT in the engine).
    private var outputFontFamily by mutableStateOf("")
    private var outputFontSize by mutableStateOf(16f)
    private var alphabets by mutableStateOf<List<String>>(emptyList())
    private var currentAlphabet by mutableStateOf("")
    private var palettes by mutableStateOf<List<String>>(emptyList())
    private var currentPalette by mutableStateOf("")
    private var speedPercent by mutableStateOf(100)
    private var inputMode by mutableStateOf(InputMode.TOUCH)
    private var tiltAvailable by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var isPlaying by mutableStateOf(true)
    private var gameMode by mutableStateOf(false)
    private var gameState by mutableStateOf<GameState?>(null)

    // Parameter keys resolved once the native lib is loaded (for the param-change listener).
    private var dasherFontKey = -1
    private var speedKey = -1
    private var alphabetKey = -1
    private var autoSpeedKey = -1
    private var learningKey = -1
    // Bottom-bar toggle states
    private var autoSpeed by mutableStateOf(false)
    private var learning by mutableStateOf(false)

    // SAF launcher: writes the output text to a user-chosen file (DESIGN.md §Toolbar "Save").
    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) saveOutputTo(uri)
        }

    // SAF launcher: opens a .txt and loads it as the output prefix (DESIGN.md §Toolbar "Open").
    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openOutputFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsService.init(this)
        AnalyticsService.capture("app_launched", mapOf("locale" to java.util.Locale.getDefault().toLanguageTag()))
        loadOutputFontPrefs()

        // Tilt provider forwards normalised coords to the engine on the main thread
        // (the C API context is single-threaded; sensor callbacks arrive elsewhere).
        tiltProvider = TiltInputProvider(this) { nx, ny ->
            mainHandler.post { engine?.onTiltNormalized(nx, ny) }
        }
        tiltAvailable = tiltProvider?.hasSensor() == true

        // Android TTS for the engine's speak callback (speak-on-stop, speak control nodes).
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) tts = null
        }

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
            eng.onGameUpdate = { gameState = it }
            engine = eng
            dasherFontKey = NativeBridge.nativeFindParameterKey("SP_DASHER_FONT")
            speedKey = NativeBridge.nativeFindParameterKey("LP_MAX_BITRATE")
            alphabetKey = NativeBridge.nativeFindParameterKey("SP_ALPHABET_ID")
            autoSpeedKey = NativeBridge.nativeFindParameterKey("BP_AUTO_SPEEDCONTROL")
            learningKey = NativeBridge.nativeFindParameterKey("BP_LM_ADAPTIVE")
            // Realize the engine (via setScreenSize) BEFORE querying its state,
            // so alphabet/palette/speed return proper values.
            canvasView?.let { v ->
                if (v.width > 0 && v.height > 0) eng.onSurfaceSizeChanged(v.width, v.height)
            }
            alphabets = eng.getAlphabetNames()
            currentAlphabet = eng.getCurrentAlphabet()
            palettes = eng.getPaletteNames()
            currentPalette = eng.getCurrentPalette()
            speedPercent = eng.getSpeedPercent()
            autoSpeed = if (autoSpeedKey >= 0) eng.boolValue(autoSpeedKey) else false
            learning = if (learningKey >= 0) eng.boolValue(learningKey) else false
            // Set default game text from the bundled DasherCore gamemode file.
            val gameTextKey = NativeBridge.nativeFindParameterKey("SP_GAME_TEXT_FILE")
            if (gameTextKey >= 0 && eng.stringValue(gameTextKey).isNullOrEmpty()) {
                eng.setStringValue(gameTextKey, "training/gamemode_en_Latn.txt")
                eng.saveSettings()
            }
            eng.start()
            // Engine→frontend callbacks (clipboard copy, speak, messages).
            eng.installEngineCallbacks()
            eng.installParameterCallback() // two-way sync (settings <-> toolbar/canvas)
            eng.installLogCallback() // engine diagnostics → logcat
            // RFC 0007: push the OS dark-mode state into the engine so a SYSTEM
            // appearance-mode user gets the right (derived) palette. Activity is
            // recreated on uiMode change (not in configChanges), so this also covers
            // the user toggling system dark mode at runtime.
            val nightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            eng.setSystemAppearance(nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            applyCanvasFont(eng)
            installAppListeners()
        }

        setContent {
            DasherAndroidTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(
                        output = fullText,
                        alphabets = alphabets,
                        currentAlphabet = currentAlphabet,
                        speedPercent = speedPercent,
                        autoSpeed = autoSpeed,
                        isPlaying = isPlaying,
                        onClear = { engine?.resetOutputText(); outputText = ""; loadedPrefix = "" },
                        onCopyAll = { copyToClipboard(fullText) },
                        onTogglePlay = {
                            val eng = engine ?: return@AppScreen
                            if (isPlaying) { eng.stop(); isPlaying = false }
                            else { eng.start(); isPlaying = true }
                        },
                        onAlphabetSelected = { name ->
                            engine?.setAlphabet(name); currentAlphabet = name; engine?.saveSettings()
                            AnalyticsService.capture("alphabet_selected", mapOf("alphabet_id" to name))
                        },
                        onSpeedChanged = { pct ->
                            speedPercent = pct; engine?.setSpeedPercent(pct); engine?.saveSettings()
                        },
                        onAutoSpeedChanged = { v ->
                            autoSpeed = v
                            if (autoSpeedKey >= 0) { engine?.setBoolValue(autoSpeedKey, v); engine?.saveSettings() }
                        },
                        onOpenSettings = { showSettings = true },
                        onOpen = { openOutput() },
                        onSave = { saveOutput() },
                        onShare = { shareOutput() },
                        outputFontFamily = outputFontFamily,
                        outputFontSize = outputFontSize,
                        gameMode = gameMode,
                        gameState = gameState,
                        onToggleGame = { toggleGame() }
                    )
                    if (showSettings) SettingsScreen(
                        engine = engine ?: return@Surface,
                        onDismiss = { showSettings = false },
                        outputFontFamily = outputFontFamily,
                        outputFontSize = outputFontSize,
                        onOutputFontChanged = { family, size ->
                            outputFontFamily = family; outputFontSize = size; saveOutputFontPrefs()
                        }
                    )
                }
            }
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
        AnalyticsService.capture("input_method_changed",
            mapOf("method" to if (newMode == InputMode.TILT) "tilt" else "touch"))
    }

    private fun toggleGame() {
        val eng = engine ?: return
        if (gameMode) {
            eng.leaveGameMode()
            eng.setGameCanvasText(true)
            gameMode = false
            gameState = null
        } else {
            if (eng.enterGameMode()) {
                eng.setGameCanvasText(false) // platform renders its own target bar
                gameMode = true
            } else {
                Toast.makeText(this, "No game text available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dasher", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun speak(text: String, interrupt: Boolean) {
        val t = tts ?: return
        if (interrupt) t.stop()
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "dasher_${System.nanoTime()}")
    }

    /**
     * Points the engine-callback listeners at THIS activity. Called after engine
     * creation and from [onResume], so the main app re-owns the callbacks after
     * the IME (which installs its own) is dismissed.
     */
    private fun installAppListeners() {
        NativeBridge.onOutputListener = null // main app uses polled onTextUpdate, not per-char output
        NativeBridge.onClipboardListener = { text -> copyToClipboard(text) }
        NativeBridge.onSpeakListener = { text, interrupt -> speak(text, interrupt) }
        NativeBridge.onMessageListener = { _, text ->
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
        NativeBridge.onParameterChangedListener = { key ->
            engine?.let {
                if (key == dasherFontKey) applyCanvasFont(it)
                if (key == speedKey) speedPercent = it.getSpeedPercent()
                if (key == alphabetKey) currentAlphabet = it.getCurrentAlphabet()
                if (key == autoSpeedKey) autoSpeed = it.boolValue(autoSpeedKey)
                if (key == learningKey) learning = it.boolValue(learningKey)
            }
        }
        NativeBridge.onLogListener = { level, text ->
            when (level) {
                0 -> Log.d("DasherCore", text)
                1 -> Log.i("DasherCore", text)
                2 -> Log.w("DasherCore", text)
                else -> Log.e("DasherCore", text)
            }
        }
    }

    private fun applyCanvasFont(eng: DasherEngine) {
        canvasView?.glyphFontName = eng.stringValue(dasherFontKey)
    }

    private fun saveOutput() {
        if (fullText.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        saveLauncher.launch(getString(R.string.save_default_name))
    }

    private fun saveOutputTo(uri: android.net.Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(fullText.toByteArray()) }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOutput() {
        openLauncher.launch(arrayOf("text/plain"))
    }

    private fun openOutputFrom(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            // Load as a prefix the engine appends to. Dasher has no CAPI to seed the edit
            // buffer (only get/reset output), so the loaded text is kept on the frontend and
            // combined with the engine's append-since-reset output for display, save, copy.
            loadedPrefix = text
            engine?.resetOutputText()
            outputText = ""
            Toast.makeText(this, "Loaded ${text.length} chars", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Open failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareOutput() {
        if (fullText.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, fullText)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share text"))
    }

    private fun loadOutputFontPrefs() {
        val prefs = getSharedPreferences("dasher_output", android.content.Context.MODE_PRIVATE)
        outputFontFamily = prefs.getString("font_family", "") ?: ""
        outputFontSize = prefs.getFloat("font_size", 16f)
    }

    private fun saveOutputFontPrefs() {
        getSharedPreferences("dasher_output", android.content.Context.MODE_PRIVATE).edit().apply {
            putString("font_family", outputFontFamily)
            putFloat("font_size", outputFontSize)
            apply()
        }
    }


    private fun outputFontFamilyFor(name: String): FontFamily = when (name) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        "sans-serif" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }

    @Composable
    private fun AppScreen(
        output: String,
        alphabets: List<String>,
        currentAlphabet: String,
        speedPercent: Int,
        autoSpeed: Boolean,
        isPlaying: Boolean,
        onClear: () -> Unit,
        onCopyAll: () -> Unit,
        onTogglePlay: () -> Unit,
        onAlphabetSelected: (String) -> Unit,
        onSpeedChanged: (Int) -> Unit,
        onAutoSpeedChanged: (Boolean) -> Unit,
        onOpenSettings: () -> Unit,
        onOpen: () -> Unit,
        onSave: () -> Unit,
        onShare: () -> Unit,
        outputFontFamily: String,
        outputFontSize: Float,
        gameMode: Boolean,
        gameState: GameState?,
        onToggleGame: () -> Unit
    ) {
        Scaffold { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TopBar(isPlaying = isPlaying, onClear = onClear, onTogglePlay = onTogglePlay,
                    onCopyAll = onCopyAll, onOpen = onOpen, onSave = onSave, onShare = onShare,
                    gameMode = gameMode, onToggleGame = onToggleGame,
                    onOpenSettings = onOpenSettings)
                if (gameMode && gameState != null) GameTargetBar(gameState!!)
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = output.ifEmpty { "Touch the canvas to start writing." },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = outputFontFamilyFor(outputFontFamily),
                        fontSize = outputFontSize.sp
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
                    speedPercent = speedPercent,
                    onSpeedChanged = onSpeedChanged,
                    autoSpeed = autoSpeed,
                    onAutoSpeedChanged = onAutoSpeedChanged
                )
            }
        }
    }

    @Composable
    private fun TopBar(
        isPlaying: Boolean,
        onClear: () -> Unit,
        onTogglePlay: () -> Unit,
        onCopyAll: () -> Unit,
        onOpen: () -> Unit,
        onSave: () -> Unit,
        onShare: () -> Unit,
        gameMode: Boolean,
        onToggleGame: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        // DESIGN.md §Top Toolbar — Lucide icons (RFC 0002). Mirrors Apple/Windows order.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ToolbarButton(Lucide.FilePlus, "New / clear output", onClear)
                ToolbarButton(Lucide.FolderOpen, "Open file", onOpen)
                ToolbarButton(if (isPlaying) Lucide.Pause else Lucide.Play,
                    if (isPlaying) "Pause" else "Play", onTogglePlay)
                ToolbarButton(Lucide.ClipboardCopy, "Copy all", onCopyAll)
                ToolbarButton(Lucide.Save, "Save to file", onSave)
                ToolbarButton(Lucide.Share2, "Share", onShare)
                Spacer(Modifier.weight(1f))
                ToolbarButton(Lucide.Gamepad2, if (gameMode) "Leave game mode" else "Game mode",
                    onToggleGame)
                ToolbarButton(Lucide.Settings, "Settings", onOpenSettings)
            }
        }
    }

    @Composable
    private fun ToolbarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
        }
    }

    /** Game-mode target bar: correct (green) / wrong (red) / remaining (grey) + progress. */
    @Composable
    private fun GameTargetBar(state: GameState) {
        val target = state.target
        val correct = state.correct.coerceAtLeast(0)
        val wrongLen = state.wrong.length
        val green = Color(0xFF1F6B46)
        val red = Color(0xFFB00020)
        val grey = MaterialTheme.colorScheme.onSurfaceVariant
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = buildAnnotatedString {
                    if (target.isNotEmpty()) {
                        val cEnd = minOf(correct, target.length)
                        val wEnd = minOf(cEnd + wrongLen, target.length)
                        if (cEnd > 0) withStyle(SpanStyle(color = green, fontWeight = FontWeight.Bold)) {
                            append(target.substring(0, cEnd))
                        }
                        if (wEnd > cEnd) withStyle(SpanStyle(color = red, fontWeight = FontWeight.Bold)) {
                            append(target.substring(cEnd, wEnd))
                        }
                        if (target.length > wEnd) withStyle(SpanStyle(color = grey)) {
                            append(target.substring(wEnd))
                        }
                    }
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            )
            Text(
                "${state.correct}/${state.targetLength}",
                style = MaterialTheme.typography.labelMedium,
                color = grey
            )
        }
    }

    @Composable
    private fun StatusBar(
        alphabets: List<String>,
        currentAlphabet: String,
        onAlphabetSelected: (String) -> Unit,
        speedPercent: Int,
        onSpeedChanged: (Int) -> Unit,
        autoSpeed: Boolean,
        onAutoSpeedChanged: (Boolean) -> Unit
    ) {
        // Windows-style single-row bottom bar: alphabet picker | speed stepper | auto toggle.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DropdownPicker(
                    leadingIcon = Lucide.Languages,
                    options = alphabets,
                    selected = currentAlphabet.ifEmpty { "English" },
                    onSelect = onAlphabetSelected,
                    modifier = Modifier.weight(1f)
                )
                // Speed stepper (− value +) — matches Dasher-Windows.
                Text("Speed", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = {
                    onSpeedChanged((speedPercent - 10).coerceIn(20, 400))
                }, modifier = Modifier.size(36.dp)) {
                    Text("−", style = MaterialTheme.typography.titleMedium)
                }
                Text("$speedPercent%", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = {
                    onSpeedChanged((speedPercent + 10).coerceIn(20, 400))
                }, modifier = Modifier.size(36.dp)) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
                // Auto speed toggle.
                Text("Auto", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = autoSpeed, onCheckedChange = onAutoSpeedChanged)
            }
        }
    }

    /** Scrollable dropdown so large lists (622 alphabets) work without LazyColumn
     *  (LazyColumn inside DropdownMenu crashes on some Compose versions). */
    @Composable
    private fun DropdownPicker(
        options: List<String>,
        selected: String,
        onSelect: (String) -> Unit,
        modifier: Modifier = Modifier,
        leadingIcon: ImageVector? = null
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { opt ->
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
        installAppListeners() // re-own callbacks after the IME released them
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
        NativeBridge.onClipboardListener = null
        NativeBridge.onSpeakListener = null
        NativeBridge.onMessageListener = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        engine?.destroy()
        engine = null
        canvasView = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
