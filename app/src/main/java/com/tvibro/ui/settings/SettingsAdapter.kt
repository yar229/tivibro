package com.tvibro.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.visible

sealed class SettingItem {
    abstract val title: String

    data class Header(override val title: String) : SettingItem()
    data class Switch(
        override val title: String,
        val summary: String = "",
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
    ) : SettingItem()

    data class Choice(
        override val title: String,
        val summary: String = "",
        val entries: List<String>,
        val values: List<String>,
        val get: () -> String,
        val set: (String) -> Unit,
    ) : SettingItem()

    data class Number(
        override val title: String,
        val summary: String = "",
        val min: Int = 0,
        val max: Int = 999,
        val get: () -> Int,
        val set: (Int) -> Unit,
    ) : SettingItem()

    data class Action(
        override val title: String,
        val summary: String = "",
        val onClick: () -> Unit,
    ) : SettingItem()

    data class Value(override val title: String, val summary: String = "") : SettingItem()
}

class SettingsAdapter(
    private val onAction: (SettingItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<SettingItem> = emptyList()

    fun submit(newItems: List<SettingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun notifyChanged(index: Int) {
        notifyItemChanged(index)
    }

    override fun getItemViewType(position: Int): Int = if (items[position] is SettingItem.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_setting_category, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_setting, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderHolder) {
            holder.title.text = (item as SettingItem.Header).title
            return
        }
        holder as ItemHolder
        val context = holder.itemView.context
        holder.itemView.nextFocusLeftId = R.id.settings_groups
        holder.title.text = item.title
        holder.summary.visible(item.summarySafe().isNotEmpty())
        holder.summary.text = item.summarySafe()
        holder.switch.visible(item is SettingItem.Switch)
        holder.value.visible(false)
        holder.chevron.visible(true)

        when (item) {
            is SettingItem.Switch -> {
                holder.switch.isChecked = item.get()
                holder.itemView.setOnClickListener {
                    val newValue = !item.get()
                    item.set(newValue)
                    holder.switch.isChecked = newValue
                    onAction(item)
                }
            }
            is SettingItem.Choice -> {
                val value = item.get()
                holder.value.visible(true)
                holder.value.text = item.entries.getOrNull(item.values.indexOf(value)).orEmpty()
                holder.itemView.setOnClickListener { onAction(item) }
            }
            is SettingItem.Number -> {
                holder.value.visible(true)
                holder.value.text = item.get().toString()
                holder.itemView.setOnClickListener { onAction(item) }
            }
            is SettingItem.Action -> {
                holder.itemView.setOnClickListener { onAction(item) }
            }
            is SettingItem.Value -> {
                holder.itemView.setOnClickListener { }
            }
            is SettingItem.Header -> Unit
        }
        holder.chevron.visible(item !is SettingItem.Switch)
    }

    private fun SettingItem.summarySafe(): String = when (this) {
        is SettingItem.Switch -> summary
        is SettingItem.Choice -> summary
        is SettingItem.Number -> summary
        is SettingItem.Action -> summary
        is SettingItem.Value -> summary
        is SettingItem.Header -> ""
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.category_title)
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.setting_title)
        val summary: TextView = view.findViewById(R.id.setting_summary)
        val value: TextView = view.findViewById(R.id.setting_value)
        val switch: SwitchCompat = view.findViewById(R.id.setting_switch)
        val chevron: ImageView = view.findViewById(R.id.setting_chevron)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
