package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignSharedEdit
import com.dynamicruntime.common.gedra.FTYPE
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GSRC
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.PFO
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.prefillFromOwner
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A creation or core details task that collects the traits of its form's type (issue #1903): what is drawn, saved, and
 * required for each type, and what asks without a form. Over the shared instance, a client per scenario.
 */
class FormTypeTraitsTest : StringSpec({
    val cxt = TestInstances.default("formTypeTraits1903")
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("formTypeTraits", client).also { it.userId = 19030L }

    /** Leads and videos, which share a trait; memos, which collect nothing; and notes, whose type lists what another task does. */
    val siteTypes: GedraConfigBuilder.() -> Unit = {
        formType("lead", "Lead", mayLeave = true) { trait("ask"); trait("contact") }
        formType("video", "Video", mayLeave = true) { trait("contact", required = false); trait("clip") }
        formType("memo", "Memo", mayLeave = true)
        formType("note", "Note", mayLeave = true) { trait("remarks") }
    }

    /** A core details workflow: a task collecting the form's type's traits, and one that lists a trait of its own. */
    val siteDetails: WfDefBuilder.() -> Unit = {
        task("basics", "Basics") {
            trait(GT.formType, required = false)
            formTypeTraits = true
            save("save", "Save", WfSaveKind.edit)
        }
        task("more", "More") {
            trait("remarks", required = false)
            save("saveMore", "Save", WfSaveKind.edit)
        }
    }

    /** The config of a site whose creation and core details workflows collect a form's type's traits. */
    fun siteConfig(
        client: String,
        types: GedraConfigBuilder.() -> Unit = siteTypes,
        details: WfDefBuilder.() -> Unit = siteDetails,
        more: GedraConfigBuilder.() -> Unit = {},
    ): GedraConfig =
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name),
                ),
            )
            for (id in listOf("ask", "contact", "clip", "remarks")) {
                trait("${id.replaceFirstChar { it.uppercase() }}Entry", id, setOf(GedraDataType.formDoc), "The $id.") {
                    property("text", "Its text.")
                }
            }
            workflow("make", WfEntry.creation) {
                task("start", "Start") {
                    trait(GT.formType, required = false)
                    formTypeTraits = true
                    save("create", "Create", WfSaveKind.create)
                }
            }
            workflow("details", WfEntry.coreDetails) { details() }
            types()
            more()
        }

    fun write(config: GedraConfig, client: String, trial: Boolean = false) {
        GedraConfigService.get(cxt).writeConfig(asClient(client), config, trial = trial)
        GedraConfigReload.reloadClient(cxt, client)
    }

    class Site(val client: String, val user: TestUser, val admin: TestUser) {
        val view = clientPath(GEP.workflowView, client)
        val save = clientPath(GEP.workflowSave, client)
    }

    fun createSite(client: String, more: GedraConfigBuilder.() -> Unit = {}): Site {
        write(siteConfig(client, more = more), client)
        return Site(
            client, TestUser.create(cxt, "user@$client.test", userClient = client),
            TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client),
        )
    }

    fun typed(type: String) = mapOf(GE.traitId to GT.formType, GE.data to mapOf(GT.formTypeField to type))
    fun entry(traitId: String, text: String = "x") = mapOf(GE.traitId to traitId, GE.data to mapOf("text" to text))

    /** A form made around the workflows, holding [entries]. */
    fun TestUser.make(vararg entries: Map<String, Any?>): String =
        postItem(GEP.formDocCreate, mapOf(GDF.entries to entries.toList()))[GDF.gedraId].toOptStr()!!

    fun saveOf(workflowId: String, taskId: String, saveId: String, gedraId: String?, entries: List<Map<String, Any?>>) = buildMap {
        put(GDF.workflowId, workflowId)
        put(GDF.taskId, taskId)
        put(GDF.saveId, saveId)
        gedraId?.let { put(GDF.gedraId, it) }
        put(GDF.entries, entries)
    }

    fun createOf(vararg entries: Map<String, Any?>) = saveOf("make", "start", "create", null, entries.toList())
    fun basicsOf(gedraId: String, vararg entries: Map<String, Any?>) = saveOf("details", "basics", "save", gedraId, entries.toList())

    fun setType(gedraId: String, type: String) = mapOf(
        GPF.targets to mapOf(
            GedraDataType.formDoc.name to listOf(
                mapOf(
                    GDF.gedraId to gedraId,
                    GPF.edits to listOf(
                        mapOf(
                            GED.action to GedraEditAction.addOrReplace.name, GE.traitId to GT.formType,
                            GE.data to mapOf(GT.formTypeField to type),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** The tasks of [this] view, each as its traits with whether each is required. */
    fun Map<String, Any?>.drawn(): List<List<Pair<Any?, Any?>>> = this[WFD.tasks].toJsonListOfMaps()
        .map { t -> t[WFD.traits].toJsonListOfMaps().map { it[WFD.traitId] to it[WFD.required] } }

    fun Map<String, Any?>.task(id: String): Map<String, Any?> = this[WFD.tasks].toJsonListOfMaps().single { it[WFD.id] == id }

    fun Site.viewOf(gedraId: String, by: TestUser = user, vararg more: Pair<String, Any?>): Map<String, Any?> =
        by.getData(view, mapOf<String, Any?>(GDF.gedraId to gedraId) + more)

    /** The stored core details state of form [gedraId], as it stands. */
    fun TestUser.completion(gedraId: String): Map<String, Any?> = getData(GEP.formDocStates, mapOf(GDF.gedraId to gedraId))[GDF.states]
        .toJsonListOfMaps().single { it[GE.traitId] == CDF.coreDetailsCompletion }[GE.data].toJsonMapOrEmpty()

    /** The refusal of an edit of [gedraId]'s basics holding [entry]. */
    fun Site.refusedBasics(gedraId: String, entry: Map<String, Any?>): String =
        user.expectError(EXC.badInput, save, basicsOf(gedraId, entry)).toString()

    val formType = GT.formType to false
    val remarks = listOf("remarks" to false)

    "a form's page draws its type's traits, and its data for them" {
        val site = createSite("types1903a")
        val lead = site.user.make(typed("lead"), entry("ask", "A demo?"), entry("clip", "Left over."))
        val view = site.viewOf(lead)
        view.drawn() shouldContainExactly listOf(listOf(formType, "ask" to true, "contact" to true), remarks)
        // What the type does not bring is not drawn, though the form holds it.
        view.task("basics")[WVF.entries].toJsonListOfMaps().map { it[GE.traitId] } shouldContainExactly listOf(GT.formType, "ask")
        fun hasClipType(view: Map<String, Any?>) = view[SCH.dDefs].toJsonMapOrEmpty().keys.any { it.endsWith(".ClipData") }
        hasClipType(view) shouldBe false
        // Two types share a trait, required by one of them.
        val video = site.user.make(typed("video"))
        site.viewOf(video).drawn() shouldContainExactly listOf(listOf(formType, "contact" to false, "clip" to true), remarks)
        hasClipType(site.viewOf(video)) shouldBe true
        // A type with no traits, no type, and one whose trait another task lists add nothing.
        val plain = listOf(listOf(formType), remarks)
        site.viewOf(site.user.make(typed("memo"))).drawn() shouldContainExactly plain
        site.viewOf(site.user.make(entry("ask"))).drawn() shouldContainExactly plain
        site.viewOf(site.user.make(typed("note"))).drawn() shouldContainExactly plain
        // A new form has no type yet.
        site.user.getData(site.view).drawn() shouldContainExactly listOf(listOf(formType))
        // Nor does a form of a type no longer defined bring anything.
        write(siteConfig(site.client, types = { formType("memo", "Memo", mayLeave = true) }), site.client)
        site.viewOf(lead).drawn() shouldContainExactly plain
    }

    "a create is held to the traits of the type it names" {
        val site = createSite("types1903b")
        fun unmet(vararg entries: Map<String, Any?>): List<String> {
            val result = site.user.postData(site.save, createOf(*entries))
            if (result[WSF.saved] == true) return emptyList()
            return result[WSF.unmetTraits].toJsonListOfStrings().also { it.isNotEmpty() shouldBe true }
        }
        unmet(typed("lead")) shouldContainExactly listOf("ask", "contact")
        unmet(typed("lead"), entry("contact")) shouldContainExactly listOf("ask")
        unmet(typed("lead"), entry("contact"), entry("ask")) shouldContainExactly emptyList()
        unmet(typed("video"), entry("clip")) shouldContainExactly emptyList()
        unmet(typed("memo")) shouldContainExactly emptyList()
        unmet() shouldContainExactly emptyList()
        // Another type's trait is not this one's to collect, and no type collects none.
        site.user.expectError(EXC.badInput, site.save, createOf(typed("lead"), entry("clip"))).toString() shouldContain
            "does not collect the trait 'clip' for a form of this type"
        site.user.expectError(EXC.badInput, site.save, createOf(entry("ask"))).toString() shouldContain "does not collect the trait 'ask'"
    }

    "an edit goes by the form's type, or by the type it sets" {
        val site = createSite("types1903c")
        val form = site.user.make(typed("lead"))
        site.user.postData(site.save, basicsOf(form, entry("ask")))[WSF.saved] shouldBe true
        site.refusedBasics(form, entry("clip")) shouldContain "does not collect the trait 'clip'"
        // The type and what it asks for, in one save; then the type is the form's.
        site.user.postData(site.save, basicsOf(form, typed("video"), entry("clip")))[WSF.saved] shouldBe true
        site.user.postData(site.save, basicsOf(form, entry("clip", "Again.")))[WSF.saved] shouldBe true
        site.refusedBasics(form, entry("ask")) shouldContain "does not collect the trait 'ask'"
        // What the type it was left holds.
        val held = site.user.getItem(GEP.formDoc, mapOf(GDF.gedraId to form))[GDF.entries].toJsonListOfMaps()
        held.map { it[GE.traitId] }.toSet() shouldBe setOf(GT.formType, "ask", "clip")
        // A trait another task lists is that task's, whatever the type lists.
        val note = site.user.make(typed("note"))
        site.refusedBasics(note, entry("remarks")) shouldContain "does not collect the trait 'remarks'"
        site.user.postData(site.save, saveOf("details", "more", "saveMore", note, listOf(entry("remarks"))))[WSF.saved] shouldBe true
    }

    "what a form lacks follows a change of its type, and a change of what its type lists" {
        val site = createSite("types1903d")
        val form = site.user.make(typed("memo"))
        site.user.completion(form)[CDF.complete] shouldBe true
        site.user.postItems(GEP.patch, setType(form, "lead"))
        site.user.completion(form).let {
            it[CDF.complete] shouldBe false
            it[CDF.missingTraits] shouldBe listOf("ask", "contact")
        }
        site.viewOf(form).let { view ->
            view.task("basics")[WVF.status].toJsonMapOrEmpty()[CDF.missingTraits] shouldBe listOf("ask", "contact")
            view[WVF.focusTask] shouldBe "basics"
        }
        site.user.postItems(GEP.patch, setType(form, "video"))
        site.user.completion(form)[CDF.missingTraits] shouldBe listOf("clip")
        // The type now lists another: what is stored is as it was until the form's state is next worked out.
        write(siteConfig(site.client, types = { formType("video", "Video", mayLeave = true) { trait("ask") } }), site.client)
        site.viewOf(form).task("basics")[WVF.status].toJsonMapOrEmpty()[CDF.missingTraits] shouldBe listOf("ask")
        site.user.completion(form)[CDF.missingTraits] shouldBe listOf("clip")
        site.user.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to form))
        site.user.completion(form)[CDF.missingTraits] shouldBe listOf("ask")
    }

    "what asks without a form answers over every type" {
        val site = createSite("types1903e") {
            workflow("review", WfEntry.normal) { task("look", "Look") { trait("ask"); save("s", "Save", WfSaveKind.edit) } }
        }
        fun dataType(trait: String) = "${clientNamespace(site.client)}.${trait.replaceFirstChar { it.uppercase() }}Data"
        // Where a type is used: in each workflow that draws it for some form type.
        fun usedBy(trait: String) = DesignSharedEdit.whereUsed(asClient(site.client), setOf(dataType(trait))).map { it[DSV.workflowId] }
        usedBy("clip") shouldContainExactly listOf("make", "details")
        usedBy("ask") shouldContainExactly listOf("make", "details", "review")

        // A workflow's own layout of a trait only a form type brings, and a prefill of it.
        fun withClip(collects: Boolean, layout: Boolean, prefill: Boolean) = siteConfig(site.client, details = {
            val clipText = mapOf(SL.field to "text", SL.label to "Clip text")
            if (layout) alterType(dataType("clip"), mapOf(SCH.layout to mapOf(SL.schemaFields to listOf(clipText))))
            task("basics", "Basics") {
                trait(GT.formType, required = false)
                formTypeTraits = collects
                if (prefill) {
                    function(prefillFromOwner { userAttribute = PFO.publicName; targetTrait = "clip"; targetValuePath = "text" })
                }
                save("save", "Save", WfSaveKind.edit)
            }
        })
        fun refusal(config: GedraConfig) = shouldThrow<KdrException> { write(config, site.client, trial = true) }.message.orEmpty()
        refusal(withClip(collects = false, layout = true, prefill = false)) shouldContain "which its pages do not show"
        refusal(withClip(collects = false, layout = false, prefill = true)) shouldContain "the task does not collect"
        write(withClip(collects = true, layout = true, prefill = true), site.client, trial = true)
        fun prefilled(type: String) = site.viewOf(site.user.make(typed(type))).task("basics")[WVF.entries].toJsonListOfMaps()
            .filter { it[GE.source] == GSRC.prefill }.map { it[GE.traitId] }
        prefilled("video") shouldContainExactly listOf("clip")
        // Not for a form whose type does not bring the trait.
        prefilled("lead") shouldContainExactly emptyList()
    }

    "a type naming a trait the client lacks is refused as a write, so no page draws it" {
        val site = createSite("types1903f")
        val stale = siteConfig(site.client, types = { formType("lead", "Lead") { trait("ask"); trait("nosuch") } })
        shouldThrow<KdrException> { write(stale, site.client, trial = true) }.message.orEmpty() shouldContain
            "which the client's forms cannot hold"
        site.viewOf(site.user.make(typed("lead"))).drawn().first() shouldContainExactly listOf(formType, "ask" to true, "contact" to true)
    }

    "a normal workflow lists its traits" {
        shouldThrow<KdrException> {
            siteConfig("types1903g") {
                workflow("review", WfEntry.normal) { task("look", "Look") { formTypeTraits = true; save("s", "Save", WfSaveKind.edit) } }
            }
        }.message.orEmpty() shouldContain "A normal workflow lists its traits"
    }

    "Design View draws the form type asked for, and an edit made there is the workflow's" {
        val site = createSite("types1903h")
        GedraConfigService.get(cxt).publish(asClient(site.client), listOf(GedraId.of(GedraConfigType.configDoc, site.client, "main")))
        GedraConfigReload.reloadClient(cxt, site.client)
        val design = EP.view to DSV.design
        val form = site.user.make(typed("lead"))
        fun block(view: Map<String, Any?>) = view[DSV.designBlock].toJsonMapOrEmpty()
        val own = site.viewOf(form, site.admin, design)
        own.drawn().first() shouldContainExactly listOf(formType, "ask" to true, "contact" to true)
        block(own)[DSV.formTypes].toJsonListOfMaps().map { it[FTYPE.formTypeId] } shouldContainExactly
            listOf("lead", "video", "memo", "note")
        block(own)[DSV.ownFormType] shouldBe "lead"
        block(own)[DSV.drawnFormType] shouldBe null
        val asVideo = site.viewOf(form, site.admin, design, DSV.drawnFormType to "video")
        asVideo.drawn().first() shouldContainExactly listOf(formType, "contact" to false, "clip" to true)
        block(asVideo)[DSV.drawnFormType] shouldBe "video"
        site.viewOf(form, site.admin, design, DSV.drawnFormType to DSV.noFormType).drawn().first() shouldContainExactly listOf(formType)
        // The form's own, asked for by name, is the form's own.
        block(site.viewOf(form, site.admin, design, DSV.drawnFormType to "lead"))[DSV.drawnFormType] shouldBe null
        // A new form, drawn for a type.
        site.admin.getData(site.view, mapOf(design, DSV.drawnFormType to "video")).drawn().first() shouldContainExactly
            listOf(formType, "contact" to false, "clip" to true)
        site.admin.expectError(EXC.badInput, site.view, args = mapOf(design, DSV.drawnFormType to "nosuch"))
            .toString() shouldContain "has no form type 'nosuch'"
        // Nobody else is answered so, and it stores nothing.
        site.viewOf(form, site.user, design, DSV.drawnFormType to "video").drawn().first() shouldContainExactly
            listOf(formType, "ask" to true, "contact" to true)
        site.viewOf(form, site.admin, DSV.drawnFormType to "video").drawn().first() shouldContainExactly
            listOf(formType, "ask" to true, "contact" to true)
        // An edit made while drawing a type is stamped, and kept, as the workflow as written.
        val clipType = block(asVideo)[DSV.types].toJsonMapOrEmpty().keys.single { it.endsWith(".ClipData") }
        site.admin.postData(
            DSV.layoutEntryEdit,
            mapOf(
                DSV.workflowId to "details", DSV.typeName to clipType, DSV.field to "text", DSV.entry to mapOf(SL.label to "Clip text"),
                DSV.basedOn to block(asVideo)[DSV.basedOn],
            ),
        )
        val stored = WorkflowService.get(cxt).forClient(site.client).workflow("details").shouldNotBeNull().def
        stored.typeAlterations.keys shouldContainExactly setOf(clipType)
        stored.task("basics").shouldNotBeNull().traits.map { it.traitId } shouldContainExactly listOf(GT.formType)
        site.viewOf(form, site.admin, design, DSV.drawnFormType to "video")[WVF.fieldLayouts].toString() shouldContain "Clip text"
    }

    "the workflow editor is told the form types, sets the key, and leaves it as it is when not said" {
        val client = "types1903i"
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name),
                ),
            )
            trait("AskEntry", "ask", setOf(GedraDataType.formDoc), "What they ask.") { property("text", "The ask.") }
            formType("lead", "Lead") { trait("ask"); trait(GT.name, required = false) }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigService.get(cxt).publish(asClient(client), listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
        val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
        admin.getItem(UADEP.clientWorkflows)[CWF.formTypes] shouldBe listOf(
            mapOf(
                FTYPE.formTypeId to "lead", FTYPE.label to "Lead",
                FTYPE.traits to listOf(
                    mapOf(WFD.traitId to "ask", WFD.required to true), mapOf(WFD.traitId to GT.name, WFD.required to false),
                ),
            ),
        )
        fun save(
            traits: List<WfTraitRef>, formTypeTraits: Boolean?, label: String, id: String? = "formDetails",
            template: String = WFT.coreDetails,
        ) =
            mapOf(
                CWF.slots to WfTemplateSlots(
                    id, template, label,
                    listOf(WfTaskSlot(if (template == WFT.collect) "collect" else "details", "Step", traits, formTypeTraits)),
                ).toInfo(),
            )
        fun task() = WorkflowService.get(cxt).forClient(client).workflow("formDetails").shouldNotBeNull().def.tasks.single()
        val formTypeOnly = listOf(WfTraitRef(GT.formType))
        admin.postData(UADEP.clientWorkflowSave, save(formTypeOnly, true, "Form details", id = null))
        task().formTypeTraits shouldBe true
        // A caller who does not say keeps it, whose task may then list no traits of its own.
        admin.postData(UADEP.clientWorkflowSave, save(formTypeOnly, null, "Form details again"))
        task().formTypeTraits shouldBe true
        admin.postData(UADEP.clientWorkflowSave, save(emptyList(), null, "Form details again"))
        task().let { it.formTypeTraits to it.traits.size } shouldBe (true to 0)
        admin.expectError(EXC.badInput, UADEP.clientWorkflowSave, save(emptyList(), false, "Form details")).toString() shouldContain
            "collects at least one trait"
        admin.postData(UADEP.clientWorkflowSave, save(formTypeOnly, false, "Form details"))
        task().formTypeTraits shouldBe false
        admin.expectError(EXC.badInput, UADEP.clientWorkflowSave, save(formTypeOnly, true, "Collect", id = null, template = WFT.collect))
            .toString() shouldContain "of a normal workflow lists its traits"
    }
})
