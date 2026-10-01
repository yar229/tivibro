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
 * Rows do not scroll on their own. Every row is padded to the whole loaded range and the grid is
 * moved by translating the row content by a single shared offset, so there is no per-row scroll
 * position to keep in sync. Rows used to hold an independently scrolling RecyclerView each, and
 * syncing them on every frame both flickered and made the shared position unreliable.
 *
 * The axis covers [days] days starting at [gridStart], so "today" and the days after it are one
 * continuous grid and the days are picked by scrolling, not by a day picker above it. The caller
 * grows [days] while the user moves to the right, and the range is only as long as the schedule
 * that has actually been read from the database.
 */
class GuideRowsAdapter(
    hourWidthPx: Int,
    private val onProgramClick: (Int, Program) -> Unit,
    private val onCellHighlighted: (Int, Program?) -> Unit,
    private val onCellFocused: (Int, Program) -> Unit,
    private val onOffsetChanged: () -> Unit,
) : RecyclerView.Adapter<GuideRowsAdapter.RowHolder>() {

    private var channels: List<Channel> = emptyList()
    private var programs: Map<Long, List<Program>> = emptyMap()
    private var hourWidth = hourWidthPx.coerceAtLeast(1)
    private var gridStart = Fmt.startOfDay(System.currentTimeMillis())
    private var loadedDays = INITIAL_DAYS
    private var highlightCurrent = true
    private var offset = 0
    private var viewport = 0
    private var contentStart = 0
    private var contentEnd = 0
    private var selectedChannel = RecyclerView.NO_POSITION
    private var selectedCell: Program? = null
    private var focusedChannel = RecyclerView.NO_POSITION
    private var focusedCell: Program? = null
    private var cellMargin = 0
    private val rows = ArrayList<RowHolder>()
    /** Part of the axis the rows are built for, in grid pixels. */
    private var windowFrom = 0
    private var windowTo = 0
    /** Spare cells kept for the next rebuild: inflating them again on every scroll frame is the
     *  single most expensive thing a row does. */
    private val cellPool = ArrayList<View>()

    private val dayWidth get() = hourWidth * HOURS_IN_DAY
    private val gridWidth get() = dayWidth * loadedDays

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: LinearLayout = view.findViewById(R.id.programs)
        var channelPosition: Int = 0
        var bound: Boolean = false
        var filledList: List<Program>? = null
        var filledDay: Long = 0L
        var filledDays: Int = 0
        var filledHighlight: Boolean = false
        var builtFrom: Int = 0
        var builtTo: Int = 0
    }

    fun setData(newChannels: List<Channel>, newPrograms: Map<Long, List<Program>>) {
        channels = newChannels
        programs = newPrograms
        recomputeContentRange()
        // The rows are rebuilt by the data set change right below, so the window only has to be
        // brought up to date here - refilling now would build every row twice.
        updateWindow(refill = false)
        // The cells are rebuilt from scratch, so a selection from the previous data cannot survive.
        clearSelection()
        focusedChannel = RecyclerView.NO_POSITION
        focusedCell = null
        notifyDataSetChanged()
    }

    fun setRange(start: Long, days: Int) {
        if (gridStart == start && loadedDays == days) return
        gridStart = start
        loadedDays = days.coerceIn(1, MAX_DAYS)
        recomputeContentRange()
        updateWindow(refill = false)
        notifyDataSetChanged()
    }

    /** Grows the axis by one day, so scrolling to the right reaches the schedule that follows. */
    fun addDay(): Boolean {
        if (loadedDays >= MAX_DAYS) return false
        loadedDays++
        recomputeContentRange()
        notifyDataSetChanged()
        return true
    }

    /** Start of the axis, in epoch millis. */
    fun rangeStart(): Long = gridStart

    /** Days the axis currently covers. */
    fun rangeDays(): Int = loadedDays

    /** Days the axis currently covers, read from the same field the grid is padded to. */
    fun loadedDays(): Int = loadedDays

    /** Width of the whole axis in pixels, the space a fling can travel through. */
    fun loadedWidth(): Int = gridWidth

    /** True while more days can still be appended to the axis. */
    fun canGrow(): Boolean = loadedDays < MAX_DAYS

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

    /**
     * Range of the axis the rows are built for: the visible part plus [WINDOW_MARGIN_HOURS] on both
     * sides. Rows only hold cells inside it, so the number of cells in a row follows what the window
     * shows instead of the length of the whole range. Without that a row of seven days carried every
     * programme of that channel - hundreds of views - and building them on the way into a new row is
     * what made the vertical scroll stutter.
     */
    private fun updateWindow(refill: Boolean = true) {
        val margin = hourWidth * WINDOW_MARGIN_HOURS
        val from = (offset - margin).coerceAtLeast(0)
        val to = (offset + viewport + margin).coerceAtMost(gridWidth)
        if (from == windowFrom && to == windowTo) return
        windowFrom = from
        windowTo = to
        if (refill) refillRows()
    }

    /** Rebuilds the attached rows whose built range no longer covers the window. */
    private fun refillRows() {
        for (holder in rows) {
            if (holder.builtFrom <= windowFrom && holder.builtTo >= windowTo) continue
            fill(holder, holder.filledList.orEmpty())
        }
    }

    fun hourWidthPx(): Int = hourWidth

    /**
     * Marks one cell as the current pick and reports it upwards, so the caller can fill the info
     * panel and decide what a second activation should do. Only one cell is selected at a time.
     */
    fun select(channelPosition: Int, program: Program) {
        if (isSelected(channelPosition, program)) return
        selectedChannel = channelPosition
        selectedCell = program
        applyHighlight()
        onCellHighlighted(channelPosition, program)
    }

    fun clearSelection() {
        if (selectedChannel == RecyclerView.NO_POSITION && selectedCell == null) return
        selectedChannel = RecyclerView.NO_POSITION
        selectedCell = null
        applyHighlight()
        onCellHighlighted(RecyclerView.NO_POSITION, null)
    }

    /**
     * One programme stands for one cell, even when the objects behind the grid are new ones: the
     * schedule is parsed again on every refresh, and a second tap has to be recognised as the
     * second tap on that very cell. Reference equality alone would lose the pick in between and
     * cost the user another tap before the channel plays.
     */
    private fun sameProgram(first: Program?, second: Program?): Boolean = when {
        first === second -> true
        first == null || second == null -> false
        first.id != 0L && first.id == second.id -> true
        else -> first.start == second.start && first.stop == second.stop && first.title == second.title
    }

    fun isSelected(channelPosition: Int, program: Program): Boolean =
        channelPosition == selectedChannel && sameProgram(selectedCell, program)

    fun selectedChannelPosition(): Int = selectedChannel

    fun selectedProgram(): Program? = selectedCell

    /** Row that holds the remote crosshair inside the grid. */
    fun focusedChannelPosition(): Int = focusedChannel

    fun focusedProgram(): Program? = focusedCell

    /** True while the crosshair really sits on that cell, not only when focus was asked for. */
    fun holdsFocusAt(channelPosition: Int, program: Program): Boolean =
        focusedChannel == channelPosition && focusedCell === program

    /**
     * True while a cell exists below or above the crosshair, no matter whether its row is on
     * screen already. The grid therefore scrolls exactly like the channel column beside it - both
     * lists move together - and only the real end of the list is a wall, where the remote has to be
     * kept inside the grid instead of walking off into the bars around it.
     */
    fun hasCellBelow(): Boolean = hasCellInDirection(1)

    fun hasCellAbove(): Boolean = hasCellInDirection(-1)

    /**
     * Row a vertical step lands on: the nearest one in that direction that carries a programme, which
     * is the row the focus search enters. Rows without anything on the axis are stepped over, and
     * [RecyclerView.NO_POSITION] is returned at the real end of the list.
     */
    fun nextChannelPosition(step: Int): Int {
        val row = focusedChannel
        if (row == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        return channelPositionIn(step, row + step)
    }

    /**
     * Nearest row that carries a cell, starting at [from] and walking in [step]. A row without
     * anything on the axis is stepped over, exactly like the focus search steps over it.
     */
    fun channelPositionIn(step: Int, from: Int): Int {
        var position = from
        while (position in channels.indices) {
            if (hasAnyCell(position)) return position
            position += step
        }
        return RecyclerView.NO_POSITION
    }

    /** False while the crosshair is not on any cell, e.g. after the focus left the grid. */
    fun hasCrosshair(): Boolean = focusedChannel != RecyclerView.NO_POSITION

    /** Programme of a row that is on air at [time], or null when that row carries nothing there. */
    fun programOnAirAt(channelPosition: Int, time: Long): Program? {
        val channel = channels.getOrNull(channelPosition) ?: return null
        return programs[channel.id].orEmpty().firstOrNull { time in it.start until it.stop }
    }

    /** First programme of a row that lies on the axis, or null when the row is empty. */
    fun firstProgramAt(channelPosition: Int): Program? {
        val channel = channels.getOrNull(channelPosition) ?: return null
        val end = gridStart + loadedDays * DAY_MS
        return programs[channel.id].orEmpty().firstOrNull { program ->
            program.stop > gridStart && program.start < end &&
                pixelForTime(program.stop) > 0 && pixelForTime(program.start) < gridWidth
        }
    }

    /**
     * Programme [step] places along the row of [channelPosition] from [program], or null when the
     * row ends in that direction. Gaps between programmes are stepped over, so the crosshair always
     * lands on something that exists instead of stopping at a hole.
     */
    fun programBeside(channelPosition: Int, program: Program, step: Int): Program? {
        val channel = channels.getOrNull(channelPosition) ?: return null
        val list = programs[channel.id].orEmpty()
        val index = list.indexOfFirst { it === program || it.start == program.start }
        if (index < 0) return null
        var cursor = index + step
        while (cursor in list.indices) {
            val candidate = list[cursor]
            if (pixelForTime(candidate.stop) > 0 && pixelForTime(candidate.start) < gridWidth) {
                return candidate
            }
            cursor += step
        }
        return null
    }

    private fun hasCellInDirection(step: Int): Boolean =
        nextChannelPosition(step) != RecyclerView.NO_POSITION

    /** True while the row carries at least one programme of the day that is on the axis. */
    private fun hasAnyCell(position: Int): Boolean {
        val channel = channels.getOrNull(position) ?: return false
        val end = gridStart + loadedDays * DAY_MS
        return programs[channel.id].orEmpty().any { program ->
            program.stop > gridStart && program.start < end &&
                pixelForTime(program.stop) > 0 && pixelForTime(program.start) < gridWidth
        }
    }

    /**
     * Pixels the time axis has to travel before the cell under the crosshair is fully on screen.
     *
     * The sign belongs to the movement of the grid: a cell hanging over the right edge moves it
     * towards the present, a cell hanging over the left edge moves it back into the past, and the
     * cell is never pushed further than that - a long programme does not throw the view across the
     * day. Zero when the cell already fits.
     *
     * It is asked for the cell the crosshair really landed on, not for the direction of the key:
     * the focus moves while the key event is still being handled, so the cell the user is heading
     * for is only known once the cell reports it.
     */
    fun revealShiftForFocusedCell(): Int {
        val program = focusedCell ?: return 0
        return offsetToReveal(program) - offset
    }

    /**
     * The offset at which [program] is fully inside the viewport, or the current one when it already
     * is. It is what [revealShiftForFocusedCell] asks for a cell that has the focus, and it is asked
     * *before* focusing too: a programme outside the built window has no cell to receive the focus,
     * so the axis has to be moved onto it first or the remote would be sent to a cell that does not
     * exist yet and the crosshair would never land.
     */
    fun offsetToReveal(program: Program): Int {
        if (viewport <= 0) return offset
        val margin = cellMargin * 2
        val start = pixelForTime(program.start)
        val stop = pixelForTime(program.stop)
        return when {
            stop - offset > viewport - margin -> stop - (viewport - margin)
            start - offset < margin -> start - margin
            else -> offset
        }
    }

    /**
     * Hands input focus to one cell of the grid. Touch mode refuses focus for plain focusable views,
     * so the cell has to ask for it in touch mode as well - otherwise OK would never reach it and
     * there would be no way to confirm a pick with a remote.
     *
     * Returns false when the row is not on screen (yet), so the caller can come back after the next
     * layout instead of guessing.
     */
    fun requestFocusOnCell(channelPosition: Int, program: Program): Boolean {
        for (holder in rows) {
            if (holder.channelPosition != channelPosition) continue
            val content = holder.content
            for (index in 0 until content.childCount) {
                val cell = content.getChildAt(index)
                if (cell.tag !== program) continue
                cell.isFocusableInTouchMode = true
                return cell.requestFocus()
            }
        }
        return false
    }

    /** Restyles the cells of the attached rows after the pick, the crosshair or the clock changed. */
    private fun applyHighlight() {
        for (holder in rows) {
            val content = holder.content
            for (index in 0 until content.childCount) {
                val cell = content.getChildAt(index)
                val program = cell.tag as? Program ?: continue
                val selected = isSelected(holder.channelPosition, program)
                val focused = focusedChannel == holder.channelPosition && focusedCell === program
                cell.setBackgroundResource(backgroundFor(program, selected, focused))
                cell.isSelected = selected
            }
        }
    }

    private fun backgroundFor(program: Program, selected: Boolean, focused: Boolean): Int = when {
        selected -> R.drawable.bg_epg_selected
        focused -> R.drawable.bg_epg_focus
        highlightCurrent && System.currentTimeMillis() in program.start until program.stop ->
            R.drawable.bg_epg_now
        else -> R.drawable.bg_epg_cell
    }

    fun pixelForTime(time: Long): Int = ((time - gridStart) / 3_600_000f * hourWidth).toInt()

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
        val steps = gridWidth / step + 1
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
            val from = pixelForTime(program.start).coerceIn(0, gridWidth)
            val to = pixelForTime(program.stop).coerceIn(0, gridWidth)
            from..to
        }
        if (spans.isEmpty()) null else spans
    }

    /** Shared horizontal position of the whole grid, in pixels from the start of the day. */
    fun setOffset(pixel: Int) {
        offset = pixel.coerceIn(minOffset(), maxOffset())
        val shift = -offset.toFloat()
        rows.forEach { it.content.translationX = shift }
        // Rows keep the cells of the range they were built for, so they are rebuilt once the window
        // has moved out of it. This is a plain layout pass over the attached rows, which is far
        // cheaper than holding every programme of every loaded day in every row.
        updateWindow()
        onOffsetChanged()
    }

    /**
     * The scrollable window follows the programmes that actually exist, not the length of the
     * day. Stalker usually has no EPG before the early morning, so a window bounded by midnight
     * would let a fling scroll into hours that are empty for every channel.
     */
    private fun recomputeContentRange() {
        var from = gridWidth
        var to = 0
        val end = gridStart + loadedDays * DAY_MS
        for (list in programs.values) {
            for (program in list) {
                if (program.stop <= gridStart || program.start >= end) continue
                from = minOf(from, pixelForTime(program.start).coerceAtLeast(0))
                to = maxOf(to, pixelForTime(program.stop))
            }
        }
        contentStart = if (to > from) from.coerceIn(0, gridWidth) else 0
        contentEnd = if (to > from) to.coerceIn(0, gridWidth) else gridWidth
    }

    private fun minOffset(): Int = contentStart.coerceIn(0, (gridWidth - viewport).coerceAtLeast(0))

    private fun maxOffset(): Int =
        (contentEnd - viewport).coerceAtLeast(0).coerceAtLeast(minOffset())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false)
        hourWidth = parent.resources.getDimensionPixelSize(R.dimen.epg_hour_width)
        cellMargin = parent.resources.getDimensionPixelSize(R.dimen.epg_cell_margin)
        return RowHolder(view)
    }

    override fun getItemCount(): Int = channels.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val channel = channels[position]
        val list = programs[channel.id].orEmpty()
        holder.channelPosition = position
        if (holder.content.childCount == 0 ||
            holder.filledList !== list ||
            holder.filledDay != gridStart ||
            holder.filledDays != loadedDays ||
            holder.filledHighlight != highlightCurrent ||
            holder.builtFrom > windowFrom ||
            holder.builtTo < windowTo
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
        // The row is padded out to the full range anyway, so that filler costs one empty view.
        for (index in content.childCount - 1 downTo 0) {
            val child = content.getChildAt(index)
            if (child.tag is Program && cellPool.size < CELL_POOL_MAX) cellPool += child
            content.removeViewAt(index)
        }
        var cursor = windowFrom
        if (cursor > 0) addFiller(content, cursor)
        for (program in list) {
            val from = pixelForTime(program.start)
            val to = pixelForTime(program.stop)
            // Outside the built range on either side: the row keeps its place on the axis through the
            // padding, so what is skipped here is never looked at until the row is built again. The
            // cell the crosshair sits on is the one exception, it has to survive every rebuild or the
            // remote would lose its place in the middle of a scroll.
            val focused = holder.channelPosition == focusedChannel && program === focusedCell
            if (!focused &&
                (to <= windowFrom || from >= windowTo || from >= gridWidth)
            ) continue
            val start = from.coerceIn(0, gridWidth)
            // Cells are placed at their real start time instead of being laid out back to back,
            // otherwise the row drifts against the time axis and the current time marker ends up
            // in the wrong place. Gaps between programmes become invisible filler.
            if (start < cursor) cursor = start
            if (start > cursor) {
                addFiller(content, start - cursor)
                cursor = start
            }
            val right = if (focused) gridWidth else windowTo
            val width = (to.coerceIn(start + 1, right) - start).coerceAtLeast(1)
            val cell = takeCell(inflater, content)
            cell.layoutParams = LinearLayout.LayoutParams(
                (width - margin).coerceAtLeast(1),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // The programme travels with the cell as its tag, which is what lets a selection be
            // restyled later without keeping a second map of every view.
            cell.tag = program
            cell.findViewById<TextView>(R.id.program_title).text = program.title
            cell.findViewById<TextView>(R.id.program_time).text =
                Fmt.time(program.start) + " - " + Fmt.time(program.stop)
            cell.setBackgroundResource(
                backgroundFor(
                    program,
                    isSelected(holder.channelPosition, program),
                    focusedChannel == holder.channelPosition && focusedCell === program,
                )
            )
            cell.setOnClickListener { onProgramClick(holder.channelPosition, program) }
            // Every cell is a focus target, so a remote can walk the grid, and the cell that holds
            // the crosshair is what the info panel describes.
            cell.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusedChannel = holder.channelPosition
                    focusedCell = program
                    applyHighlight()
                    onCellHighlighted(holder.channelPosition, program)
                    onCellFocused(holder.channelPosition, program)
                } else if (focusedChannel == holder.channelPosition && focusedCell === program) {
                    focusedChannel = RecyclerView.NO_POSITION
                    focusedCell = null
                    applyHighlight()
                    onCellHighlighted(RecyclerView.NO_POSITION, null)
                }
            }
            content.addView(cell)
            cursor = start + width
        }
        // Pad the row out to the full loaded range so every row shares one scroll range.
        if (gridWidth > cursor) addFiller(content, gridWidth - cursor)
        content.layoutParams = content.layoutParams.apply { width = gridWidth }
        holder.filledList = list
        holder.filledDay = gridStart
        holder.filledDays = loadedDays
        holder.filledHighlight = highlightCurrent
        holder.builtFrom = windowFrom
        holder.builtTo = windowTo
    }

    /** A cell from the pool when there is one, a fresh view otherwise. */
    private fun takeCell(inflater: LayoutInflater, parent: ViewGroup): View =
        if (cellPool.isEmpty()) inflater.inflate(R.layout.item_epg_program, parent, false)
        else cellPool.removeAt(cellPool.size - 1)

    private fun addFiller(content: LinearLayout, width: Int) {
        val filler = View(content.context)
        content.addView(filler, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val HOURS_IN_DAY = 24
        const val INITIAL_DAYS = 2
        const val MAX_DAYS = 7
        /** Hours of the axis built around the visible window on both sides. */
        private const val WINDOW_MARGIN_HOURS = 2
        private const val CELL_POOL_MAX = 120
    }
}
