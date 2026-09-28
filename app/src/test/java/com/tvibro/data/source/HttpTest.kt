package com.tvibro.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class HttpTest {

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `readAll returns plain body untouched`() {
        val payload = "#EXTM3U\nplain text\n"
        val result = Http.readAll(ByteArrayInputStream(bytes(payload)), null)
        assertEquals(payload, String(result, Charsets.UTF_8))
    }

    @Test
    fun `readAll unwraps gzip content encoding`() {
        val payload = "#EXTM3U\ngzip body\n"
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(bytes(payload)) }
        val result = Http.readAll(ByteArrayInputStream(buffer.toByteArray()), "gzip")
        assertEquals(payload, String(result, Charsets.UTF_8))
    }

    @Test
    fun `readAll ignores unrelated content encoding`() {
        val payload = "identity body"
        val result = Http.readAll(ByteArrayInputStream(bytes(payload)), "br")
        assertEquals(payload, String(result, Charsets.UTF_8))
    }

    @Test
    fun `encode escapes reserved characters`() {
        assertEquals("a+b%26c", Http.encode("a b&c"))
    }

    @Test
    fun `result text is utf8 decoded`() {
        val res = Http.Result(200, bytes("Привет"), "http://x", emptyMap())
        assertTrue(res.ok)
        assertEquals("Привет", res.text)
    }

    @Test
    fun `result ok is false for error codes`() {
        assertEquals(false, Http.Result(404, ByteArray(0), "http://x", emptyMap()).ok)
        assertEquals(false, Http.Result(500, ByteArray(0), "http://x", emptyMap()).ok)
        assertEquals(true, Http.Result(299, ByteArray(0), "http://x", emptyMap()).ok)
    }

    @Test
    fun `http exception carries status code`() {
        val e = Http.HttpException(403, "forbidden")
        assertEquals(403, e.code)
    }

    @Test
    fun `default user agent is a browser string`() {
        assertTrue(Http.DEFAULT_UA.contains("Mozilla/5.0"))
    }

    @Test
    fun `null body result is empty not crashing`() {
        assertEquals(0, Http.Result(204, ByteArray(0), "http://x", emptyMap()).body.size)
        assertNull(Http.Result(204, ByteArray(0), "http://x", emptyMap()).text.takeIf { false })
    }
}
