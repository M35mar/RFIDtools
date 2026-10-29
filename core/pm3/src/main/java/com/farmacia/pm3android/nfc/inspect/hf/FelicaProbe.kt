// FeliCa / ISO 18092 passive probe. Android exposes the card as NfcF, which is
// enough for Request Response (PMm), Request System Code and, when the card
// has the NDEF service (0x000B), the Type 3 NDEF message. NFC-DEP peer-to-peer
// is not available through the Android NFC API, hence the note.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.FelicaDetails
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NfcFTransport
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagIdentifier
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.nfc.inspect.content.NdefContent
import com.farmacia.pm3android.nfc.inspect.content.NdefParser
import com.farmacia.pm3android.nfc.inspect.content.NdefStatus
import com.farmacia.pm3android.nfc.inspect.content.TagContent
import java.io.ByteArrayOutputStream

object FelicaProbe : HfProbe {

    override val id = "felica"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.NFC_F)

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withNfcF { transport ->
            val idm = context.snapshot.uid
            if (idm.size != 8) return@withNfcF null
            val abort = context.abort

            val pmm = try {
                requestResponse(transport, idm)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                null
            }
            val systemCodes = try {
                requestSystemCode(transport, idm)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                emptyList()
            }
            val ndef = try {
                readNdef(transport, idm)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                NdefContent.absent
            }

            val details = FelicaDetails(
                idm = idm,
                pmm = pmm,
                manufacturer = null,
                systemCode = systemCodes.firstOrNull(),
                systemCodes = systemCodes,
            )
            val notes = buildList {
                add(TagNote.FELICA_NO_P2P)
                if (ndef.status == NdefStatus.TRUNCATED) add(TagNote.NDEF_READ_FAILED)
            }
            ProbeOutcome(
                details = details,
                content = if (ndef.found) TagContent.ndefOnly(ndef) else null,
                identity = TagIdentifier.identify(context.snapshot, details),
                notes = notes,
            )
        }

    /** Request Response: LEN 04 IDm -> LEN 05 IDm PMm. */
    private fun requestResponse(transport: NfcFTransport, idm: ByteArray): ByteArray? {
        val response = transport.transceive(frame(0x04, idm))
        if (response.size < 18 || (response[1].toInt() and 0xFF) != 0x05) return null
        return response.copyOfRange(response.size - 8, response.size)
    }

    /** Request System Code: LEN 0C IDm -> LEN 0D IDm count codes... */
    private fun requestSystemCode(transport: NfcFTransport, idm: ByteArray): List<Int> {
        val response = transport.transceive(frame(0x0C, idm))
        if (response.size < 12 || (response[1].toInt() and 0xFF) != 0x0D) return emptyList()
        val count = response[10].toInt() and 0xFF
        return (0 until minOf(count, 8)).mapNotNull { index ->
            val offset = 11 + index * 2
            if (offset + 1 >= response.size) return@mapNotNull null
            ((response[offset].toInt() and 0xFF) shl 8) or
                (response[offset + 1].toInt() and 0xFF)
        }
    }

    /**
     * Type 3 NDEF: service 0x000B, block 0 carries the NDEF TLV. The length in
     * the first block says how many more 16-byte blocks to read.
     */
    private fun readNdef(transport: NfcFTransport, idm: ByteArray): NdefContent {
        val service = byteArrayOf(0x0B, 0x00)
        // Request Service: LEN 0D, command, IDm, one node, the service code.
        val serviceResponse = transport.transceive(
            byteArrayOf(0x0D, 0x02) + idm + byteArrayOf(0x01) + service,
        )
        if (serviceResponse.size < 13) return NdefContent.absent
        if ((serviceResponse[1].toInt() and 0xFF) != 0x03) return NdefContent.absent
        if (serviceResponse[10].toInt() == 0) return NdefContent.absent

        val first = readBlock(transport, idm, service, 0) ?: return NdefContent.absent
        val total = tlvLength(first) ?: return NdefContent.absent
        if (total <= 0 || total > MAX_BLOCKS * BLOCK_SIZE) return NdefContent.absent

        val data = ByteArrayOutputStream()
        data.write(first)
        var block = 1
        while (data.size() < total && block < MAX_BLOCKS) {
            val next = readBlock(transport, idm, service, block)
                ?: return NdefContent(emptyList(), data.size(), NdefStatus.TRUNCATED)
            data.write(next)
            block++
        }
        return NdefParser.parseTlv(data.toByteArray())
    }

    private fun readBlock(
        transport: NfcFTransport,
        idm: ByteArray,
        service: ByteArray,
        block: Int,
    ): ByteArray? {
        val command = byteArrayOf(
            0x10,
            0x06,
        ) + idm + byteArrayOf(
            0x01,
        ) + service + byteArrayOf(
            0x01,
            (block ushr 8).toByte(),
            block.toByte(),
        )
        val response = transport.transceive(command)
        if (response.size < 13 + BLOCK_SIZE) return null
        if ((response[1].toInt() and 0xFF) != 0x07) return null
        if (response[10].toInt() != 0 || response[11].toInt() != 0) return null
        return response.copyOfRange(13, 13 + BLOCK_SIZE)
    }

    /** Length of the NDEF TLV (header included), null when absent. */
    private fun tlvLength(block: ByteArray): Int? {
        var position = 0
        while (position + 1 < block.size) {
            val type = block[position].toInt() and 0xFF
            if (type == 0x00) {
                position++
                continue
            }
            if (type == 0xFE) return null
            if (type == 0x03) {
                var length = block[position + 1].toInt() and 0xFF
                var header = 2
                if (length == 0xFF) {
                    if (position + 3 >= block.size) return null
                    length = ((block[position + 2].toInt() and 0xFF) shl 8) or
                        (block[position + 3].toInt() and 0xFF)
                    header = 4
                }
                return header + length
            }
            val length = block[position + 1].toInt() and 0xFF
            position += 2 + length
        }
        return null
    }

    /** LEN, command, IDm. LEN counts the whole frame including itself. */
    private fun frame(command: Int, idm: ByteArray): ByteArray =
        byteArrayOf((2 + idm.size).toByte(), command.toByte()) + idm

    private const val BLOCK_SIZE = 16
    private const val MAX_BLOCKS = 64
}
