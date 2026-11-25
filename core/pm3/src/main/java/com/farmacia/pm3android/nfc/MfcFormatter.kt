// Built-in top universal MIFARE Classic keys & safe Factory Formatter.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.IOException

object MfcQuickKeys {

    /**
     * Top 24 most common MIFARE Classic keys in the wild (Factory, MAD1/MAD2,
     * NFC Forum NDEF, Zero, common access-control/hotel/vending/transit keys).
     */
    val KEYS: List<ByteArray> = listOf(
        "FFFFFFFFFFFF", // Factory transport
        "A0A1A2A3A4A5", // MAD Key A
        "D3F7D3F7D3F7", // NFC Forum NDEF Key A
        "000000000000", // Blank / zero key
        "B0B1B2B3B4B5", // Common MAD Key B
        "4D3A99C351DD", // Common NDEF / Nokia
        "1A982C7E459A", // Common access control
        "AABBCCDDEEFF", // Test / dev key
        "714C5C886E97", // Common MiFare system
        "587EE5F9350F", // Common MiFare system
        "A0478CC39091", // Common MiFare system
        "533CB6C723F6", // Common MiFare system
        "8FD0A4F256E9", // Common MiFare system
        "A0B0C0D0E0F0", // Sequential A..F
        "A1B1C1D1E1F1", // Sequential B
        "010203040506", // Sequential 1..6
        "123456789ABC", // Sequential hex
        "B5FF67CBA951", // Common hotel/vending
        "FC00018778F7", // Common transit
        "00000FFE2488", // Common transit
        "5C8FF9990DA2", // Common access
        "75CCB59C9BED", // Common access
        "484944204953", // HID IS
        "204752454154", // GREAT
    ).mapNotNull { it.hexToBytesOrNull() }
}

/**
 * Formats a MIFARE Classic card back to factory defaults:
 *  - Data blocks (except manufacturer block 0) -> `00..00`
 *  - Sector trailers -> `FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF`
 * Every written block & trailer is verified by read-back / re-authentication.
 */
class MfcFormatter(private val transport: ClassicTransport) {

    fun formatToFactory(
        candidates: List<ByteArray> = MfcQuickKeys.KEYS,
        progress: RestoreProgress? = null,
        abort: () -> Boolean = { false },
    ): RestoreReport {
        val started = System.currentTimeMillis()
        val sectors = transport.sectorCount
        val size = MfcSize.entries.firstOrNull { it.sectors == sectors }
            ?: throw TagUnusableException("taglia MIFARE Classic non supportata ($sectors settori)")

        val keys = if (candidates.isEmpty()) MfcQuickKeys.KEYS else candidates
        val keyMap = MfcKeyMap.build(
            sectorCount = size.sectors,
            keys = keys,
            authenticate = { s, k, a -> transport.authenticate(s, k, a) },
        )
        if (keyMap.isEmpty()) {
            throw TagAuthException("nessun settore apribile con le chiavi selezionate")
        }

        val zeroBlock = ByteArray(MfcGeometry.BLOCK_SIZE)
        val factoryTrailer = ByteArray(MfcGeometry.BLOCK_SIZE).apply {
            MFC_DEFAULT_KEY.copyInto(this, 0)
            MfcAccess.DEFAULT.copyInto(this, 6)
            MFC_DEFAULT_KEY.copyInto(this, 10)
        }

        val steps = mutableListOf<StepResult>()
        var aborted = false

        for (sector in 0 until size.sectors) {
            if (abort()) {
                aborted = true
                break
            }
            val first = MfcGeometry.firstBlockOfSector(sector)
            val count = MfcGeometry.blocksInSector(sector)
            val trailerBlock = first + count - 1
            val proven = keyMap[sector]
            if (proven == null) {
                for (offset in 0 until count) {
                    val b = first + offset
                    steps += if (b == 0) {
                        StepResult(0, StepStatus.SKIPPED, StepNote.UID_KEPT)
                    } else {
                        StepResult(b, StepStatus.FAILED, StepNote.AUTH_FAILED)
                    }
                }
                progress?.onProgress(steps.size, size.blocks)
                continue
            }

            // Read current trailer if possible to determine write requirements.
            val readSession = proven.sessionForAny(preferA = true) ?: proven.sessionForAny(preferA = false)
            val currentTrailer = if (readSession != null && transport.authenticate(sector, readSession.key, readSession.keyA)) {
                runCatching { transport.readBlock(trailerBlock) }.getOrNull()
            } else {
                null
            }
            val access = currentTrailer?.copyOfRange(6, 10)?.takeIf { MfcAccess.isValid(it) }

            // Format data blocks first.
            for (offset in 0 until count - 1) {
                val block = first + offset
                if (block == 0) {
                    steps += StepResult(0, StepStatus.SKIPPED, StepNote.UID_KEPT)
                    continue
                }
                val req = if (access != null) {
                    MfcAccess.writeKeyRequirement(access, offset, count)
                } else {
                    MfcKeyRequirement.ANY
                }
                val writeSess = proven.sessionFor(req) ?: proven.sessionForAny(preferA = false)
                if (writeSess == null || !transport.authenticate(sector, writeSess.key, writeSess.keyA)) {
                    steps += StepResult(block, StepStatus.FAILED, StepNote.AUTH_FAILED)
                    continue
                }
                val existing = runCatching { transport.readBlock(block) }.getOrNull()
                if (existing != null && existing.contentEquals(zeroBlock)) {
                    steps += StepResult(block, StepStatus.UNCHANGED)
                    continue
                }
                val wrote = try {
                    transport.writeBlock(block, zeroBlock)
                    true
                } catch (_: IOException) {
                    false
                }
                if (!wrote) {
                    steps += StepResult(block, StepStatus.FAILED, StepNote.WRITE_FAILED)
                    continue
                }
                val after = runCatching { transport.readBlock(block) }.getOrNull()
                steps += if (after != null && after.contentEquals(zeroBlock)) {
                    StepResult(block, StepStatus.VERIFIED)
                } else {
                    StepResult(block, StepStatus.FAILED, StepNote.VERIFY_FAILED)
                }
            }

            // Format sector trailer to factory FF..FF 07 80 69 FF..FF.
            val trailerReq = if (access != null) {
                MfcAccess.trailerWriteRequirement(access)
            } else {
                MfcKeyRequirement.ANY
            }
            val trailerSess = proven.sessionFor(trailerReq) ?: proven.sessionForAny(preferA = false)
            if (trailerSess == null || !transport.authenticate(sector, trailerSess.key, trailerSess.keyA)) {
                steps += StepResult(trailerBlock, StepStatus.FAILED, StepNote.AUTH_FAILED)
            } else {
                val wroteTrailer = try {
                    transport.writeBlock(trailerBlock, factoryTrailer)
                    true
                } catch (_: IOException) {
                    false
                }
                if (!wroteTrailer) {
                    steps += StepResult(trailerBlock, StepStatus.FAILED, StepNote.WRITE_FAILED)
                } else {
                    val okNewKey = transport.authenticate(sector, MFC_DEFAULT_KEY, true)
                    steps += if (okNewKey) {
                        StepResult(trailerBlock, StepStatus.VERIFIED)
                    } else {
                        StepResult(trailerBlock, StepStatus.FAILED, StepNote.VERIFY_FAILED)
                    }
                }
            }
            progress?.onProgress(steps.size, size.blocks)
        }

        return RestoreReport(
            dumpName = "Factory Format",
            tagLabel = size.label,
            steps = steps.sortedBy { it.index },
            durationMs = System.currentTimeMillis() - started,
            aborted = aborted,
        )
    }
}
