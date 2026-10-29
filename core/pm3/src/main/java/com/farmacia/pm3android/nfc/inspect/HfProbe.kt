// Extension point for the HF inspector. A probe declares the technology it
// needs and returns typed details; the registry runs them in a deterministic
// order and stops at the first one that names the card. New tag families are
// added by writing a probe, not by editing the Compose screen.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.inspect.content.TagContent

class ProbeContext(
    val snapshot: TagSnapshot,
    val session: TagSession,
    val log: CommandLog,
    /** True for a tag loss, which must abort the whole inspection. */
    val abort: (Throwable) -> Boolean = { false },
)

interface HfProbe {
    /** Stable identifier, useful in logs and tests. */
    val id: String

    fun matches(context: ProbeContext): Boolean

    /** Null means "not this card"; the registry tries the next probe. */
    fun run(context: ProbeContext): ProbeOutcome?
}

class ProbeOutcome(
    val details: TagDetails? = null,
    val content: TagContent? = null,
    /** Set when the probe can name the card; the registry stops there. */
    val identity: TagIdentity? = null,
    val notes: List<TagNote> = emptyList(),
)

class ProbeResult(
    val identity: TagIdentity,
    val details: TagDetails?,
    val content: TagContent?,
    val log: List<CommandLog.Entry>,
)
