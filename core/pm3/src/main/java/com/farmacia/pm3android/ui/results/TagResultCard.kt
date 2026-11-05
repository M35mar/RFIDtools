// Compact card shown above the console: the last command's UID/ATQA/SAK and
// other structured fields, with a re-read action and the raw output on demand.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmacia.pm3android.R
import com.farmacia.pm3android.ui.components.SectionCard

@Composable
fun TagResultCard(
    info: TagInfo,
    enabled: Boolean,
    onRerun: (String) -> Unit,
) {
    var showRaw by remember(info) { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.result_last)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(info.title, style = MaterialTheme.typography.titleMedium)
            if (info.fields.isEmpty()) {
                Text(
                    text = stringResource(R.string.result_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                info.fields.forEach { field ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${field.label}: ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(104.dp),
                        )
                        Text(
                            text = field.value,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onRerun(info.command) },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.action_reread))
                }
                OutlinedButton(onClick = { showRaw = !showRaw }) {
                    Text(
                        stringResource(
                            if (showRaw) R.string.result_hide_raw else R.string.result_show_raw,
                        ),
                    )
                }
            }
            if (showRaw) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = info.raw.take(8000),
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
