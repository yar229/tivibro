package com.tvibro.data.source

import android.util.Base64
import com.tvibro.data.model.Channel
import com.tvibro.data.model.Playlist
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal Stalker / MAG middleware client: handshake, token auth, channel list and
 * temporary stream links.
 */
class StalkerApi(private val portalUrl: String, macAddress: String? = null) {

    private val base = portalUrl.trim().trimEnd('/')
    private val tokens = ConcurrentHashMap<String, String>()
    private var sessionCounter = 0

    private val profile = "hd"
    private val serial: String = normalizeMac(macAddress) ?: (100 + (System.currentTimeMillis() % 899)).toString()
    private val deviceId = "TiviBro-$serial"

    private fun normalizeMac(value: String?): String? {
        val raw = value?.trim()?.replace(Regex("[^0-9a-fA-F]"), "") ?: return null
        if (raw.length != 12) return null
        return raw.uppercase().chunked(2).joinToString(":")
    }

    private fun authToken(): String? = tokens["token"]

    fun login(): Boolean {
        val res = post("/mag/connect", JSONObject(), needsAuth = false)
        if (!res.optBoolean("connected", false)) {
            val js = res.optJSONObject("js") ?: return false
            val token = js.optString("token")
            if (token.isEmpty()) return false
            tokens["token"] = token
        }
        val token = authToken() ?: return false
        val body = JSONObject().apply {
            put("login", "common")
            put("password", Base64.encodeToString("".toByteArray(), Base64.NO_WRAP).ifEmpty { "" })
            put("type", "hd_client")
            put("token", token)
            put("serial", serial.toString())
            put("device_id", deviceId)
            put("device_id2", "TiviBro")
        }
        val profileRes = post("/mag/load_profile", body, needsAuth = false)
        return profileRes.optBoolean("connected", false) || authToken() != null
    }

    fun logout() {
        try {
            post("/mag/disconnect", JSONObject().apply { put("token", authToken() ?: "") })
        } catch (e: Exception) {
            // ignore
        }
        tokens.clear()
    }

    /** Logs in (if needed) and returns every channel of every category. */
    fun loadChannels(): List<Channel> {
        if (!login()) return emptyList()
        return try {
            val out = ArrayList<Channel>()
            for ((id, _) in categories()) {
                out += channels(id)
            }
            if (out.isEmpty()) out += channels(null)
            out.forEachIndexed { index, ch -> ch.orderIndex = index }
            out
        } finally {
            logout()
        }
    }

    fun categories(): List<Pair<String, String>> {        val res = post("/mag/tv/category", JSONObject())
        val arr = res.optJSONArray("js") ?: JSONArray()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            val name = o.optString("title")
            if (id.isNotEmpty() && name.isNotEmpty()) out += id to name
        }
        return out
    }

    fun channels(categoryId: String? = null, page: Int = 1): List<Channel> {
        val body = JSONObject().apply {
            put("token", authToken() ?: "")
            put("page", page)
            put("sortby", 1)
            if (!categoryId.isNullOrEmpty()) {
                put("category", categoryId)
                put("output", "json")
            }
        }
        val res = post("/mag/tv/get_all_channels", body)
        val arr = res.optJSONArray("js") ?: JSONArray()
        val out = ArrayList<Channel>(arr.length())
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out += Channel(
                streamId = id,
                name = o.optString("name", id),
                number = o.optString("number"),
                logoUrl = tvLogoUrl(id),
                groupTitle = o.optString("category_name"),
                tvgId = id,
                tvgName = o.optString("name", id),
                url = "",
                orderIndex = out.size,
                dateAdded = now,
                lastModified = now,
            )
        }
        return out
    }

    fun createLink(channel: Channel): String {
        val token = createToken("ffmpeg", channel)
        val body = JSONObject().apply {
            put("token", authToken() ?: "")
            put("ffmpeg", channel.streamId)
            put("force_ch_link", token)
        }
        val res = post("/mag/tv/stream_url", body)
        val url = res.optString("js")
        if (url.isNotEmpty()) return url
        return "http://localhost/ts/${channel.streamId}.ts"
    }

    fun createToken(cmd: String, channel: Channel): String {
        val marker = (sessionCounter++).toString()
        val info = Base64.encodeToString(
            "{\"mac\":\"$marker\",\"id\":${serial},\"sn\":\"$deviceId\",\"device\":\"TiviBro\",\"type\":\"$cmd\"}"
                .toByteArray(), Base64.NO_WRAP
        )
        val body = JSONObject().apply {
            put("type", "stb")
            put("token", authToken() ?: "")
            put("cmd", "create_link")
            put("mac", serial.toString())
            put("id", serial)
            put("sn", deviceId)
            put("video_out", "hdmi")
            put("device", "TiviBro")
            put("format", "ts")
            put("info", info)
            put("apiv", 2)
            put("action", "")
        }
        val res = post("/mag/portal", body)
        return res.optString("js", marker)
    }

    fun events(onLine: (Boolean) -> Unit = {}, offLine: () -> Unit = {}) {
        try {
            val body = JSONObject().apply {
                put("token", authToken() ?: "")
                put("action", "events")
                put("type", "account")
                put("event", "keep_alive")
                put("mac", serial.toString())
                put("id", serial)
                put("sn", deviceId)
            }
            val res = post("/mag/portal", body)
            if (res.optBoolean("connected", false)) onLine(true) else offLine()
        } catch (e: Exception) {
            offLine()
        }
    }

    private fun tvLogoUrl(id: String) = "$base/mag/tv/load_logo?id=$id"

    private fun post(path: String, body: JSONObject, needsAuth: Boolean = true): JSONObject {
        val headers = HashMap<String, String>()
        if (needsAuth) authToken()?.let { headers["Authorization"] = "Bearer $it" }
        headers["User-Agent"] = "Mozilla/5.0 (SMART-TV; Linux; TiviBro) AppleWebKit/537.36"
        headers["Cookie"] = "mac=$serial; stb_lang=en; timezone=Europe/London"
        val res = Http.post("$base/portal.php?type=$path", body.toString().toByteArray(), "application/json", null, headers)
        if (!res.ok) error("HTTP ${res.code}")
        val text = res.text.trim()
        if (text.isEmpty()) return JSONObject()
        return JSONObject(text)
    }

    companion object {
        fun createLink(playlist: Playlist, channel: Channel): String {
            val api = StalkerApi(playlist.url, playlist.mac.ifBlank { null })
            return runCatching { api.createLink(channel) }.getOrElse { "" }
        }

        fun loadChannels(playlist: Playlist): List<Channel> {
            val api = StalkerApi(playlist.url, playlist.mac.ifBlank { null })
            if (!api.login()) return emptyList()
            return try {
                api.loadChannels()
            } finally {
                api.logout()
            }
        }
    }
}
