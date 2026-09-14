package com.budgety.myapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Small dependency-free chart for category spending on the dashboard. */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private var values: List<Pair<String, Double>> = emptyList()

    fun setData(data: List<Pair<String, Double>>) {
        values = data.filter { it.second > 0.0 }.take(7)
        contentDescription = values.joinToString { "${it.first}: ${it.second}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) {
            canvas.drawText("No expense data for this period", 12f, height / 2f, textPaint)
            return
        }
        val maxValue = max(1.0, values.maxBy { it.second }?.second ?: 1.0)
        val chartHeight = height - 32f
        val slot = width.toFloat() / values.size
        values.forEachIndexed { index, item ->
            val barHeight = (item.second / maxValue * (chartHeight - 18f)).toFloat()
            val left = index * slot + slot * .18f
            barPaint.color = Color.rgb(15, 81, 50)
            canvas.drawRect(left, chartHeight - barHeight, left + slot * .64f, chartHeight, barPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(item.first.take(8), left + slot * .32f, height - 5f, textPaint)
        }
    }
}
