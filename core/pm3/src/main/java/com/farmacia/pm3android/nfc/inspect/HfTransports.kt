// Transports for the HF technologies Android exposes besides ISO-DEP and
// NfcA. They are plain interfaces so every probe can be tested with a fake
// card, exactly like the existing DESFire and Calypso clients.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

/** ISO 15693 / NfcV raw frames, CRC added and checked by the controller. */
interface NfcVTransport {
    fun transceive(command: ByteArray): ByteArray
}

/** ISO 18092 passive / NfcF raw frames, CRC added by the controller. */
interface NfcFTransport {
    fun transceive(command: ByteArray): ByteArray
}

/** ISO 14443-3B raw frames, CRC added and checked by the controller. */
interface NfcBTransport {
    fun transceive(command: ByteArray): ByteArray
}
