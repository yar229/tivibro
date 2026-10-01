package com.tvibro.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tvibro.ui.theme.ThemeMode
import java.util.Locale

class Prefs private constructor(context: Context) {

    // applicationContext is still null while TvBroApp.attachBaseContext() runs
    private val app: Context = context.applicationContext ?: context

    private val sp: SharedPreferences =
        app.getSharedPreferences("tvibro_settings", Context.MODE_PRIVATE)

    private val res = app.resources

    // ------------------------------------------------------------- appearance

    var language: String
        get() = sp.getString(KEY_LANGUAGE, "system") ?: "system"
        set(v) = sp.edit { putString(KEY_LANGUAGE, v) }

    var showClock: Boolean
        get() = sp.getBoolean(KEY_SHOW_CLOCK, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_CLOCK, v) }

    

    /** 0=top-left 1=top-right 2=bottom-left 3=bottom-right */
    var clockPosition: Int
        get() = sp.getInt(KEY_CLOCK_POSITION, 1)
        set(v) = sp.edit { putInt(KEY_CLOCK_POSITION, v) }

    /**
     * One of [com.tvibro.ui.theme.ThemeMode.SYSTEM] / [DARK] / [LIGHT].
     *
     * Dark by default: a tuner usually has no day/night switch and reports day mode, so "system"
     * would put the whole UI into the light palette on a television.
     */
    var colorTheme: String
        get() = sp.getString(KEY_COLOR_THEME, ThemeMode.DARK) ?: ThemeMode.DARK
        set(v) = sp.edit { putString(KEY_COLOR_THEME, v) }

    var accentColor: Int
        get() = sp.getInt(KEY_ACCENT_COLOR, 0xFF3F51B5.toInt())
        set(v) = sp.edit { putInt(KEY_ACCENT_COLOR, v) }

    var fontScale: Float
        get() = sp.getString(KEY_FONT_SIZE, "1.0")!!.toFloatOrNull() ?: 1f
        set(v) = sp.edit { putString(KEY_FONT_SIZE, v.toString()) }

    /** Multipliers for the three player panels, independent of the global font size. */
    var bottomPanelFont: Float
        get() = sp.getString(KEY_BOTTOM_PANEL_FONT, "1.0")!!.toFloatOrNull() ?: 1f
        set(v) = sp.edit { putString(KEY_BOTTOM_PANEL_FONT, v.toString()) }

    var channelPanelFont: Float
        get() = sp.getString(KEY_CHANNEL_PANEL_FONT, "1.0")!!.toFloatOrNull() ?: 1f
        set(v) = sp.edit { putString(KEY_CHANNEL_PANEL_FONT, v.toString()) }

    var infoPanelFont: Float
        get() = sp.getString(KEY_INFO_PANEL_FONT, "1.0")!!.toFloatOrNull() ?: 1f
        set(v) = sp.edit { putString(KEY_INFO_PANEL_FONT, v.toString()) }

    var uiTransparency: Int
        get() = sp.getInt(KEY_UI_TRANSPARENCY, 100)
        set(v) = sp.edit { putInt(KEY_UI_TRANSPARENCY, v) }

    var showChannelNames: Boolean
        get() = sp.getBoolean(KEY_SHOW_CHANNEL_NAMES, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_CHANNEL_NAMES, v) }

    var showChannelNumbers: Boolean
        get() = sp.getBoolean(KEY_SHOW_CHANNEL_NUMBERS, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_CHANNEL_NUMBERS, v) }

    var twoLineChannelNames: Boolean
        get() = sp.getBoolean(KEY_TWO_LINE_NAMES, false)
        set(v) = sp.edit { putBoolean(KEY_TWO_LINE_NAMES, v) }

    var twoLineProgramTitles: Boolean
        get() = sp.getBoolean(KEY_TWO_LINE_TITLES, true)
        set(v) = sp.edit { putBoolean(KEY_TWO_LINE_TITLES, v) }

    var showCurrentPrograms: Boolean
        get() = sp.getBoolean(KEY_SHOW_CURRENT_PROGRAMS, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_CURRENT_PROGRAMS, v) }

    var showPrograms: Boolean
        get() = sp.getBoolean(KEY_SHOW_PROGRAMS, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_PROGRAMS, v) }

    var showCatchupIcon: Boolean
        get() = sp.getBoolean(KEY_SHOW_CATCHUP, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_CATCHUP, v) }

    var showAllChannelsCategory: Boolean
        get() = sp.getBoolean(KEY_ALL_CHANNELS_CATEGORY, true)
        set(v) = sp.edit { putBoolean(KEY_ALL_CHANNELS_CATEGORY, v) }

    var showAllPlaylistsCategory: Boolean
        get() = sp.getBoolean(KEY_ALL_PLAYLISTS_CATEGORY, true)
        set(v) = sp.edit { putBoolean(KEY_ALL_PLAYLISTS_CATEGORY, v) }

    var showFavoritesCategory: Boolean
        get() = sp.getBoolean(KEY_FAVORITES_CATEGORY, true)
        set(v) = sp.edit { putBoolean(KEY_FAVORITES_CATEGORY, v) }

    var showHistoryButton: Boolean
        get() = sp.getBoolean(KEY_HISTORY_BUTTON, true)
        set(v) = sp.edit { putBoolean(KEY_HISTORY_BUTTON, v) }

    var showPlaylistAndGroupName: Boolean
        get() = sp.getBoolean(KEY_SHOW_PL_GROUP, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_PL_GROUP, v) }

    var highlightCurrentChannel: Boolean
        get() = sp.getBoolean(KEY_HL_CURRENT_CHANNEL, true)
        set(v) = sp.edit { putBoolean(KEY_HL_CURRENT_CHANNEL, v) }

    var highlightCurrentPrograms: Boolean
        get() = sp.getBoolean(KEY_HL_CURRENT_PROGRAMS, true)
        set(v) = sp.edit { putBoolean(KEY_HL_CURRENT_PROGRAMS, v) }

    var highlightCurrentProgramsInColor: Boolean
        get() = sp.getBoolean(KEY_HL_CURRENT_PROGRAMS_COLOR, false)
        set(v) = sp.edit { putBoolean(KEY_HL_CURRENT_PROGRAMS_COLOR, v) }

    var highlightProgressOnly: Boolean
        get() = sp.getBoolean(KEY_HL_PROGRESS_ONLY, false)
        set(v) = sp.edit { putBoolean(KEY_HL_PROGRESS_ONLY, v) }

    var dimPastPrograms: Boolean
        get() = sp.getBoolean(KEY_DIM_PAST, true)
        set(v) = sp.edit { putBoolean(KEY_DIM_PAST, v) }

    var showPastPrograms: Boolean
        get() = sp.getBoolean(KEY_SHOW_PAST, false)
        set(v) = sp.edit { putBoolean(KEY_SHOW_PAST, v) }

    var showCurrentTimeIndicator: Boolean
        get() = sp.getBoolean(KEY_TIME_INDICATOR, true)
        set(v) = sp.edit { putBoolean(KEY_TIME_INDICATOR, v) }

    var animatedTransition: Boolean
        get() = sp.getBoolean(KEY_ANIMATED_TRANSITION, true)
        set(v) = sp.edit { putBoolean(KEY_ANIMATED_TRANSITION, v) }

    // ---------------------------------------------------------------- player

    /** "exo" or "vlc" */
    var engine: String
        get() = sp.getString(KEY_ENGINE, "exo") ?: "exo"
        set(v) = sp.edit { putString(KEY_ENGINE, v) }

    var bufferSizeMs: Int
        get() = sp.getInt(KEY_BUFFER, DEFAULT_BUFFER_MS).sanitizedBufferMs()
        set(v) = sp.edit { putInt(KEY_BUFFER, v.sanitizedBufferMs()) }

    var videoDecoder: String
        get() = sp.getString(KEY_VIDEO_DECODER, "hardware") ?: "hardware"
        set(v) = sp.edit { putString(KEY_VIDEO_DECODER, v) }

    var audioDecoder: String
        get() = sp.getString(KEY_AUDIO_DECODER, "hardware") ?: "hardware"
        set(v) = sp.edit { putString(KEY_AUDIO_DECODER, v) }

    var autoFrameRate: Boolean
        get() = sp.getBoolean(KEY_AFR, false)
        set(v) = sp.edit { putBoolean(KEY_AFR, v) }

    var tunneledPlayback: Boolean
        get() = sp.getBoolean(KEY_TUNNELED, false)
        set(v) = sp.edit { putBoolean(KEY_TUNNELED, v) }

    var audioPassthrough: Boolean
        get() = sp.getBoolean(KEY_PASSTHROUGH, false)
        set(v) = sp.edit { putBoolean(KEY_PASSTHROUGH, v) }

    var amlogicFix: Boolean
        get() = sp.getBoolean(KEY_AMLOCIC, false)
        set(v) = sp.edit { putBoolean(KEY_AMLOCIC, v) }

    var seekStepRw: Int
        get() = sp.getInt(KEY_SEEK_RW, 10)
        set(v) = sp.edit { putInt(KEY_SEEK_RW, v) }

    var seekStepFf: Int
        get() = sp.getInt(KEY_SEEK_FF, 10)
        set(v) = sp.edit { putInt(KEY_SEEK_FF, v) }

    var seekStepBarBack: Int
        get() = sp.getInt(KEY_SEEK_BAR_BACK, 30)
        set(v) = sp.edit { putInt(KEY_SEEK_BAR_BACK, v) }

    var seekStepBarForward: Int
        get() = sp.getInt(KEY_SEEK_BAR_FORWARD, 30)
        set(v) = sp.edit { putInt(KEY_SEEK_BAR_FORWARD, v) }

    var panelsTimeout: Int
        get() = sp.getInt(KEY_PANELS_TIMEOUT, 5)
        set(v) = sp.edit { putInt(KEY_PANELS_TIMEOUT, v) }

    var displayChangeTimeout: Int
        get() = sp.getInt(KEY_DISPLAY_TIMEOUT, 3)
        set(v) = sp.edit { putInt(KEY_DISPLAY_TIMEOUT, v) }

    var switchDelay: Int
        get() = sp.getInt(KEY_SWITCH_DELAY, 0)
        set(v) = sp.edit { putInt(KEY_SWITCH_DELAY, v) }

    var showBlackScreen: Boolean
        get() = sp.getBoolean(KEY_BLACK_SCREEN, true)
        set(v) = sp.edit { putBoolean(KEY_BLACK_SCREEN, v) }

    var showInfoOnSwitch: Boolean
        get() = sp.getBoolean(KEY_INFO_ON_SWITCH, true)
        set(v) = sp.edit { putBoolean(KEY_INFO_ON_SWITCH, v) }

    var infoAtBottom: Boolean
        get() = sp.getBoolean(KEY_INFO_AT_BOTTOM, false)
        set(v) = sp.edit { putBoolean(KEY_INFO_AT_BOTTOM, v) }

    var showDescriptionOnSwitch: Boolean
        get() = sp.getBoolean(KEY_DESC_ON_SWITCH, false)
        set(v) = sp.edit { putBoolean(KEY_DESC_ON_SWITCH, v) }

    var stayOnList: Boolean
        get() = sp.getBoolean(KEY_STAY_ON_LIST, true)
        set(v) = sp.edit { putBoolean(KEY_STAY_ON_LIST, v) }

    var stayOnGuide: Boolean
        get() = sp.getBoolean(KEY_STAY_ON_GUIDE, false)
        set(v) = sp.edit { putBoolean(KEY_STAY_ON_GUIDE, v) }

    var stayOnSearch: Boolean
        get() = sp.getBoolean(KEY_STAY_ON_SEARCH, false)
        set(v) = sp.edit { putBoolean(KEY_STAY_ON_SEARCH, v) }

    var overlayMode: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_MODE, false)
        set(v) = sp.edit { putBoolean(KEY_OVERLAY_MODE, v) }

    var previewMode: Boolean
        get() = sp.getBoolean(KEY_PREVIEW_MODE, true)
        set(v) = sp.edit { putBoolean(KEY_PREVIEW_MODE, v) }

    var useExternalPlayer: Boolean
        get() = sp.getBoolean(KEY_EXTERNAL_PLAYER, false)
        set(v) = sp.edit { putBoolean(KEY_EXTERNAL_PLAYER, v) }

    var externalPlayerPackage: String
        get() = sp.getString(KEY_EXTERNAL_PLAYER_PKG, "") ?: ""
        set(v) = sp.edit { putString(KEY_EXTERNAL_PLAYER_PKG, v) }

    var switchToPipOnHome: Boolean
        get() = sp.getBoolean(KEY_PIP_ON_HOME, false)
        set(v) = sp.edit { putBoolean(KEY_PIP_ON_HOME, v) }

    var showSubtitles: Boolean
        get() = sp.getBoolean(KEY_SHOW_SUBS, false)
        set(v) = sp.edit { putBoolean(KEY_SHOW_SUBS, v) }

    var subtitleSizeSp: Int
        get() = sp.getInt(KEY_SUBS_SIZE, 22)
        set(v) = sp.edit { putInt(KEY_SUBS_SIZE, v) }

    var subtitleBackground: Boolean
        get() = sp.getBoolean(KEY_SUBS_BG, true)
        set(v) = sp.edit { putBoolean(KEY_SUBS_BG, v) }

    var selectSurroundTrack: Boolean
        get() = sp.getBoolean(KEY_SURROUND, false)
        set(v) = sp.edit { putBoolean(KEY_SURROUND, v) }

    var showMediaProperties: Boolean
        get() = sp.getBoolean(KEY_MEDIA_PROPS, false)
        set(v) = sp.edit { putBoolean(KEY_MEDIA_PROPS, v) }

    var showVideoResolution: Boolean
        get() = sp.getBoolean(KEY_VIDEO_RES, false)
        set(v) = sp.edit { putBoolean(KEY_VIDEO_RES, v) }

    var closedCaptions: Boolean
        get() = sp.getBoolean(KEY_CC, false)
        set(v) = sp.edit { putBoolean(KEY_CC, v) }

    // ------------------------------------------------------------------ epg

    var epgOffsetMinutes: Int
        get() = sp.getInt(KEY_EPG_OFFSET, 0)
        set(v) = sp.edit { putInt(KEY_EPG_OFFSET, v) }

    var pastDaysToKeep: Int
        get() = sp.getInt(KEY_PAST_DAYS, 7)
        set(v) = sp.edit { putInt(KEY_PAST_DAYS, v) }

    var storeDescriptions: Boolean
        get() = sp.getBoolean(KEY_STORE_DESC, true)
        set(v) = sp.edit { putBoolean(KEY_STORE_DESC, v) }

    var updateIntervalHours: Int
        get() = sp.getInt(KEY_UPDATE_INTERVAL, 24)
        set(v) = sp.edit { putInt(KEY_UPDATE_INTERVAL, v) }

    var updateOnStart: Boolean
        get() = sp.getBoolean(KEY_UPDATE_START, true)
        set(v) = sp.edit { putBoolean(KEY_UPDATE_START, v) }

    var updateOnChange: Boolean
        get() = sp.getBoolean(KEY_UPDATE_CHANGE, true)
        set(v) = sp.edit { putBoolean(KEY_UPDATE_CHANGE, v) }

    var epgFullScan: Boolean
        get() = sp.getBoolean(KEY_EPG_FULL_SCAN, false)
        set(v) = sp.edit { putBoolean(KEY_EPG_FULL_SCAN, v) }

    var epgAutoUpdate: Boolean
        get() = sp.getBoolean(KEY_EPG_AUTO_UPDATE, false)
        set(v) = sp.edit { putBoolean(KEY_EPG_AUTO_UPDATE, v) }

    var epgUpdateIntervalHours: Int
        get() = sp.getInt(KEY_EPG_UPDATE_INTERVAL, 12).coerceIn(1, 168)
        set(v) = sp.edit { putInt(KEY_EPG_UPDATE_INTERVAL, v.coerceIn(1, 168)) }

    // ------------------------------------------------------------- behaviour

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART_BOOT, false)
        set(v) = sp.edit { putBoolean(KEY_AUTOSTART_BOOT, v) }

    var autoStartOnWake: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART_WAKE, false)
        set(v) = sp.edit { putBoolean(KEY_AUTOSTART_WAKE, v) }

    var turnOnLastChannel: Boolean
        get() = sp.getBoolean(KEY_LAST_CHANNEL, true)
        set(v) = sp.edit { putBoolean(KEY_LAST_CHANNEL, v) }

    var autoplayChannels: Boolean
        get() = sp.getBoolean(KEY_AUTOPLAY, true)
        set(v) = sp.edit { putBoolean(KEY_AUTOPLAY, v) }

    var remoteCenterAction: String
        get() = sp.getString(KEY_REMOTE_CENTER, "show_info") ?: "show_info"
        set(v) = sp.edit { putString(KEY_REMOTE_CENTER, v) }

    var remoteLeftAction: String
        get() = sp.getString(KEY_REMOTE_LEFT, "show_channels") ?: "show_channels"
        set(v) = sp.edit { putString(KEY_REMOTE_LEFT, v) }

    var remoteRightAction: String
        get() = sp.getString(KEY_REMOTE_RIGHT, "volume_up") ?: "volume_up"
        set(v) = sp.edit { putString(KEY_REMOTE_RIGHT, v) }

    var remoteUpAction: String
        get() = sp.getString(KEY_REMOTE_UP, "prev_channel") ?: "prev_channel"
        set(v) = sp.edit { putString(KEY_REMOTE_UP, v) }

    var remoteDownAction: String
        get() = sp.getString(KEY_REMOTE_DOWN, "next_channel") ?: "next_channel"
        set(v) = sp.edit { putString(KEY_REMOTE_DOWN, v) }

    var switchDescriptionMaxLines: Int
        get() = sp.getInt(KEY_SWITCH_DESC_LINES, 3).coerceIn(1, 10)
        set(v) = sp.edit { putInt(KEY_SWITCH_DESC_LINES, v.coerceIn(1, 10)) }

    var confirmExit: Boolean
        get() = sp.getBoolean(KEY_CONFIRM_EXIT, true)
        set(v) = sp.edit { putBoolean(KEY_CONFIRM_EXIT, v) }

    var longBackToPlayer: Boolean
        get() = sp.getBoolean(KEY_LONG_BACK_PLAYER, true)
        set(v) = sp.edit { putBoolean(KEY_LONG_BACK_PLAYER, v) }

    var historyDelaySec: Int
        get() = sp.getInt(KEY_HISTORY_DELAY, 30)
        set(v) = sp.edit { putInt(KEY_HISTORY_DELAY, v) }

    var recentChannelCount: Int
        get() = sp.getInt(KEY_RECENT_COUNT, 10)
        set(v) = sp.edit { putInt(KEY_RECENT_COUNT, v) }

    var historyDayCount: Int
        get() = sp.getInt(KEY_HISTORY_DAYS, 7)
        set(v) = sp.edit { putInt(KEY_HISTORY_DAYS, v) }

    var showSearchHistory: Boolean
        get() = sp.getBoolean(KEY_SEARCH_HISTORY, true)
        set(v) = sp.edit { putBoolean(KEY_SEARCH_HISTORY, v) }

    var logoPriority: String
        get() = sp.getString(KEY_LOGO_PRIORITY, "playlist") ?: "playlist"
        set(v) = sp.edit { putString(KEY_LOGO_PRIORITY, v) }

    var logosFolder: String
        get() = sp.getString(KEY_LOGOS_FOLDER, "") ?: ""
        set(v) = sp.edit { putString(KEY_LOGOS_FOLDER, v) }

    var inexactLogoMatching: Boolean
        get() = sp.getBoolean(KEY_LOGO_INEXACT, false)
        set(v) = sp.edit { putBoolean(KEY_LOGO_INEXACT, v) }

    var namePrefixesToRemove: String
        get() = sp.getString(KEY_PREFIXES, "") ?: ""
        set(v) = sp.edit { putString(KEY_PREFIXES, v) }

    var nameSuffixesToRemove: String
        get() = sp.getString(KEY_SUFFIXES, "") ?: ""
        set(v) = sp.edit { putString(KEY_SUFFIXES, v) }

    var channelsSorting: String
        get() = sp.getString(KEY_CH_SORT, "order") ?: "order"
        set(v) = sp.edit { putString(KEY_CH_SORT, v) }

    var groupsSorting: String
        get() = sp.getString(KEY_GR_SORT, "order") ?: "order"
        set(v) = sp.edit { putString(KEY_GR_SORT, v) }

    var playlistsSorting: String
        get() = sp.getString(KEY_PL_SORT, "order") ?: "order"
        set(v) = sp.edit { putString(KEY_PL_SORT, v) }

    // ------------------------------------------------------------------ pin

    var pin: String
        get() = sp.getString(KEY_PIN, "") ?: ""
        set(v) = sp.edit { putString(KEY_PIN, v) }

    var pinRequiredFor: String
        get() = sp.getString(KEY_PIN_FOR, "always") ?: "always"
        set(v) = sp.edit { putString(KEY_PIN_FOR, v) }

    var pinMethod: String
        get() = sp.getString(KEY_PIN_METHOD, "numeric") ?: "numeric"
        set(v) = sp.edit { putString(KEY_PIN_METHOD, v) }

    // -------------------------------------------------------------- vod/misc

    var autoplayNextEpisode: Boolean
        get() = sp.getBoolean(KEY_AUTOPLAY_EP, true)
        set(v) = sp.edit { putBoolean(KEY_AUTOPLAY_EP, v) }

    var groupMoviesByCategories: Boolean
        get() = sp.getBoolean(KEY_GROUP_MOVIES, true)
        set(v) = sp.edit { putBoolean(KEY_GROUP_MOVIES, v) }

    var groupShowsByCategories: Boolean
        get() = sp.getBoolean(KEY_GROUP_SHOWS, true)
        set(v) = sp.edit { putBoolean(KEY_GROUP_SHOWS, v) }

    var includeVodInSearch: Boolean
        get() = sp.getBoolean(KEY_SEARCH_VOD, true)
        set(v) = sp.edit { putBoolean(KEY_SEARCH_VOD, v) }

    var recordingsFolder: String
        get() = sp.getString(KEY_REC_FOLDER, "") ?: ""
        set(v) = sp.edit { putString(KEY_REC_FOLDER, v) }

    var remindBeforeMinutes: Int
        get() = sp.getInt(KEY_REMIND_BEFORE, 5)
        set(v) = sp.edit { putInt(KEY_REMIND_BEFORE, v) }

    var popupTimeoutSec: Int
        get() = sp.getInt(KEY_POPUP_TIMEOUT, 10)
        set(v) = sp.edit { putInt(KEY_POPUP_TIMEOUT, v) }

    // --------------------------------------------------------------- helpers

    fun stringSet(key: String): Set<String> = sp.getStringSet(key, emptySet()) ?: emptySet()

    fun putStringSet(key: String, value: Set<String>) {
        sp.edit { putStringSet(key, value) }
    }

    fun boolOf(key: String, def: Boolean) = sp.getBoolean(key, def)

    fun stringOf(key: String, def: String) = sp.getString(key, def) ?: def

    fun intOf(key: String, def: Int) = sp.getInt(key, def)

    fun floatOf(key: String, def: Float) = sp.getFloat(key, def)

    fun stringOfOrNull(key: String): String? = sp.getString(key, null)

    fun applyFontScale() {
        val cfg = res.configuration
        cfg.fontScale = fontScale
        @Suppress("DEPRECATION")
        res.updateConfiguration(cfg, res.displayMetrics)
    }

    fun locale(): java.util.Locale? {
        val tag = language
        if (tag == "system") return null
        val locale = Locale.forLanguageTag(tag)
        return if (locale.language.isEmpty()) null else locale
    }

    fun all(): SharedPreferences = sp

    fun clearAll() {
        sp.edit { clear() }
    }

    companion object {
        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context).also { instance = it }
        }

        // backup keys
        const val KEY_LANGUAGE = "language"
        const val KEY_SHOW_CLOCK = "show_clock"
        const val KEY_CLOCK_POSITION = "clock_position"
        const val KEY_COLOR_THEME = "color_theme"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_BOTTOM_PANEL_FONT = "bottom_panel_font"
        const val KEY_CHANNEL_PANEL_FONT = "channel_panel_font"
        const val KEY_INFO_PANEL_FONT = "info_panel_font"
        const val KEY_UI_TRANSPARENCY = "ui_transparency"
        const val KEY_SHOW_CHANNEL_NAMES = "show_channel_names"
        const val KEY_SHOW_CHANNEL_NUMBERS = "show_channel_numbers"
        const val KEY_TWO_LINE_NAMES = "two_line_names"
        const val KEY_TWO_LINE_TITLES = "two_line_titles"
        const val KEY_SHOW_CURRENT_PROGRAMS = "show_current_programs"
        const val KEY_SHOW_PROGRAMS = "show_programs"
        const val KEY_SHOW_CATCHUP = "show_catchup"
        const val KEY_ALL_CHANNELS_CATEGORY = "all_channels_category"
        const val KEY_ALL_PLAYLISTS_CATEGORY = "all_playlists_category"
        const val KEY_FAVORITES_CATEGORY = "favorites_category"
    const val KEY_HISTORY_BUTTON = "history_button"
        const val KEY_SHOW_PL_GROUP = "show_pl_group"
        const val KEY_HL_CURRENT_CHANNEL = "hl_current_channel"
        const val KEY_HL_CURRENT_PROGRAMS = "hl_current_programs"
        const val KEY_HL_CURRENT_PROGRAMS_COLOR = "hl_current_programs_color"
        const val KEY_HL_PROGRESS_ONLY = "hl_progress_only"
        const val KEY_DIM_PAST = "dim_past"
        const val KEY_SHOW_PAST = "show_past"
        const val KEY_TIME_INDICATOR = "time_indicator"
        const val KEY_ANIMATED_TRANSITION = "animated_transition"

        const val KEY_ENGINE = "engine"
        const val KEY_BUFFER = "buffer"
        const val KEY_VIDEO_DECODER = "video_decoder"
        const val KEY_AUDIO_DECODER = "audio_decoder"
        const val KEY_AFR = "afr"
        const val KEY_TUNNELED = "tunneled"
        const val KEY_PASSTHROUGH = "passthrough"
        const val KEY_AMLOCIC = "amlogic"
        const val KEY_SEEK_RW = "seek_rw"
        const val KEY_SEEK_FF = "seek_ff"
        const val KEY_SEEK_BAR_BACK = "seek_bar_back"
        const val KEY_SEEK_BAR_FORWARD = "seek_bar_forward"
        const val KEY_PANELS_TIMEOUT = "panels_timeout"
        const val KEY_DISPLAY_TIMEOUT = "display_timeout"
        const val KEY_SWITCH_DELAY = "switch_delay"
        const val KEY_BLACK_SCREEN = "black_screen"
        const val KEY_INFO_ON_SWITCH = "info_on_switch"
        const val KEY_INFO_AT_BOTTOM = "info_at_bottom"
        const val KEY_DESC_ON_SWITCH = "desc_on_switch"
        const val KEY_STAY_ON_LIST = "stay_on_list"
        const val KEY_STAY_ON_GUIDE = "stay_on_guide"
        const val KEY_STAY_ON_SEARCH = "stay_on_search"
        const val KEY_OVERLAY_MODE = "overlay_mode"
        const val KEY_PREVIEW_MODE = "preview_mode"
        const val KEY_EXTERNAL_PLAYER = "external_player"
        const val KEY_EXTERNAL_PLAYER_PKG = "external_player_pkg"
        const val KEY_PIP_ON_HOME = "pip_on_home"
        const val KEY_SHOW_SUBS = "show_subs"
        const val KEY_SUBS_SIZE = "subs_size"
        const val KEY_SUBS_BG = "subs_bg"
        const val KEY_SURROUND = "surround"
        const val KEY_MEDIA_PROPS = "media_props"
        const val KEY_VIDEO_RES = "video_res"
        const val KEY_CC = "cc"

        const val KEY_EPG_OFFSET = "epg_offset"
        const val KEY_PAST_DAYS = "past_days"
        const val KEY_STORE_DESC = "store_desc"
        const val KEY_UPDATE_INTERVAL = "update_interval"
        const val KEY_UPDATE_START = "update_start"
        const val KEY_UPDATE_CHANGE = "update_change"
        const val KEY_EPG_FULL_SCAN = "epg_full_scan"
        const val KEY_EPG_AUTO_UPDATE = "epg_auto_update"
        const val KEY_EPG_UPDATE_INTERVAL = "epg_update_interval"

        const val KEY_AUTOSTART_BOOT = "autostart_boot"
        const val KEY_AUTOSTART_WAKE = "autostart_wake"
    const val KEY_LAST_CHANNEL = "last_channel"
    const val KEY_AUTOPLAY = "autoplay"
    const val KEY_REMOTE_CENTER = "remote_center"
    const val KEY_REMOTE_LEFT = "remote_left"
    const val KEY_REMOTE_RIGHT = "remote_right"
    const val KEY_REMOTE_UP = "remote_up"
    const val KEY_REMOTE_DOWN = "remote_down"
    const val KEY_SWITCH_DESC_LINES = "switch_desc_lines"
        const val KEY_CONFIRM_EXIT = "confirm_exit"
        const val KEY_LONG_BACK_PLAYER = "long_back_player"
        const val KEY_HISTORY_DELAY = "history_delay"
        const val KEY_RECENT_COUNT = "recent_count"
        const val KEY_HISTORY_DAYS = "history_days"
        const val KEY_SEARCH_HISTORY = "search_history"
        const val KEY_LOGO_PRIORITY = "logo_priority"
        const val KEY_LOGOS_FOLDER = "logos_folder"
        const val KEY_LOGO_INEXACT = "logo_inexact"
        const val KEY_PREFIXES = "prefixes"
        const val KEY_SUFFIXES = "suffixes"
        const val KEY_CH_SORT = "ch_sort"
        const val KEY_GR_SORT = "gr_sort"
        const val KEY_PL_SORT = "pl_sort"

        const val KEY_PIN = "pin"
        const val KEY_PIN_FOR = "pin_for"
        const val KEY_PIN_METHOD = "pin_method"

        const val KEY_AUTOPLAY_EP = "autoplay_ep"
        const val KEY_GROUP_MOVIES = "group_movies"
        const val KEY_GROUP_SHOWS = "group_shows"
        const val KEY_SEARCH_VOD = "search_vod"
        const val KEY_REC_FOLDER = "rec_folder"
        const val KEY_REMIND_BEFORE = "remind_before"
        const val KEY_POPUP_TIMEOUT = "popup_timeout"

        const val KEY_PLAYER_CHANNELS = "player_channels"
        const val KEY_PLAYER_GROUP = "player_group"
        const val KEY_PLAYER_FILTER = "player_filter"
        const val KEY_NAV_ORDER = "nav_order"
        const val KEY_REMOTE_MAP = "remote_map"
        const val KEY_BACKUP_PATH = "backup_path"
        const val KEY_LOGOS_CACHE = "logos_cache"
    }
}

/** Media3 requires minBufferMs >= 2500 and rejects smaller values with a hard crash. */
const val MIN_BUFFER_MS = 2500
const val MAX_BUFFER_MS = 60_000
const val DEFAULT_BUFFER_MS = 5000

/** Sentinel for the "no buffering" option: play straight from the live edge. */
const val NO_BUFFER_MS = 0

/**
 * [NO_BUFFER_MS] is passed through untouched because it selects a dedicated zero-buffer
 * load control, while every other value stays inside the range Media3 accepts.
 */
fun Int.sanitizedBufferMs(): Int = if (this == NO_BUFFER_MS) NO_BUFFER_MS else coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)
