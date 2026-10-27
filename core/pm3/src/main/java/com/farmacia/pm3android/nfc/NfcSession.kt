// Reader-mode wrapper. Reader mode keeps the NFC field up for as long as it is
// enabled, which is what allows a multi-second write to complete; it must stay
// enabled from the moment the tag is discovered until the last page is written.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings

class NfcSession(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private var enabled = false

    val available: Boolean get() = adapter != null

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun start(onTag: (Tag) -> Unit) {
        val adapter = adapter ?: return
        if (enabled || !adapter.isEnabled) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        try {
            adapter.enableReaderMode(activity, { tag -> onTag(tag) }, flags, extras)
            enabled = true
        } catch (_: RuntimeException) {
            enabled = false
        }
    }

    fun stop() {
        if (!enabled) return
        runCatching { adapter?.disableReaderMode(activity) }
        enabled = false
    }

    companion object {
        fun openSystemSettings(context: Context) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
        }
    }
}
