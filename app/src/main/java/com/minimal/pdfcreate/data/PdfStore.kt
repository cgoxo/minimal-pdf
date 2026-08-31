package com.minimal.pdfcreate.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

data class PdfEntry(val uri: Uri, val displayName: String, val sizeBytes: Long, val modifiedAt: Long)

/**
 * Scoped-storage access to `Documents/minimalPdf`.
 *
 * On API 29+ an app may write into a public collection and later read back *its own*
 * entries without holding any storage permission, which is exactly what we need.
 */
object PdfStore {

    const val FOLDER = "minimalPdf"
    private val RELATIVE_PATH = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/"

    private val collection: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun findByName(context: Context, displayName: String): Uri? {
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=?"
        context.contentResolver.query(
            collection, projection, selection, arrayOf(RELATIVE_PATH, displayName), null
        )?.use { c ->
            if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString())
        }
        return null
    }

    fun list(context: Context): List<PdfEntry> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.Files.FileColumns.MIME_TYPE}=?"
        val out = mutableListOf<PdfEntry>()
        context.contentResolver.query(
            collection, projection, selection, arrayOf(RELATIVE_PATH, "application/pdf"),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                out += PdfEntry(
                    uri = Uri.withAppendedPath(collection, c.getLong(0).toString()),
                    displayName = c.getString(1) ?: "",
                    sizeBytes = c.getLong(2),
                    modifiedAt = c.getLong(3) * 1000L,
                )
            }
        }
        return out
    }

    /** Writes (or replaces) `Documents/minimalPdf/<displayName>` and returns its uri. */
    fun write(context: Context, displayName: String, body: (OutputStream) -> Unit): Uri {
        findByName(context, displayName)?.let { context.contentResolver.delete(it, null, null) }

        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("Could not create $displayName in $RELATIVE_PATH")

        context.contentResolver.openOutputStream(uri, "w")?.use(body)
            ?: error("Could not open $uri for writing")

        val done = ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
        return uri
    }

    fun delete(context: Context, displayName: String) {
        findByName(context, displayName)?.let { context.contentResolver.delete(it, null, null) }
    }
}
