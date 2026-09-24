package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Source-code schema is judged by the source check mode (issue #841, part 3): a fault in a component's own types --
 * here an unregistered `g-optionsSource` and a layout naming a field its type lacks -- still refuses the boot
 * outside production, but in production the faulty keyword or layout is dropped and the node serves, as every
 * other source-config check does there. Before, these refused the boot even in production.
 */
class SourceSchemaForgivenessTest : StringSpec({

    val typeName = "${SourceFaultComponent.namespace}.Faulty"

    "outside production a component's schema fault still refuses the boot, naming the source variable" {
        val failure = shouldThrow<KdrException> {
            Startup.mkTestBootCxt("srcFaultUnit", "sourceFaultUnitTest", additionalComponents = listOf(SourceFaultComponent()))
        }
        failure.message.shouldNotBeNull() shouldContain GCFG.checkEnvVar.name
    }

    "in production the faulty keyword and layout are dropped, and the node serves" {
        val cxt: KdrCxt = Startup.mkBootCxt(
            "srcFaultProd", "sourceFaultProdTest",
            mapOf(
                ACFG.env to ENV.prod,
                // Not a test instance (so SchemaService's test-instance refusal is not what fires), yet in-memory, so
                // no real database is needed.
                ACFG.isTestInstance to false,
                ACFG.inMemoryOnly to true,
            ),
            additionalComponents = listOf(SourceFaultComponent()),
        )
        val def = SchemaService.get(cxt).storeFor(null).defs[typeName].toJsonMapOrEmpty()
        def[SCH.properties].toJsonMapOrEmpty()["pick"].toJsonMapOrEmpty().containsKey(SCH.optionsSource) shouldBe false
        def.containsKey(SCH.layout) shouldBe false

        val issues = ClientConfigIssues.get(cxt).issuesFor(GID.globalClient).filter { it.elementId == typeName }
        issues.size shouldBe 2
        issues.all { it.origin == GedraConfigOrigin.source && it.elementKind == GCEL.type } shouldBe true
    }
})

/** A component whose one global type carries two faults the boot check finds. */
class SourceFaultComponent : ComponentDefinition {
    override val providerName: String = "sourceFaultFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "sourceFault", namespace, GID.globalClient) {
            type("Faulty") {
                type = SCT.kObject
                property("pick", "From an unregistered source.") { optionsSource("noSuchOptionsSource841c") }
                layout { field("noSuchField841c", label = "Nope") }
            }
        },
    )

    companion object {
        const val namespace = "sourcefault"
    }
}
