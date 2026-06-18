package org.dasherproject.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // Observable from Compose; updated on the main thread by the engine's frame loop.
    private var outputText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Engine creation: extract data off-thread, then create + wire on the main thread.
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
            // If the canvas laid out before the engine was ready, push its size now.
            canvasView?.let { v ->
                if (v.width > 0 && v.height > 0) eng.onSurfaceSizeChanged(v.width, v.height)
            }
            eng.start()
        }

        setContent {
            DasherAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen(
                        output = outputText,
                        onClear = {
                            engine?.resetOutputText()
                            outputText = ""
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun AppScreen(output: String, onClear: () -> Unit) {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Output text panel.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(8.dp)
                ) {
                    Text(
                        text = output.ifEmpty { "Touch the canvas to start writing." },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(4.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                // Dasher canvas — fills remaining vertical space.
                AndroidView(
                    factory = { ctx ->
                        DasherCanvasView(ctx).also { view ->
                            canvasView = view
                            view.onSurfaceSizeChanged = { w, h ->
                                engine?.onSurfaceSizeChanged(w, h)
                            }
                            view.onTouchInput = { action, x, y ->
                                engine?.onTouch(action, x, y)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                // Bottom bar (Phase 0: clear only; settings/speed arrive in later phases).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
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
