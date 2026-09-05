package dev.alfieprojects.stablestill.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import dev.alfieprojects.stablestill.core.CameraIntrinsics
import dev.alfieprojects.stablestill.core.FrameMeta
import dev.alfieprojects.stablestill.core.RigAlignment
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Continuous Camera2 capture into a ring buffer, with per-frame timing metadata.
 *
 * Deliberately not CameraX. CameraX's ImageCapture hides exactly what this
 * pipeline needs: the per-frame `SENSOR_TIMESTAMP`, exposure duration, and
 * rolling-shutter skew that let a gyro trace be matched to a pixel grid.
 *
 * Images and capture results arrive on different callbacks with no ordering
 * guarantee, so both are parked in maps keyed by sensor timestamp and paired as
 * the second half shows up.
 */
class CaptureEngine(
    private val context: Context,
    private val ringCapacity: Int,
) {
    companion object {
        private const val TAG = "CaptureEngine"

        /** Unmatched halves older than this are assumed lost and released. */
        private const val ORPHAN_TIMEOUT_NANOS = 1_500_000_000L
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val ringBuffer = FrameRingBuffer(ringCapacity)

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val pendingImages = ConcurrentHashMap<Long, android.media.Image>()
    private val pendingResults = ConcurrentHashMap<Long, FrameMeta>()

    var cameraId: String? = null
        private set
    var intrinsics: CameraIntrinsics? = null
        private set
    var rig: RigAlignment? = null
        private set

    /** Frames delivered since [start], for a live "is this actually running" readout. */
    @Volatile
    var deliveredFrames: Long = 0L
        private set

    @SuppressLint("MissingPermission")
    suspend fun start(preview: Surface?, requestedSize: Size) {
        val id = selectBackCamera() ?: error("No back-facing camera available")
        cameraId = id
        val characteristics = cameraManager.getCameraCharacteristics(id)

        val t = HandlerThread("capture-engine").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val reader = ImageReader.newInstance(
            requestedSize.width,
            requestedSize.height,
            ImageFormat.YUV_420_888,
            // Headroom above the ring capacity. Without spare slots the reader
            // runs dry the instant a burst is taken and the preview freezes.
            ringCapacity + 3,
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            pendingImages[image.timestamp] = image
            tryPair(image.timestamp)
            reapOrphans(image.timestamp)
        }, h)

        intrinsics = buildIntrinsics(characteristics, requestedSize)
        rig = RigAlignment(
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            frontFacing = false,
        )

        cameraDevice = openCamera(id, h)
        val targets = listOfNotNull(preview, reader.surface)
        session = createSession(cameraDevice!!, targets, h)
        startRepeating(preview, reader.surface)
    }

    private fun startRepeating(preview: Surface?, readerSurface: Surface) {
        val device = cameraDevice ?: return
        val s = session ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            preview?.let { addTarget(it) }
            addTarget(readerSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // Any in-camera denoise or edge enhancement fights the stack: it
            // invents detail per frame that then fails to average away.
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        s.setRepeatingRequest(builder.build(), captureCallback, handler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val reader = imageReader ?: return
            pendingResults[timestamp] = FrameMeta(
                index = 0, // assigned when the burst is taken
                sensorTimestampNanos = timestamp,
                exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                // Absent on many LIMITED devices. Zero is a defensible default:
                // it degrades per-row correction, it does not break alignment.
                rollingShutterSkewNanos =
                    result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
                width = reader.width,
                height = reader.height,
            )
            tryPair(timestamp)
        }
    }

    private fun tryPair(timestamp: Long) {
        val image = pendingImages[timestamp] ?: return
        val meta = pendingResults[timestamp] ?: return
        pendingImages.remove(timestamp)
        pendingResults.remove(timestamp)
        deliveredFrames++
        ringBuffer.add(CapturedFrame(image, meta))
    }

    /**
     * Releases halves that will never find their partner.
     *
     * A dropped result leaves an Image pinned forever, and a handful of those is
     * enough to exhaust the reader pool and stall capture with no visible error.
     */
    private fun reapOrphans(nowNanos: Long) {
        pendingImages.entries.removeAll { (ts, image) ->
            (nowNanos - ts > ORPHAN_TIMEOUT_NANOS).also { stale ->
                if (stale) {
                    Log.w(TAG, "Releasing image with no capture result at $ts")
                    runCatching { image.close() }
                }
            }
        }
        pendingResults.entries.removeAll { (ts, _) -> nowNanos - ts > ORPHAN_TIMEOUT_NANOS }
    }

    /** Takes the burst around [shutterNanos]; the caller owns and must close the frames. */
    fun takeBurst(count: Int, shutterNanos: Long): List<CapturedFrame> =
        ringBuffer.takeBurst(count, shutterNanos)

    fun stop() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        ringBuffer.clear()
        pendingImages.values.forEach { runCatching { it.close() } }
        pendingImages.clear()
        pendingResults.clear()
        runCatching { imageReader?.close() }
        imageReader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------------ helpers

    private fun selectBackCamera(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private fun buildIntrinsics(
        c: CameraCharacteristics,
        size: Size,
    ): CameraIntrinsics {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return if (focal != null && physical != null && focal > 0f) {
            CameraIntrinsics.fromPhysical(
                focalLengthMm = focal.toDouble(),
                sensorWidthMm = physical.width.toDouble(),
                sensorHeightMm = physical.height.toDouble(),
                imageWidth = size.width,
                imageHeight = size.height,
            )
        } else {
            // A wrong focal length shows up as under- or over-correction, not as
            // a crash, so an approximate fallback is better than refusing to run.
            Log.w(TAG, "Falling back to an assumed 78 degree horizontal field of view")
            CameraIntrinsics.fromHorizontalFov(78.0, size.width, size.height)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(id: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (cont.isActive) cont.resume(device) else device.close()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Camera error $error"))
                    }
                }
            }, handler)
        }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (cont.isActive) cont.resume(s)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("Capture session configuration failed"))
                }
            }
        }, handler)
    }
}
