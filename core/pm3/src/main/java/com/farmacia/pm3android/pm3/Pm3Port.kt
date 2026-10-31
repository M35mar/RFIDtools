// Transport-neutral byte stream pair handed to LocalSocketBridge. USB CDC,
// Bluetooth Classic SPP and Bluetooth LE all implement the same contract, so
// the embedded client always talks to the same "socket:" transport.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import java.io.InputStream
import java.io.OutputStream

interface Pm3Port {

    /** Streams owned by this port; [close] closes them as well. */
    fun streams(): Pair<InputStream, OutputStream>

    /** Releases the link and unblocks any pending read/write. */
    fun close()
}
