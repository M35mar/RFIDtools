// Bi-directional Flipper Zero `.nfc` (Filetype: Flipper NFC device, Version 2..4)
// parser and exporter for MIFARE Classic, Ultralight/NTAG and ISO 15693-3.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

class Iso15693Dump(
    val name: String,
    val uid: ByteArray,
    val blockSize: Int,
    val blocks: List<ByteArray>,
    val dsfid: Int = 0,
    val afi: Int = 0,
    val icReference: Int = 0,
    val lockStatus: ByteArray? = null,
) {
    init {
        require(blockSize in 1..64) { "invalid ISO 15693 block size: $blockSize" }
        require(blocks.isNotEmpty() && blocks.all { it.size == blockSize }) {
            "every ISO 15693 block must have $blockSize bytes"
        }
    }

    val uidHex: String get() = uid.toHex()
    val totalBytes: Int get() = blocks.size * blockSize

    fun toPlainBytes(): ByteArray {
        val out = ByteArray(totalBytes)
        blocks.forEachIndexed { idx, blk -> blk.copyInto(out, idx * blockSize) }
        return out
    }
}

object FlipperNfcFormat {

    private const val HEADER_MAGIC = "Filetype: Flipper NFC device"

    fun isFlipperNfc(bytes: ByteArray): Boolean {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 128)).toString(Charsets.UTF_8)
        return head.trimStart().startsWith(HEADER_MAGIC)
    }

    fun parse(name: String, bytes: ByteArray): DumpImage {
        val text = bytes.toString(Charsets.UTF_8)
        val kv = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            kv[key] = value
        }
        if (kv["Filetype"] != "Flipper NFC device") {
            throw DumpFormatException("$name non è un file Flipper NFC valido")
        }
        val devType = kv["Device type"].orEmpty()
        return when {
            devType.equals("Mifare Classic", ignoreCase = true) ->
                DumpImage.Classic(parseClassic(name, kv))

            devType.contains("NTAG", ignoreCase = true) ||
                devType.contains("Ultralight", ignoreCase = true) ->
                DumpImage.Ultralight(parseUltralight(name, kv))

            devType.contains("ISO15693", ignoreCase = true) ||
                devType.contains("SLIX", ignoreCase = true) ->
                DumpImage.Iso15693(parseIso15693(name, kv))

            else -> throw DumpFormatException("Device type Flipper non supportato: $devType")
        }
    }

    private fun parseClassic(name: String, kv: Map<String, String>): MfcDump {
        val typeStr = kv["Mifare Classic type"].orEmpty().uppercase()
        val expectedBlocks = when (typeStr) {
            "MINI" -> 20
            "1K" -> 64
            "2K" -> 128
            "4K" -> 256
            else -> {
                val maxIdx = kv.keys.mapNotNull { k ->
                    k.removePrefix("Block ").toIntOrNull()?.takeIf { k.startsWith("Block ") }
                }.maxOrNull() ?: throw DumpFormatException("nessun blocco trovato in $name")
                maxIdx + 1
            }
        }
        val size = MfcSize.fromBlocks(expectedBlocks)
            ?: throw DumpFormatException("numero blocchi MIFARE Classic non valido: $expectedBlocks")

        val data = ByteArray(size.bytes)
        for (b in 0 until size.blocks) {
            val raw = kv["Block $b"] ?: continue
            val parsed = parseSpacedHexWithUnknowns(raw, MfcGeometry.BLOCK_SIZE)
            parsed.copyInto(data, b * MfcGeometry.BLOCK_SIZE)
        }
        // Extract non-zero trailer keys into MfcKeys for convenience.
        val keyA = (0 until size.sectors).map { sec ->
            val t = MfcGeometry.firstBlockOfSector(sec) + MfcGeometry.blocksInSector(sec) - 1
            data.copyOfRange(t * 16, t * 16 + 6)
        }
        val keyB = (0 until size.sectors).map { sec ->
            val t = MfcGeometry.firstBlockOfSector(sec) + MfcGeometry.blocksInSector(sec) - 1
            data.copyOfRange(t * 16 + 10, t * 16 + 16)
        }
        return MfcDump(name, size, data, MfcKeys(keyA, keyB, name))
    }

    private fun parseUltralight(name: String, kv: Map<String, String>): MfuDump {
        val total = kv["Pages total"]?.toIntOrNull() ?: run {
            val maxIdx = kv.keys.mapNotNull { k ->
                k.removePrefix("Page ").toIntOrNull()?.takeIf { k.startsWith("Page ") }
            }.maxOrNull() ?: throw DumpFormatException("nessuna pagina trovata in $name")
            maxIdx + 1
        }
        if (total !in 8..256) throw DumpFormatException("numero pagine non valido: $total")
        val pages = (0 until total).map { p ->
            val raw = kv["Page $p"]
            if (raw != null) parseSpacedHexWithUnknowns(raw, 4) else ByteArray(4)
        }
        val version = kv["Mifare version"]?.hexToBytesOrNull()?.takeIf { it.size == 8 }
        val signature = kv["Signature"]?.hexToBytesOrNull()?.takeIf { it.size >= 32 }?.copyOf(32)
        return MfuDump(
            name = name,
            pages = pages,
            version = version,
            signature = signature,
            format = "nfc",
            headerBytes = 0,
        )
    }

    private fun parseIso15693(name: String, kv: Map<String, String>): Iso15693Dump {
        val uid = kv["UID"]?.hexToBytesOrNull()
            ?: throw DumpFormatException("UID mancante in $name")
        val blockCount = kv["Block Count"]?.let { it.toIntOrNull() ?: it.toIntOrNull(16) }
            ?: throw DumpFormatException("Block Count mancante in $name")
        val blockSize = kv["Block Size"]?.let { it.toIntOrNull() ?: it.toIntOrNull(16) } ?: 4
        if (blockCount !in 1..4096 || blockSize !in 1..64) {
            throw DumpFormatException("geometria ISO15693 non valida: $blockCount x $blockSize")
        }
        val rawContent = kv["Data Content"].orEmpty()
        val flat = parseSpacedHexWithUnknowns(rawContent, blockCount * blockSize)
        val blocks = (0 until blockCount).map { idx ->
            flat.copyOfRange(idx * blockSize, (idx + 1) * blockSize)
        }
        val dsfid = kv["Data Storage Format Identifier. DSFID"]?.toIntOrNull(16)
            ?: kv["DSFID"]?.toIntOrNull(16) ?: 0
        val afi = kv["Application Family Identifier. AFI"]?.toIntOrNull(16)
            ?: kv["AFI"]?.toIntOrNull(16) ?: 0
        val icRef = kv["IC Reference"]?.toIntOrNull(16) ?: 0
        val security = kv["Block Security Status"]?.hexToBytesOrNull()
        return Iso15693Dump(
            name = name,
            uid = uid,
            blockSize = blockSize,
            blocks = blocks,
            dsfid = dsfid,
            afi = afi,
            icReference = icRef,
            lockStatus = security,
        )
    }

    private fun parseSpacedHexWithUnknowns(raw: String, expectedBytes: Int): ByteArray {
        val cleaned = raw.replace("??", "00")
        val parsed = cleaned.hexToBytesOrNull() ?: ByteArray(0)
        return if (parsed.size == expectedBytes) {
            parsed
        } else {
            ByteArray(expectedBytes).also { out ->
                parsed.copyInto(out, 0, 0, minOf(parsed.size, expectedBytes))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Exporters (Flipper NFC format v4)
    // -----------------------------------------------------------------------

    fun fromClassic(dump: MfcDump): String = buildString {
        val b0 = dump.block(0)
        val uid = dump.uid
        val sakOffset = if (uid.size == 4) 5 else 8
        val sak = b0.getOrElse(sakOffset) { 0x08 }.let { "%02X".format(it.toInt() and 0xFF) }
        val atqa = if (sakOffset + 2 < b0.size) {
            "%02X %02X".format(b0[sakOffset + 2].toInt() and 0xFF, b0[sakOffset + 1].toInt() and 0xFF)
        } else {
            "00 04"
        }
        val typeName = when (dump.size) {
            MfcSize.MINI -> "Mini"
            MfcSize.CLASSIC_1K -> "1K"
            MfcSize.CLASSIC_2K -> "2K"
            MfcSize.CLASSIC_4K -> "4K"
        }
        appendLine(HEADER_MAGIC)
        appendLine("Version: 4")
        appendLine("# Generated by RFIDtools Android")
        appendLine("Device type: Mifare Classic")
        appendLine("UID: ${uid.toHexSpaced()}")
        appendLine("ATQA: $atqa")
        appendLine("SAK: $sak")
        appendLine("Mifare Classic type: $typeName")
        appendLine("Data format version: 2")
        for (i in 0 until dump.size.blocks) {
            appendLine("Block $i: ${dump.block(i).toHexSpaced()}")
        }
    }

    fun fromUltralight(dump: MfuDump): String = buildString {
        val version = dump.version?.takeIf { it.size == 8 } ?: ByteArray(8)
        val sig = dump.signature?.takeIf { it.size >= 32 }?.copyOf(32) ?: ByteArray(32)
        appendLine(HEADER_MAGIC)
        appendLine("Version: 4")
        appendLine("# Generated by RFIDtools Android")
        appendLine("Device type: NTAG/Ultralight")
        appendLine("UID: ${dump.uid.toHexSpaced()}")
        appendLine("ATQA: 00 44")
        appendLine("SAK: 00")
        appendLine("Data format version: 2")
        appendLine("Signature: ${sig.toHexSpaced()}")
        appendLine("Mifare version: ${version.toHexSpaced()}")
        for (c in 0..2) {
            appendLine("Counter $c: 0")
            appendLine("Tearing $c: BD")
        }
        appendLine("Pages total: ${dump.pages.size}")
        appendLine("Pages read: ${dump.pages.size}")
        dump.pages.forEachIndexed { idx, page ->
            appendLine("Page $idx: ${page.toHexSpaced()}")
        }
        appendLine("Failed authentication attempts: 0")
    }

    fun fromIso15693(dump: Iso15693Dump): String = buildString {
        val sec = dump.lockStatus ?: ByteArray(dump.blocks.size)
        appendLine(HEADER_MAGIC)
        appendLine("Version: 4")
        appendLine("# Generated by RFIDtools Android")
        appendLine("Device type: ISO15693-3")
        appendLine("UID: ${dump.uid.toHexSpaced()}")
        appendLine("Data Storage Format Identifier. DSFID: %02X".format(dump.dsfid and 0xFF))
        appendLine("Application Family Identifier. AFI: %02X".format(dump.afi and 0xFF))
        appendLine("IC Reference: %02X".format(dump.icReference and 0xFF))
        appendLine("Lock Bits. Lock DSFID: false")
        appendLine("Lock AFI: false")
        appendLine("Block Count: ${dump.blocks.size}")
        appendLine("Block Size: %02X".format(dump.blockSize and 0xFF))
        appendLine("Data Content: ${dump.toPlainBytes().toHexSpaced()}")
        appendLine("Block Security Status: ${sec.toHexSpaced()}")
    }
}
