// Unit tests for the Compose-free PM3 command catalog.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.device

import com.farmacia.pm3android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {

    @Test
    fun `values and switches are substituted`() {
        val command = ToolCommand(
            label = R.string.tool_mfuev1_incr_label,
            template = "hf mfu incr -c {counter} -v {value}",
            description = R.string.tool_mfuev1_incr_desc,
            params = listOf(
                ToolValue("counter", R.string.tool_mfuev1_counter_param, "0", "1", true),
                ToolValue("value", R.string.tool_mfuev1_increment_param, "1", "2", true),
            ),
        )

        assertEquals(
            "hf mfu incr -c 3 -v 4",
            composeCommand(command, mapOf("counter" to "3", "value" to "4"), emptyMap()),
        )
        assertEquals(
            "hf mfu incr -c 1 -v 2",
            composeCommand(command, emptyMap(), emptyMap()),
        )
    }

    @Test
    fun `switches append their literal when checked`() {
        val command = ToolCommand(
            label = R.string.tool_mfuev1_reset_label,
            template = "script run mfuev1_counter_reset.lua -c {counter}",
            description = R.string.tool_mfuev1_reset_desc,
            params = listOf(
                ToolValue("counter", R.string.tool_mfuev1_counter_param, "0", "0", true),
                ToolSwitch("skip", R.string.tool_param_skip_checks, "-f"),
            ),
            danger = DangerLevel.DESTRUCTIVE,
        )

        assertEquals(
            "script run mfuev1_counter_reset.lua -c 2 -f",
            composeCommand(
                command,
                mapOf("counter" to "2"),
                mapOf("skip" to true),
            ),
        )
        assertEquals(
            "script run mfuev1_counter_reset.lua -c 2",
            composeCommand(
                command,
                mapOf("counter" to "2"),
                mapOf("skip" to false),
            ),
        )
    }

    @Test
    fun `digit-only values require digits`() {
        val value = ToolValue("counter", R.string.tool_mfuev1_counter_param, "0", digitsOnly = true)

        assertTrue(entryValid(value, "0"))
        assertTrue(entryValid(value, "12"))
        assertFalse(entryValid(value, ""))
        assertFalse(entryValid(value, "1a"))
        assertFalse(entryValid(value, "-1"))
    }

    @Test
    fun `catalog keeps destructive commands behind a confirmation`() {
        val commands = TOOL_SECTIONS.flatMap { it.commands }

        val restore = commands.single { it.template.startsWith("hf mfu restore") }
        val countertear = commands.single { it.template.startsWith("hf mfu countertear") }
        val reset = commands.single { it.template.contains("mfuev1_counter_reset") }

        assertEquals(DangerLevel.WRITE, restore.danger)
        assertEquals(DangerLevel.DESTRUCTIVE, countertear.danger)
        assertEquals(DangerLevel.DESTRUCTIVE, reset.danger)
    }

    @Test
    fun `catalog covers the new tool families`() {
        val templates = TOOL_SECTIONS.flatMap { section ->
            section.commands.map { it.template }
        }

        assertTrue("lf search" in templates)
        assertTrue("hf search" in templates)
        assertTrue("hw tune" in templates)
        assertTrue("hf 15 info" in templates)
        assertTrue("hf iclass info" in templates)
        assertTrue("hf mfdes info" in templates)
        assertTrue("hf mfu ndefread" in templates)
    }
}
