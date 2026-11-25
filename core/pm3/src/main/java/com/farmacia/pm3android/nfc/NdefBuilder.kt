// Pure-Kotlin NDEF payload builder for URI, Text, Wi-Fi (WSC), vCard,
// Bluetooth OOB pairing, Android Application Record (AAR) and blank records,
// plus pure-Kotlin NDEF message framing for HCE Type 4 tag emulation.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.ByteArrayOutputStream

enum class NdefWriteKind {
    URI,
    TEXT,
    WIFI,
    VCARD,
    APP_LAUNCHER,
    BLUETOOTH,
    ERASE,
}

enum class WifiAuth(val authType: Int, val encType: Int) {
    /** WPA2-PSK + AES (standard modern Wi-Fi). */
    WPA2_PERSONAL(0x0020, 0x0008),

    /** WPA-PSK + TKIP/AES. */
    WPA_PERSONAL(0x0002, 0x000C),

    /** Open network, no password. */
    OPEN(0x0001, 0x0001),
}

object NdefBuilder {

    const val MIME_WIFI_WSC = "application/vnd.wfa.wsc"
    const val MIME_VCARD = "text/vcard"
    const val MIME_BT_OOB = "application/vnd.bluetooth.ep.oob"
    const val EXT_ANDROID_AAR = "android.com:pkg"

    private val URI_PREFIXES = listOf(
        "", // 0x00
        "http://www.",
        "https://www.",
        "http://",
        "https://",
        "tel:",
        "mailto:",
        "ftp://anonymous:anonymous@",
        "ftp://ftp.",
        "ftps://",
        "sftp://",
        "smb://",
        "nfs://",
        "ftp://",
        "dav://",
        "news:",
        "telnet://",
        "imap:",
        "rtsp://",
        "urn:",
        "pop:",
        "sip:",
        "sips:",
        "tftp:",
        "btspp://",
        "btl2cap://",
        "btgoep://",
        "tcpobex://",
        "irdaobex://",
        "file://",
        "urn:epc:id:",
        "urn:epc:tag:",
        "urn:epc:pat:",
        "urn:epc:raw:",
        "urn:epc:",
        "urn:nfc:",
    )

    /** Encodes a URI record payload (NFC Forum URI RTD: 1 prefix byte + UTF-8 body). */
    fun buildUriPayload(rawUri: String): ByteArray {
        val uri = rawUri.trim()
        require(uri.isNotEmpty()) { "URI must not be empty" }
        var bestCode = 0
        var bestLen = 0
        for (i in 1 until URI_PREFIXES.size) {
            val prefix = URI_PREFIXES[i]
            if (uri.startsWith(prefix, ignoreCase = false) && prefix.length > bestLen) {
                bestCode = i
                bestLen = prefix.length
            }
        }
        val body = uri.substring(bestLen).toByteArray(Charsets.UTF_8)
        return byteArrayOf(bestCode.toByte()) + body
    }

    /** Encodes a Text record payload (NFC Forum Text RTD: status + lang + UTF-8 text). */
    fun buildTextPayload(text: String, language: String = "en"): ByteArray {
        val langBytes = language.trim().ifEmpty { "en" }.take(63).toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(langBytes.size.toByte()) + langBytes + textBytes
    }

    /** Encodes a vCard 3.0 payload (`text/vcard`). */
    fun buildVCardPayload(
        name: String,
        phone: String = "",
        email: String = "",
        org: String = "",
    ): ByteArray {
        val fn = name.trim()
        require(fn.isNotEmpty()) { "vCard name must not be empty" }
        val vcard = buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("FN:").append(fn).append("\r\n")
            if (org.isNotBlank()) append("ORG:").append(org.trim()).append("\r\n")
            if (phone.isNotBlank()) append("TEL;TYPE=CELL:").append(phone.trim()).append("\r\n")
            if (email.isNotBlank()) append("EMAIL:").append(email.trim()).append("\r\n")
            append("END:VCARD\r\n")
        }
        return vcard.toByteArray(Charsets.UTF_8)
    }

    /**
     * Encodes Bluetooth Secure Simple Pairing OOB payload (`application/vnd.bluetooth.ep.oob`):
     * 2-byte little-endian total length + 6-byte reversed MAC + optional Local Name EIR (0x09).
     */
    fun buildBluetoothOobPayload(macText: String, deviceName: String = ""): ByteArray {
        val mac = macText.hexToBytesOrNull()?.takeIf { it.size == 6 }
            ?: throw IllegalArgumentException("Bluetooth MAC must be 6 hex bytes (e.g. AA:BB:CC:DD:EE:FF)")
        val macLe = mac.reversedArray()
        val nameBytes = deviceName.trim().toByteArray(Charsets.UTF_8)
        val eir = if (nameBytes.isNotEmpty()) {
            byteArrayOf((nameBytes.size + 1).toByte(), 0x09) + nameBytes
        } else {
            ByteArray(0)
        }
        val totalLen = 2 + 6 + eir.size
        return byteArrayOf((totalLen and 0xFF).toByte(), ((totalLen ushr 8) and 0xFF).toByte()) + macLe + eir
    }

    /**
     * Encodes a standard Wi-Fi Simple Configuration (WSC) Credential TLV payload
     * for MIME type `application/vnd.wfa.wsc` (supported natively by Android).
     */
    fun buildWifiWscPayload(
        ssid: String,
        password: String,
        auth: WifiAuth,
    ): ByteArray {
        val cleanSsid = ssid.trim()
        require(cleanSsid.isNotEmpty()) { "SSID must not be empty" }
        val ssidBytes = cleanSsid.toByteArray(Charsets.UTF_8)
        val keyBytes = if (auth == WifiAuth.OPEN) ByteArray(0) else password.toByteArray(Charsets.UTF_8)

        val cred = ByteArrayOutputStream()
        writeTlv(cred, 0x1026, byteArrayOf(0x01))
        writeTlv(cred, 0x1045, ssidBytes)
        writeTlv(cred, 0x1003, uint16(auth.authType))
        writeTlv(cred, 0x100F, uint16(auth.encType))
        if (keyBytes.isNotEmpty()) {
            writeTlv(cred, 0x1027, keyBytes)
        }
        writeTlv(cred, 0x1020, ByteArray(6) { 0xFF.toByte() })

        val out = ByteArrayOutputStream()
        writeTlv(out, 0x100E, cred.toByteArray())
        return out.toByteArray()
    }

    /**
     * Pure-Kotlin single-record NDEF message serializer (MB=1, ME=1, TNF + type + payload),
     * usable on JVM unit tests and HCE Type 4 emulation without `android.nfc.NdefMessage`.
     */
    fun encodeSingleRecordMessage(tnf: Int, type: ByteArray, payload: ByteArray): ByteArray {
        val shortRecord = payload.size <= 255
        val flags = 0x80 or 0x40 or (if (shortRecord) 0x10 else 0x00) or (tnf and 0x07)
        val out = ByteArrayOutputStream(8 + type.size + payload.size)
        out.write(flags)
        out.write(type.size)
        if (shortRecord) {
            out.write(payload.size)
        } else {
            out.write((payload.size ushr 24) and 0xFF)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
        }
        out.write(type)
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeTlv(out: ByteArrayOutputStream, type: Int, value: ByteArray) {
        out.write((type ushr 8) and 0xFF)
        out.write(type and 0xFF)
        out.write((value.size ushr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
    }

    private fun uint16(value: Int): ByteArray =
        byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
}
