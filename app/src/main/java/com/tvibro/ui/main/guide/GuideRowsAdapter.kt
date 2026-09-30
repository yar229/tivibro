package com.tvibro.ui.main.guide

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.Fmt
import com.tvibro.data.model.Channel
import com.tvibro.data.model.Program

/**
 * One row per channel, programs laid out along the time axis.
 *
 * Rows do not scroll on their own. Every row is padded to exactly one day and the whole grid is
 * moved by translating the row content by a single shared offset, so there is no per-row scroll
 * position to keep in sync. Rows used to hold an independently scrolling RecyclerView each, and
 * syncing them on every frame both flickered and made the shared position unreliable.
 */
class GuideRowsAdapter(
    hourWidthPx: Int,
    private val onProgramClick: (Int) -> Unit,
    private val onOffsetChanged: () -> Unit,
) : RecyclerView.Adapter<GuideRowsAdapter.RowHolder>() {

    private var channels: List<Channel> = emptyList()
    private var programs: Map<Long, List<Program>> = emptyMap()
    private var hourWidth = hourWidthPx.coerceAtLeast(1)
    private var dayStart = Fmt.startOfDay(System.currentTimeMillis())
    private var highlightCurrent = true
    private var offset = 0
    private var viewport = 0
    private var contentStart = 0
    private var contentEnd = 0
    private val rows = ArrayList<RowHolder>()

    private val dayWidth get() = hourWidth * GuideDaysAdapter.HOURS_IN_DAY

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: LinearLayout = view.findViewById(R.id.programs)
        var channelPosition: Int = 0
        var bound: Boolean = false
        var filledList: List<Program>? = null
        var filledDay: Long = 0L
        var filledHighlight: Boolean = false
    }

    fun setData(newChannels: List<Channel>, newPrograms: Map<Long, List<Program>>) {
        channels = newChannels
        programs = newPrograms
        recomputeContentRange()
        notifyDataSetChanged()
    }

    fun setDayStart(start: Long) {
        dayStart = start
        recomputeContentRange()
        notifyDataSetChanged()
    }

    fun setHighlightCurrent(enabled: Boolean) {
        if (highlightCurrent == enabled) return
        highlightCurrent = enabled
        notifyDataSetChanged()
    }

    /** Width of the visible part of the grid, needed to clamp the offset. */
    fun setViewport(width: Int) {
        if (viewport == width) return
        viewport = width
        setOffset(offset)
    }

    fun hourWidthPx(): Int = hourWidth

    fun pixelForTime(time: Long): Int = ((time - dayStart) / 3_600_000f * hourWidth).toInt()

    fun currentOffset(): Int = offset

    /**
     * Closest horizontal position that actually shows programmes, or null when the current one is
     * already fine.
     *
     * Stalker EPG is sparse and lopsided: for a fresh "today" the first twelve hours hold data for
     * a couple of channels only, so the outermost programmes sit on rows the user cannot even see.
     * Clamping to those bounds lets a fling rest on a window where every visible row is empty, which
     * reads as the schedule vanishing. This looks for the nearest window where at least a third of
     * the attached rows carry something, so the grid never comes to rest completely blank.
     */
    fun nearestPopulatedOffset(): Int? {
        val spans = attachedSpans()
        if (spans.isEmpty() || viewport <= 0) return null
        val min = minOffset()
        val max = maxOffset()
        if (min >= max) return null
        fun populated(candidate: Int): Boolean {
            val left = candidate.toLong()
            val right = (candidate + viewport).toLong()
            var filled = 0
            for (row in spans) {
                for (span in row) {
                    if (span.first < right && span.last > left) {
                        filled++
                        break
                    }
                }
            }
            return filled * 3 >= spans.size
        }
        if (populated(offset)) return null
        val step = hourWidth
        val steps = dayWidth / step + 1
        for (distance in 1..steps) {
            val ahead = offset + distance * step
            if (ahead <= max && populated(ahead)) return ahead.coerceAtMost(max)
            val back = offset - distance * step
            if (back >= min && populated(back)) return back.coerceAtLeast(min)
        }
        return null
    }

    /** Programme spans, in grid pixels, of the rows that are currently on screen. */
    private fun attachedSpans(): List<List<IntRange>> = rows.mapNotNull { holder ->
        val channel = channels.getOrNull(holder.channelPosition) ?: return@mapNotNull null
        val spans = programs[channel.id].orEmpty().map { program ->
            val from = pixelForTime(program.start).coerceIn(0, dayWidth)
            val to = pixelForTime(program.stop).coerceIn(0, dayWidth)
            from..to
        }
        if (spans.isEmpty()) null else spans
    }

    /** Shared horizontal position of the whole grid, in pixels from the start of the day. */
    fun setOffset(pixel: Int) {
        offset = pixel.coerceIn(minOffset(), maxOffset())
        val shift = -offset.toFloat()
        rows.forEach { it.content.translationX = shift }
        onOffsetChanged()
    }

    /**
     * The scrollable window follows the programmes that actually exist, not the length of the
     * day. Stalker usually has no EPG before the early morning, so a window bounded by midnight
     * would let a fling scroll into hours that are empty for every channel.
     */
    private fun recomputeContentRange() {
        var from = dayWidth
        var to = 0
        val end = dayStart + GuideDaysAdapter.DAY_MS
        for (list in programs.values) {
            for (program in list) {
                if (program.stop <= dayStart || program.start >= end) continue
                from = minOf(from, pixelForTime(program.start).coerceAtLeast(0))
                to = maxOf(to, pixelForTime(program.stop))
            }
        }
        contentStart = if (to > from) from.coerceIn(0, dayWidth) else 0
        contentEnd = if (to > from) to.coerceIn(0, dayWidth) else dayWidth
    }

    private fun minOffset(): Int = contentStart.coerceIn(0, (dayWidth - viewport).coerceAtLeast(0))

    private fun maxOffset(): Int =
        (contentEnd - viewport).coerceAtLeast(0).coerceAtLeast(minOffset())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false)
        hourWidth = parent.resources.getDimensionPixelSize(R.dimen.epg_hour_width)
        return RowHolder(view)
    }

    override fun getItemCount(): Int = channels.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val channel = channels[position]
        val list = programs[channel.id].orEmpty()
        holder.channelPosition = position
        if (holder.content.childCount == 0 ||
            holder.filledList !== list ||
            holder.filledDay != dayStart ||
            holder.filledHighlight != highlightCurrent
        ) {
            fill(holder, list)
        }
        if (!holder.bound) {
            holder.itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!rows.contains(holder)) rows += holder
                    holder.content.translationX = -offset.toFloat()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    rows.remove(holder)
                }
            })
            holder.bound = true
        }
        holder.content.translationX = -offset.toFloat()
    }

    private fun fill(holder: RowHolder, list: List<Program>) {
        val content = holder.content
        val inflater = LayoutInflater.from(content.context)
        val margin = content.resources.getDimensionPixelSize(R.dimen.epg_cell_margin) * 2
        content.removeAllViews()
        var cursor = 0
        for (program in list) {
            val from = pixelForTime(program.start)
            val to = pixelForTime(program.stop)
            if (to <= 0 || from >= dayWidth) continue
            val start = from.coerceIn(0, dayWidth)
            // Cells are placed at their real start time instead of being laid out back to back,
            // otherwise the row drifts against the time axis and the current time marker ends up
            // in the wrong place. Gaps between programmes become invisible filler.
            if (start > cursor) {
                addFiller(content, start - cursor)
                cursor = start
            }
            val width = (to.coerceIn(start + 1, dayWidth) - start).coerceAtLeast(1)
            val cell = inflater.inflate(R.layout.item_epg_program, content, false)
            cell.layoutParams = LinearLayout.LayoutParams(
                (width - margin).coerceAtLeast(1),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            cell.findViewById<TextView>(R.id.program_title).text = program.title
            cell.findViewById<TextView>(R.id.program_time).text =
                Fmt.time(program.start) + " - " + Fmt.time(program.stop)
            val running = System.currentTimeMillis() in program.start until program.stop
            cell.setBackgroundResource(
                if (running && highlightCurrent) R.drawable.bg_epg_now else R.drawable.bg_epg_cell
            )
            cell.setOnClickListener { onProgramClick(holder.channelPosition) }
            content.addView(cell)
            cursor = start + width
        }
        // Pad the row out to a full day so every row shares one scroll range.
        if (dayWidth > cursor) addFiller(content, dayWidth - cursor)
        content.layoutParams = content.layoutParams.apply { width = dayWidth }
        holder.filledList = list
        holder.filledDay = dayStart
        holder.filledHighlight = highlightCurrent
    }

    private fun addFiller(content: LinearLayout, width: Int) {
        val filler = View(content.context)
        content.addView(filler, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
