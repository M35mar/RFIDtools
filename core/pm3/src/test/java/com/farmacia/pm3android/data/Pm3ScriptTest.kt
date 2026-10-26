// Unit tests for script name validation and language detection.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pm3ScriptTest {

    @Test
    fun supportedScriptNamesAreNormalized() {
        assertEquals("my_script.lua", Pm3ScriptStore.sanitizeName("my script.lua"))
        assertEquals("tool.cmd", Pm3ScriptStore.sanitizeName("../tool.CMD"))
        assertEquals("hidden.lua", Pm3ScriptStore.sanitizeName(".hidden.lua"))
        assertEquals("a_b.lua", Pm3ScriptStore.sanitizeName("a:b.lua"))
    }

    @Test
    fun unsupportedOrUnsafeNamesAreRejected() {
        assertNull(Pm3ScriptStore.sanitizeName("script.txt"))
        assertNull(Pm3ScriptStore.sanitizeName("script"))
        assertNull(Pm3ScriptStore.sanitizeName(".lua"))
        assertNull(Pm3ScriptStore.sanitizeName("script.lua.exe"))
        assertNull(Pm3ScriptStore.sanitizeName("////"))
    }

    @Test
    fun pythonNamesAreAccepted() {
        assertEquals("tool.py", Pm3ScriptStore.sanitizeName("../tool.PY"))
    }

    @Test
    fun languageDetectionUsesTheLastExtension() {
        assertEquals(ScriptLanguage.LUA, Pm3ScriptStore.languageForName("tool.lua"))
        assertEquals(ScriptLanguage.CMD, Pm3ScriptStore.languageForName("tool.CMD"))
        assertEquals(ScriptLanguage.PYTHON, Pm3ScriptStore.languageForName("tool.py"))
        assertNull(Pm3ScriptStore.languageForName("tool"))
    }

    @Test
    fun allScriptLanguagesAreEnabled() {
        assertTrue(ScriptLanguage.LUA.extension in Pm3ScriptStore.supportedExtensions)
        assertTrue(ScriptLanguage.CMD.extension in Pm3ScriptStore.supportedExtensions)
        assertTrue(ScriptLanguage.PYTHON.extension in Pm3ScriptStore.supportedExtensions)
    }
}
