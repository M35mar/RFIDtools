// GitHub release check and APK download. No third-party HTTP library: the
// updater only needs two GET requests, so HttpURLConnection keeps the APK
// small and the dependency list short.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class UpdateManager(private val context: Context) {

    companion object {
        private const val OWNER = "M35mar"
        private const val REPO = "RFIDtools"
        private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        private const val USER_AGENT = "RFIDtools-Android"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    suspend fun check(currentVersion: String, applicationId: String): ReleaseCheck =
        withContext(Dispatchers.IO) {
            val connection = open(API_URL).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException(serverMessage(connection, code))
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val release = UpdateParser.parseRelease(body, applicationId)
                when {
                    release.asset == null -> ReleaseCheck.Incompatible(release)
                    UpdateParser.isNewer(release.versionName, currentVersion) ->
                        ReleaseCheck.Newer(release)
                    else -> ReleaseCheck.Current(release.versionName)
                }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun download(
        asset: ReleaseAsset,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, asset.name)
        if (target.exists() && !target.delete()) {
            throw IOException("Cannot replace ${target.name}")
        }

        val connection = open(asset.downloadUrl).apply {
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException(serverMessage(connection, code))
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
            if (read == 0L) throw IOException("Empty download")
            asset.sha256?.let { expected ->
                if (!sha256Matches(target, expected)) {
                    throw IOException("APK checksum mismatch")
                }
            }
            target
        } catch (t: Throwable) {
            target.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun serverMessage(connection: HttpURLConnection, code: Int): String {
        val body = runCatching {
            (connection.errorStream ?: connection.inputStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?.take(300)
        }.getOrNull().orEmpty()
        val detail = body.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        return "HTTP $code$detail"
    }

    private fun sha256Matches(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xFF)
        }
        return actual.equals(expectedHex, ignoreCase = true)
    }
}
