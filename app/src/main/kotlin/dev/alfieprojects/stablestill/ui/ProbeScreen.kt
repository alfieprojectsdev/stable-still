package dev.alfieprojects.stablestill.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.alfieprojects.stablestill.probe.DeviceProbe
import dev.alfieprojects.stablestill.probe.DeviceProbeReport
import dev.alfieprojects.stablestill.probe.GyroGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 0 screen: interrogate the hardware and say plainly what it can do.
 *
 * The output is the input to every later decision - stack depth, whether the
 * gyro path is enabled at all, whether a sync calibration step is needed - so it
 * is also exportable as JSON for keeping alongside sample shots.
 */
@Composable
fun ProbeScreen(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DeviceProbeReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("stable-still", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Phase 0 - hardware probe. Rest the phone on a flat surface before " +
                "running, so the gyroscope noise floor is measured at rest.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!hasCameraPermission) {
            Button(onClick = onRequestPermission) {
                Text("Grant camera access")
            }
            Text(
                "Camera characteristics cannot be read without permission.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.Default) { DeviceProbe(context).run() }
                        }.onSuccess { report = it }
                            .onFailure { error = it.message ?: it.toString() }
                        running = false
                    }
                },
            ) {
                Text(if (report == null) "Run probe" else "Run again")
            }

            report?.let { r ->
                OutlinedButton(onClick = { shareReport(context, r) }) {
                    Text("Export JSON")
                }
            }
        }

        if (running) {
            CircularProgressIndicator()
            Text("Measuring gyroscope for 1.5 s...")
        }

        error?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Probe failed", style = MaterialTheme.typography.titleMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        report?.let { r -> ReportBody(r) }
    }
}

@Composable
private fun ReportBody(r: DeviceProbeReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Verdict", style = MaterialTheme.typography.titleMedium)
            Text(r.verdict(), style = MaterialTheme.typography.bodyMedium)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            Field("Model", "${r.model} (${r.device})")
            Field("Android", "${r.androidRelease} / API ${r.sdkInt}")
            Field("RAM", "${r.totalRamMb} MB")
            Field("OpenGL ES", r.glEsVersion)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Gyroscope", style = MaterialTheme.typography.titleMedium)
            Field("Grade", r.gyro.grade.name)
            if (r.gyro.present) {
                Field("Name", "${r.gyro.name} (${r.gyro.vendor})")
                Field("Measured rate", "%.1f Hz".format(r.gyro.measuredRateHz))
                Field("Interval jitter", "%.1f%%".format(r.gyro.intervalJitterFraction * 100))
                Field("Rest noise", "%.5f rad/s".format(r.gyro.restNoiseRadPerSec))
                Field("Zero-rate offset", "%.5f rad/s".format(r.gyro.restBiasRadPerSec))
                Field("Resolution", "%.6f rad/s".format(r.gyro.resolutionRadPerSec))
                Field("Samples", "${r.gyro.sampleCount}")
            }
            if (r.gyro.grade == GyroGrade.ABSENT) {
                Text(
                    "No gyroscope. The gyro-warp path is disabled; alignment will " +
                        "have to be image-based.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            r.gyro.notes.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
        }
    }

    r.cameras.forEach { cam ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Camera ${cam.cameraId}", style = MaterialTheme.typography.titleMedium)
                Field("Hardware level", cam.hardwareLevel)
                Field("Timestamp source", cam.timestampSource)
                Field("Sensor orientation", "${cam.sensorOrientationDegrees}°")
                Field("Focal lengths", cam.focalLengthsMm.joinToString { "%.2f mm".format(it) })
                Field(
                    "Physical size",
                    cam.physicalSizeMm?.let { "%.2f x %.2f mm".format(it.first, it.second) } ?: "-",
                )
                Field("OIS", if (cam.hasOpticalStabilisation) "yes" else "no")
                Field("Manual sensor", if (cam.supportsManualSensor) "yes" else "no")
                Field("RAW", if (cam.supportsRaw) "yes" else "no")
                Field("Reports skew", if (cam.reportsRollingShutterSkew) "yes" else "no")

                Text("Top YUV streams", style = MaterialTheme.typography.titleSmall)
                cam.yuvSizes.take(6).forEach { s ->
                    val depth = r.recommendedStackDepth(s)
                    Text(
                        "${s.width}x${s.height} (%.1f MP) up to ${s.maxFps} fps - stack up to $depth"
                            .format(s.megapixels),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun shareReport(context: Context, report: DeviceProbeReport) {
    val dir = File(context.getExternalFilesDir(null), "probe").apply { mkdirs() }
    val file = File(dir, "probe-${report.model.replace(' ', '-')}.json")
    file.writeText(report.toJson().toString(2))

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export probe report"))
}
