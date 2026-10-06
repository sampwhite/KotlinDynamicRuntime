package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.SearchRole
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.formDocsQueryDefName
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The boot checks over a client's trait-usage rules (`SchemaService.checkUsageRules`), exercised through the real
 * startup path -- the wiring the pure detectors (`searchParamCollisions`, `duplicateUsageTraitIds`, both in
 * `GedraSearchTest`) cannot cover: that `checkInit` actually runs the checks, and that the config-check mode
 * decides their consequence (strict refuses, warn logs and boots).
 *
 * Both of `checkUsageRules`' sub-checks live here, so a boot refusal over a usage rule is one class rather than
 * one per variation: a usage whose **search parameter collides with a reserved listing field** (issue #538), and
 * **two usage rules for one trait id** (issue #681). Each fixture is gated on its own load flag, so it perturbs
 * no other boot; a spec sets the flag for the fixture it wants.
 */
class UsageRuleBootCheckTest : StringSpec({

    "two usages of one trait id refuse the boot in strict mode (issue #681)" {
        val thrown = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "dupUsageStrict", "dupUsageStrictTest",
                mapOf(DuplicateUsageComponent.loadFlag.name to "true"),
                additionalComponents = listOf(DuplicateUsageComponent()),
            )
        }
        thrown.fullMessage() shouldContain DuplicateUsageComponent.dupeTrait
        thrown.fullMessage() shouldContain "more than one trait-usage rule"
    }

    "the same duplicate only warns, and boots, when the check mode is warn (issue #681)" {
        // Warn is what production defaults to: the problem is logged and recorded, and the node starts -- so a
        // duplicate that slips past a non-prod boot degrades (the trait's column, search and sort read one of the
        // two rules) rather than taking a deployment down. Proven by the boot completing.
        shouldNotThrowAny {
            Startup.mkTestBootCxt(
                "dupUsageWarn", "dupUsageWarnTest",
                mapOf(
                    DuplicateUsageComponent.loadFlag.name to "true",
                    GCFG.checkEnvVar.name to BootCheckMode.warn.name,
                ),
                additionalComponents = listOf(DuplicateUsageComponent()),
            )
        }
    }

    "a usage whose search parameter collides with a reserved listing field refuses the boot (issue #538)" {
        // A usage on a trait named like a reserved query field asks for a parameter of that name; the check
        // refuses rather than let the trait become silently unsearchable.
        val thrown = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "reservedUsageStrict", "reservedUsageStrictTest",
                mapOf(ReservedFieldUsageComponent.loadFlag.name to "true"),
                additionalComponents = listOf(ReservedFieldUsageComponent()),
            )
        }
        thrown.fullMessage() shouldContain ReservedFieldUsageComponent.reservedTrait
        thrown.fullMessage() shouldContain "reserved forms-listing field"
    }

    "the same usages only warn in warn mode: the columns stay, the parameters are dropped, paging works (issue #987)" {
        // One trait is named for the cursor field, which the listing's type does not declare -- the framework
        // appends it to a cursor-paged listing -- so only the generator leaving it out keeps it off the type.
        // The other is named for the paging offset, the case where a filter still reading the dropped parameter
        // would take `offset=0` as the value to match.
        val cxt = Startup.mkTestBootCxt(
            "reservedUsageWarn", "reservedUsageWarnTest",
            mapOf(
                ReservedFieldUsageComponent.loadFlag.name to "true",
                GCFG.checkEnvVar.name to BootCheckMode.warn.name,
            ),
            additionalComponents = listOf(ReservedFieldUsageComponent()),
        )
        val schema = SchemaService.get(cxt)
        val client = ReservedFieldUsageComponent.reservedClient
        schema.traitUsagesFor(client).map { it.traitId } shouldBe
            listOf(ReservedFieldUsageComponent.reservedTrait, ReservedFieldUsageComponent.offsetTrait)
        val query = schema.storeFor(client).types.getValue(formDocsQueryDefName()).properties.keys
        query shouldNotContain ReservedFieldUsageComponent.reservedTrait
        query shouldNotContain ReservedFieldUsageComponent.reservedTrait + SearchRole.contains.nameSuffix
        query shouldContain EP.offset

        // Through the listing: a form whose `offset` trait reads "7" is on the first page asked for with
        // `offset=0`. Were the dropped parameter still read as a filter, "0" would match no row's "7".
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        fun entry(traitId: String, value: String) = mapOf(GE.traitId to traitId, GE.data to mapOf("value" to value))
        val doc = user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(entry(ReservedFieldUsageComponent.reservedTrait, "x"), entry(ReservedFieldUsageComponent.offsetTrait, "7"))),
        )
        val rows = user.getItems(GEP.formDocs, mapOf(EP.offset to 0))
        rows.map { it[GDF.gedraId] } shouldBe listOf(doc[GDF.gedraId])
        // Both columns show, dropped search or not.
        rows.single()[GDF.displayValues].toJsonListOfMaps().associate { it[UF.traitId] to it[UF.value] } shouldBe
            mapOf(ReservedFieldUsageComponent.reservedTrait to "x", ReservedFieldUsageComponent.offsetTrait to "7")
    }
})

/** Contributes a global config with two usage rules for one trait -- the mistake #681's check catches. */
class DuplicateUsageComponent : ComponentDefinition {
    override val providerName: String = "duplicateUsageFixture"

    /** The fixture's own owner root (issue #950). */
    override val ownerRoot: String = namespace

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "duplicateUsageConfig", namespace, GID.globalClient) {
            trait(dupeEntry, dupeTrait, setOf(GedraDataType.formDoc), "A trait carrying two fields for the #681 fixture.") {
                property("year", "The year.", required = true) { type = SCT.integer }
                property("note", "A note.")
            }
            // Two usages of the one trait: the display map, search and sort all key by trait id, so the second
            // collides with the first rather than adding a column. The boot check is what catches it.
            traitUsage(dupeTrait, "Year", $$"${year}", UsageKind.number)
            traitUsage(dupeTrait, "Note", $$"${note}", substring = true)
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_DUPLICATE_USAGE_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the duplicate-usage fixture component regardless of environment.",
        )
        const val namespace = "duplicateusagefixture"
        const val dupeTrait = "$namespace:dupeYearly"
        const val dupeEntry = "DupeYearlyEntry"
    }
}

/**
 * Contributes a client's config with usages minting search parameters that collide with reserved fields (#538,
 * #987): one on a trait named for the cursor field `after`, which the listing's type does not itself declare, and
 * one named for the paging `offset`, which it does. A client's, because only a client's trait id is bare (issue
 * #951), so only it can be named exactly like the field.
 */
class ReservedFieldUsageComponent : ComponentDefinition {
    override val providerName: String = "reservedFieldUsageFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "reservedUsageConfig", clientNamespace(reservedClient), reservedClient) {
            defineClient(
                ClientDef(
                    clientId = reservedClient, name = reservedClient, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait(reservedEntry, reservedTrait, setOf(GedraDataType.formDoc), "A trait named like the cursor field.") {
                property("value", "A value.")
            }
            trait(offsetEntry, offsetTrait, setOf(GedraDataType.formDoc), "A trait named like the paging offset.") {
                property("value", "A value.")
            }
            // A string usage on a trait id that is a reserved listing field name asks for an exact parameter of
            // the same name -- the collision #538 refuses. A substring usage on `after`, so the `afterContains`
            // beside it is shown to go with it.
            traitUsage(reservedTrait, "After", $$"${value}", UsageKind.string, substring = true)
            traitUsage(offsetTrait, "Offset", $$"${value}", UsageKind.string)
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_RESERVED_USAGE_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the reserved-field-usage fixture component regardless of environment.",
        )
        // The reserved fields' names -- a usage on a trait so named collides with them. Referencing the constants
        // keeps the collision a compile-time fact: rename `EP.after` or `EP.offset` and this fixture stops
        // compiling rather than silently declaring a trait that collides with nothing.
        const val reservedTrait = EP.after
        const val reservedEntry = "ReservedUsageEntry"
        const val offsetTrait = EP.offset
        const val offsetEntry = "ReservedOffsetEntry"
        const val reservedClient = "reservedusageclient"
    }
}
