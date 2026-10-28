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
        return match.groupValues[1]
            .split('.')
            .mapNotNull { it.toIntOrNull() }
            .takeIf { it.isNotEmpty() }
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

        val url = selected.optString("browser_download_url")
        if (url.isBlank()) return null
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
