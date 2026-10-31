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

/**
 * Sink handed to the Python runner: each stdout/stderr write is forwarded to
 * the console while the script is still running.
 */
class Pm3StreamSink(private val emit: (String) -> Unit) {
    fun write(text: String) {
        // Stopping from a print() is enough to unwind a script stuck in a
        // loop that never calls back into the native client.
        check(!Pm3Native.stopRequested) { "stopped by user" }
        emit(text)
    }

    /** Bypasses the stop check, used to report the stop itself. */
    fun emitNow(text: String) {
        emit(text)
    }
}

object Pm3PythonRunner {

    /**
     * Executes [script] and returns the output not delivered through
     * [onChunk]. Must run off the main thread: Python calls back into the
     * blocking native client.
     */
    @Synchronized
    fun run(
        context: Context,
        script: File,
        arguments: String,
        onChunk: ((String) -> Unit)? = null,
    ): String {
        require(script.isFile) { "script not found: ${script.name}" }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        Pm3Native.stopRequested = false
        val module = Python.getInstance().getModule("pm3_runner")
        val sink = onChunk?.let { Pm3StreamSink(it) }
        return module.callAttr("run_script", script.absolutePath, arguments, sink).toString()
    }
}
