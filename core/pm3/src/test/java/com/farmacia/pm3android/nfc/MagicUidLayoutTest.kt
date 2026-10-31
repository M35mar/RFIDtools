// Layout tests for the UID tool. No tag is needed: block 0 and Type 2 pages
// are built and verified as plain byte arrays.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicUidLayoutTest {

    @Test
    fun `parses four and seven byte UIDs`() {
        assertEquals("11223344", MagicUidLayout.parseUid("11223344")!!.toHex())
        assertEquals("04112233445566", MagicUidLayout.parseUid("04:11:22:33:44:55:66")!!.toHex())
        assertNull(MagicUidLayout.parseUid("112233"))
        assertNull(MagicUidLayout.parseUid("1122334455667788"))
        assertNull(MagicUidLayout.parseUid(""))
    }

    @Test
    fun `parses six byte key`() {
        assertEquals("FFFFFFFFFFFF", MagicUidLayout.parseKey("FFFFFFFFFFFF")!!.toHex())
        assertNull(MagicUidLayout.parseKey("FFFFFFFF"))
        assertNull(MagicUidLayout.parseKey("FFFFFFFFFFFF00"))
    }

    @Test
    fun `four byte block zero keeps SAK ATQA and manufacturer bytes`() {
        val oldUid = hex("AABBCCDD")
        val current = hex("AABBCCDD000804000102030405060708")
        val newUid = hex("01020304")

        val plan = MagicUidLayout.buildClassic(
            MagicUidFamily.CLASSIC_1K,
            newUid,
            current,
            oldUid,
        )

        assertEquals(0, plan.uidOffset)
        assertEquals(4, plan.bccOffset)
        assertArrayEquals(hex("01020304040804000102030405060708"), plan.classicBlock0)
        assertTrue(MagicUidLayout.verifyClassicBlock0(plan, plan.classicBlock0!!))
    }

    @Test
    fun `four byte fallback uses Classic 1K SAK and ATQA`() {
        val plan = MagicUidLayout.buildClassic(
            MagicUidFamily.CLASSIC_1K,
            hex("01020304"),
            null,
            hex("AABBCCDD"),
        )

        assertArrayEquals(hex("01020304040804000000000000000000"), plan.classicBlock0)
    }

    @Test
    fun `seven byte UID already at byte zero keeps its layout`() {
        val oldUid = hex("04112233445566")
        val current = hex("0411223344556673080400AABBCCDDEE")
        val newUid = hex("04010203040506")

        val plan = MagicUidLayout.buildClassic(
            MagicUidFamily.CLASSIC_1K,
            newUid,
            current,
            oldUid,
        )

        assertEquals(0, plan.uidOffset)
        assertEquals(7, plan.bccOffset)
        assertArrayEquals(
            hex("0401020304050603080400AABBCCDDEE"),
            plan.classicBlock0,
        )
    }

    @Test
    fun `seven byte documented layout starts with manufacturer byte`() {
        val oldUid = hex("11223344556677")
        val current = hex("041122334455667700080400AABBCCDD")
        val newUid = hex("04112233445566")

        val plan = MagicUidLayout.buildClassic(
            MagicUidFamily.CLASSIC_4K,
            newUid,
            current,
            oldUid,
        )

        assertEquals(1, plan.uidOffset)
        assertEquals(8, plan.bccOffset)
        assertArrayEquals(
            hex("040411223344556673080400AABBCCDD"),
            plan.classicBlock0,
        )
    }

    @Test
    fun `seven byte fallback uses documented magic layout`() {
        val plan = MagicUidLayout.buildClassic(
            MagicUidFamily.CLASSIC_1K,
            hex("04112233445566"),
            null,
            hex("11111111111111"),
        )

        assertEquals(1, plan.uidOffset)
        assertEquals(8, plan.bccOffset)
        assertArrayEquals(
            hex("04041122334455667308040000000000"),
            plan.classicBlock0,
        )
    }

    @Test
    fun `ultralight pages recompute both BCCs and keep internal bytes`() {
        val uid = hex("04A1B2C3D4E5F6")
        val current = hex("0411223300112233010203FF")

        val plan = MagicUidLayout.buildUltralight(uid, current)
        val pages = plan.ultralightPages!!

        assertEquals(3, pages.size)
        assertArrayEquals(hex("04A1B29F"), pages[0])
        assertArrayEquals(hex("C3D4E5F6"), pages[1])
        assertArrayEquals(hex("040203FF"), pages[2])
        assertTrue(
            MagicUidLayout.verifyUltralight(
                plan,
                pages[0] + pages[1] + pages[2],
            ),
        )
    }

    @Test
    fun `ultralight verification rejects an old UID`() {
        val plan = MagicUidLayout.buildUltralight(
            hex("04A1B2C3D4E5F6"),
            hex("0411223300112233010203FF"),
        )
        val old = hex("0411223300112233010203FF")

        assertFalse(MagicUidLayout.verifyUltralight(plan, old))
    }

    @Test
    fun `classic verification rejects an unchanged block zero`() {
        val oldUid = hex("AABBCCDD")
        val plan = MagicUidLayout.buildClassic(
            MagicUidFamily.CLASSIC_1K,
            hex("01020304"),
            hex("AABBCCDD000804000102030405060708"),
            oldUid,
        )

        assertFalse(
            MagicUidLayout.verifyClassicUid(
                plan,
                hex("AABBCCDD000804000102030405060708"),
            ),
        )
    }

    @Test
    fun `BCC helpers follow ISO 14443 A rules`() {
        assertEquals(
            hex("44")[0],
            MagicUidLayout.xor(hex("11223344")),
        )
        assertEquals(
            hex("9F")[0],
            MagicUidLayout.bcc0(hex("04A1B2C3D4E5F6")),
        )
        assertEquals(
            hex("04")[0],
            MagicUidLayout.bcc1(hex("04A1B2C3D4E5F6")),
        )
    }

    private fun hex(value: String): ByteArray {
        val clean = value.replace(" ", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { index ->
            ((clean[index * 2].digitToInt(16) shl 4) or
                clean[index * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
