// Full ISO 15693 (NfcV) memory reader, restorer, lock-map scanner and
// ICODE SLIX / ST25 password presenter, with mandatory read-back verification.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import com.farmacia.pm3android.nfc.inspect.Iso15693
import com.farmacia.pm3android.nfc.inspect.NfcVTransport
import java.io.IOException

object Iso15693Dumper {

    private const val MFG_STM = 0x02
    private const val MFG_NXP = 0x04

    /**
     * Sends vendor `PRESENT_PASSWORD` (`0xB3`) when a 4-byte or 8-byte password
     * is supplied (supports NXP ICODE SLIX/SLIX2 Read+Write passwords and ST25).
     */
    fun presentPasswordIfProvided(
        transport: NfcVTransport,
        uid: ByteArray,
        password: ByteArray?,
    ) {
        if (password == null || password.isEmpty()) return
        val mfg = uid.getOrNull(6)?.toInt()?.and(0xFF) ?: 0
        when {
            mfg == MFG_NXP && password.size == 4 -> {
                // NXP ICODE SLIX/SLIX2 addressed PRESENT_PASSWORD (0xB3):
                // Try Get Random Number (0xB2) first for XORed SLIX2/SLIX session;
                // fall back to direct payload on clones.
                val rndResp = runCatching {
                    transport.transceive(
                        byteArrayOf(Iso15693.FLAG_ADDRESSED.toByte(), 0xB2.toByte(), MFG_NXP.toByte()) + uid,
                    )
                }.getOrNull()
                val rnd = if (rndResp != null && rndResp.size >= 3 && (rndResp[0].toInt() and 0x01) == 0) {
                    rndResp.copyOfRange(1, 3)
                } else {
                    null
                }
                for (pwdId in intArrayOf(0x01, 0x02)) { // 0x01 = Read, 0x02 = Write
                    val masked = if (rnd != null) {
                        byteArrayOf(
                            (password[0].toInt() xor rnd[0].toInt()).toByte(),
                            (password[1].toInt() xor rnd[1].toInt()).toByte(),
                            (password[2].toInt() xor rnd[0].toInt()).toByte(),
                            (password[3].toInt() xor rnd[1].toInt()).toByte(),
                        )
                    } else {
                        password
                    }
                    val frame = byteArrayOf(
                        Iso15693.FLAG_ADDRESSED.toByte(),
                        0xB3.toByte(),
                        MFG_NXP.toByte(),
                    ) + uid + byteArrayOf(pwdId.toByte()) + masked
                    runCatching { transport.transceive(frame) }
                }
            }

            mfg == MFG_STM && (password.size == 4 || password.size == 8) -> {
                val pwd8 = if (password.size == 8) password else password + password
                val frame = byteArrayOf(
                    Iso15693.FLAG_ADDRESSED.toByte(),
                    0xB3.toByte(),
                    MFG_STM.toByte(),
                ) + uid + byteArrayOf(0x01) + pwd8
                runCatching { transport.transceive(frame) }
            }
        }
    }

    fun readAll(
        transport: NfcVTransport,
        fallbackUid: ByteArray,
        password: ByteArray? = null,
        progress: RestoreProgress? = null,
    ): Iso15693Dump {
        presentPasswordIfProvided(transport, fallbackUid, password)

        val sys = runCatching {
            Iso15693.parseSystemInfo(transport.transceive(Iso15693.getSystemInfo()), extended = false)
        }.getOrNull() ?: runCatching {
            Iso15693.parseSystemInfo(transport.transceive(Iso15693.getExtendedSystemInfo()), extended = true)
        }.getOrNull()

        val uid = sys?.uid ?: fallbackUid
        val firstBlock = Iso15693.parseBlockData(transport.transceive(Iso15693.readBlock(0)))
            ?: throw TagAuthException("Blocco 0 ISO 15693 illeggibile (possibile protezione password)")
        val blockSize = sys?.blockSize ?: firstBlock.size.takeIf { it in 1..64 } ?: 4

        val blocks = mutableListOf<ByteArray>()
        val targetCount = sys?.blockCount ?: 256
        blocks += firstBlock.copyOf(blockSize)
        progress?.onProgress(1, targetCount)

        for (b in 1 until targetCount) {
            val data = try {
                Iso15693.parseBlockData(transport.transceive(Iso15693.readAnyBlock(b)))
            } catch (_: IOException) {
                null
            }
            if (data == null) {
                if (sys?.blockCount == null && b >= 4) break
                throw TagAuthException("Lettura ISO 15693 interrotta al blocco $b")
            }
            blocks += data.copyOf(blockSize)
            progress?.onProgress(blocks.size, targetCount)
        }

        // Read multi-block lock status when <=256 blocks and supported by the tag.
        val lockStatus = if (blocks.size <= 256) {
            runCatching {
                val resp = transport.transceive(Iso15693.readBlockSecurityStatus(0, blocks.size))
                Iso15693.parseBlockData(resp)?.takeIf { it.size == blocks.size }
            }.getOrNull()
        } else {
            null
        }

        val defaultName = "hf-15-${uid.toHex()}-dump"
        return Iso15693Dump(
            name = defaultName,
            uid = uid,
            blockSize = blockSize,
            blocks = blocks,
            dsfid = sys?.dsfid ?: 0,
            afi = sys?.afi ?: 0,
            icReference = sys?.icReference ?: 0,
            lockStatus = lockStatus,
        )
    }

    fun restore(
        transport: NfcVTransport,
        targetUid: ByteArray,
        dump: Iso15693Dump,
        password: ByteArray? = null,
        progress: RestoreProgress? = null,
        abort: () -> Boolean = { false },
    ): RestoreReport {
        val started = System.currentTimeMillis()
        presentPasswordIfProvided(transport, targetUid, password)

        val total = dump.blocks.size
        val steps = mutableListOf<StepResult>()
        var aborted = false

        for (b in 0 until total) {
            if (abort()) {
                aborted = true
                break
            }
            val desired = dump.blocks[b]
            val current = runCatching {
                Iso15693.parseBlockData(transport.transceive(Iso15693.readAnyBlock(b)))
            }.getOrNull()

            if (current != null && current.copyOf(dump.blockSize).contentEquals(desired)) {
                steps += StepResult(b, StepStatus.UNCHANGED)
                progress?.onProgress(steps.size, total)
                continue
            }

            if (dump.lockStatus != null && b < dump.lockStatus.size && dump.lockStatus[b].toInt() != 0) {
                steps += StepResult(b, StepStatus.SKIPPED, StepNote.READ_ONLY)
                progress?.onProgress(steps.size, total)
                continue
            }

            val writeFrame = if (b <= Iso15693.MAX_BLOCK) {
                Iso15693.writeBlock(b, desired)
            } else {
                byteArrayOf(
                    Iso15693.FLAG_UNADDRESSED.toByte(),
                    Iso15693.CMD_EXT_WRITE_SINGLE_BLOCK.toByte(),
                    (b and 0xFF).toByte(),
                    ((b ushr 8) and 0xFF).toByte(),
                ) + desired
            }
            val wroteOk = try {
                val resp = transport.transceive(writeFrame)
                resp.isNotEmpty() && (resp[0].toInt() and 0x01) == 0
            } catch (_: IOException) {
                false
            }
            if (!wroteOk) {
                steps += StepResult(b, StepStatus.FAILED, StepNote.WRITE_FAILED)
                progress?.onProgress(steps.size, total)
                continue
            }

            // Mandatory read-back verification!
            val after = runCatching {
                Iso15693.parseBlockData(transport.transceive(Iso15693.readAnyBlock(b)))
            }.getOrNull()
            steps += if (after != null && after.copyOf(dump.blockSize).contentEquals(desired)) {
                StepResult(b, StepStatus.VERIFIED)
            } else {
                StepResult(b, StepStatus.FAILED, StepNote.VERIFY_FAILED)
            }
            progress?.onProgress(steps.size, total)
        }

        return RestoreReport(
            dumpName = dump.name,
            tagLabel = "ISO 15693 (${dump.blocks.size} × ${dump.blockSize} B)",
            steps = steps,
            durationMs = System.currentTimeMillis() - started,
            aborted = aborted,
        )
    }
}
