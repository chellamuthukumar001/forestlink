package com.forest.offgrid.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var activeColor = Color.parseColor("#39FF14") // Default Neon Green
    private var inactiveColor = Color.argb(60, 57, 255, 20) // Semi-transparent active color
    
    private var progress = 0.0f
    private val barWidth = 8f
    private val barGap = 6f
    private var barCount = 20

    // Seeded random heights
    private val barHeights = FloatArray(40)
    private var messageSeed = 0L

    init {
        generateWaveformHeights()
    }

    fun setColors(active: Int, inactive: Int) {
        activeColor = active
        inactiveColor = inactive
        invalidate()
    }

    fun setMessageId(id: Long) {
        messageSeed = id
        generateWaveformHeights()
        invalidate()
    }

    fun setProgress(prog: Float) {
        progress = prog.coerceIn(0.0f, 1.0f)
        invalidate()
    }

    private fun generateWaveformHeights() {
        val rand = Random(messageSeed)
        for (i in barHeights.indices) {
            // Generate heights as percentages (0.15f to 0.85f of total view height)
            barHeights[i] = rand.nextFloat() * 0.7f + 0.15f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Calculate how many bars fit in the width
        val totalBarWidth = barWidth + barGap
        barCount = ((w - paddingLeft - paddingRight) / totalBarWidth).toInt().coerceIn(10, 35)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val totalBarWidth = barWidth + barGap
        
        // Calculate offset to center the waveform
        val contentWidth = barCount * totalBarWidth - barGap
        val startX = (w - contentWidth) / 2f
        
        val rect = RectF()
        val activeThresholdBar = (progress * barCount).toInt()

        for (i in 0 until barCount) {
            val barHeight = h * barHeights[i % barHeights.size]
            val x = startX + i * totalBarWidth
            val top = (h - barHeight) / 2f
            val bottom = top + barHeight
            
            rect.set(x, top, x + barWidth, bottom)
            
            // Set color based on active/played state
            if (i < activeThresholdBar) {
                barPaint.color = activeColor
            } else {
                barPaint.color = inactiveColor
            }
            
            // Draw rounded bar
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }
}
