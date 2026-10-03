package com.tvibro.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * The descriptions are the bulk of a guide file and the setting that leaves them out has never run,
 * so both paths are pinned here: the text comes through when it is asked for, and the file still
 * parses cleanly when it is not.
 *
 * Robolectric rather than a bare JVM: the XML pull parser factory has no implementation to find on a
 * plain test JVM, which is the one part of this class that has nothing to do with the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class XmltvParserTest {

    @Test
    fun `descriptions are kept when they are stored`() {
        val result = parse(STORE, storeDescriptions = true)

        assertEquals("A long story about nothing.", result.programmes.single().description)
    }

    @Test
    fun `the language the guide is read in wins`() {
        val result = parse(TWO_LANGUAGES, storeDescriptions = true)

        assertEquals("English text.", result.programmes.single().description)
    }

    @Test
    fun `no description is left behind when they are not stored`() {
        val result = parse(STORE, storeDescriptions = false)

        assertEquals("", result.programmes.single().description)
    }

    /**
     * The rest of the programme has to survive: a guide without descriptions is still a guide, and
     * the title, time and channel are what the whole player is built on.
     */
    @Test
    fun `the rest of the programme survives without descriptions`() {
        val result = parse(TWO_PROGRAMMES, storeDescriptions = false)

        assertEquals(listOf("First", "Second"), result.programmes.map { it.title })
        assertEquals(listOf("news", "news"), result.programmes.map { it.tvgId })
        assertTrue(result.programmes.all { it.stop > it.start })
    }

    /** <desc> may carry markup of its own, which is exactly what the reader has to step over. */
    @Test
    fun `markup inside a description does not derail the parse`() {
        val result = parse(MARKED_UP, storeDescriptions = false)

        assertEquals(2, result.programmes.size)
        assertEquals("Second", result.programmes[1].title)
        assertTrue(result.programmes.all { it.description.isEmpty() })
    }

    /**
     * The paragraphs of a description are joined by a line break, and everything after the
     * description in the same programme is still read: this used to throw on the marked up text and
     * take the whole source down with it.
     */
    @Test
    fun `paragraphs of a description are joined and the rest survives`() {
        val result = parse(MARKED_UP, storeDescriptions = true)

        val first = result.programmes.first()
        assertEquals("Opening remarks.\nClosing remarks.", first.description)
        assertEquals("News", first.category)
        assertEquals("Second", result.programmes[1].title)
    }

    @Test
    fun `inline markup inside a description runs on with the text`() {
        val result = parse(INLINE, storeDescriptions = true)

        assertEquals("Some bold words.", result.programmes.single().description)
    }

    private fun parse(xml: String, storeDescriptions: Boolean) =
        XmltvParser(storeDescriptions = storeDescriptions)
            .parse(ByteArrayInputStream(xml.toByteArray()))

    private companion object {
        const val STORE = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="news">
    <title>First</title>
    <desc>A long story about nothing.</desc>
  </programme>
</tv>"""

        const val TWO_LANGUAGES = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="news">
    <title>First</title>
    <desc lang="ru">Русский текст.</desc>
    <desc lang="en">English text.</desc>
  </programme>
</tv>"""

        const val TWO_PROGRAMMES = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="news">
    <title>First</title>
    <desc>One.</desc>
  </programme>
  <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="news">
    <title>Second</title>
    <desc>Two.</desc>
  </programme>
</tv>"""

        const val MARKED_UP = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="news">
    <title>First</title>
    <desc lang="en"><p>Opening remarks.</p><p>Closing remarks.</p></desc>
    <category>News</category>
  </programme>
  <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="news">
    <title>Second</title>
    <desc>Plain.</desc>
  </programme>
</tv>"""

        const val INLINE = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="news">
    <title>First</title>
    <desc>Some <b>bold</b> words.</desc>
  </programme>
</tv>"""
    }
}