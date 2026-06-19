package at.dasher.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

/**
 * Manifest-driven settings UI — mirrors DasherApple `DasherSettingsView` and
 * Dasher-Windows `SettingsPanel`: parameters are enumerated from the DasherCore
 * schema ([DasherEngine.allParameters]), bucketed by `group` into tabs, and each
 * row is rendered from its `type`/`uiType`. No per-parameter hand-coding.
 *
 * Contextual filtering (Input by active input filter, Language by active LM) and
 * the palette-swatch special are follow-ups; this first cut surfaces the full
 * schema in tabbed form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(engine: DasherEngine, onDismiss: () -> Unit) {
    var params by remember { mutableStateOf(engine.allParameters()) }
    // Bumped on every change so rows re-read fresh values from the engine.
    var version by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { version++ }
    // Re-fetch the schema (labels re-translate after a locale change).
    val reload: () -> Unit = { params = engine.allParameters(); version++ }

    val tabs = remember(params) {
        val order = listOf("Input", "Language", "Customization", "Output", "Game Mode")
        val present = params.map { it.group.ifEmpty { "Input" } }.distinct()
        // keep the canonical order, append any unexpected groups + a synthetic Privacy tab
        order.filter { it in present } + present.filter { it !in order } + listOf("Privacy")
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabGroup = tabs.getOrNull(selectedTab) ?: "Input"
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { engine.saveSettings(); onDismiss() }) {
                        Icon(Lucide.X, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                divider = {}
            ) {
                tabs.forEachIndexed { i, name ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = {
                            selectedTab = i
                            AnalyticsService.capture("settings_viewed", mapOf("tab_name" to name))
                        },
                        text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            val rows = remember(params, tabGroup) {
                params.filter { (it.group.ifEmpty { "Input" }) == tabGroup }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (tabGroup == "Privacy") {
                    item { PrivacyContent(ctx) }
                } else {
                    if (tabGroup == "Language") {
                        item { LocaleRow(engine, reload) }
                    }
                    items(rows, key = { it.key }) { p ->
                        ParameterRow(engine, p, version, bump)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyContent(context: android.content.Context) {
    var optedIn by remember { mutableStateOf(AnalyticsService.optedIn(context)) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Privacy-preserving analytics", style = MaterialTheme.typography.titleLarge)
        Text(
            "Anonymous usage analytics help understand how Dasher is used and fix crashes. " +
                "No typed text, clipboard contents, canvas contents, or personal information is ever collected. " +
                "Opt out any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Allow anonymous analytics", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = optedIn, onCheckedChange = { v ->
                AnalyticsService.setOptedIn(context, v); optedIn = v
            })
        }
        OutlinedButton(onClick = { AnalyticsService.resetId(context) }) {
            Text("Reset anonymous ID")
        }
    }
}

// Locale list matching DasherApple / Dasher-Windows (RFC 0003). Translations live
// in DasherCore/Strings/strings_*.json, bundled via the syncDasherStrings Gradle task.
private val DASHER_LOCALES = listOf(
    "en" to "English", "de" to "Deutsch", "es" to "Español", "fr" to "Français",
    "it" to "Italiano", "pt" to "Português (BR)", "pt-PT" to "Português (PT)",
    "zh-CN" to "中文", "ar" to "العربية"
)

@Composable
private fun LocaleRow(engine: DasherEngine, reload: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(engine.locale()) }
    val label = DASHER_LOCALES.firstOrNull { it.first == current }?.second ?: current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("App language", style = MaterialTheme.typography.bodyLarge)
            Text("Translates parameter names in this settings screen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DASHER_LOCALES.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            if (engine.setLocale(code)) { current = code; reload() }
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ParameterRow(engine: DasherEngine, p: ParameterInfo, version: Int, onChange: () -> Unit) {
    // `version` is read so the row recomposes and re-reads the engine after a change.
    @Suppress("UNUSED_EXPRESSION") version
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) {
                Text(p.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            p.isBool -> BoolControl(engine, p.key, onChange)
            p.isLong -> LongControl(engine, p, onChange)
            p.isString -> StringControl(engine, p, onChange)
        }
    }
}

@Composable
private fun BoolControl(engine: DasherEngine, key: Int, onChange: () -> Unit) {
    var checked by remember { mutableStateOf(engine.boolValue(key)) }
    Switch(checked = checked, onCheckedChange = { v -> checked = v; engine.setBoolValue(key, v); onChange() })
}

@Composable
private fun LongControl(engine: DasherEngine, p: ParameterInfo, onChange: () -> Unit) {
    val enums = remember(p.key) { engine.enumValues(p.key) }
    when {
        enums.isNotEmpty() -> EnumDropdown(engine, p.key, enums, onChange)
        p.max > p.min -> LongSlider(engine, p, onChange)
        else -> Text("${engine.longValue(p.key)}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LongSlider(engine: DasherEngine, p: ParameterInfo, onChange: () -> Unit) {
    var value by remember { mutableStateOf(engine.longValue(p.key).toFloat()) }
    Column(modifier = Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End) {
        Text("${value.toInt()}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { engine.setLongValue(p.key, value.toLong()); onChange() },
            valueRange = p.min.toFloat()..p.max.toFloat(),
            steps = if (p.step > 0) ((p.max - p.min) / p.step).coerceAtLeast(1).toInt() - 1 else 0
        )
    }
}

@Composable
private fun EnumDropdown(
    engine: DasherEngine, key: Int, options: List<Pair<String, Int>>, onChange: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(engine.longValue(key).toInt()) }
    val label = options.firstOrNull { it.second == current }?.first ?: "$current"
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { current = value; engine.setLongValue(key, value.toLong()); expanded = false; onChange() }
                )
            }
        }
    }
}

@Composable
private fun StringControl(engine: DasherEngine, p: ParameterInfo, onChange: () -> Unit) {
    // Curated Android font list for the canvas (Dasher) font param; engine stores "" for System.
    val isFont = p.name.contains("Font", ignoreCase = true)
    val options = if (isFont) {
        listOf("" to "System", "sans-serif" to "Sans Serif", "serif" to "Serif", "monospace" to "Monospace")
    } else {
        remember(p.key) { engine.stringValues(p.key) }.map { it to it }
    }
    if (options.isNotEmpty()) {
        StringDropdown(engine, p.key, options, onChange)
    } else {
        Text(engine.stringValue(p.key), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun StringDropdown(
    engine: DasherEngine, key: Int, options: List<Pair<String, String>>, onChange: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(engine.stringValue(key)) }
    val label = options.firstOrNull { it.first == current }?.second ?: current.ifEmpty { "Default" }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { current = value; engine.setStringValue(key, value); expanded = false; onChange() }
                )
            }
        }
    }
}
