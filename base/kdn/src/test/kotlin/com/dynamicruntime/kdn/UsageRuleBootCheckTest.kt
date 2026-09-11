package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
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
        // A usage on a trait named like a reserved query field mints a parameter that would overwrite it; the
        // check refuses rather than let the trait become silently unsearchable at merge time.
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
})

/** Contributes a global config with two usage rules for one trait -- the mistake #681's check catches. */
class DuplicateUsageComponent : ComponentDefinition {
    override val providerName: String = "duplicateUsageFixture"

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
        const val dupeTrait = "dupeYearly"
        const val dupeEntry = "DupeYearlyEntry"
    }
}

/** Contributes a global config whose usage mints a search parameter colliding with a reserved field (#538). */
class ReservedFieldUsageComponent : ComponentDefinition {
    override val providerName: String = "reservedFieldUsageFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "reservedUsageConfig", namespace, GID.globalClient) {
            trait(reservedEntry, reservedTrait, setOf(GedraDataType.formDoc), "A trait named like a reserved field.") {
                property("value", "A value.")
            }
            // A string usage on a trait id that is a reserved listing field name mints an exact parameter of the
            // same name (`offset`), which would overwrite the listing's paging field -- the collision #538 refuses.
            traitUsage(reservedTrait, "Offset", $$"${value}", UsageKind.string)
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_RESERVED_USAGE_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the reserved-field-usage fixture component regardless of environment.",
        )
        const val namespace = "reservedusagefixture"
        // The offset paging field's name -- a usage on a trait so named collides with it. Referencing the
        // constant keeps the collision a compile-time fact: rename EP.offset and this fixture stops compiling
        // rather than silently declaring a trait that collides with nothing.
        const val reservedTrait = EP.offset
        const val reservedEntry = "ReservedUsageEntry"
    }
}
