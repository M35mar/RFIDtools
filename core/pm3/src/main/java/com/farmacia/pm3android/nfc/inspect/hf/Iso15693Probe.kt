// ISO 15693 / NfcV probe: GET SYSTEM INFO, the first block and, when the
// card carries it, the NDEF Type 5 message. ICODE, ST25TV/DV and the EM
// ISO 15693 parts all answer the same standard commands; only the naming and
// the vendor-specific fields differ.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.Iso15693
import com.farmacia.pm3android.nfc.inspect.Iso15693Info
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentifier
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.nfc.inspect.content.NdefContent
import com.farmacia.pm3android.nfc.inspect.content.NdefParser
import com.farmacia.pm3android.nfc.inspect.content.NdefStatus
import com.farmacia.pm3android.nfc.inspect.content.TagContent
import java.io.ByteArrayOutputStream

object Iso15693Probe : HfProbe {

    override val id = "iso15693"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.NFC_V)

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withNfcV { transport ->
            val abort = context.abort
            val systemInfo = attempt(abort) {
                Iso15693.parseSystemInfo(transport.transceive(Iso15693.getSystemInfo()))
            }

            val firstBlock = attempt(abort) {
                Iso15693.parseBlockData(transport.transceive(Iso15693.readBlock(0)))
            }

            val blockSize = systemInfo?.blockSize ?: firstBlock?.size
            val ndef = attempt(abort) {
                readNdef(transport, blockSize, firstBlock)
            } ?: NdefContent.absent

            val uid = context.snapshot.uid
            val manufacturer = Iso15693Info.manufacturer(uid)
            val family = familyOf(manufacturer)
            val label = labelOf(family, manufacturer)
            val details = Iso15693Info.details(
                tagId = uid,
                dsfid = systemInfo?.dsfid,
                afi = systemInfo?.afi,
                firstBlock = firstBlock,
            ).let {
                com.farmacia.pm3android.nfc.inspect.Iso15693Details(
                    uid = it.uid,
                    dsfid = it.dsfid,
                    afi = it.afi,
                    firstBlock = it.firstBlock,
                    blockSize = it.blockSize ?: blockSize,
                    blocks = systemInfo?.blockCount,
                    infoFlags = systemInfo?.infoFlags,
                    icReference = systemInfo?.icReference,
                    manufacturer = manufacturer,
                    lockBits = null,
                    passwordProtected = null,
                    eas = null,
                )
            }

            val notes = buildList {
                if (systemInfo == null) add(TagNote.ISO15693_NO_SYSTEM_INFO)
                if (family == TagFamily.ST25) add(TagNote.ST25_VARIANT)
                if (ndef.status == NdefStatus.TRUNCATED) add(TagNote.NDEF_READ_FAILED)
            }

            ProbeOutcome(
                details = details,
                content = if (ndef.found) TagContent.ndefOnly(ndef) else null,
                identity = TagIdentifier.iso15693Identity(
                    snapshot = context.snapshot,
                    details = details,
                    family = family,
                    label = label,
                ),
                notes = notes,
            )
        }

    /**
     * Reads the Type 5 NDEF area. The capability container is the first four
     * bytes of block 0 and the NDEF TLV starts right after it, so the TLV can
     * live inside block 0 when the block is wider than four bytes. Only the
     * blocks needed by the TLV length are read.
     */
    private fun readNdef(
        transport: com.farmacia.pm3android.nfc.inspect.NfcVTransport,
        blockSize: Int?,
        block0: ByteArray?,
    ): NdefContent {
        val first = block0 ?: Iso15693.parseBlockData(transport.transceive(Iso15693.readBlock(0)))
            ?: return NdefContent.absent
        val size = blockSize?.takeIf { it in 1..64 }
            ?: first.size.takeIf { it in 1..64 }
            ?: 4
        if (first.size < CC_BYTES) return NdefContent.absent
        if ((first[0].toInt() and 0xFF) != CC_MAGIC) return NdefContent.absent

        val memory = ByteArrayOutputStream()
        memory.write(first)
        var block = 1
        while (memory.size() < CC_BYTES + 2 && block < MAX_BLOCKS) {
            val next = Iso15693.parseBlockData(
                transport.transceive(Iso15693.readBlock(block)),
            ) ?: return NdefContent.absent
            memory.write(next)
            block++
        }

        val total = tlvLength(memory.toByteArray(), CC_BYTES) ?: return NdefContent.absent
        val needed = CC_BYTES + total
        if (needed > MAX_BLOCKS * size) return NdefContent.absent

        while (memory.size() < needed && block < MAX_BLOCKS) {
            val next = Iso15693.parseBlockData(
                transport.transceive(Iso15693.readBlock(block)),
            ) ?: return NdefContent(
                emptyList(),
                (memory.size() - CC_BYTES).coerceAtLeast(0),
                NdefStatus.TRUNCATED,
            )
            memory.write(next)
            block++
        }
        val data = memory.toByteArray()
        if (data.size < needed) {
            return NdefContent(
                emptyList(),
                (data.size - CC_BYTES).coerceAtLeast(0),
                NdefStatus.TRUNCATED,
            )
        }
        return NdefParser.parseTlv(data.copyOfRange(CC_BYTES, needed))
    }

    /** Total bytes of the NDEF TLV (header included), or null when absent. */
    private fun tlvLength(data: ByteArray, start: Int): Int? {
        var position = start
        while (position + 1 < data.size) {
            val type = data[position].toInt() and 0xFF
            if (type == 0x00) {
                position++
                continue
            }
            if (type == 0xFE) return null
            if (type == 0x03) {
                var length = data[position + 1].toInt() and 0xFF
                var header = 2
                if (length == 0xFF) {
                    if (position + 3 >= data.size) return null
                    length = ((data[position + 2].toInt() and 0xFF) shl 8) or
                        (data[position + 3].toInt() and 0xFF)
                    header = 4
                }
                return header + length
            }
            val length = data[position + 1].toInt() and 0xFF
            position += 2 + length
        }
        return null
    }

    private inline fun <T> attempt(abort: (Throwable) -> Boolean, block: () -> T?): T? =
        try {
            block()
        } catch (error: Throwable) {
            if (abort(error)) throw error
            null
        }

    private fun familyOf(manufacturer: String?): TagFamily = when (manufacturer) {
        "NXP" -> TagFamily.ICODE
        "STMicroelectronics" -> TagFamily.ST25
        "EM Microelectronic" -> TagFamily.EM_MICROELECTRONIC
        "Infineon" -> TagFamily.SLE66R
        else -> TagFamily.ISO_15693
    }

    private fun labelOf(family: TagFamily, manufacturer: String?): String = when (family) {
        TagFamily.ICODE -> "NXP ICODE (ISO 15693)"
        TagFamily.ST25 -> "ST25TV / ST25DV (ISO 15693)"
        TagFamily.EM_MICROELECTRONIC -> "EM Microelectronic (ISO 15693)"
        TagFamily.SLE66R -> "Infineon SLE66R / my-d (ISO 15693)"
        else -> "ISO 15693${manufacturer?.let { " · $it" } ?: ""}"
    }

    private const val MAX_BLOCKS = 512

    /** Capability container: magic 0xE1, version, size, access. */
    private const val CC_BYTES = 4
    private const val CC_MAGIC = 0xE1
}
