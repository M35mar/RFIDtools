// Thin adapters between the Android NFC stack and the two restore engines.
// They are the only Android-dependent part of the NFC feature: everything else
// is plain Kotlin and unit-testable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.farmacia.pm3android.nfc.inspect.CommandLog
import com.farmacia.pm3android.nfc.inspect.IsoDepTransport
import com.farmacia.pm3android.nfc.inspect.NfcBTransport
import com.farmacia.pm3android.nfc.inspect.NfcFTransport
import com.farmacia.pm3android.nfc.inspect.NfcVTransport
import com.farmacia.pm3android.nfc.inspect.RawTagTransport
import com.farmacia.pm3android.nfc.inspect.TagSession
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

    override fun increment(block: Int, value: Int) {
        tech.increment(block, value)
    }

    override fun decrement(block: Int, value: Int) {
        tech.decrement(block, value)
    }

    override fun transfer(block: Int) {
        tech.transfer(block)
    }

    override fun close() {
        runCatching { tech.close() }
    }
}

/**
 * ISO-DEP carrier for the DESFire client. The phone's NFC stack handles the
 * ISO 7816 framing; the DESFire client only builds the wrapped APDUs.
 */
class AndroidIsoDepTransport(tag: Tag, private val log: CommandLog? = null) : IsoDepTransport {

    private val tech: IsoDep = IsoDep.get(tag)
        ?: throw TagUnusableException("il tag non supporta ISO-DEP")

    init {
        try {
            tech.connect()
            tech.timeout = ISO_TIMEOUT_MS
        } catch (lost: TagLostException) {
            throw lost
        } catch (t: Exception) {
            throw TagUnusableException("connessione al tag non riuscita", t)
        }
    }

    override fun transceive(command: ByteArray): ByteArray {
        val response = tech.transceive(command)
            ?: throw IOException("risposta NFC vuota")
        log?.record(command, response)
        return response
    }

    fun close() {
        runCatching { tech.close() }
    }

    private companion object {
        const val ISO_TIMEOUT_MS = 5000
    }
}

/**
 * Raw NfcA carrier for the MIFARE Plus SL3 client. The phone's NFC controller
 * adds and checks the ISO 14443-3A CRC, so the client only sees the payload.
 */
class AndroidNfcATransport(tag: Tag, private val log: CommandLog? = null) : RawTagTransport {

    private val tech: NfcA = NfcA.get(tag)
        ?: throw TagUnusableException("il tag non supporta NfcA")

    init {
        try {
            tech.connect()
        } catch (lost: TagLostException) {
            throw lost
        } catch (t: Exception) {
            throw TagUnusableException("connessione al tag non riuscita", t)
        }
    }

    override fun transceive(data: ByteArray): ByteArray {
        val response = tech.transceive(data)
            ?: throw IOException("risposta NFC vuota")
        log?.record(data, response)
        return response
    }

    fun close() {
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

/**
 * ISO 15693 carrier. The phone adds and checks the CRC, so the probe only sees
 * the payload frames built by [com.farmacia.pm3android.nfc.inspect.Iso15693].
 */
class AndroidNfcVTransport(tag: Tag, private val log: CommandLog? = null) : NfcVTransport {

    private val tech: NfcV = NfcV.get(tag)
        ?: throw TagUnusableException("il tag non supporta NfcV")

    init {
        try {
            tech.connect()
        } catch (lost: TagLostException) {
            throw lost
        } catch (t: Exception) {
            throw TagUnusableException("connessione al tag non riuscita", t)
        }
    }

    override fun transceive(command: ByteArray): ByteArray {
        val response = tech.transceive(command)
            ?: throw IOException("risposta NFC vuota")
        log?.record(command, response)
        return response
    }

    fun close() {
        runCatching { tech.close() }
    }
}

/** ISO 18092 passive carrier, exposed by Android as NfcF. */
class AndroidNfcFTransport(tag: Tag, private val log: CommandLog? = null) : NfcFTransport {

    private val tech: NfcF = NfcF.get(tag)
        ?: throw TagUnusableException("il tag non supporta NfcF")

    init {
        try {
            tech.connect()
        } catch (lost: TagLostException) {
            throw lost
        } catch (t: Exception) {
            throw TagUnusableException("connessione al tag non riuscita", t)
        }
    }

    override fun transceive(command: ByteArray): ByteArray {
        val response = tech.transceive(command)
            ?: throw IOException("risposta NFC vuota")
        log?.record(command, response)
        return response
    }

    fun close() {
        runCatching { tech.close() }
    }
}

/** ISO 14443-3B carrier, for the cards that never answer ISO-DEP. */
class AndroidNfcBTransport(tag: Tag, private val log: CommandLog? = null) : NfcBTransport {

    private val tech: NfcB = NfcB.get(tag)
        ?: throw TagUnusableException("il tag non supporta NfcB")

    init {
        try {
            tech.connect()
        } catch (lost: TagLostException) {
            throw lost
        } catch (t: Exception) {
            throw TagUnusableException("connessione al tag non riuscita", t)
        }
    }

    override fun transceive(command: ByteArray): ByteArray {
        val response = tech.transceive(command)
            ?: throw IOException("risposta NFC vuota")
        log?.record(command, response)
        return response
    }

    fun close() {
        runCatching { tech.close() }
    }
}

/**
 * Opens one technology at a time. Android allows a single connection per tag,
 * and the probes expect the transport to be closed before the next one starts.
 */
class AndroidTagSession(
    private val tag: Tag,
    private val log: CommandLog? = null,
) : TagSession {

    override fun <T> withIsoDep(block: (IsoDepTransport) -> T): T? =
        guarded({ AndroidIsoDepTransport(tag, log) }, { it.close() }, block)

    override fun <T> withNfcA(block: (RawTagTransport) -> T): T? =
        guarded({ AndroidNfcATransport(tag, log) }, { it.close() }, block)

    override fun <T> withNfcV(block: (NfcVTransport) -> T): T? =
        guarded({ AndroidNfcVTransport(tag, log) }, { it.close() }, block)

    override fun <T> withNfcF(block: (NfcFTransport) -> T): T? =
        guarded({ AndroidNfcFTransport(tag, log) }, { it.close() }, block)

    override fun <T> withNfcB(block: (NfcBTransport) -> T): T? =
        guarded({ AndroidNfcBTransport(tag, log) }, { it.close() }, block)

    private inline fun <A, T> guarded(
        open: () -> A,
        close: (A) -> Unit,
        block: (A) -> T,
    ): T? {
        val transport = try {
            open()
        } catch (lost: TagLostException) {
            throw lost
        } catch (_: Throwable) {
            return null
        }
        return try {
            block(transport)
        } finally {
            runCatching { close(transport) }
        }
    }
}
