// Minimal MIFARE DESFire client over ISO-DEP: the free commands only, which is
// what the inspector needs before any key is entered. Commands are wrapped in
// the ISO 7816 form (`90 INS 00 00 Lc data 00`) and multi-frame answers are
// drained with the card's additional-frame command.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.toHex
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
            0x1B to 16384,
        )

        private fun sizeLabel(bytes: Int): String = when (bytes) {
            2048 -> "2 KB"
            4096 -> "4 KB"
            8192 -> "8 KB"
            16384 -> "16 KB"
            else -> "$bytes B"
        }
    }
}

/** Key settings of a DESFire level: settings byte plus the number of keys. */
class DesfireKeySettings(val settings: Int, val rawKeyCount: Int) {

    /** 1..16, the way GetKeySettings encodes the highest key number. */
    val keyCount: Int get() = (rawKeyCount and 0x0F) + 1

    /** Bit 1: the directory commands need a key before answering. */
    val directoryLocked: Boolean get() = settings and 0x02 != 0
}

/** One DF name reported by GetDFNames. */
class DesfireDfName(val applicationId: Int, val name: ByteArray?) {
    val nameHex: String? get() = name?.toHex()
}

/** Raw answer of one native DESFire frame: data plus status word. */
class DesfireFrame(val data: ByteArray, val status: Int) {
    val ok: Boolean get() = status == 0x9100
    val additional: Boolean get() = status == 0x91AF
    val authError: Boolean get() = (status and 0xFF) in AUTH_STATUS_BYTES
}

private val AUTH_STATUS_BYTES = setOf(0xAE, 0x9D, 0xDE)

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
     * which the caller handles with [runCatching]. The answer is a sequence of
     * three-byte application ids, a length byte and the DF name.
     */
    fun getDfNames(): List<DesfireDfName> {
        val data = command(CMD_GET_DF_NAMES)
        val out = mutableListOf<DesfireDfName>()
        var index = 0
        while (index + 4 <= data.size) {
            val id = (data[index].toInt() and 0xFF) or
                ((data[index + 1].toInt() and 0xFF) shl 8) or
                ((data[index + 2].toInt() and 0xFF) shl 16)
            val length = data[index + 3].toInt() and 0xFF
            index += 4
            if (length > 16 || index + length > data.size) break
            val name = if (length == 0) null else data.copyOfRange(index, index + length)
            index += length
            out += DesfireDfName(id, name)
        }
        return out
    }

    /** GetCardUID (0x51). Needs an authenticated session, so an unauthenticated
     * call throws [DesfireException] and the caller keeps Android's UID.
     */
    fun getCardUid(): ByteArray = command(CMD_GET_CARD_UID)

    /**
     * Sends one native frame and returns data plus status without draining the
     * additional frames: the authentication handshake needs the raw 0x91AF.
     */
    fun exchange(ins: Int, data: ByteArray = ByteArray(0)): DesfireFrame {
        val response = send(ins, data)
        if (response.size < 2) {
            throw DesfireException(0, "DESFire: risposta di ${response.size} byte")
        }
        return DesfireFrame(
            data = response.copyOfRange(0, response.size - 2),
            status = statusOf(response),
        )
    }

    /** GetFreeMemory (0x6E): three bytes, free space of the selected level. */
    fun getFreeMemory(): Int {
        val data = command(CMD_GET_FREE_MEMORY)
        if (data.size < 3) {
            throw DesfireException(0, "DESFire: memoria libera di ${data.size} byte")
        }
        return ((data[0].toInt() and 0xFF) shl 16) or
            ((data[1].toInt() and 0xFF) shl 8) or
            (data[2].toInt() and 0xFF)
    }

    /** GetKeySettings (0x45): settings byte and highest key number. */
    fun getKeySettings(): DesfireKeySettings {
        val data = command(CMD_GET_KEY_SETTINGS)
        if (data.size < 2) {
            throw DesfireException(0, "DESFire: key settings di ${data.size} byte")
        }
        return DesfireKeySettings(
            settings = data[0].toInt() and 0xFF,
            rawKeyCount = data[1].toInt() and 0xFF,
        )
    }

    /** GetKeyVersion (0x64) for one key number. */
    fun getKeyVersion(keyNo: Int): Int {
        val data = command(CMD_GET_KEY_VERSION, byteArrayOf(keyNo.toByte()))
        if (data.isEmpty()) throw DesfireException(0, "DESFire: versione chiave vuota")
        return data[0].toInt() and 0xFF
    }

    /** GetFileIDs (0x6F) of the selected application. */
    fun getFileIds(): List<Int> = command(CMD_GET_FILE_IDS).map { it.toInt() and 0xFF }

    /** GetISOFileIDs (0x61): pairs of 16-bit ISO id and 8-bit file id. */
    fun getIsoFileIds(): List<DesfireIsoFile> {
        val data = command(CMD_GET_ISO_FILE_IDS)
        if (data.size % 3 != 0) return emptyList()
        return (0 until data.size step 3).map { index ->
            val iso = ((data[index].toInt() and 0xFF) shl 8) or
                (data[index + 1].toInt() and 0xFF)
            DesfireIsoFile(fileId = data[index + 2].toInt() and 0xFF, isoId = iso)
        }
    }

    /** GetFileSettings (0xF5) of one file: type, rights and geometry. */
    fun getFileSettings(fileId: Int): DesfireFile {
        val data = command(CMD_GET_FILE_SETTINGS, byteArrayOf(fileId.toByte()))
        if (data.size < 4) {
            throw DesfireException(0, "DESFire: impostazioni file di ${data.size} byte")
        }
        val type = when (data[0].toInt() and 0xFF) {
            0x00 -> DesfireFileType.STANDARD_DATA
            0x01 -> DesfireFileType.BACKUP_DATA
            0x02 -> DesfireFileType.VALUE
            0x03 -> DesfireFileType.LINEAR_RECORD
            0x04 -> DesfireFileType.CYCLIC_RECORD
            else -> DesfireFileType.UNKNOWN
        }
        val rights = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        return when (type) {
            DesfireFileType.VALUE -> DesfireFile(
                id = fileId,
                type = type,
                communication = data[1].toInt() and 0xFF,
                accessRights = rights,
                lowerLimit = int32At(data, 4),
                upperLimit = int32At(data, 8),
            )

            DesfireFileType.LINEAR_RECORD, DesfireFileType.CYCLIC_RECORD -> DesfireFile(
                id = fileId,
                type = type,
                communication = data[1].toInt() and 0xFF,
                accessRights = rights,
                recordSize = intAt(data, 4),
                recordCount = intAt(data, 7),
            )

            else -> DesfireFile(
                id = fileId,
                type = type,
                communication = data[1].toInt() and 0xFF,
                accessRights = rights,
                sizeBytes = intAt(data, 4),
            )
        }
    }

    /** SelectApplication (0x5A): 0 is the PICC level. */
    fun selectApplication(applicationId: Int) {
        command(
            CMD_SELECT_APPLICATION,
            byteArrayOf(
                (applicationId and 0xFF).toByte(),
                ((applicationId shr 8) and 0xFF).toByte(),
                ((applicationId shr 16) and 0xFF).toByte(),
            ),
        )
    }

    /** True when the card wants a key before answering the requested command. */
    fun isAuthenticationError(error: Throwable): Boolean = when (error) {
        is DesfireException -> error.statusByte in AUTH_STATUSES
        else -> false
    }

    private fun intAt(data: ByteArray, offset: Int): Int? {
        if (offset + 3 > data.size) return null
        return ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
    }

    /** Signed 32-bit value used by the lower/upper limit of a value file. */
    private fun int32At(data: ByteArray, offset: Int): Int? {
        if (offset + 4 > data.size) return null
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun idsFrom(data: ByteArray): List<Int> {
        if (data.isEmpty() || data.size % 3 != 0) return emptyList()
        // DESFire application ids are stored least significant byte first.
        return (0 until data.size step 3).map { index ->
            (data[index].toInt() and 0xFF) or
                ((data[index + 1].toInt() and 0xFF) shl 8) or
                ((data[index + 2].toInt() and 0xFF) shl 16)
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
        const val CMD_GET_FREE_MEMORY = 0x6E
        const val CMD_GET_KEY_SETTINGS = 0x45
        const val CMD_GET_KEY_VERSION = 0x64
        const val CMD_GET_FILE_IDS = 0x6F
        const val CMD_GET_ISO_FILE_IDS = 0x61
        const val CMD_GET_FILE_SETTINGS = 0xF5
        const val CMD_SELECT_APPLICATION = 0x5A
        const val CMD_ADDITIONAL_FRAME = 0xAF

        const val STATUS_OK = 0x9100
        const val STATUS_ADDITIONAL_FRAME = 0x91AF

        /** 0xAE authentication error, 0x9D permission denied, 0xDE no such key. */
        val AUTH_STATUSES = setOf(0xAE, 0x9D, 0xDE)

        const val MAX_FRAMES = 32
    }
}
