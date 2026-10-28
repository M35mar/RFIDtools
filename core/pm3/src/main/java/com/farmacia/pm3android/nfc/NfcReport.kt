// Writes the tag report next to the dumps as `<base>-report.txt`. The text is
// built by TagReport; this file only touches the filesystem, so the report
// format stays testable without Android.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.content.Context
import com.farmacia.pm3android.nfc.inspect.content.TagReport
import com.farmacia.pm3android.pm3.Pm3Resources
import java.io.File

object NfcReport {

    /** First free `<base>-report.txt` in the app home; never overwrites. */
    fun save(context: Context, baseName: String, text: String): File {
        val dir = Pm3Resources.home(context)
        dir.mkdirs()
        val file = unique(dir, TagReport.sanitize(baseName))
        file.writeText(text)
        return file
    }

    private fun unique(dir: File, baseName: String): File {
        val stem = "$baseName-report"
        var index = 0
        while (true) {
            val name = if (index == 0) "$stem.txt" else "$stem-$index.txt"
            val candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
