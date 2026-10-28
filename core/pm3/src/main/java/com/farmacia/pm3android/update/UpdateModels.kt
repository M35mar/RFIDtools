// Data model shared by the in-app updater: what GitHub publishes, what the
// download produces, and the state shown by the Info screen.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.update

import java.io.File

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Hex SHA-256 from the GitHub asset digest, when available. */
    val sha256: String?,
)

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val notes: String,
    val htmlUrl: String,
    val asset: ReleaseAsset?,
)

/** Result of comparing the latest release with the installed app. */
sealed interface ReleaseCheck {
    data class Newer(val release: ReleaseInfo) : ReleaseCheck
    data class Current(val latestVersion: String) : ReleaseCheck

    /** A newer release exists but has no APK for this app channel. */
    data class Incompatible(val release: ReleaseInfo) : ReleaseCheck
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState

    data class Downloading(
        val release: ReleaseInfo,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : UpdateState {
        val progress: Float?
            get() = if (totalBytes > 0) {
                (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data class Ready(
        val release: ReleaseInfo,
        val file: File,
        val needsPermission: Boolean = false,
    ) : UpdateState

    data class Error(val message: String) : UpdateState
}
