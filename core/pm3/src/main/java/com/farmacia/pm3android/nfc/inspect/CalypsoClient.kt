// Calypso client over ISO-DEP: application selection, FCI and the ICC
// manufacturing record, all readable without keys. The APDU shapes follow the
// Calypso reference implementation in the Proxmark3 client: CLA 0x94 on
// ISO 14443-B (without Le) and CLA 0x00 elsewhere, with a fallback on the other
// class byte when the card answers "class not supported".
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.toHex
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** An APDU status word the caller may want to inspect. */
class CalypsoException(val sw: Int, message: String) : Exception(message)

private const val CLA_ISO_A = 0x00
private const val CLA_ISO_B = 0x94
private const val INS_SELECT = 0xA4
private const val INS_READ_RECORD = 0xB2

private const val SFI_ICC = 0x02
private const val ICC_FILE_ID = 0x0002
private const val RECORD_ICC = 0x01
private const val ICC_LENGTH = 29

private const val SFI_ENVIRONMENT = 0x07
private const val TICKET_DIR_ID = 0x2000
private const val ENVIRONMENT_FILE_ID = 0x2001
private const val LEGACY_RECORD_LEN = 0x1D

private class CalypsoFreeFileSpec(
    val kind: CalypsoFileKind,
    val sfi: Int,
    val maxRecords: Int,
)

/** Standard ticket files and the record count of the usual layout. */
private val CALYPSO_FREE_FILES = listOf(
    CalypsoFreeFileSpec(CalypsoFileKind.EVENTS_LOG, 0x08, 6),
    CalypsoFreeFileSpec(CalypsoFileKind.CONTRACTS, 0x09, 8),
    CalypsoFreeFileSpec(CalypsoFileKind.COUNTERS, 0x19, 1),
    CalypsoFreeFileSpec(CalypsoFileKind.SPECIAL_EVENTS, 0x1D, 4),
    CalypsoFreeFileSpec(CalypsoFileKind.CONTRACT_LIST, 0x1E, 1),
)

private const val SW_OK = 0x9000
private const val SW_INVALIDATED = 0x6283
private const val SW_WRONG_LENGTH = 0x6700
private const val SW_CLA_NOT_SUPPORTED = 0x6E00
private const val SW_INS_NOT_SUPPORTED = 0x6D00

class CalypsoResponse(val data: ByteArray, val sw: Int) {
    val ok: Boolean get() = sw == SW_OK || sw == SW_INVALIDATED
}

/** Result of a SELECT by DF name, with the FCI fields already decoded. */
class CalypsoSelection(
    val cla: Int,
    val sw: Int,
    val fci: ByteArray,
    val dfName: ByteArray?,
    val serial: ByteArray?,
    val startup: CalypsoStartup?,
) {
    val selected: Boolean get() = sw == SW_OK || sw == SW_INVALIDATED
}

class CalypsoClient(
    private val transport: IsoDepTransport,
    private val iso14443B: Boolean = false,
) {

    /** CLA the card answered on, valid after a successful SELECT. */
    var workingCla: Int = if (iso14443B) CLA_ISO_B else CLA_ISO_A
        private set

    /**
     * SELECT by DF name (INS 0xA4, P1 0x04). The AID may be a prefix: Calypso
     * cards accept their RID, which is how an unknown network is recognised.
     */
    fun select(dfName: ByteArray): CalypsoSelection {
        val preferred = if (iso14443B) CLA_ISO_B else CLA_ISO_A
        val alternates = listOf(preferred, if (iso14443B) CLA_ISO_A else CLA_ISO_B)
        var last: CalypsoResponse? = null
        for (cla in alternates) {
            // ISO 14443-B Prime cards reject the trailing Le on SELECT.
            val response = exchange(cla, INS_SELECT, 0x04, 0x00, dfName, if (cla == CLA_ISO_B) null else 0x00)
            last = response
            if (response.sw == SW_INS_NOT_SUPPORTED || response.sw == SW_CLA_NOT_SUPPORTED) continue
            workingCla = cla
            val parsed = CalypsoFci.parse(response.data)
            return CalypsoSelection(
                cla = cla,
                sw = response.sw,
                fci = response.data,
                dfName = parsed.dfName ?: dfName,
                serial = parsed.serial,
                startup = parsed.startup,
            )
        }
        val response = last ?: throw CalypsoException(0, "Calypso: nessuna risposta")
        return CalypsoSelection(
            cla = workingCla,
            sw = response.sw,
            fci = response.data,
            dfName = dfName,
            serial = null,
            startup = null,
        )
    }

    /**
     * Selects the master file. Some cards only expose the ICC record there, so
     * the probe tries it after the application itself.
     */
    fun selectMasterFile(): Int = exchange(workingCla, INS_SELECT, 0x09, 0x00, ByteArray(0), le()).sw

    /** Reads EF 0x0002 record 1, the 29-byte manufacturing record. */
    fun readIcc(): CalypsoIcc? {
        val direct = readSfiRecord(SFI_ICC, RECORD_ICC, ICC_LENGTH)
        direct?.let { return CalypsoIccParser.parse(it) }

        // Fallback: select the file by id, then read the current record.
        if (selectFile(ICC_FILE_ID)) {
            val fallback = readCurrentRecord(RECORD_ICC, ICC_LENGTH)
            fallback?.let { return CalypsoIccParser.parse(it) }
        }
        return null
    }

    /**
     * Reads the ticketing environment (SFI 0x07, record 1), one of the few
     * records usually left readable without a secure session. The fallback
     * selects the ticket directory and the environment file by id.
     */
    fun readEnvironment(): CalypsoEnvironment? {
        val direct = readSfiRecord(SFI_ENVIRONMENT, 0x01, 0x00)
            ?: readSfiRecord(SFI_ENVIRONMENT, 0x01, LEGACY_RECORD_LEN)
        direct?.let { return CalypsoEnvironmentParser.parse(it) }

        if (selectFile(TICKET_DIR_ID) && selectFile(ENVIRONMENT_FILE_ID)) {
            val fallback = readCurrentRecord(0x01, 0x00)
                ?: readCurrentRecord(0x01, LEGACY_RECORD_LEN)
            fallback?.let { return CalypsoEnvironmentParser.parse(it) }
        }
        return null
    }

    /**
     * Reads the other standard ticket files that some cards leave readable
     * without a secure session. Each file stops at the first record the card
     * refuses, so a protected file does not stop the following ones.
     */
    fun readFreeFiles(): List<CalypsoFile> {
        val files = mutableListOf<CalypsoFile>()
        for (spec in CALYPSO_FREE_FILES) {
            val records = mutableListOf<ByteArray>()
            for (record in 1..spec.maxRecords) {
                val data = readSfiRecord(spec.sfi, record, 0x00) ?: break
                if (data.isEmpty()) break
                records += data
            }
            if (records.isNotEmpty()) {
                files += CalypsoFile(kind = spec.kind, sfi = spec.sfi, records = records)
            }
        }
        return files
    }

    private fun selectFile(fileId: Int): Boolean {
        val data = byteArrayOf((fileId shr 8).toByte(), fileId.toByte())
        // ISO SELECT FILE by file id: P1/P2 are both zero.
        val response = exchange(workingCla, INS_SELECT, 0x00, 0x00, data, le())
        return response.ok
    }

    private fun readSfiRecord(sfi: Int, record: Int, length: Int): ByteArray? {
        val response = exchange(
            workingCla,
            INS_READ_RECORD,
            record,
            (sfi shl 3) or 0x04,
            ByteArray(0),
            length,
        )
        return response.data.takeIf { response.ok && it.isNotEmpty() }
    }

    private fun readCurrentRecord(record: Int, length: Int): ByteArray? {
        val response = exchange(workingCla, INS_READ_RECORD, record, 0x04, ByteArray(0), length)
        return response.data.takeIf { response.ok && it.isNotEmpty() }
    }

    /** Le of the profile: 256 on ISO 14443-A, omitted on ISO 14443-B. */
    private fun le(): Int? = if (iso14443B) null else 0x00

    private fun exchange(
        cla: Int,
        ins: Int,
        p1: Int,
        p2: Int,
        data: ByteArray,
        le: Int?,
    ): CalypsoResponse {
        var currentLe = le
        var attempt = 0
        while (true) {
            val response = send(cla, ins, p1, p2, data, currentLe)
            val status = response.sw and 0xFF00
            if (status == 0x6C00 && attempt < 3) {
                // Wrong Le: the card tells the exact length.
                currentLe = response.sw and 0xFF
                attempt++
                continue
            }
            if (response.sw == SW_WRONG_LENGTH && attempt < 3) {
                // Wrong length: toggle the presence of Le, like the client does.
                currentLe = if (currentLe == null) 0x00 else null
                attempt++
                continue
            }
            return response
        }
    }

    private fun send(
        cla: Int,
        ins: Int,
        p1: Int,
        p2: Int,
        data: ByteArray,
        le: Int?,
    ): CalypsoResponse {
        val body = ByteArrayOutputStream()
        body.write(cla)
        body.write(ins)
        body.write(p1)
        body.write(p2)
        if (data.isNotEmpty()) {
            body.write(data.size)
            body.write(data)
        }
        if (le != null) body.write(le and 0xFF)
        val answer = transport.transceive(body.toByteArray())
        if (answer.size < 2) throw CalypsoException(0, "Calypso: risposta di ${answer.size} byte")
        val sw = ((answer[answer.size - 2].toInt() and 0xFF) shl 8) or
            (answer[answer.size - 1].toInt() and 0xFF)
        return CalypsoResponse(answer.copyOfRange(0, answer.size - 2), sw)
    }
}

/** The three FCI fields the inspector needs. */
class CalypsoFci(
    val dfName: ByteArray?,
    val serial: ByteArray?,
    val startup: CalypsoStartup?,
) {
    companion object {
        fun parse(fci: ByteArray): CalypsoFci {
            val dfName = BerTlv.find(fci, 0x84)?.takeIf { it.isNotEmpty() }
            val serial = BerTlv.find(fci, 0xC7)?.takeIf { it.size == 8 }
            val startupBytes = BerTlv.find(fci, 0x53)
            val startup = startupBytes?.takeIf { it.size == 7 }?.let { bytes ->
                CalypsoStartup(
                    sessionModifications = bytes[0].toInt() and 0xFF,
                    platform = bytes[1].toInt() and 0xFF,
                    applicationType = bytes[2].toInt() and 0xFF,
                    applicationSubtype = bytes[3].toInt() and 0xFF,
                    softwareIssuer = bytes[4].toInt() and 0xFF,
                    softwareVersion = bytes[5].toInt() and 0xFF,
                    softwareRevision = bytes[6].toInt() and 0xFF,
                )
            }
            return CalypsoFci(dfName = dfName, serial = serial, startup = startup)
        }
    }
}

/** Minimal BER-TLV reader: short tags, constructed templates unwrapped. */
object BerTlv {

    fun find(data: ByteArray, wanted: Int): ByteArray? {
        var index = 0
        while (index < data.size) {
            val first = data[index].toInt() and 0xFF
            val hasMoreTagBytes = first and 0x1F == 0x1F
            index++
            if (hasMoreTagBytes) {
                while (index < data.size && data[index].toInt() and 0x80 != 0) index++
                index++
            }
            if (index >= data.size) return null
            val lengthByte = data[index].toInt() and 0xFF
            index++
            var length = lengthByte
            if (lengthByte == 0x81) {
                if (index >= data.size) return null
                length = data[index].toInt() and 0xFF
                index++
            } else if (lengthByte == 0x82) {
                if (index + 1 >= data.size) return null
                length = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
                index += 2
            } else if (lengthByte and 0x80 != 0) {
                return null
            }
            if (index + length > data.size) return null
            val valueStart = index
            val value = data.copyOfRange(valueStart, valueStart + length)
            val tag = if (hasMoreTagBytes) {
                // Only the single-byte tags used by Calypso FCI matter here.
                -1
            } else {
                first
            }
            if (tag == wanted) return value
            val constructed = first and 0x20 != 0
            if (constructed) {
                find(value, wanted)?.let { return it }
            }
            index = valueStart + length
        }
        return null
    }
}

/** Decodes the ICC record: serial, country, manufacturer, date and check byte. */
object CalypsoIccParser {

    fun parse(raw: ByteArray): CalypsoIcc? {
        if (raw.size < ICC_LENGTH) return null
        val record = raw.copyOfRange(0, ICC_LENGTH)
        if (record.all { it == 0.toByte() }) return null

        val serial = record.copyOfRange(0x0C, 0x14)
        val country = bcdCountry(record[0x14].toInt() and 0xFF, record[0x15].toInt() and 0xFF)
        val days = ((record[0x19].toInt() and 0xFF) shl 8) or (record[0x1A].toInt() and 0xFF)
        val check = record[0x1B].toInt() and 0xFF
        val checkValid = check == checkValue(record, 0x10, 0x1B) ||
            check == checkValue(record, 0x00, 0x1B)

        return CalypsoIcc(
            raw = record,
            serial = serial,
            countryCode = country,
            manufacturer = record[0x16].toInt() and 0xFF,
            initializationDate = runCatching { EPOCH.plusDays(days.toLong()) }.getOrNull(),
            check = check,
            checkValid = checkValid,
        )
    }

    private fun bcdCountry(high: Int, low: Int): Int? {
        val nibbles = intArrayOf(high shr 4, high and 0x0F, low shr 4, low and 0x0F)
        if (nibbles.any { it > 9 }) return null
        return nibbles[1] * 100 + nibbles[2] * 10 + nibbles[3]
    }

    /** Calypso check byte: 5 plus the number of zero bits in the range. */
    private fun checkValue(data: ByteArray, from: Int, to: Int): Int {
        var zeroBits = 0
        for (index in from until to) {
            zeroBits += 8 - Integer.bitCount(data[index].toInt() and 0xFF)
        }
        return (5 + zeroBits) and 0xFF
    }

    private const val ICC_LENGTH = 29
    private val EPOCH: LocalDate = LocalDate.of(1990, 1, 1)
}

/**
 * Decodes the first bytes of the environment record: a six-bit version and the
 * 24-bit network identifier that starts at bit 13, as the Proxmark3 client does.
 */
object CalypsoEnvironmentParser {

    fun parse(raw: ByteArray): CalypsoEnvironment? {
        if (raw.isEmpty() || raw.all { it == 0.toByte() }) return null
        val version = bits(raw, 0, 6)
        val network = bits(raw, 13, 24)
        if (version == null && network == null) return null
        return CalypsoEnvironment(raw = raw.copyOf(), version = version, networkId = network)
    }

    /** Big-endian bit extraction, the first byte being the most significant. */
    private fun bits(data: ByteArray, offset: Int, length: Int): Int? {
        val total = data.size * 8
        if (length <= 0 || length > 32 || offset < 0 || offset > total - length) return null
        var value = 0
        for (index in 0 until length) {
            val position = offset + index
            val bit = (data[position / 8].toInt() shr (7 - position % 8)) and 0x01
            value = (value shl 1) or bit
        }
        return value
    }
}

/** Decodes the counters file: 24-bit big-endian values, three bytes each. */
object CalypsoFileParser {

    fun counterValues(record: ByteArray): List<Int> =
        (0 until record.size / 3).map { index ->
            val at = index * 3
            ((record[at].toInt() and 0xFF) shl 16) or
                ((record[at + 1].toInt() and 0xFF) shl 8) or
                (record[at + 2].toInt() and 0xFF)
        }
}

/**
 * Tries every known Calypso AID until one selects, then reads FCI and ICC.
 * Unknown networks still work: the RID selects the application and the DF name
 * is shown raw.
 */
object CalypsoProbe {

    fun read(
        client: CalypsoClient,
        abort: (Throwable) -> Boolean = { false },
    ): CalypsoDetails? {
        for (candidate in AidCatalog.calypsoCandidates()) {
            val selection = try {
                client.select(candidate)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                continue
            }
            if (!selection.selected) continue
            val dfName = selection.dfName ?: candidate
            return build(client, selection, dfName, abort)
        }
        return null
    }

    private fun build(
        client: CalypsoClient,
        selection: CalypsoSelection,
        dfName: ByteArray,
        abort: (Throwable) -> Boolean,
    ): CalypsoDetails {
        val icc = try {
            client.readIcc()
        } catch (error: Throwable) {
            if (abort(error)) throw error
            null
        }
        val environment = try {
            client.readEnvironment()
        } catch (error: Throwable) {
            if (abort(error)) throw error
            null
        }
        val files = try {
            client.readFreeFiles()
        } catch (error: Throwable) {
            if (abort(error)) throw error
            emptyList()
        }
        return CalypsoDetails(
            dfName = dfName,
            label = AidCatalog.calypsoLabel(dfName),
            serial = selection.serial ?: icc?.serial,
            startup = selection.startup,
            icc = icc,
            environment = environment,
            files = files,
            cla = selection.cla,
            iso14443B = selection.cla == 0x94,
        )
    }
}
