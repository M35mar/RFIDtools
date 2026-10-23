// ViewModel exposing the client facade to the console screen; one exec at a
// time (busy flag) because the client handles a single command stream.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.pm3.Pm3Client
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class Pm3ViewModel(application: Application) : AndroidViewModel(application) {

    private val client = Pm3Client(application)

    val connectionState: StateFlow<Pm3ConnectionState> = client.state
    val connectLog: StateFlow<String> = client.connectLog

    /** Path to the USB device is displayed only after a connect attempt. */
    private val deviceNameFlow = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = deviceNameFlow

    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow

    /** Console text, one entry per line block (command or response chunk). */
    private val linesFlow = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = linesFlow

    var execJob: Job? = null
        private set

    fun connect() {
        submitWork {
            appendQuietly(client.connect())
        }
    }

    fun disconnect() {
        submitWork {
            client.disconnect()
        }
    }

    fun runCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isEmpty() || busyFlow.value) {
            return
        }
        linesFlow.value = linesFlow.value + "> $command"
        submitWork {
            appendQuietly(client.exec(command))
        }
    }

    fun clearConsole() {
        linesFlow.value = emptyList()
    }

    private fun submitWork(block: suspend () -> Unit) {
        if (busyFlow.value) {
            return
        }
        busyFlow.value = true
        execJob = viewModelScope.launch {
            try {
                block()
            } finally {
                busyFlow.value = false
            }
        }
    }

    /** Adds each non-empty line of a console output chunk. */
    private fun appendQuietly(text: String) {
        val toAdd = text.split('\n').filter { it.isNotBlank() }
        if (toAdd.isNotEmpty()) {
            linesFlow.value = linesFlow.value + toAdd
        }
    }
}
