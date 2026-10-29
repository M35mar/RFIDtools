// Serialized access to the technologies of one discovered tag. Android allows
// only one technology connection at a time, so every helper opens, runs and
// closes its transport before the next one starts. The implementation lives
// in the Android layer; the probes only see these interfaces.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

interface TagSession {

    fun <T> withIsoDep(block: (IsoDepTransport) -> T): T?

    fun <T> withNfcA(block: (RawTagTransport) -> T): T?

    fun <T> withNfcV(block: (NfcVTransport) -> T): T?

    fun <T> withNfcF(block: (NfcFTransport) -> T): T?

    fun <T> withNfcB(block: (NfcBTransport) -> T): T?
}
