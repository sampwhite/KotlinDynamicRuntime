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
 * the definition editor and the write check read from them.
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
            CLD.extendsFromClientId, CLD.domainPrefix, CLD.customDomain, CLD.webResourcesId, CLD.userLabels, CLD.sandbox,
        )
        ClientPresentationFields.names shouldContainExactly listOf(
            CLD.name, CLD.description, CLD.domainPrefix, CLD.customDomain, CLD.webResourcesId, CLD.userLabels,
        )
        ClientFieldPolicies.platformFields shouldContainExactly listOf(
            CLD.clientId, CLD.customerId, CLD.usageType, CLD.audience, CLD.enabledEnvironments, CLD.extendsFromClientId,
            CLD.preload, CLD.staticConfig, CLD.testFeatures,
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
