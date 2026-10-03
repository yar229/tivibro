package com.tvibro.data.source

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * A picked file is stored as one string and read again on every update, so what matters is that every
 * shape of reference still opens and still says what it is called after the app has been restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalFileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val m3u = "#EXTM3U\n#EXTINF:-1,One,http://a/1.ts\nhttp://a/1.ts\n"

    @Test
    fun `a content uri is not a path`() {
        assertEquals(LocalFile.Kind.CONTENT, LocalFile.kindOf("content://com.android.providers/x/document/12%3A4"))
        assertTrue(LocalFile.isLocal("content://com.android.providers/x/document/12"))
    }

    @Test
    fun `a file uri is a file and an absolute path is a path`() {
        assertEquals(LocalFile.Kind.FILE, LocalFile.kindOf("file:///storage/emulated/0/Download/list.m3u"))
        assertEquals(LocalFile.Kind.PATH, LocalFile.kindOf("/storage/emulated/0/Download/list.m3u"))
    }

    @Test
    fun `a url is none of this object's business`() {
        assertEquals(LocalFile.Kind.REMOTE, LocalFile.kindOf("http://example.com/list.m3u"))
        assertEquals(LocalFile.Kind.REMOTE, LocalFile.kindOf("https://example.com/list.m3u"))
        assertFalse(LocalFile.isLocal("http://example.com/list.m3u"))
    }

    @Test
    fun `a bare name is a path, because that is what an older playlist holds`() {
        assertEquals(LocalFile.Kind.PATH, LocalFile.kindOf("playlist.m3u"))
    }

    @Test
    fun `an absolute path is read as it is`() {
        val file = write("list.m3u", m3u)

        assertEquals(m3u, read(file.absolutePath))
    }

    @Test
    fun `a file uri is read through its path`() {
        val file = write("list.m3u", m3u)

        assertEquals(m3u, read(Uri.fromFile(file).toString()))
    }

    @Test
    fun `a name with a space survives the trip through a uri`() {
        val file = write("my list.m3u", m3u)

        assertEquals(m3u, read(Uri.fromFile(file).toString()))
    }

    @Test
    fun `a drive letter written where a uri keeps its authority is put back`() {
        assertEquals("/C:/dir/list.m3u", LocalFile.pathOf("file://C:/dir/list.m3u"))
    }

    @Test
    fun `a percent escape in a name is decoded`() {
        assertEquals("/dir/my list.m3u", LocalFile.pathOf("file:///dir/my%20list.m3u"))
    }

    @Test
    fun `a bare name is looked for in the folder of the app`() {
        val dir = context.getExternalFilesDir(null)!!
        val file = File(dir, "from-app-folder.m3u")
        FileOutputStream(file).use { it.write(m3u.toByteArray()) }

        assertEquals(m3u, read("from-app-folder.m3u"))
    }

    @Test
    fun `a name on screen is the name of the file`() {
        val file = write("evening.m3u", m3u)

        assertEquals("evening.m3u", LocalFile.displayName(context, file.absolutePath))
    }

    @Test
    fun `a url is shown as it is`() {
        assertEquals(
            "http://example.com/list.m3u",
            LocalFile.displayName(context, "http://example.com/list.m3u"),
        )
    }

    @Test
    fun `a missing file says so instead of handing back nothing`() {
        val thrown = runCatching { read("/nowhere/at/all/list.m3u") }.exceptionOrNull()

        assertTrue(thrown is java.io.FileNotFoundException)
    }

    private fun write(name: String, text: String): File {
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { it.write(text.toByteArray()) }
        return file
    }

    private fun read(ref: String): String =
        LocalFile.open(context, ref).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toString()
        }
}