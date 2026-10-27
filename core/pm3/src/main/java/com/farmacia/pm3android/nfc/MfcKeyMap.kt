// Key map for MIFARE Classic, modelled on MIFARE Classic Tool's
// `MCReader.buildNextKeyMapPart`: a flat list of six-byte keys is tried as Key A
// and as Key B on every sector, and the keys that opened a sector are moved to
// the front so the next sector usually needs two attempts.
//
// The chosen key file is only a source of candidates: it never decides what is
// written. That is what makes a restore work on a tag whose UID (and therefore
// whose keys) differs from the dump.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

/** Key A and Key B proven on one sector; null when that key is unknown. */
internal class SectorKeys(val keyA: ByteArray?, val keyB: ByteArray? = null) {

    fun sessionFor(requirement: MfcKeyRequirement): Session? = when (requirement) {
        MfcKeyRequirement.ANY -> keyA?.let { Session(it, true) }
            ?: keyB?.let { Session(it, false) }

        MfcKeyRequirement.A -> keyA?.let { Session(it, true) }
        MfcKeyRequirement.B -> keyB?.let { Session(it, false) }
        MfcKeyRequirement.NONE -> null
    }

    /** Key A when available, otherwise Key B (or the reverse). */
    fun sessionForAny(preferA: Boolean = true): Session? =
        if (preferA) {
            keyA?.let { Session(it, true) } ?: keyB?.let { Session(it, false) }
        } else {
            keyB?.let { Session(it, false) } ?: keyA?.let { Session(it, true) }
        }
}

/** One proven key: its bytes and whether it was accepted as Key A. */
internal class Session(val key: ByteArray, val keyA: Boolean) {

    fun sameAs(other: Session): Boolean =
        keyA == other.keyA && key.contentEquals(other.key)
}

internal object MfcKeyMap {

    private const val KEY_BYTES = 6

    /** Every usable six-byte key, deduplicated, in the order given. */
    fun flatten(vararg sources: List<ByteArray>): List<ByteArray> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<ByteArray>()
        for (source in sources) {
            for (key in source) {
                if (key.size != KEY_BYTES) continue
                if (key.all { it == 0.toByte() }) continue
                if (seen.add(key.toHex())) out += key.copyOf()
            }
        }
        return out
    }

    /**
     * Tries every key as Key A and Key B on every sector. A sector that no key
     * opens is simply missing from the result, so the caller can report it.
     *
     * @param onSectorDone called after each sector, for the progress bar.
     */
    fun build(
        sectorCount: Int,
        keys: List<ByteArray>,
        authenticate: (sector: Int, key: ByteArray, keyA: Boolean) -> Boolean,
        onSectorDone: ((Int) -> Unit)? = null,
    ): Map<Int, SectorKeys> {
        val order = keys.toMutableList()
        val result = LinkedHashMap<Int, SectorKeys>()

        for (sector in 0 until sectorCount) {
            var keyA: ByteArray? = null
            var keyB: ByteArray? = null
            for (candidate in order) {
                if (keyA == null && authenticate(sector, candidate, true)) {
                    keyA = candidate
                }
                if (keyB == null && authenticate(sector, candidate, false)) {
                    keyB = candidate
                }
                if (keyA != null && keyB != null) break
            }
            if (keyA != null || keyB != null) {
                result[sector] = SectorKeys(keyA?.copyOf(), keyB?.copyOf())
                // Key reuse is very likely: try the keys that just worked first.
                val found = listOfNotNull(keyA, keyB)
                order.removeAll { candidate -> found.any { it.contentEquals(candidate) } }
                order.addAll(0, found)
            }
            onSectorDone?.invoke(sector)
        }
        return result
    }
}
