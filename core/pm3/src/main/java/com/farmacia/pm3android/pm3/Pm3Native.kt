// Kotlin entry points matching the JNI functions in
// core/pm3/src/main/cpp/pm3_jni.c (registered with RegisterNatives).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

object Pm3Native {

    init {
        System.loadLibrary("pm3android")
    }

    /** Must run once, before the first open: fixes up $HOME for the client. */
    external fun nativeInit(home: String): Boolean

    /**
     * Connects and handshakes with the PM3; returns everything the client
     * printed (including failure hints). Blocking, call off the main thread.
     */
    external fun nativeOpen(port: String): String

    /** Closes the port and the client communication thread. */
    external fun nativeClose()

    external fun nativeIsOpen(): Boolean

    /**
     * Runs one client command and returns its captured console output.
     * Blocks until the command completes, so it must be called off the main
     * thread; calls are serialised on the native side.
     */
    external fun nativeExec(cmd: String): String

    /**
     * Returns the console output produced since the last call. Safe to call
     * from another thread while [nativeExec] is still running.
     */
    external fun nativeGrab(): String

    /** Asks the command in progress to stop (cooperative, best effort). */
    external fun nativeAbort()

    /**
     * True when the user asked to stop. The Python bridge polls this between
     * script statements; native code uses its own flag.
     */
    @Volatile
    var stopRequested: Boolean = false
}
