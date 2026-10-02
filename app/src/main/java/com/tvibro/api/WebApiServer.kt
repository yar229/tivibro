package com.tvibro.api

import android.content.Intent
import fi.iki.elonen.NanoHTTPD
import com.tvibro.TvBroApp
import com.tvibro.base.Fmt
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.EpgSource
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.PlaylistType
import com.tvibro.data.model.Program
import com.tvibro.data.source.M3uParser
import com.tvibro.data.source.StalkerApi
import com.tvibro.data.source.XtreamApi
import com.tvibro.ui.main.MainActivity
import com.tvibro.ui.player.Playback
import com.tvibro.ui.player.PlayerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * A small HTTP server that exposes the player over the network, so a phone or a
 * home-automation system can ask what is on and change the channel.
 *
 * Every request has to carry the api key, either in the X-API-Key header or as an
 * apikey query parameter. Without it the server answers 401 and does nothing.
 *
 * The API is documented in OpenAPI 3.0: the machine-readable description is served
 * at /api/openapi.json and a Swagger UI page at /api/docs and /swagger.
 */
class WebApiServer(
    private val app: TvBroApp,
    port: Int,
    private val apiKey: String,
) : NanoHTTPD("0.0.0.0", port) {

    

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val publicPaths = setOf("/api/openapi.json", "/api/docs", "/swagger")
        if (!publicPaths.contains(uri) && !isAuthorized(session)) {
            return jsonError(Response.Status.UNAUTHORIZED, "unauthorized")
        }
        return try {
            route(session)
        } catch (e: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (apiKey.isEmpty()) return false
        val header = session.headers["x-api-key"]
        val param = session.parameters["apikey"]?.firstOrNull()
        return header == apiKey || param == apiKey
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return when (uri) {
            "/api/status" -> status()
            "/api/channels" -> channels()
            "/api/epg" -> epg(session)
            "/api/history" -> history()
            "/api/playlists/list" -> playlistsList()
            "/api/playlists/add" -> playlistsAdd(session)
            "/api/playlists/remove" -> playlistsRemove(session)
            "/api/play" -> play(session)
            "/api/stop" -> shutdown()
            "/api/next" -> next()
            "/api/prev" -> prev()
            "/api/remote" -> remote(session)
            "/api/openapi.json" -> openApi(session)
            "/api/docs" -> swaggerUi()
            "/swagger" -> swaggerUi()
            else -> jsonError(Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun status(): Response {
        val obj = JSONObject()
        val channelId = Playback.channelId()
        obj.put("playing", Playback.active())
        obj.put("channelId", channelId)
        obj.put("channelName", Playback.channelName())
        // What is on air on the channel that is playing, so a caller does not have to ask the guide
        // for it separately. Null while nothing plays or while the guide has nothing for that channel.
        obj.put("epg", programJson(if (channelId > 0L) app.repo.currentProgram(channelId) else null))
        return jsonOk(obj)
    }

    /**
     * The programme that is on air right now. A channel the guide says nothing about carries a null
     * epg rather than no epg at all, so the shape of the answer does not depend on the channel.
     */
    private fun programJson(program: Program?): Any =
        if (program == null) JSONObject.NULL else JSONObject()
            .put("title", program.title)
            .put("subtitle", program.subtitle)
            .put("start", Fmt.isoTime(program.start))
            .put("stop", Fmt.isoTime(program.stop))

    private fun channels(): Response {
        val arr = JSONArray()
        val playlistIds = app.repo.playlists().map { it.id }
        val channels = app.repo.channels(playlistIds, "", ChannelFilter.ALL, "order")
        // One query covers every channel. Asking the guide per channel would mean hundreds of them
        // for this list, which is slow enough to look like a hung request.
        val programs = app.repo.programsMapFor(channels.map { it.id }, System.currentTimeMillis())
        channels.forEach { ch ->
            val o = JSONObject()
            o.put("id", ch.id)
            o.put("name", ch.name)
            o.put("group", ch.groupTitle)
            o.put("epg", programJson(programs[ch.id]))
            arr.put(o)
        }
        return jsonOk(JSONObject().put("channels", arr))
    }

    private fun epg(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        app.repo.programsFor(id, now, now + 24 * 60 * 60 * 1000L).forEach { p ->
            val o = JSONObject()
            o.put("title", p.title)
            o.put("start", Fmt.isoTime(p.start))
            o.put("stop", Fmt.isoTime(p.stop))
            arr.put(o)
        }
        return jsonOk(JSONObject().put("epg", arr))
    }

    private fun history(): Response {
        val arr = JSONArray()
        app.repo.history().forEach { h ->
            val o = JSONObject()
            o.put("channelId", h.channelId)
            o.put("watchedAt", Fmt.isoTime(h.watchedAt))
            arr.put(o)
        }
        return jsonOk(JSONObject().put("history", arr))
    }

    /**
     * What is set up: every playlist with its address, how many channels it brought and when it was
     * last read. The password of an xtream or stalker entry is left out, so the answer is safe to
     * log or to show in a panel without handing the account over with it.
     */
    private fun playlistsList(): Response {
        val playlists = app.repo.playlists()
        // One query for the channels of all of them, counted per playlist in memory, rather than a
        // count query per playlist.
        val counts = app.repo.channels(playlists.map { it.id }, "", ChannelFilter.ALL, "order")
            .groupingBy { it.playlistId }.eachCount()
        val arr = JSONArray()
        playlists.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("type", p.type.name.lowercase())
            o.put("url", p.url)
            o.put("epgUrl", p.epgUrl)
            o.put("login", p.login)
            o.put("enabled", p.enabled)
            o.put("hidden", p.hidden)
            o.put("channels", counts[p.id] ?: 0)
            // A playlist that has never been read has no time to report, so it answers null rather
            // than a date in 1970.
            o.put("lastUpdate", if (p.lastUpdate > 0L) Fmt.isoTime(p.lastUpdate) else JSONObject.NULL)
            arr.put(o)
        }
        return jsonOk(JSONObject().put("playlists", arr))
    }

    /**
     * Adds a playlist and brings its channels in, the same way the wizard does. Reading the source
     * is network work and happens on the thread of this request; the epg afterwards is handed to the
     * update thread, so it does not hold the answer up.
     *
     * Unlike the wizard, a failure takes the half-written entry away again. A wizard leaves its
     * draft on screen to be corrected, while a caller tends to simply try again, and a leftover
     * playlist with no channels would then sit in the guide as an empty source.
     */
    private fun playlistsAdd(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()?.trim().orEmpty()
        if (url.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "missing url")
        val type = parsePlaylistType(session.parameters["type"]?.firstOrNull())
            .getOrElse { return jsonError(Response.Status.BAD_REQUEST, it.message ?: "unknown type") }
        val repo = app.repo
        // A type has its own set of values, so credentials of one type must not end up on another.
        val login = if (type == PlaylistType.XTREAM) session.parameters["login"]?.firstOrNull().orEmpty() else ""
        val password = if (type == PlaylistType.XTREAM) session.parameters["password"]?.firstOrNull().orEmpty() else ""
        val mac = if (type == PlaylistType.STALKER) session.parameters["mac"]?.firstOrNull().orEmpty() else ""
        val name = session.parameters["name"]?.firstOrNull()?.trim().orEmpty().ifEmpty { url }
        val id = repo.insertPlaylist(
            Playlist(
                name = name, type = type, url = url,
                login = login, password = password, mac = mac, lastUpdate = 0,
            )
        )
        try {
            val epg = session.parameters["epg"]?.firstOrNull()?.trim().orEmpty()
            if (epg.isNotEmpty() && repo.epgSources().none { it.playlistId == id }) {
                repo.insertEpgSource(EpgSource(name = name, url = epg, playlistId = id))
            }
            val channels = when (type) {
                PlaylistType.XTREAM -> XtreamApi(url, login, password).loadChannels()
                PlaylistType.STALKER -> StalkerApi(url, mac.ifBlank { null }).loadChannels()
                else -> M3uParser.parseUrl(url).channels
            }
            if (channels.isEmpty()) error("playlist is empty")
            repo.replaceChannels(id, channels)
            repo.setPlaylistLastUpdate(id, System.currentTimeMillis())
            // The epg is bound a few lines above, so this is the one moment where its address is
            // known and has never been downloaded. The update_change setting decides whether it
            // runs at all.
            app.sources.refreshEpgOnChange(id)
        } catch (e: Exception) {
            repo.deletePlaylist(id)
            return jsonError(Response.Status.BAD_REQUEST, e.message ?: "cannot read the playlist")
        }
        return jsonOk(JSONObject().put("ok", true).put("id", id).put("name", name))
    }

    /**
     * Takes a playlist away with everything that came from it: the channels, their programmes and
     * the epg source bound to it.
     */
    private fun playlistsRemove(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        if (app.repo.playlist(id) == null) return jsonError(Response.Status.NOT_FOUND, "playlist not found")
        app.repo.deletePlaylist(id)
        return jsonOk(JSONObject().put("ok", true))
    }

    /**
     * The type a caller asked for, by name or by the number the database stores. A local file is
     * not on offer: reading one would turn this endpoint into a way of having the app open a path
     * of the caller's choosing, and that stays what the wizard on the device is for. It is a known
     * type that is refused rather than a misspelt one, so it says so.
     */
    private fun parsePlaylistType(raw: String?): Result<PlaylistType> {
        val type = when (raw?.trim()?.lowercase().orEmpty()) {
            "", "m3u", "remote_m3u" -> PlaylistType.REMOTE_M3U
            "xtream", "xtream_codes" -> PlaylistType.XTREAM
            "stalker", "stalker_portal" -> PlaylistType.STALKER
            "file", "local" -> PlaylistType.FILE
            else -> raw?.trim()?.toIntOrNull()?.let { PlaylistType.from(it) }
        } ?: return Result.failure(IllegalArgumentException("unknown type"))
        if (type == PlaylistType.FILE) {
            return Result.failure(IllegalArgumentException("a local file cannot be added over the api"))
        }
        return Result.success(type)
    }

    private fun play(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing id")
        sendToMain("play", channelId = id)
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun shutdown(): Response {
        sendToMain("stop")
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun next(): Response {
        sendToMain("next")
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun prev(): Response {
        sendToMain("prev")
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun remote(session: IHTTPSession): Response {
        val key = session.parameters["key"]?.firstOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "missing key")
        sendToMain("remote", key = key)
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun sendToMain(action: String, channelId: Long = -1L, key: String = "") {
        // Commands that change what is on screen go to the player, which is singleTask: while it is
        // already up the command lands in its onNewIntent and the channel is switched in place.
        // Sending those to the guide instead meant clearing the player off the top of the stack, and
        // the player it destroyed released the shared engine afterwards, wiping the channel the
        // freshly started one had just put there. The player never opens without a channel to play.
        val toPlayer = action == "play" || action == "stop" || action == "next" || action == "prev" ||
            // A remote key belongs to the window the user is looking at, and the guide and the player
            // answer different keys. Sending it to the guide while the player was up built a second
            // guide on top of it, so the key landed on a window nobody was watching.
            (action == "remote" && app.focusedActivity is PlayerActivity)
        val intent = Intent(app, if (toPlayer) PlayerActivity::class.java else MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("web_api_action", action)
        if (channelId > 0) intent.putExtra("web_api_channel_id", channelId)
        if (key.isNotEmpty()) intent.putExtra("web_api_key", key)
        app.startActivity(intent)
    }

    private fun openApi(session: IHTTPSession): Response {
        val host = session.headers["host"] ?: "localhost:${app.prefs.webApiPort}"
        val serverUrl = "http://$host/api"
        val paths = JSONObject()

        val statusOp = JSONObject()
            .put("summary", "What is playing right now")
            .put("operationId", "getStatus")
            .put("tags", JSONArray().put("playback"))
        val statusSchema = JSONObject().put("\$ref", "#/components/schemas/Status")
        val statusExample = JSONObject()
            .put("playing", true)
            .put("channelId", 42)
            .put("channelName", "BBC One")
            .put("epg", JSONObject()
                .put("title", "The News")
                .put("subtitle", "Live")
                .put("start", "2026-10-02T11:00:00+03:00")
                .put("stop", "2026-10-02T12:00:00+03:00"))
        val statusContent = JSONObject().put("application/json", JSONObject()
            .put("schema", statusSchema)
            .put("example", statusExample))
        val statusResponse = JSONObject().put("200", JSONObject()
            .put("description", "Current playback status")
            .put("content", statusContent))
        statusOp.put("responses", statusResponse)
        paths.put("/status", JSONObject().put("get", statusOp))

        val channelsOp = JSONObject()
            .put("summary", "List all channels")
            .put("operationId", "getChannels")
            .put("tags", JSONArray().put("channels"))
        val channelsSchema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("channels", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("\$ref", "#/components/schemas/Channel"))))
        val channelsExample = JSONObject().put("channels", JSONArray().put(JSONObject()
            .put("id", 1).put("name", "BBC One").put("group", "Entertainment")
                .put("epg", JSONObject()
                    .put("title", "The News")
                    .put("subtitle", "Live")
                    .put("start", "2026-10-02T11:00:00+03:00")
                    .put("stop", "2026-10-02T12:00:00+03:00"))))
        val channelsContent = JSONObject().put("application/json", JSONObject()
            .put("schema", channelsSchema)
            .put("example", channelsExample))
        val channelsResponse = JSONObject().put("200", JSONObject()
            .put("description", "Array of channels")
            .put("content", channelsContent))
        channelsOp.put("responses", channelsResponse)
        paths.put("/channels", JSONObject().put("get", channelsOp))

        val epgOp = JSONObject()
            .put("summary", "Programme for a channel (next 24 hours)")
            .put("operationId", "getEpg")
            .put("tags", JSONArray().put("epg"))
        val epgParam = JSONObject()
            .put("name", "id")
            .put("in", "query")
            .put("required", true)
            .put("description", "Channel ID")
            .put("schema", JSONObject().put("type", "integer").put("format", "int64"))
        epgOp.put("parameters", JSONArray().put(epgParam))
        val epgSchema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("epg", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("\$ref", "#/components/schemas/Program"))))
        val epgExample = JSONObject().put("epg", JSONArray().put(JSONObject()
            .put("title", "News").put("start", "2023-09-01T12:00:00+03:00").put("stop", "2023-09-01T13:00:00+03:00")))
        val epgContent = JSONObject().put("application/json", JSONObject()
            .put("schema", epgSchema)
            .put("example", epgExample))
        val epgResponse = JSONObject().put("200", JSONObject()
            .put("description", "Array of programmes")
            .put("content", epgContent))
        epgOp.put("responses", epgResponse)
        paths.put("/epg", JSONObject().put("get", epgOp))

        val historyOp = JSONObject()
            .put("summary", "Recently watched channels")
            .put("operationId", "getHistory")
            .put("tags", JSONArray().put("history"))
        val historySchema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("history", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("\$ref", "#/components/schemas/HistoryEntry"))))
        val historyExample = JSONObject().put("history", JSONArray().put(JSONObject()
            .put("channelId", 42).put("watchedAt", "2023-09-01T12:00:00+03:00")))
        val historyContent = JSONObject().put("application/json", JSONObject()
            .put("schema", historySchema)
            .put("example", historyExample))
        val historyResponse = JSONObject().put("200", JSONObject()
            .put("description", "Array of history entries")
            .put("content", historyContent))
        historyOp.put("responses", historyResponse)
        paths.put("/history", JSONObject().put("get", historyOp))

        val playlistsListOp = JSONObject()
            .put("summary", "List all playlists")
            .put("operationId", "listPlaylists")
            .put("tags", JSONArray().put("playlists"))
        val playlistsListSchema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("playlists", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("\$ref", "#/components/schemas/Playlist"))))
        val playlistsListExample = JSONObject().put("playlists", JSONArray().put(JSONObject()
            .put("id", 1).put("name", "Home").put("type", "remote_m3u")
            .put("url", "http://tv.loc/m3u").put("epgUrl", "http://tv.loc/epg.xml")
            .put("login", "").put("enabled", true).put("hidden", false)
            .put("channels", 402).put("lastUpdate", "2026-10-02T09:15:00+03:00")))
        playlistsListOp.put("responses", JSONObject().put("200", JSONObject()
            .put("description", "Array of playlists")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", playlistsListSchema)
                .put("example", playlistsListExample)))))
        paths.put("/playlists/list", JSONObject().put("get", playlistsListOp))

        val playlistsAddOp = JSONObject()
            .put("summary", "Add a playlist and load its channels")
            .put("operationId", "addPlaylist")
            .put("tags", JSONArray().put("playlists"))
            .put("description", "Reads the source over the network, so the answer waits for it. Nothing is kept if the source turns out to be empty or unreachable, which makes a retry after a wrong address safe.")
        fun playlistParam(name: String, description: String, type: String, required: Boolean = false) =
            JSONObject()
                .put("name", name)
                .put("in", "query")
                .put("required", required)
                .put("description", description)
                .put("schema", JSONObject().put("type", type))
        val addParams = JSONArray()
            .put(playlistParam("url", "Address of the playlist, or the server for xtream and stalker", "string", true))
            .put(playlistParam("name", "Name shown in the guide, defaults to the url", "string"))
            .put(playlistParam("type", "Source type: m3u (default), xtream or stalker", "string"))
            .put(playlistParam("login", "xtream login", "string"))
            .put(playlistParam("password", "xtream password", "string"))
            .put(playlistParam("mac", "stalker mac address", "string"))
            .put(playlistParam("epg", "xmltv url to bind to the playlist", "string"))
        playlistsAddOp.put("parameters", addParams)
        playlistsAddOp.put("responses", JSONObject().put("200", JSONObject()
            .put("description", "Playlist added")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("ok", JSONObject().put("type", "boolean"))
                        .put("id", JSONObject().put("type", "integer").put("format", "int64"))
                        .put("name", JSONObject().put("type", "string"))))))))
        paths.put("/playlists/add", JSONObject().put("post", playlistsAddOp))

        val playlistsRemoveOp = JSONObject()
            .put("summary", "Delete a playlist with its channels and programmes")
            .put("operationId", "removePlaylist")
            .put("tags", JSONArray().put("playlists"))
        val removeParam = JSONObject()
            .put("name", "id")
            .put("in", "query")
            .put("required", true)
            .put("description", "Playlist ID")
            .put("schema", JSONObject().put("type", "integer").put("format", "int64"))
        playlistsRemoveOp.put("parameters", JSONArray().put(removeParam))
        val removeResponse = JSONObject().put("200", JSONObject()
            .put("description", "Playlist deleted")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", JSONObject().put("\$ref", "#/components/schemas/OkResponse")))))
        playlistsRemoveOp.put("responses", removeResponse)
        paths.put("/playlists/remove", JSONObject().put("post", playlistsRemoveOp))

        val playOp = JSONObject()
            .put("summary", "Play a channel")
            .put("operationId", "playChannel")
            .put("tags", JSONArray().put("playback"))
        val playParam = JSONObject()
            .put("name", "id")
            .put("in", "query")
            .put("required", true)
            .put("description", "Channel ID to play")
            .put("schema", JSONObject().put("type", "integer").put("format", "int64"))
        playOp.put("parameters", JSONArray().put(playParam))
        val playResponse = JSONObject().put("200", JSONObject()
            .put("description", "Playback started")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", JSONObject().put("\$ref", "#/components/schemas/OkResponse")))))
        playOp.put("responses", playResponse)
        paths.put("/play", JSONObject().put("post", playOp))

        val stopOp = JSONObject()
            .put("summary", "Stop playback")
            .put("operationId", "stopPlayback")
            .put("tags", JSONArray().put("playback"))
        val stopResponse = JSONObject().put("200", JSONObject()
            .put("description", "Playback stopped")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", JSONObject().put("\$ref", "#/components/schemas/OkResponse")))))
        stopOp.put("responses", stopResponse)
        paths.put("/stop", JSONObject().put("post", stopOp))

        val nextOp = JSONObject()
            .put("summary", "Switch to next channel")
            .put("operationId", "nextChannel")
            .put("tags", JSONArray().put("playback"))
        val nextResponse = JSONObject().put("200", JSONObject()
            .put("description", "Switched to next channel")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", JSONObject().put("\$ref", "#/components/schemas/OkResponse")))))
        nextOp.put("responses", nextResponse)
        paths.put("/next", JSONObject().put("post", nextOp))

        val prevOp = JSONObject()
            .put("summary", "Switch to previous channel")
            .put("operationId", "prevChannel")
            .put("tags", JSONArray().put("playback"))
        val prevResponse = JSONObject().put("200", JSONObject()
            .put("description", "Switched to previous channel")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", JSONObject().put("\$ref", "#/components/schemas/OkResponse")))))
        prevOp.put("responses", prevResponse)
        paths.put("/prev", JSONObject().put("post", prevOp))

        val remoteOp = JSONObject()
            .put("summary", "Send a remote control key")
            .put("operationId", "sendRemote")
            .put("tags", JSONArray().put("remote"))
        val remoteParam = JSONObject()
            .put("name", "key")
            .put("in", "query")
            .put("required", true)
            .put("description", "Key to send: up, down, left, right, ok, back")
            .put("schema", JSONObject().put("type", "string")
                .put("enum", JSONArray().put("up").put("down").put("left").put("right").put("ok").put("back")))
        remoteOp.put("parameters", JSONArray().put(remoteParam))
        val remoteResponse = JSONObject().put("200", JSONObject()
            .put("description", "Key sent")
            .put("content", JSONObject().put("application/json", JSONObject()
                .put("schema", JSONObject().put("\$ref", "#/components/schemas/OkResponse")))))
        remoteOp.put("responses", remoteResponse)
        paths.put("/remote", JSONObject().put("post", remoteOp))

        val schemas = JSONObject()
        // Every time the API hands out is ISO 8601 carrying the zone the player is on, so a client
        // never has to guess it and never has to agree on an epoch with the device.
        fun isoTime() = JSONObject()
            .put("type", "string")
            .put("format", "date-time")
            .put("example", "2026-10-02T11:00:00+03:00")
            .put("description", "ISO 8601 date and time with the UTC offset of the player")
        // A channel with nothing on air answers with a null epg. The reference is wrapped in an
        // allOf because a sibling of $ref is ignored in OpenAPI 3.0, and nullable has to sit next to it.
        fun epgRef() = JSONObject()
            .put("allOf", JSONArray().put(JSONObject().put("\$ref", "#/components/schemas/Program")))
            .put("nullable", true)
            .put("description", "The programme on air right now, or null when the guide has none")
        schemas.put("Status", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("playing", JSONObject().put("type", "boolean"))
                .put("channelId", JSONObject().put("type", "integer").put("format", "int64"))
                .put("channelName", JSONObject().put("type", "string"))
                .put("epg", epgRef())))
        schemas.put("Channel", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "integer").put("format", "int64"))
                .put("name", JSONObject().put("type", "string"))
                .put("group", JSONObject().put("type", "string"))
                .put("epg", epgRef())))
        schemas.put("Program", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("subtitle", JSONObject().put("type", "string"))
                .put("start", isoTime())
                .put("stop", isoTime())))
        schemas.put("HistoryEntry", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("channelId", JSONObject().put("type", "integer").put("format", "int64"))
                .put("watchedAt", isoTime())))
        // The password a playlist was added with is deliberately absent: this is what a caller gets
        // back from the list, and it has no reason to carry the account with it.
        schemas.put("Playlist", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "integer").put("format", "int64"))
                .put("name", JSONObject().put("type", "string"))
                .put("type", JSONObject().put("type", "string")
                    .put("enum", JSONArray().put("remote_m3u").put("xtream").put("stalker")))
                .put("url", JSONObject().put("type", "string"))
                .put("epgUrl", JSONObject().put("type", "string"))
                .put("login", JSONObject().put("type", "string"))
                .put("enabled", JSONObject().put("type", "boolean"))
                .put("hidden", JSONObject().put("type", "boolean"))
                .put("channels", JSONObject().put("type", "integer"))
                .put("lastUpdate", JSONObject().put("allOf", JSONArray().put(isoTime()))
                    .put("nullable", true)
                    .put("description", "When the channels were last read, or null if never"))))
        schemas.put("OkResponse", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("ok", JSONObject().put("type", "boolean"))))
        schemas.put("ErrorResponse", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("error", JSONObject().put("type", "string"))))

        val spec = JSONObject()
            .put("openapi", "3.0.3")
            .put("info", JSONObject()
                .put("title", "TiViBro Web API")
                .put("version", "1.0.0")
                .put("description", "Control the TiViBro player over the network. Every request requires the API key in the X-API-Key header or the apikey query parameter."))
            .put("servers", JSONArray().put(JSONObject().put("url", serverUrl)))
            .put("paths", paths)
            .put("components", JSONObject()
                .put("schemas", schemas)
                .put("securitySchemes", JSONObject()
                    .put("ApiKeyAuth", JSONObject()
                        .put("type", "apiKey")
                        .put("in", "header")
                        .put("name", "X-API-Key"))))
            .put("security", JSONArray().put(JSONObject().put("ApiKeyAuth", JSONArray())))
        return jsonOk(spec)
    }

    private fun swaggerUi(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <title>TiViBro Web API</title>
              <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
            </head>
            <body>
              <div id="swagger-ui"></div>
              <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
              <script>
                SwaggerUIBundle({ url: '/api/openapi.json', dom_id: '#swagger-ui' })
              </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun jsonOk(obj: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())

    private fun jsonError(status: Response.Status, msg: String): Response =
        newFixedLengthResponse(status, "application/json", JSONObject().put("error", msg).toString())

    companion object {
        /** The first non-loopback IPv4 address of the device, or an empty string. */
        fun localIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return ""
        }
    }
}