package com.veltrix.ultron.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.veltrix.ultron.MainActivity
import com.veltrix.ultron.R
import com.veltrix.ultron.platform.AndroidExecutorBridge
import kotlin.math.abs

/**
 * User-consented edge invocation surface.
 *
 * Overlay permission always remains under Android Settings. The edge handle can open
 * ULTRON Chat or explicitly request a one-shot MediaProjection consent session; it
 * never grants capabilities or executes a mission by itself.
 */
class UltronOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay()
            else -> showOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeOverlayView()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (overlayView != null) return

        val manager = getSystemService(WindowManager::class.java)
        if (manager == null) {
            stopSelf()
            return
        }

        val density = resources.displayMetrics.density
        val swipeDetector = EdgeSwipeGestureDetector(
            minHorizontalDistance = EDGE_SWIPE_DISTANCE_DP * density,
            maxVerticalDrift = EDGE_VERTICAL_DRIFT_DP * density
        )
        val maxVisualTravel = EDGE_VISUAL_TRAVEL_DP * density
        val cancelLongPressDistance = LONG_PRESS_CANCEL_DISTANCE_DP * density
        var longPressTriggered = false
        var downX = 0f
        var downY = 0f
        lateinit var handle: TextView
        val longPress = Runnable {
            longPressTriggered = true
            handle.translationX = 0f
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openScreenCaptureConsent()
        }

        handle = TextView(this).apply {
            text = "›"
            contentDescription = getString(R.string.edge_handle_description)
            setTextColor(Color.WHITE)
            textSize = 23f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(225, 11, 16, 22))
                cornerRadius = 18f * density
                setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.rgb(25, 200, 255))
            }
            setOnClickListener { openUltronChat() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressTriggered = false
                        downX = event.rawX
                        downY = event.rawY
                        swipeDetector.onDown(event.rawX, event.rawY)
                        view.translationX = 0f
                        mainHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - downX) > cancelLongPressDistance || abs(event.rawY - downY) > cancelLongPressDistance) {
                            mainHandler.removeCallbacks(longPress)
                        }
                        if (!longPressTriggered) {
                            view.translationX = swipeDetector.horizontalProgress(event.rawX).coerceAtMost(maxVisualTravel)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPress)
                        val invoke = !longPressTriggered && swipeDetector.isRightSwipe(event.rawX, event.rawY)
                        swipeDetector.reset()
                        view.animate().translationX(0f).setDuration(120L).start()
                        when {
                            longPressTriggered -> Unit
                            invoke -> openUltronChat()
                            else -> view.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPress)
                        longPressTriggered = false
                        swipeDetector.reset()
                        view.animate().translationX(0f).setDuration(120L).start()
                        true
                    }
                    else -> false
                }
            }
        }

        val params = WindowManager.LayoutParams(
            (EDGE_HANDLE_WIDTH_DP * density).toInt(),
            (EDGE_HANDLE_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
        }

        runCatching { manager.addView(handle, params) }
            .onSuccess {
                windowManager = manager
                overlayView = handle
            }
            .onFailure { stopSelf() }
    }

    private fun openUltronChat() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_INVOCATION_SOURCE, INVOCATION_EDGE_GESTURE)
                putExtra(MainActivity.EXTRA_INITIAL_PAGE, MainActivity.PAGE_CHAT)
            }
        )
    }

    private fun openScreenCaptureConsent() {
        val sourcePackage = AndroidExecutorBridge.observer.observe().packageName
        startActivity(
            ScreenCaptureConsentActivity.createIntent(this, sourcePackage).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun hideOverlay() {
        removeOverlayView()
        stopSelf()
    }

    private fun removeOverlayView() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        windowManager = null
    }

    companion object {
        const val ACTION_SHOW = "com.veltrix.ultron.overlay.SHOW"
        const val ACTION_HIDE = "com.veltrix.ultron.overlay.HIDE"
        private const val INVOCATION_EDGE_GESTURE = "edge_gesture"
        private const val EDGE_HANDLE_WIDTH_DP = 24f
        private const val EDGE_HANDLE_HEIGHT_DP = 112f
        private const val EDGE_SWIPE_DISTANCE_DP = 56f
        private const val EDGE_VERTICAL_DRIFT_DP = 88f
        private const val EDGE_VISUAL_TRAVEL_DP = 48f
        private const val LONG_PRESS_CANCEL_DISTANCE_DP = 12f
    }
}
