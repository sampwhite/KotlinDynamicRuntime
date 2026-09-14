package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The one place a stored config's `testFeatures` is redacted for emission (issue #696): `GedraConfigRow`, the
 * source both config reads draw from -- the whole-bundle read (via [GedraConfigRow.slotsForEmission]) and the
 * trait-level read (via [GedraConfigRow.entriesForEmission]). Off a test instance the stored client definition's
 * `testFeatures` is stripped; on a test instance it stands. Testing it here, at the source, covers every reader
 * at once -- the leak that slipped through when the redaction sat on one emission path only.
 */
class GedraConfigRowEmissionTest : StringSpec({

    fun row(testFeatures: List<String>): GedraConfigRow =
        GedraConfigRow(GedraId.of(GedraConfigType.configDoc, "acme", "main", "1"), "acme").also {
            it.entries = listOf(
                mapOf(
                    GE.traitId to CCT.clientDef,
                    GE.data to mapOf(CLD.clientId to "acme", CLD.name to "Acme", CLD.testFeatures to testFeatures),
                ),
                mapOf(GE.traitId to CCT.cfactDef, GE.data to mapOf(CCT.name to "ready")),
            )
        }

    "off a test instance, testFeatures is stripped from the stored client def and the rest is untouched" {
        val out = row(listOf("demoFeature")).entriesForEmission(isTestInstance = false)
        val clientDef = out.first { it[GE.traitId] == CCT.clientDef }[GE.data].toJsonMapOrEmpty()
        clientDef.containsKey(CLD.testFeatures) shouldBe false
        clientDef[CLD.clientId] shouldBe "acme"
        // A non-clientDef entry passes through as it stands.
        out.first { it[GE.traitId] == CCT.cfactDef }[GE.data].toJsonMapOrEmpty()[CCT.name] shouldBe "ready"
    }

    "on a test instance, testFeatures stands" {
        val out = row(listOf("demoFeature")).entriesForEmission(isTestInstance = true)
        out.first { it[GE.traitId] == CCT.clientDef }[GE.data].toJsonMapOrEmpty()[CLD.testFeatures] shouldBe listOf("demoFeature")
    }

    "the grouped bundle view redacts at the same source" {
        val out = row(listOf("demoFeature")).slotsForEmission(isTestInstance = false)
        out.getValue(CCT.clientDef).single().containsKey(CLD.testFeatures) shouldBe false
    }
})
