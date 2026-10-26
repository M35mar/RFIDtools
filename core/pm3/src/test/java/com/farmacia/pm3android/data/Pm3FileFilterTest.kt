// Unit tests for the client-resource visibility and category rules.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pm3FileFilterTest {

    @Test
    fun userFilesAreVisible() {
        assertTrue(Pm3FileFilter.isVisible("dump.bin"))
        assertTrue(Pm3FileFilter.isVisible("subdir/trace.bin"))
        assertTrue(Pm3FileFilter.isVisible(".proxmark3/logs/pm3.log"))
        assertTrue(Pm3FileFilter.isVisible(".proxmark3/traces/hf.trace"))
    }

    @Test
    fun clientResourcesAreHidden() {
        assertFalse(Pm3FileFilter.isVisible(".proxmark3/dictionaries/default_keys.dic"))
        assertFalse(Pm3FileFilter.isVisible(".proxmark3/lualibs/foo.lua"))
        assertFalse(Pm3FileFilter.isVisible(".proxmark3/luascripts/tool.lua"))
        assertFalse(Pm3FileFilter.isVisible(".proxmark3/cmdscripts/foo.cmd"))
        assertFalse(Pm3FileFilter.isVisible(".proxmark3/resources/foo.json"))
        assertFalse(Pm3FileFilter.isVisible(".proxmark3/prefs.json"))
        assertFalse(Pm3FileFilter.isVisible(".hidden"))
        assertFalse(Pm3FileFilter.isVisible(""))
    }

    @Test
    fun categoriesFollowClientOutputConventions() {
        assertEquals(FileCategory.DUMP, Pm3FileFilter.categoryOf("hf-mf-dump.bin"))
        assertEquals(FileCategory.DUMP, Pm3FileFilter.categoryOf("card.eml"))
        assertEquals(FileCategory.TRACE, Pm3FileFilter.categoryOf(".proxmark3/traces/hf.trace"))
        assertEquals(FileCategory.TRACE, Pm3FileFilter.categoryOf("capture.trc"))
        assertEquals(FileCategory.LOG, Pm3FileFilter.categoryOf(".proxmark3/logs/pm3.log"))
        assertEquals(FileCategory.OTHER, Pm3FileFilter.categoryOf("notes.txt"))
    }

    @Test
    fun pathNormalizationIsStable() {
        assertEquals(".proxmark3/logs/a.log", Pm3FileFilter.normalize("\\.proxmark3\\logs\\a.log"))
        assertTrue(Pm3FileFilter.isVisible("/dump.bin"))
    }

    @Test
    fun deleteSafetyRejectsFilesOutsideHome() {
        val home = java.nio.file.Files.createTempDirectory("pm3-home").toFile()
        val inside = java.io.File(home, "dump.bin").apply { writeText("x") }
        val outside = java.nio.file.Files.createTempFile("pm3-outside", ".bin").toFile()
        try {
            assertTrue(Pm3FileFilter.isInsideHome(home, inside))
            assertFalse(Pm3FileFilter.isInsideHome(home, outside))
        } finally {
            home.deleteRecursively()
            outside.delete()
        }
    }
}
