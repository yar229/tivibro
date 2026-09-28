package com.tvibro.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses extinf attributes and stream url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news24" tvg-name="News 24" tvg-logo="http://cdn/logo.png" group-title="News" tvg-num="12",News Twenty Four
            http://host/stream/news24.ts
        """.trimIndent()

        val res = M3uParser.parse(playlist, "http://host/playlist.m3u")

        assertEquals(1, res.channels.size)
        val ch = res.channels.first()
        assertEquals("News Twenty Four", ch.name)
        assertEquals("news24", ch.streamId)
        assertEquals("news24", ch.tvgId)
        assertEquals("News 24", ch.tvgName)
        assertEquals("News", ch.groupTitle)
        assertEquals("12", ch.number)
        assertEquals("http://cdn/logo.png", ch.logoUrl)
        assertEquals("http://host/stream/news24.ts", ch.url)
        assertEquals("ts", ch.containerExtension)
        assertEquals(listOf("News"), res.groups)
    }

    @Test
    fun `resolves relative stream urls against the playlist url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            streams/a.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, "http://host/live/playlist.m3u").channels.single()
        assertEquals("http://host/live/streams/a.ts", ch.url)
    }

    @Test
    fun `resolves root relative urls to the host root`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            /top/a.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, "http://host/live/playlist.m3u").channels.single()
        assertEquals("http://host/top/a.ts", ch.url)
    }

    @Test
    fun `reads catchup attributes and defaults days`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ctv" catchup="append" catchup-type="flussonic" catchup-days="3",CTV
            http://host/ctv.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, null).channels.single()
        assertEquals("flussonic", ch.catchupType)
        assertEquals(3, ch.catchupDays)
        assertEquals("append", ch.catchupSource)
    }

    @Test
    fun `catchup days default when source present but days missing`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ctv" catchup="append",CTV
            http://host/ctv.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, null).channels.single()
        assertEquals(M3uParser.DEFAULT_CATCHUP_DAYS, ch.catchupDays)
    }

    @Test
    fun `no catchup attributes means zero days`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ctv",CTV
            http://host/ctv.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, null).channels.single()
        assertEquals(0, ch.catchupDays)
        assertEquals("", ch.catchupSource)
    }

    @Test
    fun `extgrp overrides group title`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" group-title="Wrong",Channel A
            #EXTGRP:Right Group
            http://host/a.ts
        """.trimIndent()

        val res = M3uParser.parse(playlist, null)
        assertEquals("Right Group", res.channels.single().groupTitle)
        assertTrue(res.groups.contains("Right Group"))
    }

    @Test
    fun `group order is preserved without duplicates`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" group-title="News",A
            http://host/a.ts
            #EXTINF:-1 tvg-id="b" group-title="News",B
            http://host/b.ts
            #EXTINF:-1 tvg-id="c" group-title="Sports",C
            http://host/c.ts
        """.trimIndent()

        assertEquals(listOf("News", "Sports"), M3uParser.parse(playlist, null).groups)
    }

    @Test
    fun `order index follows playlist order`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",A
            http://host/a.ts
            #EXTINF:-1 tvg-id="b",B
            http://host/b.ts
            #EXTINF:-1 tvg-id="c",C
            http://host/c.ts
        """.trimIndent()

        val channels = M3uParser.parse(playlist, null).channels
        assertEquals(listOf(0, 1, 2), channels.map { it.orderIndex })
        assertEquals(listOf("a", "b", "c"), channels.map { it.streamId })
    }

    @Test
    fun `stream url is the stream id when tvg-id is missing`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Fallback Name
            http://host/fallback.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, null).channels.single()
        assertEquals("http://host/fallback.ts", ch.streamId)
        assertEquals("", ch.tvgId)
    }

    @Test
    fun `series entries are flagged`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="s1" group-title="Series" series-name="Show",S01E01
            http://host/s1.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, null).channels.single()
        assertTrue(ch.isSeries)
        assertEquals("Show", ch.seriesName)
    }

    @Test
    fun `vod group marks channel as vod`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="v1" group-title="VOD",Some Movie
            http://host/v1.ts
        """.trimIndent()

        assertTrue(M3uParser.parse(playlist, null).channels.single().isVod)
    }

    @Test
    fun `lines without extinf are ignored`() {
        val playlist = """
            #EXTM3U
            http://host/orphan.ts
            #EXTINF:-1 tvg-id="a",A
            http://host/a.ts
        """.trimIndent()

        assertEquals(1, M3uParser.parse(playlist, null).channels.size)
    }

    @Test
    fun `commas inside quoted attributes do not break parsing`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a,b" group-title="News, Sport",Weird, Title
            http://host/a.ts
        """.trimIndent()

        val ch = M3uParser.parse(playlist, null).channels.single()
        assertEquals("a,b", ch.tvgId)
        assertEquals("News, Sport", ch.groupTitle)
        assertEquals("Weird, Title", ch.name)
    }

    @Test
    fun `empty playlist yields no channels`() {
        val res = M3uParser.parse("#EXTM3U\n", null)
        assertTrue(res.channels.isEmpty())
        assertTrue(res.groups.isEmpty())
    }

    @Test
    fun `absolute keeps absolute urls and rejects blanks`() {
        assertEquals("http://a/b.ts", M3uParser.absolute("http://a/b.ts", "http://x/y.m3u"))
        assertEquals("https://a/b.ts", M3uParser.absolute("https://a/b.ts", null))
        assertNull(M3uParser.absolute("", null))
        assertNull(M3uParser.absolute(null, null))
        assertEquals("relative.ts", M3uParser.absolute("relative.ts", null))
    }

    @Test
    fun `extensionOf strips query and pipe arguments`() {
        assertEquals("ts", M3uParser.extensionOf("http://a/b.ts?token=1"))
        assertEquals("mp4", M3uParser.extensionOf("http://a/b.mp4|User-Agent=VLC"))
        assertEquals("", M3uParser.extensionOf("http://a/stream"))
    }

    @Test
    fun `cleanName strips configured prefixes and suffixes`() {
        assertEquals(
            "News",
            M3uParser.cleanName("[US] News HD", listOf("[US]"), listOf("HD")),
        )
        assertEquals("Name", M3uParser.cleanName("Name", emptyList(), emptyList()))
    }

    @Test
    fun `catchupTemplate expands placeholder tokens`() {
        val out = M3uParser.catchupTemplate("?start={start}&end={end}&utc={utc}&duration={duration}")
        assertFalse(out.contains("{start}"))
        assertFalse(out.contains("{end}"))
        assertTrue(out.contains("start="))
        assertTrue(out.contains("duration="))
    }

    @Test
    fun `parseLine parses single line entries`() {
        val ch = M3uParser.parseLine(
            """#EXTINF:-1 tvg-id="x" group-title="Kids",http://host/x.ts"""
        )
        assertEquals("x", ch?.streamId)
        assertEquals("Kids", ch?.groupTitle)
        assertEquals("http://host/x.ts", ch?.url)
    }

    @Test
    fun `parseLine returns null for non extinf lines`() {
        assertNull(M3uParser.parseLine("http://host/a.ts"))
        assertNull(M3uParser.parseLine(""))
    }
}
