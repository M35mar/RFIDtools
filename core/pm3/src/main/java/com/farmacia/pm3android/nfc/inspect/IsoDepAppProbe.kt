// Generic ISO-DEP application probe: the PPSE of a payment card and the NFC
// Forum NDEF application. Both are read with a plain ISO 7816 SELECT, so an
// EMV card is named without running any payment command.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.io.ByteArrayOutputStream

object IsoDepAppProbe {

    private const val SW_OK = 0x9000
    private const val SW_FILE_NOT_FOUND = 0x6A82

    /**
     * Tries the PPSE, then the NFC Forum NDEF application, then the two
     * secure-element applets that are worth naming without any credential.
     * Returns null when the card refuses all of them, which is the normal
     * answer of a proprietary applet.
     */
    fun probe(transport: IsoDepTransport): ApplicationDetails? {
        payment(transport)?.let { return it }
        ndef(transport)?.let { return it }
        applet(transport)?.let { return it }
        return null
    }

    private fun payment(transport: IsoDepTransport): ApplicationDetails? {
        val response = select(transport, PPSE) ?: return null
        if (response.sw != SW_OK) return null
        val aid = BerTlv.find(response.data, 0x4F)?.takeIf { it.isNotEmpty() } ?: return null
        val label = BerTlv.find(response.data, 0x50)
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("") { byte -> (byte.toInt() and 0xFF).toChar().toString() }
            ?.trim()
        val base = AidCatalog.application(aid)
        return ApplicationDetails(
            aid = base.aid,
            label = label?.takeIf { it.isNotEmpty() } ?: base.label,
            kind = ApplicationKind.EMV,
            provider = base.provider,
        )
    }

    private fun ndef(transport: IsoDepTransport): ApplicationDetails? {
        val response = select(transport, NDEF_AID) ?: return null
        if (response.sw != SW_OK) return null
        val base = AidCatalog.application(NDEF_AID)
        return ApplicationDetails(
            aid = base.aid,
            label = base.label,
            kind = ApplicationKind.NDEF,
            provider = base.provider,
        )
    }

    /** CIPURSE and PIV: both answer SELECT by DF name with a plain FCI. */
    private fun applet(transport: IsoDepTransport): ApplicationDetails? {
        for (aid in KNOWN_APPLETS) {
            val response = select(transport, aid) ?: continue
            if (response.sw != SW_OK) continue
            return AidCatalog.application(aid)
        }
        return null
    }

    private class Response(val data: ByteArray, val sw: Int)

    private fun select(transport: IsoDepTransport, name: ByteArray): Response? = try {
        val body = ByteArrayOutputStream()
        body.write(0x00)
        body.write(0xA4)
        body.write(0x04)
        body.write(0x00)
        body.write(name.size)
        body.write(name)
        body.write(0x00)
        val answer = transport.transceive(body.toByteArray())
        if (answer.size < 2) {
            null
        } else {
            val sw = ((answer[answer.size - 2].toInt() and 0xFF) shl 8) or
                (answer[answer.size - 1].toInt() and 0xFF)
            if (sw == SW_FILE_NOT_FOUND) Response(ByteArray(0), sw)
            else Response(answer.copyOfRange(0, answer.size - 2), sw)
        }
    } catch (_: Throwable) {
        null
    }

    private val PPSE = "2PAY.SYS.DDF01".toByteArray(Charsets.ISO_8859_1)

    private val NDEF_AID = byteArrayOf(
        0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
    )

    /** OSPT CIPURSE and NIST PIV, the two applets the inspector can name. */
    private val KNOWN_APPLETS = listOf(
        byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x05, 0x07),
        byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x03, 0x08, 0x00, 0x00, 0x10,
            0x00, 0x01, 0x00,
        ),
    )
}
