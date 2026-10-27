// In-memory model of the dump files produced by the Proxmark3 client, plus the
// geometry needed to walk them. Everything here is pure Kotlin so the format
// handling can be unit-tested without a phone or a tag.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.File

/** A file that is not a dump we can write to a tag. */
class DumpFormatException(message: String) : Exception(message)

private const val HEX_DIGITS = "0123456789ABCDEF"

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xFF
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0F])
    }
}

fun ByteArray.toHexSpaced(): String = buildString(size * 3) {
    // `indices` inside buildString would resolve to CharSequence.indices on the
    // (empty) StringBuilder, so the receiver is spelled out.
    this@toHexSpaced.forEachIndexed { index, byte ->
        if (index > 0) append(' ')
        val value = byte.toInt() and 0xFF
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0F])
    }
}

/** Printable rendering of the bytes: non-ASCII characters become dots. */
fun ByteArray.toAscii(): String = buildString(size) {
    for (byte in this@toAscii) {
        val value = byte.toInt() and 0xFF
        append(if (value in 0x20..0x7E) value.toChar() else '.')
    }
}

/** Returns the bytes of [this] parsed as hex, or null when the text is not hex. */
fun String.hexToBytesOrNull(): ByteArray? {
    val compact = filterNot { it.isWhitespace() || it == ':' || it == '-' }
    if (compact.isEmpty() || compact.length % 2 != 0) return null
    val out = ByteArray(compact.length / 2)
    for (index in out.indices) {
        val high = compact[index * 2].digitToIntOrNull(16) ?: return null
        val low = compact[index * 2 + 1].digitToIntOrNull(16) ?: return null
        out[index] = ((high shl 4) or low).toByte()
    }
    return out
}

private fun String.isHex(): Boolean = all { it.digitToIntOrNull(16) != null }

// ---------------------------------------------------------------------------
// MIFARE Classic
// ---------------------------------------------------------------------------

object MfcGeometry {

    const val BLOCK_SIZE = 16

    /** Sectors 0..31 hold four blocks, sectors 32..39 hold sixteen. */
    fun blocksInSector(sector: Int): Int = if (sector < 32) 4 else 16

    fun firstBlockOfSector(sector: Int): Int =
        if (sector < 32) sector * 4 else 32 * 4 + (sector - 32) * 16

    fun sectorOfBlock(block: Int): Int =
        if (block < 128) block / 4 else 32 + (block - 128) / 16

    /** The last block of a sector holds key A, the access bits and key B. */
    fun isTrailer(block: Int): Boolean {
        val sector = sectorOfBlock(block)
        return block == firstBlockOfSector(sector) + blocksInSector(sector) - 1
    }
}

/** Card sizes the app can restore. `bytes` drives the dump-file detection. */
enum class MfcSize(val sectors: Int, val blocks: Int, val label: String) {
    MINI(5, 20, "MIFARE Mini"),
    CLASSIC_1K(16, 64, "MIFARE Classic 1K"),
    CLASSIC_2K(32, 128, "MIFARE Classic 2K"),
    CLASSIC_4K(40, 256, "MIFARE Classic 4K");

    val bytes: Int get() = blocks * MfcGeometry.BLOCK_SIZE

    companion object {
        fun fromBytes(size: Int): MfcSize? = entries.firstOrNull { it.bytes == size }

        fun fromBlocks(blocks: Int): MfcSize? = entries.firstOrNull { it.blocks == blocks }
    }
}

/** Six bytes per sector, split in a key-A array and a key-B array. */
class MfcKeys(
    private val keyA: List<ByteArray>,
    private val keyB: List<ByteArray>,
    val sourceName: String,
    /**
     * Keys without a sector, from a flat key file. They are tried as
     * candidates by the reader and the restorer, never written blindly.
     */
    val candidates: List<ByteArray> = emptyList(),
) {
    val sectors: Int get() = maxOf(keyA.size, keyB.size)

    fun keyAFor(sector: Int): ByteArray? = keyA.getOrNull(sector)

    fun keyBFor(sector: Int): ByteArray? = keyB.getOrNull(sector)

    fun isComplete(size: MfcSize): Boolean =
        keyA.size >= size.sectors || keyB.size >= size.sectors

    /**
     * Every six-byte key this set knows, sector-bound or flat, without zeroes.
     * Used to build the MCT-style key map: the keys are candidates for any
     * sector, never written into a trailer unless they come from the dump.
     */
    fun allKeys(): List<ByteArray> = buildList {
        addAll(keyA)
        addAll(keyB)
        addAll(candidates)
    }.filter { key ->
        key.size == 6 && key.any { it != 0.toByte() }
    }

    companion object {
        val EMPTY = MfcKeys(emptyList(), emptyList(), "")
    }
}

class MfcDump(
    val name: String,
    val size: MfcSize,
    val data: ByteArray,
    val keys: MfcKeys? = null,
) {
    init {
        require(data.size == size.bytes) {
            "dump has ${data.size} bytes, ${size.label} needs ${size.bytes}"
        }
    }

    fun block(index: Int): ByteArray =
        data.copyOfRange(index * MfcGeometry.BLOCK_SIZE, (index + 1) * MfcGeometry.BLOCK_SIZE)

    fun trailer(sector: Int): ByteArray = block(
        MfcGeometry.firstBlockOfSector(sector) + MfcGeometry.blocksInSector(sector) - 1,
    )

    fun trailerKeyA(sector: Int): ByteArray = trailer(sector).copyOfRange(0, 6)

    fun trailerKeyB(sector: Int): ByteArray = trailer(sector).copyOfRange(10, 16)

    fun trailerAccessBytes(sector: Int): ByteArray = trailer(sector).copyOfRange(6, 10)

    /**
     * True when every sector trailer stores both keys, so the restore can take
     * them from the dump even without the sibling `-key.bin`. The phone reader
     * writes them back because a tag never returns Key A.
     */
    fun keysInTrailers(): Boolean =
        (0 until size.sectors).all { sector ->
            trailerKeyA(sector).any { it != 0.toByte() } &&
                trailerKeyB(sector).any { it != 0.toByte() }
        }

    /**
     * UID encoded in block 0. The manufacturer block is never written, but the
     * UID is useful in the UI to pair a dump with its key file. A four-byte UID
     * is followed by its BCC, so the BCC tells the two layouts apart.
     */
    val uid: ByteArray
        get() {
            val b = block(0)
            val bcc = (b[0].toInt() xor b[1].toInt() xor b[2].toInt() xor b[3].toInt()).toByte()
            return if (bcc == b[4]) b.copyOfRange(0, 4) else b.copyOfRange(0, 7)
        }

    val uidHex: String get() = uid.toHex()
}

// ---------------------------------------------------------------------------
// MIFARE Ultralight / NTAG
// ---------------------------------------------------------------------------

/**
 * Page layout of an Ultralight family dump. The four configuration pages are
 * always the last four. MF0UL21 and the NTAG21x family spend the page before
 * them on additional lock bits; MF0UL11 has no such page and its user area
 * ends right before the configuration.
 */
class MfuLayout(val totalPages: Int) {

    init {
        require(totalPages >= 8) { "a MIFARE Ultralight dump has at least 8 pages" }
    }

    val maxPage: Int get() = totalPages - 1

    val userFirst: Int get() = 4

    val userLast: Int get() = when (totalPages) {
        20 -> 15 // MF0UL11: page 16 starts the configuration area
        else -> totalPages - 6
    }

    /** MOD, RFUI, RFUI, AUTH0. */
    val cfg0: Int get() = totalPages - 4

    /** ACCESS, VCTID, RFUI, RFUI. */
    val cfg1: Int get() = totalPages - 3

    val pwdPage: Int get() = totalPages - 2

    val packPage: Int get() = totalPages - 1

    /** Additional lock bits of MF0UL21/NTAG21x, -1 when the variant has none. */
    val lockPage: Int get() = if (totalPages == 20) -1 else totalPages - 5

    val userBytes: Int get() = (userLast - userFirst + 1) * 4

    val variant: String
        get() = when (totalPages) {
            20 -> "MF0UL11 (48 B)"
            41 -> "MF0UL21 (128 B)"
            45 -> "NTAG213 (144 B)"
            135 -> "NTAG215 (504 B)"
            231 -> "NTAG216 (888 B)"
            else -> "Ultralight/NTAG ($totalPages pagine)"
        }

    /**
     * Only variants whose memory map is known exactly are accepted: writing the
     * configuration of an unknown layout could destroy a tag.
     */
    val supported: Boolean get() = totalPages in SUPPORTED_PAGES

    companion object {
        val SUPPORTED_PAGES = setOf(20, 41, 45, 135, 231)
    }
}

class MfuDump(
    val name: String,
    val pages: List<ByteArray>,
    val version: ByteArray? = null,
    val signature: ByteArray? = null,
    val format: String = "bin",
    /** Byte length of the file header this dump was read from (0 for plain page dumps). */
    val headerBytes: Int = 0,
) {
    init {
        require(pages.isNotEmpty() && pages.all { it.size == 4 }) {
            "every page must hold exactly four bytes"
        }
    }

    val layout: MfuLayout by lazy { MfuLayout(pages.size) }

    fun page(index: Int): ByteArray = pages[index]

    /** Seven-byte UID: serial page plus the four UID bytes of page 1. */
    val uid: ByteArray
        get() = if (pages.size >= 2) {
            pages[0].copyOfRange(0, 3) + pages[1].copyOfRange(0, 4)
        } else {
            pages[0]
        }

    val uidHex: String get() = uid.toHex()
}

/** Serialises pages the way `hf mfu dump` lays them out on disk. */
fun List<ByteArray>.toMfuBin(version: ByteArray?, signature: ByteArray?): ByteArray {
    val header = ByteArray(56)
    if (version != null) version.copyInto(header, 0, 0, minOf(version.size, 8))
    header[11] = (size - 1).toByte()
    if (signature != null) signature.copyInto(header, 12, 0, minOf(signature.size, 32))
    val body = ByteArray(size * 4)
    forEachIndexed { index, page -> page.copyInto(body, index * 4, 0, 4) }
    return header + body
}

fun MfuDump.toBin(): ByteArray = pages.toMfuBin(version, signature)

fun MfcDump.toBin(): ByteArray = data.copyOf()

/** Name of the sibling key file PM3 writes next to a dump (`...-dump.bin`). */
fun keyFileCandidate(dump: File): File? {
    val parent = dump.parentFile ?: return null
    val base = dump.name.substringBeforeLast('.', dump.name)
    val stem = if (base.endsWith("-dump")) base.removeSuffix("-dump") else base
    val candidates = listOf(
        "$stem-key.bin",
        "$stem.keys",
        "$stem-key.txt",
    )
    return candidates.map { File(parent, it) }.firstOrNull { it.isFile }
}
