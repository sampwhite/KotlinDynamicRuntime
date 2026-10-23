package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The client definition's field policies (issue #1108): one per field the schema declares, and the lists the create,
 * the definition editor, and the write check read from them.
 */
class ClientFieldPoliciesTest : StringSpec({
    val cxt = KdrCxt("policies", KdrInstanceConfig("policies", ENV.local, ENV.liveSource))

    "every field the definition's schema declares has a policy, and nothing else does" {
        val declared = ClientDefSchema.defs(cxt)[CLD.writtenInfoTypeQualified].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        ClientFieldPolicies.byField.keys shouldBe declared.keys
    }

    "the platform's create, the editor and the platform's own fields are read from the policies" {
        ClientFieldPolicies.createFields shouldContainExactly listOf(
            CLD.clientId, CLD.name, CLD.description, CLD.customerId, CLD.usageType, CLD.audience, CLD.enabledEnvironments,
            CLD.extendsFromClientId, CLD.domainPrefix, CLD.customDomain, CLD.webResourcesId, CLD.timeZone, CLD.userLabels,
            CLD.sandbox, CLD.preload, CLD.sessionHours, CLD.refreshIntervalSeconds,
        )
        // The client's own administrator edits these (issue #1116); an administrator who sees every client these too.
        ClientPresentationFields.names shouldContainExactly
            listOf(CLD.name, CLD.description, CLD.timeZone, CLD.userLabels, CLD.sessionHours)
        // The refresh interval is the platform's (issue #1308): every open tab asks that often.
        ClientPresentationFields.platformNames shouldContainExactly listOf(
            CLD.refreshIntervalSeconds, CLD.usageType, CLD.audience, CLD.enabledEnvironments, CLD.preload, CLD.domainPrefix, CLD.customDomain, CLD.webResourcesId,
        )
        ClientFieldPolicies.platformFields shouldContainExactly listOf(
            CLD.clientId, CLD.customerId, CLD.usageType, CLD.audience, CLD.enabledEnvironments, CLD.extendsFromClientId,
            CLD.domainPrefix, CLD.customDomain, CLD.webResourcesId, CLD.preload, CLD.staticConfig, CLD.retired, CLD.testFeatures,
            CLD.joinRoles, CLD.refreshIntervalSeconds,
        )
    }

    // Issue #1112: a client made by another takes the creator's choices and what its maker passes on, nothing else.
    "a delegated definition is the creator's choices, the parent's passed-on fields, and nothing a create clears" {
        val parent = mapOf(
            CLD.clientId to "acme", CLD.name to "Acme", CLD.customerId to "acme", CLD.usageType to "production",
            CLD.audience to "customer", CLD.enabledEnvironments to listOf("unit", "local"), CLD.webResourcesId to "acmeWeb",
            CLD.timeZone to "America/Chicago", CLD.domainPrefix to "acme", CLD.userLabels to listOf("reviewer"),
        )
        val given = mapOf(CLD.clientId to "acme_east", CLD.name to "East", CLD.audience to "internal", CLD.sandbox to true)
        ClientFieldPolicies.delegatedDefinition(parent, given) shouldBe mapOf(
            CLD.clientId to "acme_east", CLD.name to "East", CLD.customerId to "acme", CLD.usageType to "production",
            CLD.audience to "customer", CLD.enabledEnvironments to listOf("unit", "local"), CLD.webResourcesId to "acmeWeb",
            CLD.timeZone to "America/Chicago", CLD.sandbox to true,
        )
    }

    "a platform field is changed by a different value, not by another order or an absent default" {
        val stored = mapOf(
            CLD.clientId to "x", CLD.audience to "internal", CLD.usageType to "dev", CLD.enabledEnvironments to listOf("unit", "local"),
        )
        val same = stored + mapOf(CLD.enabledEnvironments to listOf("local", "unit"), CLD.preload to false, CLD.testFeatures to emptyList<String>())
        ClientFieldPolicies.platformChangesBy(same, stored).shouldBeEmpty()
        ClientFieldPolicies.platformChangesBy(stored + (CLD.enabledEnvironments to listOf("unit")), stored) shouldBe
            listOf(CLD.enabledEnvironments)
        // A client with no definition starts from the least authority.
        ClientFieldPolicies.platformChangesBy(mapOf(CLD.audience to "internal", CLD.usageType to "production"), null) shouldBe
            listOf(CLD.audience)
    }
})
