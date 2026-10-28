// Full-screen inspector: one adaptive flow that identifies the tag in the
// hand and shows everything the phone can read without keys. Reader mode is
// enabled only while the overlay is waiting for a tag, so re-scanning is
// explicit.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.InspectError
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.nfc.TagInspectState
import com.farmacia.pm3android.nfc.TagInspectViewModel
import com.farmacia.pm3android.nfc.inspect.DesfireVersion
import com.farmacia.pm3android.nfc.inspect.TagConfidence
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagInspectScreen(
    state: TagInspectState,
    viewModel: TagInspectViewModel,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current
    val session = remember(activity) { activity?.let { NfcSession(it) } }

    var nfcEnabled by remember { mutableStateOf(session?.isEnabled == true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nfcEnabled = session?.isEnabled == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(state.readerActive, session) {
        if (state.readerActive) {
            session?.start { tag -> viewModel.onTag(tag) }
        } else {
            session?.stop()
        }
        onDispose { session?.stop() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.inspect_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose, enabled = !state.busy) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.nfc_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.identity != null -> ResultContent(
                        state = state,
                        viewModel = viewModel,
                        onClose = onClose,
                    )

                    state.busy -> ReadingCard()
                    !nfcEnabled -> OffCard(
                        onOpenSettings = {
                            activity?.let { NfcSession.openSystemSettings(it) }
                        },
                    )

                    state.error != null -> ErrorState(
                        state = state,
                        viewModel = viewModel,
                        onClose = onClose,
                    )

                    else -> WaitingCard()
                }
            }
        }
    }
}

@Composable
private fun ResultContent(
    state: TagInspectState,
    viewModel: TagInspectViewModel,
    onClose: () -> Unit,
) {
    val identity = state.identity ?: return
    IdentityHero(identity)
    IdentityDetails(identity)
    if (identity.notes.isNotEmpty()) NotesCard(identity.notes)

    val desfire = state.desfire
    if (desfire != null) DesfireCard(desfire, state.applications)

    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.inspect_again),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.inspect_close))
    }
}

@Composable
private fun IdentityHero(identity: TagIdentity) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(30.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = identity.label,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = identity.uidHex,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = confidenceText(identity.confidence),
                color = confidenceColor(identity.confidence),
            )
        }
    }
}

@Composable
private fun IdentityDetails(identity: TagIdentity) {
    val bytes = identity.memoryBytes
    val memory = if (bytes != null) {
        stringResource(R.string.inspect_memory_bytes, bytes)
    } else {
        stringResource(R.string.inspect_memory_unknown)
    }
    val atqa = identity.atqa
    val sak = identity.sak
    val version = identity.version

    SectionCard(title = stringResource(R.string.inspect_identity)) {
        InfoRow(stringResource(R.string.inspect_uid), identity.uidHex)
        InfoRow(stringResource(R.string.inspect_uid_kind), uidKindText(identity.uidLength))
        InfoRow(
            stringResource(R.string.inspect_vendor),
            identity.vendor ?: stringResource(R.string.inspect_unknown),
        )
        if (atqa != null) {
            InfoRow(stringResource(R.string.inspect_atqa), "0x%04X".format(atqa))
        }
        if (sak != null) {
            InfoRow(stringResource(R.string.inspect_sak), "0x%02X".format(sak))
        }
        InfoRow(stringResource(R.string.inspect_memory), memory)
        if (version != null) {
            InfoRow(stringResource(R.string.inspect_version), version.versionText)
        }
        if (identity.techs.isNotEmpty()) {
            InfoRow(
                stringResource(R.string.inspect_tech),
                identity.techs.joinToString(" · ") { shortTech(it) },
            )
        }
    }
}

@Composable
private fun NotesCard(notes: List<TagNote>) {
    SectionCard(title = stringResource(R.string.inspect_notes)) {
        notes.forEach { note ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = noteText(note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DesfireCard(version: DesfireVersion, applications: List<Int>?) {
    val generation = if (version.generation.isEmpty()) {
        stringResource(R.string.inspect_unavailable_value)
    } else {
        version.generation
    }
    val storageBytes = version.storageBytes
    val storage = if (storageBytes != null) {
        stringResource(R.string.inspect_memory_bytes, storageBytes)
    } else {
        stringResource(R.string.inspect_memory_unknown)
    }
    val applicationsText = when {
        applications == null -> stringResource(R.string.inspect_unavailable_value)
        applications.isEmpty() -> stringResource(R.string.inspect_applications_none)
        else -> applications.joinToString(" · ") { "0x%06X".format(it) }
    }

    SectionCard(title = stringResource(R.string.inspect_desfire_title)) {
        InfoRow(stringResource(R.string.inspect_desfire_generation), generation)
        InfoRow(
            stringResource(R.string.inspect_desfire_software),
            "${version.softwareMajor}.${version.softwareMinor}",
        )
        InfoRow(stringResource(R.string.inspect_memory), storage)
        InfoRow(stringResource(R.string.inspect_applications), applicationsText)
    }
}

@Composable
private fun WaitingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(R.string.inspect_waiting_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.inspect_waiting_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.inspect_reading_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.inspect_reading_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorState(
    state: TagInspectState,
    viewModel: TagInspectViewModel,
    onClose: () -> Unit,
) {
    val message = if (state.error == InspectError.TAG_LOST) {
        stringResource(R.string.inspect_error_tag_lost)
    } else {
        stringResource(R.string.inspect_error_read)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = stringResource(R.string.inspect_error_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.inspect_retry))
    }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.inspect_close))
    }
}

@Composable
private fun OffCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.nfc_off_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.nfc_off_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nfc_off_settings))
            }
        }
    }
}

@Composable
private fun uidKindText(bytes: Int): String = when (bytes) {
    4 -> stringResource(R.string.inspect_uid_4)
    7 -> stringResource(R.string.inspect_uid_7)
    10 -> stringResource(R.string.inspect_uid_10)
    else -> stringResource(R.string.inspect_uid_other, bytes)
}

@Composable
private fun confidenceText(confidence: TagConfidence): String = stringResource(
    when (confidence) {
        TagConfidence.HIGH -> R.string.inspect_confidence_high
        TagConfidence.MEDIUM -> R.string.inspect_confidence_medium
        TagConfidence.LOW -> R.string.inspect_confidence_low
    },
)

@Composable
private fun confidenceColor(confidence: TagConfidence): Color = when (confidence) {
    TagConfidence.HIGH -> MaterialTheme.colorScheme.primary
    TagConfidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
    TagConfidence.LOW -> MaterialTheme.colorScheme.error
}

@Composable
private fun noteText(note: TagNote): String = stringResource(
    when (note) {
        TagNote.UID_7_BYTE -> R.string.inspect_note_uid7
        TagNote.UID_10_BYTE -> R.string.inspect_note_uid10
        TagNote.CLASSIC_WITHOUT_TECH -> R.string.inspect_note_classic_no_tech
        TagNote.VERSION_UNAVAILABLE -> R.string.inspect_note_version
        TagNote.DESFIRE_NO_DUMP -> R.string.inspect_note_desfire
        TagNote.PLUS_NEEDS_KEYS -> R.string.inspect_note_plus
        TagNote.VENDOR_MAYBE_CLONE -> R.string.inspect_note_clone
    },
)

private fun shortTech(tech: String): String = tech.substringAfterLast('.')
