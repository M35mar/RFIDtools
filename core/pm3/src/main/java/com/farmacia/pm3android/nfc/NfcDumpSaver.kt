// Dump writing shared by the "Leggi e salva dump" flow and the inspector.
// Both capture with the same engines, so the file layout, the key file pairing
// and the never-overwrite rule live here once.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.content.Context
import com.farmacia.pm3android.pm3.Pm3Resources
import java.io.File

/** The files a dump produced: the `.bin` and, for Classic, the key file. */
class SavedDump(val dump: File, val keyFile: File?)

object NfcDumpSaver {

    /**
     * Writes a complete capture to the app home folder. The name must already
     * be valid, as returned by [MfuDumpFiles.fileName]; a collision gets the
     * usual `-1`, `-2` suffix. The key file is written only when every Key B
     * was proven, so a restore never writes zeroes over the card.
     */
    fun save(
        context: Context,
        name: String,
        classic: MfcCapture? = null,
        ultralight: MfuCapture? = null,
        saveKeys: Boolean = true,
    ): SavedDump {
        require(classic != null || ultralight != null) { "no capture to save" }
        val dir = Pm3Resources.home(context)
        dir.mkdirs()
        if (classic != null) {
            val dump = MfuDumpFiles.uniqueDump(dir, name)
            dump.writeBytes(classic.toBin())
            var keyFile: File? = null
            if (saveKeys) {
                val keys = classic.keysToBin()
                if (keys != null) {
                    keyFile = MfuDumpFiles.keyFileFor(dump)
                    keyFile.writeBytes(keys)
                }
            }
            return SavedDump(dump, keyFile)
        }
        val dump = MfuDumpFiles.unique(dir, name)
        dump.writeBytes(MfuDumpWriter.toBin(ultralight!!))
        return SavedDump(dump, null)
    }
}
