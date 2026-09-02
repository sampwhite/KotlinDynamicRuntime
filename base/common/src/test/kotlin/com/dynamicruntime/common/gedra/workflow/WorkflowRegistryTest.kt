package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.BootCheckMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Building the workflow registries and the checks that decide what a scope sees (issue #533), over plain
 * data: configs in a collector, clients as maps, fragments as a lookup lambda. No boot, so every refusal --
 * which a working sample can never reach -- is exercised here, in both the strict and the degrading mode.
 */
class WorkflowRegistryTest : StringSpec({

    fun cxtIn(env: String): KdrCxt = KdrCxt("wf", KdrInstanceConfig("wf-$env", env, ENV.liveSource))
    val devCxt = cxtIn(ENV.local)
    val prodCxt = cxtIn(ENV.prod)

    /** Traits every scope can see: `name` and `report`, in a global bundle. */
    fun globalTraits(cxt: KdrCxt, workflows: GedraConfigBuilderBlock = {}): GedraConfig =
        gedraConfig(cxt, "wfCore", GCFG.globalNamespace) {
            trait("NameEntry", "name", setOf(GedraDataType.formDoc)) { property("name", "Name.") }
            trait("ReportEntry", "report", setOf(GedraDataType.formDoc)) { property("year", "Year.") }
            workflows(this)
        }

    fun client(cxt: KdrCxt, id: String, included: List<String>, workflows: GedraConfigBuilderBlock = {}): GedraConfig =
        gedraConfig(cxt, "${id}Client", "${id}config", id) {
            defineClient(
                ClientDef(
                    clientId = id, name = id, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.prod), includedTraits = included,
                ),
            )
            workflows(this)
        }

    fun creation(id: String, vararg traits: String, label: String = "Create"): GedraConfigBuilderBlock = {
        workflow(id, WfEntry.creation) {
            task("only", label) {
                traits.forEach { trait(it) }
                save("go", label)
            }
        }
    }

    /** Every file is a backend file holding `identify.label` and nothing else. */
    val fragments = WfFragmentLookup { _, fileId, ns, key ->
        when (fileId) {
            "wfCopy" -> WfFragmentHit(found = true, backend = true, present = ns == "identify" && key == "label")
            "served" -> WfFragmentHit(found = true, backend = false, present = true)
            else -> null
        }
    }

    fun build(cxt: KdrCxt, configs: List<GedraConfig>, mode: BootCheckMode = BootCheckMode.strict): Pair<WorkflowRegistries, List<GedraConfigIssue>> {
        val collector = GedraConfigCollector()
        configs.forEach { collector.add(cxt, it) }
        val clients = collector.configs.mapNotNull { it.client }.associate { it.clientId to it as ClientDef? }
        val issues = mutableListOf<GedraConfigIssue>()
        val regs = buildWorkflowRegistries(cxt, collector, clients, { emptySet() }, fragments, mode, issues)
        return regs to issues
    }

    "a global creation workflow is inherited by a client that supports its traits" {
        val (regs, issues) = build(devCxt, listOf(globalTraits(devCxt, creation("createForm", "name")), client(devCxt, "acme", listOf("name"))))
        issues.shouldBeEmpty()
        regs.global.creation.shouldNotBeNull().def.workflowId shouldBe "createForm"
        // Inherits everything, so no registry of its own: absent-means-global.
        regs.byClient["acme"].shouldBeNull()
        regs.forClient("acme").creation.shouldNotBeNull().ref.text shouldContain "gc.cd.global.wfCore#createForm"
    }

    "a client that does not support the global creation's trait does not inherit it" {
        val (regs, issues) = build(devCxt, listOf(globalTraits(devCxt, creation("createForm", "name")), client(devCxt, "acme", listOf("report"))))
        issues.shouldBeEmpty()
        regs.forClient("acme").creation.shouldBeNull()
        regs.forClient("acme").workflows.shouldBeEmpty()
    }

    "a client's creation workflow shadows the global one, whatever its id" {
        val configs = listOf(
            globalTraits(devCxt, creation("createForm", "name")),
            client(devCxt, "acme", listOf("name", "report"), creation("acmeCreate", "report")),
        )
        val (regs, issues) = build(devCxt, configs)
        issues.shouldBeEmpty()
        val acme = regs.forClient("acme")
        acme.creation.shouldNotBeNull().def.workflowId shouldBe "acmeCreate"
        acme.workflow("createForm").shouldBeNull()
        // Global is untouched by what a client does.
        regs.global.creation.shouldNotBeNull().def.workflowId shouldBe "createForm"
    }

    "an entry kind that is not built is refused" {
        val bad = client(devCxt, "acme", listOf("name")) {
            workflow("later", WfEntry.survey) { task("a", "A") { trait("name"); save("s", "S") } }
        }
        val e = shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), bad)) }
        e.message shouldContain "not built yet"
    }

    "a workflow collecting a trait its client does not support is refused" {
        val bad = client(devCxt, "acme", listOf("name"), creation("createForm", "report"))
        val e = shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), bad)) }
        e.message shouldContain "does not support"
    }

    "a second creation workflow in one scope is refused" {
        val bad = client(devCxt, "acme", listOf("name", "report")) {
            creation("one", "name")(this)
            creation("two", "report")(this)
        }
        val e = shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), bad)) }
        e.message shouldContain "second creation workflow"
    }

    "labels: a resolving backend pull is fine, a missing key, a served file and a two-part key are not" {
        fun labelled(label: String) = client(devCxt, "acme", listOf("name"), creation("createForm", "name", label = label))
        build(devCxt, listOf(globalTraits(devCxt), labelled("""%{@t("wfCopy.identify.label")}"""))).second.shouldBeEmpty()
        // Guarded: left to its default, as the fragment service's own check leaves it.
        build(devCxt, listOf(globalTraits(devCxt), labelled("""%{@t("wfCopy.identify.gone") ?: "Create"}"""))).second.shouldBeEmpty()
        shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), labelled("""%{@t("wfCopy.identify.gone")}"""))) }
            .message shouldContain "has no 'identify.gone'"
        shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), labelled("""%{@t("served.identify.label")}"""))) }
            .message shouldContain "served (frontend) file"
        shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), labelled("""%{@t("wfCopy.label")}"""))) }
            .message shouldContain "three-part"
        shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), labelled("""%{@t("nowhere.identify.label")}"""))) }
            .message shouldContain "no fragment file 'nowhere'"
        shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), labelled("""%{@t("wfCopy.identify.label")"""))) }
            .message shouldContain "does not parse"
    }

    "in production a bad workflow is dropped from its scope and the rest is kept" {
        val configs = listOf(
            globalTraits(prodCxt, creation("createForm", "name")),
            client(prodCxt, "acme", listOf("name"), creation("acmeCreate", "report")),
        )
        val (regs, issues) = build(prodCxt, configs, BootCheckMode.warn)
        issues.size shouldBe 1
        issues.single().message shouldContain "does not support"
        // Acme's own was dropped, and since it never shadowed anything, acme inherits the global creation.
        regs.forClient("acme").creation.shouldNotBeNull().def.workflowId shouldBe "createForm"
    }

    "with the check off, everything is taken as declared -- nothing checked, nothing dropped" {
        val configs = listOf(
            globalTraits(devCxt, creation("createForm", "name")),
            client(devCxt, "acme", listOf("name")) {
                // An unbuilt entry kind collecting an unsupported trait, and two creation workflows: three
                // refusals in strict mode, none here.
                workflow("later", WfEntry.survey) { task("a", "A") { trait("report"); save("s", "S") } }
                creation("one", "name")(this)
                creation("two", "name")(this)
            },
        )
        val (regs, issues) = build(devCxt, configs, BootCheckMode.off)
        issues.shouldBeEmpty()
        // Shadowing is semantics rather than a check, so the client's creations still replace the global one.
        regs.forClient("acme").workflows.keys shouldBe setOf("later", "one", "two")
    }

    "the same workflow id in two bundles of one scope is a collision: refused, or first kept" {
        val configs = listOf(
            globalTraits(devCxt),
            client(devCxt, "acme", listOf("name", "report"), creation("createForm", "name")),
            gedraConfig(devCxt, "acmeMore", "acmeconfig", "acme") { creation("createForm", "report")(this) },
        )
        shouldThrow<KdrException> { build(devCxt, configs) }.message shouldContain "declared a second time"
        val (regs, issues) = build(devCxt, configs, BootCheckMode.warn)
        issues.single().message shouldContain "declared a second time"
        regs.forClient("acme").creation.shouldNotBeNull().def.tasks.single().requiredTraitIds shouldBe listOf("name")
    }
})

private typealias GedraConfigBuilderBlock = com.dynamicruntime.common.gedra.GedraConfigBuilder.() -> Unit
