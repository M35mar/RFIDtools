// Readers for the dump files the Proxmark3 client writes into $HOME/.proxmark3.
//
// MIFARE Classic  : raw .bin (16 bytes per block), .eml and MCT-style .txt.
// Ultralight/NTAG : .bin with the 56-byte header (`MFU_DUMP_PREFIX_LENGTH`),
//                   the legacy 48-byte header and plain page dumps.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.File

/** A dump file recognised as one of the families the app can restore. */
sealed interface DumpImage {
    val name: String
    val uidHex: String
    val tagLabel: String

    class Classic(val dump: MfcDump) : DumpImage {
        override val name: String get() = dump.name
        override val uidHex: String get() = dump.uidHex
        override val tagLabel: String get() = dump.size.label
    }

    class Ultralight(val dump: MfuDump) : DumpImage {
        override val name: String get() = dump.name
        override val uidHex: String get() = dump.uidHex
        override val tagLabel: String get() = dump.layout.variant
    }
}

object DumpLoader {

    private val TEXT_EXTENSIONS = setOf("eml", "txt")

    fun load(file: File): DumpImage {
        if (!file.isFile) throw DumpFormatException("file non trovato: ${file.name}")
        val bytes = file.readBytes()
        if (bytes.isEmpty()) throw DumpFormatException("file vuoto")

        if (file.extension.lowercase() in TEXT_EXTENSIONS) {
            return DumpImage.Classic(MfcDumpParser.fromText(file.name, bytes))
        }

        mfuFromBin(file.name, bytes)?.let { return DumpImage.Ultralight(it) }
        MfcDumpParser.fromBinary(file.name, bytes, file)?.let { return DumpImage.Classic(it) }
        mfuLegacyFromBin(file.name, bytes)?.let { return DumpImage.Ultralight(it) }
        mfuPlainFromBin(file.name, bytes)?.let { return DumpImage.Ultralight(it) }

        throw DumpFormatException("formato non riconosciuto (${bytes.size} byte)")
    }

    private fun mfuFromBin(name: String, bytes: ByteArray): MfuDump? {
        if (bytes.size <= MFU_HEADER_BYTES) return null
        val body = bytes.size - MFU_HEADER_BYTES
        if (body % 4 != 0) return null
        val pages = body / 4
        if (pages !in 8..MAX_MFU_PAGES) return null
        // The header stores the highest page of the dump; a mismatch means the
        // file is not in the current format.
        if ((bytes[11].toInt() and 0xFF) != pages - 1) return null
        return buildMfu(name, bytes, MFU_HEADER_BYTES, pages, "bin")
    }

    private fun mfuLegacyFromBin(name: String, bytes: ByteArray): MfuDump? {
        if (bytes.size <= OLD_MFU_HEADER_BYTES) return null
        val body = bytes.size - OLD_MFU_HEADER_BYTES
        if (body % 4 != 0) return null
        val pages = body / 4
        if (pages !in 8..MAX_MFU_PAGES) return null
        if (!hasValidBcc(bytes, OLD_MFU_HEADER_BYTES)) return null
        return buildMfu(name, bytes, OLD_MFU_HEADER_BYTES, pages, "bin-legacy")
    }

    private fun mfuPlainFromBin(name: String, bytes: ByteArray): MfuDump? {
        if (bytes.size % 4 != 0) return null
        val pages = bytes.size / 4
        if (pages !in 8..MAX_MFU_PAGES) return null
        val list = (0 until pages).map { bytes.copyOfRange(it * 4, it * 4 + 4) }
        return MfuDump(name, list, format = "plain", headerBytes = 0)
    }

    private fun buildMfu(
        name: String,
        bytes: ByteArray,
        header: Int,
        pages: Int,
        format: String,
    ): MfuDump {
        val list = (0 until pages).map { bytes.copyOfRange(header + it * 4, header + it * 4 + 4) }
        val version = bytes.copyOfRange(0, 8)
        val signatureOffset = if (header == MFU_HEADER_BYTES) 12 else 16
        val signature = bytes.copyOfRange(signatureOffset, signatureOffset + 32)
        return MfuDump(name, list, version, signature, format, header)
    }

    /** BCC check the client uses to tell real UL/NTAG dumps from random data. */
    private fun hasValidBcc(bytes: ByteArray, offset: Int): Boolean {
        if (bytes.size < offset + 9) return false
        val bcc0 = (0x88 xor (bytes[offset].toInt() and 0xFF) xor
            (bytes[offset + 1].toInt() and 0xFF) xor (bytes[offset + 2].toInt() and 0xFF)).toByte()
        val bcc1 = (bytes[offset + 4].toInt() xor bytes[offset + 5].toInt() xor
            bytes[offset + 6].toInt() xor bytes[offset + 7].toInt()).toByte()
        return bcc0 == bytes[offset + 3] && bcc1 == bytes[offset + 8]
    }

    private const val MFU_HEADER_BYTES = 56
    private const val OLD_MFU_HEADER_BYTES = 48
    private const val MAX_MFU_PAGES = 256
}

object MfcDumpParser {

    fun fromBinary(name: String, bytes: ByteArray, source: File?): MfcDump? {
        val size = MfcSize.fromBytes(bytes.size) ?: return null
        val keys = source?.let { file ->
            keyFileCandidate(file)?.let { key -> runCatching { MfcKeyFileParser.parse(key, size) }.getOrNull() }
        }
        return MfcDump(name, size, bytes, keys)
    }

    /**
     * Text dumps come either from `hf mf dump` (.eml, one block per line) or
     * from MIFARE Classic Tool (.txt, sector headers plus block lines). Both
     * boil down to 32 hex characters per block, in card order.
     */
    fun fromText(name: String, bytes: ByteArray): MfcDump {
        val text = bytes.toString(Charsets.UTF_8)
        val blocks = mutableListOf<ByteArray>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("+") || line.startsWith("#") || line.startsWith("//")) continue
            val withoutIndex = line.replace(INDEX_PREFIX, "")
            val compact = withoutIndex.filterNot { it == ' ' || it == '\t' || it == ':' || it == '-' }
            if (compact.length != 32 || !compact.all { it.isHexDigit() }) continue
            blocks += compact.hexToBytesOrNull() ?: continue
        }
        val size = MfcSize.fromBlocks(blocks.size)
            ?: throw DumpFormatException(
                "il file di testo contiene ${blocks.size} blocchi, non è un dump MIFARE Classic",
            )
        val flat = ByteArray(size.bytes)
        blocks.forEachIndexed { index, block -> block.copyInto(flat, index * MfcGeometry.BLOCK_SIZE) }
        return MfcDump(name, size, flat, null)
    }

    private fun Char.isHexDigit(): Boolean = digitToIntOrNull(16) != null

    private val INDEX_PREFIX = Regex("^\\s*\\d+\\s*:\\s*")
}

object MfcKeyFileParser {

    private const val KEY_BYTES = 6

    /**
     * Binary key files written by `hf mf dump` / `hf mf autopwn` are two equal
     * halves: every sector's key A, then every sector's key B, six bytes each.
     *
     * Files that do not match a sector layout are accepted as a flat list of
     * six-byte keys, which is what most key collections export. Trying them as
     * candidates is safe because a wrong key is simply refused.
     */
    fun parse(file: File, size: MfcSize): MfcKeys = parse(file.readBytes(), size, file.name)

    fun parse(bytes: ByteArray, size: MfcSize, name: String): MfcKeys {
        if (bytes.isEmpty()) throw DumpFormatException("key file vuoto: $name")

        val sectorBytes = size.sectors * KEY_BYTES
        if (bytes.size == sectorBytes * 2) {
            val keyA = (0 until size.sectors).map { bytes.copyOfRange(it * 6, it * 6 + 6) }
            val keyB = (0 until size.sectors).map {
                bytes.copyOfRange(sectorBytes + it * 6, sectorBytes + it * 6 + 6)
            }
            return MfcKeys(keyA, keyB, name)
        }
        if (bytes.size == sectorBytes) {
            val keyA = (0 until size.sectors).map { bytes.copyOfRange(it * 6, it * 6 + 6) }
            return MfcKeys(keyA, emptyList(), name)
        }

        // A real text file without a single 12-hex token is a dump or a
        // document, not a key file: decoding its bytes as binary keys would
        // invent garbage entries.
        val text = MfcKeyText.decodeText(bytes)
        if (text != null) {
            val tokens = MfcKeyText.textKeys(text)
            if (tokens.isEmpty()) {
                throw DumpFormatException("$name non contiene chiavi da 6 byte")
            }
            // Standard text key files have at most two keys per line, one for
            // Key A and one for Key B. More keys than a card can hold is still
            // a valid flat key file: every entry is only a candidate.
            if (tokens.size <= size.sectors * 2) {
                return parseText(text, size, name)
            }
            return MfcKeys(emptyList(), emptyList(), name, tokens)
        }

        val flat = MfcKeyText.binaryKeys(bytes)
        if (flat.isEmpty()) {
            throw DumpFormatException(
                "key file di ${bytes.size} byte: non è un file chiavi MIFARE Classic, " +
                    "né un elenco di chiavi da 6 byte",
            )
        }
        return MfcKeys(emptyList(), emptyList(), name, flat)
    }

    private fun parseText(text: String, size: MfcSize, name: String): MfcKeys {
        val keyA = mutableListOf<ByteArray>()
        val keyB = mutableListOf<ByteArray>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("+") || line.startsWith("#")) continue
            val tokens = line.split(Regex("[\\s;]+")).filter { it.isNotEmpty() }
            val first = tokens.firstOrNull()?.hexToBytesOrNull()?.takeIf { it.size == 6 }
            val second = tokens.getOrNull(1)?.hexToBytesOrNull()?.takeIf { it.size == 6 }
            if (first != null) keyA += first
            if (second != null) keyB += second
            if (keyA.size >= size.sectors) break
        }
        if (keyA.isEmpty()) throw DumpFormatException("key file senza chiavi: $name")
        return MfcKeys(keyA, keyB, name)
    }
}

/**
 * Helpers shared by the key-file parser: text sniffing and flat six-byte
 * records. A key file is a list of candidates, not a dictionary with its own
 * format, so there is no separate dictionary parser any more.
 */
object MfcKeyText {

    private const val KEY_BYTES = 6
    private const val MAX_KEYS = 4096

    /**
     * Decodes UTF-8 text and UTF-16 (with or without BOM). Null means the
     * content is binary, not text.
     */
    internal fun decodeText(bytes: ByteArray): String? {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }

        val zeros = bytes.count { it == 0.toByte() }
        if (zeros > bytes.size / 4) {
            val little = String(bytes, Charsets.UTF_16LE)
            val big = String(bytes, Charsets.UTF_16BE)
            val chosen = if (controlChars(little) <= controlChars(big)) little else big
            return chosen.takeIf { controlChars(it) <= it.length / 4 }
        }

        val text = bytes.toString(Charsets.UTF_8)
        // A binary file decodes to replacement characters; without this check
        // a flat list of six-byte keys would be mistaken for text and rejected
        // for having no 12-hex token.
        return text.takeIf { controlChars(it) == 0 && '\uFFFD' !in it }
    }

    private fun controlChars(text: String): Int =
        text.count { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }

    /** Every 12-hex token; a 32-hex dump line is not split into fake keys. */
    internal fun textKeys(text: String): List<ByteArray> {
        val seen = LinkedHashSet<String>()
        for (token in text.split(Regex("[^0-9A-Fa-f]+"))) {
            if (token.length != 12) continue
            val bytes = token.hexToBytesOrNull() ?: continue
            if (bytes.all { it == 0.toByte() }) continue
            seen += bytes.toHex()
            if (seen.size >= MAX_KEYS) break
        }
        return seen.map { it.hexToBytesOrNull()!! }
    }

    /**
     * Flat six-byte records. When the size is not a multiple of six the first
     * `size % 6` bytes are treated as a header, which is what common exporters
     * with a small header produce (e.g. 8-byte header + 336 keys = 2024 bytes).
     */
    internal fun binaryKeys(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < KEY_BYTES) return emptyList()
        val remainder = bytes.size % KEY_BYTES
        val offset = if (remainder == 1) 0 else remainder
        if (bytes.size - offset < KEY_BYTES) return emptyList()

        val seen = LinkedHashSet<String>()
        var index = offset
        while (index + KEY_BYTES <= bytes.size && seen.size < MAX_KEYS) {
            val key = bytes.copyOfRange(index, index + KEY_BYTES)
            if (key.any { it != 0.toByte() }) seen += key.toHex()
            index += KEY_BYTES
        }
        return seen.map { it.hexToBytesOrNull()!! }
    }
}

/** Locates the key files the app can use for a MIFARE Classic tag. */
object MfcKeyFiles {

    /** Only these extensions are accepted, both in the list and on import. */
    val EXTENSIONS = setOf("bin", "txt")

    /**
     * True for a `.bin`/`.txt` file that is not a dump. Dumps are large binary
     * files whose bytes would be mistaken for a huge flat key list.
     */
    fun isKeyFile(name: String): Boolean {
        val lower = name.lowercase()
        if (EXTENSIONS.none { lower.endsWith(".$it") }) return false
        return !isDumpName(lower)
    }

    fun isDumpName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith("-dump.bin") ||
            lower.endsWith("-dump.txt") ||
            lower.endsWith(".eml")
    }

    /** Key files in [dir] and its first-level folders, sorted by path. */
    fun listIn(dir: File): List<File> =
        dir.walkTopDown()
            .maxDepth(2)
            .onEnter { it == dir || !it.name.startsWith(".") }
            .filter { it.isFile && isKeyFile(it.name) }
            .sortedBy { it.relativeTo(dir).path.lowercase() }
            .toList()
}

object MfuDumpParser {

    fun parse(file: File): MfuDump = when (val image = DumpLoader.load(file)) {
        is DumpImage.Ultralight -> image.dump
        is DumpImage.Classic -> throw DumpFormatException("${file.name} è un dump MIFARE Classic")
    }
}
