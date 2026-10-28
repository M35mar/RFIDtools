// Rules for what the Files screen may show and delete.
//
// The client's $HOME also contains dictionaries, Lua libraries, scripts and
// resources installed from the APK. Those are implementation details and must
// never be exposed as user data: deleting them breaks the client.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.data

import java.io.File

enum class FileCategory {
    DUMP,
    TRACE,
    LOG,
    REPORT,
    OTHER,
}

object Pm3FileFilter {

    private const val CLIENT_DIR = ".proxmark3"

    /** Reports written by the NFC inspector; key `.txt` files stay OTHER. */
    const val REPORT_SUFFIX = "-report.txt"

    fun normalize(relativePath: String): String =
        relativePath.replace('\\', '/').trimStart('/')

    /** True for files the user may see, share, export and delete. */
    fun isVisible(relativePath: String): Boolean {
        val path = normalize(relativePath)
        if (path.isBlank()) return false
        if (path.startsWith(".")) {
            return path.startsWith("$CLIENT_DIR/logs/") ||
                path.startsWith("$CLIENT_DIR/traces/")
        }
        return true
    }

    fun categoryOf(relativePath: String): FileCategory {
        val path = normalize(relativePath).lowercase()
        return when {
            path.endsWith(REPORT_SUFFIX) -> FileCategory.REPORT
            path.startsWith("$CLIENT_DIR/logs/") || path.endsWith(".log") -> FileCategory.LOG
            path.startsWith("$CLIENT_DIR/traces/") ||
                path.endsWith(".trace") ||
                path.endsWith(".trc") -> FileCategory.TRACE
            path.endsWith(".bin") ||
                path.endsWith(".dump") ||
                path.endsWith(".eml") ||
                path.endsWith(".json") -> FileCategory.DUMP
            else -> FileCategory.OTHER
        }
    }

    fun isInsideHome(home: File, file: File): Boolean = runCatching {
        val homePath = home.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        filePath.startsWith(homePath)
    }.getOrDefault(false)
}
