package com.tvibro.ui.common

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.applyPanelTransparency
import com.tvibro.base.visible

object Dialogs {

    data class Item(
        val title: String,
        val value: String = "",
        val checked: Boolean = false,
        val enabled: Boolean = true,
    )

    fun show(
        context: Context,
        title: String,
        message: String? = null,
        items: List<Item> = emptyList(),
        onCancel: (() -> Unit)? = null,
        onClick: ((Int) -> Unit)? = null,
    ): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_list, null)
        val dialog = baseDialog(context, view)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        val messageView = view.findViewById<TextView>(R.id.dialog_message)
        if (message.isNullOrEmpty()) {
            messageView.visible(false)
        } else {
            messageView.text = message
        }
        val list = view.findViewById<RecyclerView>(R.id.dialog_list)
        if (items.isEmpty()) {
            list.visible(false)
        } else {
            list.layoutManager = LinearLayoutManager(context)
            list.adapter = DialogRowAdapter(items) { index ->
                dialog.dismiss()
                onClick?.invoke(index)
            }
        }
        dialog.setOnCancelListener { onCancel?.invoke() }
        return dialog.showOnce()
    }

    fun confirm(
        context: Context,
        title: String,
        message: String,
        okText: String = context.getString(R.string.ok),
        onOk: () -> Unit,
    ): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        val dialog = baseDialog(context, view)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        view.findViewById<TextView>(R.id.dialog_message).text = message
        view.findViewById<Button>(R.id.button_ok).text = okText
        view.findViewById<Button>(R.id.button_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.button_ok).setOnClickListener {
            dialog.dismiss()
            onOk()
        }
        return dialog.showOnce()
    }

    fun input(
        context: Context,
        title: String,
        value: String = "",
        hint: String = "",
        secondInput: Boolean = false,
        secondValue: String = "",
        passwordSecond: Boolean = false,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        required: Boolean = false,
        onOk: (String, String) -> Unit,
    ): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        val dialog = baseDialog(context, view)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        val input = view.findViewById<EditText>(R.id.dialog_input)
        val input2 = view.findViewById<EditText>(R.id.dialog_input2)
        input.inputType = inputType
        input.hint = hint
        input.setText(value)
        input.setSelection(value.length)
        input2.visible(secondInput)
        if (secondInput) {
            input2.inputType = if (passwordSecond) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else inputType
            input2.setText(secondValue)
        }
        view.findViewById<Button>(R.id.button_cancel).setOnClickListener { dialog.dismiss() }
        val ok = view.findViewById<Button>(R.id.button_ok)
        ok.setOnClickListener {
            val text = input.text.toString()
            if (required && text.isBlank()) {
                input.error = context.getString(R.string.name_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            onOk(text, input2.text.toString())
        }
        if (secondInput) {
            input2.setOnEditorActionListener { _, _, _ ->
                ok.performClick()
                true
            }
        } else {
            input.setOnEditorActionListener { _, _, _ ->
                ok.performClick()
                true
            }
        }
        return dialog.showOnce()
    }

    fun progress(context: Context, title: String): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        val dialog = baseDialog(context, view)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        view.findViewById<TextView>(R.id.dialog_message).text = context.getString(R.string.please_wait)
        view.findViewById<Button>(R.id.button_ok).visible(false)
        view.findViewById<Button>(R.id.button_cancel).visible(false)
        dialog.setCancelable(false)
        return dialog.showOnce()
    }

    private fun Dialog.showOnce(): Dialog {
        if (!isShowing) show()
        return this
    }

    fun baseDialog(context: Context, view: View): Dialog {
        val dialog = Dialog(context, R.style.Theme_TvBro_Dialog)
        // Every dialog in the app is built from one of the layouts that carry the panel background,
        // so this single call covers all of them.
        view.applyPanelTransparency(context)
        dialog.setContentView(view)
        // Theme.TvBro.Dialog leaves windowCloseOnTouchOutside off and a plain Dialog never turns it
        // on by itself, so without this the only ways out were Back and the back swipe.
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        return dialog
    }

    class DialogRowAdapter(
        private val items: List<Item>,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<DialogRowAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.item_title)
            val value: TextView = view.findViewById(R.id.item_value)
            val check: View = view.findViewById(R.id.item_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_dialog_row, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.value.visible(item.value.isNotEmpty())
            holder.value.text = item.value
            holder.check.visible(item.checked)
            holder.itemView.isEnabled = item.enabled
            holder.itemView.alpha = if (item.enabled) 1f else 0.4f
            holder.itemView.isActivated = item.checked
            holder.itemView.setOnClickListener {
                if (item.enabled) onClick(holder.bindingAdapterPosition)
            }
        }
    }
}
