package com.tvibro.data.source

import com.tvibro.data.model.Program
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class XmltvParser(
    private val storeDescriptions: Boolean = true,
    /**
     * Keeps only the programmes of the channels the caller knows, matched on the id attribute the
     * way the channels are looked up later. A country wide EPG holds millions of entries and
     * building the ones nobody asked for is what runs a tablet out of memory.
     */
    private val acceptChannel: ((String) -> Boolean)? = null,
    /**
     * Hands every accepted programme over as it comes out of the document. A caller that wants to
     * follow the progress of a huge archive cannot wait for the parse to finish first, and passing
     * the callback turns the collected list off.
     */
    private val onProgramme: ((Program) -> Unit)? = null,
) {

    class Result(
        val programmes: List<Program>,
        val channelNames: Map<String, String>,
    )

    fun parse(input: InputStream, offsetMinutes: Int = 0): Result {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null)

        val offset = offsetMinutes * 60_000L
        val programmes = ArrayList<Program>(2048)
        val channelNames = HashMap<String, String>()

        var current: Program? = null
        var titles = HashMap<String, String>()
        var subTitles = HashMap<String, String>()
        var descs = HashMap<String, String>()
        var category = ""
        var icon = ""

        var inChannel = false
        var channelId = ""
        var channelNamesMap = HashMap<String, String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id").orEmpty()
                        channelNamesMap = HashMap()
                        inChannel = true
                    }

                    "display-name" -> if (inChannel) {
                        val lang = parser.getAttributeValue(null, "lang").orEmpty()
                        val text = parser.nextText()
                        if (text.isNotBlank()) channelNamesMap[lang] = text
                        event = parser.eventType
                        continue
                    }

                    "programme" -> {
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        val ch = parser.getAttributeValue(null, "channel").orEmpty()
                        val accept = acceptChannel
                        val wanted = accept == null ||
                            accept(ch.trim().lowercase(Locale.US))
                        current = if (!wanted || ch.isEmpty() || start <= 0L || stop <= start) {
                            null
                        } else {
                            Program(tvgId = ch, start = start + offset, stop = stop + offset)
                        }
                        titles = HashMap()
                        subTitles = HashMap()
                        descs = HashMap()
                        category = ""
                        icon = ""
                    }

                    "title" -> if (current != null) {
                        titles = readText(parser, titles)
                        event = parser.eventType
                        continue
                    }

                    "sub-title" -> if (current != null) {
                        subTitles = readText(parser, subTitles)
                        event = parser.eventType
                        continue
                    }

                    "desc" -> if (current != null && storeDescriptions) {
                        descs = readText(parser, descs)
                        event = parser.eventType
                        continue
                    }

                    "category" -> if (current != null) {
                        if (category.isEmpty()) category = parser.nextText()
                        else parser.nextText()
                        event = parser.eventType
                        continue
                    }

                    "icon" -> if (current != null && icon.isEmpty()) {
                        icon = parser.getAttributeValue(null, "src").orEmpty()
                    }

                    "credits" -> if (current != null) {
                        event = skipTag(parser)
                        continue
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> if (inChannel) {
                        val name = pick(channelNamesMap)
                        if (channelId.isNotEmpty() && name.isNotEmpty()) channelNames[channelId] = name
                        inChannel = false
                    }

                    "programme" -> {
                        val p = current
                        if (p != null) {
                            p.title = pick(titles)
                            p.subtitle = pick(subTitles)
                            if (storeDescriptions) p.description = pick(descs)
                            p.category = category
                            p.icon = icon
                            if (p.title.isNotEmpty()) {
                                val accept = onProgramme
                                if (accept == null) programmes += p else accept(p)
                            }
                        }
                        current = null
                    }
                }
            }
            event = parser.next()
        }

        return Result(programmes, channelNames)
    }

    private fun readText(parser: XmlPullParser, into: HashMap<String, String>): HashMap<String, String> {
        val lang = parser.getAttributeValue(null, "lang").orEmpty()
        val text = parser.nextText()
        if (text.isNotBlank()) into[lang] = text
        return into
    }

    private fun pick(map: Map<String, String>): String {
        if (map.isEmpty()) return ""
        return map["en"] ?: map["ru"] ?: map.values.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun skipTag(parser: XmlPullParser): Int {
        var depth = 1
        var e = parser.next()
        while (depth > 0) {
            if (e == XmlPullParser.END_DOCUMENT) return e
            if (e == XmlPullParser.START_TAG) depth++
            if (e == XmlPullParser.END_TAG) depth--
            e = parser.next()
        }
        return e
    }

    companion object {
        private val formats = arrayOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm Z",
            "yyyyMMddHHmm",
        )

        fun parseTime(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            val trimmed = value.trim()
            for (f in formats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.US)
                    if (f.endsWith("Z")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val t = sdf.parse(trimmed)?.time ?: continue
                    if (t > 0) return t
                } catch (e: Exception) {
                    // try next pattern
                }
            }
            return 0L
        }

        fun formatTime(time: Long): String {
            val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date(time))
        }
    }
}
