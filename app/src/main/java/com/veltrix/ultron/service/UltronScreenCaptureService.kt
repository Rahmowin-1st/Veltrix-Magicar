package com.veltrix.ultron.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.IntentCompat
import com.veltrix.ultron.R
import com.veltrix.ultron.platform.AndroidScreenCaptureBridge
import com.veltrix.ultron.platform.AndroidScreenCaptureFrame
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Captures one user-approved frame, publishes it to RAM, then tears projection down. */
class UltronScreenCaptureService : Service() {
    private val delivered = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var sourcePackage: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE_ONCE) {
            stopSelf()
            return START_NOT_STICKY
        }
        startProjectionForeground()
        sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            AndroidScreenCaptureBridge.error("Capture grant was missing")
            cleanup(stopProjection = false)
            return START_NOT_STICKY
        }
        startOneShotCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup(stopProjection = true)
        super.onDestroy()
    }

    private fun startProjectionForeground() {
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen vision", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Visible notification for one-time user-approved screen capture"
                }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setContentText(getString(R.string.screen_capture_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startOneShotCapture(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            AndroidScreenCaptureBridge.unavailable("MediaProjection manager unavailable")
            cleanup(stopProjection = false)
            return
        }
        val thread = HandlerThread("ultron-screen-vision").also { it.start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        worker = handler

        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (mediaProjection == null) {
            AndroidScreenCaptureBridge.error("Android rejected the capture session")
            cleanup(stopProjection = false)
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!delivered.get()) {
                    AndroidScreenCaptureBridge.unavailable("Screen capture session stopped before a frame was available")
                }
                cleanup(stopProjection = false)
            }
        }, handler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ available ->
            val image = runCatching { available.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            if (!delivered.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            processImage(image, width, height)
        }, handler)

        val virtualDisplay = runCatching {
            mediaProjection.createVirtualDisplay(
                "VeltrixUltronOneShot",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )
        }.getOrNull()
        if (virtualDisplay == null) {
            AndroidScreenCaptureBridge.error("Virtual display could not be created")
            cleanup(stopProjection = true)
            return
        }
        display = virtualDisplay

        handler.postDelayed({
            if (delivered.compareAndSet(false, true)) {
                AndroidScreenCaptureBridge.unavailable("No capturable frame was produced")
                cleanup(stopProjection = true)
            }
        }, CAPTURE_TIMEOUT_MS)
    }

    private fun processImage(image: Image, width: Int, height: Int) {
        try {
            val plane = image.planes.firstOrNull()
            if (plane == null) {
                AndroidScreenCaptureBridge.unavailable("Capture frame had no pixel plane")
                return
            }
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride.coerceAtLeast(pixelStride * width)
            val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (padded !== cropped) padded.recycle()

            val scaled = scaleForPlanner(cropped)
            if (scaled !== cropped) cropped.recycle()
            if (isUniformFrame(scaled)) {
                scaled.recycle()
                AndroidScreenCaptureBridge.unavailable("Captured surface was blank or protected")
                return
            }
            val bytes = encodeBoundedJpeg(scaled)
            val outputWidth = scaled.width
            val outputHeight = scaled.height
            scaled.recycle()
            AndroidScreenCaptureBridge.publish(
                AndroidScreenCaptureFrame(
                    bytes = bytes,
                    mimeType = "image/jpeg",
                    width = outputWidth,
                    height = outputHeight,
                    sourcePackage = sourcePackage
                )
            )
        } catch (_: Throwable) {
            AndroidScreenCaptureBridge.error("Capture frame could not be processed")
        } finally {
            image.close()
            cleanup(stopProjection = true)
        }
    }

    private fun encodeBoundedJpeg(bitmap: Bitmap): ByteArray {
        var quality = INITIAL_JPEG_QUALITY
        while (quality >= MIN_JPEG_QUALITY) {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IllegalStateException("JPEG compression failed")
                }
                output.toByteArray()
            }
            if (bytes.size <= MAX_ENCODED_FRAME_BYTES) return bytes
            quality -= JPEG_QUALITY_STEP
        }
        throw IllegalStateException("One-shot frame exceeded bounded planner payload")
    }

    private fun scaleForPlanner(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_FRAME_SIDE) return bitmap
        val ratio = MAX_FRAME_SIDE.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun isUniformFrame(bitmap: Bitmap): Boolean {
        var minLuma = 255
        var maxLuma = 0
        val xSteps = 9
        val ySteps = 9
        for (yi in 0 until ySteps) {
            val y = ((bitmap.height - 1) * yi / (ySteps - 1)).coerceAtLeast(0)
            for (xi in 0 until xSteps) {
                val x = ((bitmap.width - 1) * xi / (xSteps - 1)).coerceAtLeast(0)
                val pixel = bitmap.getPixel(x, y)
                val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                minLuma = minOf(minLuma, luma)
                maxLuma = maxOf(maxLuma, luma)
            }
        }
        return maxLuma - minLuma < MIN_LUMA_RANGE
    }

    private fun cleanup(stopProjection: Boolean) {
        if (!cleaned.compareAndSet(false, true)) return
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        val currentProjection = projection
        projection = null
        if (stopProjection) runCatching { currentProjection?.stop() }
        worker?.removeCallbacksAndMessages(null)
        worker = null
        runCatching { workerThread?.quitSafely() }
        workerThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_CAPTURE_ONCE = "com.veltrix.ultron.screen.CAPTURE_ONCE"
        const val EXTRA_RESULT_CODE = "com.veltrix.ultron.extra.MEDIA_PROJECTION_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.veltrix.ultron.extra.MEDIA_PROJECTION_RESULT_DATA"
        const val EXTRA_SOURCE_PACKAGE = "com.veltrix.ultron.extra.MEDIA_PROJECTION_SOURCE_PACKAGE"
        private const val CHANNEL_ID = "veltrix_screen_vision"
        private const val NOTIFICATION_ID = 1717
        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private const val MAX_FRAME_SIDE = 960
        private const val MAX_ENCODED_FRAME_BYTES = 180_000
        private const val INITIAL_JPEG_QUALITY = 72
        private const val MIN_JPEG_QUALITY = 32
        private const val JPEG_QUALITY_STEP = 8
        private const val MIN_LUMA_RANGE = 4
    }
}
