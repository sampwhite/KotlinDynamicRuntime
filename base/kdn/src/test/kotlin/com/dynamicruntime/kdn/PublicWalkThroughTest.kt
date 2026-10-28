package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.JOIN
import com.dynamicruntime.common.gedra.TraitChoiceDesign
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.confdemo.ConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * The walk-through a reference deployment links from a signed-in visitor's home page (issue #1429), followed as its
 * reader would, so the page cannot fall behind the product without a failing build.
 *
 * The page's tables are what the reader enters, each marked on the line before it with the form it is for
 * (`<!-- kdr-walk: workflow=review -->`) or `<!-- kdr-walk: name -->` for the name on the profile. This reads each
 * table, finds each **label** among the fields of the traits that workflow's form shows -- a field's `title`, or its
 * key with a capital where it has none, as the form labels it -- and turns each **value** into what the form would send:
 * a choice by its label, yes or no, a number, or text. Then it walks the page's steps through the endpoints the pages
 * use: join as a speaker, create the talk, answer the survey, write the abstract, join the committee, name that user,
 * and review and accept the talk. A field or a choice the demo renames, a step the demo no longer allows, and a role the
 * page names that the demo does not offer each fail here.
 *
 * Part 2, the reader's own client, is followed the same way, but its forms are read off the workflow view -- each
 * field by the label the form shows and with the choices it offers -- since the reader builds them: create the client,
 * change two keys of its copy found by the words they say now, design a trait and a workflow collecting it, try them
 * in the sandbox, change the form in Design View, publish, invite somebody and label them, restrict the workflow to the
 * label, have the invited person suggest a book on the changed form, open the workflow to everyone, and run the report
 * a new client starts with.
 *
 * Part 3, for developers, has no form for the reader to fill in; its AI writes one. So the test makes the key in the
 * sandbox as the page says, finds the endpoint the page names in the catalog the key is shown, reads the sandbox's forms
 * with it, and plays the AI with the bundle one following the page's prompt might write: written with the key as the
 * parent's draft the sandbox runs, tried on a form that stays the sandbox's, published, and seen by the client's people.
 * The key is revoked and refused. Every `/kda/...` path part 3 names is one it calls.
 *
 * The page's own buttons and headings are not checked here; following the page in a browser is part of changing it.
 */
class PublicWalkThroughTest : StringSpec({
    val cxt = TestInstances.reference("publicWalkThrough1429")
    val page = File(File("../..").canonicalFile, WalkThrough.sourcePath).readText()
    val tables = walkTables(page)
    // What the reader's `*your first name*` becomes here: unusual enough to find this test's talk on a shared demo.
    val firstName = "Walker1429"

    /** The traits each form shows, by the workflow it belongs to -- where its table's labels are looked up. */
    val traitsByForm = mapOf(
        ConfDemo.createTalkWorkflow to listOf(GT.name),
        ConfDemo.talkSurveyWorkflow to listOf(ConfDemo.session, ConfDemo.questionnaire),
        ConfDemo.abstractWorkflow to listOf(ConfDemo.abstract, ConfDemo.questionnaire),
        ConfDemo.reviewWorkflow to listOf(ConfDemo.review),
    )
    // Each trait's data schema: the demo's own traits as its stored `base` bundle defines them, and the global ones --
    // the core `name` trait -- as their `$ref` names a type the components declared.
    val collector = SchemaCollector.get(cxt).shouldNotBeNull()
    val globalSchemas = collector.gedraConfigs.traitsFor(GID.globalClient).associate { trait ->
        val ref = trait.dataSchema[SCH.dRef].toOptStr()?.removePrefix(defsRefPrefix)
        trait.traitId to (ref?.let { collector.defs[it].toJsonMapOrEmpty() } ?: trait.dataSchema)
    }
    val bound = cxt.mkSubContext("publicWalkThrough", ConfDemo.client)
    val base = GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, ConfDemo.client, ConfDemo.baseConfig))
        .shouldNotBeNull().slotsForEmission(cxt.instanceConfig.isTestInstance).toJsonMapOrEmpty()
    val traitSchemas = globalSchemas + base[CCT.traitDef].toJsonListOfMaps()
        .associate { it[CCT.traitId].toOptStr()!! to it[CCT.dataSchema].toJsonMapOrEmpty() }

    /** One table's rows as the entries a save sends: per trait, each field the reader fills in, with its value. */
    fun entriesOf(workflow: String): List<Map<String, Any?>> {
        val rows = tables.getValue(walkWorkflowKey(workflow))
        val byTrait = linkedMapOf<String, MutableMap<String, Any?>>()
        for ((label, shown) in rows) {
            val (traitId, field, schema) = fieldFor(label, traitsByForm.getValue(workflow), traitSchemas)
            byTrait.getOrPut(traitId) { linkedMapOf() }[field] = valueFor(schema, shown.replace(placeholder, firstName))
        }
        return byTrait.map { (traitId, data) -> mapOf(GE.traitId to traitId, GE.data to data) }
    }
    /**
     * Saves the form of [workflow] as the page has it filled in, on the form [gid] -- or, with none, creates the form,
     * and answers its id.
     */
    fun save(by: TestUser, workflow: String, task: String, saveId: String, gid: String?): String {
        val saved = by.postData(
            GEP.workflowSave,
            buildMap {
                put(WFD.workflowId, workflow)
                put(GDF.taskId, task)
                put(GDF.saveId, saveId)
                if (gid != null) put(GDF.gedraId, gid)
                put(GDF.entries, entriesOf(workflow))
            },
        )
        return gid ?: saved[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()
    }
    fun join(by: TestUser, role: String): Long =
        by.postData(JOIN.joinPath, mapOf(JOIN.client to ConfDemo.client, JOIN.role to role, JOIN.acknowledged to true))[JOIN.userId]
            .toOptLong().shouldNotBeNull()
    fun reviewTask(by: TestUser, gid: String, task: String): Map<String, Any?> =
        by.getData(GEP.workflowView, mapOf(WFD.workflowId to ConfDemo.reviewWorkflow, GDF.gedraId to gid))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == task }

    "the page has a table for every form it has the reader fill in, and for each step of the reader's own client" {
        tables.keys shouldBe traitsByForm.keys.map { walkWorkflowKey(it) }.toSet() + walkNameKey + ownClientKeys
    }

    "every role the page has the reader join is one the conference demo offers, by its label" {
        val labels = ClientService.get(cxt).present(ConfDemo.client).shouldNotBeNull().joinRoles.map { it.label }
        val named = joinAsRegex.findAll(page).map { it.groupValues[1] }.toList()
        named shouldContainAll listOf("Speaker", "Committee member")
        named.filterNot { it in labels }.shouldBeEmpty()
    }

    "the walk-through, followed through the endpoints its pages use, ends with the talk accepted" {
        val email = "walker@walk1429.test"
        val visitor = TestUser.create(cxt, email)
        visitor.selfClient() shouldBe CL.public
        val publicUser = visitor.getData(AEP.selfInfo)[UPF.userId].toOptLong().shouldNotBeNull()

        // As a speaker: the talk, its survey, and its abstract.
        val speaker = join(visitor, ConfDemo.speakerRole)
        val gid = save(visitor, ConfDemo.createTalkWorkflow, ConfDemo.nameTalkTask, ConfDemo.createSave, null)
        save(visitor, ConfDemo.talkSurveyWorkflow, ConfDemo.describeTask, ConfDemo.saveAbout, gid)
        visitor.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to ConfDemo.abstractWorkflow))
        save(visitor, ConfDemo.abstractWorkflow, ConfDemo.writeTask, ConfDemo.saveAbstract, gid)

        // Back to the public user, and in again as the committee, with a name of its own.
        visitor.postData(AEP.switchUser, mapOf(AFLD.userId to publicUser))
        val committee = join(visitor, ConfDemo.committeeRole)
        // An editor (issue #1455), and no administrator.
        visitor.getData(AEP.selfInfo)[UPF.roles].toJsonListOfStrings() shouldBe listOf(ROLE.user, ROLE.editor)
        val name = tables.getValue(walkNameKey).single()[1].replace(placeholder, firstName)
        visitor.postData(AEP.profileSetName, mapOf(AFLD.name to name))[UPF.name] shouldBe name

        // The committee member finds the talk by its name among everyone's, and reviews and accepts it.
        val title = entriesOf(ConfDemo.createTalkWorkflow).single()[GE.data].toJsonMapOrEmpty()[GT.nameField]
        val found = visitor.getItems(GEP.formDocs, mapOf(EP.limit to 500))
            .single { row -> row[GDF.displayValues].toJsonListOfMaps().any { it[UF.value] == title } }
        found[GDF.gedraId] shouldBe gid
        visitor.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to ConfDemo.reviewWorkflow))
        save(visitor, ConfDemo.reviewWorkflow, ConfDemo.recordReviewTask, ConfDemo.saveReview, gid)
        visitor.postData(
            GEP.workflowApprove,
            mapOf(GDF.gedraId to gid, GDF.workflowId to ConfDemo.reviewWorkflow, GDF.taskId to ConfDemo.acceptTask),
        )

        // As the speaker again: the talk is accepted, and its name stays the speaker's own.
        visitor.postData(AEP.switchUser, mapOf(AFLD.userId to speaker))
        val accepted = reviewTask(visitor, gid, ConfDemo.acceptTask)[WVF.approval].toJsonMapOrEmpty()
        accepted[WVF.approved] shouldBe true
        visitor.getData(AEP.selfInfo)[UPF.name] shouldBe null
        committee shouldNotBe speaker
    }

    // Part 2 (issue #1429, phase 2): a client of the reader's own, built in its sandbox and published, with a workflow
    // a labeled person tries before everyone sees it, and the report a new client starts with.

    /** The one value [key]'s table gives for [label]. */
    fun cell(key: String, label: String): String = tables.getValue(key).single { it[0] == label }[1]

    /** What the form of [workflow] shows, on the form [gid] if any: each field by its label, as the page's tables name it. */
    fun shownFields(by: TestUser, workflow: String, gid: String?): Map<String, ShownField> {
        val view = by.getData(GEP.workflowView, buildMap { put(WFD.workflowId, workflow); gid?.let { put(GDF.gedraId, it) } })
        val defs = view[SCH.dDefs].toJsonMapOrEmpty()
        val layouts = view[WVF.fieldLayouts].toJsonMapOrEmpty()
        val out = linkedMapOf<String, ShownField>()
        for (trait in view[WFD.tasks].toJsonListOfMaps().flatMap { it[WFD.traits].toJsonListOfMaps() }) {
            val type = trait[WVF.schemaRef].toOptStr().shouldNotBeNull().removePrefix(defsRefPrefix)
            val entries = layouts[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps().associateBy { it[SL.field].toOptStr() }
            for ((key, raw) in defs[type].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()) {
                val schema = raw.toJsonMapOrEmpty()
                val entry = entries[key].orEmpty()
                val label = entry[SL.label].toOptStr() ?: schema[SCH.title].toOptStr() ?: key.replaceFirstChar { it.uppercase() }
                // The choices this form offers: its own list where it narrows the trait's, else the trait's.
                val choices = (entry[SL.choices] ?: schema[SCH.options]).toJsonListOfMaps()
                    .associate { it[SL.label].toOptStr()!! to it[SL.value].toOptStr()!! }
                out[label] = ShownField(trait[WFD.traitId].toOptStr()!!, key, choices)
            }
        }
        return out
    }

    /** [key]'s table as the entries a save of [workflow]'s form sends, its labels and choices read off the form itself. */
    fun shownEntries(by: TestUser, key: String, workflow: String, gid: String?): List<Map<String, Any?>> {
        val fields = shownFields(by, workflow, gid)
        val byTrait = linkedMapOf<String, MutableMap<String, Any?>>()
        for ((label, shown) in tables.getValue(key)) {
            val field = fields[label]
                ?: error("The walk-through's '$label' is not a field of $workflow's form, whose fields are ${fields.keys}.")
            val value = if (field.choices.isEmpty()) shown else field.choices[shown]
                ?: error("'$shown' is not one of the choices $workflow's form offers for '$label': ${field.choices.keys}.")
            byTrait.getOrPut(field.traitId) { linkedMapOf() }[field.key] = value
        }
        return byTrait.map { (traitId, data) -> mapOf(GE.traitId to traitId, GE.data to data) }
    }

    /** Creates a form through the client's creation workflow, named as [key]'s table says, and answers its id. */
    fun createForm(by: TestUser, key: String): String = by.postData(
        GEP.workflowSave,
        mapOf(
            WFD.workflowId to starterCreateWorkflow, GDF.taskId to starterCreateTask, GDF.saveId to starterCreateSave,
            GDF.entries to shownEntries(by, key, starterCreateWorkflow, null),
        ),
    )[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()

    /** Engages [workflow] on the form [gid], and saves its form as [key]'s table fills it in. */
    fun suggest(by: TestUser, key: String, workflow: String, gid: String) {
        by.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflow))
        by.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to workflow, GDF.taskId to collectTask, GDF.saveId to collectSave, GDF.gedraId to gid,
                GDF.entries to shownEntries(by, key, workflow, gid),
            ),
        )
    }

    "part 2, followed through the endpoints its pages use, ends with the pilot's suggestion in the client's report" {
        val founder = TestUser.create(cxt, "founder@walk1429.test")
        val client = founder.postData(AEP.createOwnClient, mapOf(CLD.name to cell("client", "Name")))[CLD.client].toOptStr()
            .shouldNotBeNull()
        founder.selfClient() shouldBe client
        val administrator = founder.getData(AEP.selfInfo)[UPF.userId].toOptLong().shouldNotBeNull()
        founder.postData(AEP.openSandbox, emptyMap())
        founder.selfClient() shouldBe sandboxOf(client)

        // Its words: each key found as Find finds it, by the words it says now.
        val keys = founder.getItems(CPY.keysPath)
        for ((now, key, words) in tables.getValue("copy")) {
            val row = keys.singleOrNull { "${it[COV.namespaceField]}.${it[COV.key]}" == key && it[COV.value] == now }
                ?: error("No copy key '$key' says '$now'.")
            val edit = mapOf(
                COV.fileId to row[COV.fileId], COV.namespaceField to row[COV.namespaceField], COV.key to row[COV.key], COV.value to words,
            )
            founder.postData(CPY.setPath, edit)[CPY.mode] shouldBe EDM.draft
        }

        // A trait of its own, and a workflow collecting it, which the editor offers by the trait's id.
        val fields = tables.getValue("fields").map { (label, kind, choices) ->
            val choiceList = choices.split(", ").filter { it.isNotEmpty() }.map { TraitChoiceDesign(null, it) }
            TraitFieldDesign(null, label, traitKinds[kind] ?: error("'$kind' is not a kind the page knows."), choices = choiceList)
        }
        val design = TraitDesign(null, cell("trait", "Name"), cell("trait", "Description"), fields)
        val made = founder.postData(UADEP.clientTraitDesign, mapOf(CIT.design to design.toInfo()))
        made[CPY.mode] shouldBe EDM.draft
        val traitId = made[CIT.design].toJsonMapOrEmpty()[CIT.traitId].toOptStr().shouldNotBeNull()
        cell("newWorkflow", "Collects") shouldBe traitId
        val slots = WfTemplateSlots(
            null, WFT.collect, cell("newWorkflow", "Name"),
            listOf(WfTaskSlot(collectTask, cell("newWorkflow", "Step"), listOf(WfTraitRef(traitId)))),
        )
        val workflowConfig = founder.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))[CWF.configName].toOptStr()
            .shouldNotBeNull()
        val workflow = workflowConfig.removePrefix(CWF.configPrefix)

        // Tried in the sandbox, and changed for this workflow in Design View.
        val tried = createForm(founder, "sandboxForm")
        suggest(founder, "sandboxSuggestion", workflow, tried)
        val shown = shownFields(founder, workflow, tried)
        for ((label, change) in tables.getValue("design")) {
            val field = shown[label].shouldNotBeNull()
            val view = founder.getData(GEP.workflowView, mapOf(WFD.workflowId to workflow, GDF.gedraId to tried, EP.view to DSV.design))
            val type = view[WFD.tasks].toJsonListOfMaps().single()[WFD.traits].toJsonListOfMaps().single()[WVF.schemaRef].toOptStr()!!
                .removePrefix(defsRefPrefix)
            val entry = when {
                change.startsWith(relabel) -> mapOf(SL.label to change.removePrefix(relabel))
                change.startsWith(dropChoice) -> {
                    val dropped = change.removePrefix(dropChoice)
                    check(dropped in field.choices) { "'$dropped' is not a choice of '$label'." }
                    val kept = field.choices.filterKeys { it != dropped }.map { (l, v) -> mapOf(SL.value to v, SL.label to l) }
                    mapOf(SL.label to label, SL.choices to kept)
                }
                else -> error("'$change' is not a change the page knows.")
            }
            val basedOn = view[DSV.designBlock].toJsonMapOrEmpty()[DSV.basedOn]
            founder.postData(
                DSV.layoutEntryEdit,
                mapOf(DSV.workflowId to workflow, DSV.typeName to type, DSV.field to field.key, DSV.entry to entry, DSV.basedOn to basedOn),
            )
        }

        // Published together, as Select all unpublished selects them, and the client runs them.
        val unpublished = founder.getItems(CFEP.bundles).filter { it[CFEP.published] == false }.map { it[CFEP.name].toOptStr()!! }
        unpublished.toSet() shouldBe setOf(CPY.copyConfigName, made[CIT.configName].toOptStr()!!, workflowConfig)
        founder.postData(CFEP.bundlePublish, mapOf(CFEP.names to unpublished))
        // Back from the profile's Your users, as the page now has it: switching to a user from inside a sandbox leaves
        // it, by the same call the bar's Back to Book Club makes.
        founder.postData(AEP.leaveSandbox, mapOf(AFLD.userId to administrator))
        founder.selfClient() shouldBe client
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, client)?.content?.get("home")?.get("title") shouldBe
            tables.getValue("copy").single { it[1] == "home.title" }[2]

        // An invitation, with the client's own subject; the label; and the workflow seen by its holders.
        val address = "pilot@walk1429.test"
        cell("invite", "Email address") shouldBe addressPlaceholder
        val invited = founder.postData(UADEP.userCreate, mapOf(ADF.primaryId to address))[ADF.userId].toOptLong().shouldNotBeNull()
        val mail = MailService.get(cxt).lastEmailTo(address).shouldNotBeNull()
        mail.subject shouldBe tables.getValue("copy").single { it[1] == "invitation.subject" }[2]
        founder.postData(UADEP.userSetLabels, mapOf(ADF.userId to invited, ADF.labels to listOf(cell("userLabels", "Labels"))))
        val pilot = WfVisibility(WfVisibilityMode.restricted, listOf(cell("visibility", "Labels")))
        founder.postData(
            UADEP.clientWorkflowParams,
            mapOf(WFD.workflowId to workflow, WFP.params to mapOf(WFP.visibility to pilot.toInfo())),
        )

        // The invited person accepts, and suggests a book: the form has the label and the choices Design View gave it.
        val browser = TestHttpClient(cxt.instanceConfig)
        val token = mail.text.substringAfter("${HMENU.inviteTokenParam}=").takeWhile { !it.isWhitespace() }
        val accepted = browser.sendJsonPostRequest(AEP.invitationAccept, mapOf(AFLD.invitationToken to token))[EP.results]
            .toJsonMapOrEmpty()
        accepted[UPF.userId] shouldBe invited
        val person = TestUser(browser, cxt, accepted)
        val theirs = createForm(person, "invitedForm")
        suggest(person, "invitedSuggestion", workflow, theirs)

        // Opened to everyone, and the report the client started with lists the person's form, not the sandbox's.
        founder.postData(
            UADEP.clientWorkflowParams,
            mapOf(WFD.workflowId to workflow, WFP.params to mapOf(WFP.visibility to pilot.withMode(WfVisibilityMode.everyone).toInfo())),
        )
        val rows = founder.client.sendJsonGetRequest(UADEP.reportRun, mapOf(RRUN.reportId to ClientCreate.starterReport))[EP.items]
            .toJsonListOfMaps()
        rows.map { it[RRUN.values].toJsonMapOrEmpty()["name"] } shouldBe listOf(cell("invitedForm", "Name"))
    }


    // Part 3 (issue #1429, phase 3): an API key made in the sandbox, the catalog, and an AI's workflow written through the
    // API. The AI is played here by the bundle an assistant following docs/demo-client would write, and the form it makes
    // to try it, which is enough to hold the page to what a sandbox key may do (issue #1453): read the catalog and the
    // sandbox's forms, write a bundle as the parent's draft, try it on a form of the sandbox's own, and publish it.

    "part 3: a sandbox key made as the page says builds and publishes a workflow, its trial form stays in the sandbox" {
        val founder = TestUser.create(cxt, "developer@walk1429.test")
        val client = founder.postData(AEP.createOwnClient, mapOf(CLD.name to cell("client", "Name")))[CLD.client].toOptStr()
            .shouldNotBeNull()
        val administrator = founder.getData(AEP.selfInfo)[UPF.userId].toOptLong().shouldNotBeNull()
        founder.postData(AEP.openSandbox, emptyMap())
        val sandbox = sandboxOf(client)
        val formId = createForm(founder, "sandboxForm")

        // The key, made in the sandbox: Read-only unchecked, so a full key, lasting the days the page picks.
        cell("apiKey", "Access") shouldBe "Read-only, unchecked"
        val made = founder.postData(
            AKEY.selfCreate,
            mapOf(
                AKEY.name to cell("apiKey", "New key"), AKEY.access to AKEY.full,
                AKEY.days to cell("apiKey", "Lasts").removeSuffix(" days").toInt(),
            ),
        )
        val secret = made[AKEY.secret].toOptStr().shouldNotBeNull()
        val program = TestHttpClient(cxt.instanceConfig).apply { setHeader(AKEY.authorizationHeader, "${AKEY.bearerScheme}$secret") }
        val keyed = TestUser(program, cxt, founder.getData(AEP.selfInfo))
        keyed.selfClient() shouldBe sandbox

        // The catalog the key is shown holds the endpoint the page finds.
        val endpoints = TestUser.endpointsIn(keyed.catalog()).map { it[EI.method].toOptStr() to it[EI.path].toOptStr() }.toSet()
        // A client gets its own copy of the endpoint only when its schema differs from the shared one. On the reference
        // deployment every self-serve client's does, since it takes fewer traits than the sample's there; this test
        // instance carries no sample, so the sandbox varies nothing and the catalog lists the shared endpoint instead.
        val (method, shown) = tables.getValue("catalog").single()
        val formDocs = listOf(shown.replace(sandboxIdPlaceholder, sandbox), shown.replace("/$sandboxIdPlaceholder", ""))
            .firstOrNull { (method to it) in endpoints }
            .shouldNotBeNull()
        // Every other path the page names is one this test calls with the key below; the catalog lists only the
        // published API to a caller without env auth, as a reader on the reference deployment is, so it is not asked.
        val named = kdaPathRegex.findAll(page.substringAfter(part3Heading)).map { it.groupValues[1] }
            .filterNot { it.startsWith("/gedra") }.toSet()
        named shouldBe setOf(AEP.selfInfo, catalogPath, CFEP.bundleWrite)
        program.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe 200
        keyed.getItems(formDocs).map { it[GDF.gedraId] } shouldBe listOf(formId)

        // The AI's bundle: a trait and a workflow, written with the key as the parent's draft, which the sandbox runs --
        // asking to be reloaded, as the skill the prompt points to says a write that should run at once must.
        val written = keyed.postData(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to aiBundle, CFEP.namespaceField to clientNamespace(client), CFEP.slots to aiSlots(client),
                CFEP.reloadField to true,
            ),
        )
        written[CFEP.client] shouldBe client
        written[CFEP.published] shouldBe false
        WorkflowService.get(cxt).forClient(sandbox).workflow(aiWorkflow).shouldNotBeNull()
        WorkflowService.get(cxt).forClient(client).workflow(aiWorkflow) shouldBe null

        // The AI tries it, as the prompt asks, on a form of its own: the sandbox's.
        val tried = createForm(keyed, "sandboxForm")
        keyed.postData(GEP.workflowEngage, mapOf(GDF.gedraId to tried, WFD.workflowId to aiWorkflow))
        keyed.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to aiWorkflow, GDF.taskId to aiTask, GDF.saveId to aiSave, GDF.gedraId to tried,
                GDF.entries to listOf(mapOf(GE.traitId to aiTrait, GE.data to mapOf("rating" to 4, "thoughts" to "A good read."))),
            ),
        )
        GedraId.parse(tried).client shouldBe sandbox

        // Published from the sandbox, then the key revoked there, as the page's last step does: it is refused.
        keyed.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf(aiBundle), CFEP.reloadField to true))
        WorkflowService.get(cxt).forClient(client).workflow(aiWorkflow).shouldNotBeNull()
        founder.postData(AKEY.selfRevoke, mapOf(AKEY.keyId to made[AKEY.key].toJsonMapOrEmpty()[AKEY.keyId]))
        program.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe 401

        // Back in the client: the workflow is there for its people too, and the AI's trial form is not.
        founder.postData(AEP.leaveSandbox, mapOf(AFLD.userId to administrator))
        founder.selfClient() shouldBe client
        founder.getItems(GEP.formDocs, mapOf(EP.limit to 500)).map { it[GDF.gedraId] }.contains(tried) shouldBe false
        val member = TestUser.create(cxt, "member@walk1429.test", userClient = client)
        member.getData(GEP.workflowView, mapOf(WFD.workflowId to aiWorkflow))[WVF.found] shouldBe true
    }

})

/** The page's placeholder for the reader's first name. */
private const val placeholder = "*your first name*"
private const val walkNameKey = "name"
private const val defsRefPrefix = $$"#/$defs/"
private fun walkWorkflowKey(workflow: String) = "workflow=$workflow"

/** The tables of part 2, the reader's own client, by their markers. */
private val ownClientKeys = setOf(
    "client", "copy", "trait", "fields", "newWorkflow", "sandboxForm", "sandboxSuggestion", "design", "invite", "userLabels",
    "visibility", "invitedForm", "invitedSuggestion", "apiKey", "catalog",
)

/** Part 3's heading, after which every `/kda/...` path the page names is one the key is shown in the catalog. */
private const val part3Heading = "## Part 3:"
private const val catalogPath = "/schema/endpoints"
private val kdaPathRegex = Regex("""/kda(/[A-Za-z/]+[A-Za-z])""")

/** The page's placeholder for the reader's sandbox's id, in a path. */
private const val sandboxIdPlaceholder = "*your sandbox's id*"

/** The bundle the page's prompt asks an AI for, as one following docs/demo-client might write it. */
private const val aiBundle = "reviewTheBook"
private const val aiWorkflow = "reviewTheBook"
private const val aiTrait = "bookReview"
private const val aiTask = "review"
private const val aiSave = "saveReview"

private fun aiSlots(client: String): Map<String, Any?> = mapOf(
    CCT.traitDef to listOf(
        mapOf(
            CCT.traitId to aiTrait, CCT.typeName to "${clientNamespace(client)}.BookReviewEntry", CCT.appliesTo to listOf("formDoc"),
            CCT.description to "A member's review of a book the club has read.",
            CCT.dataSchema to mapOf(
                SCH.properties to mapOf(
                    "rating" to mapOf(SCH.title to "Rating", SCH.type to SCT.integer, "minimum" to 1, "maximum" to 5),
                    "thoughts" to mapOf(SCH.title to "What I thought", SCH.type to SCT.string),
                ),
                "required" to listOf("rating"),
            ),
        ),
    ),
    CCT.workflowDef to listOf(
        mapOf(
            CCT.workflowId to aiWorkflow,
            CCT.definition to mapOf(
                WFD.entry to "normal", WFD.label to "Review the book", WFD.workflowId to aiWorkflow,
                WFD.tasks to listOf(
                    mapOf(
                        WFD.id to aiTask, WFD.label to "Your review",
                        WFD.saves to listOf(mapOf(WFD.id to aiSave, WFD.label to "Save the review", "kind" to "edit")),
                        WFD.traits to listOf(mapOf(WFD.traitId to aiTrait, WFD.required to true)),
                    ),
                ),
            ),
        ),
    ),
)

/** The page's placeholder for the address the reader invites. */
private const val addressPlaceholder = "*an address of yours*"

/** The trait editor's kinds, by the words it shows for them -- the webapp's `kindLabel`. */
private val traitKinds = mapOf("Text" to TraitFieldKind.text, "Choice" to TraitFieldKind.choice)

/** How the page's Design View table says a change: a new label, or a choice removed. */
private const val relabel = "Label: "
private const val dropChoice = "Remove the choice: "

/**
 * The tasks and saves of the workflows a new client starts with, and of the Collect information template: the
 * template's own ids (`CoreWorkflowTemplates`), which declares no constants for them. The creation workflow's id is
 * the one the create makes from the template's label.
 */
private const val starterCreateWorkflow = "newForm"
private const val starterCreateTask = "identify"
private const val starterCreateSave = "create"
private const val collectTask = "collect"
private const val collectSave = "saveInformation"

/** A field as a form shows it: its trait, its key, and the choices it offers, label to value -- none for a free field. */
private class ShownField(val traitId: String, val key: String, val choices: Map<String, String>)

private val walkMarkerRegex = Regex("""^\s*<!--\s*kdr-walk:\s*(.*?)\s*-->\s*$""")
private val joinAsRegex = Regex("""\*\*Join as ([^*]+)\*\*""")

/**
 * Each marked table of [page], by its marker, as its rows: each a list of its cells, with Markdown's emphasis on the
 * first taken off. The header row and its rule are skipped.
 */
private fun walkTables(page: String): Map<String, List<List<String>>> {
    val lines = page.lines()
    val out = linkedMapOf<String, List<List<String>>>()
    for ((i, line) in lines.withIndex()) {
        val marker = walkMarkerRegex.find(line)?.groupValues?.get(1) ?: continue
        val rows = lines.drop(i + 1).takeWhile { it.trimStart().startsWith("|") }.drop(2).map { row ->
            val cells = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            listOf(cells[0].removeSurrounding("**")) + cells.drop(1)
        }
        check(marker !in out) { "The walk-through marks two tables '$marker'." }
        out[marker] = rows
    }
    return out
}

/**
 * The field [label] names among [traits] -- its trait, its key, and its schema -- as the form labels it: by its
 * `title`, or by its key with a capital where it has none. Fails unless exactly one field has the label.
 */
private fun fieldFor(label: String, traits: List<String>, schemas: Map<String, Map<String, Any?>>): Triple<String, String, Map<String, Any?>> {
    val matches = traits.flatMap { traitId ->
        val properties = schemas[traitId].shouldNotBeNull()[SCH.properties].toJsonMapOrEmpty()
        properties.mapNotNull { (key, raw) ->
            val schema = raw.toJsonMapOrEmpty()
            val shown = schema[SCH.title].toOptStr() ?: key.replaceFirstChar { it.uppercase() }
            if (shown == label) Triple(traitId, key, schema) else null
        }
    }
    check(matches.size == 1) { "The walk-through's '$label' is ${matches.size} fields of $traits, not one." }
    return matches.single()
}

/** What the form sends for [shown], typed as [schema] asks: choices by their labels, yes or no, a number, or text. */
private fun valueFor(schema: Map<String, Any?>, shown: String): Any {
    fun choice(options: Map<String, Any?>, label: String): String =
        options[SCH.options].toJsonListOfMaps().singleOrNull { it[SCH.label] == label }?.get(SCH.value).toOptStr()
            ?: error("'$label' is not one of the choices ${options[SCH.options]}.")
    return when {
        schema[SCH.options] != null -> choice(schema, shown)
        schema[SCH.type] == SCT.array -> shown.split(", ").map { choice(schema[SCH.items].toJsonMapOrEmpty(), it) }
        schema[SCH.type] == SCT.boolean -> when (shown) {
            "Yes" -> true
            "No" -> false
            else -> error("'$shown' is not Yes or No.")
        }
        schema[SCH.type] == SCT.integer -> shown.toLong()
        else -> shown
    }
}
