package dev.alfieprojects.stablestill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.alfieprojects.stablestill.capture.BurstCaptureController
import dev.alfieprojects.stablestill.capture.CaptureOption
import dev.alfieprojects.stablestill.capture.SavedBurst
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 1 screen: save a burst, with its gyro trace, to somewhere `adb pull`
 * can reach.
 *
 * There is no viewfinder and nothing is merged. The handover calls this the
 * highest-value hour in the project for one reason: a burst on disk turns every
 * later alignment bug into a JVM test instead of a thing that only reproduces
 * on a phone in your hand. Showing a preview would be work spent on the part
 * that does not carry that value.
 */
@Composable
fun CaptureScreen(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { BurstCaptureController(context) }

    var option by remember { mutableStateOf(CaptureOption.FULL) }
    var depth by remember { mutableIntStateOf(8) }
    var capNanos by remember { mutableStateOf<Long?>(20_000_000L) }
    var running by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SavedBurst?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Polled rather than pushed: the counters live on the capture thread, and a
    // twice-a-second readout is enough to tell a running camera from a stalled
    // one without putting a state write in the frame callback.
    var buffered by remember { mutableIntStateOf(0) }
    var delivered by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        while (running) {
            buffered = controller.bufferedFrames
            delivered = controller.deliveredFrames
            delay(500)
        }
    }

    // The camera must not survive the screen. Holding it open in the background
    // keeps the ImageReader pool allocated and blocks every other camera app.
    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Save a burst", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Records frames and the gyro trace covering them, for replaying off " +
                "the phone. Hold the phone as you would to take a photo - hand " +
                "tremor is the signal here, not noise.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!hasCameraPermission) {
            Button(onClick = onRequestPermission) { Text("Grant camera access") }
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resolution", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureOption.ALL.forEach { o ->
                        Choice(o.label, selected = option == o, enabled = !running) { option = o }
                    }
                }
                Text("Stack depth", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 8, 12).forEach { d ->
                        Choice("$d", selected = depth == d, enabled = !running) { depth = d }
                    }
                }
                Text("Max exposure", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXPOSURE_CAPS.forEach { (label, nanos) ->
                        Choice(label, selected = capNanos == nanos, enabled = !running) {
                            capNanos = nanos
                        }
                    }
                }
                Text(
                    "Auto lets AE spend the whole frame period on one exposure - 50 ms " +
                        "at 20 fps here - and that blur is baked into the anchor.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val megabytes = option.frameBytes * (depth + 3) / (1024 * 1024)
                Text(
                    "$megabytes MB of image buffers while running; a burst spans " +
                        "${(depth - 1) * 1000 / option.fps} ms at ${option.fps} fps.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !saving,
                onClick = {
                    error = null
                    if (running) {
                        controller.stop()
                        running = false
                    } else {
                        scope.launch {
                            runCatching { controller.start(option, depth, capNanos) }
                                .onSuccess { running = true }
                                .onFailure { error = it.message ?: it.toString() }
                        }
                    }
                },
            ) {
                Text(if (running) "Stop camera" else "Start camera")
            }

            OutlinedButton(
                enabled = running && !saving && buffered >= 2,
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        runCatching { controller.saveBurst() }
                            .onSuccess { saved = it }
                            .onFailure { error = it.message ?: it.toString() }
                        saving = false
                    }
                },
            ) {
                Text("Save burst")
            }
        }

        if (running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Running", style = MaterialTheme.typography.titleSmall)
                    Field("Buffered frames", "$buffered / $depth")
                    Field("Delivered", "$delivered")
                    Field("Gyroscope", if (controller.hasGyroscope) "recording" else "absent")
                    Field("Exposure", "${controller.exposureNanos / 1_000_000} ms")
                    Field("ISO", "${controller.iso}")
                    if (buffered < 2) {
                        Text(
                            "Waiting for the buffer to fill.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (saving) {
            CircularProgressIndicator()
            Text("Writing frames to disk...", style = MaterialTheme.typography.bodySmall)
        }

        error?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Capture failed", style = MaterialTheme.typography.titleMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        saved?.let { s -> SavedBurstCard(s, controller.pullCommand(s)) }
    }
}

@Composable
private fun SavedBurstCard(s: SavedBurst, pullCommand: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Burst saved", style = MaterialTheme.typography.titleMedium)
            Field("Frames", "${s.frameCount}")
            Field("Gyro samples", "${s.gyroSampleCount}")
            Field("Size", "${s.bytesWritten / (1024 * 1024)} MB")
            Field("Frame span", "${s.frameSpanMillis} ms")
            Field("Gyro covers frames", if (s.gyroCoversFrames) "yes" else "NO")
            Field("Write time", "${s.elapsedMillis} ms")
            Text(
                s.directory.name,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Pull it with:", style = MaterialTheme.typography.bodySmall)
            Text(pullCommand, style = MaterialTheme.typography.bodySmall)
            s.notes.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Auto, plus the exposure the design assumes and half of it. */
private val EXPOSURE_CAPS: List<Pair<String, Long?>> = listOf(
    "Auto" to null,
    "20 ms" to 20_000_000L,
    "10 ms" to 10_000_000L,
)

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}
