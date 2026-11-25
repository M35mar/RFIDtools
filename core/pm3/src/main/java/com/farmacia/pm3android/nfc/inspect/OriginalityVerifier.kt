// Pure-Kotlin ECDSA originality signature verifier + NXP silicon wafer decoder.
//
// Uses BigInteger Weierstrass elliptic-curve arithmetic (secp128r1, secp192r1,
// secp224r1) so it runs identically on JVM unit tests and every Android version
// without relying on Conscrypt/BouncyCastle curve availability.
//
// Public keys & curves match Proxmark3's client/src/crypto/originality.c
// (GPL-3.0-or-later).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.hexToBytesOrNull
import java.math.BigInteger

enum class OriginalityGroup {
    MFC,
    MFUL,
    MFULAES,
    MFP,
    MFDES,
    ISO15693,
    ST25,
    ALL,
}

enum class OriginalityStatus {
    VALID,
    INVALID,
    ALL_ZEROS,
    UNSUPPORTED_LENGTH,
}

data class OriginalityResult(
    val status: OriginalityStatus,
    val keyName: String? = null,
    val curveName: String? = null,
)

data class NxpSiliconInfo(
    val waferCounter: Long,
    val waferX: Int,
    val waferY: Int,
    val testSite: Int,
)

object OriginalityVerifier {

    private class Curve(
        val name: String,
        val coordBytes: Int,
        pHex: String,
        aHex: String,
        bHex: String,
        gxHex: String,
        gyHex: String,
        nHex: String,
    ) {
        val p = BigInteger(pHex, 16)
        val a = BigInteger(aHex, 16)
        val b = BigInteger(bHex, 16)
        val n = BigInteger(nHex, 16)
        val g = Point(BigInteger(gxHex, 16), BigInteger(gyHex, 16))
    }

    private data class Point(val x: BigInteger, val y: BigInteger) {
        companion object {
            val INFINITY = Point(BigInteger.ZERO, BigInteger.ZERO)
        }
        val isInfinity: Boolean get() = this === INFINITY
    }

    private val SECP128R1 = Curve(
        name = "secp128r1",
        coordBytes = 16,
        pHex = "FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF",
        aHex = "FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFC",
        bHex = "E87579C11079F43DD824993C2CEE5ED3",
        gxHex = "161FF7528B899B2D0C28607CA52C5B86",
        gyHex = "CF5AC8395BAFEB13C02DA292DDED7A83",
        nHex = "FFFFFFFE0000000075A30D1B9038A115",
    )

    private val SECP192R1 = Curve(
        name = "secp192r1",
        coordBytes = 24,
        pHex = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFF",
        aHex = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFC",
        bHex = "64210519E59C80E70FA7E9AB72243049FEB8DEECC146B9B1",
        gxHex = "188DA80EB03090F67CBF20EB43A18800F4FF0AFD82FF1012",
        gyHex = "07192B95FFC8DA78631011ED6B24CDD573F977A11E794811",
        nHex = "FFFFFFFFFFFFFFFFFFFFFFFF99DEF836146BC9B1B4D22831",
    )

    private val SECP224R1 = Curve(
        name = "secp224r1",
        coordBytes = 28,
        pHex = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF000000000000000000000001",
        aHex = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFE",
        bHex = "B4050A850C04B3ABF54132565044B0B7D7BFD8BA270B39432355FFB4",
        gxHex = "B70E0CBD6BB4BF7F321390B94A03C1D356C21122343280D6115C1D21",
        gyHex = "BD376388B5F723FB4C22DFE6CD4375A05A07476444D5819985007E34",
        nHex = "FFFFFFFFFFFFFFFFFFFFFFFFFFFF16A2E0B8F03E13DD29455C5C2A3D",
    )

    private class PubKeyEntry(
        val group: OriginalityGroup,
        val curve: Curve,
        val name: String,
        val uncompressedHex: String,
    ) {
        val point: Point? by lazy {
            val raw = uncompressedHex.hexToBytesOrNull() ?: return@lazy null
            if (raw.size != 1 + curve.coordBytes * 2 || raw[0] != 0x04.toByte()) return@lazy null
            val x = BigInteger(1, raw.copyOfRange(1, 1 + curve.coordBytes))
            val y = BigInteger(1, raw.copyOfRange(1 + curve.coordBytes, raw.size))
            Point(x, y).takeIf { isOnCurve(curve, it) }
        }
    }

    private val KEYS = listOf(
        PubKeyEntry(
            OriginalityGroup.MFUL, SECP128R1, "NXP NTAG21x",
            "04494E1A386D3D3CFE3DC10E5DE68A499B1C202DB5B132393E89ED19FE5BE8BC61",
        ),
        PubKeyEntry(
            OriginalityGroup.MFUL, SECP128R1, "NXP MIFARE Ultralight EV1",
            "0490933BDCD6E99B4E255E3DA55389A827564E11718E017292FAF23226A96614B8",
        ),
        PubKeyEntry(
            OriginalityGroup.MFUL, SECP128R1, "NXP NTAG210/212",
            "04A748B6A632FBEE2C0897702B33BEA1C074998E17B84ACA04FF267E5D2C91F6DC",
        ),
        PubKeyEntry(
            OriginalityGroup.MFUL, SECP128R1, "Mikron Type 2",
            "04F971EDA742A4A80D32DCF6A814A707CC3DC396D35902F72929FDCD698B3468F2",
        ),
        PubKeyEntry(
            OriginalityGroup.ST25, SECP128R1, "ST25TN TruST25 (ST)",
            "0440004F974F7C76BC8718E523D85FA7B354A9A992BFA966CB8219242F9D274FD6",
        ),
        PubKeyEntry(
            OriginalityGroup.ST25, SECP128R1, "ST25TA TruST25 (ST)",
            "041D92163650161A2548D33881C235D0FB2315C2C31A442F23C87ACF14497C0CBA",
        ),
        PubKeyEntry(
            OriginalityGroup.ST25, SECP128R1, "ST25TV TruST25 (ST)",
            "04101E188A8B4CDDBC62D5BC3E0E6850F0C2730E744B79765A0E079907FBDB01BC",
        ),
        PubKeyEntry(
            OriginalityGroup.ISO15693, SECP128R1, "NXP ICODE DNA / SLIX2",
            "048878A2A2D3EEC336B4F261A082BD71F9BE11C4E2E896648B32EFA59CEA6E59F0",
        ),
        PubKeyEntry(
            OriginalityGroup.ISO15693, SECP128R1, "NXP NTAG 5",
            "04494E1A386D3D3CFE3DC10E5DE68A499B1C202DB5B132393E89ED19FE5BE8BC61",
        ),
        PubKeyEntry(
            OriginalityGroup.ISO15693, SECP128R1, "VivoKey Spark1",
            "04D64BB732C0D214E7EC580736ACF847284B502C25C0F7F2FA86AACE1DADA4387A",
        ),
        PubKeyEntry(
            OriginalityGroup.MFC, SECP128R1, "NXP MIFARE Classic EV1",
            "044F6D3F294DEA5737F0F46FFEE88A356EED95695DD7E0C27A591E6F6F65962BAF",
        ),
        PubKeyEntry(
            OriginalityGroup.MFC, SECP128R1, "NXP MIFARE Classic QL88",
            "046F70AC557F5461CE5052C8E4A7838C11C7A236797E8A0730A101837C004039C2",
        ),
        PubKeyEntry(
            OriginalityGroup.MFULAES, SECP192R1, "NXP Ultralight AES",
            "0453BF8C49B7BD9FE3207A91513B9C1D238ECAB07186B772104AB535F7D3AE63CF7C7F3DD0D169DA3E99E43C6399621A86",
        ),
        PubKeyEntry(
            OriginalityGroup.MFULAES, SECP192R1, "NXP Ultralight AES (alt)",
            "04DC34DAA903F2726A6225B11C692AF6AB4396575CA12810CBBCE3F781A097B3833B50AB364A70D9C2B641A728A599AE74",
        ),
        PubKeyEntry(
            OriginalityGroup.MFULAES, SECP192R1, "NXP NTAG 22x DNA",
            "0485D5B9353B4FAA77581BA2AE96630C5876D6E8603308ABE9A81A0B506F52D02D04FEE6F2D365B3DEE7B9FAD9133E2976",
        ),
        PubKeyEntry(
            OriginalityGroup.MFP, SECP224R1, "NXP MIFARE Plus EV1",
            "044409ADC42F91A8394066BA83D872FB1D16803734E911170412DDF8BAD1A4DADFD0416291AFE1C748253925DA39A5F39A1C557FFACD34C62E",
        ),
        PubKeyEntry(
            OriginalityGroup.MFP, SECP224R1, "NXP MIFARE Plus EV2",
            "04BB49AE4447E6B1B6D21C098C1538B594A11A4A1DBF3D5E673DEACDEB3CC512D1C08AFA1A2768CE20A200BACD2DC7804CD7523A0131ABF607",
        ),
        PubKeyEntry(
            OriginalityGroup.MFDES, SECP224R1, "NXP NTAG 424 DNA / DESFire EV2",
            "04B304DC4C615F5326FE9383DDEC9AA892DF3A57FA7FFB3276192BC0EAA252ED45A865E3B093A3D0DCE5BE29E92F1392CE7DE321E3E5C52B3A",
        ),
        PubKeyEntry(
            OriginalityGroup.MFDES, SECP224R1, "NXP NTAG 424 DNA / DESFire Light",
            "048A9B380AF2EE1B98DC417FECC263F8449C7625CECE82D9B916C992DA209D68422B81EC20B65A66B5102A61596AF3379200599316A00A1410",
        ),
        PubKeyEntry(
            OriginalityGroup.MFDES, SECP224R1, "NXP NTAG 413 DNA / DESFire EV1",
            "04BB5D514F7050025C7D0F397310360EEC91EAF792E96FC7E0F496CB4E669D414F877B7B27901FE67C2E3B33CD39D1C797715189AC951C2ADD",
        ),
        PubKeyEntry(
            OriginalityGroup.MFDES, SECP224R1, "NXP DESFire EV3",
            "041DB46C145D0A36539C6544BD6D9B0AA62FF91EC48CBC6ABAE36E0089A46F0D08C8A715EA40A63313B92E90DDC1730230E0458A33276FB743",
        ),
    )

    /**
     * Verifies [signature] (raw R || S, 32, 48 or 56 bytes) over [uid] (raw
     * unhashed message, per NXP AN11350 / AN11341). When [tryReversed] is true
     * (ISO 15693 / ST25), also checks reversed UID + reversed signature.
     */
    fun verify(
        uid: ByteArray,
        signature: ByteArray,
        group: OriginalityGroup = OriginalityGroup.ALL,
        tryReversed: Boolean = false,
    ): OriginalityResult {
        if (signature.isEmpty() || signature.size !in setOf(32, 48, 56)) {
            return OriginalityResult(OriginalityStatus.UNSUPPORTED_LENGTH)
        }
        if (signature.all { it == 0.toByte() }) {
            return OriginalityResult(OriginalityStatus.ALL_ZEROS)
        }
        val coordLen = signature.size / 2
        val candidates = KEYS.filter { entry ->
            entry.curve.coordBytes == coordLen &&
                (group == OriginalityGroup.ALL || entry.group == group ||
                    (group == OriginalityGroup.MFUL && entry.group == OriginalityGroup.ST25) ||
                    (group == OriginalityGroup.ISO15693 && entry.group == OriginalityGroup.ST25))
        }

        for (entry in candidates) {
            val q = entry.point ?: continue
            if (verifyRaw(entry.curve, q, uid, signature)) {
                return OriginalityResult(OriginalityStatus.VALID, entry.name, entry.curve.name)
            }
            if (tryReversed) {
                val revUid = uid.reversedArray()
                val revSig = signature.reversedArray()
                if (verifyRaw(entry.curve, q, revUid, revSig)) {
                    return OriginalityResult(OriginalityStatus.VALID, entry.name, entry.curve.name)
                }
            }
        }
        // Fallback across all keys of matching length in case a cross-family key signed it.
        if (group != OriginalityGroup.ALL) {
            for (entry in KEYS.filter { it.curve.coordBytes == coordLen && it !in candidates }) {
                val q = entry.point ?: continue
                if (verifyRaw(entry.curve, q, uid, signature)) {
                    return OriginalityResult(OriginalityStatus.VALID, entry.name, entry.curve.name)
                }
            }
        }
        return OriginalityResult(OriginalityStatus.INVALID)
    }

    /** Decodes NXP wafer coordinates & counter from a 7-byte `04...` UID. */
    fun decodeNxpSilicon(uid: ByteArray): NxpSiliconInfo? {
        if (uid.size != 7 || (uid[0].toInt() and 0xFF) != 0x04) return null
        val u1 = uid[1].toInt() and 0xFF
        val u2 = uid[2].toInt() and 0xFF
        val u3 = uid[3].toInt() and 0xFF
        val u4 = uid[4].toInt() and 0xFF
        val u5 = uid[5].toInt() and 0xFF
        val u6 = uid[6].toInt() and 0xFF

        val waferX = ((u6 and 0x03) shl 8) or u1
        val waferY = ((u6 and 0x0C) shl 6) or u2
        val waferCounter = ((u4.toLong() shl 5) or
            (((u6 and 0xF0).toLong()) shl 17) or
            (u5.toLong() shl 13) or
            ((u3 ushr 3).toLong()))
        val testSite = u3 and 0x07
        return NxpSiliconInfo(waferCounter, waferX, waferY, testSite)
    }

    // -----------------------------------------------------------------------
    // Pure Weierstrass ECDSA verification (mbedTLS-equivalent, unhashed z)
    // -----------------------------------------------------------------------

    private fun verifyRaw(curve: Curve, q: Point, message: ByteArray, signature: ByteArray): Boolean {
        val half = curve.coordBytes
        if (signature.size != half * 2) return false
        val r = BigInteger(1, signature.copyOfRange(0, half))
        val s = BigInteger(1, signature.copyOfRange(half, signature.size))
        val n = curve.n
        if (r <= BigInteger.ZERO || r >= n || s <= BigInteger.ZERO || s >= n) return false

        // Per FIPS 186-4 / mbedtls_ecdsa_read_signature: derive integer e from
        // message bytes; if bit-length exceeds curve order bit-length, shift right.
        var e = BigInteger(1, message)
        val msgBits = message.size * 8
        val nBits = n.bitLength()
        if (msgBits > nBits) {
            e = e.shiftRight(msgBits - nBits)
        }
        e = e.mod(n)

        val w = try {
            s.modInverse(n)
        } catch (_: ArithmeticException) {
            return false
        }
        val u1 = e.multiply(w).mod(n)
        val u2 = r.multiply(w).mod(n)
        val p1 = scalarMul(curve, curve.g, u1)
        val p2 = scalarMul(curve, q, u2)
        val sum = pointAdd(curve, p1, p2)
        if (sum.isInfinity) return false
        return sum.x.mod(n) == r
    }

    private fun isOnCurve(curve: Curve, pt: Point): Boolean {
        if (pt.isInfinity) return false
        val p = curve.p
        val lhs = pt.y.multiply(pt.y).mod(p)
        val rhs = pt.x.modPow(BigInteger.valueOf(3), p)
            .add(curve.a.multiply(pt.x))
            .add(curve.b)
            .mod(p)
        return lhs == rhs
    }

    private fun pointAdd(curve: Curve, p1: Point, p2: Point): Point {
        if (p1.isInfinity) return p2
        if (p2.isInfinity) return p1
        val p = curve.p
        if (p1.x == p2.x) {
            if (p1.y.add(p2.y).mod(p) == BigInteger.ZERO) return Point.INFINITY
            return pointDouble(curve, p1)
        }
        val dx = p2.x.subtract(p1.x).mod(p)
        val dy = p2.y.subtract(p1.y).mod(p)
        val lambda = dy.multiply(dx.modInverse(p)).mod(p)
        val x3 = lambda.multiply(lambda).subtract(p1.x).subtract(p2.x).mod(p)
        val y3 = lambda.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(p)
        return Point(x3, y3)
    }

    private fun pointDouble(curve: Curve, pt: Point): Point {
        if (pt.isInfinity || pt.y == BigInteger.ZERO) return Point.INFINITY
        val p = curve.p
        val num = pt.x.multiply(pt.x).multiply(BigInteger.valueOf(3)).add(curve.a).mod(p)
        val den = pt.y.shiftLeft(1).mod(p)
        val lambda = num.multiply(den.modInverse(p)).mod(p)
        val x3 = lambda.multiply(lambda).subtract(pt.x.shiftLeft(1)).mod(p)
        val y3 = lambda.multiply(pt.x.subtract(x3)).subtract(pt.y).mod(p)
        return Point(x3, y3)
    }

    private fun scalarMul(curve: Curve, pt: Point, k: BigInteger): Point {
        if (k == BigInteger.ZERO || pt.isInfinity) return Point.INFINITY
        var result = Point.INFINITY
        var addend = pt
        var scalar = k
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0)) {
                result = pointAdd(curve, result, addend)
            }
            addend = pointDouble(curve, addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }
}
