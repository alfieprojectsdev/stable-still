package dev.alfieprojects.stablestill.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.alfieprojects.stablestill.capture.BurstCaptureController
import dev.alfieprojects.stablestill.pipeline.BurstReplayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Entry point.
 *
 * Opens on the device probe rather than on a viewfinder, and that ordering is
 * deliberate: until the probe says what the gyroscope and camera clock actually
 * do on this handset, a viewfinder would just be a confident-looking way to
 * produce wrong results.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val AUTO_REPLAY_TAG = "AutoReplay"
    }

    private var hasCameraPermission by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    /**
     * Stacks the newest saved burst and logs the result, then finishes.
     *
     * `adb shell am start -n dev.alfieprojects.stablestill/.ui.MainActivity \
     *     --ez autoReplay true`
     *
     * This exists because synthetic input is blocked on the target handset -
     * `input tap` returns cleanly and does nothing - so without it the GPU path
     * can only be triggered by a finger, and a stage that can only be run by
     * hand is a stage that stops being run.
     */
    private fun runAutoReplay() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    BurstReplayer(File(getExternalFilesDir(null), "captures"))
                        .replay(
                            BurstCaptureController(this@MainActivity).savedBursts().first(),
                            rejectSigma = (intent.getStringExtra("rejectSigma")?.toFloatOrNull() ?: 0.10f),
                        )
                }
            }
            result.onSuccess {
                Log.i(
                    AUTO_REPLAY_TAG,
                    "OK source=${it.sourceDirectory.name} merged=${it.framesMerged}/${it.framesTotal} " +
                        "anchor=${it.anchorIndex} out=${it.outputWidth}x${it.outputHeight} " +
                        "shift=${"%.1f".format(it.maxCornerShiftPx)}px sigma=${(intent.getStringExtra("rejectSigma")?.toFloatOrNull() ?: 0.10f)} ms=${it.elapsedMillis} " +
                        "file=${it.output.absolutePath}",
                )
            }.onFailure { Log.e(AUTO_REPLAY_TAG, "FAILED: ${it.stackTraceToString()}") }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.getBooleanExtra("autoReplay", false) == true) {
            runAutoReplay()
            return
        }

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            MaterialTheme {
                Surface {
                    // The probe stays the landing screen. Capture is a second
                    // tab rather than a replacement, because a burst saved
                    // against unknown hardware is a burst you cannot interpret.
                    var showCapture by remember { mutableStateOf(false) }
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { showCapture = false }) { Text("Probe") }
                            TextButton(onClick = { showCapture = true }) { Text("Capture") }
                        }
                        val onRequest = { requestCamera.launch(Manifest.permission.CAMERA) }
                        if (showCapture) {
                            CaptureScreen(hasCameraPermission, onRequest)
                        } else {
                            ProbeScreen(hasCameraPermission, onRequest)
                        }
                    }
                }
            }
        }
    }
}
