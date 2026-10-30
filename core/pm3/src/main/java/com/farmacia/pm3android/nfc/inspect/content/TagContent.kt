// Content analysis for the inspector: what is readable inside a captured tag.
// It works on the same captures the dump flow produces, so nothing here touches
// a tag: a partially readable card simply yields fewer facts.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import com.farmacia.pm3android.nfc.MfcCapture
import com.farmacia.pm3android.nfc.MfcGeometry
import com.farmacia.pm3android.nfc.MfcValueBlock
import com.farmacia.pm3android.nfc.MfuCapture
import com.farmacia.pm3android.nfc.toHex

/** How a Classic sector is used, once MAD has been decoded. */
enum class SectorRole {
    MANUFACTURER,
    MAD1,
    MAD2,
    NDEF,
    DATA,
}

class ClassicSector(
    val sector: Int,
    val blocks: Int,
    /** Every block of the sector was read. */
    val readable: Boolean,
    val role: SectorRole,
    /** AID assigned by MAD, null when the sector is not in the directory. */
    val aid: Int?,
    val aidName: String?,
    val access: SectorAccess?,
    /** False when the C1/C2/C3 nibbles and their complements disagree. */
    val accessConsistent: Boolean,
    /** First data block as hex, null when it was not readable. */
    val firstDataHex: String?,
    /** The first data block holds a MIFARE value block. */
    val valueBlock: Boolean,
)

class ClassicContent(
    val uidHex: String,
    val sizeLabel: String,
    val sectors: List<ClassicSector>,
    val mad: MadDirectory?,
    val ndef: NdefContent,
    val readableSectors: Int,
    val totalSectors: Int,
)

/** Capability container of a Type 2 tag, page 3. */
class CapabilityContainer(
    val versionMajor: Int,
    val versionMinor: Int,
    val capacityBytes: Int,
    val readAccess: Int,
    val writeAccess: Int,
)

/** One NTAG counter: three little-endian bytes plus the tearing flag. */
class MfuCounter(
    /** Null when the slot reads 0xFFFFFF, the "not supported" value. */
    val value: Int?,
    val tearing: Boolean,
)

class UltralightContent(
    val uidHex: String,
    val pages: Int,
    val cc: CapabilityContainer?,
    val ndef: NdefContent,
    val counters: List<MfuCounter>,
)

/** Everything the inspector knows about the content, once a dump was read. */
class TagContent(
    val classic: ClassicContent? = null,
    val ultralight: UltralightContent? = null,
    /** NDEF read directly over ISO-DEP, FeliCa or ISO 15693. */
    private val directNdef: NdefContent? = null,
) {
    val ndef: NdefContent
        get() = classic?.ndef ?: ultralight?.ndef ?: directNdef ?: NdefContent.absent

    companion object {
        /** Content that only carries an NDEF message, e.g. a Type 4/5 tag. */
        fun ndefOnly(ndef: NdefContent): TagContent = TagContent(directNdef = ndef)
    }
}

object TagContentAnalyzer {

    private const val BLOCK = MfcGeometry.BLOCK_SIZE
    private const val PAGE = 4
    private const val MAD2_SECTOR = 16
    private const val CC_PAGE = 3
    private const val CC_MAGIC = 0xE1
    private const val COUNTER_UNSUPPORTED = 0xFFFFFF

    fun of(capture: MfcCapture): TagContent = TagContent(classic = classic(capture))

    fun of(capture: MfuCapture): TagContent = TagContent(ultralight = ultralight(capture))

    /**
     * Content of a page memory read over raw NfcA: the same Type 2 parsing as a
     * dump, without counters because the tag never answered GET_VERSION.
     */
    fun pages(pages: List<ByteArray>, uidHex: String): TagContent =
        TagContent(ultralight = ultralightOf(pages, uidHex, emptyList()))

    fun classic(capture: MfcCapture): ClassicContent {
        val bytes = capture.toBin()
        val size = capture.size
        val readable = capture.readableBlocks

        val mad1 = if (isReadable(readable, 0, 4)) {
            MadParser.parse(
                bytes.copyOfRange(0, 4 * BLOCK),
                if (size.sectors > MAD2_SECTOR && isReadable(readable, MAD2_SECTOR, 4)) {
                    bytes.copyOfRange(MAD2_SECTOR * 4 * BLOCK, (MAD2_SECTOR + 1) * 4 * BLOCK)
                } else {
                    null
                },
            )
        } else {
            null
        }

        val aids = mad1?.entries?.associateBy { it.sector }.orEmpty()
        val ndefSectors = if (mad1?.crcOk == true) {
            mad1.entries
                .filter { it.aid == MadParser.AID_NDEF }
                .map { it.sector }
                .filter { it < size.sectors }
                .sorted()
        } else {
            emptyList()
        }

        val sectors = (0 until size.sectors).map { sector ->
            val firstBlock = MfcGeometry.firstBlockOfSector(sector)
            val blockCount = MfcGeometry.blocksInSector(sector)
            val trailerBlock = firstBlock + blockCount - 1
            val sectorReadable = (firstBlock..trailerBlock).all { it in readable }
            val trailer = if (isReadable(readable, trailerBlock, 1)) {
                block(bytes, trailerBlock)
            } else {
                null
            }
            val entry = aids[sector]
            val dataBlock = if (sector == 0) firstBlock + 1 else firstBlock
            val dataReadable = dataBlock < trailerBlock && dataBlock in readable
            val firstData = if (dataReadable) block(bytes, dataBlock) else null
            ClassicSector(
                sector = sector,
                blocks = blockCount,
                readable = sectorReadable,
                role = roleOf(sector, entry, mad1),
                aid = entry?.aid,
                aidName = entry?.name,
                access = trailer?.let { AccessBits.parse(it) },
                accessConsistent = trailer?.let { AccessBits.isConsistent(it) } ?: false,
                firstDataHex = firstData?.toHex(),
                valueBlock = firstData?.let { MfcValueBlock.isConsistent(it) } ?: false,
            )
        }

        return ClassicContent(
            uidHex = capture.uidHex,
            sizeLabel = size.label,
            sectors = sectors,
            mad = mad1,
            ndef = classicNdef(bytes, size.sectors, readable, ndefSectors),
            readableSectors = capture.readableSectors.size,
            totalSectors = size.sectors,
        )
    }

    fun ultralight(capture: MfuCapture): UltralightContent =
        ultralightOf(capture.pages, capture.uidHex, capture.counters)

    private fun ultralightOf(
        pages: List<ByteArray>,
        uidHex: String,
        counters: List<ByteArray>,
    ): UltralightContent {
        val cc = parseCc(pages)
        val ndef = if (cc == null) {
            NdefContent.absent
        } else {
            val end = minOf(pages.size * PAGE, CC_PAGE * PAGE + PAGE + cc.capacityBytes)
            val lastPage = minOf(pages.size, end / PAGE)
            if (lastPage <= CC_PAGE + 1) {
                NdefContent.absent
            } else {
                NdefParser.parseTlv(flatten(pages, CC_PAGE + 1, lastPage))
            }
        }

        return UltralightContent(
            uidHex = uidHex,
            pages = pages.size,
            cc = cc,
            ndef = ndef,
            counters = counters.map { counter ->
                val value = (counter[0].toInt() and 0xFF) or
                    ((counter[1].toInt() and 0xFF) shl 8) or
                    ((counter[2].toInt() and 0xFF) shl 16)
                MfuCounter(
                    value = if (value == COUNTER_UNSUPPORTED) null else value,
                    tearing = counter.size > 3 && counter[3] != 0.toByte(),
                )
            },
        )
    }

    private fun classicNdef(
        bytes: ByteArray,
        sectors: Int,
        readable: Set<Int>,
        ndefSectors: List<Int>,
    ): NdefContent {
        if (ndefSectors.isEmpty()) return NdefContent.absent
        val data = mutableListOf<Byte>()
        for (sector in ndefSectors) {
            if (sector >= sectors) return NdefContent(emptyList(), 0, NdefStatus.TRUNCATED)
            val first = MfcGeometry.firstBlockOfSector(sector)
            val count = MfcGeometry.blocksInSector(sector)
            val trailer = first + count - 1
            for (block in first until trailer) {
                if (block !in readable) {
                    return NdefContent(emptyList(), data.size, NdefStatus.TRUNCATED)
                }
                block(bytes, block).forEach { data += it }
            }
        }
        return NdefParser.parseTlv(data.toByteArray())
    }

    private fun roleOf(sector: Int, entry: MadEntry?, mad: MadDirectory?): SectorRole = when {
        sector == 0 -> if (mad != null) SectorRole.MAD1 else SectorRole.MANUFACTURER
        sector == MAD2_SECTOR && mad != null && mad.version == 2 -> SectorRole.MAD2
        entry?.aid == MadParser.AID_NDEF -> SectorRole.NDEF
        else -> SectorRole.DATA
    }

    private fun parseCc(pages: List<ByteArray>): CapabilityContainer? {
        if (pages.size <= CC_PAGE) return null
        val page = pages[CC_PAGE]
        if (page.size < 4) return null
        if ((page[0].toInt() and 0xFF) != CC_MAGIC) return null
        val version = page[1].toInt() and 0xFF
        val access = page[3].toInt() and 0xFF
        return CapabilityContainer(
            versionMajor = (version ushr 4) and 0x0F,
            versionMinor = version and 0x0F,
            capacityBytes = (page[2].toInt() and 0xFF) * 8,
            readAccess = (access ushr 4) and 0x0F,
            writeAccess = access and 0x0F,
        )
    }

    private fun flatten(pages: List<ByteArray>, fromPage: Int, toPage: Int): ByteArray {
        val out = ByteArray((toPage - fromPage).coerceAtLeast(0) * PAGE)
        for (index in 0 until out.size / PAGE) {
            val page = pages.getOrNull(fromPage + index) ?: break
            page.copyInto(out, index * PAGE, 0, minOf(PAGE, page.size))
        }
        return out
    }

    private fun block(bytes: ByteArray, index: Int): ByteArray =
        bytes.copyOfRange(index * BLOCK, index * BLOCK + BLOCK)

    private fun isReadable(readable: Set<Int>, first: Int, count: Int): Boolean =
        (first until first + count).all { it in readable }
}
