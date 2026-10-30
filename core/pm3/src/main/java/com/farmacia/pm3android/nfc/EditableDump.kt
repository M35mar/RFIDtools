// Editable view over a .bin dump.
//
// The original file bytes are kept untouched: an edit only patches the block or
// page region in a copy, so saving never rebuilds the file (the 56-byte MFU
// header, version and signature stay exactly as the client wrote them).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.File

enum class DumpFamily { CLASSIC, ULTRALIGHT }

/** What a row of the dump represents on the card. */
enum class DumpRowRole {
    DATA,
    USER,
    TRAILER,
    UID,
    BCC,
    LOCK,
    OTP,
    CONFIG0,
    CONFIG1,
    PASSWORD,
    PACK,
}

/**
 * One editable row: a 16-byte block for MIFARE Classic or a 4-byte page for
 * Ultralight/NTAG. [writtenByRestore] tells the UI whether `restore` will write
 * it: the UID/manufacturer block, the static lock bits, the OTP and the lock
 * page are read-only on a real tag.
 */
data class DumpRow(
    val index: Int,
    val bytes: ByteArray,
    val sector: Int? = null,
    val role: DumpRowRole = DumpRowRole.DATA,
    val writtenByRestore: Boolean = true,
    /** Decoded lines shown under the hex, empty for plain data. */
    val details: List<String> = emptyList(),
) {
    val hex: String get() = bytes.toHexSpaced()

    val ascii: String get() = bytes.toAscii()

    val byteCount: Int get() = bytes.size
}

/**
 * Decoded view of a row. Everything is plain text and language-neutral: the
 * values are the ones printed by the Proxmark3 client (`UID`, `Key A`,
 * `Access`, `MOD`, ...), so they read the same in both app languages.
 */
object DumpRowDetails {

    fun describe(
        family: DumpFamily,
        index: Int,
        rowCount: Int,
        bytes: ByteArray,
    ): List<String> = when (family) {
        DumpFamily.CLASSIC -> classic(index, bytes)
        DumpFamily.ULTRALIGHT -> ultralight(index, rowCount, bytes)
    }

    /** MIFARE value block: value, ~value, value again, address and ~address. */
    fun isValueBlock(bytes: ByteArray): Boolean =
        bytes.size == MfcGeometry.BLOCK_SIZE && MfcValueBlock.isConsistent(bytes)

    private fun classic(index: Int, bytes: ByteArray): List<String> {
        if (bytes.size != MfcGeometry.BLOCK_SIZE) return emptyList()
        if (index == 0) {
            val uidBytes = if (bccMatches(bytes)) 4 else 7
            val out = mutableListOf("UID ${bytes.copyOfRange(0, uidBytes).toHex()}")
            val sak = uidBytes + 1
            if (sak + 1 < bytes.size) {
                out += "BCC ${bytes[uidBytes].hex()}" +
                    " · SAK ${bytes[sak].hex()}" +
                    " · ATQA ${bytes.copyOfRange(sak + 1, sak + 3).toHex()}"
            }
            return out
        }
        if (MfcGeometry.isTrailer(index)) return trailer(bytes)
        return valueBlock(bytes)
    }

    private fun bccMatches(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return true
        val bcc = (bytes[0].toInt() xor bytes[1].toInt() xor
            bytes[2].toInt() xor bytes[3].toInt()).toByte()
        return bcc == bytes[4]
    }

    private fun trailer(bytes: ByteArray): List<String> {
        val access = bytes.copyOfRange(6, 10)
        val out = mutableListOf("Key A ${bytes.copyOfRange(0, 6).toHex()}")
        val accessHex = access.copyOfRange(0, 3).toHexSpaced()
        if (MfcAccess.isValid(access)) {
            val conditions = (0..3).joinToString(" · ") { block ->
                val name = if (block == 3) "T" else "b$block"
                "$name ${MfcAccess.condition(access, block).toString(2).padStart(3, '0')}"
            }
            out += "Access $accessHex · $conditions"
        } else {
            out += "Access $accessHex · invalid"
        }
        out += "Key B ${bytes.copyOfRange(10, 16).toHex()} · GPB ${bytes[9].hex()}"
        return out
    }

    private fun valueBlock(bytes: ByteArray): List<String> {
        if (!isValueBlock(bytes)) return emptyList()
        var value = 0L
        for (offset in 0 until 4) {
            value = value or ((bytes[offset].toLong() and 0xFF) shl (offset * 8))
        }
        val address = (bytes[12].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        return listOf("value $value · addr 0x$address · checked")
    }

    private fun ultralight(index: Int, rowCount: Int, bytes: ByteArray): List<String> {
        if (bytes.size != 4 || rowCount < 8) return emptyList()
        val layout = MfuLayout(rowCount)
        return when (index) {
            2 -> listOf("lock ${bytes.copyOfRange(0, 2).toHexSpaced()}")
            3 -> listOf("OTP ${bytes.toHexSpaced()}")
            layout.lockPage -> listOf("lock ${bytes.toHexSpaced()}")
            layout.cfg0 -> listOf("MOD ${bytes[0].hex()} · AUTH0 ${bytes[3].hex()}")
            layout.cfg1 -> listOf("ACCESS ${bytes[0].hex()} · VCTID ${bytes[1].hex()}")
            layout.pwdPage -> listOf("PWD ${bytes.toHexSpaced()}")
            layout.packPage -> listOf("PACK ${bytes.copyOfRange(0, 2).toHexSpaced()}")
            else -> emptyList()
        }
    }

    private fun Byte.hex(): String = byteArrayOf(this).toHex()
}

class EditableDump private constructor(
    val file: File,
    val name: String,
    val family: DumpFamily,
    val label: String,
    val rowSize: Int,
    /** Byte length of the file header before the first row (0 for Classic/plain dumps). */
    val headerBytes: Int,
    private val original: ByteArray,
    private val data: ByteArray,
) {

    val rows: List<DumpRow> = when (family) {
        DumpFamily.CLASSIC -> buildClassicRows(data)
        DumpFamily.ULTRALIGHT -> buildUltralightRows(data, headerBytes)
    }

    /** Row indices whose bytes differ from the file on disk. */
    val modifiedRows: Set<Int> = rows.indices
        .filter { index -> !rowEquals(index, original) }
        .toSet()

    val dirty: Boolean get() = modifiedRows.isNotEmpty()

    val modifiedCount: Int get() = modifiedRows.size

    /** UID rebuilt from the current bytes, so editing it updates the summary. */
    val uidHex: String get() = when (family) {
        DumpFamily.CLASSIC -> classicUidHex(data)
        DumpFamily.ULTRALIGHT -> ultralightUidHex(data, headerBytes)
    }

    fun isModified(index: Int): Boolean = index in modifiedRows

    /**
     * Returns a new dump with [bytes] written at [index]. The byte count must
     * match the row exactly: rows cannot be resized.
     */
    fun edit(index: Int, bytes: ByteArray): EditableDump {
        val row = rows.getOrNull(index)
            ?: throw DumpFormatException("riga $index inesistente")
        require(bytes.size == rowSize) {
            "la riga $index richiede $rowSize byte, ricevuti ${bytes.size}"
        }
        val patched = data.copyOf()
        bytes.copyInto(patched, headerBytes + index * rowSize)
        return copy(data = patched)
    }

    /** The bytes to write back to [file]. */
    fun toBytes(): ByteArray = data.copyOf()

    /** Clears every edit, returning to the content on disk. */
    fun revert(): EditableDump = copy(data = original.copyOf())

    /** Marks the current content as the new baseline after a successful save. */
    fun saved(): EditableDump = copy(original = data.copyOf(), data = data.copyOf())

    private fun copy(
        original: ByteArray = this.original,
        data: ByteArray = this.data,
    ): EditableDump = EditableDump(
        file = file,
        name = name,
        family = family,
        label = label,
        rowSize = rowSize,
        headerBytes = headerBytes,
        original = original,
        data = data,
    )

    private fun rowEquals(index: Int, source: ByteArray): Boolean {
        val start = headerBytes + index * rowSize
        if (start + rowSize > source.size) return false
        for (offset in 0 until rowSize) {
            if (data[start + offset] != source[start + offset]) return false
        }
        return true
    }

    companion object {

        private const val BLOCK_BYTES = MfcGeometry.BLOCK_SIZE
        private const val PAGE_BYTES = 4

        /** Opens a .bin dump. Throws [DumpFormatException] for anything else. */
        fun load(file: File): EditableDump {
            if (!file.isFile) throw DumpFormatException("file non trovato: ${file.name}")
            if (!file.extension.equals("bin", ignoreCase = true)) {
                throw DumpFormatException("${file.name} non è un dump .bin")
            }
            val bytes = file.readBytes()
            if (bytes.isEmpty()) throw DumpFormatException("file vuoto")
            return when (val image = DumpLoader.load(file)) {
                is DumpImage.Classic -> EditableDump(
                    file = file,
                    name = file.name,
                    family = DumpFamily.CLASSIC,
                    label = image.dump.size.label,
                    rowSize = BLOCK_BYTES,
                    headerBytes = 0,
                    original = bytes,
                    data = bytes.copyOf(),
                )

                is DumpImage.Ultralight -> {
                    val offset = image.dump.headerBytes
                    if (offset < 0 || offset >= bytes.size) {
                        throw DumpFormatException("header del dump non riconosciuto")
                    }
                    EditableDump(
                        file = file,
                        name = file.name,
                        family = DumpFamily.ULTRALIGHT,
                        label = image.dump.layout.variant,
                        rowSize = PAGE_BYTES,
                        headerBytes = offset,
                        original = bytes,
                        data = bytes.copyOf(),
                    )
                }
            }
        }

        private fun buildClassicRows(data: ByteArray): List<DumpRow> {
            val blocks = data.size / BLOCK_BYTES
            return (0 until blocks).map { index ->
                val role = when {
                    index == 0 -> DumpRowRole.UID
                    MfcGeometry.isTrailer(index) -> DumpRowRole.TRAILER
                    else -> DumpRowRole.DATA
                }
                val bytes = data.copyOfRange(index * BLOCK_BYTES, (index + 1) * BLOCK_BYTES)
                DumpRow(
                    index = index,
                    bytes = bytes,
                    sector = MfcGeometry.sectorOfBlock(index),
                    role = role,
                    writtenByRestore = index != 0,
                    details = DumpRowDetails.describe(DumpFamily.CLASSIC, index, blocks, bytes),
                )
            }
        }

        private fun buildUltralightRows(data: ByteArray, offset: Int): List<DumpRow> {
            val pages = (data.size - offset) / PAGE_BYTES
            val layout = MfuLayout(pages)
            return (0 until pages).map { index ->
                val role = when (index) {
                    0 -> DumpRowRole.UID
                    1 -> DumpRowRole.BCC
                    2 -> DumpRowRole.LOCK
                    3 -> DumpRowRole.OTP
                    layout.lockPage -> DumpRowRole.LOCK
                    layout.cfg0 -> DumpRowRole.CONFIG0
                    layout.cfg1 -> DumpRowRole.CONFIG1
                    layout.pwdPage -> DumpRowRole.PASSWORD
                    layout.packPage -> DumpRowRole.PACK
                    else -> DumpRowRole.USER
                }
                val bytes = data.copyOfRange(
                    offset + index * PAGE_BYTES,
                    offset + (index + 1) * PAGE_BYTES,
                )
                DumpRow(
                    index = index,
                    bytes = bytes,
                    sector = null,
                    role = role,
                    writtenByRestore = index > 3 && index != layout.lockPage,
                    details = DumpRowDetails.describe(DumpFamily.ULTRALIGHT, index, pages, bytes),
                )
            }
        }

        private fun classicUidHex(data: ByteArray): String {
            if (data.size < 5) return ""
            val bcc = (data[0].toInt() xor data[1].toInt() xor
                data[2].toInt() xor data[3].toInt()).toByte()
            val length = if (bcc == data[4]) 4 else 7
            return data.copyOfRange(0, minOf(length, data.size)).toHex()
        }

        private fun ultralightUidHex(data: ByteArray, offset: Int): String {
            val pages = (data.size - offset) / PAGE_BYTES
            if (pages >= 2) {
                return (data.copyOfRange(offset, offset + 3) +
                    data.copyOfRange(offset + PAGE_BYTES, offset + 2 * PAGE_BYTES)).toHex()
            }
            return if (pages == 1) {
                data.copyOfRange(offset, offset + PAGE_BYTES).toHex()
            } else {
                ""
            }
        }
    }
}
