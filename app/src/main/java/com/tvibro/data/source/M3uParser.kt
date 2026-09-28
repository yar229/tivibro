package com.tvibro.data.source

import com.tvibro.data.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLDecoder

object M3uParser {

    class Result(
        val channels: List<Channel>,
        val groups: List<String>,
    )

    fun parseUrl(url: String, userAgent: String? = null): Result =
        parse(Http.get(url, userAgent).body.inputStream(), url)

    fun parse(text: String, baseUrl: String? = null): Result = parse(text.byteInputStream(), baseUrl)

    fun parse(input: InputStream, baseUrl: String?): Result {
        val channels = ArrayList<Channel>()
        val groups = LinkedHashSet<String>()
        val now = System.currentTimeMillis()
        val prefixes = emptyList<String>()
        val suffixes = emptyList<String>()

        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var attrs: MutableMap<String, String>? = null
            var index = 0
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    attrs = null
                    continue
                }
                if (trimmed.startsWith("#EXTM3U")) continue
                if (trimmed.startsWith("#EXTINF")) {
                    val parsed = parseExtInf(trimmed)
                    attrs = parsed.first
                    continue
                }
                if (trimmed.startsWith("#EXTGRP")) {
                    val a = attrs ?: continue
                    val g = trimmed.substringAfter(':').trim()
                    if (g.isNotEmpty()) {
                        a["group-title"] = g
                        groups += g
                    }
                    continue
                }
                if (trimmed.startsWith("#EXT")) continue

                val a = attrs ?: continue
                attrs = null
                val streamUrl = absolute(trimmed, baseUrl) ?: continue
                val name = cleanName(
                    a["name"]?.takeIf { it.isNotBlank() }
                        ?: a["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: streamUrl.substringAfterLast('/'),
                    prefixes, suffixes
                )
                val number = a["tvg-num"]?.takeIf { it.isNotBlank() } ?: ""
                val group = a["group-title"]?.takeIf { it.isNotBlank() } ?: ""
                if (group.isNotEmpty()) groups += group
                val catchupSource = a["catchup-source"]?.takeIf { it.isNotBlank() }
                    ?: a["url-tvg"]?.takeIf { it.isNotBlank() }
                    ?: a["catchup"]?.takeIf { it.isNotBlank() }
                val catchupDays = a["catchup-days"]?.toIntOrNull()
                    ?: if (catchupSource != null) DEFAULT_CATCHUP_DAYS else 0
                val isVod = group.equals("VOD", true) ||
                    a["vod"]?.toIntOrNull() == 1 ||
                    name.contains("VOD", true) && a["tvg-id"].isNullOrBlank() && catchupSource == null
                channels += Channel(
                    streamId = a["tvg-id"]?.takeIf { it.isNotBlank() } ?: streamUrl,
                    name = name,
                    number = number,
                    logoUrl = absolute(a["tvg-logo"], baseUrl) ?: "",
                    groupTitle = group,
                    tvgId = a["tvg-id"]?.trim() ?: "",
                    tvgName = a["tvg-name"]?.trim() ?: "",
                    url = streamUrl,
                    catchupSource = catchupSource?.let { catchupTemplate(it) } ?: "",
                    catchupDays = catchupDays,
                    catchupType = a["catchup-type"]?.trim() ?: "",
                    isVod = isVod,
                    vodCategory = a["category"]?.trim() ?: group,
                    vodYear = a["year"]?.trim() ?: "",
                    vodRating = a["rating"]?.trim() ?: "",
                    seriesName = a["series-name"]?.trim() ?: "",
                    season = a["season"]?.trim() ?: "",
                    episode = a["episode"]?.trim() ?: "",
                    containerExtension = extensionOf(streamUrl),
                    orderIndex = index++,
                    dateAdded = now,
                    lastModified = now,
                    isSeries = a["series-name"]?.isNotBlank() == true,
                )
            }
        }
        return Result(channels, groups.toList())
    }

    fun parseLine(line: String, baseUrl: String? = null): Channel? {
        val p = parseLineRaw(line, baseUrl) ?: return null
        return Channel(
            streamId = p.streamId,
            name = p.name,
            number = p.number,
            logoUrl = p.logoUrl,
            groupTitle = p.groupTitle,
            tvgId = p.tvgId,
            tvgName = p.tvgName,
            url = p.url,
            catchupSource = p.catchupSource,
            catchupDays = p.catchupDays,
            catchupType = p.catchupType,
            isVod = p.isVod,
            vodCategory = p.vodCategory,
            vodYear = p.vodYear,
            vodRating = p.vodRating,
            seriesName = p.seriesName,
            season = p.season,
            episode = p.episode,
            containerExtension = p.containerExtension,
            orderIndex = 0,
            dateAdded = System.currentTimeMillis(),
            lastModified = System.currentTimeMillis(),
            isSeries = p.isSeries,
        )
    }

    fun parseLineRaw(line: String, baseUrl: String? = null): Channel? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("#EXTINF")) return null
        val (attrs, displayName) = parseExtInf(trimmed)
        val url = displayName.takeIf { it.contains("://") }?.let { absolute(it, baseUrl) } ?: return null
        val name = attrs["name"]?.takeIf { it.isNotBlank() }
            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/')
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: ""
        val catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() }?.let { catchupTemplate(it) } ?: ""
        val now = System.currentTimeMillis()
        return Channel(
            streamId = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: url,
            name = name,
            number = attrs["tvg-num"]?.takeIf { it.isNotBlank() } ?: "",
            logoUrl = absolute(attrs["tvg-logo"], baseUrl) ?: "",
            groupTitle = group,
            tvgId = attrs["tvg-id"]?.trim() ?: "",
            tvgName = attrs["tvg-name"]?.trim() ?: "",
            url = url,
            catchupSource = catchupSource,
            catchupDays = attrs["catchup-days"]?.toIntOrNull() ?: if (catchupSource.isNotEmpty()) DEFAULT_CATCHUP_DAYS else 0,
            catchupType = attrs["catchup-type"]?.trim() ?: "",
            isVod = group.equals("VOD", true),
            vodCategory = attrs["category"]?.trim() ?: group,
            vodYear = attrs["year"]?.trim() ?: "",
            vodRating = attrs["rating"]?.trim() ?: "",
            seriesName = attrs["series-name"]?.trim() ?: "",
            season = attrs["season"]?.trim() ?: "",
            episode = attrs["episode"]?.trim() ?: "",
            containerExtension = extensionOf(url),
            orderIndex = 0,
            dateAdded = now,
            lastModified = now,
            isSeries = attrs["series-name"]?.isNotBlank() == true,
        )
    }

    /** @return attributes and the name part (which may be a URL for single-line entries). */
    private fun parseExtInf(line: String): Pair<MutableMap<String, String>, String> {
        val attrs = LinkedHashMap<String, String>()
        val body = line.removePrefix("#EXTINF:")
        // the attribute block ends at the first comma that is not inside quotes,
        // so "-1,Name" and "-1 tvg-id=\"x\",Name" are both handled
        val split = findUnquotedComma(body)
        val rawAttrPart = if (split >= 0) body.substring(0, split) else body
        val namePart = if (split >= 0) body.substring(split + 1).trim() else ""
        // the first token of the attribute part is the duration, e.g. "#EXTINF:-1 tvg-id=..."
        val attrPart = rawAttrPart.trim().let {
            val firstTokenEnd = it.indexOfFirst { ch -> ch == ' ' || ch == '\t' }
            if (firstTokenEnd < 0) "" else it.substring(firstTokenEnd).trimStart()
        }

        var i = 0
        while (i < attrPart.length) {
            val eq = attrPart.indexOf('=', i)
            if (eq < 0) break
            val key = attrPart.substring(i, eq).trim().removePrefix(":").trim()
            if (key.isEmpty() || !key.first().isLetter()) {
                i = eq + 1
                continue
            }
            val value: String
            if (eq + 1 < attrPart.length && attrPart[eq + 1] == '"') {
                val close = attrPart.indexOf('"', eq + 2)
                if (close < 0) {
                    value = attrPart.substring(eq + 2).trim()
                    i = attrPart.length
                } else {
                    value = attrPart.substring(eq + 2, close)
                    i = close + 1
                    while (i < attrPart.length && attrPart[i] == ',') i++
                }
            } else {
                val comma = attrPart.indexOf(',', eq + 1)
                if (comma < 0) {
                    value = attrPart.substring(eq + 1).trim()
                    i = attrPart.length
                } else {
                    value = attrPart.substring(eq + 1, comma).trim()
                    i = comma + 1
                }
            }
            attrs[key.lowercase()] = value
        }
        // the display name (text after the last comma) is exposed as the "name" attribute
        if (namePart.isNotBlank()) attrs["name"] = namePart
        return attrs to namePart
    }

    private fun findUnquotedComma(text: String): Int {
        var inQuote = false
        for (i in text.indices) {
            when (text[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return i
            }
        }
        return -1
    }

    fun cleanName(name: String, prefixes: List<String>, suffixes: List<String>): String {
        var out = name.trim()
        if (prefixes.isNotEmpty()) {
            for (p in prefixes) {
                if (p.isNotEmpty() && out.startsWith(p, ignoreCase = true)) {
                    out = out.substring(p.length).trimStart(' ', '-', '.', '|', ':')
                }
            }
        }
        if (suffixes.isNotEmpty()) {
            for (s in suffixes) {
                if (s.isNotEmpty() && out.endsWith(s, ignoreCase = true)) {
                    out = out.substring(0, out.length - s.length).trimEnd(' ', '-', '.', '|', ':')
                }
            }
        }
        return out
    }

    fun catchupTemplate(source: String): String = source
        .replace("{start}", CATCHUP_START)
        .replace("{utc}", CATCHUP_UTC)
        .replace("{duration}", CATCHUP_DURATION)
        .replace("{end}", CATCHUP_END)
        .replace("{timestamp}", CATCHUP_START)
        .replace("{start_utc}", CATCHUP_START_UTC)
        .replace("{end_utc}", CATCHUP_END_UTC)

    fun absolute(url: String?, baseUrl: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.trim()
        if (u.contains("://")) return u
        if (u.startsWith("data:") || u.startsWith("file:") || u.startsWith("content:")) return u
        if (u.startsWith("/")) return baseUrl?.let { baseRoot(it) + u } ?: u
        if (baseUrl == null) return u
        val path = baseUrl.substringBeforeLast('/', baseUrl)
        return "$path/$u"
    }

    private fun baseRoot(url: String): String {
        val idx = url.indexOf("://")
        if (idx < 0) return url
        val afterScheme = url.substring(idx + 3)
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) url else url.substring(0, idx + 3 + slash)
    }

    fun extensionOf(url: String): String {
        val clean = url.substringBefore('|').substringBefore('?')
        val last = clean.substringAfterLast('/')
        if (!last.contains('.')) return ""
        return last.substringAfterLast('.').takeIf { it.length <= 5 } ?: ""
    }

    fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }

    const val DEFAULT_CATCHUP_DAYS = 7
    private const val CATCHUP_START = "\$start"
    private const val CATCHUP_END = "\$end"
    private const val CATCHUP_UTC = "\$utc"
    private const val CATCHUP_DURATION = "\$duration"
    private const val CATCHUP_START_UTC = "\$start_utc"
    private const val CATCHUP_END_UTC = "\$end_utc"
}
