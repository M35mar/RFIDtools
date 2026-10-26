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
        return Pm3Native.nativeExec(command)
    }
}
