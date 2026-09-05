package dev.alfieprojects.stablestill.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
                    ProbeScreen(
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermission = { requestCamera.launch(Manifest.permission.CAMERA) },
                    )
                }
            }
        }
    }
}
