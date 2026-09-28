package com.tvibro.data.source

import android.content.Context
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

class SourceManager(context: Context) {

    private val repo = TvBroRepository.get(context)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-update").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

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
                val programs = loadEpgFor(playlist, stored)
                programs.size to stored.size
            }
            running.set(false)
            onDone(result)
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
                        loadEpgFor(playlist, stored)
                    }
                } catch (e: Exception) {
                    errors++
                    lastError = e.message
                }
                onProgress(index + 1, playlists.size)
            }
            running.set(false)
            onDone(if (errors > 0) "$errors playlist(s) failed: $lastError" else null)
        }
    }

    fun deletePlaylistData(playlistId: Long) = executor.execute { repo.deletePlaylist(playlistId) }

    // ------------------------------------------------------------------- epg

    fun loadEpgFor(playlist: Playlist, channels: List<Channel>? = null): Map<Long, List<Program>> {
        val sources = repo.epgSourcesForAny(playlist.id)
        val urls = LinkedHashSet<String>()
        playlist.epgUrl.takeIf { it.isNotBlank() }?.let { urls += it }
        sources.forEach { urls += it.url }
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

        urls.forEach { url ->
            try {
                val res = Http.get(url, playlist.userAgent, readTimeoutMs = 120000)
                if (!res.ok) return@forEach
                val parsed = XmltvParser(storeDescriptions = true).parse(res.body.inputStream())
                parsed.programmes.forEach { p ->
                    val key = p.tvgId.trim().lowercase(Locale.US)
                    val channel = byId[key] ?: byName[key] ?: return@forEach
                    if (p.stop <= from || p.start >= to) return@forEach
                    p.channelId = channel.id
                    result.getOrPut(p.channelId) { ArrayList() } += p
                }
            } catch (e: Exception) {
                // skip broken source, keep the rest
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
        executor.execute {
            running.set(true)
            val playlist = repo.playlist(playlistId)
            val count = if (playlist == null) 0 else loadEpgFor(playlist).values.sumOf { it.size }
            running.set(false)
            onDone(count)
        }
    }

    fun refreshAllEpg(onDone: (Int) -> Unit) {
        executor.execute {
            running.set(true)
            var total = 0
            repo.playlists(onlyEnabled = true).forEach { playlist ->
                total += loadEpgFor(playlist).values.sumOf { it.size }
            }
            running.set(false)
            onDone(total)
        }
    }

    fun pruneOldPrograms(days: Int) {
        executor.execute {
            val cutoff = System.currentTimeMillis() - days * 24 * 3600_000L
            repo.clearProgramsBefore(cutoff)
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
                onDone(id)
            } catch (e: Exception) {
                onError(e)
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
                onDone(id, parsed.channels.size)
            } catch (e: Exception) {
                onError(e)
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
                onDone(id, channels.size)
            } catch (e: Exception) {
                onError(e)
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
}
