// Parsing and version rules for the GitHub releases endpoint. Kept free of
// Android dependencies so the comparison and asset selection can be unit
// tested on the JVM.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.update

import org.json.JSONArray
import org.json.JSONObject

object UpdateParser {

    private const val RELEASE_APK = "pm3-release.apk"
    private const val DEBUG_APK = "pm3-debug.apk"

    private val VERSION_PREFIX = Regex("""^[vV]?(\d+(?:\.\d+)*)""")

    /**
     * Extracts the numeric part of a tag or version name. `v0.7.0`,
     * `0.7.0-debug` and `0.7` all become `0.7.0` / `[0, 7]` respectively.
     */
    fun numericVersion(raw: String): List<Int>? {
        val match = VERSION_PREFIX.find(raw.trim()) ?: return null
        // Every component must parse: dropping an overflowing one
        // ("2.0.99999999999") would silently compare a shorter version.
        val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.isNotEmpty() }
    }

    /** Human-readable version without the leading `v`. */
    fun displayVersion(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")

    fun isNewer(candidate: String, current: String): Boolean {
        val left = numericVersion(candidate) ?: return false
        val right = numericVersion(current) ?: return false
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private val TRUSTED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    /** HTTPS on a GitHub host only: anything else is not a release asset. */
    fun isTrustedDownloadUrl(raw: String): Boolean {
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Parses `/releases/latest`. Draft and prerelease documents are rejected:
     * an update prompt must never point at an unstable build.
     */
    fun parseRelease(json: String, applicationId: String): ReleaseInfo {
        val root = JSONObject(json)
        require(!root.optBoolean("draft", false)) { "Draft release" }
        require(!root.optBoolean("prerelease", false)) { "Prerelease" }

        val tag = root.optString("tag_name").ifBlank {
            root.optString("name")
        }
        val version = displayVersion(tag)
        require(version.isNotBlank()) { "Missing release version" }

        return ReleaseInfo(
            tagName = tag,
            versionName = version,
            notes = root.optString("body").trim(),
            htmlUrl = root.optString("html_url"),
            asset = selectAsset(root.optJSONArray("assets"), applicationId),
        )
    }

    /**
     * Release builds only accept the stably-signed APK; debug builds only
     * accept the debug APK. Installing the other channel would either fail on
     * the signature or create a second app with a different package id.
     */
    fun selectAsset(assets: JSONArray?, applicationId: String): ReleaseAsset? {
        if (assets == null) return null
        val debugChannel = applicationId.endsWith(".debug")
        val preferred = if (debugChannel) DEBUG_APK else RELEASE_APK
        val selected = (0 until assets.length())
            .mapNotNull { index -> assets.optJSONObject(index) }
            .firstOrNull {
                it.optString("name").equals(preferred, ignoreCase = true)
            } ?: return null

        // The digest and the URL come from the same JSON document, so the URL
        // must be restricted to the release host on its own.
        val url = selected.optString("browser_download_url")
        if (!isTrustedDownloadUrl(url)) return null
        return ReleaseAsset(
            name = selected.optString("name"),
            downloadUrl = url,
            sizeBytes = selected.optLong("size", 0L),
            sha256 = selected.optString("digest")
                .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.takeIf { it.isNotBlank() },
        )
    }
}
