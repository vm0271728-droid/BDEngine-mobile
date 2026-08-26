package com.bdengine.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SegmentSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    private var phase = 0
    private val frameRunnable = object : Runnable {
        override fun run() {
            phase = (phase + 1) % SEGMENTS
            invalidate()
            postDelayed(this, FRAME_DELAY_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(frameRunnable)
        post(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f
        val innerRadius = size * 0.20f
        val outerRadius = size * 0.39f
        paint.strokeWidth = size * 0.075f

        for (index in 0 until SEGMENTS) {
            val distance = (index - phase + SEGMENTS) % SEGMENTS
            val alpha = (255 - distance * 18).coerceIn(45, 255)
            paint.alpha = alpha

            val angle = Math.toRadians(index * (360.0 / SEGMENTS) - 90.0)
            val cos = cos(angle).toFloat()
            val sin = sin(angle).toFloat()

            canvas.drawLine(
                centerX + cos * innerRadius,
                centerY + sin * innerRadius,
                centerX + cos * outerRadius,
                centerY + sin * outerRadius,
                paint
            )
        }
    }

    companion object {
        private const val SEGMENTS = 12
        private const val FRAME_DELAY_MS = 75L
    }
}
