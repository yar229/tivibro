package com.tvibro.ui.main.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.tvibro.R
import com.tvibro.base.Fmt
import kotlin.math.ceil
import kotlin.math.max

/**
 * Time scale drawn above the grid: a tick and a label every [labelStepMinutes], the ticks at full
 * hours being taller and brighter.
 *
 * The scale is a full day wide and is moved by [setOffset] exactly like the rows below it, so both
 * stay on the same time axis without a scroll position of their own. Only the ticks that fall into
 * the visible window are drawn, the ruler spans 48 steps but a tablet shows roughly six of them.
 */
class TimeRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val hourWidth =
        resources.getDimensionPixelSize(R.dimen.epg_hour_width).coerceAtLeast(1)
    private val labelStepMinutes = resources.getInteger(R.integer.epg_ruler_step_minutes)
        .coerceAtLeast(5)
    private val stepWidth = hourWidth * labelStepMinutes / 60
    private val dayWidth = hourWidth * GuideDaysAdapter.HOURS_IN_DAY
    private val stepsPerDay = MINUTES_PER_DAY / labelStepMinutes
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.outline, null)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.epg_ruler_tick).toFloat()
    }
    private val hourTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.text_disabled, null)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.epg_ruler_tick).toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.text_secondary, null)
        textSize = resources.getDimension(R.dimen.epg_ruler_text)
        textAlign = Paint.Align.LEFT
    }
    private val halfLabelPaint = Paint(labelPaint).apply {
        color = resources.getColor(R.color.text_tertiary, null)
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.accent, null)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.epg_now_line).toFloat()
    }

    private var dayStart = Fmt.startOfDay(System.currentTimeMillis())
    private var nowPixel = -1

    init {
        contentDescription = context.getString(R.string.epg_ruler)
    }

    fun setDayStart(start: Long) {
        if (dayStart == start) return
        dayStart = start
        invalidate()
    }

    /** Shares the grid position, so the labels sit exactly above their programmes. */
    fun setOffset(offset: Int) {
        translationX = -offset.toFloat()
    }

    /** Keeps the marker on the scale in step with the one drawn over the grid. */
    fun setNow(now: Long) {
        val pixel = pixelForTime(now)
        if (pixel == nowPixel) return
        nowPixel = pixel
        invalidate()
    }

    fun pixelForTime(time: Long): Int = ((time - dayStart) / 3_600_000f * hourWidth).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dayWidth, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val height = height.toFloat()
        val textBaseline = height - labelPaint.descent() / 2f - labelPaint.textSize * 0.15f
        val shortTick = height * 0.22f
        val fullTick = height * 0.42f
        // translationX is negative while the grid is scrolled forward, so the first step that can
        // still reach the screen is the one at or after the left edge. The ruler itself is a whole
        // day wide, the visible part is only as wide as the grid it belongs to.
        val left = -translationX
        val right = left + ((parent as? View)?.width ?: width)
        val first = max(0, ceil(left / stepWidth - 1f).toInt())
        val last = minOf(stepsPerDay - 1, (right / stepWidth).toInt() + 1)
        for (step in first..last) {
            val x = step * stepWidth.toFloat()
            val onHour = step * labelStepMinutes % 60 == 0
            canvas.drawLine(
                x,
                height,
                x,
                height - if (onHour) fullTick else shortTick,
                if (onHour) hourTickPaint else tickPaint,
            )
            canvas.drawText(
                Fmt.time(dayStart + step * labelStepMinutes * 60_000L),
                x + labelPaint.textSize * 0.3f,
                textBaseline,
                if (onHour) labelPaint else halfLabelPaint,
            )
        }
        if (nowPixel >= 0 && nowPixel in left.toInt()..right.toInt()) {
            // The grid marker is a view whose left edge sits on the position, so the line on the
            // scale is centred half a stroke further right to line up with it.
            val centre = nowPixel + nowPaint.strokeWidth / 2f
            canvas.drawLine(centre, 0f, centre, height, nowPaint)
        }
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
