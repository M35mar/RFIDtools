// Proxmark3 Iceman JSON dump parser & exporter (`mfcard`, `mfu`, `15`).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.json.JSONObject

object Pm3JsonDump {

    fun parse(name: String, bytes: ByteArray): DumpImage {
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw DumpFormatException("$name non è un JSON Proxmark3 valido: ${e.message}")
        }
        val fileType = root.optString("FileType", "").lowercase()
        val blocksObj = root.optJSONObject("blocks")
            ?: throw DumpFormatException("$name non contiene la sezione 'blocks'")

        val indices = blocksObj.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().toList()
        if (indices.isEmpty()) throw DumpFormatException("$name non contiene blocchi numerati")
        val maxIndex = indices.last()
        val firstBytes = blocksObj.optString(indices.first().toString(), "").hexToBytesOrNull()
            ?: throw DumpFormatException("blocco ${indices.first()} non valido in $name")

        return when {
            fileType.contains("mfu") || firstBytes.size == 4 && fileType != "15" && !fileType.contains("15693") -> {
                val pageCount = maxIndex + 1
                if (pageCount !in 8..256) {
                    throw DumpFormatException("numero pagine JSON non valido: $pageCount")
                }
                val pages = (0 until pageCount).map { idx ->
                    blocksObj.optString(idx.toString(), "").hexToBytesOrNull()
                        ?.takeIf { it.size == 4 }
                        ?: ByteArray(4)
                }
                val card = root.optJSONObject("Card")
                val version = card?.optString("Version", "")?.hexToBytesOrNull()?.takeIf { it.size == 8 }
                val sig = card?.optString("Signature", "")?.hexToBytesOrNull()?.takeIf { it.size >= 32 }?.copyOf(32)
                DumpImage.Ultralight(MfuDump(name, pages, version, sig, "json", 0))
            }

            fileType == "15" || fileType.contains("15693") || fileType.contains("icode") -> {
                val blockSize = firstBytes.size.coerceIn(1, 64)
                val count = maxIndex + 1
                val blocks = (0 until count).map { idx ->
                    blocksObj.optString(idx.toString(), "").hexToBytesOrNull()
                        ?.takeIf { it.size == blockSize }
                        ?: ByteArray(blockSize)
                }
                val card = root.optJSONObject("Card")
                val uid = card?.optString("UID", "")?.hexToBytesOrNull() ?: ByteArray(8)
                DumpImage.Iso15693(Iso15693Dump(name, uid, blockSize, blocks))
            }

            else -> {
                val blockCount = maxIndex + 1
                val size = MfcSize.fromBlocks(blockCount)
                    ?: throw DumpFormatException("numero blocchi JSON non valido: $blockCount")
                val flat = ByteArray(size.bytes)
                for (b in 0 until size.blocks) {
                    val blk = blocksObj.optString(b.toString(), "").hexToBytesOrNull()
                    if (blk != null && blk.size == MfcGeometry.BLOCK_SIZE) {
                        blk.copyInto(flat, b * MfcGeometry.BLOCK_SIZE)
                    }
                }
                val keyA = (0 until size.sectors).map { sec ->
                    val t = MfcGeometry.firstBlockOfSector(sec) + MfcGeometry.blocksInSector(sec) - 1
                    flat.copyOfRange(t * 16, t * 16 + 6)
                }
                val keyB = (0 until size.sectors).map { sec ->
                    val t = MfcGeometry.firstBlockOfSector(sec) + MfcGeometry.blocksInSector(sec) - 1
                    flat.copyOfRange(t * 16 + 10, t * 16 + 16)
                }
                DumpImage.Classic(MfcDump(name, size, flat, MfcKeys(keyA, keyB, name)))
            }
        }
    }
}
