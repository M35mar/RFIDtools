// JNI bridge exposed to the embedded Chaquopy runtime. Python scripts call
// `pm3.pm3().console(...)`; the shim in src/main/python/pm3.py forwards those
// calls here.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

object Pm3PythonBridge {

    /**
     * Runs one command in the already-open native client. The native side
     * serialises calls, so this is safe from the Python worker thread.
     *
     * `capture` and `quiet` mirror the upstream SWIG API. The Android client
     * always captures and suppresses console echo; the returned text is what
     * the Python script consumes.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun console(command: String, capture: Boolean, quiet: Boolean): String {
        check(Pm3Native.nativeIsOpen()) { "PM3 is not connected" }
        // Cooperative stop: Python scripts call back here for every PM3
        // command, so a pending stop unwinds the script at the next one.
        check(!Pm3Native.stopRequested) { "stopped by user" }
        val output = Pm3Native.nativeExec(command)
        check(!Pm3Native.stopRequested) { "stopped by user" }
        return output
    }
}
