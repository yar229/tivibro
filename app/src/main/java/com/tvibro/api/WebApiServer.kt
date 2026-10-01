package com.tvibro.api

import android.content.Intent
import fi.iki.elonen.NanoHTTPD
import com.tvibro.TvBroApp
import com.tvibro.data.model.ChannelFilter
import com.tvibro.ui.main.MainActivity
import com.tvibro.ui.player.Playback
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
            "/api/play" -> play(session)
            "/api/stop" -> shutdown()
            "/api/next" -> next()
            "/api/prev" -> prev()
            "/api/remote" -> remote(session)
            "/api/openapi.json" -> openApi(session)
            "/api/docs" -> swaggerUi(session)
            "/swagger" -> swaggerUi(session)
            else -> jsonError(Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun status(): Response {
        val obj = JSONObject()
        obj.put("playing", Playback.active())
        obj.put("channelId", Playback.channelId())
        obj.put("channelName", Playback.channelName())
        obj.put("positionMs", Playback.positionMs())
        return jsonOk(obj)
    }

    private fun channels(): Response {
        val arr = JSONArray()
        val playlistIds = app.repo.playlists().map { it.id }
        app.repo
            .channels(playlistIds, "", ChannelFilter.ALL, "order")
            .forEach { ch ->
                val o = JSONObject()
                o.put("id", ch.id)
                o.put("name", ch.name)
                o.put("group", ch.groupTitle)
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
            o.put("start", p.start)
            o.put("stop", p.stop)
            arr.put(o)
        }
        return jsonOk(JSONObject().put("epg", arr))
    }

    private fun history(): Response {
        val arr = JSONArray()
        app.repo.history().forEach { h ->
            val o = JSONObject()
            o.put("channelId", h.channelId)
            o.put("watchedAt", h.watchedAt)
            arr.put(o)
        }
        return jsonOk(JSONObject().put("history", arr))
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
        val intent = Intent(app, MainActivity::class.java)
            // CLEAR_TOP is what makes the command arrive at all. With SINGLE_TOP alone the intent
            // only lands on the guide while the guide is the top window, so a command sent while the
            // full screen player is up built a second guide underneath it instead, and the handler
            // behind onNewIntent never ran. Clearing the top brings the one guide forward and hands it
            // the command.
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
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
            .put("positionMs", 125000)
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
            .put("id", 1).put("name", "BBC One").put("group", "Entertainment")))
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
            .put("title", "News").put("start", 1693500000000L).put("stop", 1693503600000L)))
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
            .put("channelId", 42).put("watchedAt", 1693500000000L)))
        val historyContent = JSONObject().put("application/json", JSONObject()
            .put("schema", historySchema)
            .put("example", historyExample))
        val historyResponse = JSONObject().put("200", JSONObject()
            .put("description", "Array of history entries")
            .put("content", historyContent))
        historyOp.put("responses", historyResponse)
        paths.put("/history", JSONObject().put("get", historyOp))

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
        schemas.put("Status", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("playing", JSONObject().put("type", "boolean"))
                .put("channelId", JSONObject().put("type", "integer").put("format", "int64"))
                .put("channelName", JSONObject().put("type", "string"))
                .put("positionMs", JSONObject().put("type", "integer").put("format", "int64"))))
        schemas.put("Channel", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "integer").put("format", "int64"))
                .put("name", JSONObject().put("type", "string"))
                .put("group", JSONObject().put("type", "string"))))
        schemas.put("Program", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("start", JSONObject().put("type", "integer").put("format", "int64"))
                .put("stop", JSONObject().put("type", "integer").put("format", "int64"))))
        schemas.put("HistoryEntry", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("channelId", JSONObject().put("type", "integer").put("format", "int64"))
                .put("watchedAt", JSONObject().put("type", "integer").put("format", "int64"))))
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

    private fun swaggerUi(session: IHTTPSession): Response {
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