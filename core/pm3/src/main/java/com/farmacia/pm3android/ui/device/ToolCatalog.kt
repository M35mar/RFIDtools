// Curated PM3 command catalog. Kept free of Compose so command assembly,
// validation and the destructive-command policy can be unit-tested on the JVM.
//
// Every template is syntax-checked against the pinned client command trees
// (third_party/proxmark3/client/src/cmd*.c).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.device

import androidx.annotation.StringRes
import com.farmacia.pm3android.R

/** How much care the UI must take before running a command. */
enum class DangerLevel {
    SAFE,

    /** Writes to the tag (restore, increment, ...). */
    WRITE,

    /** Irreversible or potentially bricking operation. */
    DESTRUCTIVE,
}

/** A value the user types in; digitsOnly forbids anything but numbers. */
data class ToolValue(
    val name: String,
    @StringRes val label: Int,
    val placeholder: String,
    val defaultValue: String = "",
    val digitsOnly: Boolean = false,
) : ToolParam

/** A checkbox that appends its literal (already with dashes) when checked. */
data class ToolSwitch(
    val name: String,
    @StringRes val label: Int,
    val literal: String,
) : ToolParam

sealed interface ToolParam

data class ToolCommand(
    @StringRes val label: Int,
    /** Command template; "{name}" tokens are replaced by ToolValue entries. */
    val template: String,
    @StringRes val description: Int,
    val params: List<ToolParam> = emptyList(),
    val danger: DangerLevel = DangerLevel.SAFE,
)

data class ToolSectionData(
    @StringRes val title: Int,
    val commands: List<ToolCommand>,
)

/** Factories returning the common type, so list inference stays simple. */
private fun valueOf(
    name: String,
    @StringRes label: Int,
    placeholder: String,
    defaultValue: String = "",
    digitsOnly: Boolean = false,
): ToolParam = ToolValue(name, label, placeholder, defaultValue, digitsOnly)

private fun flagOf(@StringRes label: Int, literal: String): ToolParam =
    ToolSwitch(literal, label, literal)

internal fun ToolParam.asValue(): ToolValue? = this as? ToolValue
internal fun ToolParam.asSwitch(): ToolSwitch? = this as? ToolSwitch

private val LF_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_lf_em410x_label,
        "lf em 410x reader",
        R.string.tool_lf_em410x_desc,
    ),
    ToolCommand(
        R.string.tool_lf_hid_label,
        "lf hid reader",
        R.string.tool_lf_hid_desc,
    ),
    ToolCommand(
        R.string.tool_lf_ioprox_label,
        "lf io reader",
        R.string.tool_lf_ioprox_desc,
    ),
    ToolCommand(
        R.string.tool_lf_search_label,
        "lf search",
        R.string.tool_lf_search_desc,
    ),
    ToolCommand(
        R.string.tool_lf_t55xx_detect_label,
        "lf t55xx detect",
        R.string.tool_lf_t55xx_detect_desc,
    ),
    ToolCommand(
        R.string.tool_lf_t55xx_info_label,
        "lf t55xx info",
        R.string.tool_lf_t55xx_info_desc,
    ),
    ToolCommand(
        R.string.tool_lf_t55xx_dump_label,
        "lf t55xx dump",
        R.string.tool_lf_t55xx_dump_desc,
    ),
)

private val HF_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_hf_search_label,
        "hf search",
        R.string.tool_hf_search_desc,
    ),
    ToolCommand(
        R.string.tool_hf_14a_info_label,
        "hf 14a info",
        R.string.tool_hf_14a_info_desc,
    ),
    ToolCommand(
        R.string.tool_hw_tune_label,
        "hw tune",
        R.string.tool_hw_tune_desc,
    ),
)

private val MFC_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_mfc_info_label,
        "hf mf info",
        R.string.tool_mfc_info_desc,
    ),
    ToolCommand(
        R.string.tool_mfc_dump_label,
        "hf mf dump",
        R.string.tool_mfc_dump_desc,
    ),
    ToolCommand(
        R.string.tool_mfc_chk_label,
        "hf mf chk",
        R.string.tool_mfc_chk_desc,
    ),
    ToolCommand(
        R.string.tool_mfc_autopwn_label,
        "hf mf autopwn",
        R.string.tool_mfc_autopwn_desc,
    ),
)

private val MFU_EV1_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_mfuev1_info_label,
        "hf mfu info",
        R.string.tool_mfuev1_info_desc,
    ),
    ToolCommand(
        R.string.tool_mfuev1_dump_label,
        "hf mfu dump",
        R.string.tool_mfuev1_dump_desc,
    ),
    ToolCommand(
        R.string.tool_mfuev1_ndefread_label,
        "hf mfu ndefread",
        R.string.tool_mfuev1_ndefread_desc,
    ),
    ToolCommand(
        R.string.tool_mfuev1_restore_label,
        "hf mfu restore -f {file}",
        R.string.tool_mfuev1_restore_desc,
        listOf(
            valueOf(
                "file",
                R.string.tool_mfuev1_restore_param_file,
                "dump.bin",
            ),
        ),
        DangerLevel.WRITE,
    ),
    ToolCommand(
        R.string.tool_mfuev1_incr_label,
        "hf mfu incr -c {counter} -v {value}",
        R.string.tool_mfuev1_incr_desc,
        listOf(
            valueOf(
                "counter",
                R.string.tool_mfuev1_counter_param,
                "0",
                defaultValue = "0",
                digitsOnly = true,
            ),
            valueOf(
                "value",
                R.string.tool_mfuev1_increment_param,
                "1",
                defaultValue = "1",
                digitsOnly = true,
            ),
        ),
        DangerLevel.WRITE,
    ),
    ToolCommand(
        R.string.tool_mfuev1_countertear_label,
        "hf mfu countertear -c {counter}",
        R.string.tool_mfuev1_countertear_desc,
        listOf(
            valueOf(
                "counter",
                R.string.tool_mfuev1_counter_param,
                "0",
                defaultValue = "0",
                digitsOnly = true,
            ),
        ),
        DangerLevel.DESTRUCTIVE,
    ),
    ToolCommand(
        R.string.tool_mfuev1_reset_label,
        "script run mfuev1_counter_reset.lua -c {counter}",
        R.string.tool_mfuev1_reset_desc,
        listOf(
            valueOf(
                "counter",
                R.string.tool_mfuev1_counter_param,
                "0",
                defaultValue = "0",
                digitsOnly = true,
            ),
            flagOf(R.string.tool_param_skip_checks, "-f"),
        ),
        DangerLevel.DESTRUCTIVE,
    ),
)

private val ISO15693_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_iso15693_info_label,
        "hf 15 info",
        R.string.tool_iso15693_info_desc,
    ),
    ToolCommand(
        R.string.tool_iso15693_reader_label,
        "hf 15 reader",
        R.string.tool_iso15693_reader_desc,
    ),
    ToolCommand(
        R.string.tool_iso15693_dump_label,
        "hf 15 dump",
        R.string.tool_iso15693_dump_desc,
    ),
)

private val ICLASS_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_iclass_info_label,
        "hf iclass info",
        R.string.tool_iclass_info_desc,
    ),
    ToolCommand(
        R.string.tool_iclass_dump_label,
        "hf iclass dump",
        R.string.tool_iclass_dump_desc,
    ),
)

private val DESFIRE_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_desfire_info_label,
        "hf mfdes info",
        R.string.tool_desfire_info_desc,
    ),
)

val TOOL_SECTIONS = listOf(
    ToolSectionData(R.string.tool_section_lf, LF_COMMANDS),
    ToolSectionData(R.string.tool_section_hf_generic, HF_COMMANDS),
    ToolSectionData(R.string.tool_section_mfc, MFC_COMMANDS),
    ToolSectionData(R.string.tool_section_mfuev1, MFU_EV1_COMMANDS),
    ToolSectionData(R.string.tool_section_iso15693, ISO15693_COMMANDS),
    ToolSectionData(R.string.tool_section_iclass, ICLASS_COMMANDS),
    ToolSectionData(R.string.tool_section_desfire, DESFIRE_COMMANDS),
)

// ---------------------------------------------------------------------------
// Danger policy for commands that do not come from the catalog
// ---------------------------------------------------------------------------

/** Irreversible operations: erase, wipe, UID rewrite, counter tearing. */
private val DESTRUCTIVE_VERBS = setOf(
    "countertear",
    "erase",
    "wipe",
    "eclr",
    "setuid",
    "cload",
)

/** Operations that write to the card but can be repeated. */
private val WRITE_VERBS = setOf(
    "write",
    "wrbl",
    "restore",
    "incr",
    "increment",
    "decr",
    "decrement",
    "setblk",
    "load",
)

/** The subcommand path ("hf mfu restore") without options or placeholders. */
private fun commandKey(text: String): String = text
    .trim()
    .split(Regex("\\s+"))
    .asSequence()
    .filter { it.isNotBlank() }
    .filterNot { it.startsWith("-") || it.contains("{") }
    .take(3)
    .joinToString(" ")

private val CATALOG_DANGER: Map<String, DangerLevel> = buildMap {
    for (section in TOOL_SECTIONS) {
        for (command in section.commands) {
            val key = commandKey(command.template)
            val current = this[key]
            if (current == null || command.danger.ordinal > current.ordinal) {
                put(key, command.danger)
            }
        }
    }
}

/**
 * How careful the UI must be with a command the user typed: the console
 * history and the "run again" action of a result card must not skip the
 * confirmation the catalog asks for.
 *
 * A command that is not in the catalog cannot be classified, so it is treated
 * as a write: one extra confirmation is cheaper than an accidental wipe.
 */
fun dangerFor(commandText: String): DangerLevel {
    if (commandText.isBlank()) return DangerLevel.SAFE
    CATALOG_DANGER[commandKey(commandText)]?.let { return it }
    val tokens = commandText.lowercase().split(Regex("\\s+"))
    return when {
        tokens.any { it in DESTRUCTIVE_VERBS } -> DangerLevel.DESTRUCTIVE
        tokens.any { it in WRITE_VERBS } -> DangerLevel.WRITE
        // A command the catalog does not know: one extra confirmation is
        // cheaper than an accidental write.
        else -> DangerLevel.WRITE
    }
}

/** Substitutes {name} tokens and appends checked switch literals. */
fun composeCommand(
    command: ToolCommand,
    values: Map<String, String>,
    switches: Map<String, Boolean>,
): String {
    var result = command.template
    for (param in command.params) {
        param.asValue()?.let { value ->
            result = result.replace(
                "{${value.name}}",
                values[value.name]?.takeIf { it.isNotBlank() } ?: value.defaultValue,
            )
        }
        param.asSwitch()?.let { switch ->
            if (switches[switch.name] == true) {
                result = "$result ${switch.literal}"
            }
        }
    }
    return result
}

fun entryValid(entry: ToolValue, text: String?): Boolean {
    val content = text.orEmpty()
    return if (entry.digitsOnly) {
        content.isNotEmpty() && content.all { it.isDigit() }
    } else {
        content.isNotBlank()
    }
}
