package com.tvibro.data.db

import android.content.Context
import com.tvibro.TvBroApp
import com.tvibro.data.EpgOffset
import com.tvibro.data.Prefs
import com.tvibro.data.model.Channel
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.EpgSource
import com.tvibro.data.model.HistoryEntry
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.Program
import com.tvibro.data.model.Reminder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TvBroRepository internal constructor(context: Context) {

    private val appContext = context.applicationContext
    private val helper = TvBroDatabase(appContext)
    private val db get() = helper.writableDatabase

    // group list cache: playlistId -> list of group names in playlist order
    private val groupsCache = ConcurrentHashMap<Long, List<String>>()
    private val playlistNames = ConcurrentHashMap<Long, String>()

    // ------------------------------------------------------------ playlists

    fun playlists(onlyEnabled: Boolean = false): List<Playlist> =
        db.rawQuery(
            "SELECT * FROM playlists " + if (onlyEnabled) "WHERE enabled=1 " else "" +
                "ORDER BY order_index ASC, name COLLATE NOCASE ASC",
            null
        ).use { c ->
            val out = ArrayList<Playlist>()
            while (c.moveToNext()) out += c.toPlaylist()
            out.also { playlistNames.clear(); it.forEach { p -> playlistNames[p.id] = p.name } }
        }

    fun playlist(id: Long): Playlist? =
        db.rawQuery("SELECT * FROM playlists WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toPlaylist() else null }

    fun insertPlaylist(playlist: Playlist): Long =
        db.insertOrThrow("playlists", null, playlist.toValues())

    fun updatePlaylist(playlist: Playlist) {
        db.update("playlists", playlist.toValues(), "id=?", arrayOf(playlist.id.toString()))
    }

    fun setPlaylistEnabled(id: Long, enabled: Boolean) {
        db.update("playlists", cv { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun setPlaylistHidden(id: Long, hidden: Boolean) {
        db.update("playlists", cv { put("hidden", if (hidden) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun setPlaylistOrder(idsInOrder: List<Long>) {
        db.beginTransaction()
        try {
            idsInOrder.forEachIndexed { index, id ->
                db.update("playlists", cv { put("order_index", index) }, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setPlaylistLastUpdate(id: Long, time: Long) {
        db.update("playlists", cv { put("last_update", time) }, "id=?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        db.beginTransaction()
        try {
            db.delete("programs", "channel_id IN (SELECT id FROM channels WHERE playlist_id=?)", arrayOf(id.toString()))
            db.delete("channels", "playlist_id=?", arrayOf(id.toString()))
            db.delete("epg_sources", "playlist_id=?", arrayOf(id.toString()))
            db.delete("playlists", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        groupsCache.remove(id)
    }

    private fun Playlist.toValues() = cv {
        put("name", name)
        put("type", type.id)
        put("url", url)
        put("login", login)
        put("password", password)
        put("user_agent", userAgent)
        put("epg_url", epgUrl)
        put("mac", mac)
        put("enabled", if (enabled) 1 else 0)
        put("order_index", orderIndex)
        put("date_added", dateAdded)
        put("last_update", lastUpdate)
        put("hidden", if (hidden) 1 else 0)
    }

    fun playlistName(id: Long): String = playlistNames[id] ?: ""

    // ------------------------------------------------------------- channels

    fun groupsOf(playlistId: Long): List<String> = groupsCache[playlistId] ?: run {
        val out = ArrayList<String>()
        db.rawQuery(
            "SELECT DISTINCT group_title FROM channels WHERE playlist_id=? AND group_title<>'' ORDER BY order_index ASC",
            arrayOf(playlistId.toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        groupsCache[playlistId] = out
        out
    }

    fun allGroups(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        db.rawQuery(
            "SELECT playlist_id, DISTINCT group_title FROM channels WHERE group_title<>'' ORDER BY playlist_id, order_index",
            null
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getString(1) }
        return out
    }

    fun countChannels(playlistId: Long, vod: Boolean? = null): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM channels WHERE playlist_id=?" + (vod?.let { " AND is_vod=" + if (it) 1 else 0 } ?: ""),
            arrayOf(playlistId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun channel(id: Long): Channel? =
        db.rawQuery("SELECT * FROM channels WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toChannel() else null }

    fun channelByStream(playlistId: Long, streamId: String): Channel? =
        db.rawQuery(
            "SELECT * FROM channels WHERE playlist_id=? AND stream_id=? LIMIT 1",
            arrayOf(playlistId.toString(), streamId)
        ).use { if (it.moveToFirst()) it.toChannel() else null }

    /**
     * Main channel list query.
     *
     * @param group empty means "all groups".
     * @param sort one of name|order|date_added|last_modified|watch_time|manual
     */
    fun channels(
        playlistIds: List<Long>,
        group: String,
        filter: ChannelFilter,
        sort: String,
        showHidden: Boolean = false,
        showBlocked: Boolean = false,
        search: String? = null,
        limit: Int = 0,
    ): List<Channel> {
        if (playlistIds.isEmpty()) return emptyList()
        val args = ArrayList<String>()
        val where = ArrayList<String>()

        if (playlistIds.size == 1) {
            where += "playlist_id=?"
            args += playlistIds[0].toString()
        } else {
            where += "playlist_id IN (${playlistIds.joinToString(",") { "?" }})"
            args += playlistIds.map { it.toString() }
        }

        if (filter == ChannelFilter.TV) where += "is_vod=0"
        if (filter == ChannelFilter.FAVORITES) where += "favorite=1"
        if (group.isNotEmpty() && group != ALL_GROUPS) {
            where += "group_title=?"
            args += group
        }
        if (!showHidden) where += "hidden=0"
        if (!showBlocked) where += "blocked=0"
        search?.takeIf { it.isNotBlank() }?.let {
            where += "(name LIKE ? OR tvg_name LIKE ? OR number LIKE ?)"
            val p = "%$it%"
            args += p; args += p; args += p
        }

        val order = when (sort) {
            "name" -> "name COLLATE NOCASE ASC"
            "date_added" -> "date_added ASC"
            "last_modified" -> "last_modified ASC"
            "watch_time" -> "watch_time_ms DESC"
            "manual" -> "order_index ASC"
            else -> "number ASC, order_index ASC, name COLLATE NOCASE ASC"
        }
        val sql = "SELECT * FROM channels WHERE ${where.joinToString(" AND ")} ORDER BY $order" +
            if (limit > 0) " LIMIT $limit" else ""
        return db.rawQuery(sql, args.toTypedArray()).use { c ->
            val out = ArrayList<Channel>()
            while (c.moveToNext()) {
                val ch = c.toChannel()
                ch.playlistName = playlistName(ch.playlistId)
                out += ch
            }
            out
        }
    }

    fun historyChannels(limit: Int): List<Channel> =
        db.rawQuery(
            """
            SELECT c.* FROM channels c
            INNER JOIN history h ON h.channel_id = c.id
            WHERE c.hidden = 0
            ORDER BY h.watched_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            val out = ArrayList<Channel>()
            while (c.moveToNext()) {
                val ch = c.toChannel()
                ch.playlistName = playlistName(ch.playlistId)
                out += ch
            }
            out
        }

    fun insertChannels(playlistId: Long, channels: List<Channel>) {
        if (channels.isEmpty()) return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                """
                INSERT INTO channels (playlist_id, stream_id, name, number, logo_url, group_title, tvg_id, tvg_name,
                  url, catchup_source, catchup_days, catchup_type, is_vod, vod_category, vod_year, vod_rating,
                  series_name, season, episode, container_extension, order_index, date_added, last_modified,
                  duration_ms, is_series)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            channels.forEach { ch ->
                stmt.clearBindings()
                stmt.bindLong(1, playlistId)
                stmt.bindString(2, ch.streamId)
                stmt.bindString(3, ch.name)
                stmt.bindString(4, ch.number)
                stmt.bindString(5, ch.logoUrl)
                stmt.bindString(6, ch.groupTitle)
                stmt.bindString(7, ch.tvgId)
                stmt.bindString(8, ch.tvgName)
                stmt.bindString(9, ch.url)
                stmt.bindString(10, ch.catchupSource)
                stmt.bindLong(11, ch.catchupDays.toLong())
                stmt.bindString(12, ch.catchupType)
                stmt.bindLong(13, if (ch.isVod) 1 else 0)
                stmt.bindString(14, ch.vodCategory)
                stmt.bindString(15, ch.vodYear)
                stmt.bindString(16, ch.vodRating)
                stmt.bindString(17, ch.seriesName)
                stmt.bindString(18, ch.season)
                stmt.bindString(19, ch.episode)
                stmt.bindString(20, ch.containerExtension)
                stmt.bindLong(21, ch.orderIndex.toLong())
                stmt.bindLong(22, ch.dateAdded)
                stmt.bindLong(23, ch.lastModified)
                stmt.bindLong(24, ch.durationMs)
                stmt.bindLong(25, if (ch.isSeries) 1 else 0)
                stmt.executeInsert()
            }
            stmt.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        groupsCache.remove(playlistId)
    }

    /**
     * Synchronises the playlist with [channels], keeping user state (favorite, hidden, blocked,
     * my list, manual order and watch statistics) matched by stream id.
     *
     * Rows are updated in place, so channel ids stay stable and the references from
     * programs, history and reminders keep pointing at the same channel. Streams that
     * disappeared from the playlist are removed together with their EPG and history data.
     *
     * @return the stored channels with their database ids.
     */
    fun replaceChannels(playlistId: Long, channels: List<Channel>): List<Channel> {
        val existing = db.rawQuery(
            "SELECT id, stream_id, favorite, hidden, blocked, in_my_list, order_index, date_added, " +
                "watch_time_ms, last_watched, progress_ms, duration_ms FROM channels WHERE playlist_id=?",
            arrayOf(playlistId.toString())
        ).use { c ->
            val map = LinkedHashMap<String, StoredChannelState>()
            while (c.moveToNext()) {
                val streamId = c.getString(1)
                if (streamId.isEmpty() || map.containsKey(streamId)) continue
                map[streamId] = StoredChannelState(
                    id = c.getLong(0),
                    favorite = c.getInt(2) != 0,
                    hidden = c.getInt(3) != 0,
                    blocked = c.getInt(4) != 0,
                    inMyList = c.getInt(5) != 0,
                    orderIndex = c.getInt(6),
                    dateAdded = c.getLong(7),
                    watchTimeMs = c.getLong(8),
                    lastWatched = c.getLong(9),
                    progressMs = c.getLong(10),
                    durationMs = c.getLong(11),
                )
            }
            map
        }

        val now = System.currentTimeMillis()
        val staleIds = ArrayList<Long>()

        db.beginTransaction()
        try {
            val retained = HashSet<String>()
            channels.forEach { ch ->
                if (!retained.add(ch.streamId)) return@forEach
                ch.playlistId = playlistId
                val old = existing[ch.streamId]
                if (old == null) {
                    ch.dateAdded = now
                    ch.lastModified = now
                    ch.id = insertChannelRow(playlistId, ch)
                } else {
                    ch.id = old.id
                    ch.favorite = old.favorite
                    ch.hidden = old.hidden
                    ch.blocked = old.blocked
                    ch.inMyList = old.inMyList
                    ch.orderIndex = old.orderIndex
                    ch.dateAdded = old.dateAdded
                    ch.watchTimeMs = old.watchTimeMs
                    ch.lastWatched = old.lastWatched
                    ch.progressMs = old.progressMs
                    ch.durationMs = if (ch.durationMs > 0) ch.durationMs else old.durationMs
                    ch.lastModified = now
                    updateChannelRow(ch)
                }
            }

            existing.forEach { (streamId, state) ->
                if (streamId !in retained) staleIds += state.id
            }
            staleIds.forEach { id ->
                db.delete("programs", "channel_id=?", arrayOf(id.toString()))
                db.delete("history", "channel_id=?", arrayOf(id.toString()))
                db.delete("reminders", "channel_id=?", arrayOf(id.toString()))
                db.delete("channels", "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        groupsCache.remove(playlistId)

        return channels
            .distinctBy { it.streamId }
            .map { ch -> channel(ch.id) ?: ch }
    }

    private fun insertChannelRow(playlistId: Long, ch: Channel): Long = db.compileStatement(
        """
        INSERT INTO channels (playlist_id, stream_id, name, number, logo_url, group_title, tvg_id, tvg_name,
          url, catchup_source, catchup_days, catchup_type, is_vod, vod_category, vod_year, vod_rating,
          series_name, season, episode, container_extension, order_index, date_added, last_modified,
          duration_ms, is_series)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()
    ).use { stmt ->
        stmt.bindLong(1, playlistId)
        stmt.bindString(2, ch.streamId)
        stmt.bindString(3, ch.name)
        stmt.bindString(4, ch.number)
        stmt.bindString(5, ch.logoUrl)
        stmt.bindString(6, ch.groupTitle)
        stmt.bindString(7, ch.tvgId)
        stmt.bindString(8, ch.tvgName)
        stmt.bindString(9, ch.url)
        stmt.bindString(10, ch.catchupSource)
        stmt.bindLong(11, ch.catchupDays.toLong())
        stmt.bindString(12, ch.catchupType)
        stmt.bindLong(13, if (ch.isVod) 1 else 0)
        stmt.bindString(14, ch.vodCategory)
        stmt.bindString(15, ch.vodYear)
        stmt.bindString(16, ch.vodRating)
        stmt.bindString(17, ch.seriesName)
        stmt.bindString(18, ch.season)
        stmt.bindString(19, ch.episode)
        stmt.bindString(20, ch.containerExtension)
        stmt.bindLong(21, ch.orderIndex.toLong())
        stmt.bindLong(22, ch.dateAdded)
        stmt.bindLong(23, ch.lastModified)
        stmt.bindLong(24, ch.durationMs)
        stmt.bindLong(25, if (ch.isSeries) 1 else 0)
        stmt.executeInsert()
    }

    private fun updateChannelRow(ch: Channel) {
        db.execSQL(
            """
            UPDATE channels SET name=?, number=?, logo_url=?, group_title=?, tvg_id=?, tvg_name=?,
              url=?, catchup_source=?, catchup_days=?, catchup_type=?, is_vod=?, vod_category=?,
              vod_year=?, vod_rating=?, series_name=?, season=?, episode=?, container_extension=?,
              last_modified=?, duration_ms=?, is_series=? WHERE id=?
            """.trimIndent(),
            arrayOf(
                ch.name, ch.number, ch.logoUrl, ch.groupTitle, ch.tvgId, ch.tvgName,
                ch.url, ch.catchupSource, ch.catchupDays, ch.catchupType, if (ch.isVod) 1 else 0,
                ch.vodCategory, ch.vodYear, ch.vodRating, ch.seriesName, ch.season, ch.episode,
                ch.containerExtension, ch.lastModified, ch.durationMs, if (ch.isSeries) 1 else 0, ch.id
            )
        )
    }

    private data class StoredChannelState(
        val id: Long,
        val favorite: Boolean,
        val hidden: Boolean,
        val blocked: Boolean,
        val inMyList: Boolean,
        val orderIndex: Int,
        val dateAdded: Long,
        val watchTimeMs: Long,
        val lastWatched: Long,
        val progressMs: Long,
        val durationMs: Long,
    )

    /**
     * Drops what the guide no longer shows. [cutoff] is a wall-clock instant, so it is walked on the
     * stored scale here: with the guide moved forward, a row that is still on screen would sit on
     * the wrong side of the line and be thrown away while it is still the programme on air.
     */
    fun clearProgramsBefore(cutoff: Long) {
        db.delete("programs", "stop < ?", arrayOf(EpgOffset.toStored(cutoff, epgOffsetMs).toString()))
    }

    fun deleteChannel(id: Long) {
        db.beginTransaction()
        try {
            db.delete("programs", "channel_id=?", arrayOf(id.toString()))
            db.delete("history", "channel_id=?", arrayOf(id.toString()))
            db.delete("reminders", "channel_id=?", arrayOf(id.toString()))
            db.delete("channels", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateChannel(channel: Channel) {
        db.update("channels", channel.toValues(), "id=?", arrayOf(channel.id.toString()))
    }

    fun setChannelFlags(id: Long, favorite: Boolean? = null, hidden: Boolean? = null, blocked: Boolean? = null, inMyList: Boolean? = null) {
        val v = cv {
            favorite?.let { put("favorite", if (it) 1 else 0) }
            hidden?.let { put("hidden", if (it) 1 else 0) }
            blocked?.let { put("blocked", if (it) 1 else 0) }
            inMyList?.let { put("in_my_list", if (it) 1 else 0) }
        }
        if (v.size() == 0) return
        db.update("channels", v, "id=?", arrayOf(id.toString()))
    }

    fun setChannelFlagsBulk(ids: List<Long>, favorite: Boolean? = null, hidden: Boolean? = null, blocked: Boolean? = null, inMyList: Boolean? = null) {
        if (ids.isEmpty()) return
        val v = cv {
            favorite?.let { put("favorite", if (it) 1 else 0) }
            hidden?.let { put("hidden", if (it) 1 else 0) }
            blocked?.let { put("blocked", if (it) 1 else 0) }
            inMyList?.let { put("in_my_list", if (it) 1 else 0) }
        }
        if (v.size() == 0) return
        val placeholders = ids.joinToString(",") { "?" }
        db.update("channels", v, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }

    fun setProgress(id: Long, progressMs: Long, durationMs: Long) {
        db.update("channels", cv {
            put("progress_ms", progressMs)
            if (durationMs > 0) put("duration_ms", durationMs)
        }, "id=?", arrayOf(id.toString()))
    }

    fun setChannelOrder(idsInOrder: List<Long>) {
        db.beginTransaction()
        try {
            idsInOrder.forEachIndexed { index, id ->
                db.update("channels", cv { put("order_index", index) }, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addWatchTime(id: Long, deltaMs: Long) {
        if (deltaMs <= 0) return
        db.execSQL(
            "UPDATE channels SET watch_time_ms = watch_time_ms + ?, last_watched = ? WHERE id = ?",
            arrayOf(deltaMs, System.currentTimeMillis(), id)
        )
    }

    private fun Channel.toValues() = cv {
        put("playlist_id", playlistId)
        put("stream_id", streamId)
        put("name", name)
        put("number", number)
        put("logo_url", logoUrl)
        put("group_title", groupTitle)
        put("tvg_id", tvgId)
        put("tvg_name", tvgName)
        put("url", url)
        put("catchup_source", catchupSource)
        put("catchup_days", catchupDays)
        put("catchup_type", catchupType)
        put("is_vod", if (isVod) 1 else 0)
        put("vod_category", vodCategory)
        put("vod_year", vodYear)
        put("vod_rating", vodRating)
        put("series_name", seriesName)
        put("season", season)
        put("episode", episode)
        put("container_extension", containerExtension)
        put("favorite", if (favorite) 1 else 0)
        put("hidden", if (hidden) 1 else 0)
        put("blocked", if (blocked) 1 else 0)
        put("in_my_list", if (inMyList) 1 else 0)
        put("order_index", orderIndex)
        put("date_added", dateAdded)
        put("last_modified", lastModified)
        put("watch_time_ms", watchTimeMs)
        put("last_watched", lastWatched)
        put("progress_ms", progressMs)
        put("duration_ms", durationMs)
        put("is_series", if (isSeries) 1 else 0)
    }

    // -------------------------------------------------------------- programs

    /**
     * The user's shift of the guide in milliseconds, read per query rather than kept here: the
     * setting can be moved at any moment and nothing has to be rebuilt or reimported for it.
     */
    private val epgOffsetMs: Long
        get() = EpgOffset.ms(Prefs.get(appContext).epgOffsetMinutes)

    fun programsFor(channelId: Long, from: Long, to: Long): List<Program> =
        db.rawQuery(
            "SELECT * FROM programs WHERE channel_id=? AND stop>? AND start<? ORDER BY start ASC",
            arrayOf(
                channelId.toString(),
                EpgOffset.toStored(from, epgOffsetMs).toString(),
                EpgOffset.toStored(to, epgOffsetMs).toString(),
            )
        ).use { c -> collectPrograms(c) }

    fun programsForChannels(channelIds: List<Long>, from: Long, to: Long): Map<Long, List<Program>> {
        if (channelIds.isEmpty()) return emptyMap()
        val ph = channelIds.joinToString(",") { "?" }
        val args = arrayListOf(
            EpgOffset.toStored(from, epgOffsetMs).toString(),
            EpgOffset.toStored(to, epgOffsetMs).toString(),
        )
        args += channelIds.map { it.toString() }
        return db.rawQuery(
            "SELECT * FROM programs WHERE stop>? AND start<? AND channel_id IN ($ph) ORDER BY channel_id, start ASC",
            args.toTypedArray()
        ).use { c ->
            val map = HashMap<Long, MutableList<Program>>()
            while (c.moveToNext()) {
                val p = c.toProgram(epgOffsetMs)
                map.getOrPut(p.channelId) { ArrayList() } += p
            }
            map
        }
    }

    fun currentProgram(channelId: Long, now: Long = System.currentTimeMillis()): Program? {
        val at = EpgOffset.toStored(now, epgOffsetMs)
        return db.rawQuery(
            "SELECT * FROM programs WHERE channel_id=? AND start<=? AND stop>? ORDER BY start DESC LIMIT 1",
            arrayOf(channelId.toString(), at.toString(), at.toString())
        ).use { if (it.moveToFirst()) it.toProgram(epgOffsetMs) else null }
    }

    fun programAt(channelId: Long, time: Long): Program? {
        val at = EpgOffset.toStored(time, epgOffsetMs)
        return db.rawQuery(
            "SELECT * FROM programs WHERE channel_id=? AND start<=? AND stop>? ORDER BY start DESC LIMIT 1",
            arrayOf(channelId.toString(), at.toString(), at.toString())
        ).use { if (it.moveToFirst()) it.toProgram(epgOffsetMs) else null }
    }

    fun programsMapFor(channelIds: List<Long>, now: Long): Map<Long, Program> {
        if (channelIds.isEmpty()) return emptyMap()
        val ph = channelIds.joinToString(",") { "?" }
        val at = EpgOffset.toStored(now, epgOffsetMs)
        val args = arrayListOf(at.toString(), at.toString())
        args += channelIds.map { it.toString() }
        return db.rawQuery(
            "SELECT p.* FROM programs p INNER JOIN (" +
                "SELECT channel_id, MAX(start) AS ms FROM programs WHERE stop>? AND start<? AND channel_id IN ($ph) GROUP BY channel_id" +
                ") t ON p.channel_id = t.channel_id AND p.start = t.ms",
            args.toTypedArray()
        ).use { c ->
            val map = HashMap<Long, Program>()
            while (c.moveToNext()) {
                val p = c.toProgram(epgOffsetMs)
                map[p.channelId] = p
            }
            map
        }
    }

    fun replacePrograms(channelIds: List<Long>, programs: List<Program>) {
        if (channelIds.isEmpty()) return
        db.beginTransaction()
        try {
            val ph = channelIds.joinToString(",") { "?" }
            db.delete("programs", "channel_id IN ($ph)", channelIds.map { it.toString() }.toTypedArray())
            val stmt = db.compileStatement(
                "INSERT INTO programs (channel_id, tvg_id, title, subtitle, description, category, icon, start, stop) VALUES (?,?,?,?,?,?,?,?,?)"
            )
            programs.forEach { p ->
                stmt.clearBindings()
                stmt.bindLong(1, p.channelId)
                stmt.bindString(2, p.tvgId)
                stmt.bindString(3, p.title)
                stmt.bindString(4, p.subtitle)
                stmt.bindString(5, p.description)
                stmt.bindString(6, p.category)
                stmt.bindString(7, p.icon)
                stmt.bindLong(8, p.start)
                stmt.bindLong(9, p.stop)
                stmt.executeInsert()
            }
            stmt.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearPrograms(channelIds: List<Long>) {
        if (channelIds.isEmpty()) return
        val ph = channelIds.joinToString(",") { "?" }
        db.delete("programs", "channel_id IN ($ph)", channelIds.map { it.toString() }.toTypedArray())
    }

    fun clearAllPrograms() {
        db.delete("programs", null, null)
    }

    private fun collectPrograms(c: android.database.Cursor): List<Program> {
        val offsetMs = epgOffsetMs
        val out = ArrayList<Program>()
        while (c.moveToNext()) out += c.toProgram(offsetMs)
        return out
    }

    // ------------------------------------------------------------- epg source

    fun epgSources(): List<EpgSource> =
        db.rawQuery("SELECT * FROM epg_sources ORDER BY priority ASC, name ASC", null).use { c ->
            val out = ArrayList<EpgSource>()
            while (c.moveToNext()) out += c.toEpgSource()
            out
        }

    /** Enabled sources bound to [playlistId]; playlist id 0 means "applies to every playlist". */
    fun epgSourcesFor(playlistId: Long): List<EpgSource> =
        db.rawQuery(
            "SELECT * FROM epg_sources WHERE playlist_id=? AND enabled=1 ORDER BY priority ASC",
            arrayOf(playlistId.toString())
        ).use { c ->
            val out = ArrayList<EpgSource>()
            while (c.moveToNext()) out += c.toEpgSource()
            out
        }

    fun epgSourcesForAny(playlistId: Long): List<EpgSource> =
        db.rawQuery(
            "SELECT * FROM epg_sources WHERE playlist_id IN (0, ?) AND enabled=1 ORDER BY priority ASC",
            arrayOf(playlistId.toString())
        ).use { c ->
            val out = ArrayList<EpgSource>()
            while (c.moveToNext()) out += c.toEpgSource()
            out
        }

    fun insertEpgSource(source: EpgSource): Long = db.insertOrThrow("epg_sources", null, cv {
        put("name", source.name)
        put("url", source.url)
        put("playlist_id", source.playlistId)
        put("priority", source.priority)
        put("last_update", source.lastUpdate)
        put("enabled", if (source.enabled) 1 else 0)
    })

    fun updateEpgSource(source: EpgSource) {
        db.update("epg_sources", cv {
            put("name", source.name)
            put("url", source.url)
            put("playlist_id", source.playlistId)
            put("priority", source.priority)
            put("last_update", source.lastUpdate)
            put("enabled", if (source.enabled) 1 else 0)
        }, "id=?", arrayOf(source.id.toString()))
    }

    fun deleteEpgSource(id: Long) {
        db.delete("epg_sources", "id=?", arrayOf(id.toString()))
    }

    fun setEpgSourceLastUpdate(id: Long, time: Long) {
        db.update("epg_sources", cv { put("last_update", time) }, "id=?", arrayOf(id.toString()))
    }

    fun setEpgSourceOrder(idsInOrder: List<Long>) {
        db.beginTransaction()
        try {
            idsInOrder.forEachIndexed { index, id ->
                db.update("epg_sources", cv { put("priority", index) }, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // -------------------------------------------------------------- reminders

    fun reminders(): List<Reminder> =
        db.rawQuery("SELECT * FROM reminders ORDER BY start ASC", null).use { c ->
            val out = ArrayList<Reminder>()
            while (c.moveToNext()) out += c.toReminder()
            out
        }

    fun addReminder(channelId: Long, program: Program, minutesBefore: Int, action: Int): Long =
        db.insertOrThrow("reminders", null, cv {
            put("channel_id", channelId)
            put("program_title", program.title)
            put("start", program.start)
            put("minutes_before", minutesBefore)
            put("action", action)
        })

    fun deleteReminder(id: Long) {
        db.delete("reminders", "id=?", arrayOf(id.toString()))
    }

    fun deleteReminderFor(channelId: Long) {
        db.delete("reminders", "channel_id=?", arrayOf(channelId.toString()))
    }

    // ---------------------------------------------------------------- history

    fun addHistory(channelId: Long, watchTimeMs: Long) {
        // INSERT OR REPLACE instead of UPSERT: ON CONFLICT DO UPDATE needs SQLite 3.24 (API 30+)
        db.execSQL(
            "INSERT OR REPLACE INTO history (channel_id, watched_at, watch_time_ms) VALUES (?,?,?)",
            arrayOf(channelId, System.currentTimeMillis(), watchTimeMs)
        )
    }

    fun removeHistory(channelId: Long) {
        db.delete("history", "channel_id=?", arrayOf(channelId.toString()))
    }

    fun clearHistory() {
        db.delete("history", null, null)
    }

    fun history(): List<HistoryEntry> =
        db.rawQuery("SELECT * FROM history ORDER BY watched_at DESC", null).use { c ->
            val out = ArrayList<HistoryEntry>()
            while (c.moveToNext()) out += c.toHistoryEntry()
            out
        }

    fun lastWatchedChannelId(): Long? =
        db.rawQuery("SELECT channel_id FROM history ORDER BY watched_at DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    // ------------------------------------------------------------------ VOD

    fun vodCategories(series: Boolean, onlyMyList: Boolean = false): List<String> {
        val where = StringBuilder("is_vod=1 AND series_name<>''")
        if (onlyMyList) where.append(" AND in_my_list=1")
        return db.rawQuery(
            "SELECT DISTINCT series_name FROM channels WHERE $where ORDER BY series_name COLLATE NOCASE ASC", null
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }
    }

    fun episodesOf(seriesName: String, playlistIds: List<Long>): List<Channel> {
        if (playlistIds.isEmpty()) return emptyList()
        val ph = playlistIds.joinToString(",") { "?" }
        val args = arrayListOf(seriesName)
        args += playlistIds.map { it.toString() }
        return db.rawQuery(
            "SELECT * FROM channels WHERE series_name=? AND playlist_id IN ($ph) ORDER BY season ASC, episode ASC, order_index ASC",
            args.toTypedArray()
        ).use { c ->
            val out = ArrayList<Channel>()
            while (c.moveToNext()) out += c.toChannel()
            out
        }
    }

    fun seasonsOf(seriesName: String, playlistIds: List<Long>): List<String> =
        episodesOf(seriesName, playlistIds).map { it.season }.filter { it.isNotEmpty() }.distinct().sorted()

    fun channelsByIds(ids: List<Long>): List<Channel> {
        if (ids.isEmpty()) return emptyList()
        val ph = ids.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT * FROM channels WHERE id IN ($ph) ORDER BY number ASC, order_index ASC, name COLLATE NOCASE ASC",
            ids.map { it.toString() }.toTypedArray()
        ).use { c ->
            val out = ArrayList<Channel>()
            while (c.moveToNext()) {
                val ch = c.toChannel()
                ch.playlistName = playlistName(ch.playlistId)
                out += ch
            }
            out
        }
    }

    fun myList(playlistIds: List<Long>): List<Channel> {
        if (playlistIds.isEmpty()) return emptyList()
        val ph = playlistIds.joinToString(",") { "?" }
        val args = playlistIds.map { it.toString() }
        return db.rawQuery(
            "SELECT * FROM channels WHERE playlist_id IN ($ph) AND in_my_list=1 ORDER BY last_modified DESC",
            args.toTypedArray()
        ).use { c ->
            val out = ArrayList<Channel>()
            while (c.moveToNext()) {
                val ch = c.toChannel()
                ch.playlistName = playlistName(ch.playlistId)
                out += ch
            }
            out
        }
    }

    fun vod(playlistIds: List<Long>, series: Boolean, category: String = "", search: String? = null): List<Channel> {
        if (playlistIds.isEmpty()) return emptyList()
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        val ph = playlistIds.joinToString(",") { "?" }
        where += "playlist_id IN ($ph)"
        args += playlistIds.map { it.toString() }
        if (series) {
            where += "series_name<>''"
            if (category.isNotEmpty()) {
                where += "series_name=?"
                args += category
            }
        } else {
            where += "series_name=''"
            if (category.isNotEmpty()) {
                where += "vod_category=?"
                args += category
            }
        }
        search?.takeIf { it.isNotBlank() }?.let {
            where += "name LIKE ?"
            args += "%$it%"
        }
        return db.rawQuery(
            "SELECT * FROM channels WHERE ${where.joinToString(" AND ")} ORDER BY name COLLATE NOCASE ASC", args.toTypedArray()
        ).use { c ->
            val out = ArrayList<Channel>()
            while (c.moveToNext()) {
                val ch = c.toChannel()
                ch.playlistName = playlistName(ch.playlistId)
                out += ch
            }
            out
        }
    }

    fun vodCategoriesOfItems(playlistIds: List<Long>, series: Boolean): List<String> {
        if (playlistIds.isEmpty()) return emptyList()
        val ph = playlistIds.joinToString(",") { "?" }
        val column = if (series) "series_name" else "vod_category"
        val where = "playlist_id IN ($ph) AND is_vod=1 AND $column<>''"
        val args = playlistIds.map { it.toString() }.toTypedArray()
        return db.rawQuery("SELECT DISTINCT $column FROM channels WHERE $where ORDER BY $column COLLATE NOCASE", args)
            .use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out += c.getString(0)
                out
            }
    }

    // ------------------------------------------------------------------ vod

    fun execSQL(sql: String, args: Array<Any> = emptyArray()) {
        db.execSQL(sql, args)
    }

    /** Restores a previously created backup archive, replacing the current database. */
    fun restoreFrom(archive: File): Boolean = runCatching {
        val dbFile = helper.databaseFile()
        val tmp = File(dbFile.parentFile, "${dbFile.name}.restore")
        java.util.zip.ZipFile(archive).use { zip ->
            val entry = zip.getEntry("tvibro.db") ?: return false
            tmp.outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
        }
        db.close()
        listOf("-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        if (!tmp.renameTo(dbFile)) {
            tmp.copyTo(dbFile, overwrite = true)
            tmp.delete()
        }
        groupsCache.clear()
        playlistNames.clear()
        true
    }.getOrDefault(false)

    /** Copies the database file and the settings into a zip archive at [target]. */
    fun backupTo(target: File): Boolean = runCatching {
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val dbFile = helper.databaseFile()
        target.parentFile?.mkdirs()
        val prefsBody = TvBroApp.get().prefs.all().all
            .entries.joinToString("\n") { entry -> "${entry.key}=${entry.value}" }
        java.util.zip.ZipOutputStream(target.outputStream().buffered()).use { zip ->
            if (dbFile.exists()) {
                zip.putNextEntry(java.util.zip.ZipEntry("tvibro.db"))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(java.util.zip.ZipEntry("tvibro_prefs.txt"))
            zip.write(prefsBody.toByteArray())
            zip.closeEntry()
        }
        true
    }.getOrDefault(false)

    fun wipe() {
        db.beginTransaction()
        try {
            listOf("playlists", "channels", "programs", "epg_sources", "reminders", "history").forEach {
                db.delete(it, null, null)
            }
            groupsCache.clear()
            playlistNames.clear()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val ALL_GROUPS = "__all__"

        @Volatile
        private var instance: TvBroRepository? = null

        fun get(context: Context): TvBroRepository =
            instance ?: synchronized(this) {
                instance ?: TvBroRepository(context).also { instance = it }
            }
    }
}
