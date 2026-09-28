package com.tvibro.ui.pin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import com.tvibro.R
import com.tvibro.data.Prefs
import com.tvibro.ui.common.PinGate
import com.tvibro.ui.player.PlayerActivity

/** Standalone PIN screen: asks for the code and starts the requested channel on success. */
class PinActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.enter_pin)
        val prefs = Prefs.get(this)
        if (prefs.pin.isEmpty()) {
            playChannel()
            return
        }
        PinGate.require(this) { playChannel() }
    }

    private fun playChannel() {
        val id = intent.getLongExtra(EXTRA_CHANNEL_ID, 0)
        if (id <= 0) {
            finish()
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, id)
        )
        finish()
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"

        fun start(activity: Activity, channelId: Long) {
            val prefs = Prefs.get(activity)
            if (prefs.pin.isEmpty()) {
                activity.startActivity(
                    Intent(activity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channelId)
                )
                return
            }
            activity.startActivity(
                Intent(activity, PinActivity::class.java)
                    .putExtra(EXTRA_CHANNEL_ID, channelId)
            )
        }
    }
}
