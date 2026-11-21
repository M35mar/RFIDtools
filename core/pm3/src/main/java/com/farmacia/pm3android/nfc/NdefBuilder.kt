// Pure-Kotlin NDEF payload builder for URI, Text, Wi-Fi (WSC) and blank records.
//
// Kept free of android.nfc.* types so every encoding rule (URI prefix table,
// UTF-8 text header, Wi-Fi Simple Configuration TLV) is covered by JVM unit
// tests without mocking the Android framework.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import java.io.ByteArrayOutputStream

enum class NdefWriteKind {
    URI,
    TEXT,
    WIFI,
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
        // Network Index (0x1026): 0x01
        writeTlv(cred, 0x1026, byteArrayOf(0x01))
        // SSID (0x1045)
        writeTlv(cred, 0x1045, ssidBytes)
        // Authentication Type (0x1003)
        writeTlv(cred, 0x1003, uint16(auth.authType))
        // Encryption Type (0x100F)
        writeTlv(cred, 0x100F, uint16(auth.encType))
        // Network Key (0x1027)
        if (keyBytes.isNotEmpty()) {
            writeTlv(cred, 0x1027, keyBytes)
        }
        // MAC Address (0x1020): wildcard FF:FF:FF:FF:FF:FF
        writeTlv(cred, 0x1020, ByteArray(6) { 0xFF.toByte() })

        val out = ByteArrayOutputStream()
        // Credential container (0x100E)
        writeTlv(out, 0x100E, cred.toByteArray())
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
