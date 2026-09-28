package com.tvibro.ui.guide

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.base.Fmt
import com.tvibro.base.visible
import com.tvibro.data.Prefs
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.model.Channel
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.Program
import com.tvibro.ui.player.PlayerActivity
import java.util.Locale
import java.util.concurrent.Executors

class TvGuideActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: TvBroRepository
    private lateinit var channelsAdapter: GuideChannelsAdapter
    private lateinit var rowsAdapter: GuideRowsAdapter
    private lateinit var daysAdapter: DaysAdapter
    private lateinit var nowLine: View
    private lateinit var statusView: TextView
    private lateinit var channelsList: RecyclerView
    private lateinit var programsList: RecyclerView

    private var syncingRows = false
    private var lastSyncPosition = RecyclerView.NO_POSITION
    private var lastSyncTop = 0

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-guide").apply { isDaemon = true } }
    private var channels: List<Channel> = emptyList()
    private var programs: Map<Long, List<Program>> = emptyMap()
    private var dayStart = 0L
    private var dayIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        repo = TvBroApp.repo(this)
        setContentView(R.layout.activity_guide)
        nowLine = findViewById(R.id.now_line)
        statusView = findViewById(R.id.guide_status)

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        daysAdapter = DaysAdapter { index -> selectDay(index) }
        findViewById<RecyclerView>(R.id.days_list).apply {
            layoutManager = LinearLayoutManager(this@TvGuideActivity, RecyclerView.HORIZONTAL, false)
            adapter = daysAdapter
        }

        channelsAdapter = GuideChannelsAdapter(
            onClick = { position -> playChannel(position) },
            onProgramsLongClick = { position -> showProgramMenu(position) }
        )
        channelsList = findViewById(R.id.channels_list)
        channelsList.apply {
            layoutManager = LinearLayoutManager(this@TvGuideActivity)
            adapter = channelsAdapter
        }

        rowsAdapter = GuideRowsAdapter(
            onProgramClick = { channelIndex, programIndex ->
                val channel = channels.getOrNull(channelIndex) ?: return@GuideRowsAdapter
                val program = programs[channel.id]?.getOrNull(programIndex) ?: return@GuideRowsAdapter
                playProgram(channel, program)
            }
        )
        programsList = findViewById(R.id.programs_rows)
        programsList.apply {
            layoutManager = LinearLayoutManager(this@TvGuideActivity)
            adapter = rowsAdapter
        }

        // The channel column and the program grid are two lists with identical items, so they
        // have to share one vertical scroll position: otherwise a channel ends up next to
        // somebody else's programs and the grid cannot be read at all.
        channelsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                syncVerticalScroll(channelsList, programsList)
            }
        })
        programsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                syncVerticalScroll(programsList, channelsList)
            }
        })

        dayStart = Fmt.startOfDay(System.currentTimeMillis())
        daysAdapter.submit(Fmt.startOfDay(System.currentTimeMillis()), 7)
        loadChannels()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun loadChannels() {
        executor.execute {
            val playlists = repo.playlists(onlyEnabled = true)
            val list = repo.channels(playlists.map { it.id }, "", ChannelFilter.TV, "name")
            val now = System.currentTimeMillis()
            val from = dayStart
            val to = dayStart + 86_400_000L
            val map = if (list.isNotEmpty()) {
                repo.programsForChannels(list.map { it.id }, from, to)
            } else {
                emptyMap()
            }
            runOnUiThread {
                channels = list
                programs = map
                channelsAdapter.submit(list)
                rowsAdapter.setData(list, map)
                statusView.text = getString(R.string.channels_count, list.size)
                positionNowLine()
            }
        }
    }

    /**
     * Mirrors the vertical position of [source] onto [target]. Both lists use the same layout
     * manager and the same item height, so the first visible row and its offset fully describe
     * the position. The applied position is remembered, which keeps the two scroll listeners
     * from bouncing the same change back and forth.
     */
    private fun syncVerticalScroll(source: RecyclerView, target: RecyclerView) {
        if (syncingRows) return
        val sourceLm = source.layoutManager as? LinearLayoutManager ?: return
        val targetLm = target.layoutManager as? LinearLayoutManager ?: return
        val first = sourceLm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val view = source.findViewHolderForAdapterPosition(first)?.itemView ?: return
        val top = view.top
        if (first == lastSyncPosition && top == lastSyncTop) return
        lastSyncPosition = first
        lastSyncTop = top
        syncingRows = true
        targetLm.scrollToPositionWithOffset(first, top)
        syncingRows = false
    }

    private fun selectDay(index: Int) {
        dayIndex = index
        dayStart = Fmt.startOfDay(System.currentTimeMillis()) + index * 86_400_000L
        daysAdapter.select(index)
        rowsAdapter.setDayStart(dayStart)
        loadChannels()
    }

    private fun positionNowLine() {
        val now = System.currentTimeMillis()
        if (now < dayStart || now > dayStart + 86_400_000L) {
            nowLine.visible(false)
            return
        }
        nowLine.visible(true)
        val hourWidth = resources.getDimensionPixelSize(R.dimen.epg_hour_width)
        val offset = ((now - dayStart) / 3_600_000f * hourWidth).toInt()
        nowLine.translationX = offset.toFloat()
        rowsAdapter.scrollToTime(now)
    }

    private fun playChannel(position: Int) {
        val channel = channels.getOrNull(position) ?: return
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        startActivity(intent)
    }

    private fun playProgram(channel: Channel, program: Program) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        startActivity(intent)
    }

    private fun showProgramMenu(channelPosition: Int) {
        val channel = channels.getOrNull(channelPosition) ?: return
        val list = programs[channel.id].orEmpty()
        val now = System.currentTimeMillis()
        val options = list.map { com.tvibro.ui.common.Dialogs.Item(it.title, Fmt.timeRange(it.start, it.stop, Locale.getDefault())) }
        com.tvibro.ui.common.Dialogs.show(this, channel.name, getString(R.string.programs), options) { which ->
            playProgram(channel, list[which])
        }
    }
}

class DaysAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<DaysAdapter.Holder>() {

    private var days: List<Long> = emptyList()
    private var count = 7
    private var selected = 0
    private val locale = Locale.getDefault()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.day_name)
        val date: TextView = view.findViewById(R.id.day_date)
    }

    fun submit(startOfToday: Long, daysCount: Int) {
        count = daysCount
        days = (0 until daysCount).map { startOfToday + it * 86_400_000L }
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        if (index == selected || index !in days.indices) return
        val previous = selected
        selected = index
        notifyItemChanged(previous)
        notifyItemChanged(selected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_day, parent, false))

    override fun getItemCount(): Int = days.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val time = days[position]
        val isToday = position == 0
        holder.name.setText(
            when {
                isToday -> R.string.today
                position == 1 -> R.string.tomorrow
                else -> R.string.yesterday
            }
        )
        if (position > 1) {
            holder.name.text = Fmt.dayName(time, locale)
        }
        holder.date.text = Fmt.date(time, locale)
        holder.itemView.isActivated = position == selected
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }
}

class GuideChannelsAdapter(
    private val onClick: (Int) -> Unit,
    private val onProgramsLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<GuideChannelsAdapter.Holder>() {

    private var items: List<Channel> = emptyList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.channel_name)
        val number: TextView = view.findViewById(R.id.channel_number)
    }

    fun submit(newItems: List<Channel>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_channel, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        holder.name.text = channel.name
        holder.number.visible(channel.number.isNotEmpty())
        holder.number.text = channel.number
        if (channel.logoUrl.isNotBlank()) {
            holder.logo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_logo_channel)
                error(R.drawable.ic_logo_channel)
            }
        } else {
            holder.logo.setImageResource(R.drawable.ic_logo_channel)
        }
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onProgramsLongClick(holder.bindingAdapterPosition)
            true
        }
    }
}

class GuideRowsAdapter(
    private val onProgramClick: (Int, Int) -> Unit,
) : RecyclerView.Adapter<GuideRowsAdapter.RowHolder>() {

    private var channels: List<Channel> = emptyList()
    private var programs: Map<Long, List<Program>> = emptyMap()
    private var hourWidth = 160
    private var dayStart = Fmt.startOfDay(System.currentTimeMillis())
    private val locale = Locale.getDefault()
    private val rows = ArrayList<RecyclerView>()

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val list: RecyclerView = view.findViewById(R.id.programs)
        var scrollBound = false
    }

    fun setData(newChannels: List<Channel>, newPrograms: Map<Long, List<Program>>) {
        channels = newChannels
        programs = newPrograms
        notifyDataSetChanged()
    }

    fun setDayStart(start: Long) {
        dayStart = start
    }

    fun scrollToTime(time: Long) {
        val offset = ((time - dayStart) / 3_600_000f * hourWidth).toInt()
        rows.forEach { it.scrollBy(offset - it.computeHorizontalScrollOffset(), 0) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false)
        hourWidth = parent.resources.getDimensionPixelSize(R.dimen.epg_hour_width)
        return RowHolder(view)
    }

    override fun getItemCount(): Int = channels.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val channel = channels[position]
        val list = programs[channel.id].orEmpty()
        holder.list.layoutManager = LinearLayoutManager(holder.itemView.context, RecyclerView.HORIZONTAL, false)
        holder.list.adapter = ProgramRowAdapter(list, hourWidth, onClick = {
            onProgramClick(position, it)
        })
        if (!holder.scrollBound) {
            holder.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    rows.filter { it !== recyclerView }.forEach { it.scrollBy(dx, 0) }
                }
            })
            holder.itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!rows.contains(holder.list)) rows += holder.list
                }

                override fun onViewDetachedFromWindow(v: View) {
                    rows.remove(holder.list)
                }
            })
            holder.scrollBound = true
        }
    }

    class ProgramRowAdapter(
        private val items: List<Program>,
        private val hourWidth: Int,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ProgramRowAdapter.Holder>() {

        private val locale = Locale.getDefault()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.program_title)
            val time: TextView = view.findViewById(R.id.program_time)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_epg_program, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val program = items[position]
            val durationMin = ((program.stop - program.start) / 60_000L).coerceAtLeast(1L)
            val width = (durationMin / 60f * hourWidth).toInt().coerceAtLeast(hourWidth / 4)
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply { this.width = width }
            holder.title.text = program.title
            holder.time.text = Fmt.time(program.start) + " - " + Fmt.time(program.stop)
            val isCurrent = System.currentTimeMillis() in program.start until program.stop
            holder.itemView.setBackgroundResource(
                if (isCurrent) R.drawable.bg_epg_now else R.drawable.bg_epg_cell
            )
            holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        }
    }
}
