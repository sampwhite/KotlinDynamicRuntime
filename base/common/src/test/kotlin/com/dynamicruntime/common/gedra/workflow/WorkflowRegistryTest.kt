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

    fun normal(id: String, vararg traits: String, label: String = "Audit"): GedraConfigBuilderBlock = {
        workflow(id, WfEntry.normal) {
            task("only", label) {
                traits.forEach { trait(it) }
                save("go", label, WfSaveKind.edit)
            }
        }
    }

    fun survey(id: String, vararg traits: String, label: String = "Review"): GedraConfigBuilderBlock = {
        workflow(id, WfEntry.survey) {
            task("only", label) {
                traits.forEach { trait(it) }
                save("go", label, WfSaveKind.edit)
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
        // The cfact vocabulary eligibility tests parse against: global gets one name, a client adds its own.
        val cfactNames: (String?) -> Set<String> = { scope ->
            if (scope == null) setOf("surveyComplete") else setOf("surveyComplete", "${scope}Only")
        }
        val regs = buildWorkflowRegistries(
            cxt, collector, clients, { emptySet() }, fragments, cfactNames = cfactNames, mode = mode, issues = issues,
        )
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

    // Every entry kind is built now that `normal` has landed (issue #794), so the unbuilt-kind refusal has
    // nothing left to refuse. What replaces it is the rule that makes `normal` different from the other two.
    "normal workflows are admitted, and a scope may declare many of them" {
        val configs = listOf(
            globalTraits(devCxt),
            client(devCxt, "acme", listOf("name", "report")) {
                normal("auditReview", "name")(this)
                normal("secondReview", "report")(this)
            },
        )
        val (regs, issues) = build(devCxt, configs)
        issues.shouldBeEmpty()
        // Many per scope, unlike creation and survey, where a second declaration takes the kind over.
        regs.forClient("acme").workflows.keys shouldBe setOf("auditReview", "secondReview")
    }

    "a normal workflow's saves must be edits, since the form already exists" {
        val e = shouldThrow<KdrException> {
            client(devCxt, "acme", listOf("name")) {
                workflow("later", WfEntry.normal) { task("a", "A") { trait("name"); save("s", "S") } }
            }
        }
        e.message shouldContain "runs against an existing form"
    }

    // Eligibility tests (issue #783) parse against the declaring scope's cfacts, so a misspelled cfact refuses
    // the workflow at boot instead of being a test that never passes -- and a client's own cfact is usable in
    // its own workflow, where it would not parse globally.
    "an eligibility test must parse against the scope's cfacts, and may use the client's own" {
        fun eligible(test: String): GedraConfig = client(devCxt, "acme", listOf("name")) {
            workflow("auditReview", WfEntry.normal) {
                eligibility("check", test, "Not yet.")
                task("a", "A") { trait("name"); save("s", "S", WfSaveKind.edit) }
            }
        }
        val (regs, issues) = build(devCxt, listOf(globalTraits(devCxt), eligible("surveyComplete, acmeOnly")))
        issues.shouldBeEmpty()
        regs.forClient("acme").workflow("auditReview").shouldNotBeNull()

        val e = shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), eligible("surveyComplet"))) }
        e.message shouldContain "eligibility test 'check'"
    }

    "a singleton rule's condition must parse against the scope's cfacts" {
        fun ruled(cond: String): GedraConfig = client(devCxt, "acme", listOf("name")) {
            workflow("auditReview", WfEntry.normal) {
                singleton(WSC.needsReview, cond)
                task("a", "A") { trait("name"); save("s", "S", WfSaveKind.edit) }
            }
        }
        build(devCxt, listOf(globalTraits(devCxt), ruled("acmeOnly"))).second.shouldBeEmpty()
        val e = shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), ruled("acmeOnlee"))) }
        e.message shouldContain "'needsReview' singleton rule"
    }

    // Approval tasks (issue #787): the cfact an approval emits must be declared in the scope, and its copy rides
    // the label check -- the same two rules an eligibility test's test and explanation are held to.
    "an approval task's cfact must be declared, and its copy rides the label check" {
        fun approving(cfact: String, prompt: String = "Approve it."): GedraConfig = client(devCxt, "acme", listOf("name")) {
            workflow("auditReview", WfEntry.normal) {
                task("record", "Record") { trait("name"); save("s", "S", WfSaveKind.edit) }
                task("approve", "Approve") { approval(cfact, prompt, "Approve") }
            }
        }
        build(devCxt, listOf(globalTraits(devCxt), approving("acmeOnly"))).second.shouldBeEmpty()
        shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), approving("acmeOnlee"))) }
            .message shouldContain "emitting the cfact 'acmeOnlee'"
        shouldThrow<KdrException> {
            build(devCxt, listOf(globalTraits(devCxt), approving("acmeOnly", prompt = """%{@t("wfCopy.identify.gone")}""")))
        }.message shouldContain "approval prompt of task 'approve'"
    }

    "an eligibility explanation rides the label check" {
        val bad = client(devCxt, "acme", listOf("name")) {
            workflow("auditReview", WfEntry.normal) {
                eligibility("check", "surveyComplete", """%{@t("wfCopy.identify.gone")}""")
                task("a", "A") { trait("name"); save("s", "S", WfSaveKind.edit) }
            }
        }
        val e = shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), bad)) }
        e.message shouldContain "explanation on eligibility test 'check'"
    }

    "a survey workflow is admitted, beside the creation workflow in one scope" {
        val configs = listOf(
            globalTraits(devCxt),
            client(devCxt, "acme", listOf("name", "report")) {
                creation("createForm", "name")(this)
                survey("reviewForm", "report")(this)
            },
        )
        val (regs, issues) = build(devCxt, configs)
        issues.shouldBeEmpty()
        val acme = regs.forClient("acme")
        acme.creation.shouldNotBeNull().def.workflowId shouldBe "createForm"
        acme.survey.shouldNotBeNull().def.workflowId shouldBe "reviewForm"
    }

    "a second survey workflow in one scope is refused" {
        val bad = client(devCxt, "acme", listOf("name", "report")) {
            survey("one", "name")(this)
            survey("two", "report")(this)
        }
        val e = shouldThrow<KdrException> { build(devCxt, listOf(globalTraits(devCxt), bad)) }
        e.message shouldContain "second survey workflow"
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

    "the workflow's own label rides the same check (issue #719)" {
        fun titled(label: String?): GedraConfigBuilderBlock = {
            workflow("createForm", WfEntry.creation) {
                this.label = label
                task("only", "Create") {
                    trait("name")
                    save("go", "Create")
                }
            }
        }
        fun withTitle(label: String?) = listOf(globalTraits(devCxt), client(devCxt, "acme", listOf("name"), titled(label)))
        // Absent, or a resolving pull: fine, and the definition carries what was written.
        build(devCxt, withTitle(null)).second.shouldBeEmpty()
        build(devCxt, withTitle("""%{@t("wfCopy.identify.label")}""")).second.shouldBeEmpty()
        build(devCxt, withTitle("Plain title")).first.forClient("acme").creation.shouldNotBeNull().def.label shouldBe "Plain title"
        // A pull that cannot resolve is refused, named as the workflow's label rather than a task's.
        shouldThrow<KdrException> { build(devCxt, withTitle("""%{@t("wfCopy.identify.gone")}""")) }
            .message shouldContain "has a label that"
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
                // A normal workflow collecting an unsupported trait, and two creation workflows: refusals in
                // strict mode, none here.
                workflow("later", WfEntry.normal) { task("a", "A") { trait("report"); save("s", "S", WfSaveKind.edit) } }
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
