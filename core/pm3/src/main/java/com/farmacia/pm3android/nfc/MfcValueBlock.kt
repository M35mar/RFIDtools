// MIFARE Classic value block: the standard 16-byte layout used for stored
// credit, tickets or ride counts.
//
//   bytes 0..3   value, little endian
//   bytes 4..7   one's complement of the value
//   bytes 8..11  value again
//   bytes 12..15 block address, its complement, address, complement
//
// The operations on the card are INCREMENT / DECREMENT followed by TRANSFER;
// the block is never rewritten with a plain WRITE. The access bits decide
// whether the sector key may run them, and this file keeps that decision pure
// so it can be tested without a phone.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.IOException

/** Which key proved on a sector can run the operation. */
enum class MfcKeyType {
    A,
    B,
}

/** The two value-block operations the tool exposes. */
enum class MfcValueOperation {
    INCREMENT,
    DECREMENT,
}

/**
 * One value block found by [MfcValueTool.scan]. [increment] and [decrement]
 * are the ACL requirements; the key availability comes from the key map that
 * opened the sector during the same read.
 */
data class MfcValueBlockInfo(
    val sector: Int,
    val block: Int,
    val value: Int,
    val increment: MfcKeyRequirement,
    val decrement: MfcKeyRequirement,
    val hasKeyA: Boolean,
    val hasKeyB: Boolean,
) {
    val incrementKey: MfcKeyType? get() = keyFor(increment)

    val decrementKey: MfcKeyType? get() = keyFor(decrement)

    val canIncrement: Boolean get() = incrementKey != null

    val canDecrement: Boolean get() = decrementKey != null

    private fun keyFor(requirement: MfcKeyRequirement): MfcKeyType? = when (requirement) {
        MfcKeyRequirement.ANY -> when {
            hasKeyA -> MfcKeyType.A
            hasKeyB -> MfcKeyType.B
            else -> null
        }

        MfcKeyRequirement.A -> if (hasKeyA) MfcKeyType.A else null
        MfcKeyRequirement.B -> if (hasKeyB) MfcKeyType.B else null
        MfcKeyRequirement.NONE -> null
    }
}

/** Result of a successful increment or decrement. */
data class MfcValueChange(
    val sector: Int,
    val block: Int,
    val operation: MfcValueOperation,
    val amount: Int,
    val previous: Int,
    val value: Int,
    val keyType: MfcKeyType,
)

/** The block can be read but its ACL does not allow the chosen operation. */
class MfcValuePermissionException(val operation: MfcValueOperation) :
    IOException("operazione non consentita dagli access bits")

/** The value changed between the scan and the write. */
class MfcValueChangedException(val current: Int) :
    IOException("il valore del blocco è cambiato")

/** The tag did not return the value the operation should have produced. */
class MfcValueVerifyException(val expected: Int, val actual: Int?) :
    IOException("verifica del nuovo valore non riuscita")

/** The requested amount would move the value outside the signed 32-bit range. */
class MfcValueRangeException(val previous: Int, val amount: Int) :
    IOException("il nuovo valore non è valido")

/** Decoder/encoder and validation of the standard MIFARE Classic value block. */
object MfcValueBlock {

    private const val BYTES = 16

    /** The value is stored little endian in bytes 0..3. */
    fun value(block: ByteArray): Int {
        require(block.size >= 4) { "blocco valore troppo corto" }
        return (block[0].toInt() and 0xFF) or
            ((block[1].toInt() and 0xFF) shl 8) or
            ((block[2].toInt() and 0xFF) shl 16) or
            ((block[3].toInt() and 0xFF) shl 24)
    }

    fun address(block: ByteArray): Int {
        require(block.size >= BYTES) { "blocco valore troppo corto" }
        return block[12].toInt() and 0xFF
    }

    /**
     * Value, complement and address fields agree. The block number is only
     * checked by [isValid]: a value block read as a dump may be shown by the
     * inspector even when its address byte is unusual.
     */
    fun isConsistent(block: ByteArray): Boolean {
        if (block.size < BYTES) return false
        for (index in 0 until 4) {
            val value = block[index].toInt() and 0xFF
            if ((block[4 + index].toInt() and 0xFF) != (value xor 0xFF)) return false
            if ((block[8 + index].toInt() and 0xFF) != value) return false
        }
        val address = block[12].toInt() and 0xFF
        return (block[13].toInt() and 0xFF) == (address xor 0xFF) &&
            (block[14].toInt() and 0xFF) == address &&
            (block[15].toInt() and 0xFF) == (address xor 0xFF)
    }

    /** Standard block, and its address field points at [blockNumber]. */
    fun isValid(block: ByteArray, blockNumber: Int): Boolean =
        isConsistent(block) && address(block) == (blockNumber and 0xFF)

    /** Builds the standard 16-byte layout; used by the tests and the editor. */
    fun encode(value: Int, address: Int): ByteArray {
        val out = ByteArray(BYTES)
        for (index in 0 until 4) {
            val byte = (value ushr (8 * index)).toByte()
            out[index] = byte
            out[4 + index] = (byte.toInt() xor 0xFF).toByte()
            out[8 + index] = byte
        }
        val addressByte = address.toByte()
        out[12] = addressByte
        out[13] = (addressByte.toInt() xor 0xFF).toByte()
        out[14] = addressByte
        out[15] = (addressByte.toInt() xor 0xFF).toByte()
        return out
    }
}
