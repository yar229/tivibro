package com.tvibro.ui.common

import android.app.Activity
import android.content.Context
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import com.tvibro.R
import com.tvibro.base.toast
import com.tvibro.data.Prefs

/** Asks for the PIN code stored in preferences and reports the result through [onSuccess]. */
object PinGate {

    fun require(activity: Activity, onSuccess: () -> Unit) {
        val prefs = Prefs.get(activity)
        if (prefs.pin.isEmpty()) {
            onSuccess()
            return
        }
        Dialogs.input(
            context = activity,
            title = activity.getString(R.string.channel_is_protected),
            value = "",
            hint = activity.getString(R.string.enter_pin),
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            required = true,
        ) { entered, _ ->
            if (entered == prefs.pin) {
                onSuccess()
            } else {
                activity.toast(activity.getString(R.string.wrong_pin))
            }
        }.show()
    }

    fun changePin(context: Context) {
        val prefs = Prefs.get(context)
        Dialogs.input(
            context = context,
            title = context.getString(R.string.pin_code),
            value = prefs.pin,
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            required = true,
        ) { pin, _ ->
            prefs.pin = pin
            context.toast(context.getString(R.string.settings_saved))
        }.show()
    }
}
