// Pure layout engine for writing a new UID into a magic MIFARE card.
//
// The Android NFC API can only write the manufacturer block through a normal
// WRITE (Gen2/CUID) or through the Gen3 APDU commands. Both paths need a valid
// block 0 / Type 2 pages, so the construction is kept here without any Android
// dependency and covered by unit tests.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

/** Card families the UID tool can handle. */
enum class MagicUidFamily(val label: String) {
    CLASSIC_1K("MIFARE Classic 1K"),
    CLASSIC_4K("MIFARE Classic 4K"),
    ULTRALIGHT_EV1("MIFARE Ultralight EV1"),
}

/** How the UID was actually written, shown in the result card. */
enum class MagicUidMethod {
    /** Normal WRITE to block 0 (Gen2 / CUID), verified by reading it back. */
    CLASSIC_DIRECT,

    /** Gen3 APDU 90 F0 CC CC 10 with a complete block 0. */
    CLASSIC_APDU,

    /** Gen3 APDU 90 FB CC CC 07: changes the UID only. */
    CLASSIC_APDU_UID,

    /** Three consecutive Type 2 WRITEs to pages 0, 1 and 2. */
    ULTRALIGHT_DIRECT,
}

/** A complete write plan: what to send and where the UID lives. */
data class MagicUidPlan(
    val family: MagicUidFamily,
    val uid: ByteArray,
    /** Classic manufacturer block (16 bytes), null for Ultralight. */
    val classicBlock0: ByteArray? = null,
    /** Byte offset of the UID inside [classicBlock0]. */
    val uidOffset: Int = 0,
    /** Byte offset of the BCC inside [classicBlock0]. */
    val bccOffset: Int = 0,
    /** Pages 0..2 for Ultralight, null for Classic. */
    val ultralightPages: List<ByteArray>? = null,
)

object MagicUidLayout {

    const val UID_4_BYTES = 4
    const val UID_7_BYTES = 7
    const val KEY_BYTES = 6

    /** Parses exactly [expectedBytes] hex bytes, ignoring spaces and separators. */
    fun parseHex(text: String, expectedBytes: Int): ByteArray? {
        val digits = text.filter { it.isHexDigit() }
        if (digits.length != expectedBytes * 2) return null
        val out = ByteArray(expectedBytes)
        for (index in out.indices) {
            val high = digits[index * 2].digitToIntOrNull(16) ?: return null
            val low = digits[index * 2 + 1].digitToIntOrNull(16) ?: return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /** 4 or 7 bytes from 8 or 14 hex digits, or null when malformed. */
    fun parseUid(text: String): ByteArray? {
        val digits = text.filter { it.isHexDigit() }
        return when (digits.length) {
            UID_4_BYTES * 2 -> parseHex(digits, UID_4_BYTES)
            UID_7_BYTES * 2 -> parseHex(digits, UID_7_BYTES)
            else -> null
        }
    }

    /** Six-byte key from 12 hex digits, or null when malformed. */
    fun parseKey(text: String): ByteArray? =
        parseHex(text, KEY_BYTES)

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun xor(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Byte {
        var value = 0
        for (index in from until until) value = value xor (bytes[index].toInt() and 0xFF)
        return value.toByte()
    }

    /**
     * Builds block 0 for a 4-byte Classic card: UID, BCC, SAK, ATQA and the
     * manufacturer bytes are preserved from [current] when it is available.
     */
    fun buildClassic(
        family: MagicUidFamily,
        uid: ByteArray,
        current: ByteArray?,
        currentCardUid: ByteArray,
    ): MagicUidPlan {
        require(family == MagicUidFamily.CLASSIC_1K || family == MagicUidFamily.CLASSIC_4K)
        require(uid.size == UID_4_BYTES || uid.size == UID_7_BYTES)
        val current16 = current?.takeIf { it.size >= 16 }?.copyOf(16)

        return if (uid.size == UID_4_BYTES) {
            val block = current16 ?: defaultClassicBlock0(family, 4)
            uid.copyInto(block, 0)
            block[4] = xor(uid)
            MagicUidPlan(
                family = family,
                uid = uid,
                classicBlock0 = block,
                uidOffset = 0,
                bccOffset = 4,
            )
        } else {
            buildClassic7(family, uid, current16, currentCardUid)
        }
    }

    /**
     * A 7-byte block 0 has two known layouts in the wild:
     *  - UID at bytes 0..6, BCC at 7, SAK at 8, ATQA at 9..10;
     *  - manufacturer byte 0x04 at 0, UID at 1..7, BCC at 8, SAK at 9,
     *    ATQA at 10..11 (the documented magic-card layout).
     * The current block is compared with the presented UID to keep the layout
     * the card already uses. When neither matches, the documented layout is
     * used.
     */
    private fun buildClassic7(
        family: MagicUidFamily,
        uid: ByteArray,
        current: ByteArray?,
        currentCardUid: ByteArray,
    ): MagicUidPlan {
        val cardUidAtZero = current != null &&
            currentCardUid.size == UID_7_BYTES &&
            current.copyOfRange(0, 7).contentEquals(currentCardUid)
        val cardUidAtOne = current != null &&
            currentCardUid.size == UID_7_BYTES &&
            current.copyOfRange(1, 8).contentEquals(currentCardUid)

        val offset = if (cardUidAtZero && !cardUidAtOne) 0 else 1
        val bccOffset = offset + 7
        val block = current ?: defaultClassicBlock0(family, 7)
        if (offset == 1) block[0] = 0x04
        uid.copyInto(block, offset)
        block[bccOffset] = xor(uid)
        return MagicUidPlan(
            family = family,
            uid = uid,
            classicBlock0 = block,
            uidOffset = offset,
            bccOffset = bccOffset,
        )
    }

    /** Block 0 used when the current one cannot be read. */
    fun defaultClassicBlock0(family: MagicUidFamily, uidBytes: Int): ByteArray {
        val block = ByteArray(16)
        if (uidBytes == UID_4_BYTES) {
            block[5] = if (family == MagicUidFamily.CLASSIC_4K) 0x18 else 0x08
            block[6] = if (family == MagicUidFamily.CLASSIC_4K) 0x02 else 0x04
        } else {
            block[0] = 0x04
            block[9] = if (family == MagicUidFamily.CLASSIC_4K) 0x18 else 0x08
            block[10] = if (family == MagicUidFamily.CLASSIC_4K) 0x02 else 0x04
        }
        return block
    }

    /**
     * Builds pages 0..2 of an Ultralight EV1. Internal and lock bytes of page 2
     * are copied from [currentPages] (pages 0..3 as returned by READ 30 00).
     */
    fun buildUltralight(uid: ByteArray, currentPages: ByteArray): MagicUidPlan {
        require(uid.size == UID_7_BYTES)
        require(currentPages.size >= 12) { "servono almeno 3 pagine lette" }
        val page0 = byteArrayOf(uid[0], uid[1], uid[2], bcc0(uid))
        val page1 = uid.copyOfRange(3, 7)
        val page2 = byteArrayOf(
            bcc1(uid),
            currentPages[9],
            currentPages[10],
            currentPages[11],
        )
        return MagicUidPlan(
            family = MagicUidFamily.ULTRALIGHT_EV1,
            uid = uid,
            ultralightPages = listOf(page0, page1, page2),
        )
    }

    /** BCC0 = 0x88 XOR UID0..2 (the CT byte is always 0x88 for Type 2). */
    fun bcc0(uid: ByteArray): Byte {
        require(uid.size == UID_7_BYTES)
        return (0x88 xor (xor(uid, 0, 3).toInt() and 0xFF)).toByte()
    }

    /** BCC1 = UID3 XOR UID4 XOR UID5 XOR UID6. */
    fun bcc1(uid: ByteArray): Byte {
        require(uid.size == UID_7_BYTES)
        return xor(uid, 3, 7)
    }

    /** True when [actual] block 0 contains the planned UID at the planned offset. */
    fun verifyClassicUid(plan: MagicUidPlan, actual: ByteArray): Boolean {
        if (plan.classicBlock0 == null) return false
        if (actual.size < 16) return false
        return actual.copyOfRange(plan.uidOffset, plan.uidOffset + plan.uid.size)
            .contentEquals(plan.uid)
    }

    /** Strict block-0 check, used by tests and for the byte-for-byte read-back. */
    fun verifyClassicBlock0(plan: MagicUidPlan, actual: ByteArray): Boolean {
        val block = plan.classicBlock0 ?: return false
        if (actual.size < 16) return false
        return actual.copyOfRange(0, 16).contentEquals(block)
    }

    /** True when the first three pages read back contain the planned pages. */
    fun verifyUltralight(plan: MagicUidPlan, actual: ByteArray): Boolean {
        val pages = plan.ultralightPages ?: return false
        if (actual.size < 12) return false
        for (index in pages.indices) {
            val start = index * 4
            if (!actual.copyOfRange(start, start + 4).contentEquals(pages[index])) return false
        }
        return true
    }
}
