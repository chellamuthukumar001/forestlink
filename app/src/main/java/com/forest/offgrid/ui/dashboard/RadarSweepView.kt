package com.forest.offgrid.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

class RadarSweepView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00D4FF") // Cyber Blue
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 60 // Semi-transparent
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
    private val rotationSpeed = 1.5f // Degrees per frame

    // Simulated node locations (angle in degrees, radius fraction 0..1, is SOS)
    private val simulatedBlips = listOf(
        RadarBlip(45f, 0.4f, false),
        RadarBlip(135f, 0.7f, false),
        RadarBlip(220f, 0.5f, true), // SOS blip (red)
        RadarBlip(315f, 0.8f, false)
    )

    private var centerX = 0f
    private var centerY = 0f
    private var maxRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        maxRadius = minOf(centerX, centerY) * 0.9f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw grid concentric circles
        canvas.drawCircle(centerX, centerY, maxRadius, gridPaint)
        canvas.drawCircle(centerX, centerY, maxRadius * 0.66f, gridPaint)
        canvas.drawCircle(centerX, centerY, maxRadius * 0.33f, gridPaint)

        // Draw grid crosshairs
        canvas.drawLine(centerX - maxRadius, centerY, centerX + maxRadius, centerY, gridPaint)
        canvas.drawLine(centerX, centerY - maxRadius, centerX, centerY + maxRadius, gridPaint)

        // Set up the sweep gradient paint dynamically based on current angle
        val angleRad = Math.toRadians(sweepAngle.toDouble())
        val endX = (centerX + maxRadius * cos(angleRad)).toFloat()
        val endY = (centerY + maxRadius * sin(angleRad)).toFloat()

        // Draw sweep line
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00D4FF")
            strokeWidth = 4f
            alpha = 180
        }
        canvas.drawLine(centerX, centerY, endX, endY, linePaint)

        // Draw sweeping sector gradient
        val colors = intArrayOf(
            Color.argb(80, 0, 212, 255), // Cyber Blue with alpha
            Color.argb(0, 0, 212, 255)
        )
        val shader = RadialGradient(
            centerX, centerY, maxRadius,
            colors, null, Shader.TileMode.CLAMP
        )
        sweepPaint.shader = shader

        // Use drawArc to draw the trailing gradient sector
        canvas.drawArc(
            centerX - maxRadius, centerY - maxRadius,
            centerX + maxRadius, centerY + maxRadius,
            sweepAngle - 45f, 45f, true, sweepPaint
        )

        // Draw node blips with decay alpha
        simulatedBlips.forEach { blip ->
            // Calculate distance of sweep past the blip angle
            val diff = (sweepAngle - blip.angle + 360) % 360
            
            // Decaying alpha effect: brightest immediately after sweep passes
            val alphaFactor = if (diff < 90) {
                1.0f - (diff / 90f)
            } else {
                0.1f // faint residual glow
            }

            val blipX = (centerX + maxRadius * blip.radiusPercent * cos(Math.toRadians(blip.angle.toDouble()))).toFloat()
            val blipY = (centerY + maxRadius * blip.radiusPercent * sin(Math.toRadians(blip.angle.toDouble()))).toFloat()

            if (blip.isSos) {
                // Pulse SOS in red
                blipPaint.color = Color.parseColor("#FF3131") // Pulse Red
                blipPaint.alpha = (alphaFactor * 255).toInt().coerceIn(30, 255)
                blipRingPaint.color = Color.parseColor("#FF3131")
                blipRingPaint.alpha = ((1f - alphaFactor) * 255).toInt().coerceIn(0, 255)
                
                // Draw inner dot
                canvas.drawCircle(blipX, blipY, 14f, blipPaint)
                // Draw outer expanding ring
                canvas.drawCircle(blipX, blipY, 14f + (diff % 30f), blipRingPaint)
            } else {
                // Regular node in neon green
                blipPaint.color = Color.parseColor("#39FF14") // Neon Green
                blipPaint.alpha = (alphaFactor * 255).toInt().coerceIn(20, 255)
                canvas.drawCircle(blipX, blipY, 10f, blipPaint)
            }
        }

        // Increment angle
        sweepAngle = (sweepAngle + rotationSpeed) % 360

        // Redraw on next frame
        postInvalidateOnAnimation()
    }

    private data class RadarBlip(val angle: Float, val radiusPercent: Float, val isSos: Boolean)
}
