package com.tvibro.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tvibro.data.model.Channel
import com.tvibro.data.model.EpgSource
import com.tvibro.data.model.HistoryEntry
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.PlaylistType
import com.tvibro.data.model.Program
import com.tvibro.data.model.Reminder
import java.io.File

class TvBroDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext ?: context

    fun databaseFile(): File = appContext.getDatabasePath(DB_NAME)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE playlists (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL DEFAULT '',
              type INTEGER NOT NULL DEFAULT 0,
              url TEXT NOT NULL DEFAULT '',
              login TEXT NOT NULL DEFAULT '',
              password TEXT NOT NULL DEFAULT '',
              user_agent TEXT NOT NULL DEFAULT '',
              epg_url TEXT NOT NULL DEFAULT '',
              mac TEXT NOT NULL DEFAULT '',
              enabled INTEGER NOT NULL DEFAULT 1,
              order_index INTEGER NOT NULL DEFAULT 0,
              date_added INTEGER NOT NULL DEFAULT 0,
              last_update INTEGER NOT NULL DEFAULT 0,
              hidden INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE channels (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              playlist_id INTEGER NOT NULL DEFAULT 0,
              stream_id TEXT NOT NULL DEFAULT '',
              name TEXT NOT NULL DEFAULT '',
              number TEXT NOT NULL DEFAULT '',
              logo_url TEXT NOT NULL DEFAULT '',
              group_title TEXT NOT NULL DEFAULT '',
              tvg_id TEXT NOT NULL DEFAULT '',
              tvg_name TEXT NOT NULL DEFAULT '',
              url TEXT NOT NULL DEFAULT '',
              catchup_source TEXT NOT NULL DEFAULT '',
              catchup_days INTEGER NOT NULL DEFAULT 0,
              catchup_type TEXT NOT NULL DEFAULT '',
              is_vod INTEGER NOT NULL DEFAULT 0,
              vod_category TEXT NOT NULL DEFAULT '',
              vod_year TEXT NOT NULL DEFAULT '',
              vod_rating TEXT NOT NULL DEFAULT '',
              series_name TEXT NOT NULL DEFAULT '',
              season TEXT NOT NULL DEFAULT '',
              episode TEXT NOT NULL DEFAULT '',
              container_extension TEXT NOT NULL DEFAULT '',
              favorite INTEGER NOT NULL DEFAULT 0,
              hidden INTEGER NOT NULL DEFAULT 0,
              blocked INTEGER NOT NULL DEFAULT 0,
              in_my_list INTEGER NOT NULL DEFAULT 0,
              order_index INTEGER NOT NULL DEFAULT 0,
              date_added INTEGER NOT NULL DEFAULT 0,
              last_modified INTEGER NOT NULL DEFAULT 0,
              watch_time_ms INTEGER NOT NULL DEFAULT 0,
              last_watched INTEGER NOT NULL DEFAULT 0,
              progress_ms INTEGER NOT NULL DEFAULT 0,
              duration_ms INTEGER NOT NULL DEFAULT 0,
              is_series INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ch_playlist ON channels(playlist_id)")
        db.execSQL("CREATE INDEX idx_ch_group ON channels(playlist_id, group_title)")
        db.execSQL("CREATE INDEX idx_ch_series ON channels(series_name)")
        db.execSQL(
            """
            CREATE TABLE programs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              channel_id INTEGER NOT NULL DEFAULT 0,
              tvg_id TEXT NOT NULL DEFAULT '',
              title TEXT NOT NULL DEFAULT '',
              subtitle TEXT NOT NULL DEFAULT '',
              description TEXT NOT NULL DEFAULT '',
              category TEXT NOT NULL DEFAULT '',
              icon TEXT NOT NULL DEFAULT '',
              start INTEGER NOT NULL DEFAULT 0,
              stop INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_pr_channel ON programs(channel_id, start)")
        db.execSQL(
            """
            CREATE TABLE epg_sources (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL DEFAULT '',
              url TEXT NOT NULL DEFAULT '',
              playlist_id INTEGER NOT NULL DEFAULT 0,
              priority INTEGER NOT NULL DEFAULT 0,
              last_update INTEGER NOT NULL DEFAULT 0,
              enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE reminders (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              channel_id INTEGER NOT NULL DEFAULT 0,
              program_title TEXT NOT NULL DEFAULT '',
              start INTEGER NOT NULL DEFAULT 0,
              minutes_before INTEGER NOT NULL DEFAULT 5,
              action INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE history (
              channel_id INTEGER PRIMARY KEY,
              watched_at INTEGER NOT NULL DEFAULT 0,
              watch_time_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_hist_watched ON history(watched_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            // v1 -> v2: playlists.mac, required by the Stalker portal handshake
            if (!db.columnExists("playlists", "mac")) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN mac TEXT NOT NULL DEFAULT ''")
            }
            return
        }
        db.execSQL("DROP TABLE IF EXISTS playlists")
        db.execSQL("DROP TABLE IF EXISTS channels")
        db.execSQL("DROP TABLE IF EXISTS programs")
        db.execSQL("DROP TABLE IF EXISTS epg_sources")
        db.execSQL("DROP TABLE IF EXISTS reminders")
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    private fun SQLiteDatabase.columnExists(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (c.getString(nameIndex) == column) return true
            }
            false
        }

    companion object {
        const val DB_NAME = "tvbro.db"
        const val DB_VERSION = 2
    }
}

// ---------------------------------------------------------------- cursors

internal fun Cursor.str(name: String): String {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) "" else getString(i)
}

internal fun Cursor.long(name: String, def: Long = 0): Long {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) def else getLong(i)
}

internal fun Cursor.int(name: String, def: Int = 0): Int {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) def else getInt(i)
}

internal fun Cursor.bool(name: String): Boolean = int(name) != 0

internal fun Cursor.toPlaylist(): Playlist = Playlist(
    id = long("id"),
    name = str("name"),
    type = PlaylistType.from(int("type")),
    url = str("url"),
    login = str("login"),
    password = str("password"),
    userAgent = str("user_agent"),
    epgUrl = str("epg_url"),
    mac = str("mac"),
    enabled = bool("enabled"),
    orderIndex = int("order_index"),
    dateAdded = long("date_added"),
    lastUpdate = long("last_update"),
    hidden = bool("hidden"),
)

internal fun Cursor.toChannel(): Channel = Channel(
    id = long("id"),
    playlistId = long("playlist_id"),
    playlistName = "",
    streamId = str("stream_id"),
    name = str("name"),
    number = str("number"),
    logoUrl = str("logo_url"),
    groupTitle = str("group_title"),
    tvgId = str("tvg_id"),
    tvgName = str("tvg_name"),
    url = str("url"),
    catchupSource = str("catchup_source"),
    catchupDays = int("catchup_days"),
    catchupType = str("catchup_type"),
    isVod = bool("is_vod"),
    vodCategory = str("vod_category"),
    vodYear = str("vod_year"),
    vodRating = str("vod_rating"),
    seriesName = str("series_name"),
    season = str("season"),
    episode = str("episode"),
    containerExtension = str("container_extension"),
    favorite = bool("favorite"),
    hidden = bool("hidden"),
    blocked = bool("blocked"),
    inMyList = bool("in_my_list"),
    orderIndex = int("order_index"),
    dateAdded = long("date_added"),
    lastModified = long("last_modified"),
    watchTimeMs = long("watch_time_ms"),
    lastWatched = long("last_watched"),
    progressMs = long("progress_ms"),
    durationMs = long("duration_ms"),
    isSeries = bool("is_series"),
)

/**
 * Reads a programme out of a row. [epgOffsetMs] moves it onto the wall clock when the user has put
 * the guide on a different time from the source's, and stays out of the way when they have not.
 */
internal fun Cursor.toProgram(epgOffsetMs: Long = 0): Program = Program(
    id = long("id"),
    channelId = long("channel_id"),
    tvgId = str("tvg_id"),
    title = str("title"),
    subtitle = str("subtitle"),
    description = str("description"),
    category = str("category"),
    icon = str("icon"),
    start = long("start") + epgOffsetMs,
    stop = long("stop") + epgOffsetMs,
)

internal fun Cursor.toEpgSource(): EpgSource = EpgSource(
    id = long("id"),
    name = str("name"),
    url = str("url"),
    playlistId = long("playlist_id"),
    priority = int("priority"),
    lastUpdate = long("last_update"),
    enabled = bool("enabled"),
)

internal fun Cursor.toReminder(): Reminder = Reminder(
    id = long("id"),
    channelId = long("channel_id"),
    programTitle = str("program_title"),
    start = long("start"),
    minutesBefore = int("minutes_before"),
    action = int("action"),
)

internal fun Cursor.toHistoryEntry(): HistoryEntry = HistoryEntry(
    channelId = long("channel_id"),
    watchedAt = long("watched_at"),
    watchTimeMs = long("watch_time_ms"),
)

internal fun cv(block: ContentValues.() -> Unit): ContentValues = ContentValues().apply(block)
