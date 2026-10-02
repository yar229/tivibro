package com.tvibro.data.source

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tvibro.TvBroApp
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.model.Channel
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.PlaylistType
import com.tvibro.data.model.Program
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How far an EPG update has got for the source that is running right now, counted in channels of
 * the playlist. There is no separate state for the file coming in: the archive is read while it
 * arrives and the programmes go straight into the database, so "downloading" is the same work as
 * "parsing" and telling them apart would say nothing about how far the update has come.
 */
data class EpgProgress(
    val label: String,
    val channels: Int = 0,
    val channelsTotal: Int = 0,
)

class SourceManager(context: Context) {

    private val repo = TvBroRepository.get(context)
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-update").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    val isRunning: Boolean get() = running.get()

    // A screen that did not start the update itself still follows it through this. It is read on
    // the main thread, so a screen that has gone away is never called back into.
    @Volatile
    private var observer: ((EpgProgress?) -> Unit)? = null

    fun watch(progress: (EpgProgress?) -> Unit) {
        observer = progress
    }

    fun stopWatching() {
        observer = null
    }

    private fun publish(progress: EpgProgress?) {
        if (observer == null) return
        onMain { observer?.invoke(progress) }
    }

    // Work runs on the update thread, callbacks are always delivered on the main
    // thread so callers can touch dialogs, toasts and adapters directly.
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    // ------------------------------------------------------------- playlists

    fun loadPlaylistChannels(playlist: Playlist): List<Channel> = when (playlist.type) {
        PlaylistType.FILE -> loadFromFile(playlist)
        PlaylistType.REMOTE_M3U -> M3uParser.parseUrl(playlist.url, playlist.userAgent).channels
        PlaylistType.XTREAM -> apiFor(playlist).channels()
        PlaylistType.STALKER -> StalkerApi(playlist.url, playlist.mac.ifBlank { null }).loadChannels()
    }

    fun refreshPlaylist(playlistId: Long, onDone: (Result<Pair<Int, Int>>) -> Unit) {
        executor.execute {
            running.set(true)
            val result = runCatching {
                val playlist = repo.playlist(playlistId) ?: error("Playlist not found")
                val fresh = loadPlaylistChannels(playlist)
                if (fresh.isEmpty()) error("No channels found")
                val now = System.currentTimeMillis()
                val stored = repo.replaceChannels(playlistId, fresh)
                repo.setPlaylistLastUpdate(playlistId, now)
                val programs = if (epgOnChange()) {
                    loadEpgFor(playlist, stored)
                } else {
                    emptyMap<Long, List<Program>>()
                }
                programs.size to stored.size
            }
            running.set(false)
            onMain { onDone(result) }
        }
    }

    fun refreshAllPlaylists(onProgress: (Int, Int) -> Unit, onDone: (String?) -> Unit) {
        executor.execute {
            running.set(true)
            val playlists = repo.playlists(onlyEnabled = true)
            var errors = 0
            var lastError: String? = null
            playlists.forEachIndexed { index, playlist ->
                try {
                    val fresh = loadPlaylistChannels(playlist)
                    if (fresh.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        val stored = repo.replaceChannels(playlist.id, fresh)
                        repo.setPlaylistLastUpdate(playlist.id, now)
                        if (epgOnChange()) loadEpgFor(playlist, stored)
                    }
                } catch (e: Exception) {
                    errors++
                    lastError = e.message
                }
                onMain { onProgress(index + 1, playlists.size) }
            }
            running.set(false)
            onMain { onDone(if (errors > 0) "$errors playlist(s) failed: $lastError" else null) }
        }
    }

    fun deletePlaylistData(playlistId: Long) = executor.execute { repo.deletePlaylist(playlistId) }

    // ------------------------------------------------------------------- epg

    fun loadEpgFor(
        playlist: Playlist,
        channels: List<Channel>? = null,
        onProgress: ((EpgProgress) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ): Map<Long, List<Program>> {
        val sources = repo.epgSourcesForAny(playlist.id)
        val urls = LinkedHashMap<String, Long?>()
        val namesById = HashMap<Long, String>()
        playlist.epgUrl.takeIf { it.isNotBlank() }?.let { urls[it] = null }
        sources.forEach {
            urls.putIfAbsent(it.url, it.id)
            namesById[it.id] = it.name
        }
        if (urls.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val from = now - 2 * 24 * 3600_000L
        val to = now + 7 * 24 * 3600_000L
        val list = channels ?: repo.channels(
            listOf(playlist.id), "", ChannelFilter.TV, "order", showHidden = true, showBlocked = true
        )
        val byId = HashMap<String, Channel>()
        val byName = HashMap<String, Channel>()
        list.forEach { ch ->
            ch.tvgId.takeIf { it.isNotBlank() }?.let { byId[it.lowercase(Locale.US)] = ch }
            byName[ch.name.trim().lowercase(Locale.US)] = ch
        }
        val result = HashMap<Long, MutableList<Program>>()

        urls.forEach { (url, sourceId) ->
            val label = sourceId?.let { namesById[it] } ?: playlist.name
            onProgress?.invoke(EpgProgress(label, 0, list.size))
            try {
                // The file is read while it downloads and the parser keeps only the channels of
                // this playlist: a full EPG archive is far too big to hold in memory twice.
                Http.openStream(
                    url,
                    playlist.userAgent,
                    readTimeoutMs = 120000,
                ).use { input ->
                    val filled = HashSet<Long>()
                    XmltvParser(
                        storeDescriptions = true,
                        acceptChannel = { key -> byId.containsKey(key) || byName.containsKey(key) },
                        onProgramme = { p ->
                            val key = p.tvgId.trim().lowercase(Locale.US)
                            val channel = byId[key] ?: byName[key]
                            if (channel != null && p.stop > from && p.start < to) {
                                p.channelId = channel.id
                                result.getOrPut(p.channelId) { ArrayList() } += p
                                // Only a channel that was not filled yet changes the number, which
                                // keeps the callback down to one call per channel of the playlist.
                                if (filled.add(channel.id)) {
                                    onProgress?.invoke(EpgProgress(label, filled.size, list.size))
                                }
                            }
                        }
                    ).parse(input)
                }
                sourceId?.let { repo.setEpgSourceLastUpdate(it, System.currentTimeMillis()) }
            } catch (e: Exception) {
                // The other sources are still worth trying, but a source that quietly fails looks
                // exactly like a playlist that has no EPG at all, so the reason has to leave here.
                Log.w(TAG, "EPG source failed: $url", e)
                onError?.invoke("${playlist.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        val stored = HashMap<Long, List<Program>>()
        result.forEach { (channelId, programs) ->
            repo.replacePrograms(listOf(channelId), programs)
            stored[channelId] = programs
        }
        return stored
    }

    fun refreshEpgForPlaylist(playlistId: Long, onDone: (Int) -> Unit) {
        fetchEpgForPlaylist(playlistId, onDone)
    }

    /**
     * The EPG for a playlist that has just been written. Its channels are already in the database,
     * so only the programmes have to be pulled for them.
     *
     * Without this a brand new playlist sits in the guide with every channel empty until the next
     * scheduled EPG update, which is hours away: the wizard that created the playlist is exactly
     * the moment where its EPG is known and has never been downloaded.
     */
    fun refreshEpgOnChange(playlistId: Long, onDone: ((Int) -> Unit)? = null) {
        if (!epgOnChange()) {
            if (onDone != null) onMain { onDone(0) }
            return
        }
        fetchEpgForPlaylist(playlistId) { count -> onDone?.invoke(count) }
    }

    /**
     * Whether a change to the playlists should bring the EPG along with it.
     *
     * A refreshed playlist can come back with channels no programme has ever been matched to, so
     * the two updates normally belong together. A user who keeps them apart - to save the traffic of
     * a large xmltv archive, or to update the EPG on their own schedule - turns this off, and the
     * scheduled and manual EPG updates still work.
     */
    private fun epgOnChange(): Boolean = TvBroApp.prefs(appContext).updateOnChange

    private fun fetchEpgForPlaylist(playlistId: Long, onDone: (Int) -> Unit) {
        executor.execute {
            running.set(true)
            val playlist = repo.playlist(playlistId)
            val count = if (playlist == null) 0 else loadEpgFor(
                playlist,
                onProgress = { publish(it) },
            ).values.sumOf { it.size }
            running.set(false)
            publish(null)
            onMain { onDone(count) }
        }
    }

    fun refreshAllEpg(onProgress: (EpgProgress) -> Unit = {}, onDone: (Int, String?) -> Unit) {
        executor.execute {
            running.set(true)
            var total = 0
            val failures = ArrayList<String>()
            repo.playlists(onlyEnabled = true).forEach { playlist ->
                total += loadEpgFor(
                    playlist,
                    onProgress = { local ->
                        publish(local)
                        onProgress(local)
                    },
                    onError = { failures += it },
                ).values.sumOf { it.size }
            }
            running.set(false)
            val reason = failures.take(3).joinToString("; ").ifEmpty { null }
            publish(null)
            onMain { onDone(total, reason) }
        }
    }

    fun pruneOldPrograms(days: Int, onDone: (() -> Unit)? = null) {
        executor.execute {
            val cutoff = System.currentTimeMillis() - days * 24 * 3600_000L
            repo.clearProgramsBefore(cutoff)
            if (onDone != null) onMain { onDone() }
        }
    }

    // ----------------------------------------------------------------- files

    fun loadFromFile(playlist: Playlist): List<Channel> {
        val file = File(playlist.url)
        if (!file.exists()) {
            // try app external files dir
            val alt = File(android.os.Environment.getExternalStorageDirectory(), playlist.url)
            if (alt.exists()) return M3uParser.parse(alt.inputStream(), null).channels
            error("File not found: ${playlist.url}")
        }
        return M3uParser.parse(file.inputStream(), null).channels
    }

    fun importLocalFile(path: String, name: String, onDone: (Long) -> Unit, onError: (Exception) -> Unit) {
        executor.execute {
            try {
                val parsed = M3uParser.parse(File(path).inputStream(), null)
                if (parsed.channels.isEmpty()) error("Playlist is empty")
                val playlist = Playlist(name = name, type = PlaylistType.FILE, url = path, lastUpdate = System.currentTimeMillis())
                val id = repo.insertPlaylist(playlist)
                repo.replaceChannels(id, parsed.channels)
                onMain { onDone(id) }
            } catch (e: Exception) {
                onMain { onError(e) }
            }
        }
    }

    fun addRemotePlaylist(name: String, url: String, userAgent: String, onDone: (Long, Int) -> Unit, onError: (Exception) -> Unit) {
        executor.execute {
            try {
                val parsed = M3uParser.parseUrl(url, userAgent)
                if (parsed.channels.isEmpty()) error("Playlist is empty")
                val playlist = Playlist(
                    name = name, type = PlaylistType.REMOTE_M3U, url = url,
                    userAgent = userAgent, lastUpdate = System.currentTimeMillis()
                )
                val id = repo.insertPlaylist(playlist)
                repo.replaceChannels(id, parsed.channels)
                onMain { onDone(id, parsed.channels.size) }
            } catch (e: Exception) {
                onMain { onError(e) }
            }
        }
    }

    fun addXtreamPlaylist(
        name: String, server: String, login: String, password: String,
        onDone: (Long, Int) -> Unit, onError: (Exception) -> Unit,
    ) {
        executor.execute {
            try {
                val api = XtreamApi(server, login, password)
                val channels = api.channels()
                if (channels.isEmpty()) error("No channels returned")
                val playlist = Playlist(
                    name = name, type = PlaylistType.XTREAM, url = server,
                    login = login, password = password, lastUpdate = System.currentTimeMillis()
                )
                val id = repo.insertPlaylist(playlist)
                repo.replaceChannels(id, channels)
                onMain { onDone(id, channels.size) }
            } catch (e: Exception) {
                onMain { onError(e) }
            }
        }
    }

    fun apiFor(playlist: Playlist): XtreamApi =
        XtreamApi(playlist.url, playlist.login, playlist.password, playlist.userAgent)

    fun testXtream(server: String, login: String, password: String): String {
        val api = XtreamApi(server, login, password)
        val info = api.userInfo()
        val status = info.optString("status")
        val exp = info.optLong("exp_date", 0L) * 1000
        val days = if (exp > 0) (exp - System.currentTimeMillis()) / 86_400_000L else -1
        return buildString {
            append("Status: ").append(status.ifEmpty { "active" }).append('\n')
            append("User: ").append(info.optString("username", login)).append('\n')
            append("Expires: ").append(if (days >= 0) "$days day(s)" else "unknown").append('\n')
            append("Connections: ").append(info.optString("max_connections", "1")).append('\n')
        }
    }

    fun streamUrl(playlist: Playlist, channel: Channel): String = when (playlist.type) {
        PlaylistType.XTREAM -> apiFor(playlist).streamUrl(channel)
        PlaylistType.STALKER -> StalkerApi.createLink(playlist, channel)
        else -> channel.url
    }

    private companion object {
        const val TAG = "TvBroSources"
    }
}
