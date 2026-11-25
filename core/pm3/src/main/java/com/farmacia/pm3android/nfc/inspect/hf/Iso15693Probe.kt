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
import com.farmacia.pm3android.nfc.inspect.OriginalityGroup
import com.farmacia.pm3android.nfc.inspect.OriginalityStatus
import com.farmacia.pm3android.nfc.inspect.OriginalityVerifier
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
                Iso15693.parseSystemInfo(transport.transceive(Iso15693.getSystemInfo()), extended = false)
            } ?: attempt(abort) {
                Iso15693.parseSystemInfo(transport.transceive(Iso15693.getExtendedSystemInfo()), extended = true)
            }

            val firstBlock = attempt(abort) {
                Iso15693.parseBlockData(transport.transceive(Iso15693.readBlock(0)))
            }

            val blockSize = systemInfo?.blockSize ?: firstBlock?.size
            val ndef = attempt(abort) {
                readNdef(transport, blockSize, firstBlock, systemInfo?.blockCount)
            } ?: NdefContent.absent

            val uid = context.snapshot.uid
            val manufacturer = Iso15693Info.manufacturer(uid)
            val family = familyOf(manufacturer)
            val rawSig = attempt(abort) {
                val resp = transport.transceive(Iso15693.readSignature())
                Iso15693.parseBlockData(resp)?.takeIf { it.size == 32 }
            }
            val sigSuffix = rawSig?.let { sig ->
                val check = OriginalityVerifier.verify(
                    uid = uid,
                    signature = sig,
                    group = OriginalityGroup.ISO15693,
                    tryReversed = true,
                )
                when (check.status) {
                    OriginalityStatus.VALID -> " · ECC Valid"
                    OriginalityStatus.INVALID -> " · ECC Invalid"
                    else -> ""
                }
            }.orEmpty()
            val label = labelOf(family, manufacturer, systemInfo?.icReference, systemInfo?.blockCount) + sigSuffix
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
     * blocks needed by the TLV length are read; the standard one-byte block
     * address stops the scan at block 255, so a longer tag is reported as
     * truncated rather than read at the wrong address.
     */
    private fun readNdef(
        transport: com.farmacia.pm3android.nfc.inspect.NfcVTransport,
        blockSize: Int?,
        block0: ByteArray?,
        totalBlocks: Int?,
    ): NdefContent {
        val first = block0 ?: Iso15693.parseBlockData(transport.transceive(Iso15693.readBlock(0)))
            ?: return NdefContent.absent
        val size = blockSize?.takeIf { it in 1..64 }
            ?: first.size.takeIf { it in 1..64 }
            ?: 4
        if (first.size < CC_BYTES) return NdefContent.absent
        val magic = first[0].toInt() and 0xFF
        if (magic != CC_MAGIC && magic != CC_MAGIC_EXTENDED) return NdefContent.absent

        // NFC Forum Type 5: 8-byte CC when magic is 0xE2 or MLEN (byte 2) is 0x00.
        val ccLen = if (magic == CC_MAGIC_EXTENDED || first[2].toInt() == 0) 8 else CC_BYTES
        val maxBlocks = (totalBlocks ?: Iso15693.MAX_BLOCKS).coerceIn(1, 512)

        val memory = ByteArrayOutputStream()
        memory.write(first)
        var block = 1
        while (memory.size() < ccLen + 4 && block < maxBlocks) {
            val next = Iso15693.parseBlockData(
                transport.transceive(Iso15693.readAnyBlock(block)),
            ) ?: return NdefContent.absent
            memory.write(next)
            block++
        }

        val total = tlvLength(memory.toByteArray(), ccLen) ?: return NdefContent.absent
        val needed = ccLen + total
        if (needed > maxBlocks * size) return NdefContent.absent

        while (memory.size() < needed && block < maxBlocks) {
            val next = Iso15693.parseBlockData(
                transport.transceive(Iso15693.readAnyBlock(block)),
            ) ?: return NdefContent(
                emptyList(),
                (memory.size() - ccLen).coerceAtLeast(0),
                NdefStatus.TRUNCATED,
            )
            memory.write(next)
            block++
        }
        val data = memory.toByteArray()
        if (data.size < needed) {
            return NdefContent(
                emptyList(),
                (data.size - ccLen).coerceAtLeast(0),
                NdefStatus.TRUNCATED,
            )
        }
        return NdefParser.parseTlv(data.copyOfRange(ccLen, needed))
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

    private fun labelOf(
        family: TagFamily,
        manufacturer: String?,
        icRef: Int?,
        blocks: Int?,
    ): String = when (family) {
        TagFamily.ICODE -> when {
            icRef == 0x01 && blocks != null && blocks >= 75 -> "NXP ICODE SLIX2 (ISO 15693)"
            icRef == 0x01 && blocks != null && blocks in 28..36 -> "NXP ICODE SLIX (ISO 15693)"
            icRef == 0x02 -> "NXP ICODE SLIX-S (ISO 15693)"
            icRef == 0x03 -> "NXP ICODE SLIX-L (ISO 15693)"
            else -> "NXP ICODE (ISO 15693)"
        }
        TagFamily.ST25 -> when (icRef) {
            0x23 -> "ST25TV512 / ST25TV02K (ISO 15693)"
            0x24 -> "ST25DV04K (ISO 15693)"
            0x25 -> "ST25DV16K (ISO 15693)"
            0x26 -> "ST25DV64K (ISO 15693)"
            else -> "ST25TV / ST25DV (ISO 15693)"
        }
        TagFamily.EM_MICROELECTRONIC -> "EM Microelectronic (ISO 15693)"
        TagFamily.SLE66R -> "Infineon SLE66R / my-d (ISO 15693)"
        else -> when (manufacturer) {
            "Texas Instruments" -> "TI Tag-it HF-I (ISO 15693)"
            else -> "ISO 15693${manufacturer?.let { " · $it" } ?: ""}"
        }
    }

    /** Capability container: magic 0xE1 (4-byte) or 0xE2 (8-byte extended). */
    private const val CC_BYTES = 4
    private const val CC_MAGIC = 0xE1
    private const val CC_MAGIC_EXTENDED = 0xE2
}
