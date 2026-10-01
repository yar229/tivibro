package com.tvibro.ui.main.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.tvibro.R
import com.tvibro.base.Fmt
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Time scale drawn above the grid: a tick and a label every [labelStepMinutes], the ticks at full
 * hours being taller and brighter.
 *
 * The scale shares the grid position, [setOffset] moves it, but it is not translated: what has to be
 * drawn depends on the position, and a view that moves itself without asking for a new drawing is
 * only ever re-transformed, so the labels would keep the ticks of the old moment. Instead the offset
 * is a plain field and every change repaints.
 *
 * The day is written in the upper left corner for the day the left edge of the window stands on, with
 * its full weekday name and its date. The grid carries today and the days after it as one continuous
 * strip, so this is what tells the user which day they are looking at while they scroll. Only the
 * ticks inside the window are drawn: the range spans several days, a tablet shows about six steps.
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
    private val dayWidth = hourWidth * GuideRowsAdapter.HOURS_IN_DAY
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
    private val dayNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.accent, null)
        textSize = resources.getDimension(R.dimen.epg_ruler_day_name)
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val dayDatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.text_secondary, null)
        textSize = resources.getDimension(R.dimen.epg_ruler_day_date)
        textAlign = Paint.Align.LEFT
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.accent, null)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.epg_now_line).toFloat()
    }

    private var gridStart = Fmt.startOfDay(System.currentTimeMillis())
    private var days = GuideRowsAdapter.INITIAL_DAYS
    private var offset = 0
    private var nowPixel = -1
    private var locale: Locale = resources.configuration.locales[0]

    init {
        // The scale only labels the grid below it, so it must not become a stop for anything that
        // looks for focus: not the remote, and not the accessibility focus either. Some TV firmwares
        // show the description of whatever holds the focus next to the top of the screen, and a
        // description here leaked the name of the scale into the info panel area while walking the
        // grid. Every cell already carries its own title and time, so nothing is lost.
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setRange(start: Long, days: Int) {
        if (gridStart == start && this.days == days) return
        gridStart = start
        this.days = days.coerceAtLeast(1)
        invalidate()
    }

    /** Shares the grid position, so the labels sit exactly above their programmes. */
    fun setOffset(offset: Int) {
        if (this.offset == offset) return
        this.offset = offset
        invalidate()
    }

    /** Keeps the marker on the scale in step with the one drawn over the grid. */
    fun setNow(now: Long) {
        val pixel = pixelForTime(now)
        if (pixel == nowPixel) return
        nowPixel = pixel
        invalidate()
    }

    fun pixelForTime(time: Long): Int = ((time - gridStart) / 3_600_000f * hourWidth).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The scale is as wide as the window it labels: it is drawn in place instead of being moved
        // over the grid, so it cannot measure itself by the length of the whole range.
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val height = height.toFloat()
        val textBaseline = height - labelPaint.descent() / 2f - labelPaint.textSize * 0.15f
        // The ticks stay in the lower part of the scale: the day takes the upper left corner.
        val shortTick = height * 0.12f
        val fullTick = height * 0.2f
        val left = offset.toFloat()
        val right = left + width
        val totalSteps = stepsPerDay * days
        val first = max(0, ceil(left / stepWidth - 1f).toInt())
        val last = minOf(totalSteps - 1, (right / stepWidth).toInt() + 1)
        for (step in first..last) {
            val x = step * stepWidth.toFloat()
            val onHour = step * labelStepMinutes % 60 == 0
            canvas.drawLine(
                x - left,
                height,
                x - left,
                height - if (onHour) fullTick else shortTick,
                if (onHour) hourTickPaint else tickPaint,
            )
            canvas.drawText(
                Fmt.time(gridStart + step * labelStepMinutes * 60_000L),
                x - left + labelPaint.textSize * 0.3f,
                textBaseline,
                if (onHour) labelPaint else halfLabelPaint,
            )
        }
        drawDay(canvas)
        if (nowPixel >= 0 && nowPixel - offset in 0..width) {
            // The grid marker is a view whose left edge sits on the position, so the line on the
            // scale is centred half a stroke further right to line up with it.
            val centre = nowPixel - offset + nowPaint.strokeWidth / 2f
            canvas.drawLine(centre, 0f, centre, height, nowPaint)
        }
    }

    /**
     * Writes the day of the left edge of the window into the upper left corner: the full weekday name
     * above its date. It follows the scroll instead of sitting at the start of its own day, because a
     * name pinned to the day boundary walks off the screen long before the day it names is over.
     */
    private fun drawDay(canvas: Canvas) {
        val day = (offset / dayWidth).coerceIn(0, days - 1)
        val time = gridStart + day * GuideRowsAdapter.DAY_MS
        val left = dayNamePaint.textSize * 0.4f
        val nameBaseline = dayNamePaint.textSize
        canvas.drawText(Fmt.dayName(time, locale), left, nameBaseline, dayNamePaint)
        canvas.drawText(
            Fmt.dayDate(time, locale),
            left,
            nameBaseline + dayDatePaint.textSize,
            dayDatePaint,
        )
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}