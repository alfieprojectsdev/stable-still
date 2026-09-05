package dev.alfieprojects.stablestill.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

/**
 * Entry point.
 *
 * Opens on the device probe rather than on a viewfinder, and that ordering is
 * deliberate: until the probe says what the gyroscope and camera clock actually
 * do on this handset, a viewfinder would just be a confident-looking way to
 * produce wrong results.
 */
class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
