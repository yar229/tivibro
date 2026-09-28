package com.tvibro.ui.vod

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tvibro.R
import com.tvibro.base.visible
import com.tvibro.data.model.Channel

class VodAdapter(
    private val context: Context,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<VodAdapter.Holder>() {

    private var items: List<Channel> = emptyList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.poster)
        val inList: ImageView = view.findViewById(R.id.in_my_list)
        val title: TextView = view.findViewById(R.id.item_title)
        val info: TextView = view.findViewById(R.id.item_info)
    }

    fun submit(newItems: List<Channel>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun itemAt(index: Int): Channel? = items.getOrNull(index)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        val meta = listOfNotNull(
            item.vodYear.takeIf { it.isNotBlank() },
            item.vodRating.takeIf { it.isNotBlank() && it != "0.0" }
        ).joinToString(" · ")
        holder.info.visible(meta.isNotEmpty())
        holder.info.text = meta
        holder.inList.visible(item.inMyList)
        if (item.logoUrl.isNotBlank()) {
            holder.poster.load(item.logoUrl) {
                placeholder(R.drawable.ic_movie)
                error(R.drawable.ic_movie)
                crossfade(true)
            }
        } else {
            holder.poster.setImageResource(R.drawable.ic_movie)
        }
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
    }
}
