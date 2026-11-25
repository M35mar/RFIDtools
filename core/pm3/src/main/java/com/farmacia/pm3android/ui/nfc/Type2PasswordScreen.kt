// Full-screen overlay & ViewModel for NTAG21x / Ultralight EV1 Password &
// Protection management (Set / Remove password + UID-derived PWD/PACK presets).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import android.app.Application
import android.nfc.Tag
import android.nfc.TagLostException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.AndroidUltralightTransport
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.nfc.Type2PasswordKeygen
import com.farmacia.pm3android.nfc.Type2PasswordTool
import com.farmacia.pm3android.nfc.Type2PwdAction
import com.farmacia.pm3android.nfc.Type2PwdResult
import com.farmacia.pm3android.nfc.hexToBytesOrNull
import com.farmacia.pm3android.nfc.toHex
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

enum class Type2PwdStage { EDIT, WAITING, WORKING, DONE, ERROR }

data class Type2PwdState(
    val visible: Boolean = false,
    val stage: Type2PwdStage = Type2PwdStage.EDIT,
    val action: Type2PwdAction = Type2PwdAction.SET_PROTECTION,
    val currentPwdHex: String = "",
    val newPwdHex: String = "12345678",
    val newPackHex: String = "8080",
    val uidForKeygenHex: String = "",
    val protectReadAndWrite: Boolean = false,
    val startPageText: String = "4",
    val result: Type2PwdResult? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = stage == Type2PwdStage.WORKING
    val readerActive: Boolean get() = visible && (stage == Type2PwdStage.WAITING || stage == Type2PwdStage.WORKING)

    val valid: Boolean
        get() {
            val curOk = currentPwdHex.isEmpty() || currentPwdHex.hexToBytesOrNull()?.size == 4
            if (!curOk) return false
            if (action == Type2PwdAction.REMOVE_PROTECTION) return true
            val pwdOk = newPwdHex.hexToBytesOrNull()?.size == 4
            val packOk = newPackHex.hexToBytesOrNull()?.size == 2
            val pageOk = (startPageText.toIntOrNull() ?: -1) in 0..230
            return pwdOk && packOk && pageOk
        }
}

class Type2PasswordViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(Type2PwdState())
    val state: StateFlow<Type2PwdState> = stateFlow

    fun start(prefillUidHex: String = "") {
        stateFlow.value = Type2PwdState(visible = true, uidForKeygenHex = prefillUidHex)
    }

    fun close() {
        if (stateFlow.value.busy) return
        stateFlow.value = Type2PwdState()
    }

    fun setAction(action: Type2PwdAction) {
        stateFlow.value = stateFlow.value.copy(action = action, error = null)
    }

    fun setCurrentPwd(hex: String) {
        stateFlow.value = stateFlow.value.copy(
            currentPwdHex = hex.filter { it.digitToIntOrNull(16) != null }.take(8).uppercase(),
        )
    }

    fun setNewPwd(hex: String) {
        stateFlow.value = stateFlow.value.copy(
            newPwdHex = hex.filter { it.digitToIntOrNull(16) != null }.take(8).uppercase(),
        )
    }

    fun setNewPack(hex: String) {
        stateFlow.value = stateFlow.value.copy(
            newPackHex = hex.filter { it.digitToIntOrNull(16) != null }.take(4).uppercase(),
        )
    }

    fun setUidForKeygen(hex: String) {
        stateFlow.value = stateFlow.value.copy(
            uidForKeygenHex = hex.filter { it.digitToIntOrNull(16) != null }.take(14).uppercase(),
        )
    }

    fun applyAmiiboKeygen() {
        val uid = stateFlow.value.uidForKeygenHex.hexToBytesOrNull() ?: return
        val (pwd, pack) = Type2PasswordKeygen.deriveAmiiboStyle(uid) ?: return
        stateFlow.value = stateFlow.value.copy(
            newPwdHex = pwd.toHex(),
            newPackHex = pack.toHex(),
        )
    }

    fun applyCrc32Keygen() {
        val uid = stateFlow.value.uidForKeygenHex.hexToBytesOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val (pwd, pack) = Type2PasswordKeygen.deriveCrc32(uid)
        stateFlow.value = stateFlow.value.copy(
            newPwdHex = pwd.toHex(),
            newPackHex = pack.toHex(),
        )
    }

    fun setProtectReadAndWrite(enabled: Boolean) {
        stateFlow.value = stateFlow.value.copy(protectReadAndWrite = enabled)
    }

    fun setStartPage(text: String) {
        stateFlow.value = stateFlow.value.copy(startPageText = text.filter { it.isDigit() }.take(3))
    }

    fun arm() {
        if (!stateFlow.value.valid) return
        stateFlow.value = stateFlow.value.copy(stage = Type2PwdStage.WAITING, error = null, result = null)
    }

    fun backToEdit() {
        if (stateFlow.value.busy) return
        stateFlow.value = stateFlow.value.copy(stage = Type2PwdStage.EDIT, error = null)
    }

    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (current.stage != Type2PwdStage.WAITING) return
        val curPwd = current.currentPwdHex.takeIf { it.isNotEmpty() }?.hexToBytesOrNull()
        val newPwd = current.newPwdHex.hexToBytesOrNull() ?: ByteArray(4) { 0xFF.toByte() }
        val newPack = current.newPackHex.hexToBytesOrNull() ?: ByteArray(2)
        val startPage = current.startPageText.toIntOrNull() ?: 4

        stateFlow.value = current.copy(stage = Type2PwdStage.WORKING, error = null)
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val transport = AndroidUltralightTransport(tag)
                    try {
                        Type2PasswordTool(transport).apply(
                            uid = tag.id ?: ByteArray(0),
                            action = current.action,
                            currentPassword = curPwd,
                            newPassword = newPwd,
                            newPack = newPack,
                            protectReadAndWrite = current.protectReadAndWrite,
                            startPage = startPage,
                        )
                    } finally {
                        transport.close()
                    }
                }
                stateFlow.value = stateFlow.value.copy(stage = Type2PwdStage.DONE, result = res)
            } catch (t: CancellationException) {
                throw t
            } catch (_: TagLostException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = Type2PwdStage.ERROR,
                    error = getApplication<Application>().getString(R.string.uid_error_lost),
                )
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    stage = Type2PwdStage.ERROR,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Type2PasswordScreen(
    state: Type2PwdState,
    viewModel: Type2PasswordViewModel,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current
    val session = remember(activity) { activity?.let { NfcSession(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    var nfcEnabled by remember { mutableStateOf(session?.isEnabled == true) }
    val accents = MaterialTheme.pm3Accents
    val chrome = MaterialTheme.pm3Chrome

    BackHandler(enabled = !state.busy) {
        if (state.stage == Type2PwdStage.EDIT) onClose() else viewModel.backToEdit()
    }

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) nfcEnabled = session?.isEnabled == true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(state.readerActive, session) {
        if (state.readerActive) session?.start { tag -> viewModel.onTag(tag) }
        else session?.stop()
        onDispose { session?.stop() }
    }

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
                        title = { Text(stringResource(R.string.t2pwd_title)) },
                        navigationIcon = {
                            IconButton(onClick = onClose, enabled = !state.busy) {
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
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.stage) {
                    Type2PwdStage.EDIT -> {
                        SectionCard(
                            title = stringResource(R.string.t2pwd_mode_title),
                            icon = if (state.action == Type2PwdAction.SET_PROTECTION) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            accent = accents.lf,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = state.action == Type2PwdAction.SET_PROTECTION,
                                    onClick = { viewModel.setAction(Type2PwdAction.SET_PROTECTION) },
                                    label = { Text(stringResource(R.string.t2pwd_set)) },
                                )
                                FilterChip(
                                    selected = state.action == Type2PwdAction.REMOVE_PROTECTION,
                                    onClick = { viewModel.setAction(Type2PwdAction.REMOVE_PROTECTION) },
                                    label = { Text(stringResource(R.string.t2pwd_remove)) },
                                )
                            }

                            OutlinedTextField(
                                value = state.currentPwdHex,
                                onValueChange = viewModel::setCurrentPwd,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.t2pwd_current_label)) },
                                placeholder = { Text("FFFFFFFF (opzionale se aperto)") },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                singleLine = true,
                            )

                            if (state.action == Type2PwdAction.SET_PROTECTION) {
                                OutlinedTextField(
                                    value = state.newPwdHex,
                                    onValueChange = viewModel::setNewPwd,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.t2pwd_new_label)) },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    singleLine = true,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = state.newPackHex,
                                        onValueChange = viewModel::setNewPack,
                                        modifier = Modifier.weight(1f),
                                        label = { Text("PACK (4 hex)") },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = state.startPageText,
                                        onValueChange = viewModel::setStartPage,
                                        modifier = Modifier.weight(1f),
                                        label = { Text("AUTH0 page") },
                                        singleLine = true,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = state.protectReadAndWrite,
                                        onCheckedChange = viewModel::setProtectReadAndWrite,
                                    )
                                    Text(
                                        text = stringResource(R.string.t2pwd_prot_rw),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                HorizontalDivider()
                                Text(
                                    text = stringResource(R.string.t2pwd_keygen_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accents.info,
                                )
                                OutlinedTextField(
                                    value = state.uidForKeygenHex,
                                    onValueChange = viewModel::setUidForKeygen,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("UID (14 hex per calcolo PWD)") },
                                    placeholder = { Text("04112233445566") },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    singleLine = true,
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AssistChip(
                                        onClick = viewModel::applyAmiiboKeygen,
                                        enabled = state.uidForKeygenHex.length == 14,
                                        label = { Text("NTAG215 XOR") },
                                    )
                                    AssistChip(
                                        onClick = viewModel::applyCrc32Keygen,
                                        enabled = state.uidForKeygenHex.length >= 8,
                                        label = { Text("CRC32(UID)") },
                                    )
                                }
                            }

                            Button(
                                onClick = viewModel::arm,
                                enabled = state.valid && nfcEnabled,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accents.lf,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.t2pwd_arm))
                            }
                        }
                    }

                    Type2PwdStage.WAITING -> SectionCard(
                        title = stringResource(R.string.ndef_writer_waiting_title),
                        icon = Icons.Filled.Nfc,
                        accent = accents.usb,
                    ) {
                        Text(stringResource(R.string.t2pwd_waiting_body))
                        OutlinedButton(onClick = viewModel::backToEdit) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }

                    Type2PwdStage.WORKING -> SectionCard(
                        title = stringResource(R.string.ndef_writer_writing),
                        accent = accents.lf,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Text(stringResource(R.string.uid_working_body))
                        }
                    }

                    Type2PwdStage.DONE -> SectionCard(
                        title = stringResource(R.string.t2pwd_done_title),
                        icon = Icons.Filled.CheckCircle,
                        accent = accents.success,
                    ) {
                        state.result?.let { res ->
                            InfoRow("Tag", res.tagLabel)
                            InfoRow("UID", res.uidHex)
                            InfoRow("AUTH0", "0x%02X".format(res.auth0Page))
                            InfoRow(
                                stringResource(R.string.uid_row_verified),
                                stringResource(R.string.uid_verified_yes),
                            )
                        }
                        Button(onClick = viewModel::backToEdit, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_close))
                        }
                    }

                    Type2PwdStage.ERROR -> SectionCard(
                        title = stringResource(R.string.ndef_writer_error_title),
                        icon = Icons.Filled.ErrorOutline,
                        accent = accents.danger,
                    ) {
                        Text(state.error.orEmpty(), color = accents.danger)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::arm, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ndef_writer_retry))
                            }
                            OutlinedButton(onClick = viewModel::backToEdit, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ndef_writer_edit))
                            }
                        }
                    }
                }
            }
        }
    }
}
