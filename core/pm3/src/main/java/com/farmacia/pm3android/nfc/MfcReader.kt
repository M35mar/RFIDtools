// Phone-NFC reader for MIFARE Classic.
//
// The sequence follows MIFARE Classic Tool: a flat list of candidate keys is
// tried as Key A and as Key B on every sector, then each sector is read with
// both sessions and the results are merged. This is what captures a card whose
// sectors do not share one key, and it is the same key map the restore engine
// builds on the destination tag.
//
// When a key file is selected only its keys are used: a wrong file must fail
// instead of being rescued by the factory key. `FFFFFFFFFFFF` is tried only when
// no file is selected.
//
// Only the transport is Android-specific, so the reader is unit-testable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.IOException

/** Key file chosen by the user, kept as bytes because the tag size is unknown. */
class KeyFileSource(val name: String, val bytes: ByteArray)

/** What a Classic read captured: raw blocks plus the keys that worked. */
class MfcCapture(
    val size: MfcSize,
    private val data: ByteArray,
    /** Keys per sector, merged from the key file and the ones that worked. */
    val keys: MfcKeys?,
    /** Blocks that were actually read; the others stay zero in the dump. */
    val readableBlocks: Set<Int>,
    /** Sectors where at least one key was accepted. */
    val readableSectors: Set<Int>,
) {

    init {
        require(data.size == size.bytes) {
            "dump da ${data.size} byte, ${size.label} ne richiede ${size.bytes}"
        }
    }

    /** UID from block 0; a four-byte UID is followed by its BCC. */
    val uid: ByteArray
        get() {
            val block = data.copyOfRange(0, MfcGeometry.BLOCK_SIZE)
            val bcc = (block[0].toInt() xor block[1].toInt() xor
                block[2].toInt() xor block[3].toInt()).toByte()
            return if (bcc == block[4]) block.copyOfRange(0, 4) else block.copyOfRange(0, 7)
        }

    val uidHex: String get() = uid.toHex()

    /** PM3 file name without extension, uppercase hex. */
    val defaultName: String get() = "hf-mf-$uidHex-dump"

    val unreadableBlocks: List<Int> get() = (0 until size.blocks).filter { it !in readableBlocks }

    fun toBin(): ByteArray = data.copyOf()

    /**
     * Key file bytes in the same layout as `hf mf dump`: Key A of every sector
     * followed by Key B of every sector. When a Key B is unknown the file only
     * holds the Key A half, so a later restore keeps the key already on the
     * card instead of writing zeroes.
     */
    fun keysToBin(): ByteArray? {
        val keys = keys ?: return null
        val sectors = size.sectors
        val keyA = ByteArray(sectors * KEY_BYTES)
        val keyB = ByteArray(sectors * KEY_BYTES)
        var anyA = false
        var anyB = false
        var allB = true

        for (sector in 0 until sectors) {
            val a = keys.keyAFor(sector)
            if (a != null && a.any { it != 0.toByte() }) {
                a.copyInto(keyA, sector * KEY_BYTES)
                anyA = true
            }
            val b = keys.keyBFor(sector)
            if (b == null || b.all { it == 0.toByte() }) {
                allB = false
            } else {
                b.copyInto(keyB, sector * KEY_BYTES)
                anyB = true
            }
        }

        return when {
            !anyA && !anyB -> null
            allB -> keyA + keyB
            else -> keyA
        }
    }

    private companion object {
        const val KEY_BYTES = 6
    }
}

class MfcReader(private val transport: ClassicTransport) {

    /**
     * Reads the whole card.
     *
     * [keyFile] is parsed against the detected size: a file laid out for
     * another tag becomes a flat candidate list instead of being rejected,
     * because the same six-byte keys are often valid for every card.
     *
     * @param requireComplete throw [MfcIncompleteReadException] when any sector
     *   or block is missing. Dumps always set this: a partial file looks valid
     *   and would later be restored as zeroes.
     */
    fun read(
        keyFile: KeyFileSource? = null,
        requireComplete: Boolean = false,
        progress: RestoreProgress? = null,
    ): MfcCapture {
        val size = sizeOf()
        val parsed = keyFile?.let { source ->
            try {
                MfcKeyFileParser.parse(source.bytes, size, source.name)
            } catch (e: DumpFormatException) {
                throw TagUnusableException(
                    "file chiavi non valido per ${size.label}: ${e.message}",
                    e,
                )
            }
        }

        // With a key file selected only its keys are tried. The factory key is
        // a fallback for the no-file case, not a rescue for a wrong file.
        val candidates = if (parsed == null) {
            listOf(MFC_DEFAULT_KEY.copyOf())
        } else {
            MfcKeyMap.flatten(parsed.allKeys())
        }
        if (candidates.isEmpty()) {
            throw TagUnusableException("il file chiavi non contiene chiavi utilizzabili")
        }

        val keyMap = MfcKeyMap.build(
            sectorCount = size.sectors,
            keys = candidates,
            authenticate = { sector, key, keyA ->
                tryAuthenticate(sector, Session(key, keyA))
            },
        ) { sector ->
            // Keep the bar moving while the keys are being tried; the second
            // pass then continues from the same block scale.
            progress?.onProgress(MfcGeometry.firstBlockOfSector(sector), size.blocks)
        }

        val data = ByteArray(size.bytes)
        val readableBlocks = mutableSetOf<Int>()
        val readableSectors = mutableSetOf<Int>()
        var done = 0

        for (sector in 0 until size.sectors) {
            val first = MfcGeometry.firstBlockOfSector(sector)
            val count = MfcGeometry.blocksInSector(sector)
            val proven = keyMap[sector]

            if (proven != null) {
                readableSectors += sector
                // Read with Key A first, then with Key B: read condition 011
                // hides the block from an A session. Only proven keys are used,
                // so no extra authentication is wasted on the wrong guesses.
                val sessions = buildList {
                    proven.sessionForAny(preferA = true)?.let(::add)
                    proven.sessionForAny(preferA = false)?.let(::add)
                }.distinctBy { it.keyA to it.key.toHex() }

                for (session in sessions) {
                    if (!tryAuthenticate(sector, session)) continue
                    for (offset in 0 until count) {
                        val block = first + offset
                        if (block in readableBlocks) continue
                        val payload = tryRead(block) ?: continue
                        payload.copyInto(data, block * MfcGeometry.BLOCK_SIZE)
                        readableBlocks += block
                    }
                    if ((first until first + count).all { it in readableBlocks }) break
                }
            }

            done += count
            progress?.onProgress(done, size.blocks)
        }

        if (readableSectors.isEmpty()) {
            throw TagAuthException("nessun settore leggibile: servono le chiavi corrette")
        }

        // Keys are recorded only when an authentication proved them. A wrong
        // key written into a trailer would lock the sector for good.
        val mergedA = MutableList(size.sectors) { ByteArray(KEY_BYTES) }
        val mergedB = MutableList(size.sectors) { ByteArray(KEY_BYTES) }
        for ((sector, keys) in keyMap) {
            keys.keyA?.copyInto(mergedA[sector])
            keys.keyB?.copyInto(mergedB[sector])
        }

        // A tag never returns Key A and usually masks Key B, so a raw dump would
        // lose the keys that opened each sector. Storing the proven keys in the
        // trailer makes the saved .bin restorable on its own, as MCT does.
        patchTrailers(data, size, mergedA, mergedB, readableBlocks)

        if (requireComplete) {
            val incomplete = (0 until size.sectors).filter { sector ->
                sector !in readableSectors || (0 until MfcGeometry.blocksInSector(sector)).any {
                    MfcGeometry.firstBlockOfSector(sector) + it !in readableBlocks
                }
            }
            val unreadable = size.blocks - readableBlocks.size
            if (incomplete.isNotEmpty() || unreadable > 0) {
                throw MfcIncompleteReadException(
                    missingSectors = incomplete,
                    unreadableBlocks = unreadable,
                )
            }
        }

        return MfcCapture(
            size = size,
            data = data,
            keys = MfcKeys(mergedA, mergedB, parsed?.sourceName ?: keyFile?.name ?: ""),
            readableBlocks = readableBlocks,
            readableSectors = readableSectors,
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Tag size from the sector map; the card tells us its own geometry. */
    private fun sizeOf(): MfcSize {
        val sectors = transport.sectorCount
        MfcSize.entries.firstOrNull { it.sectors == sectors }?.let { return it }
        val blocks = (0 until sectors).sumOf { transport.blockCount(it) }
        return MfcSize.entries.firstOrNull { it.blocks == blocks }
            ?: throw TagUnusableException("taglia del tag non riconosciuta ($sectors settori)")
    }

    private fun tryAuthenticate(sector: Int, session: Session): Boolean = try {
        transport.authenticate(sector, session.key, session.keyA)
    } catch (_: IOException) {
        false
    }

    /** Overwrites the trailer keys of the sectors that were actually read. */
    private fun patchTrailers(
        data: ByteArray,
        size: MfcSize,
        keyA: List<ByteArray>,
        keyB: List<ByteArray>,
        readable: Set<Int>,
    ) {
        for (sector in 0 until size.sectors) {
            val trailer = MfcGeometry.firstBlockOfSector(sector) +
                MfcGeometry.blocksInSector(sector) - 1
            if (trailer !in readable) continue
            val base = trailer * MfcGeometry.BLOCK_SIZE
            keyA[sector]
                .takeIf { key -> key.any { it != 0.toByte() } }
                ?.copyInto(data, base)
            keyB[sector]
                .takeIf { key -> key.any { it != 0.toByte() } }
                ?.copyInto(data, base + 10)
        }
    }

    private fun tryRead(block: Int): ByteArray? = try {
        transport.readBlock(block)
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val KEY_BYTES = 6
    }
}
