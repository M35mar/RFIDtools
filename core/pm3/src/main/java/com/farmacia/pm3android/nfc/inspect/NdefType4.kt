// NDEF Type 4 reading over ISO-DEP: select the NFC Forum application, read
// the capability container and then the NDEF file. The same code is reused by
// the DESFire probe, because a DESFire card with an NDEF application is still
// a Type 4 tag.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.inspect.content.NdefContent
import com.farmacia.pm3android.nfc.inspect.content.NdefParser
import com.farmacia.pm3android.nfc.inspect.content.NdefStatus
import com.farmacia.pm3android.nfc.inspect.content.TagContent

object NdefType4 {

    /** NFC Forum NDEF Tag Application. */
    val NDEF_AID = byteArrayOf(
        0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
    )

    private const val CC_FILE_ID = 0xE103
    private const val CC_TAG_NDEF_FILE = 0x04
    /** CCLEN 2 + version 1 + MLe 2 + MLc 2 + TL 1 + L 1 + FID 2 + size 2 + R/W 2. */
    private const val CC_MIN_LENGTH = 15
    private const val MAX_NDEF_BYTES = 0xFFFF

    /**
     * Reads the NDEF message of a Type 4 tag. Returns null when the card has no
     * NDEF application; a malformed message is reported as truncated or
     * invalid instead of throwing.
     */
    fun read(transport: IsoDepTransport): TagContent? {
        val selection = Iso7816.select(transport, NDEF_AID)
        if (!selection.ok) return null

        val ccSelect = Iso7816.selectFile(transport, CC_FILE_ID)
        if (!ccSelect.ok) return null
        val cc = Iso7816.readBinary(transport, 0, CC_MIN_LENGTH)
        val container = cc.data
        if (container.size < CC_MIN_LENGTH) return null
        if ((container[7].toInt() and 0xFF) != CC_TAG_NDEF_FILE) return null
        if ((container[8].toInt() and 0xFF) < 6) return null

        val fileId = ((container[9].toInt() and 0xFF) shl 8) or
            (container[10].toInt() and 0xFF)
        val maxSize = ((container[11].toInt() and 0xFF) shl 8) or
            (container[12].toInt() and 0xFF)

        val fileSelect = Iso7816.selectFile(transport, fileId)
        if (!fileSelect.ok) return null

        val lengthBytes = Iso7816.readBinary(transport, 0, 2)
        if (lengthBytes.data.size < 2) return null
        val length = ((lengthBytes.data[0].toInt() and 0xFF) shl 8) or
            (lengthBytes.data[1].toInt() and 0xFF)
        if (length == 0) {
            return TagContent.ndefOnly(NdefContent(emptyList(), 0, NdefStatus.FOUND))
        }
        val wanted = minOf(length, maxSize.takeIf { it > 0 } ?: MAX_NDEF_BYTES)
        if (wanted <= 0) return null

        val message = Iso7816.readBinary(transport, 2, wanted)
        if (message.data.isEmpty()) return null
        return TagContent.ndefOnly(NdefParser.parseMessage(message.data))
    }
}
