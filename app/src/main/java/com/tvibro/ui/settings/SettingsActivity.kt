package com.tvibro.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.base.toast
import com.tvibro.data.Prefs
import com.tvibro.data.model.EpgSource
import com.tvibro.ui.common.Dialogs
import com.tvibro.ui.common.PinGate
import com.tvibro.ui.playlist.PlaylistWizardActivity
import com.tvibro.work.EpgUpdateScheduler
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private data class SettingsGroup(val title: String, val items: List<SettingItem>)

    private lateinit var prefs: Prefs
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var groupAdapter: SettingsGroupAdapter
    private lateinit var list: RecyclerView
    private lateinit var panel: View
    private lateinit var scrim: View
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var backButton: View
    private var groups: List<SettingsGroup> = emptyList()
    private var openIndex = NO_GROUP
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_settings)

        panel = findViewById(R.id.settings_panel)
        scrim = findViewById(R.id.settings_scrim)
        titleView = findViewById(R.id.settings_title)
        statusView = findViewById(R.id.settings_status)
        backButton = findViewById(R.id.back_button)
        list = findViewById(R.id.settings_list)

        backButton.setOnClickListener { navigateBack() }
        scrim.setOnClickListener { close() }
        onBackPressedDispatcher.addCallback(this) { navigateBack() }

        settingsAdapter = SettingsAdapter { item -> onItemClick(item) }
        groupAdapter = SettingsGroupAdapter { position -> openGroup(position) }
        list.layoutManager = LinearLayoutManager(this)

        rebuild()
        showRoot(focus = true)
        animateIn()
    }

    private fun animateIn() {
        val animate = prefs.animatedTransition
        panel.doOnPreDraw {
            if (animate) {
                panel.translationX = panel.width.toFloat()
                panel.animate().translationX(0f).setDuration(ENTER_MS).start()
                scrim.animate().alpha(1f).setDuration(ENTER_MS).start()
            } else {
                panel.translationX = 0f
                scrim.alpha = 1f
            }
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        backButton.isEnabled = false
        scrim.isEnabled = false
        if (!prefs.animatedTransition) {
            finish()
            return
        }
        panel.animate()
            .translationX(panel.width.toFloat())
            .setDuration(EXIT_MS)
            .withEndAction { finish() }
            .start()
        scrim.animate().alpha(0f).setDuration(EXIT_MS).start()
    }

    private fun navigateBack() {
        if (closing) return
        if (openIndex != NO_GROUP) showRoot(focus = true) else close()
    }

    private fun rebuild() {
        groups = buildGroups()
        groupAdapter.submit(
            groups.map { SettingsGroupAdapter.Entry(it.title, itemCountLabel(it.settingCount())) }
        )
        if (openIndex == NO_GROUP) {
            showRoot(focus = false)
        } else {
            groups.getOrNull(openIndex)?.let { settingsAdapter.submit(it.items) }
        }
        statusView.text = getString(R.string.app_name) + " " + appVersion()
    }

    private fun showRoot(focus: Boolean) {
        openIndex = NO_GROUP
        titleView.setText(R.string.settings)
        list.adapter = groupAdapter
        scrollToTop(focus)
    }

    private fun openGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        openIndex = index
        titleView.text = group.title
        settingsAdapter.submit(group.items)
        list.adapter = settingsAdapter
        scrollToTop(focus = true)
    }

    private fun scrollToTop(focus: Boolean) {
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        if (!focus) return
        list.post {
            list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: list.requestFocus()
        }
    }

    private fun itemCountLabel(count: Int): String =
        resources.getQuantityString(R.plurals.settings_items_count, count, count)

    private fun SettingsGroup.settingCount(): Int = items.count { it !is SettingItem.Header }

    private fun buildGroups(): List<SettingsGroup> {
        val result = mutableListOf<SettingsGroup>()
        fun group(titleId: Int, block: MutableList<SettingItem>.() -> Unit) {
            val items = mutableListOf<SettingItem>()
            items.block()
            result.add(SettingsGroup(getString(titleId), items))
        }

        group(R.string.general) {
            add(SettingItem.Choice(
                getString(R.string.language),
                entries = resources.getStringArray(R.array.language_entries).toList(),
                values = resources.getStringArray(R.array.language_values).toList(),
                get = { prefs.language },
                set = { prefs.language = it },
            ))
            add(SettingItem.Header(getString(R.string.appearance)))
            add(switchItem(R.string.show_clock) { prefs.showClock })
            add(switchItem(R.string.show_date) { prefs.showDate })
            add(SettingItem.Choice(
                getString(R.string.clock_position),
                entries = listOf(
                    getString(R.string.top_left),
                    getString(R.string.top_right),
                    getString(R.string.bottom_left),
                    getString(R.string.bottom_right),
                ),
                values = listOf("0", "1", "2", "3"),
                get = { prefs.clockPosition.toString() },
                set = { prefs.clockPosition = it.toIntOrNull() ?: 1 },
            ))
            add(SettingItem.Choice(
                getString(R.string.font_size),
                entries = resources.getStringArray(R.array.font_size_entries).toList(),
                values = resources.getStringArray(R.array.font_size_values).toList(),
                get = { prefs.fontScale.toString() },
                set = { prefs.fontScale = it.toFloatOrNull() ?: 1f },
            ))
            add(SettingItem.Choice(
                getString(R.string.font_size_bottom_panel),
                entries = resources.getStringArray(R.array.font_size_entries).toList(),
                values = resources.getStringArray(R.array.font_size_values).toList(),
                get = { prefs.bottomPanelFont.toString() },
                set = { prefs.bottomPanelFont = it.toFloatOrNull() ?: 1f },
            ))
            add(SettingItem.Choice(
                getString(R.string.font_size_channel_panel),
                entries = resources.getStringArray(R.array.font_size_entries).toList(),
                values = resources.getStringArray(R.array.font_size_values).toList(),
                get = { prefs.channelPanelFont.toString() },
                set = { prefs.channelPanelFont = it.toFloatOrNull() ?: 1f },
            ))
            add(SettingItem.Choice(
                getString(R.string.font_size_info_panel),
                entries = resources.getStringArray(R.array.font_size_entries).toList(),
                values = resources.getStringArray(R.array.font_size_values).toList(),
                get = { prefs.infoPanelFont.toString() },
                set = { prefs.infoPanelFont = it.toFloatOrNull() ?: 1f },
            ))
            add(SettingItem.Number(
                getString(R.string.ui_transparency),
                min = 20,
                max = 100,
                get = { prefs.uiTransparency },
                set = { prefs.uiTransparency = it },
            ))
            add(switchItem(R.string.animated_transition) { prefs.animatedTransition })
            add(SettingItem.Header(getString(R.string.updates)))
            add(SettingItem.Number(
                getString(R.string.update_interval),
                min = 1,
                max = 168,
                get = { prefs.updateIntervalHours },
                set = { prefs.updateIntervalHours = it },
            ))
            add(switchItem(R.string.update_on_app_start) { prefs.updateOnStart })
            add(switchItem(R.string.update_on_playlists_change) { prefs.updateOnChange })
            add(switchItem(R.string.confirm_exit) { prefs.confirmExit })
            add(switchItem(R.string.long_back_to_player) { prefs.longBackToPlayer })
        }

        group(R.string.player) {
            add(SettingItem.Choice(
                getString(R.string.engine),
                entries = resources.getStringArray(R.array.engine_entries).toList(),
                values = resources.getStringArray(R.array.engine_values).toList(),
                get = { prefs.engine },
                set = { prefs.engine = it },
            ))
            add(switchItem(R.string.use_external_player) { prefs.useExternalPlayer })
            add(SettingItem.Choice(
                getString(R.string.buffer_size),
                getString(R.string.buffer_size_hint),
                entries = resources.getStringArray(R.array.buffer_entries).toList(),
                values = resources.getStringArray(R.array.buffer_values).toList(),
                get = { prefs.bufferSizeMs.toString() },
                set = { prefs.bufferSizeMs = it.toIntOrNull() ?: 5000 },
            ))
            add(SettingItem.Header(getString(R.string.video_and_audio)))
            add(SettingItem.Choice(
                getString(R.string.video_decoder),
                getString(R.string.video_decoder_hint),
                entries = listOf(getString(R.string.hardware), getString(R.string.software)),
                values = listOf("hardware", "software"),
                get = { prefs.videoDecoder },
                set = { prefs.videoDecoder = it },
            ))
            add(SettingItem.Choice(
                getString(R.string.audio_decoder),
                getString(R.string.audio_decoder_hint),
                entries = listOf(getString(R.string.hardware), getString(R.string.software)),
                values = listOf("hardware", "software"),
                get = { prefs.audioDecoder },
                set = { prefs.audioDecoder = it },
            ))
            add(switchItem(R.string.auto_frame_rate) { prefs.autoFrameRate })
            add(switchItem(R.string.tunneled_playback) { prefs.tunneledPlayback })
            add(switchItem(R.string.audio_passthrough) { prefs.audioPassthrough })
            add(switchItem(R.string.amlogic_fix) { prefs.amlogicFix })
            add(SettingItem.Header(getString(R.string.seeking)))
            add(SettingItem.Choice(
                getString(R.string.skip_step_rw),
                entries = resources.getStringArray(R.array.skipped_step_entries).toList(),
                values = resources.getStringArray(R.array.skipped_step_values).toList(),
                get = { prefs.seekStepRw.toString() },
                set = { prefs.seekStepRw = it.toIntOrNull() ?: 10 },
            ))
            add(SettingItem.Choice(
                getString(R.string.skip_step_ff),
                entries = resources.getStringArray(R.array.skipped_step_entries).toList(),
                values = resources.getStringArray(R.array.skipped_step_values).toList(),
                get = { prefs.seekStepFf.toString() },
                set = { prefs.seekStepFf = it.toIntOrNull() ?: 10 },
            ))
            add(SettingItem.Choice(
                getString(R.string.skip_step_tb_back),
                entries = resources.getStringArray(R.array.skipped_step_entries).toList(),
                values = resources.getStringArray(R.array.skipped_step_values).toList(),
                get = { prefs.seekStepBarBack.toString() },
                set = { prefs.seekStepBarBack = it.toIntOrNull() ?: 30 },
            ))
            add(SettingItem.Choice(
                getString(R.string.skip_step_tb_forward),
                entries = resources.getStringArray(R.array.skipped_step_entries).toList(),
                values = resources.getStringArray(R.array.skipped_step_values).toList(),
                get = { prefs.seekStepBarForward.toString() },
                set = { prefs.seekStepBarForward = it.toIntOrNull() ?: 30 },
            ))
            add(SettingItem.Header(getString(R.string.timeouts)))
            add(SettingItem.Number(
                getString(R.string.panels_timeout),
                min = 1,
                max = 60,
                get = { prefs.panelsTimeout },
                set = { prefs.panelsTimeout = it },
            ))
            add(SettingItem.Number(
                getString(R.string.display_change_timeout),
                min = 1,
                max = 60,
                get = { prefs.displayChangeTimeout },
                set = { prefs.displayChangeTimeout = it },
            ))
            add(SettingItem.Number(
                getString(R.string.switch_delay),
                min = 0,
                max = 30,
                get = { prefs.switchDelay },
                set = { prefs.switchDelay = it },
            ))
            add(SettingItem.Number(
                getString(R.string.switch_desc_lines),
                min = 1,
                max = 10,
                get = { prefs.switchDescriptionMaxLines },
                set = { prefs.switchDescriptionMaxLines = it },
            ))
            add(SettingItem.Header(getString(R.string.player_interface)))
            add(switchItem(R.string.show_black_screen) { prefs.showBlackScreen })
            add(switchItem(R.string.show_info_at_bottom) { prefs.infoAtBottom })
            add(switchItem(R.string.show_description_when_switching_channels_only) { prefs.showDescriptionOnSwitch })
            add(switchItem(R.string.overlay_mode) { prefs.overlayMode })
            add(switchItem(R.string.preview_mode) { prefs.previewMode })
            add(switchItem(R.string.switch_to_pip_on_home) { prefs.switchToPipOnHome })
            add(switchItem(R.string.show_media_properties) { prefs.showMediaProperties })
            add(switchItem(R.string.show_video_resolution) { prefs.showVideoResolution })
            add(switchItem(R.string.show_cc_all_channels) { prefs.closedCaptions })
            add(switchItem(R.string.select_surround_track) { prefs.selectSurroundTrack })
        }

        group(R.string.remote_control) {
            add(SettingItem.Choice(
                getString(R.string.remote_center),
                entries = resources.getStringArray(R.array.remote_action_entries).toList(),
                values = resources.getStringArray(R.array.remote_action_values).toList(),
                get = { prefs.remoteCenterAction },
                set = { prefs.remoteCenterAction = it },
            ))
            add(SettingItem.Choice(
                getString(R.string.remote_left),
                entries = resources.getStringArray(R.array.remote_action_entries).toList(),
                values = resources.getStringArray(R.array.remote_action_values).toList(),
                get = { prefs.remoteLeftAction },
                set = { prefs.remoteLeftAction = it },
            ))
            add(SettingItem.Choice(
                getString(R.string.remote_right),
                entries = resources.getStringArray(R.array.remote_action_entries).toList(),
                values = resources.getStringArray(R.array.remote_action_values).toList(),
                get = { prefs.remoteRightAction },
                set = { prefs.remoteRightAction = it },
            ))
            add(SettingItem.Choice(
                getString(R.string.remote_up),
                entries = resources.getStringArray(R.array.remote_action_entries).toList(),
                values = resources.getStringArray(R.array.remote_action_values).toList(),
                get = { prefs.remoteUpAction },
                set = { prefs.remoteUpAction = it },
            ))
            add(SettingItem.Choice(
                getString(R.string.remote_down),
                entries = resources.getStringArray(R.array.remote_action_entries).toList(),
                values = resources.getStringArray(R.array.remote_action_values).toList(),
                get = { prefs.remoteDownAction },
                set = { prefs.remoteDownAction = it },
            ))
        }

        group(R.string.home_screen) {
            add(switchItem(R.string.show_channel_names) { prefs.showChannelNames })
            add(switchItem(R.string.show_channel_numbers) { prefs.showChannelNumbers })
            add(switchItem(R.string.two_line_channel_names) { prefs.twoLineChannelNames })
            add(switchItem(R.string.two_line_program_titles) { prefs.twoLineProgramTitles })
            add(switchItem(R.string.show_current_programs) { prefs.showCurrentPrograms })
            add(switchItem(R.string.show_catchup_icon) { prefs.showCatchupIcon })
            add(switchItem(R.string.show_playlist_and_group_name) { prefs.showPlaylistAndGroupName })
            add(switchItem(R.string.show_all_channels_category) { prefs.showAllChannelsCategory })
            add(switchItem(R.string.show_all_playlists_category) { prefs.showAllPlaylistsCategory })
            add(switchItem(R.string.show_favorites_category) { prefs.showFavoritesCategory })
            add(switchItem(R.string.show_history_button) { prefs.showHistoryButton })
            add(switchItem(R.string.show_tv_guide_button) { prefs.showGuideButton })
            add(SettingItem.Header(getString(R.string.markers)))
            add(switchItem(R.string.highlight_current_channel) { prefs.highlightCurrentChannel })
            add(switchItem(R.string.highlight_current_programs) { prefs.highlightCurrentPrograms })
            add(switchItem(R.string.highlight_progress_only) { prefs.highlightProgressOnly })
            add(switchItem(R.string.dim_past_programs) { prefs.dimPastPrograms })
            add(switchItem(R.string.show_current_time_indicator) { prefs.showCurrentTimeIndicator })
            add(SettingItem.Header(getString(R.string.navigation)))
            add(switchItem(R.string.stay_on_list) { prefs.stayOnList })
            add(switchItem(R.string.stay_on_guide) { prefs.stayOnGuide })
            add(switchItem(R.string.stay_on_search) { prefs.stayOnSearch })
        }

        group(R.string.playlists_and_channels) {
            add(SettingItem.Header(getString(R.string.sorting)))
            add(SettingItem.Choice(
                getString(R.string.playlists_sorting),
                entries = sortEntries(),
                values = sortValues(),
                get = { prefs.playlistsSorting },
                set = { prefs.playlistsSorting = it },
            ))
            add(SettingItem.Choice(
                getString(R.string.channels_sorting),
                getString(R.string.channels_sorting_hint),
                entries = sortEntries(),
                values = sortValues(),
                get = { prefs.channelsSorting },
                set = { prefs.channelsSorting = it },
            ))
            add(SettingItem.Choice(
                getString(R.string.groups_sorting),
                getString(R.string.groups_sorting_hint),
                entries = sortEntries(),
                values = sortValues(),
                get = { prefs.groupsSorting },
                set = { prefs.groupsSorting = it },
            ))
            add(SettingItem.Action(getString(R.string.add_playlist)) {
                startActivity(android.content.Intent(this@SettingsActivity, PlaylistWizardActivity::class.java))
            })
            add(SettingItem.Action(getString(R.string.channel_names_editor)) { editChannelNames() })
            add(SettingItem.Action(getString(R.string.clear_logos_cache)) { clearLogos() })
        }

        group(R.string.epg) {
            add(SettingItem.Number(
                getString(R.string.epg_offset),
                min = -12,
                max = 12,
                get = { prefs.epgOffsetMinutes },
                set = { prefs.epgOffsetMinutes = it },
            ))
            add(SettingItem.Number(
                getString(R.string.past_days_to_keep),
                min = 1,
                max = 30,
                get = { prefs.pastDaysToKeep },
                set = { prefs.pastDaysToKeep = it },
            ))
            add(switchItem(R.string.store_descriptions) { prefs.storeDescriptions })
            add(switchItem(R.string.full_scan, R.string.full_scan_hint) { prefs.epgFullScan })
            add(
                switchItem(R.string.epg_auto_update, R.string.epg_auto_update_hint) {
                    prefs.epgAutoUpdate
                }
            )
            add(
                SettingItem.Number(
                    getString(R.string.epg_update_interval),
                    min = 1,
                    max = 168,
                    get = { prefs.epgUpdateIntervalHours },
                    set = {
                        prefs.epgUpdateIntervalHours = it
                        if (prefs.epgAutoUpdate) EpgUpdateScheduler.apply(this@SettingsActivity)
                    },
                )
            )
            add(SettingItem.Action(getString(R.string.epg_sources)) { showEpgSources() })
            add(SettingItem.Action(getString(R.string.update_epg)) { updateEpg() })
            add(SettingItem.Action(getString(R.string.clear_epg)) { clearEpg() })
        }

        group(R.string.history) {
            add(SettingItem.Number(
                getString(R.string.history_delay),
                min = 0,
                max = 300,
                get = { prefs.historyDelaySec },
                set = { prefs.historyDelaySec = it },
            ))
            add(SettingItem.Number(
                getString(R.string.recent_channel_count),
                min = 1,
                max = 50,
                get = { prefs.recentChannelCount },
                set = { prefs.recentChannelCount = it },
            ))
            add(SettingItem.Number(
                getString(R.string.history_day_count),
                min = 1,
                max = 30,
                get = { prefs.historyDayCount },
                set = { prefs.historyDayCount = it },
            ))
            add(SettingItem.Action(getString(R.string.reset_watch_time)) { resetWatchTime() })
        }

        group(R.string.startup) {
            add(switchItem(R.string.auto_start_on_boot) { prefs.autoStartOnBoot })
            add(switchItem(R.string.auto_start_on_wake, R.string.auto_start_on_wake_hint) { prefs.autoStartOnWake })
            add(switchItem(R.string.turn_on_last_channel) { prefs.turnOnLastChannel })
            add(switchItem(R.string.autoplay_channels) { prefs.autoplayChannels })
        }

        group(R.string.data) {
            add(SettingItem.Action(getString(R.string.pin_code), getString(R.string.pin_code_hint)) {
                PinGate.changePin(this@SettingsActivity)
            })
            add(SettingItem.Action(getString(R.string.back_up_data)) { backup() })
            add(SettingItem.Action(getString(R.string.restore_data)) { restore() })
        }

        group(R.string.about) {
            add(SettingItem.Value(getString(R.string.version), appVersion()))
            add(SettingItem.Action(getString(R.string.check_for_new_version)) {
                toast(getString(R.string.no_new_version))
            })
            add(SettingItem.Action(getString(R.string.exit)) { finishAffinity() })
        }

        return result
    }

    private fun switchItem(titleId: Int, getter: () -> Boolean): SettingItem.Switch =
        SettingItem.Switch(getString(titleId), get = getter, set = { newValue ->
            getterSetterMap(titleId, newValue)
        })

    private fun switchItem(titleId: Int, summaryId: Int, getter: () -> Boolean): SettingItem.Switch =
        SettingItem.Switch(getString(titleId), getString(summaryId), get = getter, set = {
            getterSetterMap(titleId, it)
        })

    private fun getterSetterMap(titleId: Int, value: Boolean) {
        when (titleId) {
            R.string.show_clock -> prefs.showClock = value
            R.string.show_date -> prefs.showDate = value
            R.string.animated_transition -> prefs.animatedTransition = value
            R.string.update_on_app_start -> prefs.updateOnStart = value
            R.string.update_on_playlists_change -> prefs.updateOnChange = value
            R.string.show_channel_names -> prefs.showChannelNames = value
            R.string.show_channel_numbers -> prefs.showChannelNumbers = value
            R.string.two_line_channel_names -> prefs.twoLineChannelNames = value
            R.string.two_line_program_titles -> prefs.twoLineProgramTitles = value
            R.string.show_current_programs -> prefs.showCurrentPrograms = value
            R.string.show_catchup_icon -> prefs.showCatchupIcon = value
            R.string.show_playlist_and_group_name -> prefs.showPlaylistAndGroupName = value
            R.string.show_all_channels_category -> prefs.showAllChannelsCategory = value
            R.string.show_all_playlists_category -> prefs.showAllPlaylistsCategory = value
            R.string.show_favorites_category -> prefs.showFavoritesCategory = value
            R.string.show_history_button -> prefs.showHistoryButton = value
            R.string.show_tv_guide_button -> prefs.showGuideButton = value
            R.string.highlight_current_channel -> prefs.highlightCurrentChannel = value
            R.string.highlight_current_programs -> prefs.highlightCurrentPrograms = value
            R.string.highlight_progress_only -> prefs.highlightProgressOnly = value
            R.string.dim_past_programs -> prefs.dimPastPrograms = value
            R.string.show_current_time_indicator -> prefs.showCurrentTimeIndicator = value
            R.string.auto_frame_rate -> prefs.autoFrameRate = value
            R.string.tunneled_playback -> prefs.tunneledPlayback = value
            R.string.audio_passthrough -> prefs.audioPassthrough = value
            R.string.amlogic_fix -> prefs.amlogicFix = value
            R.string.use_external_player -> prefs.useExternalPlayer = value
            R.string.show_black_screen -> prefs.showBlackScreen = value
            R.string.show_info_at_bottom -> prefs.infoAtBottom = value
            R.string.show_description_when_switching_channels_only -> prefs.showDescriptionOnSwitch = value
            R.string.stay_on_list -> prefs.stayOnList = value
            R.string.stay_on_guide -> prefs.stayOnGuide = value
            R.string.stay_on_search -> prefs.stayOnSearch = value
            R.string.overlay_mode -> prefs.overlayMode = value
            R.string.preview_mode -> prefs.previewMode = value
            R.string.switch_to_pip_on_home -> prefs.switchToPipOnHome = value
            R.string.show_media_properties -> prefs.showMediaProperties = value
            R.string.show_video_resolution -> prefs.showVideoResolution = value
            R.string.show_cc_all_channels -> prefs.closedCaptions = value
            R.string.select_surround_track -> prefs.selectSurroundTrack = value
            R.string.store_descriptions -> prefs.storeDescriptions = value
            R.string.full_scan -> prefs.epgFullScan = value
            R.string.auto_start_on_boot -> prefs.autoStartOnBoot = value
            R.string.auto_start_on_wake -> prefs.autoStartOnWake = value
            R.string.turn_on_last_channel -> prefs.turnOnLastChannel = value
            R.string.autoplay_channels -> prefs.autoplayChannels = value
            R.string.confirm_exit -> prefs.confirmExit = value
            R.string.long_back_to_player -> prefs.longBackToPlayer = value
            R.string.epg_auto_update -> {
                prefs.epgAutoUpdate = value
                EpgUpdateScheduler.apply(this)
            }
            else -> Unit
        }
    }

    private fun onItemClick(item: SettingItem) {
        when (item) {
            is SettingItem.Choice -> {
                val current = item.get()
                val items = item.entries.mapIndexed { index, entry ->
                    Dialogs.Item(entry, checked = item.values.getOrNull(index) == current)
                }
                Dialogs.show(this, item.title, item.summary.ifEmpty { null }, items) { which ->
                    item.values.getOrNull(which)?.let {
                        item.set(it)
                        settingsAdapter.notifyDataSetChanged()
                    }
                }
            }
            is SettingItem.Number -> {
                Dialogs.input(
                    this,
                    item.title,
                    value = item.get().toString(),
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    onOk = { text, _ ->
                        val value = text.toIntOrNull() ?: return@input
                        item.set(value.coerceIn(item.min, item.max))
                        settingsAdapter.notifyDataSetChanged()
                    },
                ).show()
            }
            is SettingItem.Action -> item.onClick()
            else -> Unit
        }
    }

    private fun showEpgSources() {
        val repo = TvBroApp.repo(this)
        val sources = repo.epgSources()
        if (sources.isEmpty()) {
            Dialogs.show(
                this,
                getString(R.string.epg_sources),
                getString(R.string.epg_sources_hint),
                listOf(Dialogs.Item(getString(R.string.add_source)))
            ) { addEpgSource(0) }
            return
        }
        val items = sources.map {
            Dialogs.Item(it.name, it.url, checked = it.enabled)
        }
        Dialogs.show(this, getString(R.string.epg_sources), null, items) { which ->
            val source = sources[which]
            Dialogs.show(
                this,
                source.name,
                source.url,
                listOf(
                    Dialogs.Item(getString(R.string.update_epg)),
                    Dialogs.Item(if (source.enabled) getString(R.string.unassign) else getString(R.string.assign_epg)),
                    Dialogs.Item(getString(R.string.delete_source)),
                )
            ) { action ->
                when (action) {
                    0 -> {
                        repo.updateEpgSource(source.copy(enabled = true))
                        updateEpg()
                    }
                    1 -> {
                        repo.updateEpgSource(source.copy(enabled = !source.enabled))
                        toast(getString(R.string.settings_saved))
                    }
                    2 -> {
                        repo.deleteEpgSource(source.id)
                        toast(getString(R.string.reminder_is_deleted))
                    }
                }
                rebuild()
            }
        }
    }

    private fun addEpgSource(playlistId: Long) {
        Dialogs.input(
            this,
            getString(R.string.source_name),
            hint = getString(R.string.enter_source_name),
            onOk = { name, _ -> if (name.isNotBlank()) addEpgUrl(name, playlistId) }
        )
    }

    private fun addEpgUrl(name: String, playlistId: Long) {
        Dialogs.input(
            this,
            getString(R.string.epg_url),
            hint = "http://example.com/epg.xml.gz",
            onOk = { url, _ ->
                if (url.isBlank()) return@input
                TvBroApp.repo(this).insertEpgSource(
                    EpgSource(name = name, url = url, playlistId = playlistId)
                )
                toast(getString(R.string.epg_source_added))
                rebuild()
            }
        )
    }

    private fun updateEpg() {
        EpgUpdateScheduler.runNow(this)
        toast(getString(R.string.epg_update_started))
    }

    private fun clearEpg() {
        Dialogs.confirm(
            this,
            getString(R.string.clear_epg),
            getString(R.string.delete_all_programs),
            getString(R.string.delete)
        ) {
            TvBroApp.get().sources.pruneOldPrograms(0) {
                toast(getString(R.string.settings_saved))
            }
        }
    }

    private fun resetWatchTime() {
        Dialogs.confirm(
            this,
            getString(R.string.reset_watch_time_q),
            getString(R.string.reset_watch_time_msg),
            getString(R.string.reset)
        ) {
            TvBroApp.repo(this).execSQL("UPDATE channels SET watch_time_ms = 0, last_watched = 0")
            toast(getString(R.string.watch_time_reset))
        }
    }

    private fun editChannelNames() {
        Dialogs.input(
            this,
            getString(R.string.prefixes_to_remove),
            value = prefs.namePrefixesToRemove,
            hint = getString(R.string.channel_names_editor_hint),
            secondInput = true,
            secondValue = prefs.nameSuffixesToRemove,
            onOk = { prefixes, suffixes ->
                prefs.namePrefixesToRemove = prefixes
                prefs.nameSuffixesToRemove = suffixes
                toast(getString(R.string.settings_saved))
            }
        )
    }

    private fun clearLogos() {
        val dir = File(cacheDir, "logos")
        if (dir.exists()) dir.deleteRecursively()
        toast(getString(R.string.logos_cache_cleared))
    }

    private fun backup() {
        val repo = TvBroApp.repo(this)
        val target = File(getExternalFilesDir(null), "tvibro_backup_${System.currentTimeMillis()}.zip")
        val dialog = Dialogs.progress(this, getString(R.string.creating_backup))
        dialog.show()
        Thread {
            val ok = repo.backupTo(target)
            runOnUiThread {
                dialog.dismiss()
                toast(getString(if (ok) R.string.backup_created else R.string.failed_to_create_backup), long = true)
            }
        }.start()
    }

    private fun restore() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val backups = dir.listFiles { f -> f.name.startsWith("tvibro_backup_") && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (backups.isEmpty()) {
            toast(getString(R.string.no_backup_found), long = true)
            return
        }
        val items = backups.map { file ->
            Dialogs.Item(
                file.name.removePrefix("tvibro_backup_").removeSuffix(".zip"),
                android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", file.lastModified()).toString(),
            )
        }
        Dialogs.show(this, getString(R.string.restore_data), getString(R.string.select_backup_file), items) { which ->
            confirmRestore(backups[which])
        }
    }

    private fun confirmRestore(file: File) {
        Dialogs.confirm(
            this,
            getString(R.string.restore_data_q),
            getString(R.string.restore_data_hint),
            getString(R.string.restore)
        ) {
            val repo = TvBroApp.repo(this)
            val dialog = Dialogs.progress(this, getString(R.string.restoring_data))
            dialog.show()
            Thread {
                val ok = repo.restoreFrom(file)
                runOnUiThread {
                    dialog.dismiss()
                    if (ok) {
                        Toast.makeText(this, R.string.restart_required, Toast.LENGTH_LONG).show()
                        finishAffinity()
                    } else {
                        toast(getString(R.string.failed_to_restore_data), long = true)
                    }
                }
            }.start()
        }
    }

    private fun sortEntries(): List<String> = resources.getStringArray(R.array.sort_entries).toList()

    private fun sortValues(): List<String> = resources.getStringArray(R.array.sort_values).toList()

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "1.0"

    companion object {
        private const val NO_GROUP = -1
        private const val ENTER_MS = 220L
        private const val EXIT_MS = 180L
    }
}
