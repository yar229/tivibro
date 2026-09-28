package com.tvibro.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.visible

class GroupsAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<GroupsAdapter.Holder>() {

    private var items: List<Category> = emptyList()
    private var selected = 0

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.group_icon)
        val name: TextView = view.findViewById(R.id.group_name)
        val count: TextView = view.findViewById(R.id.group_count)
    }

    fun submit(newItems: List<Category>, selectedIndex: Int = selected) {
        items = newItems
        selected = selectedIndex.coerceIn(0, (newItems.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        if (index == selected || index !in items.indices) return
        val previous = selected
        selected = index
        notifyItemChanged(previous)
        notifyItemChanged(selected)
    }

    fun selectedIndex(): Int = selected

    fun itemAt(index: Int): Category? = items.getOrNull(index)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.icon)
        holder.name.text = item.name
        holder.count.visible(false)
        holder.itemView.isActivated = position == selected
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
    }
}
