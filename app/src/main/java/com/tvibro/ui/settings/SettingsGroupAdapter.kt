package com.tvibro.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R

class SettingsGroupAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SettingsGroupAdapter.Holder>() {

    data class Entry(val title: String, val count: String)

    private var items: List<Entry> = emptyList()

    fun submit(newItems: List<Entry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_settings_group, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.count.text = item.count
        holder.itemView.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onClick(index)
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.group_title)
        val count: TextView = view.findViewById(R.id.group_count)
    }
}
