package com.tvibro.data.source

import com.tvibro.data.model.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class XtreamApi(
    private val server: String,
    private val login: String,
    private val password: String,
    private val userAgent: String? = null,
) {

    private val base: String by lazy {
        val s = server.trim().trimEnd('/')
        if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
    }

    val playerApi: String get() = "$base/player_api.php"

    private val cache = ConcurrentHashMap<String, Any>()

    fun clearCache() = cache.clear()

    private inline fun <reified T> cached(key: String, loader: () -> T): T {
        val hit = cache[key]
        if (hit != null) return hit as T
        val value = loader()
        cache[key] = value as Any
        return value
    }

    fun authenticate(): JSONObject {
        val json = getObject("$playerApi?username=$login&password=$password")
        if (!json.optBoolean("user_info_authentication", true) && json.optInt("auth") == 0) {
            throw IllegalStateException(json.optString("user_info") .ifEmpty { "Authentication failed" })
        }
        return json
    }

    fun userInfo(): JSONObject = authenticate().optJSONObject("user_info") ?: JSONObject()

    fun serverInfo(): JSONObject = authenticate().optJSONObject("server_info") ?: JSONObject()

    fun accountInfo(): JSONObject = authenticate().optJSONObject("account_info") ?: JSONObject()

    fun profileName(): String = userInfo().optString("username", login)

    fun expDate(): Long = userInfo().optLong("exp_date", 0L) * 1000

    fun maxConnections(): Int = userInfo().optString("max_connections", "1").toIntOrNull() ?: 1

    fun activeSessions(): Int = userInfo().optInt("active_cons", 0)

    fun channels(): List<Channel> = cached("channels") { loadChannels() }

    /** Live channels, series and VOD in one request batch, bypassing the cache. */
    fun loadChannels(): List<Channel> {
        val all = ArrayList<Channel>()
        all += liveStreams()
        all += series()
        all += vods()
        return all
    }

    fun categories(categoryId: String = "*"): List<Pair<String, String>> =
        cached("cats:$categoryId") {
            val arr = getArray("$playerApi?username=$login&password=$password&action=get_live_categories" +
                if (categoryId != "*") "&category_id=$categoryId" else "")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(o.optString("category_id") to o.optString("category_name"))
                }
            }
        }

    fun liveStreams(categoryId: String? = null): List<Channel> = cached("live:$categoryId") {
        val url = "$playerApi?username=$login&password=$password&action=get_live_streams" +
            (categoryId?.let { "&category_id=$it" } ?: "")
        parseStreams(getArray(url), vod = false)
    }

    fun series(categoryId: String? = null): List<Channel> = cached("series:$categoryId") {
        val url = "$playerApi?username=$login&password=$password&action=get_series" +
            (categoryId?.let { "&category_id=$it" } ?: "")
        parseSeries(getArray(url))
    }

    fun vods(categoryId: String? = null): List<Channel> = cached("vod:$categoryId") {
        val url = "$playerApi?username=$login&password=$password&action=get_vod_streams" +
            (categoryId?.let { "&category_id=$it" } ?: "")
        parseStreams(getArray(url), vod = true)
    }

    fun seriesInfo(seriesId: String): SeriesInfo {
        val url = "$playerApi?username=$login&password=$password&action=get_series_info&series_id=$seriesId"
        val obj = getObject(url)
        val episodes = ArrayList<Episode>()
        obj.optJSONArray("episodes")?.let { eps ->
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                episodes += Episode(
                    id = e.optString("id"),
                    season = e.optInt("season", 0),
                    episode = e.optInt("episode", 0),
                    title = e.optString("title"),
                    plot = e.optString("plot"),
                    streamIcon = e.optString("stream_icon"),
                    rating = e.optDouble("rating", 0.0),
                    duration = e.optString("duration"),
                    containerExtension = e.optString("container_extension"),
                    directSource = absoluteStream(e.optString("direct_source")),
                    added = e.optLong("added", 0L) * 1000,
                )
            }
        }
        val info = obj.optJSONObject("info") ?: JSONObject()
        return SeriesInfo(
            name = info.optString("name"),
            plot = info.optString("plot"),
            cover = info.optString("cover"),
            cast = info.optString("cast"),
            director = info.optString("director"),
            genre = info.optString("genre"),
            releaseDate = info.optString("releaseDate"),
            rating = info.optString("rating"),
            episodes = episodes,
        )
    }

    fun vodInfo(streamId: String): VodInfo {
        val url = "$playerApi?username=$login&password=$password&action=get_vod_info&vod_id=$streamId"
        val obj = getObject(url)
        val info = obj.optJSONObject("info") ?: JSONObject()
        return VodInfo(
            name = info.optString("name"),
            plot = info.optString("plot"),
            cover = info.optString("cover"),
            cast = info.optString("cast"),
            director = info.optString("director"),
            genre = info.optString("genre"),
            releaseDate = info.optString("releaseDate"),
            rating = info.optString("rating"),
            video = obj.optJSONObject("video")?.optString("stream_id").orEmpty(),
        )
    }

    fun streamUrl(media: Channel): String = when {
        media.isVod -> "$playerApi?username=$login&password=$password&action=get_vod_url&vod_id=${media.streamId}&container=${media.containerExtension.ifEmpty { "ts" }}"
        else -> "$base/live/${login}/${password}/${media.streamId}.${media.containerExtension.ifEmpty { "ts" }}"
    }

    fun absoluteStream(path: String): String = when {
        path.isEmpty() -> ""
        path.contains("://") -> path
        else -> "$base/$path"
    }

    // ------------------------------------------------------------- internals

    private fun parseStreams(arr: JSONArray, vod: Boolean): List<Channel> {
        val out = ArrayList<Channel>(arr.length())
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id", o.optString("series_id", ""))
            if (id.isEmpty()) continue
            val name = o.optString("name")
            val isSeries = o.has("series_id") && !vod
            out += Channel(
                streamId = id,
                name = name,
                number = o.optString("num").takeIf { it.isNotEmpty() } ?: "",
                logoUrl = o.optString("stream_icon"),
                groupTitle = o.optString("category_name"),
                tvgId = o.optString("tvg_id", id),
                tvgName = name,
                url = "",
                catchupSource = o.optString("catchup_source"),
                catchupDays = o.optInt("catchup_days", if (o.optString("catchup_source").isEmpty()) 0 else 7),
                catchupType = o.optString("catchup_type"),
                isVod = vod || isSeries,
                vodCategory = o.optString("category_name"),
                vodYear = o.optString("year", o.optString("releaseDate", "")).take(4),
                vodRating = o.optString("rating").ifEmpty { o.optDouble("rating", 0.0).toString() },
                seriesName = if (isSeries) name else "",
                containerExtension = o.optString("container_extension"),
                orderIndex = out.size,
                dateAdded = now,
                lastModified = now,
                isSeries = isSeries,
            )
        }
        return out
    }

    private fun parseSeries(arr: JSONArray): List<Channel> {
        val out = ArrayList<Channel>(arr.length())
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("series_id")
            if (id.isEmpty()) continue
            val name = o.optString("title")
            out += Channel(
                streamId = id,
                name = name,
                logoUrl = o.optString("cover"),
                groupTitle = o.optString("category"),
                tvgId = id,
                tvgName = name,
                url = "",
                isVod = true,
                vodCategory = o.optString("category"),
                vodYear = o.optString("releaseDate", "").take(4),
                vodRating = o.optString("rating").ifEmpty { o.optDouble("rating", 0.0).toString() },
                seriesName = name,
                orderIndex = out.size,
                dateAdded = now,
                lastModified = now,
                isSeries = true,
            )
        }
        return out
    }

    private fun getObject(url: String): JSONObject {
        val res = Http.get(url, userAgent)
        if (!res.ok) throw IllegalStateException("HTTP ${res.code}")
        val text = res.text.trim()
        if (text.isEmpty()) throw IllegalStateException("Empty response")
        return JSONObject(text)
    }

    private fun getArray(url: String): JSONArray {
        val res = Http.get(url, userAgent)
        if (!res.ok) throw IllegalStateException("HTTP ${res.code}")
        val text = res.text.trim()
        if (text.isEmpty()) return JSONArray()
        return if (text.startsWith("[")) JSONArray(text) else JSONArray(JSONObject(text).optJSONArray("data") ?: JSONArray())
    }

    // ------------------------------------------------------------------ types

    data class SeriesInfo(
        val name: String,
        val plot: String,
        val cover: String,
        val cast: String,
        val director: String,
        val genre: String,
        val releaseDate: String,
        val rating: String,
        val episodes: List<Episode>,
    )

    data class Episode(
        val id: String,
        val season: Int,
        val episode: Int,
        val title: String,
        val plot: String,
        val streamIcon: String,
        val rating: Double,
        val duration: String,
        val containerExtension: String,
        val directSource: String,
        val added: Long,
    )

    data class VodInfo(
        val name: String,
        val plot: String,
        val cover: String,
        val cast: String,
        val director: String,
        val genre: String,
        val releaseDate: String,
        val rating: String,
        val video: String,
    )
}
