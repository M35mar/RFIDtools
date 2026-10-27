// File helpers for the phone-NFC screens.
//
// The Data tab and the NFC overlay share the same folder: a file imported here
// is copied into [Pm3Resources.home] so it can also be listed, renamed or
// deleted from the Data tab. The import is a copy, not a persisted URI grant,
// so the picker can be used with any document provider.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.farmacia.pm3android.R
import com.farmacia.pm3android.pm3.Pm3Resources
import java.io.File
import java.io.IOException

object NfcFileStore {

    /** Copies a document chosen with SAF into the app home and returns it. */
    fun import(context: Context, uri: Uri): File {
        val home = Pm3Resources.home(context)
        home.mkdirs()
        val displayName = queryDisplayName(context, uri)
            ?: "nfc-${System.currentTimeMillis()}.bin"
        // Only key files are accepted: a .keys dictionary, an .eml dump or a
        // document without six-byte keys is refused before anything is copied.
        if (!MfcKeyFiles.isKeyFile(displayName)) {
            throw DumpFormatException(context.getString(R.string.nfc_import_format))
        }
        val target = uniqueTarget(home, sanitizeFileName(displayName))
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException(context.getString(R.string.common_input_unavailable))
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return target
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) it.getString(index) else null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val invalid = "\\/:*?\"<>|"
        val cleaned = buildString {
            for (char in name) {
                append(if (char in invalid || char.code < 0x20) '_' else char)
            }
        }
            .trim()
            .trimStart('.')
            .take(128)
        return cleaned.ifBlank { "nfc-${System.currentTimeMillis()}.bin" }
    }

    private fun uniqueTarget(home: File, name: String): File {
        var candidate = File(home, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(home, "$stem-$counter$extension")
            counter++
        }
        return candidate
    }
}
