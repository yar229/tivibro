package com.tvibro.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.tvibro.data.model.Channel
import com.tvibro.ui.player.PlayerActivity
import java.util.concurrent.Executors

class VodDetailsActivity : AppCompatActivity() {

    private lateinit var series: Channel
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-vod-details").apply { isDaemon = true } }
    private var episodes: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod_details)
        val repo = TvBroApp.repo(this)
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
        if (channelId == 0L) {
            finish()
            return
        }
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        executor.execute {
            val channel = repo.channel(channelId) ?: run {
                runOnUiThread { finish() }
                return@execute
            }
            val list = if (channel.isSeries) {
                repo.episodesOf(channel.seriesName, listOf(channel.playlistId))
            } else {
                listOf(channel)
            }
            runOnUiThread {
                series = channel
                episodes = list
                bind(channel, list)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun bind(channel: Channel, episodes: List<Channel>) {
        findViewById<TextView>(R.id.details_title).text = channel.name
        val meta = listOfNotNull(
            channel.vodYear.takeIf { it.isNotBlank() },
            channel.vodCategory.takeIf { it.isNotBlank() },
            channel.vodRating.takeIf { it.isNotBlank() && it != "0.0" }
        ).joinToString(" · ")
        findViewById<TextView>(R.id.details_info).text = meta
        findViewById<TextView>(R.id.details_plot).text = episodePlot(episodes)
        findViewById<TextView>(R.id.details_cast).text = ""

        val poster = findViewById<ImageView>(R.id.poster)
        if (channel.logoUrl.isNotBlank()) {
            poster.load(channel.logoUrl) {
                placeholder(R.drawable.ic_movie)
                error(R.drawable.ic_movie)
            }
        } else {
            poster.setImageResource(R.drawable.ic_movie)
        }

        findViewById<Button>(R.id.play_button).setOnClickListener {
            playEpisodes(episodes)
        }

        val label = findViewById<TextView>(R.id.episodes_label)
        val list = findViewById<RecyclerView>(R.id.episodes_list)
        if (channel.isSeries && episodes.isNotEmpty()) {
            label.visible(true)
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = EpisodesAdapter(episodes) { index -> playEpisodes(listOf(episodes[index])) }
        } else {
            label.visible(false)
            list.visible(false)
        }
    }

    private fun episodePlot(episodes: List<Channel>): String =
        episodes.firstOrNull()?.name.orEmpty()

    private fun playEpisodes(episodes: List<Channel>) {
        val first = episodes.firstOrNull() ?: return
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, first.id)
        if (episodes.size > 1) {
            intent.putExtra(
                PlayerActivity.EXTRA_CHANNEL_IDS,
                episodes.map { it.id }.toLongArray()
            )
            intent.putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, 0)
        }
        startActivity(intent)
    }

    class EpisodesAdapter(
        private val items: List<Channel>,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<EpisodesAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val logo: ImageView = view.findViewById(R.id.logo)
            val name: TextView = view.findViewById(R.id.channel_name)
            val number: TextView = view.findViewById(R.id.channel_number)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_channel, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val label = Fmt.episodeLabel(item.season, item.episode)
            holder.name.text = if (label.isEmpty()) item.name else "$label · ${item.name}"
            holder.number.visible(item.durationMs > 0)
            holder.number.text = if (item.durationMs > 0) Fmt.duration(item.durationMs) else ""
            if (item.logoUrl.isNotBlank()) {
                holder.logo.load(item.logoUrl) {
                    placeholder(R.drawable.ic_logo_channel)
                    error(R.drawable.ic_logo_channel)
                }
            } else {
                holder.logo.setImageResource(R.drawable.ic_logo_channel)
            }
            holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }
}
