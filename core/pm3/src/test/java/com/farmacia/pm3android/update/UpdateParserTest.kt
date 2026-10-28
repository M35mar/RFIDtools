// Version comparison and release parsing are pure JVM logic, so they are
// covered without an emulator.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParserTest {

    @Test
    fun comparesNumericSegments() {
        assertTrue(UpdateParser.isNewer("0.7.0", "0.6.0"))
        assertTrue(UpdateParser.isNewer("0.10.0", "0.9.9"))
        assertTrue(UpdateParser.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateParser.isNewer("0.6.0", "0.6.0"))
        assertFalse(UpdateParser.isNewer("0.5.9", "0.6.0"))
    }

    @Test
    fun ignoresPrefixAndBuildSuffix() {
        assertTrue(UpdateParser.isNewer("v0.7.0", "0.6.0-debug"))
        assertEquals("0.7.0", UpdateParser.displayVersion("v0.7.0"))
        assertEquals(listOf(0, 7), UpdateParser.numericVersion("0.7-debug"))
        assertNull(UpdateParser.numericVersion("nightly"))
    }

    @Test
    fun parsesReleaseAndSelectsStableAsset() {
        val release = UpdateParser.parseRelease(RELEASE_JSON, "com.farmacia.pm3android")

        assertEquals("0.7.0", release.versionName)
        assertEquals("New NFC section", release.notes)
        assertEquals("pm3-release.apk", release.asset?.name)
        assertEquals("https://example.test/pm3-release.apk", release.asset?.downloadUrl)
        assertEquals("abc123", release.asset?.sha256)
    }

    @Test
    fun debugBuildOnlyAcceptsDebugAsset() {
        val release = UpdateParser.parseRelease(RELEASE_JSON, "com.farmacia.pm3android.debug")

        assertEquals("pm3-debug.apk", release.asset?.name)
    }

    @Test
    fun releaseBuildHasNoCompatibleAssetWhenOnlyDebugIsPublished() {
        val json = RELEASE_JSON.replace("pm3-release.apk", "pm3-other.apk")
        val release = UpdateParser.parseRelease(json, "com.farmacia.pm3android")

        assertNull(release.asset)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrerelease() {
        UpdateParser.parseRelease(
            RELEASE_JSON.replace("\"prerelease\": false", "\"prerelease\": true"),
            "com.farmacia.pm3android",
        )
    }

    private companion object {
        const val RELEASE_JSON = """
            {
              "tag_name": "0.7.0",
              "name": "RFIDtools 0.7.0",
              "body": "New NFC section",
              "html_url": "https://example.test/release",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "pm3-debug.apk",
                  "browser_download_url": "https://example.test/pm3-debug.apk",
                  "size": 100,
                  "digest": "sha256:def456"
                },
                {
                  "name": "pm3-release.apk",
                  "browser_download_url": "https://example.test/pm3-release.apk",
                  "size": 200,
                  "digest": "sha256:abc123"
                }
              ]
            }
        """
    }
}
