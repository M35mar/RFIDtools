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
import com.farmacia.pm3android.pm3.Pm3Resources
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class Pm3File(val file: File, val size: Long)

class Pm3ViewModel(application: Application) : AndroidViewModel(application) {

    private val client = Pm3Client(application)

    val connectionState: StateFlow<Pm3ConnectionState> = client.state
    val connectLog: StateFlow<String> = client.connectLog

    /** USB device presence (set by refreshDevice, see DeviceScreen). */
    private val deviceFlow = MutableStateFlow<Pm3DeviceInfo?>(null)
    val device: StateFlow<Pm3DeviceInfo?> = deviceFlow

    /** Files written by the client into $HOME (dumps, traces, logs). */
    private val filesFlow = MutableStateFlow<List<Pm3File>>(emptyList())
    val files: StateFlow<List<Pm3File>> = filesFlow

    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow

    /** Console text, one entry per line block (command or response chunk). */
    private val linesFlow = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = linesFlow

    var execJob: Job? = null
        private set

    fun refreshDevice() {
        val found = client.foundDevice()
        deviceFlow.value = found?.let { dev ->
            Pm3DeviceInfo(
                name = dev.deviceName.substringAfterLast('/'),
                manufacturer = dev.manufacturerName,
                product = dev.productName,
                hasPermission = client.hasPermission(dev),
            )
        }
    }

    fun refreshFiles() {
        val home = Pm3Resources.home(getApplication())
        filesFlow.value = home.walkTopDown()
            .filter { it.isFile }
            .map { Pm3File(it, it.length()) }
            .sortedBy { it.file.relativeTo(home).path }
            .toList()
    }

    fun requestPermission(device: Pm3DeviceInfo): Unit {
        // permissions are requested through connect(); device info screen
        // forwards to connect directly.
        connect()
    }

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

    fun deleteFile(pm3File: Pm3File) {
        pm3File.file.delete()
        refreshFiles()
    }

    data class Pm3DeviceInfo(
        val name: String,
        val manufacturer: String?,
        val product: String?,
        val hasPermission: Boolean,
    )

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
