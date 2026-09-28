package com.tvibro.ui.playlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.base.toast
import com.tvibro.base.visible
import com.tvibro.data.model.EpgSource
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.PlaylistType
import com.tvibro.data.source.Http
import com.tvibro.data.source.M3uParser
import com.tvibro.data.source.StalkerApi
import com.tvibro.data.source.XtreamApi
import com.tvibro.ui.common.Dialogs
import java.util.Locale

class PlaylistWizardActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var fieldsView: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var testButton: View
    private lateinit var okButton: TextView

    private var type: PlaylistType = PlaylistType.REMOTE_M3U
    private var onTypeStep = true
    private val inputs = HashMap<Int, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)
        titleView = findViewById(R.id.wizard_title)
        messageView = findViewById(R.id.wizard_message)
        fieldsView = findViewById(R.id.wizard_fields)
        statusView = findViewById(R.id.wizard_status)
        testButton = findViewById(R.id.button_test)
        okButton = findViewById(R.id.button_ok)
        findViewById<View>(R.id.button_cancel).setOnClickListener { finish() }
        testButton.setOnClickListener { testConnection() }
        okButton.setOnClickListener {
            if (onTypeStep) showTypeStep() else save()
        }
        // a dialog must not be attached before the activity window has a token
        window.decorView.post { if (!isFinishing) showTypeStep() }
    }

    private fun showTypeStep() {
        onTypeStep = true
        clearFields()
        titleView.setText(R.string.playlist_type)
        messageView.setText(R.string.select_playlist_type)
        messageView.visible(true)
        testButton.visible(false)
        okButton.setText(R.string.next)
        val items = listOf(
            Dialogs.Item(getString(R.string.m3u_playlist)),
            Dialogs.Item(getString(R.string.xtream_codes)),
            Dialogs.Item(getString(R.string.stalker_portal)),
        )
        Dialogs.show(this, getString(R.string.playlist_type), getString(R.string.select_playlist_type), items) { which ->
            type = listOf(
                PlaylistType.REMOTE_M3U,
                PlaylistType.XTREAM,
                PlaylistType.STALKER,
            )[which.coerceIn(0, 2)]
            showDetailsStep()
        }
    }

    private fun showDetailsStep() {
        onTypeStep = false
        clearFields()
        when (type) {
            PlaylistType.FILE -> Unit
            PlaylistType.REMOTE_M3U -> {
                titleView.setText(R.string.m3u_parameters)
                addField(R.string.playlist_name, InputType.TYPE_CLASS_TEXT, defaultName())
                addField(R.string.playlist_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "")
                addField(R.string.epg_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "", optional = true)
                messageView.setText(R.string.m3u_parameters_hint)
            }
            PlaylistType.XTREAM -> {
                titleView.setText(R.string.xtream_parameters)
                addField(R.string.playlist_name, InputType.TYPE_CLASS_TEXT, defaultName())
                addField(R.string.server_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "http://")
                addField(R.string.username, InputType.TYPE_CLASS_TEXT, "")
                addField(R.string.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "", password = true)
                addField(R.string.epg_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "", optional = true)
                messageView.setText(R.string.xtream_parameters_hint)
            }
            PlaylistType.STALKER -> {
                titleView.setText(R.string.stalker_parameters)
                addField(R.string.playlist_name, InputType.TYPE_CLASS_TEXT, defaultName())
                addField(R.string.server_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "http://")
                addField(R.string.mac_address, InputType.TYPE_CLASS_TEXT, "00:1A:79:00:00:00")
                messageView.setText(R.string.stalker_parameters_hint)
            }
        }
        messageView.visible(true)
        testButton.visible(true)
        okButton.setText(R.string.add)
    }

    private fun addField(labelRes: Int, type: Int, value: String, optional: Boolean = false, password: Boolean = false) {
        val ctx = this
        val label = TextView(this).apply {
            setText(if (optional) getString(R.string.optional_field, getString(labelRes)) else getString(labelRes))
            TextViewCompat.setTextAppearance(this, R.style.TvBro_Text_Time)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        val edit = EditText(this).apply {
            setHint(getString(labelRes))
            inputType = type
            setText(value)
            setSingleLine()
            if (password) tag = "password"
        }
        fieldsView.addView(label)
        fieldsView.addView(edit)
        inputs[labelRes] = edit
    }

    private fun clearFields() {
        fieldsView.removeAllViews()
        inputs.clear()
        statusView.visible(false)
        statusView.text = ""
    }

    private fun value(res: Int): String = inputs[res]?.text?.toString()?.trim().orEmpty()

    private fun secret(res: Int): String = inputs[res]?.text?.toString().orEmpty()

    private fun server(): String = value(R.string.server_address).trimEnd('/')

    private fun testConnection() {
        if (serverOrUrl().isBlank()) {
            toast(getString(R.string.url_required))
            return
        }
        statusView.visible(true)
        statusView.text = getString(R.string.connecting)
        Thread {
            val message = runCatching {
                when (type) {
                    PlaylistType.XTREAM -> {
                        val api = XtreamApi(server(), value(R.string.username), secret(R.string.password))
                        api.authenticate()
                        getString(R.string.connection_successful)
                    }
                    PlaylistType.STALKER -> {
                        val api = StalkerApi(server(), value(R.string.mac_address).ifBlank { null })
                        if (api.login()) getString(R.string.connection_successful) else getString(R.string.connection_failed)
                    }
                    else -> {
                        val parsed = M3uParser.parseUrl(serverOrUrl())
                        getString(R.string.channels_found, parsed.channels.size)
                    }
                }
            }.getOrElse { it.message ?: getString(R.string.connection_failed) }
            runOnUiThread { statusView.text = message }
        }.start()
    }

    private fun serverOrUrl(): String = when (type) {
        PlaylistType.REMOTE_M3U -> value(R.string.playlist_url)
        else -> server()
    }

    private fun save() {
        if (serverOrUrl().isBlank()) {
            toast(getString(R.string.url_required))
            return
        }
        okButton.isEnabled = false
        statusView.visible(true)
        statusView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        statusView.text = getString(R.string.processing)
        val name = value(R.string.playlist_name).ifBlank { defaultName() }
        val epg = value(R.string.epg_url)
        val login = value(R.string.username)
        val password = secret(R.string.password)
        val mac = value(R.string.mac_address)
        val target = serverOrUrl()
        Thread {
            val result = runCatching {
                val repo = TvBroApp.repo(this)
                val playlistId = repo.insertPlaylist(
                    Playlist(
                        name = name,
                        type = type,
                        url = target,
                        login = login,
                        password = password,
                        mac = mac,
                        lastUpdate = 0,
                    )
                )
                if (epg.isNotBlank()) {
                    repo.insertEpgSource(EpgSource(name = name, url = epg, playlistId = playlistId))
                }
                val channels = when (type) {
                    PlaylistType.XTREAM -> XtreamApi(server(), login, password).loadChannels()
                    PlaylistType.STALKER -> StalkerApi(server(), mac.ifBlank { null }).loadChannels()
                    else -> M3uParser.parseUrl(target).channels
                }
                if (channels.isEmpty()) error(getString(R.string.playlist_update_failed))
                repo.replaceChannels(playlistId, channels)
                repo.setPlaylistLastUpdate(playlistId, System.currentTimeMillis())
                channels.size
            }
            runOnUiThread {
                okButton.isEnabled = true
                result.onSuccess { count ->
                    toast(getString(R.string.channels_found, count))
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CHANNELS, count))
                    finish()
                }.onFailure { error ->
                    statusView.text = error.message ?: getString(R.string.playlist_update_failed)
                }
            }
        }.start()
    }

    private fun defaultName(): String = when (type) {
        PlaylistType.REMOTE_M3U -> getString(R.string.m3u_playlist)
        PlaylistType.XTREAM -> getString(R.string.xtream_codes)
        PlaylistType.STALKER -> getString(R.string.stalker_portal)
        PlaylistType.FILE -> getString(R.string.local_file)
    }.lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }

    companion object {
        const val EXTRA_CHANNELS = "channels"

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, PlaylistWizardActivity::class.java))
        }
    }
}
