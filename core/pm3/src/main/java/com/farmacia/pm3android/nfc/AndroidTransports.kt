// Thin adapters between the Android NFC stack and the two restore engines.
// They are the only Android-dependent part of the NFC feature: everything else
// is plain Kotlin and unit-testable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import java.io.IOException

class AndroidClassicTransport(tag: Tag) : ClassicTransport {

    private val tech: MifareClassic = MifareClassic.get(tag)
        ?: throw TagUnusableException("il tag non è un MIFARE Classic")

    init {
        try {
            tech.connect()
        } catch (t: Exception) {
            throw TagUnusableException("connessione al tag non riuscita", t)
        }
    }

    override val sectorCount: Int get() = tech.sectorCount

    override fun blockCount(sector: Int): Int = tech.getBlockCountInSector(sector)

    override fun authenticate(sector: Int, key: ByteArray, keyA: Boolean): Boolean = try {
        if (keyA) {
            tech.authenticateSectorWithKeyA(sector, key)
        } else {
            tech.authenticateSectorWithKeyB(sector, key)
        }
    } catch (_: IOException) {
        false
    }

    override fun readBlock(block: Int): ByteArray = tech.readBlock(block)

    override fun writeBlock(block: Int, data: ByteArray) {
        tech.writeBlock(block, data)
    }

    override fun close() {
        runCatching { tech.close() }
    }
}

class AndroidUltralightTransport(tag: Tag) : UltralightTransport {

    private val tech: NfcA = NfcA.get(tag)
        ?: throw TagUnusableException("il tag non supporta NfcA")

    init {
        try {
            tech.connect()
        } catch (t: Exception) {
            throw TagUnusableException("connessione al tag non riuscita", t)
        }
    }

    override fun transceive(command: ByteArray): ByteArray {
        val response = tech.transceive(command)
            ?: throw IOException("risposta NFC vuota")
        return response
    }

    override fun close() {
        runCatching { tech.close() }
    }
}
