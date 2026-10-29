// Authenticated read of one MIFARE Plus sector in SL3. The user supplies the
// AES key of the sector; the key number is derived from the sector and from the
// requested key (A or B).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

object PlusReader {

    fun readSector(
        transport: RawTagTransport,
        key: ByteArray,
        sector: Int,
        keyB: Boolean,
    ): PlusSectorRead {
        val keyNumber = PlusSession.keyNumber(sector, keyB)
        val session = PlusSession.authenticate(transport, keyNumber, key)
        val first = PlusGeometry.firstBlock(sector)
        val count = PlusGeometry.blockCount(sector)

        // Three blocks per frame keeps the answer within the phone's NfcA
        // transceive limit, as the Proxmark3 client does.
        val blocks = mutableListOf<PlusBlock>()
        var block = first
        while (blocks.size < count) {
            val take = minOf(3, count - blocks.size)
            val data = session.readBlocks(transport, block, take)
            data.forEachIndexed { index, bytes ->
                blocks += PlusBlock(number = block + index, data = bytes)
            }
            block += take
        }

        return PlusSectorRead(
            sector = sector,
            keyB = keyB,
            blocks = blocks,
        )
    }
}
