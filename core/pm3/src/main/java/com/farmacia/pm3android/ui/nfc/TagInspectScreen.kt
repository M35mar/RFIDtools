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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.DesfireReadError
import com.farmacia.pm3android.nfc.DesfireReadStage
import com.farmacia.pm3android.nfc.DesfireReadState
import com.farmacia.pm3android.nfc.DumpError
import com.farmacia.pm3android.nfc.DumpStage
import com.farmacia.pm3android.nfc.DumpState
import com.farmacia.pm3android.nfc.InspectError
import com.farmacia.pm3android.nfc.MfuDumpFiles
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.nfc.PlusReadError
import com.farmacia.pm3android.nfc.PlusReadStage
import com.farmacia.pm3android.nfc.PlusReadState
import com.farmacia.pm3android.nfc.ReportStage
import com.farmacia.pm3android.nfc.ReportState
import com.farmacia.pm3android.nfc.toHex
import com.farmacia.pm3android.nfc.toHexSpaced
import com.farmacia.pm3android.nfc.TagInspectState
import com.farmacia.pm3android.nfc.TagInspectViewModel
import com.farmacia.pm3android.nfc.inspect.ApplicationDetails
import com.farmacia.pm3android.nfc.inspect.ApplicationKind
import com.farmacia.pm3android.nfc.inspect.CalypsoDetails
import com.farmacia.pm3android.nfc.inspect.CalypsoFileKind
import com.farmacia.pm3android.nfc.inspect.CalypsoFileParser
import com.farmacia.pm3android.nfc.inspect.CommandLog
import com.farmacia.pm3android.nfc.inspect.DesfireApplication
import com.farmacia.pm3android.nfc.inspect.DesfireDetails
import com.farmacia.pm3android.nfc.inspect.DesfireFile
import com.farmacia.pm3android.nfc.inspect.DesfireFileType
import com.farmacia.pm3android.nfc.inspect.DetailSection
import com.farmacia.pm3android.nfc.inspect.EmrtdDetails
import com.farmacia.pm3android.nfc.inspect.FelicaDetails
import com.farmacia.pm3android.nfc.inspect.GenericDetails
import com.farmacia.pm3android.nfc.inspect.Iso15693Details
import com.farmacia.pm3android.nfc.inspect.IsoDepDetails
import com.farmacia.pm3android.nfc.inspect.MemoryRow
import com.farmacia.pm3android.nfc.inspect.PageMemoryDetails
import com.farmacia.pm3android.nfc.inspect.PlusDetails
import com.farmacia.pm3android.nfc.inspect.PlusGeometry
import com.farmacia.pm3android.nfc.inspect.TagConfidence
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.nfc.inspect.content.CapabilityContainer
import com.farmacia.pm3android.nfc.inspect.content.ClassicContent
import com.farmacia.pm3android.nfc.inspect.content.ClassicSector
import com.farmacia.pm3android.nfc.inspect.content.MadDirectory
import com.farmacia.pm3android.nfc.inspect.content.MfuCounter
import com.farmacia.pm3android.nfc.inspect.content.NdefContent
import com.farmacia.pm3android.nfc.inspect.content.NdefKind
import com.farmacia.pm3android.nfc.inspect.content.NdefRecord
import com.farmacia.pm3android.nfc.inspect.content.NdefStatus
import com.farmacia.pm3android.nfc.inspect.content.SectorRole
import com.farmacia.pm3android.nfc.inspect.content.TagContent
import com.farmacia.pm3android.nfc.inspect.content.UltralightContent
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagInspectScreen(
    state: TagInspectState,
    viewModel: TagInspectViewModel,
    onClose: () -> Unit,
    onOpenDumpFlow: () -> Unit,
    onOpenCounterTool: () -> Unit,
    onOpenValueTool: () -> Unit,
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
                        IconButton(
                            onClick = onClose,
                            enabled = !state.busy && state.dump.stage != DumpStage.READING,
                        ) {
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
                        onOpenDumpFlow = onOpenDumpFlow,
                        onOpenCounterTool = onOpenCounterTool,
                        onOpenValueTool = onOpenValueTool,
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
    onOpenDumpFlow: () -> Unit,
    onOpenCounterTool: () -> Unit,
    onOpenValueTool: () -> Unit,
) {
    val identity = state.identity ?: return
    IdentityHero(identity)
    IdentityDetails(identity)
    if (identity.notes.isNotEmpty()) NotesCard(identity.notes)

    // The write tools come before the long memory dump: on a classic tag the
    // user should not have to scroll past 64 blocks to find them.
    val ev1 = identity.family == TagFamily.MIFARE_ULTRALIGHT &&
        identity.version?.storageCode in EV1_STORAGE
    if (identity.family == TagFamily.MIFARE_CLASSIC || ev1) {
        WriteToolsCard(
            ev1 = ev1,
            onOpenCounterTool = onOpenCounterTool,
            onOpenValueTool = onOpenValueTool,
        )
    }

    when (val details = state.details) {
        is DesfireDetails -> DesfireCard(details, state.desfireRead, viewModel)
        is PlusDetails -> PlusCard(details, state.plusRead, viewModel)
        is CalypsoDetails -> CalypsoCard(details)
        is Iso15693Details -> Iso15693Card(details)
        is FelicaDetails -> FelicaCard(details)
        is ApplicationDetails -> ApplicationCard(details)
        is EmrtdDetails -> EmrtdCard(details)
        is IsoDepDetails -> IsoDepCard(details)
        is GenericDetails -> GenericCard(details)
        // The page memory has its own table right below the content cards.
        is PageMemoryDetails -> Unit
        null -> Unit
    }

    if (state.commandLog.isNotEmpty()) CommandLogCard(state.commandLog)

    val content = state.content
    if (content != null) {
        ContentCards(content)
    } else if (identity.dumpable) {
        Text(
            text = stringResource(R.string.inspect_content_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val pageDetails = state.details as? PageMemoryDetails
    if (pageDetails != null) {
        MemoryCard(pageDetails.rows, rowSize = 4)
    } else if (state.dump.rows.isNotEmpty()) {
        MemoryCard(state.dump.rows, rowSize = state.dump.rowSize)
    }

    if (identity.dumpable) {
        DumpCard(state.dump, viewModel, onOpenDumpFlow)
        DumpRestoreNote(identity)
    } else {
        Text(
            text = stringResource(R.string.inspect_dump_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ReportCard(state.report, viewModel)

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

/** MF0UL11 and MF0UL21 storage codes from GET_VERSION. */
private val EV1_STORAGE = setOf(0x0B, 0x0E)

@Composable
private fun WriteToolsCard(
    ev1: Boolean,
    onOpenCounterTool: () -> Unit,
    onOpenValueTool: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.inspect_write_title)) {
        Text(
            text = stringResource(
                if (ev1) R.string.inspect_write_counter_desc else R.string.inspect_write_value_desc,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ev1) {
            Button(onClick = onOpenCounterTool, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.AddCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.inspect_write_counter),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            Button(onClick = onOpenValueTool, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.CreditCard,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.inspect_write_value),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.inspect_write_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun DesfireCard(
    details: DesfireDetails,
    read: DesfireReadState,
    viewModel: TagInspectViewModel,
) {
    var showDialog by remember { mutableStateOf(false) }
    val version = details.version
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

    SectionCard(title = stringResource(R.string.inspect_desfire_title)) {
        InfoRow(stringResource(R.string.inspect_desfire_generation), generation)
        InfoRow(
            stringResource(R.string.inspect_desfire_software),
            "${version.softwareMajor}.${version.softwareMinor}",
        )
        InfoRow(stringResource(R.string.inspect_memory), storage)
        details.picc?.freeMemory?.let { free ->
            InfoRow(
                stringResource(R.string.inspect_desfire_free),
                stringResource(R.string.inspect_memory_bytes, free),
            )
        }
        details.picc?.keySettings?.let { settings ->
            val keys = details.picc.keyCount?.toString()
                ?: stringResource(R.string.inspect_unavailable_value)
            InfoRow(
                stringResource(R.string.inspect_desfire_picc_keys),
                "0x%02X · %s".format(settings, stringResource(R.string.inspect_keys_count, keys)),
            )
        }
        val applications = details.applications
        val applicationsText = when {
            applications == null -> stringResource(R.string.inspect_unavailable_value)
            applications.isEmpty() -> stringResource(R.string.inspect_applications_none)
            else -> applications.size.toString()
        }
        InfoRow(stringResource(R.string.inspect_applications), applicationsText)
        applications.orEmpty().forEach { application ->
            DesfireApplicationRow(application)
        }
        DesfireReadControls(
            read = read,
            onStart = { showDialog = true },
            viewModel = viewModel,
        )
    }

    if (showDialog) {
        DesfireKeyDialog(
            onDismiss = { showDialog = false },
            onConfirm = { keyHex, keyNo ->
                val started = viewModel.startDesfireRead(keyHex, keyNo)
                if (started) showDialog = false
                started
            },
        )
    }
}

@Composable
private fun DesfireReadControls(
    read: DesfireReadState,
    onStart: () -> Unit,
    viewModel: TagInspectViewModel,
) {
    when (read.stage) {
        DesfireReadStage.NONE -> {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.inspect_desfire_read_button))
            }
        }

        DesfireReadStage.WAITING -> {
            Text(
                text = stringResource(R.string.inspect_desfire_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = viewModel::cancelDesfireRead,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.inspect_dump_cancel))
            }
        }

        DesfireReadStage.READING -> {
            Text(
                text = stringResource(R.string.inspect_desfire_reading),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        DesfireReadStage.DONE -> {
            Text(
                text = stringResource(R.string.inspect_desfire_done, read.filesRead),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.inspect_desfire_read_again))
            }
        }

        DesfireReadStage.FAILED -> {
            Text(
                text = desfireReadErrorText(read.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = viewModel::retryDesfireRead,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.inspect_retry))
            }
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.inspect_desfire_read_button))
            }
        }
    }
}

@Composable
private fun desfireReadErrorText(error: DesfireReadError?): String = stringResource(
    when (error) {
        DesfireReadError.TAG_LOST -> R.string.inspect_desfire_error_lost
        DesfireReadError.AUTH -> R.string.inspect_desfire_no_files
        DesfireReadError.READ, null -> R.string.inspect_desfire_error_read
    },
)

@Composable
private fun DesfireKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Boolean,
) {
    var keyHex by remember { mutableStateOf("") }
    var keyNo by remember { mutableStateOf("0") }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inspect_desfire_read_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyHex,
                    onValueChange = {
                        keyHex = it.trim()
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.inspect_desfire_key)) },
                    placeholder = { Text(stringResource(R.string.inspect_desfire_key_hint)) },
                    singleLine = true,
                    isError = invalid,
                )
                OutlinedTextField(
                    value = keyNo,
                    onValueChange = {
                        keyNo = it.filter { char -> char.isDigit() }.take(2)
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.inspect_desfire_key_no)) },
                    singleLine = true,
                    isError = invalid,
                )
                Text(
                    text = stringResource(R.string.inspect_desfire_key_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.inspect_desfire_key_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val started = onConfirm(keyHex, keyNo.toIntOrNull() ?: -1)
                    if (!started) invalid = true
                },
            ) {
                Text(stringResource(R.string.inspect_desfire_read_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.inspect_dump_cancel))
            }
        },
    )
}

@Composable
private fun DesfireApplicationRow(application: DesfireApplication) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.inspect_desfire_application, application.idHex),
            style = MaterialTheme.typography.labelMedium,
        )
        application.dfName?.let { name ->
            Text(
                text = stringResource(R.string.inspect_desfire_df_name, name.toHex()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        application.keySettings?.let { settings ->
            val keys = application.keyCount?.toString()
                ?: stringResource(R.string.inspect_unavailable_value)
            Text(
                text = stringResource(
                    R.string.inspect_desfire_application_keys,
                    "0x%02X".format(settings),
                    keys,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val files = application.files
        val filesText = when {
            files == null -> stringResource(R.string.inspect_unavailable_value)
            files.isEmpty() -> stringResource(R.string.inspect_files_none)
            else -> files.size.toString()
        }
        Text(
            text = stringResource(R.string.inspect_desfire_files, filesText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        files.orEmpty().forEach { file -> DesfireFileRow(file) }
        if (application.locked) {
            Text(
                text = stringResource(R.string.inspect_desfire_locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun DesfireFileRow(file: DesfireFile) {
    val geometry = when {
        file.sizeBytes != null -> stringResource(
            R.string.inspect_desfire_file_size,
            file.sizeBytes,
        )

        file.recordSize != null && file.recordCount != null -> stringResource(
            R.string.inspect_desfire_file_records,
            file.recordCount,
            file.recordSize,
        )

        else -> ""
    }
    val access = file.accessRights?.let { " · 0x%04X".format(it) }.orEmpty()
    Text(
        text = stringResource(
            R.string.inspect_desfire_file,
            file.id,
            fileTypeText(file.type),
        ) + geometry + access,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (file.communication == 3 && file.data == null) {
        Text(
            text = stringResource(R.string.inspect_desfire_enciphered),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    file.data?.let { data ->
        val clipped = if (data.size > 256) data.copyOf(256).toHex() + " …" else data.toHex()
        Text(
            text = clipped,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun fileTypeText(type: DesfireFileType): String = stringResource(
    when (type) {
        DesfireFileType.STANDARD_DATA -> R.string.inspect_desfire_type_standard
        DesfireFileType.BACKUP_DATA -> R.string.inspect_desfire_type_backup
        DesfireFileType.VALUE -> R.string.inspect_desfire_type_value
        DesfireFileType.LINEAR_RECORD -> R.string.inspect_desfire_type_linear
        DesfireFileType.CYCLIC_RECORD -> R.string.inspect_desfire_type_cyclic
        DesfireFileType.UNKNOWN -> R.string.inspect_desfire_type_unknown
    },
)

@Composable
private fun PlusCard(
    details: PlusDetails,
    read: PlusReadState,
    viewModel: TagInspectViewModel,
) {
    var showDialog by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.inspect_plus_title)) {
        InfoRow(
            stringResource(R.string.inspect_plus_product),
            details.version.productName,
        )
        InfoRow(
            stringResource(R.string.inspect_plus_version),
            details.version.versionText,
        )
        details.software?.let {
            InfoRow(stringResource(R.string.inspect_desfire_software), it.versionText)
        }
        details.securityLevel?.let {
            InfoRow(stringResource(R.string.inspect_plus_level), it.toString())
        }
        details.storageBytes?.let {
            InfoRow(
                stringResource(R.string.inspect_memory),
                stringResource(R.string.inspect_memory_bytes, it),
            )
        }
        details.sectorRead?.let { sector ->
            InfoRow(stringResource(R.string.inspect_plus_sector), sector.sector.toString())
            InfoRow(
                stringResource(R.string.inspect_plus_key_type),
                if (sector.keyB) "B" else "A",
            )
            sector.blocks.forEach { block ->
                Text(
                    text = stringResource(R.string.inspect_plus_block, block.number) +
                        "  " + block.data.toHexSpaced(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PlusReadControls(
            read = read,
            onStart = { showDialog = true },
            viewModel = viewModel,
            enabled = details.securityLevel == null || details.securityLevel >= 3,
        )
    }

    if (showDialog) {
        PlusKeyDialog(
            maxSector = PlusGeometry.sectorCount(details.storageBytes) - 1,
            onDismiss = { showDialog = false },
            onConfirm = { keyHex, sector, keyB ->
                val started = viewModel.startPlusRead(keyHex, sector, keyB)
                if (started) showDialog = false
                started
            },
        )
    }
}

@Composable
private fun PlusReadControls(
    read: PlusReadState,
    onStart: () -> Unit,
    viewModel: TagInspectViewModel,
    enabled: Boolean,
) {
    if (!enabled) return
    when (read.stage) {
        PlusReadStage.NONE -> {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.inspect_plus_read_button))
            }
        }

        PlusReadStage.WAITING -> {
            Text(
                text = stringResource(R.string.inspect_plus_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = viewModel::cancelPlusRead,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.inspect_dump_cancel))
            }
        }

        PlusReadStage.READING -> {
            Text(
                text = stringResource(R.string.inspect_plus_reading),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        PlusReadStage.DONE -> {
            Text(
                text = stringResource(R.string.inspect_plus_done, read.sector),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.inspect_plus_read_again))
            }
        }

        PlusReadStage.FAILED -> {
            Text(
                text = plusReadErrorText(read.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = viewModel::retryPlusRead,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.inspect_retry))
            }
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.inspect_plus_read_button))
            }
        }
    }
}

@Composable
private fun plusReadErrorText(error: PlusReadError?): String = stringResource(
    when (error) {
        PlusReadError.TAG_LOST -> R.string.inspect_plus_error_lost
        PlusReadError.AUTH -> R.string.inspect_plus_error_auth
        PlusReadError.READ, null -> R.string.inspect_plus_error_read
    },
)

@Composable
private fun PlusKeyDialog(
    maxSector: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Boolean) -> Boolean,
) {
    var keyHex by remember { mutableStateOf("") }
    var sectorText by remember { mutableStateOf("0") }
    var keyB by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inspect_plus_read_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyHex,
                    onValueChange = {
                        keyHex = it.trim()
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.inspect_desfire_key)) },
                    placeholder = { Text(stringResource(R.string.inspect_desfire_key_hint)) },
                    singleLine = true,
                    isError = invalid,
                )
                OutlinedTextField(
                    value = sectorText,
                    onValueChange = {
                        sectorText = it.filter { char -> char.isDigit() }.take(2)
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.inspect_plus_sector)) },
                    singleLine = true,
                    isError = invalid,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { keyB = false }) {
                        Text(if (keyB) "A" else "✓ A")
                    }
                    OutlinedButton(onClick = { keyB = true }) {
                        Text(if (keyB) "✓ B" else "B")
                    }
                }
                Text(
                    text = stringResource(R.string.inspect_plus_key_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.inspect_plus_key_invalid, maxSector),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val started = onConfirm(
                        keyHex,
                        sectorText.toIntOrNull() ?: -1,
                        keyB,
                    )
                    if (!started) invalid = true
                },
            ) {
                Text(stringResource(R.string.inspect_desfire_read_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.inspect_dump_cancel))
            }
        },
    )
}

@Composable
private fun CalypsoCard(details: CalypsoDetails) {
    SectionCard(title = stringResource(R.string.inspect_calypso_title)) {
        InfoRow(stringResource(R.string.inspect_calypso_df_name), details.dfName.toHex())
        details.label?.let {
            InfoRow(stringResource(R.string.inspect_calypso_application), it)
        }
        details.serial?.let {
            InfoRow(stringResource(R.string.inspect_calypso_serial), it.toHex())
        }
        InfoRow(
            stringResource(R.string.inspect_calypso_iso_b),
            yesNo(details.iso14443B),
        )
        details.cla?.let {
            InfoRow(stringResource(R.string.inspect_calypso_cla), "0x%02X".format(it))
        }
        details.startup?.let { startup ->
            InfoRow(
                stringResource(R.string.inspect_calypso_platform),
                "0x%02X".format(startup.platform),
            )
            InfoRow(
                stringResource(R.string.inspect_calypso_application_type),
                "0x%02X / 0x%02X".format(startup.applicationType, startup.applicationSubtype),
            )
            InfoRow(
                stringResource(R.string.inspect_calypso_software),
                "%d.%d.%d".format(
                    startup.softwareIssuer,
                    startup.softwareVersion,
                    startup.softwareRevision,
                ),
            )
        }
        details.icc?.let { icc ->
            icc.serial?.let {
                InfoRow(stringResource(R.string.inspect_calypso_icc_serial), it.toHex())
            }
            icc.countryCode?.let {
                InfoRow(stringResource(R.string.inspect_calypso_country), it.toString())
            }
            icc.manufacturer?.let {
                InfoRow(stringResource(R.string.inspect_calypso_manufacturer), "0x%02X".format(it))
            }
            icc.initializationDate?.let {
                InfoRow(stringResource(R.string.inspect_calypso_initialized), it.toString())
            }
            icc.check?.let {
                val state = when (icc.checkValid) {
                    true -> stringResource(R.string.inspect_calypso_check_ok)
                    false -> stringResource(R.string.inspect_calypso_check_bad)
                    null -> stringResource(R.string.inspect_unknown)
                }
                InfoRow(stringResource(R.string.inspect_calypso_check), "0x%02X · %s".format(it, state))
            }
        }
        details.environment?.let { environment ->
            InfoRow(
                stringResource(R.string.inspect_calypso_environment_version),
                environment.version?.toString()
                    ?: stringResource(R.string.inspect_unavailable_value),
            )
            InfoRow(
                stringResource(R.string.inspect_calypso_environment_network),
                environment.networkId?.let { "0x%06X".format(it) }
                    ?: stringResource(R.string.inspect_unavailable_value),
            )
            Text(
                text = environment.raw.toHexSpaced(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (details.locked) {
            Text(
                text = stringResource(R.string.inspect_calypso_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        details.files.forEach { file ->
            Text(
                text = calypsoFileTitle(file.kind),
                style = MaterialTheme.typography.labelMedium,
            )
            if (file.kind == CalypsoFileKind.COUNTERS) {
                CalypsoFileParser.counterValues(file.records.first())
                    .forEachIndexed { index, value ->
                        InfoRow(
                            stringResource(R.string.inspect_calypso_counter, index + 1),
                            value.toString(),
                        )
                    }
            } else {
                file.records.forEachIndexed { index, record ->
                    InfoRow(
                        stringResource(R.string.inspect_calypso_record, index + 1),
                        record.toHexSpaced(),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.inspect_calypso_session_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun calypsoFileTitle(kind: CalypsoFileKind): String = stringResource(
    when (kind) {
        CalypsoFileKind.EVENTS_LOG -> R.string.inspect_calypso_file_events
        CalypsoFileKind.CONTRACTS -> R.string.inspect_calypso_file_contracts
        CalypsoFileKind.COUNTERS -> R.string.inspect_calypso_file_counters
        CalypsoFileKind.SPECIAL_EVENTS -> R.string.inspect_calypso_file_special
        CalypsoFileKind.CONTRACT_LIST -> R.string.inspect_calypso_file_contract_list
    },
)

@Composable
private fun Iso15693Card(details: Iso15693Details) {
    SectionCard(title = stringResource(R.string.inspect_iso15693_title)) {
        InfoRow(stringResource(R.string.inspect_iso15693_uid), details.uid.toHex())
        details.dsfid?.let {
            InfoRow(stringResource(R.string.inspect_iso15693_dsfid), "0x%02X".format(it))
        }
        details.afi?.let {
            InfoRow(stringResource(R.string.inspect_iso15693_afi), "0x%02X".format(it))
        }
        details.blockSize?.let {
            InfoRow(stringResource(R.string.inspect_iso15693_block_size), "$it")
        }
        details.firstBlock?.let {
            InfoRow(stringResource(R.string.inspect_iso15693_block0), it.toHexSpaced())
        }
    }
}

@Composable
private fun FelicaCard(details: FelicaDetails) {
    SectionCard(title = stringResource(R.string.inspect_felica_title)) {
        InfoRow(stringResource(R.string.inspect_felica_idm), details.idm.toHex())
        details.pmm?.let {
            InfoRow(stringResource(R.string.inspect_felica_pmm), it.toHex())
        }
        details.manufacturer?.let {
            InfoRow(stringResource(R.string.inspect_felica_manufacturer), "0x%04X".format(it))
        }
        details.systemCode?.let {
            InfoRow(stringResource(R.string.inspect_felica_system), "0x%04X".format(it))
        }
    }
}

@Composable
private fun ApplicationCard(details: ApplicationDetails) {
    SectionCard(title = stringResource(R.string.inspect_application_title)) {
        InfoRow(stringResource(R.string.inspect_application_aid), details.aidHex)
        details.label?.let {
            InfoRow(stringResource(R.string.inspect_calypso_application), it)
        }
        InfoRow(stringResource(R.string.inspect_application_kind), applicationKindText(details.kind))
        details.provider?.let {
            InfoRow(stringResource(R.string.inspect_vendor), it)
        }
    }
}

@Composable
private fun EmrtdCard(details: EmrtdDetails) {
    SectionCard(title = stringResource(R.string.inspect_emrtd_title)) {
        InfoRow(stringResource(R.string.inspect_emrtd_aid), details.aidHex)
        details.label?.let {
            InfoRow(stringResource(R.string.inspect_calypso_application), it)
        }
        InfoRow(stringResource(R.string.inspect_emrtd_pace), yesNo(details.paceSupported))
        InfoRow(stringResource(R.string.inspect_emrtd_bac), yesNo(details.bacSupported))
        details.cardAccess?.let {
            InfoRow(stringResource(R.string.inspect_emrtd_card_access), it.toHexSpaced())
        }
    }
}

@Composable
private fun IsoDepCard(details: IsoDepDetails) {
    SectionCard(title = stringResource(R.string.inspect_isodep_title)) {
        details.ats?.let {
            InfoRow(stringResource(R.string.inspect_isodep_ats), it.toHexSpaced())
        }
        details.historicalBytes?.let {
            InfoRow(stringResource(R.string.inspect_isodep_historical), it.toHexSpaced())
        }
        details.applications.forEach { application ->
            InfoRow(
                stringResource(R.string.inspect_application_aid),
                application.aidHex + (application.label?.let { label -> " · $label" } ?: ""),
            )
        }
    }
}

@Composable
private fun GenericCard(details: GenericDetails) {
    SectionCard(title = details.summary ?: stringResource(R.string.inspect_generic_title)) {
        details.sections.forEach { section ->
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            section.rows.forEach { row -> InfoRow(row.label, row.value) }
            section.table?.let { table ->
                table.rows.forEach { cells -> InfoRow(cells.firstOrNull().orEmpty(), cells.drop(1).joinToString(" · ")) }
            }
        }
    }
}

@Composable
private fun CommandLogCard(entries: List<CommandLog.Entry>) {
    SectionCard(title = stringResource(R.string.inspect_log_title)) {
        entries.takeLast(LOG_ROWS).forEach { entry ->
            Text(
                text = "→ " + entry.command.toHexSpaced(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "← " + (entry.response?.toHexSpaced() ?: entry.error ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val LOG_ROWS = 12

@Composable
private fun applicationKindText(kind: ApplicationKind): String = when (kind) {
    ApplicationKind.EMV -> "EMV"
    ApplicationKind.CIPURSE -> "CIPURSE"
    ApplicationKind.PIV -> "PIV"
    ApplicationKind.NDEF -> "NDEF"
    ApplicationKind.ACCESS -> stringResource(R.string.inspect_kind_access)
    ApplicationKind.OTHER -> stringResource(R.string.inspect_kind_other)
}

@Composable
private fun ContentCards(content: TagContent) {
    val classic = content.classic
    if (classic != null) {
        classic.mad?.let { mad -> MadCard(mad) }
        NdefCard(classic.ndef)
        SectorTableCard(classic)
        return
    }
    val ultralight = content.ultralight
    if (ultralight != null) {
        Type2Card(ultralight)
        ultralight.cc?.let { cc -> CapabilityCard(cc) }
        NdefCard(ultralight.ndef)
        CountersCard(ultralight.counters)
        return
    }
    // Type 3, 4 and 5 tags only carry the message read over their own transport.
    if (content.ndef.status != NdefStatus.ABSENT) NdefCard(content.ndef)
}

@Composable
private fun MadCard(mad: MadDirectory) {
    SectionCard(title = stringResource(R.string.inspect_mad_title)) {
        InfoRow(stringResource(R.string.inspect_mad_version), mad.version.toString())
        InfoRow(stringResource(R.string.inspect_mad_publisher), mad.publisherSector.toString())
        InfoRow(stringResource(R.string.inspect_mad_multi), yesNo(mad.multiApplication))
        InfoRow(stringResource(R.string.inspect_mad_directory), yesNo(mad.directoryAvailable))
        Text(
            text = stringResource(
                if (mad.crcOk) R.string.inspect_mad_crc_ok else R.string.inspect_mad_crc_bad,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (mad.crcOk) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (mad.assigned.isEmpty()) {
            Text(
                text = stringResource(R.string.inspect_mad_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            mad.assigned.forEach { entry ->
                val name = entry.name?.let { " $it" }.orEmpty()
                InfoRow(
                    stringResource(R.string.inspect_mad_sector, entry.sector),
                    "${entry.aidHex}$name",
                )
            }
        }
    }
}

@Composable
private fun NdefCard(ndef: NdefContent) {
    SectionCard(title = stringResource(R.string.inspect_ndef_title)) {
        when (ndef.status) {
            NdefStatus.ABSENT -> Text(
                text = stringResource(R.string.inspect_ndef_absent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            NdefStatus.TRUNCATED -> Text(
                text = stringResource(R.string.inspect_ndef_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            NdefStatus.INVALID -> Text(
                text = stringResource(R.string.inspect_ndef_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            NdefStatus.FOUND -> {
                Text(
                    text = stringResource(
                        R.string.inspect_ndef_summary,
                        ndef.records.size,
                        ndef.bytes,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ndef.records.forEach { record -> NdefRecordRow(record) }
            }
        }
    }
}

@Composable
private fun NdefRecordRow(record: NdefRecord) {
    val body = record.text
        ?: record.type.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.inspect_empty_value)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = stringResource(
                R.string.inspect_ndef_record,
                ndefKindText(record.kind),
                record.payloadBytes,
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SectorTableCard(content: ClassicContent) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) content.sectors else content.sectors.take(SECTOR_PREVIEW)
    SectionCard(title = stringResource(R.string.inspect_sectors_title)) {
        Text(
            text = stringResource(
                R.string.inspect_sectors_summary,
                content.readableSectors,
                content.totalSectors,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        visible.forEach { sector -> SectorRow(sector) }
        if (content.sectors.size > SECTOR_PREVIEW) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.inspect_sectors_less)
                    } else {
                        stringResource(R.string.inspect_sectors_all, content.sectors.size)
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.inspect_access_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectorRow(sector: ClassicSector) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.inspect_sector_line,
                    sector.sector,
                    sector.blocks,
                    roleText(sector.role),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (!sector.readable) {
                Text(
                    text = stringResource(R.string.inspect_sector_unreadable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val access = sector.access
        if (access != null) {
            Text(
                text = access.data.joinToString("   ") { it.compact() },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = access.trailerCompact(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!sector.accessConsistent) {
                Text(
                    text = stringResource(R.string.inspect_access_inconsistent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (sector.valueBlock) {
            Text(
                text = stringResource(R.string.inspect_sector_value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        sector.firstDataHex?.let { hex ->
            Text(
                text = hex.take(16).chunked(2).joinToString(" ") + " …",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Type2Card(content: UltralightContent) {
    SectionCard(title = stringResource(R.string.inspect_type2_title)) {
        InfoRow(stringResource(R.string.inspect_type2_pages), content.pages.toString())
        if (content.cc == null) {
            Text(
                text = stringResource(R.string.inspect_type2_cc_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapabilityCard(cc: CapabilityContainer) {
    SectionCard(title = stringResource(R.string.inspect_type2_cc)) {
        InfoRow(
            stringResource(R.string.inspect_type2_cc_version),
            "${cc.versionMajor}.${cc.versionMinor}",
        )
        InfoRow(
            stringResource(R.string.inspect_type2_cc_capacity),
            stringResource(R.string.inspect_memory_bytes, cc.capacityBytes),
        )
        InfoRow(stringResource(R.string.inspect_type2_cc_read), "0x%X".format(cc.readAccess))
        InfoRow(stringResource(R.string.inspect_type2_cc_write), "0x%X".format(cc.writeAccess))
    }
}

@Composable
private fun CountersCard(counters: List<MfuCounter>) {
    SectionCard(title = stringResource(R.string.inspect_counters_title)) {
        counters.forEachIndexed { index, counter ->
            val value = counter.value?.toString()
                ?: stringResource(R.string.inspect_counter_none)
            val suffix = if (counter.tearing) {
                " · " + stringResource(R.string.inspect_counter_tearing)
            } else {
                ""
            }
            InfoRow(
                stringResource(R.string.inspect_counter_line, index + 1),
                value + suffix,
            )
        }
    }
}

@Composable
private fun ReportCard(report: ReportState, viewModel: TagInspectViewModel) {
    SectionCard(title = stringResource(R.string.inspect_report_title)) {
        when (report.stage) {
            ReportStage.IDLE, ReportStage.FAILED -> {
                if (report.stage == ReportStage.FAILED) {
                    Text(
                        text = stringResource(R.string.inspect_report_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.inspect_report_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = viewModel::saveReport, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.inspect_report_save),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            ReportStage.SAVED -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.inspect_report_saved),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                report.savedName?.let {
                    InfoRow(stringResource(R.string.inspect_report_name), it)
                }
                Text(
                    text = stringResource(R.string.inspect_dump_saved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SECTOR_PREVIEW = 8

@Composable
private fun roleText(role: SectorRole): String = stringResource(
    when (role) {
        SectorRole.MANUFACTURER -> R.string.inspect_role_manufacturer
        SectorRole.MAD1 -> R.string.inspect_role_mad1
        SectorRole.MAD2 -> R.string.inspect_role_mad2
        SectorRole.NDEF -> R.string.inspect_role_ndef
        SectorRole.DATA -> R.string.inspect_role_data
    },
)

@Composable
private fun ndefKindText(kind: NdefKind): String = stringResource(
    when (kind) {
        NdefKind.TEXT -> R.string.inspect_ndef_kind_text
        NdefKind.URI -> R.string.inspect_ndef_kind_uri
        NdefKind.MIME -> R.string.inspect_ndef_kind_mime
        NdefKind.EXTERNAL -> R.string.inspect_ndef_kind_external
        NdefKind.SMART_POSTER -> R.string.inspect_ndef_kind_poster
        NdefKind.EMPTY -> R.string.inspect_ndef_kind_empty
        NdefKind.UNKNOWN -> R.string.inspect_ndef_kind_unknown
    },
)

@Composable
private fun yesNo(value: Boolean): String = stringResource(
    if (value) R.string.inspect_yes else R.string.inspect_no,
)

/** Short restore caveat for the tag family, empty when there is nothing to say. */
@Composable
private fun DumpRestoreNote(identity: TagIdentity) {
    val note = when (identity.family) {
        TagFamily.MIFARE_ULTRALIGHT -> R.string.inspect_dump_note_ultralight
        TagFamily.NTAG -> R.string.inspect_dump_note_ntag
        TagFamily.MIFARE_CLASSIC -> R.string.inspect_dump_note_classic
        TagFamily.MY_D -> R.string.inspect_dump_note_myd
        else -> null
    } ?: return
    Text(
        text = stringResource(note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DumpCard(
    dump: DumpState,
    viewModel: TagInspectViewModel,
    onOpenDumpFlow: () -> Unit,
) {
    var showSave by remember(dump.suggestedName, dump.stage) { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.inspect_dump_title)) {
        when (dump.stage) {
            DumpStage.NONE -> {
                Text(
                    text = stringResource(R.string.inspect_dump_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DumpStage.WAITING -> {
                Text(
                    text = stringResource(R.string.inspect_dump_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = viewModel::cancelDump,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.inspect_dump_cancel))
                }
            }

            DumpStage.READING -> {
                Text(
                    text = stringResource(
                        R.string.inspect_dump_progress,
                        dump.progressDone,
                        dump.progressTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { fraction(dump.progressDone, dump.progressTotal) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            DumpStage.DONE -> {
                Text(
                    text = stringResource(
                        R.string.inspect_dump_read_done,
                        dump.rows.size,
                        dump.rowSize,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                dump.error?.let { error ->
                    Text(
                        text = dumpErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.inspect_dump_not_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { showSave = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.inspect_dump_save),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            DumpStage.SAVED -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.inspect_dump_saved),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                dump.savedName?.let {
                    InfoRow(stringResource(R.string.inspect_dump_saved_name), it)
                }
                dump.savedKeyName?.let {
                    InfoRow(stringResource(R.string.inspect_dump_saved_key), it)
                }
                Text(
                    text = stringResource(R.string.inspect_dump_saved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DumpStage.INCOMPLETE -> {
                Text(
                    text = stringResource(
                        R.string.inspect_dump_incomplete,
                        dump.sectorsMissing,
                        dump.blocksMissing,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenDumpFlow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.inspect_dump_open_full))
                }
                OutlinedButton(onClick = viewModel::retryDump, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.inspect_retry))
                }
            }

            DumpStage.FAILED -> {
                Text(
                    text = dumpErrorText(dump.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onOpenDumpFlow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.inspect_dump_open_full))
                }
                OutlinedButton(onClick = viewModel::retryDump, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.inspect_retry))
                }
            }
        }
    }
    if (showSave) {
        SaveDumpDialog(
            initialName = dump.suggestedName,
            onDismiss = { showSave = false },
            onSave = { name ->
                showSave = false
                viewModel.saveDump(name)
            },
        )
    }
}

/** Name dialog for the explicit save: `.bin` is added, nothing is overwritten. */
@Composable
private fun SaveDumpDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val valid = MfuDumpFiles.fileName(name) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inspect_dump_name_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.inspect_dump_name_label)) },
                singleLine = true,
                isError = !valid,
                supportingText = {
                    Text(
                        stringResource(
                            if (valid) {
                                R.string.inspect_dump_name_help
                            } else {
                                R.string.inspect_dump_name_invalid
                            },
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = valid) {
                Text(stringResource(R.string.inspect_dump_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.inspect_dump_cancel))
            }
        },
    )
}

private const val MEMORY_PREVIEW = 16

/** Hex table of the memory that was just read: page number, bytes and text. */
@Composable
private fun MemoryCard(rows: List<MemoryRow>, rowSize: Int) {
    var expanded by remember(rows.size, rows.firstOrNull()?.index) { mutableStateOf(false) }
    val visible = if (expanded) rows else rows.take(MEMORY_PREVIEW)
    SectionCard(title = stringResource(R.string.inspect_memory_title)) {
        Text(
            text = stringResource(
                R.string.inspect_memory_summary,
                rows.size,
                rowSize,
                rows.size * rowSize,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        visible.forEach { row ->
            Text(
                text = "%03X  %s  %s".format(row.index, row.hex, row.ascii),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (rows.size > MEMORY_PREVIEW) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.inspect_sectors_less)
                    } else {
                        stringResource(R.string.inspect_memory_show_all, rows.size)
                    },
                )
            }
        }
    }
}

private fun fraction(done: Int, total: Int): Float =
    if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

@Composable
private fun dumpErrorText(error: DumpError?): String = stringResource(
    when (error) {
        DumpError.TAG_LOST -> R.string.inspect_dump_error_lost
        DumpError.KEYS -> R.string.inspect_dump_error_keys
        DumpError.PASSWORD -> R.string.inspect_dump_error_password
        DumpError.INCOMPLETE, DumpError.READ, null -> R.string.inspect_dump_error_read
    },
)

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
        TagNote.PLUS_SL2_UNSUPPORTED -> R.string.inspect_note_plus_sl2
        TagNote.VENDOR_MAYBE_CLONE -> R.string.inspect_note_clone
        TagNote.CALYPSO_KEYS -> R.string.inspect_note_calypso
        TagNote.ISO_B_WEAK -> R.string.inspect_note_iso_b
        TagNote.DESFIRE_LOCKED -> R.string.inspect_note_desfire_locked
        TagNote.EMRTD_NEEDS_MRZ -> R.string.inspect_note_emrtd_mrz
        TagNote.EMRTD_PACE -> R.string.inspect_note_emrtd_pace
        TagNote.EMRTD_BIOMETRIC -> R.string.inspect_note_emrtd_biometric
        TagNote.EID_PROPRIETARY -> R.string.inspect_note_eid
        TagNote.SLE66R_PROPRIETARY -> R.string.inspect_note_sle66r
        TagNote.MY_D_VARIANT_INFERRED -> R.string.inspect_note_my_d_variant
        TagNote.SMARTMX_NO_MEMORY -> R.string.inspect_note_smartmx
        TagNote.ISO_15693_PASSWORD -> R.string.inspect_note_iso15693_password
        TagNote.ISO15693_NO_SYSTEM_INFO -> R.string.inspect_note_iso15693_info
        TagNote.ST25_VARIANT -> R.string.inspect_note_st25
        TagNote.FELICA_NO_P2P -> R.string.inspect_note_felica_p2p
        TagNote.NDEF_READ_FAILED -> R.string.inspect_note_ndef_failed
        TagNote.APDU_ONLY -> R.string.inspect_note_apdu_only
    },
)

private fun shortTech(tech: String): String = tech.substringAfterLast('.')
