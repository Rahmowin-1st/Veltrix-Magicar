package com.veltrix.ultron.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.veltrix.ultron.platform.AndroidScreenCaptureBridge

/**
 * Transparent user-consent trampoline for exactly one MediaProjection session.
 * It never caches or reuses Android's consent result.
 */
class ScreenCaptureConsentActivity : Activity() {
    private var requestStarted = false
    private var sourcePackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        AndroidScreenCaptureBridge.beginCapture(sourcePackage)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            AndroidScreenCaptureBridge.unavailable("MediaProjection is unavailable on this device")
            finish()
            return
        }
        requestStarted = true
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Android API; retained for minSdk-compatible one-shot consent trampoline")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        requestStarted = false
        if (resultCode != RESULT_OK || data == null) {
            AndroidScreenCaptureBridge.denied()
            finish()
            return
        }

        val captureIntent = Intent(this, UltronScreenCaptureService::class.java).apply {
            action = UltronScreenCaptureService.ACTION_CAPTURE_ONCE
            putExtra(UltronScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(UltronScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(UltronScreenCaptureService.EXTRA_SOURCE_PACKAGE, sourcePackage)
        }
        runCatching { ContextCompat.startForegroundService(this, captureIntent) }
            .onFailure { AndroidScreenCaptureBridge.error("Could not start screen capture service") }
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && requestStarted) {
            AndroidScreenCaptureBridge.denied()
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAPTURE = 4107
        const val EXTRA_SOURCE_PACKAGE = "com.veltrix.ultron.extra.SCREEN_CAPTURE_SOURCE_PACKAGE"

        fun createIntent(context: Context, sourcePackage: String?): Intent =
            Intent(context, ScreenCaptureConsentActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            }
    }
}
