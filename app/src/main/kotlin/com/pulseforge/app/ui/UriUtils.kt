package com.pulseforge.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Reads a provider-supplied display name and size without copying the file. */
object UriUtils {

    data class DocumentInfo(val displayName: String, val sizeBytes: Long?)

    fun describe(context: Context, uri: Uri): DocumentInfo {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return DocumentInfo(
            displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported.apk",
            sizeBytes = size
        )
    }
}
