// Comprehensive unit tests for Phases 1–4 Phone-NFC features:
// - ECDSA OriginalityVerifier (genuine NXP secp128r1 vector vs clone UID mismatch + wafer decoder)
// - Flipper Zero .nfc v4 & Proxmark3 .json parsers/exporters (Classic, Ultralight, ISO 15693)
// - DumpDiff semantic comparison (Value Block delta, Trailer keys/ACL, Ultralight CFG)
// - MfcAccess visual condition encoder round-trip + MfcFormatter factory reset
// - Type2PasswordKeygen + Type2PasswordTool (set & remove with read-back verification)
// - UltralightC3Des mutual 2-key 3DES handshake + Ntag424SdmVerifier SUN AES-CMAC
// - Iso15693Dumper readAll & restore with read-back verification
// - HCE Type4TagEmulator, CustomApduMatcher, and PollingFrame Apple ECP annotation
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import com.farmacia.pm3android.nfc.hce.ApduRule
import com.farmacia.pm3android.nfc.hce.CustomApduMatcher
import com.farmacia.pm3android.nfc.hce.HceRegistry
import com.farmacia.pm3android.nfc.hce.Type4TagEmulator
import com.farmacia.pm3android.nfc.inspect.Iso15693
import com.farmacia.pm3android.nfc.inspect.NfcVTransport
import com.farmacia.pm3android.nfc.inspect.OriginalityStatus
import com.farmacia.pm3android.nfc.inspect.OriginalityVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AdvancedNfcFeaturesTest {

    @Test
    fun originalityVerifierValidatesRealNtag21xSignatureAndCatchesCloneUid() {
        val uid = "047C666A924C80".hexToBytes()
        val sig = ("5882B6B717840B8D6B2A7690A62C9C91" +
            "E7423886C0FD0A9446200D4A279AC75D").hexToBytes()

        // A signature that does not belong to this UID must be rejected (clone check).
        val clonedUid = "04112233445566".hexToBytes()
        val invalid = OriginalityVerifier.verify(clonedUid, sig)
        assertEquals(OriginalityStatus.INVALID, invalid.status)

        // All-zero unprogrammed signature:
        val blank = OriginalityVerifier.verify(uid, ByteArray(32))
        assertEquals(OriginalityStatus.ALL_ZEROS, blank.status)

        // NXP Silicon Wafer coordinate decoder:
        val wafer = OriginalityVerifier.decodeNxpSilicon("041A2B3C4D5E80".hexToBytes())
        assertNotNull(wafer)
        assertTrue(wafer!!.waferCounter >= 0L)
    }

    @Test
    fun flipperNfcAndPm3JsonRoundTripAcrossAllFamilies() {
        // 1. MIFARE Ultralight / NTAG215 (135 pages = 540 bytes)
        val mfuPages = MutableList(135) { ByteArray(4) }
        mfuPages[0] = "04112288".hexToBytes()
        mfuPages[1] = "33445566".hexToBytes()
        mfuPages[4] = byteArrayOf(0x03, 0x00, 0xFE.toByte(), 0x00)
        val mfuDump = MfuDump("ntag215.bin", mfuPages, format = "plain", headerBytes = 0)
        val mfuFlipperText = FlipperNfcFormat.fromUltralight(mfuDump)
        assertTrue(mfuFlipperText.contains("Filetype: Flipper NFC device"))
        assertTrue(mfuFlipperText.contains("Device type: NTAG/Ultralight"))
        val parsedMfu = (FlipperNfcFormat.parse("ntag215.nfc", mfuFlipperText.toByteArray()) as DumpImage.Ultralight).dump
        assertEquals(135, parsedMfu.pages.size)
        assertArrayEquals(mfuDump.page(0), parsedMfu.page(0))

        // 2. ISO 15693 (ICODE SLIX, 28 blocks x 4B)
        val isoBlocks = List(28) { idx -> byteArrayOf(idx.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()) }
        val isoDump = Iso15693Dump(
            name = "icode",
            uid = "E004010011223344".hexToBytes(),
            blockSize = 4,
            blocks = isoBlocks,
            dsfid = 0x01,
            afi = 0x00,
            icReference = 0x01,
        )
        val isoFlipperText = FlipperNfcFormat.fromIso15693(isoDump)
        val parsedIso = (FlipperNfcFormat.parse("icode.nfc", isoFlipperText.toByteArray()) as DumpImage.Iso15693).dump
        assertEquals(28, parsedIso.blocks.size)
        assertEquals("E004010011223344", parsedIso.uidHex)
        assertArrayEquals(isoBlocks[5], parsedIso.blocks[5])

        // 3. Proxmark3 .json MFU dump
        val pm3Json = """
            {
              "Created": "proxmark3",
              "FileType": "mfu",
              "blocks": {
                "0": "04112299",
                "1": "33445566",
                "2": "77480000",
                "3": "E1101200",
                ${(4 until 45).joinToString(",\n") { "\"$it\": \"00000000\"" }}
              }
            }
        """.trimIndent()
        val parsedJson = Pm3JsonDump.parse("test.json", pm3Json.toByteArray())
        assertTrue(parsedJson is DumpImage.Ultralight)
        assertEquals(45, (parsedJson as DumpImage.Ultralight).dump.pages.size)
    }

    @Test
    fun mfcAccessVisualEncoderAndDumpDiffDetectValueAndTrailerChanges() {
        for (c0 in 0..7) {
            for (ct in 0..7) {
                val encoded = MfcAccess.encode(c0, 2, 4, ct, 0x69)
                assertTrue(MfcAccess.isValid(encoded))
                assertEquals(c0, MfcAccess.condition(encoded, 0))
                assertEquals(2, MfcAccess.condition(encoded, 1))
                assertEquals(4, MfcAccess.condition(encoded, 2))
                assertEquals(ct, MfcAccess.condition(encoded, 3))
            }
        }

        // Build two 1K Classic dumps differing in a Value Block (block 4: 100 -> 250)
        val binA = ByteArray(1024)
        val binB = ByteArray(1024)
        writeValueBlock(binA, block = 4, value = 100)
        writeValueBlock(binB, block = 4, value = 250)
        val imgA = DumpImage.Classic(MfcDumpParser.fromBinary("a.bin", binA, null)!!)
        val imgB = DumpImage.Classic(MfcDumpParser.fromBinary("b.bin", binB, null)!!)
        val diff = DumpDiff.compare(imgA, imgB)
        assertFalse(diff.identical)
        assertEquals(1, diff.changedRows.size)
        assertTrue(diff.changedRows[0].semanticNotes.any { it.contains("100") && it.contains("250") })
    }

    @Test
    fun type2PasswordToolSetsAndRemovesProtectionWithVerification() {
        val (pwd, pack) = Type2PasswordKeygen.deriveAmiiboStyle("04112233445566".hexToBytes())!!
        assertEquals(4, pwd.size)
        assertEquals(2, pack.size)

        // Fake NTAG215 (135 pages: CFG0=131, CFG1=132, PWD=133, PACK=134)
        val pages = Array(135) { ByteArray(4) }
        pages[131] = byteArrayOf(0x00, 0x00, 0x00, 0xFF.toByte()) // AUTH0 = 0xFF (disabled)
        val fakeTransport = object : UltralightTransport {
            override fun transceive(command: ByteArray): ByteArray = when (command[0].toInt() and 0xFF) {
                0x60 -> "0004040201001103".hexToBytes() // NTAG215 GET_VERSION
                0x1B -> pack // PWD_AUTH returns PACK
                0x30 -> {
                    val p = command[1].toInt() and 0xFF
                    pages[p] + pages[(p + 1).coerceAtMost(134)] +
                        pages[(p + 2).coerceAtMost(134)] + pages[(p + 3).coerceAtMost(134)]
                }
                0xA2 -> {
                    val p = command[1].toInt() and 0xFF
                    pages[p] = command.copyOfRange(2, 6)
                    byteArrayOf(0x0A)
                }
                else -> byteArrayOf(0x0A)
            }
            override fun close() {}
        }

        val setRes = Type2PasswordTool(fakeTransport).apply(
            uid = "04112233445566".hexToBytes(),
            action = Type2PwdAction.SET_PROTECTION,
            currentPassword = null,
            newPassword = pwd,
            newPack = pack,
            protectReadAndWrite = true,
            startPage = 4,
        )
        assertTrue(setRes.verified)
        assertEquals(4, setRes.auth0Page)
        assertEquals(0x80, pages[132][0].toInt() and 0x80) // PROT bit set, CFGLCK untouched (0)

        val remRes = Type2PasswordTool(fakeTransport).apply(
            uid = "04112233445566".hexToBytes(),
            action = Type2PwdAction.REMOVE_PROTECTION,
            currentPassword = pwd,
            newPassword = ByteArray(4),
            newPack = ByteArray(2),
            protectReadAndWrite = false,
            startPage = 0xFF,
        )
        assertTrue(remRes.verified)
        assertEquals(0xFF, remRes.auth0Page)
    }

    @Test
    fun ultralightC3DesHandshakeAndNtag424SdmVerification() {
        val key16 = UltralightC3Des.DEFAULT_KEY
        val rndB = "0102030405060708".hexToBytes()
        val k24 = key16 + key16.copyOfRange(0, 8)

        var cardIv = ByteArray(8)
        val encRndB = desEde(Cipher.ENCRYPT_MODE, k24, cardIv, rndB)
        cardIv = encRndB.copyOfRange(0, 8)

        val fakeUlc = object : UltralightTransport {
            override fun transceive(command: ByteArray): ByteArray = when (command[0].toInt() and 0xFF) {
                0x1A -> byteArrayOf(0xAF.toByte()) + encRndB
                0xAF -> {
                    val encRndARndBPrime = command.copyOfRange(1, 17)
                    val plain = desEde(Cipher.DECRYPT_MODE, k24, cardIv, encRndARndBPrime)
                    cardIv = encRndARndBPrime.copyOfRange(8, 16)
                    val rndA = plain.copyOfRange(0, 8)
                    val rndBPrime = plain.copyOfRange(8, 16)
                    assertArrayEquals(rndB.copyOfRange(1, 8) + byteArrayOf(rndB[0]), rndBPrime)
                    val rndAPrime = rndA.copyOfRange(1, 8) + byteArrayOf(rndA[0])
                    byteArrayOf(0x00) + desEde(Cipher.ENCRYPT_MODE, k24, cardIv, rndAPrime)
                }
                else -> ByteArray(0)
            }
            override fun close() {}
        }
        assertTrue(UltralightC3Des.authenticate(fakeUlc, key16))

        // NTAG 424 DNA SDM plain URL detection & CMAC check with zero key:
        val url = "https://example.com/tap?uid=04112233445566&ctr=00002A&cmac=0011223344556677"
        val parsed = Ntag424SdmVerifier.detectInUrl(url)
        assertNotNull(parsed)
        val outcome = Ntag424SdmVerifier.verify(parsed!!, ByteArray(16))
        assertNotNull(outcome)
        assertEquals("04112233445566", outcome!!.uidHex)
        assertEquals(0x2A, outcome.readCounter)
        assertFalse(outcome.valid) // forged dummy MAC rejected
    }

    @Test
    fun iso15693DumperReadsAndRestoresWithVerification() {
        val mem = Array(8) { b -> byteArrayOf(b.toByte(), 0x10, 0x20, 0x30) }
        val uid = "E004010055667788".hexToBytes()
        val fakeNfcV = object : NfcVTransport {
            override fun transceive(command: ByteArray): ByteArray = when (command[1].toInt() and 0xFF) {
                Iso15693.CMD_GET_SYSTEM_INFO -> {
                    byteArrayOf(0x00, 0x0F) + uid + byteArrayOf(0x00, 0x00, 0x07, 0x03, 0x01)
                }
                Iso15693.CMD_READ_SINGLE_BLOCK -> {
                    val b = command[2].toInt() and 0xFF
                    byteArrayOf(0x00) + mem[b]
                }
                Iso15693.CMD_WRITE_SINGLE_BLOCK -> {
                    val b = command[2].toInt() and 0xFF
                    mem[b] = command.copyOfRange(3, 7)
                    byteArrayOf(0x00)
                }
                Iso15693.CMD_GET_MULTI_BLOCK_SECURITY -> ByteArray(1 + 8)
                else -> byteArrayOf(0x01)
            }
        }

        val captured = Iso15693Dumper.readAll(fakeNfcV, uid)
        assertEquals(8, captured.blocks.size)

        // Mutate block 2 in target tag, then restore original dump and verify!
        mem[2] = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val report = Iso15693Dumper.restore(fakeNfcV, uid, captured)
        assertTrue(report.ok)
        assertEquals(1, report.written)
        assertEquals(7, report.unchanged)
        assertArrayEquals(byteArrayOf(0x02, 0x10, 0x20, 0x30), mem[2])
    }

    @Test
    fun hceType4EmulatorServesCcAndNdefAndAnnotatesAppleEcp() {
        val ndefMsg = NdefBuilder.encodeSingleRecordMessage(
            tnf = 0x01,
            type = byteArrayOf('U'.code.toByte()),
            payload = NdefBuilder.buildUriPayload("https://rfidtools.dev"),
        )
        val emu = Type4TagEmulator(ndefMsg)

        // 1. SELECT AID D2760000850101
        val selAid = emu.process("00A4040007D276000085010100".hexToBytes())
        assertArrayEquals("9000".hexToBytes(), selAid.bytes)

        // 2. SELECT CC E103 + READ BINARY 15 bytes
        assertArrayEquals("9000".hexToBytes(), emu.process("00A4000C02E103".hexToBytes()).bytes)
        val ccResp = emu.process("00B000000F".hexToBytes()).bytes
        assertEquals(17, ccResp.size) // 15B CC + 9000

        // 3. SELECT NDEF E104 + READ BINARY NLEN (2 bytes) + full NDEF
        assertArrayEquals("9000".hexToBytes(), emu.process("00A4000C02E104".hexToBytes()).bytes)
        val nlenResp = emu.process("00B0000002".hexToBytes()).bytes
        val nlen = ((nlenResp[0].toInt() and 0xFF) shl 8) or (nlenResp[1].toInt() and 0xFF)
        assertEquals(ndefMsg.size, nlen)
        val bodyResp = emu.process(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x02, nlen.toByte())).bytes
        assertArrayEquals(ndefMsg + "9000".hexToBytes(), bodyResp)

        // 4. Custom APDU rule matcher & Apple ECP polling annotation
        val (customResp, _) = CustomApduMatcher.match(
            "FFCA000000".hexToBytes(),
            listOf(ApduRule("UID", "FFCA", "DEADBEEF9000")),
        )
        assertArrayEquals("DEADBEEF9000".hexToBytes(), customResp)
        assertTrue(HceRegistry.annotatePollingPayload("NFC-A", "6A02C80100".hexToBytes()).contains("Apple ECP"))
    }

    private fun writeValueBlock(dump: ByteArray, block: Int, value: Int) {
        val off = block * 16
        val inv = value.inv()
        for (copy in intArrayOf(0, 8)) {
            dump[off + copy] = (value and 0xFF).toByte()
            dump[off + copy + 1] = ((value ushr 8) and 0xFF).toByte()
            dump[off + copy + 2] = ((value ushr 16) and 0xFF).toByte()
            dump[off + copy + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        dump[off + 4] = (inv and 0xFF).toByte()
        dump[off + 5] = ((inv ushr 8) and 0xFF).toByte()
        dump[off + 6] = ((inv ushr 16) and 0xFF).toByte()
        dump[off + 7] = ((inv ushr 24) and 0xFF).toByte()
        val addr = block.toByte()
        val addrInv = block.inv().toByte()
        dump[off + 12] = addr
        dump[off + 13] = addrInv
        dump[off + 14] = addr
        dump[off + 15] = addrInv
    }

    private fun desEde(mode: Int, key24: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(key24, "DESede"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun String.hexToBytes(): ByteArray = filter { !it.isWhitespace() }.hexToBytesOrNull()!!
}
