// Scan and apply operations on MIFARE Classic value blocks.
//
// The classic "credit" workflow is: authenticate, INCREMENT or DECREMENT,
// TRANSFER, then read the block back. A plain WRITE is not used: it would
// bypass the value-block consistency rules and, on decrement-only sectors, is
// refused by the card.
//
// The scan keeps every standard value block it can see, together with the ACL
// requirement and the keys that actually opened the sector. A block whose ACL
// does not allow an operation is still shown, but the matching button is
// disabled by the UI.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.TagLostException
import java.io.IOException

object MfcValueTool {

    /**
     * Every readable, standard value block of [capture], in card order.
     *
     * The address field is not required to equal the block number: transit
     * cards such as RicaricaMi store a slot/relative address there. Blocks whose
     * trailer was not read are still listed, but their ACL is unknown and the UI
     * disables the operations.
     */
    fun scan(capture: MfcCapture): List<MfcValueBlockInfo> {
        val bytes = capture.toBin()
        val keys = capture.keys
        val blocks = mutableListOf<MfcValueBlockInfo>()

        for (sector in 0 until capture.size.sectors) {
            val first = MfcGeometry.firstBlockOfSector(sector)
            val count = MfcGeometry.blocksInSector(sector)
            val trailer = first + count - 1
            val trailerReadable = trailer in capture.readableBlocks

            val access = if (trailerReadable) {
                val trailerBase = trailer * MfcGeometry.BLOCK_SIZE
                bytes.copyOfRange(trailerBase + 6, trailerBase + 9)
            } else {
                null
            }
            val aclValid = access != null && MfcAccess.isValid(access)

            val hasKeyA = keys?.keyAFor(sector)?.any { it != 0.toByte() } == true
            val hasKeyB = keys?.keyBFor(sector)?.any { it != 0.toByte() } == true

            // Block 0 of sector 0 is the manufacturer block, never a value.
            val start = if (sector == 0) first + 1 else first
            for (block in start until trailer) {
                if (block !in capture.readableBlocks) continue
                val base = block * MfcGeometry.BLOCK_SIZE
                val payload = bytes.copyOfRange(base, base + MfcGeometry.BLOCK_SIZE)
                if (!MfcValueBlock.isConsistent(payload)) continue
                val offset = block - first
                val address = MfcValueBlock.address(payload)
                blocks += MfcValueBlockInfo(
                    sector = sector,
                    block = block,
                    value = MfcValueBlock.value(payload),
                    address = address,
                    addressMatches = address == (block and 0xFF),
                    trailerReadable = trailerReadable,
                    aclValid = aclValid,
                    increment = if (aclValid) {
                        MfcAccess.incrementKeyRequirement(access, offset, count)
                    } else {
                        MfcKeyRequirement.NONE
                    },
                    decrement = if (aclValid) {
                        MfcAccess.decrementKeyRequirement(access, offset, count)
                    } else {
                        MfcKeyRequirement.NONE
                    },
                    hasKeyA = hasKeyA,
                    hasKeyB = hasKeyB,
                )
            }
        }
        return blocks
    }

    /**
     * RicaricaMi (Milan) is an En1545 card whose ride counters are value blocks
     * in sectors 2..5. The AID 0x0221 is repeated in the MAD-like blocks 0.1 and
     * 0.2; this is the same early check Metrodroid uses.
     */
    fun isRicaricaMi(capture: MfcCapture): Boolean {
        val bytes = capture.toBin()
        for (block in 1..2) {
            val base = block * MfcGeometry.BLOCK_SIZE
            val start = if (block == 1) 1 else 0
            for (index in start..7) {
                val offset = base + index * 2
                if (offset + 1 >= bytes.size) return false
                val value = ((bytes[offset].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 1].toInt() and 0xFF)
                if (value != RICARICA_MI_AID) return false
            }
        }
        return true
    }

    /**
     * Runs one operation on a re-presented tag. The capture must come from the
     * same card and [info] from [scan] on that capture.
     *
     * The block is read again before the command: if somebody used the card in
     * the meantime, the operation is aborted instead of applying the amount to
     * a value the user never saw.
     */
    fun apply(
        transport: ClassicTransport,
        capture: MfcCapture,
        info: MfcValueBlockInfo,
        operation: MfcValueOperation,
        amount: Int,
    ): MfcValueChange {
        if (amount <= 0) throw MfcValueRangeException(info.value, amount)
        if (transport.sectorCount != capture.size.sectors ||
            transport.blockCount(info.sector) != MfcGeometry.blocksInSector(info.sector)
        ) {
            throw TagUnusableException("la geometria del tag non corrisponde alla lettura")
        }

        val keyType = when (operation) {
            MfcValueOperation.INCREMENT -> info.incrementKey
            MfcValueOperation.DECREMENT -> info.decrementKey
        } ?: throw MfcValuePermissionException(operation)

        val key = when (keyType) {
            MfcKeyType.A -> capture.keys?.keyAFor(info.sector)
            MfcKeyType.B -> capture.keys?.keyBFor(info.sector)
        } ?: throw TagAuthException("la chiave necessaria non è disponibile")

        if (!transport.authenticate(info.sector, key, keyType == MfcKeyType.A)) {
            throw TagAuthException("autenticazione del settore ${info.sector} non riuscita")
        }

        val before = transport.readBlock(info.block)
        if (!MfcValueBlock.isConsistent(before)) {
            throw MfcValueChangedException(MfcValueBlock.value(before))
        }
        val previous = MfcValueBlock.value(before)
        if (previous != info.value) throw MfcValueChangedException(previous)

        val expected = when (operation) {
            MfcValueOperation.INCREMENT -> previous.toLong() + amount
            MfcValueOperation.DECREMENT -> previous.toLong() - amount
        }
        if (expected !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            (operation == MfcValueOperation.DECREMENT && expected < 0)
        ) {
            throw MfcValueRangeException(previous, amount)
        }

        when (operation) {
            MfcValueOperation.INCREMENT -> transport.increment(info.block, amount)
            MfcValueOperation.DECREMENT -> transport.decrement(info.block, amount)
        }
        // INCREMENT/DECREMENT only stage the result in the transfer buffer;
        // TRANSFER is the command that commits it to the block.
        transport.transfer(info.block)

        val after = try {
            transport.readBlock(info.block)
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            throw MfcValueVerifyException(expected.toInt(), null)
        }
        if (!MfcValueBlock.isConsistent(after)) {
            throw MfcValueVerifyException(expected.toInt(), MfcValueBlock.value(after))
        }
        val value = MfcValueBlock.value(after)
        if (value != expected.toInt()) {
            throw MfcValueVerifyException(expected.toInt(), value)
        }

        return MfcValueChange(
            sector = info.sector,
            block = info.block,
            operation = operation,
            amount = amount,
            previous = previous,
            value = value,
            keyType = keyType,
        )
    }

    private const val RICARICA_MI_AID = 0x0221
}
