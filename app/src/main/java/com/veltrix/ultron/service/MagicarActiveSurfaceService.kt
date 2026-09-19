package com.veltrix.ultron.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.animation.ValueAnimator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Minimal assistant surface for the 1280x720 FYT head unit.
 *
 * It never accepts touches and never blocks the underlying app. While the assistant
 * is active it renders only:
 *  - a soft cyan/blue pulsing perimeter;
 *  - a lightweight top waveform driven by microphone level.
 *
 * No buttons, chat chrome, cards, or modal UI are drawn here.
 */
class MagicarActiveSurfaceService : Service() {
    private var windowManager: WindowManager? = null
    private var surface: ActiveSurfaceView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVE -> showSurface()
            ACTION_LEVEL -> {
                showSurface()
                surface?.setVoiceLevel(intent.getFloatExtra(EXTRA_LEVEL, 0f))
            }
            ACTION_IDLE -> {
                hideSurface()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideSurface()
        super.onDestroy()
    }

    private fun showSurface() {
        if (!Settings.canDrawOverlays(this)) return
        if (surface != null) return
        val manager = getSystemService(WindowManager::class.java) ?: return
        val view = ActiveSurfaceView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { manager.addView(view, params) }
            .onSuccess {
                windowManager = manager
                surface = view
                view.start()
            }
    }

    private fun hideSurface() {
        val view = surface ?: return
        view.stop()
        runCatching { windowManager?.removeViewImmediate(view) }
        surface = null
        windowManager = null
    }

    private class ActiveSurfaceView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private var phase = 0f
        private var pulse = 0f
        private var currentLevel = 0f
        private var targetLevel = 0f

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_300L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                phase += 0.12f
                pulse = 0.5f - 0.5f * kotlin.math.cos((value * 2f * PI).toFloat())
                currentLevel += (targetLevel - currentLevel) * 0.22f
                invalidate()
            }
        }

        fun start() {
            if (!animator.isStarted) animator.start()
        }

        fun stop() {
            animator.cancel()
        }

        fun setVoiceLevel(level: Float) {
            targetLevel = level.coerceIn(0f, 1f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return

            val edgeAlpha = (95 + 95 * pulse).toInt().coerceIn(0, 255)
            val haloAlpha = (28 + 40 * pulse).toInt().coerceIn(0, 120)
            val edgeWidth = 2.2f * density + 1.2f * density * pulse
            val inset = edgeWidth * 0.6f

            edgePaint.strokeWidth = edgeWidth
            edgePaint.color = Color.argb(edgeAlpha, 30, 190, 255)
            haloPaint.strokeWidth = 9f * density
            haloPaint.color = Color.argb(haloAlpha, 20, 145, 255)

            canvas.drawRoundRect(
                inset,
                inset,
                w - inset,
                h - inset,
                7f * density,
                7f * density,
                haloPaint
            )
            canvas.drawRoundRect(
                inset,
                inset,
                w - inset,
                h - inset,
                7f * density,
                7f * density,
                edgePaint
            )

            drawTopWave(canvas, w)
        }

        private fun drawTopWave(canvas: Canvas, width: Float) {
            val centerX = width / 2f
            val centerY = 19f * density
            val usableWidth = (width * 0.32f).coerceAtLeast(220f * density)
            val bars = 39
            val spacing = usableWidth / (bars - 1)
            val voice = (0.12f + currentLevel * 0.88f).coerceIn(0.12f, 1f)

            val shader = LinearGradient(
                centerX - usableWidth / 2f,
                0f,
                centerX + usableWidth / 2f,
                0f,
                intArrayOf(
                    Color.argb(0, 24, 170, 255),
                    Color.argb(235, 45, 210, 255),
                    Color.argb(255, 112, 225, 255),
                    Color.argb(235, 45, 210, 255),
                    Color.argb(0, 24, 170, 255)
                ),
                floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
            wavePaint.shader = shader
            wavePaint.strokeWidth = 2.2f * density

            for (i in 0 until bars) {
                val normalized = i.toFloat() / (bars - 1)
                val distance = abs(normalized - 0.5f) * 2f
                val envelope = (1f - distance * distance).coerceAtLeast(0.12f)
                val oscillation = 0.52f + 0.48f * abs(sin(phase + i * 0.48f))
                val half = (2.5f + 13f * voice * envelope * oscillation) * density
                val x = centerX - usableWidth / 2f + i * spacing
                canvas.drawLine(x, centerY - half, x, centerY + half, wavePaint)
            }
            wavePaint.shader = null
        }
    }

    companion object {
        const val ACTION_ACTIVE = "com.veltrix.magicar.surface.ACTIVE"
        const val ACTION_LEVEL = "com.veltrix.magicar.surface.LEVEL"
        const val ACTION_IDLE = "com.veltrix.magicar.surface.IDLE"
        const val EXTRA_LEVEL = "voice_level"

        fun activate(context: Context) {
            context.startService(
                Intent(context, MagicarActiveSurfaceService::class.java).setAction(ACTION_ACTIVE)
            )
        }

        fun updateLevel(context: Context, level: Float) {
            context.startService(
                Intent(context, MagicarActiveSurfaceService::class.java)
                    .setAction(ACTION_LEVEL)
                    .putExtra(EXTRA_LEVEL, level.coerceIn(0f, 1f))
            )
        }

        fun deactivate(context: Context) {
            context.startService(
                Intent(context, MagicarActiveSurfaceService::class.java).setAction(ACTION_IDLE)
            )
        }
    }
}
