// Curated AID table for the phone inspector. It is deliberately small and
// hand checked: only identifiers that change what the app shows are listed, so
// an unknown card falls back to the raw AID instead of a wrong name.
//
// Sources: the Calypso and NXP entries of the Proxmark3 `aidlist.json`, the
// Calypso Networks Association audit tool prefixes and the EMV scheme AIDs.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.hexToBytesOrNull
import com.farmacia.pm3android.nfc.toHex

/** One catalogue row. [aid] is uppercase hex, matched as a prefix. */
class AidInfo(
    val aid: String,
    val label: String?,
    val provider: String?,
    val kind: ApplicationKind,
    val family: TagFamily? = null,
)

object AidCatalog {

    /** RID used by the newer Calypso cards (Navigo, Andante, MOBIB, ...). */
    const val CALYPSO_RID_NEW = "A000000291"

    /** RID used by the Setra/BNEVT family and by several French networks. */
    const val CALYPSO_RID_SETRA = "A000000404"

    /**
     * Legacy Calypso application identifiers: the ASCII name of the network,
     * for example `1TIC.ICA`, `3MTR.ICA`, `0ETP.ICA`.
     */
    val CALYPSO_LEGACY = listOf(
        "315449432E494341", // 1TIC.ICA
        "304554502E494341", // 0ETP.ICA
        "324D50502E494341", // 2MPP.ICA
        "334D54522E494341", // 3MTR.ICA
        "54494C414D4F32", // TILAMO2
    )

    /** Every AID prefix that makes a card a Calypso card. */
    private val CALYPSO_PREFIXES = CALYPSO_LEGACY + listOf(CALYPSO_RID_NEW, CALYPSO_RID_SETRA)

    /** The standard MIFARE Plus / DESFire Light RID. */
    const val NXP_RID = "A000000396564341"

    private val ENTRIES = listOf(
        // Calypso Networks Association generic prefixes.
        AidInfo(CALYPSO_RID_NEW, null, "Calypso Networks Association", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo(CALYPSO_RID_SETRA, null, "Calypso Networks Association", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("315449432E494341", "1TIC.ICA", "Calypso Networks Association", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("304554502E494341", "0ETP.ICA", "Calypso Networks Association", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("324D50502E494341", "2MPP.ICA", "Calypso Networks Association", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("334D54522E494341", "3MTR.ICA", "Calypso Networks Association", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("54494C414D4F32", "TILAMO2", "Lignes d'Azur", ApplicationKind.OTHER, TagFamily.CALYPSO),
        // Calypso transit applications.
        AidInfo("A00000040401250901", "Navigo", "Ile-de-France Mobilites", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A00000040401250057", "E-ticket", "TCL Lyon", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A00000040401250069", "Lignes d'Azur", "Lignes d'Azur", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A00000040401250091", "CTS Strasbourg", "CTS", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A00000040401250920", "La Carte / ZOU", "Lignes d'Azur", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A00000040402250920", "ZOU", "ZOU", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A00000040441250920", "ZOU", "ZOU", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A000000291D1240400", "OPUS", "OPUS", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A000000291D6200002", "Andante", "Transportes Metropolitanos do Porto", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A000000291D6200001", "navegante", "Transportes Metropolitanos de Lisboa", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("A000000291D05600", "MOBIB", "MOBIB", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("315449432E494341D05600", "MOBIB", "MOBIB", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("315449432E494341D4840101", "Tarjeta de Movilidad", "SEMOVI", ApplicationKind.OTHER, TagFamily.CALYPSO),
        AidInfo("334D54522E494341D4840101", "Tarjeta de Movilidad", "SEMOVI", ApplicationKind.OTHER, TagFamily.CALYPSO),
        // NXP.
        AidInfo("A00000039656434103F015400000000B", "MIFARE DESFire Light", "NXP", ApplicationKind.OTHER, TagFamily.DESFIRE),
        AidInfo("A000000396564341FFFFFFFFFFFFFFFF", "MIFARE Plus", "NXP", ApplicationKind.OTHER, TagFamily.MIFARE_PLUS),
        // NFC Forum.
        AidInfo("D2760000850101", "NDEF Tag Application", "NFC Forum", ApplicationKind.NDEF),
        AidInfo("D2760000850100", "NDEF Tag Application", "NFC Forum", ApplicationKind.NDEF),
        // Payment schemes.
        AidInfo("A0000000031010", "Visa", "Visa", ApplicationKind.EMV),
        AidInfo("A0000000032010", "Visa Electron", "Visa", ApplicationKind.EMV),
        AidInfo("A0000000033010", "Visa Interlink", "Visa", ApplicationKind.EMV),
        AidInfo("A0000000041010", "Mastercard", "Mastercard", ApplicationKind.EMV),
        AidInfo("A0000000043060", "Maestro", "Mastercard", ApplicationKind.EMV),
        AidInfo("A0000000050001", "Maestro UK", "Mastercard", ApplicationKind.EMV),
        AidInfo("A00000002501", "American Express", "American Express", ApplicationKind.EMV),
        AidInfo("A00000006510", "JCB", "JCB", ApplicationKind.EMV),
        AidInfo("A0000001523010", "Discover", "Discover", ApplicationKind.EMV),
        AidInfo("A0000002771010", "Interac", "Interac", ApplicationKind.EMV),
        AidInfo("A0000003330101", "UnionPay", "China UnionPay", ApplicationKind.EMV),
        AidInfo("A0000003241010", "Diners Club", "Discover", ApplicationKind.EMV),
        AidInfo("A0000004760101", "Bancontact", "Bancontact", ApplicationKind.EMV),
        // secure elements and transit platforms.
        AidInfo("A000000507", "CIPURSE", "OSPT Alliance", ApplicationKind.CIPURSE),
        AidInfo("A000000308000010000100", "PIV", "NIST", ApplicationKind.PIV),
    )

    /** The exact row or the most specific prefix match, null when unknown. */
    fun find(aid: ByteArray): AidInfo? = find(aid.toHex())

    fun find(aidHex: String): AidInfo? {
        val needle = aidHex.uppercase()
        if (needle.isEmpty()) return null
        return ENTRIES
            .filter { needle.startsWith(it.aid) }
            .maxByOrNull { it.aid.length }
    }

    /** True when the DF name is a Calypso application identifier. */
    fun isCalypso(dfName: ByteArray): Boolean {
        val hex = dfName.toHex().uppercase()
        return CALYPSO_PREFIXES.any { hex.startsWith(it) }
    }

    /** Curated name of a Calypso DF name, null when the table has no row. */
    fun calypsoLabel(dfName: ByteArray): String? = find(dfName)?.label

    /** Describes a plain ISO-DEP application from its AID. */
    fun application(aid: ByteArray): ApplicationDetails {
        val info = find(aid)
        return ApplicationDetails(
            aid = aid.copyOf(),
            label = info?.label,
            kind = info?.kind ?: ApplicationKind.OTHER,
            provider = info?.provider,
        )
    }

    /** True for the AIDs worth an ISO SELECT when a tag is ISO-DEP. */
    fun isKnown(aid: ByteArray): Boolean = find(aid) != null

    /**
     * AIDs to try, in order, when looking for a Calypso application: the two
     * RIDs first (a card accepts its RID as a prefix), then the legacy ASCII
     * identifiers, then the full applications of the table. Longest first, so
     * the name shown is the most specific one.
     */
    fun calypsoCandidates(): List<ByteArray> {
        val hex = buildList {
            add(CALYPSO_RID_SETRA)
            add(CALYPSO_RID_NEW)
            addAll(CALYPSO_LEGACY)
            ENTRIES
                .filter { it.family == TagFamily.CALYPSO }
                .map { it.aid }
                .sortedByDescending { it.length }
                .forEach { add(it) }
        }
        return hex.distinct().mapNotNull { it.hexToBytesOrNull() }
    }
}
