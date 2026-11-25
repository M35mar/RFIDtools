// Full-screen Dump Diff / Compare overlay (MIFARE Classic, Ultralight/NTAG,
// ISO 15693 across .bin, .nfc, .json, .eml, .mct).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.files

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.FileCategory
import com.farmacia.pm3android.data.Pm3File
import com.farmacia.pm3android.nfc.DumpDiff
import com.farmacia.pm3android.nfc.DumpDiffReport
import com.farmacia.pm3android.nfc.DumpDiffRow
import com.farmacia.pm3android.nfc.DumpLoader
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.theme.pm3Accents
import com.farmacia.pm3android.ui.theme.pm3Chrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DumpDiffState(
    val visible: Boolean = false,
    val leftFile: File? = null,
    val rightFile: File? = null,
    val report: DumpDiffReport? = null,
    val error: String? = null,
)

class DumpDiffViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(DumpDiffState())
    val state: StateFlow<DumpDiffState> = stateFlow

    fun open(initialLeft: File? = null) {
        stateFlow.value = DumpDiffState(visible = true, leftFile = initialLeft)
    }

    fun close() {
        stateFlow.value = DumpDiffState()
    }

    fun pickLeft(file: File) {
        val right = stateFlow.value.rightFile
        stateFlow.value = stateFlow.value.copy(leftFile = file, error = null)
        if (right != null) runCompare(file, right)
    }

    fun pickRight(file: File) {
        val left = stateFlow.value.leftFile
        stateFlow.value = stateFlow.value.copy(rightFile = file, error = null)
        if (left != null) runCompare(left, file)
    }

    private fun runCompare(left: File, right: File) {
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val imgA = DumpLoader.load(left)
                    val imgB = DumpLoader.load(right)
                    DumpDiff.compare(imgA, imgB)
                }
                stateFlow.value = stateFlow.value.copy(report = report, error = null)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    report = null,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DumpDiffScreen(
    state: DumpDiffState,
    files: List<Pm3File>,
    viewModel: DumpDiffViewModel,
    onClose: () -> Unit,
) {
    val accents = MaterialTheme.pm3Accents
    val chrome = MaterialTheme.pm3Chrome
    var pickingSide by remember { mutableStateOf<Int?>(null) }
    val dumps = remember(files) {
        files.filter {
            it.category == FileCategory.DUMP &&
                !it.file.name.lowercase().endsWith("-key.bin")
        }
    }

    BackHandler { onClose() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = chrome.topBarBg,
                            titleContentColor = Color(0xFFF8FAFC),
                            navigationIconContentColor = Color(0xFFF8FAFC),
                        ),
                        title = { Text(stringResource(R.string.diff_title)) },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.nfc_back),
                                )
                            }
                        },
                    )
                    HorizontalDivider(color = chrome.chromeDivider)
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionCard(
                    title = stringResource(R.string.diff_select_title),
                    icon = Icons.AutoMirrored.Filled.CompareArrows,
                    accent = accents.info,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pickingSide = 0 },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = state.leftFile?.name ?: stringResource(R.string.diff_pick_a),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { pickingSide = 1 },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = state.rightFile?.name ?: stringResource(R.string.diff_pick_b),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    state.error?.let { err ->
                        Text(err, color = accents.danger, style = MaterialTheme.typography.bodySmall)
                    }
                }

                val report = state.report
                if (report != null) {
                    SectionCard(
                        title = stringResource(R.string.diff_summary_title),
                        accent = if (report.identical) accents.success else accents.lf,
                    ) {
                        InfoRow(stringResource(R.string.nfc_tag_type), report.familyLabel)
                        InfoRow(
                            stringResource(R.string.diff_changed_count),
                            if (report.identical) {
                                stringResource(R.string.diff_identical)
                            } else {
                                "${report.changedRows.size} / ${report.totalRows}"
                            },
                        )
                    }

                    if (!report.identical) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(report.changedRows, key = { it.index }) { row ->
                                DiffRowCard(row)
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingSide != null) {
        AlertDialog(
            onDismissRequest = { pickingSide = null },
            title = {
                Text(
                    stringResource(
                        if (pickingSide == 0) R.string.diff_pick_a else R.string.diff_pick_b,
                    ),
                )
            },
            text = {
                if (dumps.isEmpty()) {
                    Text(stringResource(R.string.nfc_pick_empty))
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        dumps.forEach { entry ->
                            OutlinedButton(
                                onClick = {
                                    val side = pickingSide
                                    pickingSide = null
                                    if (side == 0) viewModel.pickLeft(entry.file)
                                    else viewModel.pickRight(entry.file)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = entry.file.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingSide = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun DiffRowCard(row: DumpDiffRow) {
    val accents = MaterialTheme.pm3Accents
    val title = if (row.sector != null) {
        "Sector ${row.sector} · Block ${row.index}"
    } else {
        "Index ${row.index}"
    }
    SectionCard(title = title, accent = accents.lf) {
        row.before?.let { before ->
            Text(
                text = highlightedHex("A: ", before, row.changedByteIndices, accents.danger),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        row.after?.let { after ->
            Text(
                text = highlightedHex("B: ", after, row.changedByteIndices, accents.success),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        row.semanticNotes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelMedium,
                color = accents.info,
            )
        }
    }
}

private fun highlightedHex(
    prefix: String,
    bytes: ByteArray,
    changed: Set<Int>,
    highlightColor: Color,
) = buildAnnotatedString {
    append(prefix)
    bytes.forEachIndexed { idx, b ->
        if (idx > 0) append(' ')
        val hex = "%02X".format(b.toInt() and 0xFF)
        if (idx in changed) {
            withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                append(hex)
            }
        } else {
            append(hex)
        }
    }
}
