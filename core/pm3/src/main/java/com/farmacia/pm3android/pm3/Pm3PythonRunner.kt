// Lazy entry point for the Chaquopy Python runtime. The runtime is not started
// at app launch: users who never run a .py script do not pay the startup and
// memory cost.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

object Pm3PythonRunner {

    /**
     * Executes [script] and returns everything it printed. Must run off the
     * main thread: Python calls back into the blocking native client.
     */
    @Synchronized
    fun run(context: Context, script: File, arguments: String): String {
        require(script.isFile) { "script not found: ${script.name}" }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        val module = Python.getInstance().getModule("pm3_runner")
        return module.callAttr("run_script", script.absolutePath, arguments).toString()
    }
}
