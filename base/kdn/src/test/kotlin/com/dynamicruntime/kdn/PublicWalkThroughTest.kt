package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.JOIN
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
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

    "the page has a table for every form it has the reader fill in, and for the committee user's name" {
        tables.keys shouldBe traitsByForm.keys.map { walkWorkflowKey(it) }.toSet() + walkNameKey
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
        val name = tables.getValue(walkNameKey).single().second.replace(placeholder, firstName)
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
})

/** The page's placeholder for the reader's first name. */
private const val placeholder = "*your first name*"
private const val walkNameKey = "name"
private const val defsRefPrefix = $$"#/$defs/"
private fun walkWorkflowKey(workflow: String) = "workflow=$workflow"

private val walkMarkerRegex = Regex("""^\s*<!--\s*kdr-walk:\s*(.*?)\s*-->\s*$""")
private val joinAsRegex = Regex("""\*\*Join as ([^*]+)\*\*""")

/**
 * Each marked table of [page], by its marker, as its rows: the label and the value, with Markdown's emphasis on the
 * label taken off. The header row and its rule are skipped.
 */
private fun walkTables(page: String): Map<String, List<Pair<String, String>>> {
    val lines = page.lines()
    val out = linkedMapOf<String, List<Pair<String, String>>>()
    for ((i, line) in lines.withIndex()) {
        val marker = walkMarkerRegex.find(line)?.groupValues?.get(1) ?: continue
        val rows = lines.drop(i + 1).takeWhile { it.trimStart().startsWith("|") }.drop(2).map { row ->
            val cells = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            cells[0].removeSurrounding("**") to cells[1]
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
