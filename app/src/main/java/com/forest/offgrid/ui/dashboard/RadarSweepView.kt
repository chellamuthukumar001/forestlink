package com.forest.offgrid.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class RadarSweepView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private val COLOR_CYBER_BLUE = Color.parseColor("#00D4FF")
        private val COLOR_PULSE_RED = Color.parseColor("#FF3131")
        private val COLOR_NEON_GREEN = Color.parseColor("#39FF14")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CYBER_BLUE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 60
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CYBER_BLUE
        strokeWidth = 4f
        alpha = 180
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val blipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var sweepAngle = 0f
    private val rotationSpeed = 1.5f

    // Simulated node locations (angle in degrees, radius fraction 0..1, is SOS)
    private val simulatedBlips = listOf(
        RadarBlip(45f, 0.4f, false),
        RadarBlip(135f, 0.7f, false),
        RadarBlip(220f, 0.5f, true),
        RadarBlip(315f, 0.8f, false)
    )

    private var centerX = 0f
    private var centerY = 0f
    private var maxRadius = 0f
    private var isAnimating = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        maxRadius = minOf(centerX, centerY) * 0.9f

        if (maxRadius > 0f) {
            val gradientColors = intArrayOf(
                Color.argb(80, 0, 212, 255),
                Color.argb(0, 0, 212, 255)
            )
            sweepPaint.shader = RadialGradient(
                centerX, centerY, maxRadius,
                gradientColors, null, Shader.TileMode.CLAMP
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pauseAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            resumeAnimation()
        } else {
            pauseAnimation()
        }
    }

    private fun resumeAnimation() {
        if (!isAnimating) {
            isAnimating = true
            postInvalidateOnAnimation()
        }
    }

    private fun pauseAnimation() {
        isAnimating = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (maxRadius <= 0f) return

        // Draw grid concentric circles
        canvas.drawCircle(centerX, centerY, maxRadius, gridPaint)
        canvas.drawCircle(centerX, centerY, maxRadius * 0.66f, gridPaint)
        canvas.drawCircle(centerX, centerY, maxRadius * 0.33f, gridPaint)

        // Draw grid crosshairs
        canvas.drawLine(centerX - maxRadius, centerY, centerX + maxRadius, centerY, gridPaint)
        canvas.drawLine(centerX, centerY - maxRadius, centerX, centerY + maxRadius, gridPaint)

        // Sweep line endpoint
        val angleRad = Math.toRadians(sweepAngle.toDouble())
        val endX = (centerX + maxRadius * cos(angleRad)).toFloat()
        val endY = (centerY + maxRadius * sin(angleRad)).toFloat()

        canvas.drawLine(centerX, centerY, endX, endY, linePaint)

        // Trailing gradient sector
        canvas.drawArc(
            centerX - maxRadius, centerY - maxRadius,
            centerX + maxRadius, centerY + maxRadius,
            sweepAngle - 45f, 45f, true, sweepPaint
        )

        // Draw node blips with decay alpha
        for (i in simulatedBlips.indices) {
            val blip = simulatedBlips[i]
            val diff = (sweepAngle - blip.angle + 360f) % 360f

            val alphaFactor = if (diff < 90f) {
                1.0f - (diff / 90f)
            } else {
                0.1f
            }

            val blipAngleRad = Math.toRadians(blip.angle.toDouble())
            val blipX = (centerX + maxRadius * blip.radiusPercent * cos(blipAngleRad)).toFloat()
            val blipY = (centerY + maxRadius * blip.radiusPercent * sin(blipAngleRad)).toFloat()

            if (blip.isSos) {
                blipPaint.color = COLOR_PULSE_RED
                blipPaint.alpha = (alphaFactor * 255f).toInt().coerceIn(30, 255)
                blipRingPaint.color = COLOR_PULSE_RED
                blipRingPaint.alpha = ((1f - alphaFactor) * 255f).toInt().coerceIn(0, 255)

                canvas.drawCircle(blipX, blipY, 14f, blipPaint)
                canvas.drawCircle(blipX, blipY, 14f + (diff % 30f), blipRingPaint)
            } else {
                blipPaint.color = COLOR_NEON_GREEN
                blipPaint.alpha = (alphaFactor * 255f).toInt().coerceIn(20, 255)
                canvas.drawCircle(blipX, blipY, 10f, blipPaint)
            }
        }

        // Increment angle
        sweepAngle = (sweepAngle + rotationSpeed) % 360f

        // Redraw next frame only if visible & attached
        if (isAnimating && isShown) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
        }
    }

    private data class RadarBlip(val angle: Float, val radiusPercent: Float, val isSos: Boolean)
}
