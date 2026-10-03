package com.tvibro.ui.playlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.base.applyFocusScale
import com.tvibro.base.toast
import com.tvibro.base.visible
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.model.EpgSource
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.PlaylistType
import com.tvibro.data.source.LocalFile
import com.tvibro.data.source.M3uParser
import com.tvibro.data.source.StalkerApi
import com.tvibro.data.source.XtreamApi
import com.tvibro.ui.common.Dialogs
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The one form for a playlist. Adding starts at the type, editing goes straight to the fields with
 * the stored values in them, so a playlist is described in one place only. Changing the type in edit
 * mode keeps every value that still belongs to the new type.
 */
class PlaylistWizardActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var typeView: TextView
    private lateinit var fieldsView: LinearLayout
    private lateinit var fieldsScroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var testButton: View
    private lateinit var okButton: TextView

    private var type: PlaylistType = PlaylistType.REMOTE_M3U
    private var onTypeStep = true
    private val inputs = HashMap<Int, EditText>()

    /** Reference of the picked playlist file, kept apart from the fields: it is not typed. */
    private var playlistFile = ""
    private var playlistFileView: TextView? = null

    /**
     * Asks the system for the playlist file.
     *
     * The grant that comes with the answer dies with the process, and a playlist is read again on
     * every update, so it is asked to outlive that. Not every provider agrees to that, and the user
     * is better off knowing that the file will have to be picked again than finding out on the day
     * the playlist stops updating.
     */
    private val pickPlaylistFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        playlistFile = uri.toString()
        if (!LocalFile.keepPermission(this, uri)) toast(getString(R.string.file_access_not_kept))
        showChosenFile()
    }

    /** Playlist being edited, null while a new one is being added. */
    private var editing: Playlist? = null
    private var draft = Draft()

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-wizard").apply { isDaemon = true } }

    private class Draft(
        var name: String = "",
        var url: String = "",
        var login: String = "",
        var password: String = "",
        var mac: String = "",
        var epg: String = "",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)
        titleView = findViewById(R.id.wizard_title)
        messageView = findViewById(R.id.wizard_message)
        typeView = findViewById(R.id.wizard_type)
        fieldsView = findViewById(R.id.wizard_fields)
        fieldsScroll = findViewById(R.id.wizard_fields_scroll)
        statusView = findViewById(R.id.wizard_status)
        testButton = findViewById(R.id.button_test)
        okButton = findViewById(R.id.button_ok)
        findViewById<View>(R.id.button_cancel).setOnClickListener { finish() }
        testButton.setOnClickListener { testConnection() }
        okButton.setOnClickListener {
            if (onTypeStep) showTypeStep() else save()
        }
        typeView.setOnClickListener { chooseType() }
        // a dialog must not be attached before the activity window has a token
        window.decorView.post { if (!isFinishing) start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    /**
     * Editing reads the playlist and its EPG binding off the main thread: the screen has to open
     * with the real values, and the query must not stand in the way of that.
     */
    private fun start() {
        val playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, 0L)
        if (playlistId <= 0L) {
            showTypeStep()
            return
        }
        executor.execute {
            val repo = TvBroApp.repo(this)
            val playlist = runCatching { repo.playlist(playlistId) }.getOrNull()
            val epg = runCatching {
                // every binding, not only the enabled ones: a disabled source is still the one the
                // field belongs to, and it is updated instead of duplicated.
                val bound = repo.epgSources().firstOrNull { it.playlistId == playlistId }?.url.orEmpty()
                if (bound.isNotBlank()) bound else playlist?.epgUrl.orEmpty()
            }.getOrDefault("")
            runOnUiThread {
                if (isFinishing || playlist == null) {
                    if (playlist == null) toast(getString(R.string.channel_is_unavailable))
                    finish()
                    return@runOnUiThread
                }
                editing = playlist
                type = playlist.type
                if (type == PlaylistType.FILE) playlistFile = playlist.url
                draft = Draft(
                    name = playlist.name,
                    url = playlist.url,
                    login = playlist.login,
                    password = playlist.password,
                    mac = playlist.mac,
                    epg = epg,
                )
                showDetailsStep()
            }
        }
    }

    private fun showTypeStep() {
        onTypeStep = true
        clearFields()
        titleView.setText(R.string.playlist_type)
        messageView.setText(R.string.select_playlist_type)
        messageView.visible(true)
        typeView.visible(false)
        testButton.visible(false)
        okButton.setText(R.string.next)
        val items = listOf(
            Dialogs.Item(getString(R.string.m3u_playlist)),
            Dialogs.Item(getString(R.string.xtream_codes)),
            Dialogs.Item(getString(R.string.stalker_portal)),
            Dialogs.Item(getString(R.string.local_file)),
        )
        Dialogs.show(this, getString(R.string.playlist_type), getString(R.string.select_playlist_type), items) { which ->
            type = typeOrder[which.coerceIn(0, typeOrder.lastIndex)]
            showDetailsStep()
        }
    }

    private fun showDetailsStep() {
        onTypeStep = false
        // A re-render happens when the type changes, so what is typed right now has to be kept.
        if (inputs.isNotEmpty()) captureDraft()
        clearFields()
        when (type) {
            PlaylistType.FILE -> {
                titleView.setText(if (editing != null) R.string.edit_playlist else R.string.local_file_parameters)
                addField(R.string.playlist_name, InputType.TYPE_CLASS_TEXT, draft.name.ifBlank { defaultName() })
                addFileRow()
                addField(R.string.epg_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, draft.epg, optional = true)
                messageView.setText(R.string.local_file_hint)
            }
            PlaylistType.REMOTE_M3U -> {
                titleView.setText(if (editing != null) R.string.edit_playlist else R.string.m3u_parameters)
                addField(R.string.playlist_name, InputType.TYPE_CLASS_TEXT, draft.name.ifBlank { defaultName() })
                addField(R.string.playlist_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, draft.url)
                addField(R.string.epg_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, draft.epg, optional = true)
                messageView.setText(R.string.m3u_parameters_hint)
            }
            PlaylistType.XTREAM -> {
                titleView.setText(if (editing != null) R.string.edit_playlist else R.string.xtream_parameters)
                addField(R.string.playlist_name, InputType.TYPE_CLASS_TEXT, draft.name.ifBlank { defaultName() })
                addField(R.string.server_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, draft.url.ifBlank { "http://" })
                addField(R.string.username, InputType.TYPE_CLASS_TEXT, draft.login)
                addField(R.string.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, draft.password, password = true)
                addField(R.string.epg_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, draft.epg, optional = true)
                messageView.setText(R.string.xtream_parameters_hint)
            }
            PlaylistType.STALKER -> {
                titleView.setText(if (editing != null) R.string.edit_playlist else R.string.stalker_parameters)
                addField(R.string.playlist_name, InputType.TYPE_CLASS_TEXT, draft.name.ifBlank { defaultName() })
                addField(R.string.server_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, draft.url.ifBlank { "http://" })
                addField(R.string.mac_address, InputType.TYPE_CLASS_TEXT, draft.mac.ifBlank { "00:1A:79:00:00:00" })
                messageView.setText(R.string.stalker_parameters_hint)
            }
        }
        messageView.visible(true)
        capFieldsToWindow()
        // Only editing can change the type of a stored playlist, and there it is offered right on
        // the form: picking another one re-renders the fields and keeps the values that still fit.
        typeView.visible(editing != null)
        updateTypeLabel()
        testButton.visible(true)
        okButton.setText(if (editing != null) R.string.save else R.string.add)
    }

    /**
     * Keeps the whole form inside the window.
     *
     * The window is a floating one, so its height follows its content - and the content grows with
     * the fields of the chosen type. A portal playlist with a large font did not fit on the tablet:
     * the bottom of the window was cut off and the buttons, the last thing in the layout, were
     * clipped to their top edge. The fields are the part that may give way, so the scroller around
     * them is shortened by exactly the overflow and never below one field: the form stays complete,
     * the button row keeps its full height, and the rest is reached by scrolling.
     *
     * Measured after the layout, because only then the height the fields ask for is known.
     */
    private fun capFieldsToWindow() {
        fieldsScroll.post {
            val root = findViewById<ViewGroup>(R.id.wizard_root)
            // Everything but the fields: they are the part that gives way. The scroller measures the
            // fields without a limit, so their height is the height the form would like to have.
            var chrome = root.paddingTop + root.paddingBottom
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                val params = child.layoutParams as? ViewGroup.MarginLayoutParams
                val margins = (params?.topMargin ?: 0) + (params?.bottomMargin ?: 0)
                chrome += if (child === fieldsScroll) margins else child.height + margins
            }
            val room = (resources.displayMetrics.heightPixels * WINDOW_MAX_HEIGHT).toInt() - chrome
            // Never below one field: a sliver of a scroller is worse than a window slightly too tall.
            val oneField = if (fieldsView.childCount > 0) fieldsView.getChildAt(0).height else 0
            val floor = oneField.coerceAtLeast(fieldsScroll.minimumHeight)
            val height = fieldsView.height.coerceAtMost(room.coerceAtLeast(floor))
            if (fieldsScroll.height == height) return@post
            fieldsScroll.updateLayoutParams { this.height = height }
        }
    }

    private fun updateTypeLabel() {
        typeView.text = getString(R.string.playlist_type) + ": " + getString(typeLabel(type))
    }

    private fun chooseType() {
        val items = listOf(
            Dialogs.Item(getString(R.string.m3u_playlist), checked = type == PlaylistType.REMOTE_M3U),
            Dialogs.Item(getString(R.string.xtream_codes), checked = type == PlaylistType.XTREAM),
            Dialogs.Item(getString(R.string.stalker_portal), checked = type == PlaylistType.STALKER),
            Dialogs.Item(getString(R.string.local_file), checked = type == PlaylistType.FILE),
        )
        Dialogs.show(this, getString(R.string.playlist_type), getString(R.string.select_playlist_type), items) { which ->
            type = typeOrder[which.coerceIn(0, typeOrder.lastIndex)]
            showDetailsStep()
        }
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

    /**
     * The row that holds the picked playlist file.
     *
     * It shows the name the file has on the device and keeps the reference to itself: a
     * `content://` reference says nothing to a person and there is no field to type one into. The
     * name is all that is written down, the whole reference stays where the picker left it.
     */
    private fun addFileRow() {
        val ctx = this
        val label = TextView(this).apply {
            setText(getString(R.string.playlist_file))
            TextViewCompat.setTextAppearance(this, R.style.TvBro_Text_Time)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        val value = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = ctx.resources.getDimensionPixelSize(R.dimen.spacing_md) }
            TextViewCompat.setTextAppearance(this, R.style.TvBro_Text_Time)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        playlistFileView = value
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(value)
            addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = ctx.resources.getDimensionPixelSize(R.dimen.spacing_md) }
                TextViewCompat.setTextAppearance(this, R.style.TvBro_NavItemLabel)
                setBackgroundResource(R.drawable.bg_group_item)
                applyFocusScale()
                setOnClickListener { pickPlaylistFile.launch(ANY_FILE) }
                setText(R.string.choose_file)
            })
        }
        fieldsView.addView(label)
        fieldsView.addView(row)
        showChosenFile()
    }

    /** Writes the picked file, or the absence of one, into the row. */
    private fun showChosenFile() {
        val view = playlistFileView ?: return
        val picked = playlistFile.isNotBlank()
        view.setText(
            if (picked) LocalFile.displayName(this, playlistFile) else getString(R.string.no_file_selected)
        )
        view.setTextColor(
            ContextCompat.getColor(this, if (picked) R.color.text_primary else R.color.text_secondary)
        )
    }

    private fun clearFields() {
        fieldsView.removeAllViews()
        inputs.clear()
        playlistFileView = null
        statusView.visible(false)
        statusView.text = ""
    }

    private fun captureDraft() {
        draft = Draft(
            name = value(R.string.playlist_name),
            url = when (type) {
                PlaylistType.REMOTE_M3U -> value(R.string.playlist_url)
                PlaylistType.FILE -> playlistFile
                else -> value(R.string.server_address)
            },
            login = value(R.string.username),
            password = secret(R.string.password),
            mac = value(R.string.mac_address),
            epg = value(R.string.epg_url),
        )
    }

    private fun value(res: Int): String = inputs[res]?.text?.toString()?.trim().orEmpty()

    private fun secret(res: Int): String = inputs[res]?.text?.toString().orEmpty()

    private fun server(): String = value(R.string.server_address).trimEnd('/')

    private fun testConnection() {
        val target = serverOrUrl()
        if (target.isBlank()) {
            toast(getString(if (type == PlaylistType.FILE) R.string.playlist_file_required else R.string.url_required))
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
                    PlaylistType.FILE -> {
                        val parsed = M3uParser.parse(LocalFile.open(this, target), null)
                        getString(R.string.channels_found, parsed.channels.size)
                    }
                    else -> {
                        val parsed = M3uParser.parseUrl(target)
                        getString(R.string.channels_found, parsed.channels.size)
                    }
                }
            }.getOrElse { it.message ?: getString(R.string.connection_failed) }
            runOnUiThread { statusView.text = message }
        }.start()
    }

    private fun serverOrUrl(): String = when (type) {
        PlaylistType.REMOTE_M3U -> value(R.string.playlist_url)
        PlaylistType.FILE -> playlistFile
        else -> server()
    }

    private fun save() {
        val target = serverOrUrl()
        if (target.isBlank()) {
            toast(getString(if (type == PlaylistType.FILE) R.string.playlist_file_required else R.string.url_required))
            return
        }
        captureDraft()
        okButton.isEnabled = false
        statusView.visible(true)
        statusView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        statusView.text = getString(R.string.processing)
        val playlist = editing
        val name = draft.name.ifBlank { defaultName() }
        // A type has its own set of values, so switching the type must not leave the values of the
        // old one behind in the record.
        val login = if (type == PlaylistType.XTREAM) draft.login else ""
        val password = if (type == PlaylistType.XTREAM) draft.password else ""
        val mac = if (type == PlaylistType.STALKER) draft.mac else ""
        Thread {
            val result = runCatching {
                val repo = TvBroApp.repo(this)
                val playlistId = if (playlist == null) {
                    repo.insertPlaylist(
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
                } else {
                    repo.updatePlaylist(
                        playlist.copy(
                            name = name,
                            type = type,
                            url = target,
                            login = login,
                            password = password,
                            mac = mac,
                        )
                    )
                    playlist.id
                }
                bindEpg(repo, playlistId, name)
                val channels = when (type) {
                    PlaylistType.XTREAM -> XtreamApi(server(), login, password).loadChannels()
                    PlaylistType.STALKER -> StalkerApi(server(), mac.ifBlank { null }).loadChannels()
                    PlaylistType.FILE -> M3uParser.parse(
                        LocalFile.open(this@PlaylistWizardActivity, target),
                        null,
                    ).channels
                    else -> M3uParser.parseUrl(target).channels
                }
                if (channels.isEmpty()) error(getString(R.string.playlist_update_failed))
                repo.replaceChannels(playlistId, channels)
                repo.setPlaylistLastUpdate(playlistId, System.currentTimeMillis())
                // The EPG is bound a few lines above, so this is the one moment where its url is
                // known and has never been downloaded. It runs on the update thread, so the
                // wizard does not have to wait for it.
                TvBroApp.get().sources.refreshEpgOnChange(playlistId)
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

    /**
     * The EPG url of the form belongs to the playlist: an existing binding is pointed at the new
     * address, a new one is added, and an empty field leaves the bindings alone instead of dropping
     * an EPG the user set up elsewhere.
     */
    private fun bindEpg(repo: TvBroRepository, playlistId: Long, name: String) {
        val url = draft.epg.trim()
        if (url.isBlank()) return
        val existing = repo.epgSources().firstOrNull { it.playlistId == playlistId }
        if (existing == null) {
            repo.insertEpgSource(EpgSource(name = name, url = url, playlistId = playlistId))
        } else {
            repo.updateEpgSource(existing.copy(name = name, url = url, enabled = true))
        }
    }

    private fun defaultName(): String = when (type) {
        PlaylistType.REMOTE_M3U -> getString(R.string.m3u_playlist)
        PlaylistType.XTREAM -> getString(R.string.xtream_codes)
        PlaylistType.STALKER -> getString(R.string.stalker_portal)
        PlaylistType.FILE -> getString(R.string.local_file)
    }.lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }

    private fun typeLabel(type: PlaylistType): Int = when (type) {
        PlaylistType.REMOTE_M3U -> R.string.m3u_playlist
        PlaylistType.XTREAM -> R.string.xtream_codes
        PlaylistType.STALKER -> R.string.stalker_portal
        PlaylistType.FILE -> R.string.local_file
    }

    companion object {
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        /** The order the types are offered in, which the dialogs keep to. */
        private val typeOrder = listOf(
            PlaylistType.REMOTE_M3U,
            PlaylistType.XTREAM,
            PlaylistType.STALKER,
            PlaylistType.FILE,
        )

        /**
         * Every file is offered, whatever the system thinks it is: an m3u off a device is served as
         * a plain file, as an m3u8 playlist or as nothing in particular, and a filter that only let
         * through the tidy names would hide the file the user is pointing at.
         */
        private val ANY_FILE = arrayOf("*/*")

        /**
         * Part of the screen height the window may take at most. The floating window follows its
         * content, so without a bound the form grows until its bottom is off the screen.
         */
        private const val WINDOW_MAX_HEIGHT = 0.9f

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, PlaylistWizardActivity::class.java))
        }

        fun startEdit(activity: Activity, playlistId: Long) {
            activity.startActivity(
                Intent(activity, PlaylistWizardActivity::class.java)
                    .putExtra(EXTRA_PLAYLIST_ID, playlistId)
            )
        }
    }
}
