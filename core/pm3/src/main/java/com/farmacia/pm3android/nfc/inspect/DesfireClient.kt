// Minimal MIFARE DESFire client over ISO-DEP: the free commands only, which is
// what the inspector needs before any key is entered. Commands are wrapped in
// the ISO 7816 form (`90 INS 00 00 Lc data 00`) and multi-frame answers are
// drained with the card's additional-frame command.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.io.ByteArrayOutputStream

/** Anything that can carry wrapped APDUs, faked in the unit tests. */
interface IsoDepTransport {
    fun transceive(command: ByteArray): ByteArray
}

/** A DESFire status word the caller may want to inspect. */
class DesfireException(val status: Int, message: String) : Exception(message) {

    val statusByte: Int get() = status and 0xFF
}

/** Hardware block of a DESFire GetVersion answer, plus the software version. */
class DesfireVersion(
    val hardwareVendor: Int,
    val hardwareType: Int,
    val hardwareSubtype: Int,
    val hardwareMajor: Int,
    val hardwareMinor: Int,
    val storageCode: Int,
    val protocolCode: Int,
    val softwareMajor: Int,
    val softwareMinor: Int,
) {
    val isDesfire: Boolean get() = hardwareType == 0x01

    val vendorName: String get() = VersionInfo.vendorNameOf(hardwareVendor)

    val storageBytes: Int? get() = STORAGE_BYTES[storageCode]

    val generation: String
        get() = when (hardwareSubtype) {
            0x01 -> "EV1"
            0x02 -> "EV2"
            0x03 -> "EV3"
            else -> ""
        }

    val label: String
        get() = buildString {
            append("MIFARE DESFire")
            if (generation.isNotEmpty()) append(' ').append(generation)
            storageBytes?.let { append(' ').append(sizeLabel(it)) }
        }

    companion object {
        private val STORAGE_BYTES = mapOf(
            0x18 to 2048,
            0x19 to 4096,
            0x1A to 8192,
        )

        private fun sizeLabel(bytes: Int): String = when (bytes) {
            2048 -> "2 KB"
            4096 -> "4 KB"
            8192 -> "8 KB"
            else -> "$bytes B"
        }
    }
}

class DesfireClient(private val transport: IsoDepTransport) {

    /**
     * GetVersion (0x60). Free on every DESFire generation and the only reliable
     * way to tell EV1/EV2/EV3 and the memory size apart.
     */
    fun getVersion(): DesfireVersion? {
        val data = command(CMD_GET_VERSION)
        if (data.size < 14) return null
        return DesfireVersion(
            hardwareVendor = data[0].toInt() and 0xFF,
            hardwareType = data[1].toInt() and 0xFF,
            hardwareSubtype = data[2].toInt() and 0xFF,
            hardwareMajor = data[3].toInt() and 0xFF,
            hardwareMinor = data[4].toInt() and 0xFF,
            storageCode = data[5].toInt() and 0xFF,
            protocolCode = data[6].toInt() and 0xFF,
            softwareMajor = data[10].toInt() and 0xFF,
            softwareMinor = data[11].toInt() and 0xFF,
        )
    }

    /**
     * GetApplicationIDs (0x6A): the only free filesystem call. A card with no
     * application answers with an empty list.
     */
    fun getApplicationIds(): List<Int> = idsFrom(command(CMD_GET_APPLICATION_IDS))

    /**
     * GetDFNames (0x6D), EV2 and later. Older cards answer "illegal command",
     * which the caller handles with [runCatching].
     */
    fun getDfNames(): List<Int> = idsFrom(command(CMD_GET_DF_NAMES))

    /**
     * GetCardUID (0x51). Needs an authenticated session, so an unauthenticated
     * call throws [DesfireException] and the caller keeps Android's UID.
     */
    fun getCardUid(): ByteArray = command(CMD_GET_CARD_UID)

    private fun idsFrom(data: ByteArray): List<Int> {
        if (data.isEmpty() || data.size % 3 != 0) return emptyList()
        return (0 until data.size step 3).map { index ->
            ((data[index].toInt() and 0xFF) shl 16) or
                ((data[index + 1].toInt() and 0xFF) shl 8) or
                (data[index + 2].toInt() and 0xFF)
        }
    }

    private fun command(ins: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArrayOutputStream()
        var response = send(ins, data)
        var status = statusOf(response)
        body.write(response, 0, response.size - 2)
        var frames = 1
        while (status == STATUS_ADDITIONAL_FRAME) {
            if (++frames > MAX_FRAMES) {
                throw DesfireException(status, "DESFire: troppe risposte parziali")
            }
            response = send(CMD_ADDITIONAL_FRAME, ByteArray(0))
            status = statusOf(response)
            body.write(response, 0, response.size - 2)
        }
        if (status != STATUS_OK) {
            throw DesfireException(
                status,
                "DESFire: stato 0x%02X".format(status and 0xFF),
            )
        }
        return body.toByteArray()
    }

    private fun send(ins: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(5 + data.size + 1)
        frame[0] = 0x90.toByte()
        frame[1] = ins.toByte()
        frame[4] = data.size.toByte()
        data.copyInto(frame, 5)
        return transport.transceive(frame)
    }

    private fun statusOf(response: ByteArray): Int {
        if (response.size < 2) {
            throw DesfireException(0, "DESFire: risposta di ${response.size} byte")
        }
        return ((response[response.size - 2].toInt() and 0xFF) shl 8) or
            (response[response.size - 1].toInt() and 0xFF)
    }

    private companion object {
        const val CMD_GET_VERSION = 0x60
        const val CMD_GET_CARD_UID = 0x51
        const val CMD_GET_APPLICATION_IDS = 0x6A
        const val CMD_GET_DF_NAMES = 0x6D
        const val CMD_ADDITIONAL_FRAME = 0xAF

        const val STATUS_OK = 0x9100
        const val STATUS_ADDITIONAL_FRAME = 0x91AF

        const val MAX_FRAMES = 32
    }
}
