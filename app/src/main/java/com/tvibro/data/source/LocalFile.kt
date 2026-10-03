package com.tvibro.data.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Reads a file the user picked on the device.
 *
 * Three shapes of reference reach the app and all of them are one string in the same column: a
 * `content://` uri from the document picker, a `file://` uri, and a plain path as it was typed or
 * stored before the picker existed. A playlist and an EPG source keep the reference they were given
 * and are read again on every update, days later and possibly after a reboot, so the reference is
 * resolved per use instead of being turned into something else on the way in.
 *
 * A picked uri is read through the content resolver, which is also the only way to reach a file that
 * lives in another app's storage or on a drive the system has mounted for everybody.
 */
internal object LocalFile {

    /** What a reference points at. */
    enum class Kind { CONTENT, FILE, PATH, REMOTE }

    /**
     * A bare name is a path: it is what the wizard stored before files could be picked, and it is
     * looked for under the places a file of the app can be in. Anything with a scheme of its own is
     * a url and is none of this object's business.
     */
    fun kindOf(ref: String): Kind {
        val trimmed = ref.trim()
        return when {
            trimmed.startsWith("content://") -> Kind.CONTENT
            trimmed.startsWith("file://") -> Kind.FILE
            trimmed.startsWith("/") -> Kind.PATH
            trimmed.contains("://") -> Kind.REMOTE
            else -> Kind.PATH
        }
    }

    /** Whether the reference names something on the device rather than a url to fetch. */
    fun isLocal(ref: String): Boolean = kindOf(ref) != Kind.REMOTE

    /**
     * Opens the reference for reading. A gzip stream is unpacked as it is read, because a file has
     * no headers to say so: an `epg.xml.gz` from the download folder is the usual shape.
     */
    @Throws(Exception::class)
    fun open(context: Context, ref: String): InputStream {
        val stream = rawStream(context, ref)
        return Http.decodeStream(stream)
    }

    @Throws(Exception::class)
    private fun rawStream(context: Context, ref: String): InputStream = when (kindOf(ref)) {
        Kind.CONTENT -> context.contentResolver.openInputStream(Uri.parse(ref.trim()))
            ?: throw FileNotFoundException("Cannot open $ref")
        Kind.FILE -> File(pathOf(ref.trim())).inputStream()
        Kind.PATH -> {
            val file = resolve(context, ref.trim())
            if (!file.isFile) throw FileNotFoundException("File not found: ${file.path}")
            file.inputStream()
        }
        Kind.REMOTE -> throw IllegalArgumentException("Not a local file: $ref")
    }

    /**
     * A path as written down, with the scheme taken off. Percent escapes are decoded, so a name with
     * a space in it survives the trip.
     *
     * A reference written the way a person writes one, `file://C:/dir/list.m3u`, puts the drive
     * letter where a uri keeps its authority. That reference is put back together here instead of
     * losing the drive and then reporting a file that cannot exist.
     */
    internal fun pathOf(ref: String): String {
    val uri = Uri.parse(ref)
    val authority = uri.authority.orEmpty()
    val path = uri.path ?: ref.removePrefix("file://")
    val whole = if (authority.isEmpty() || path.startsWith("/$authority")) path else "/$authority$path"
    return try {
        Uri.decode(whole)
    } catch (e: IllegalArgumentException) {
        whole
    }
    }

    /**
     * The file a plain path names.
     *
     * A bare name is tried where a file of this app can be: next to it, in its own external folder
     * and on the shared external storage. The last of those needs a permission on older releases and
     * may be gone on the newest ones, so it is the last resort rather than the first.
     */
    fun resolve(context: Context, ref: String): File {
        val direct = File(ref)
        if (direct.isFile) return direct
        if (!direct.isAbsolute) {
            context.getExternalFilesDir(null)?.let { dir ->
                val own = File(dir, ref)
                if (own.isFile) return own
            }
            val shared = File(Environment.getExternalStorageDirectory(), ref)
            if (shared.isFile) return shared
        }
        return direct
    }

    /**
     * The name the file has on the device, which is what a person recognises. A reference is kept as
     * it was given; only the label next to it comes from here.
     */
    fun displayName(context: Context, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return ""
        return when (kindOf(trimmed)) {
            Kind.CONTENT -> contentName(context, Uri.parse(trimmed)) ?: trimmed
            Kind.FILE, Kind.PATH -> resolve(context, pathOf(trimmed)).name.takeIf { it.isNotBlank() } ?: trimmed
            Kind.REMOTE -> trimmed
        }
    }

    private fun contentName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
    }.getOrNull()

    /**
     * Keeps the read grant to a picked file across reboots.
     *
     * Without this the grant lasts until the device restarts and the playlist then fails to update
     * for a reason that looks like a broken file. Not every provider offers a persistable grant, and
     * losing the file afterwards is worse than not being able to keep it, so this answers whether it
     * worked instead of throwing.
     */
    fun keepPermission(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess
}