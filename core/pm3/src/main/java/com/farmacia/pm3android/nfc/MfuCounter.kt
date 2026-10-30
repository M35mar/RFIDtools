// Set a MIFARE Ultralight EV1 counter to an absolute value.
//
// EV1 counters are one-way: the phone can only INCREMENT_CNT, never write or
// decrement. The tool therefore reads the current value, computes the delta to
// the target the user chose, increments by that delta and reads the counter
// back. A target below the current value is refused; resetting a counter needs
// the Proxmark3 and its `mfuev1_counter_reset.lua` script.
//
// Commands (NXP MF0ULX1):
//   GET_VERSION   0x60
//   PWD_AUTH      0x1B + 4-byte password
//   READ_CNT      0x39 + index        -> 3 bytes little endian
//   READ_TEARING  0x3E + index        -> 0xBD when safe
//   INCREMENT_CNT 0xA5 + index + 3-byte delta
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.TagLostException
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.VersionInfo
import java.io.IOException

/** Result of a counter update. [written] is false when it was already there. */
data class MfuCounterChange(
    val index: Int,
    val previous: Int,
    val value: Int,
    val written: Boolean,
    val tagLabel: String,
    /** The tag did not answer READ_TEARING; the write proceeded with a warning. */
    val tearingUnknown: Boolean = false,
)

/** The target is below the current value: an EV1 counter can only grow. */
class MfuCounterTargetException(val current: Int, val target: Int) :
    IOException("il contatore vale $current e può solo aumentare")

/** The counter is in a tearing state; writing now could leave it inconsistent. */
class MfuCounterTearingException(val index: Int) :
    IOException("il contatore $index è in stato di tearing")

/** The tag did not return the value that was just written. */
class MfuCounterVerifyException(val expected: Int, val actual: Int?) :
    IOException("verifica del contatore non riuscita")

/** The tag is not an MF0UL11/MF0UL21, or its counter slot is not supported. */
class MfuCounterUnsupportedException(message: String) : IOException(message)

class MfuCounterNotSupportedException(val index: Int) :
    IOException("il contatore $index non è supportato da questo tag")

class MfuCounterTool(private val transport: UltralightTransport) {

    /**
     * Reads the version, optionally authenticates, and brings [index] to
     * [target]. The read-back is part of the operation: a tag that accepted
     * the command but did not change is reported as a failure.
     */
    fun setCounter(index: Int, target: Int, password: ByteArray?): MfuCounterChange {
        require(index in 0..2) { "indice contatore fuori intervallo" }
        require(target in 0..COUNTER_MAX) { "valore contatore fuori intervallo" }
        if (password != null) require(password.size == 4) { "la password deve avere 4 byte" }

        val version = readVersion()
        if (password != null && !authenticate(password)) {
            throw TagAuthException("il tag ha rifiutato la password")
        }

        val previous = try {
            readCounter(index)
        } catch (e: MfuCounterNotSupportedException) {
            throw e
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            if (password == null) {
                throw TagAuthException("il contatore richiede la password")
            }
            throw TagUnusableException("lettura del contatore non riuscita", e)
        }

        if (target == previous) {
            return MfuCounterChange(
                index = index,
                previous = previous,
                value = previous,
                written = false,
                tagLabel = version.productName,
            )
        }
        if (target < previous) throw MfuCounterTargetException(previous, target)

        val tearingOk = readTearing(index)
        if (tearingOk == false) throw MfuCounterTearingException(index)

        val delta = target - previous
        increment(index, delta)

        val actual = try {
            readCounter(index)
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            throw MfuCounterVerifyException(target, null)
        }
        if (actual != target) throw MfuCounterVerifyException(target, actual)

        return MfuCounterChange(
            index = index,
            previous = previous,
            value = actual,
            written = true,
            tagLabel = version.productName,
            tearingUnknown = tearingOk == null,
        )
    }

    // -----------------------------------------------------------------------
    // Card commands
    // -----------------------------------------------------------------------

    private fun readVersion(): VersionInfo {
        val response = try {
            transport.transceive(byteArrayOf(CMD_GET_VERSION.toByte()))
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            throw MfuCounterUnsupportedException("GET_VERSION non risponde: ${e.message}")
        }
        if (response.size < 8) {
            throw TagUnusableException("GET_VERSION: risposta di ${response.size} byte")
        }
        val version = VersionInfo.parse(response)
            ?: throw MfuCounterUnsupportedException("versione del tag non riconosciuta")
        if (version.family != TagFamily.MIFARE_ULTRALIGHT || version.storageCode !in EV1_STORAGE) {
            throw MfuCounterUnsupportedException(
                "solo MIFARE Ultralight EV1: il tag è ${version.productName}",
            )
        }
        return version
    }

    private fun authenticate(password: ByteArray): Boolean = try {
        val response = transport.transceive(byteArrayOf(CMD_PWD_AUTH.toByte()) + password)
        response.size >= 2
    } catch (e: TagLostException) {
        throw e
    } catch (_: IOException) {
        false
    }

    private fun readCounter(index: Int): Int {
        val response = transport.transceive(
            byteArrayOf(CMD_READ_CNT.toByte(), index.toByte()),
        )
        if (response.size < 3) {
            throw IOException("READ_CNT $index: risposta di ${response.size} byte")
        }
        val value = (response[0].toInt() and 0xFF) or
            ((response[1].toInt() and 0xFF) shl 8) or
            ((response[2].toInt() and 0xFF) shl 16)
        if (value == COUNTER_UNSUPPORTED) throw MfuCounterNotSupportedException(index)
        return value
    }

    /** True when safe, false on tearing, null when the tag did not answer. */
    private fun readTearing(index: Int): Boolean? = try {
        val response = transport.transceive(
            byteArrayOf(CMD_READ_TEARING.toByte(), index.toByte()),
        )
        if (response.isEmpty()) null else (response[0].toInt() and 0xFF) == TEARING_OK
    } catch (e: TagLostException) {
        throw e
    } catch (_: IOException) {
        null
    }

    private fun increment(index: Int, delta: Int) {
        val command = byteArrayOf(
            CMD_INCREMENT_CNT.toByte(),
            index.toByte(),
            (delta and 0xFF).toByte(),
            ((delta ushr 8) and 0xFF).toByte(),
            ((delta ushr 16) and 0xFF).toByte(),
        )
        val response = transport.transceive(command)
        // An empty answer is what some Android stacks return for a bare ACK.
        if (response.isNotEmpty() && (response[0].toInt() and 0xFF) != ACK) {
            throw IOException("INCREMENT_CNT rifiutato dal tag")
        }
    }

    private companion object {
        const val CMD_GET_VERSION = 0x60
        const val CMD_PWD_AUTH = 0x1B
        const val CMD_READ_CNT = 0x39
        const val CMD_READ_TEARING = 0x3E
        const val CMD_INCREMENT_CNT = 0xA5
        const val ACK = 0x0A
        const val TEARING_OK = 0xBD
        const val COUNTER_UNSUPPORTED = 0xFFFFFF
        const val COUNTER_MAX = 0xFFFFFF
        val EV1_STORAGE = setOf(0x0B, 0x0E)
    }
}
