package com.tvibro.data.source

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.concurrent.thread

object Http {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    class Result(
        val code: Int,
        val body: ByteArray,
        val url: String,
        val headers: Map<String, List<String>>,
    ) {
        val ok: Boolean get() = code in 200..299
        val text: String get() = String(body, Charsets.UTF_8)
    }

    class HttpException(val code: Int, message: String) : Exception(message)

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    @Throws(Exception::class)
    fun get(
        url: String,
        userAgent: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 20000,
        readTimeoutMs: Int = 30000,
        maxRedirects: Int = 5,
    ): Result {
        val conn = connectGet(url, userAgent, headers, connectTimeoutMs, readTimeoutMs, maxRedirects)
        val code = conn.responseCode
        val stream: InputStream? =
            if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { readAll(it, conn.contentEncoding) } ?: ByteArray(0)
        val hdr = conn.headerFields.filterKeys { it != null }
            .mapKeys { it.key!! }
        val result = Result(code, body, conn.url.toString(), hdr)
        conn.disconnect()
        return result
    }

    /**
     * Hands out the response while it is still downloading, which is the only way through an EPG
     * archive that unpacks to hundreds of megabytes. A gzip archive served as a file is unpacked on
     * the fly: such hosts answer with a x-gzip content type and no content encoding header, so the
     * magic bytes have to be looked at as well. Closing the stream closes the connection.
     */
    @Throws(Exception::class)
    fun openStream(
        url: String,
        userAgent: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 20000,
        readTimeoutMs: Int = 30000,
        maxRedirects: Int = 5,
    ): InputStream {
        val conn = connectGet(url, userAgent, headers, connectTimeoutMs, readTimeoutMs, maxRedirects)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw HttpException(code, "HTTP $code")
        }
        return decoded(BufferedInputStream(conn.inputStream), conn.contentEncoding)
    }

    /** Follows the redirects by hand, because the status has to be known before the body is read. */
    @Throws(Exception::class)
    private fun connectGet(
        url: String,
        userAgent: String?,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxRedirects: Int,
    ): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val conn = open(current, "GET", userAgent, headers, connectTimeoutMs, readTimeoutMs)
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location.isNullOrBlank() || redirects++ >= maxRedirects) {
                    conn.disconnect()
                    throw HttpException(code, "Too many redirects")
                }
                conn.disconnect()
                current = URL(URL(current), location).toString()
                continue
            }
            return conn
        }
    }

    /**
     * Unpacks a stream that may be gzipped but does not say so.
     *
     * A response says it in a header; a file on the device has no headers at all, and the only thing
     * that tells an `epg.xml.gz` from an `epg.xml` is the magic number at its start.
     */
    fun decodeStream(stream: InputStream): InputStream = decoded(BufferedInputStream(stream), null)

    private fun decoded(stream: BufferedInputStream, contentEncoding: String?): InputStream {
        val encoding = contentEncoding?.lowercase()
        if (encoding != null) {
            if (encoding.contains("gzip")) return GZIPInputStream(stream)
            if (encoding.contains("deflate")) return InflaterInputStream(stream)
        }
        stream.mark(2)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(stream) else stream
    }

    @Throws(Exception::class)
    fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        userAgent: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 20000,
        readTimeoutMs: Int = 30000,
    ): Result {
        val conn = open(url, "POST", userAgent, headers, connectTimeoutMs, readTimeoutMs)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val out = stream?.use { readAll(it, conn.contentEncoding) } ?: ByteArray(0)
        val hdr = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
        val result = Result(code, out, url, hdr)
        conn.disconnect()
        return result
    }

    @Throws(Exception::class)
    fun postForm(
        url: String,
        form: Map<String, String>,
        userAgent: String? = null,
        connectTimeoutMs: Int = 20000,
        readTimeoutMs: Int = 30000,
    ): Result {
        val body = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            .toByteArray(Charsets.UTF_8)
        return post(url, body, "application/x-www-form-urlencoded", userAgent, emptyMap(), connectTimeoutMs, readTimeoutMs)
    }

    fun getAsync(
        url: String,
        userAgent: String? = null,
        onResult: (Result?) -> Unit,
        onError: ((Exception) -> Unit)? = null,
    ) = thread(name = "tvibro-http", isDaemon = true) {
        try {
            onResult(get(url, userAgent))
        } catch (e: Exception) {
            onError?.invoke(e) ?: onResult(null)
        }
    }

    @Throws(Exception::class)
    fun getText(url: String, userAgent: String? = null, readTimeoutMs: Int = 60000): String {
        val res = get(url, userAgent, readTimeoutMs = readTimeoutMs)
        if (!res.ok) throw HttpException(res.code, "HTTP ${res.code}")
        return res.text
    }

    fun readAll(stream: InputStream, contentEncoding: String?): ByteArray {
        var input: InputStream = BufferedInputStream(stream)
        val encoding = contentEncoding?.lowercase()
        if (encoding != null) {
            input = when {
                encoding.contains("gzip") -> GZIPInputStream(input)
                encoding.contains("deflate") -> InflaterInputStream(input)
                else -> input
            }
        }
        return input.use { input ->
            val buffer = ByteArrayOutputStream(64 * 1024)
            val chunk = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        }
    }

    fun downloadToFile(url: String, target: File, userAgent: String? = null, progress: ((Long, Long) -> Unit)? = null): File {
        val result = get(url, userAgent, readTimeoutMs = 60000)
        if (!result.ok) throw HttpException(result.code, "HTTP ${result.code}")
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            val total = result.body.size.toLong()
            result.body.inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    done += n
                    progress?.invoke(done, total)
                }
            }
        }
        return target
    }

    private fun open(
        url: String,
        method: String,
        userAgent: String?,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("Connection", "close")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }
}
