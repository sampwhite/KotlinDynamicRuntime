package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.jsonMapOrNull
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLongOrNull
import com.dynamicruntime.common.util.toOptStr

/*
 * The workflow assistant page's pure logic (issue #1527), covered under `jsNodeTest`: a conversation's stored messages
 * read into what the transcript shows, each tool call's label, the drafts the conversation made, the allowance line,
 * and when the page polls. The messages are the Messages API's own (`ABLK`), stored exactly as sent and answered.
 */

/** One stored message: its place in the conversation, its role, and its content blocks. */
class StoredMessage(val seq: Long, val role: String, val blocks: List<Map<String, Any?>>)

/** One tool call the model made, with its answer once it has one: [result] null while it runs. */
class ToolCall(val id: String, val name: String, val input: Map<String, Any?>, val result: String?, val isError: Boolean)

/** One thing the transcript shows, keyed for React by where it came from. */
sealed interface TranscriptItem {
    val key: String

    /** What the person wrote ([fromPerson]), or what the model answered. */
    class Said(override val key: String, val fromPerson: Boolean, val text: String) : TranscriptItem

    /** A progress note: the model's summarized thinking, shown muted. */
    class Note(override val key: String, val text: String) : TranscriptItem

    /** The tool calls of one answer, collapsed into one line ([label]) that opens to each call and its result. */
    class Tools(override val key: String, val label: String, val calls: List<ToolCall>) : TranscriptItem
}

/**
 * [messages] as the transcript shows them, in order. A person's text is said by them, and the tool results that come back
 * as the person's are not: each is joined to its call. A model's answer is its text, its notes, and its calls, the calls
 * of one answer making one line where the first of them stood. An empty note -- thinking the API did not summarize --
 * shows nothing.
 */
fun transcriptItems(messages: List<StoredMessage>): List<TranscriptItem> {
    val results = messages.filter { it.role == ABLK.user }.flatMap { it.blocks }
        .filter { it[ABLK.type] == ABLK.toolResult }.associateBy { it[ABLK.toolUseId].toOptStr().orEmpty() }
    val items = mutableListOf<TranscriptItem>()
    for (message in messages) {
        val person = message.role == ABLK.user
        var calls: MutableList<ToolCall>? = null
        message.blocks.forEachIndexed { i, block ->
            val key = "${message.seq}-$i"
            when (block[ABLK.type]) {
                ABLK.text -> block.getOptStr(ABLK.text)?.takeIf { it.isNotBlank() }?.let { items.add(TranscriptItem.Said(key, person, it)) }
                ABLK.thinking -> block.getOptStr(ABLK.thinking)?.takeIf { it.isNotBlank() }?.let { items.add(TranscriptItem.Note(key, it)) }
                ABLK.toolUse -> {
                    val id = block.getOptStr(ABLK.id).orEmpty()
                    val answer = results[id]
                    val call = ToolCall(
                        id, block.getOptStr(ABLK.name).orEmpty(), block[ABLK.input].toJsonMapOrEmpty(),
                        answer?.let { resultText(it[ABLK.content]) }, answer?.get(ABLK.isError) == true,
                    )
                    val open = calls
                    if (open == null) {
                        val started = mutableListOf(call)
                        calls = started
                        items.add(TranscriptItem.Tools(key, "", started))
                    } else {
                        open.add(call)
                    }
                }
            }
        }
    }
    // Labeled once each line holds all its calls.
    return items.map { if (it is TranscriptItem.Tools) TranscriptItem.Tools(it.key, toolsLabel(it.calls), it.calls) else it }
}

/** A tool result's content as text: a string, or the text of its blocks. */
fun resultText(content: Any?): String = when (content) {
    null -> ""
    is String -> content
    else -> content.toJsonListOfMaps().mapNotNull { it.getOptStr(ABLK.text) }.joinToString("\n")
}

/**
 * The line a group of [calls] shows (issue #1527): what was looked at, what was checked, and what was drafted, each
 * named, a name in backticks (`codeSpans` draws it as code). "Looked at: bundle `rsvp`, workflows, cfacts". A call
 * still running ends its part with an ellipsis; one refused says so.
 */
fun toolsLabel(calls: List<ToolCall>): String {
    val looked = mutableListOf<String>()
    val parts = mutableListOf<String>()
    for (call in calls) {
        val outcome = when {
            call.result == null -> "…"
            call.isError -> " (refused)"
            else -> ""
        }
        when (call.name) {
            ATOOL.patchConfig -> {
                val names = patchedBundles(call)
                if (call.input[CFEP.dryRun] == true) {
                    parts.add("Checked a change to ${names.joinToString(", ") { "`$it`" }}$outcome")
                } else {
                    val versions = if (call.isError) emptyMap() else writtenVersions(call.result)
                    val named = names.joinToString(", ") { name -> "`$name`" + (versions[name]?.let { " (v$it)" } ?: "") }
                    parts.add(if (call.isError) "Not drafted: $named" else "Drafted $named$outcome")
                }
            }
            ATOOL.setIncludedTraits ->
                parts.add(if (call.isError) "Not drafted: the included traits" else "Drafted the included traits$outcome")
            in targetedWrites -> {
                val what = wroteWhat(call)
                // A workflow's parameters are set, in force at once in the sandbox, never drafted (issue #1766).
                val verb = if (call.name == ATOOL.setWorkflowParams) "Set" else "Drafted"
                parts.add(
                    when {
                        call.input[CFEP.dryRun] == true -> "Checked a change to $what$outcome"
                        call.isError -> "Not ${verb.lowercase()}: $what"
                        else -> "$verb $what$outcome"
                    },
                )
            }
            else -> looked.add(lookedAt(call) + outcome)
        }
    }
    if (looked.isNotEmpty()) parts.add(0, "Looked at: ${looked.joinToString(", ")}")
    return parts.joinToString("; ")
}

/**
 * The targeted writes, each saving a draft of one bundle: the client's wording and menu (issue #1763), its definition
 * and forms list (issue #1764), whose saves but the layout's are dry-run first, Design View's edits (issue #1765), and
 * the editors of template workflows, traits, and reports (issue #1766). A workflow's parameters are no draft: their
 * answer names no bundle, so they are listed among none.
 */
private val targetedWrites = setOf(
    ATOOL.setCopy, ATOOL.applyCopy, ATOOL.resetCopy, ATOOL.setMenuItem, ATOOL.resetMenuItem,
    ATOOL.setClientDefinition, ATOOL.saveFormsListColumn, ATOOL.removeFormsListColumn, ATOOL.setFormsListLayout,
    ATOOL.setFieldCopy, ATOOL.setSharedField, ATOOL.setHeading, ATOOL.setSharedHeading, ATOOL.setWorkflowLabel, ATOOL.setShownFields,
    ATOOL.saveTemplateWorkflow, ATOOL.removeTemplateWorkflow, ATOOL.setWorkflowParams, ATOOL.designTrait, ATOOL.narrowTrait,
    ATOOL.createReport, ATOOL.removeReport,
)

/**
 * What a targeted write changed, as the label names it: "copy `home: home.title`", "menu item `docs`", "the client's
 * definition", "forms list column `Memo`".
 */
fun wroteWhat(call: ToolCall): String {
    val file = call.input.getOptStr(COV.fileId).orEmpty()
    fun key() = "copy `$file: ${call.input.getOptStr(COV.namespaceField).orEmpty()}.${call.input.getOptStr(COV.key).orEmpty()}`"
    val item = "menu item `${call.input.getOptStr(COV.itemId).orEmpty()}`"
    return when (call.name) {
        ATOOL.setCopy -> key()
        ATOOL.resetCopy -> "the platform's wording for ${key()}"
        ATOOL.applyCopy -> "copy in `$file` (${call.input[CPY.changes].toJsonListOfMaps().size} keys)"
        ATOOL.resetMenuItem -> "the shipped $item"
        ATOOL.setClientDefinition -> "the client's definition"
        ATOOL.saveFormsListColumn -> "forms list column `${call.input[CUS.column].toJsonMapOrEmpty().getOptStr(CCT.label).orEmpty()}`"
        ATOOL.removeFormsListColumn -> "the removal of forms list column `${call.input.getOptStr(CCT.label).orEmpty()}`"
        ATOOL.setFormsListLayout -> "the forms list's layout"
        // Design View's edits (issue #1765), by the workflow or field they change.
        ATOOL.setFieldCopy ->
            "the copy of `${call.input.getOptStr(DSV.field).orEmpty()}` in `${call.input.getOptStr(DSV.workflowId).orEmpty()}`"
        ATOOL.setSharedField -> "the shared copy of `${call.input.getOptStr(DSV.field).orEmpty()}`"
        ATOOL.setHeading -> "a heading in `${call.input.getOptStr(DSV.workflowId).orEmpty()}`"
        ATOOL.setSharedHeading -> "a shared heading"
        ATOOL.setWorkflowLabel -> "a label in `${call.input.getOptStr(DSV.workflowId).orEmpty()}`"
        ATOOL.setShownFields -> "the fields `${call.input.getOptStr(DSV.workflowId).orEmpty()}` shows"
        // The editors of template workflows, traits, and reports (issue #1766), by what they make or change.
        ATOOL.saveTemplateWorkflow -> "workflow `${call.input[CWF.slots].toJsonMapOrEmpty().getOptStr(WFD.label).orEmpty()}`"
        ATOOL.removeTemplateWorkflow -> "the removal of workflow `${call.input.getOptStr(WFD.workflowId).orEmpty()}`"
        ATOOL.setWorkflowParams -> "the sandbox's parameters of `${call.input.getOptStr(WFD.workflowId).orEmpty()}`"
        ATOOL.designTrait -> "trait `${call.input[CIT.design].toJsonMapOrEmpty().getOptStr(CIT.label).orEmpty()}`"
        ATOOL.narrowTrait -> "the narrowing of `${call.input[CIT.narrowing].toJsonMapOrEmpty().getOptStr(CIT.traitId).orEmpty()}`"
        ATOOL.createReport -> "report `${call.input.getOptStr(RDEF.label).orEmpty()}`"
        ATOOL.removeReport -> "the removal of report `${call.input.getOptStr(RRUN.reportId).orEmpty()}`"
        else -> item
    }
}

/** What a read call looked at, as the label names it. */
fun lookedAt(call: ToolCall): String = when (call.name) {
    ATOOL.readDoc -> "document `${call.input.getOptStr(ATOOL.doc).orEmpty()}`"
    ATOOL.listConfigs -> "bundles"
    ATOOL.readConfig -> "bundle `${call.input.getOptStr(CFEP.name).orEmpty()}`"
    ATOOL.readConfigEntries -> "entries of `${call.input.getOptStr(CFEP.name).orEmpty()}`"
    ATOOL.listWorkflows -> "workflows"
    ATOOL.listTraits -> "traits"
    ATOOL.readCfacts -> "cfacts"
    ATOOL.viewWorkflow -> call.input.getOptStr(GDF.workflowId)?.let { "workflow `$it`" } ?: "the creation workflow"
    // The reads of the client as its sandbox runs it (issue #1762).
    ATOOL.readClient -> "the client's definition"
    ATOOL.readMenu -> "the menu"
    ATOOL.readCopy -> call.input.getOptStr(ATOOL.file)?.let { file ->
        call.input.getOptStr(ATOOL.namespace)?.let { "copy `$file.$it`" } ?: "copy `$file`"
    } ?: "the copy files"
    ATOOL.readOverrides -> "the client's changes"
    ATOOL.readFormsList -> "the forms list"
    ATOOL.listReports -> "reports"
    ATOOL.readReportPaths -> "report paths"
    ATOOL.readWebAssets -> "web assets"
    ATOOL.readFileRefs -> "file refs"
    ATOOL.listDrafts -> "drafts"
    ATOOL.readImpact -> "what publishing would do"
    ATOOL.readChecklist -> "the getting-started steps"
    ATOOL.readDefinition -> "where `${call.input.getOptStr(DSV.key).orEmpty()}` comes from"
    else -> "`${call.name}`"
}

/** The bundles a `patch_config` call names, in order. */
fun patchedBundles(call: ToolCall): List<String> =
    call.input[CFEP.configs].toJsonListOfMaps().mapNotNull { it.getOptStr(CFEP.name) }

/**
 * Each bundle's version as a write answered it, by name: empty when the answer is not one to read -- a long one is cut
 * to fit what the model is shown, and is then not JSON.
 */
fun writtenVersions(result: String?): Map<String, Long> =
    result?.jsonMapOrNull()?.get(CFEP.configs).toJsonListOfMaps().mapNotNull { config ->
        val name = config.getOptStr(CFEP.name) ?: return@mapNotNull null
        config[CFEP.version].toOptLongOrNull()?.let { name to it }
    }.toMap()

/**
 * One configuration the conversation drafted (issue #1527): a bundle written by `patch_config` -- its latest version, as
 * far as the answer said, and the workflows its edits added or changed -- or by a wording or menu save (issue #1763), or
 * the included traits ([isTraits]).
 */
class AssistantDraft(val bundle: String?, val version: Long?, val workflowIds: List<String>, val isTraits: Boolean = false) {
    /** How the drafts panel names it: "`rsvp` (v3)", or "The included traits". */
    val title: String get() = if (isTraits) "The included traits" else "`$bundle`" + (version?.let { " (v$it)" } ?: "")
}

/**
 * The drafts [messages] made, in the order first made: one per bundle, at its latest write, and one for the included
 * traits. Only a write that succeeded counts -- a dry run writes nothing, and a refused write wrote nothing either.
 */
fun draftsOf(messages: List<StoredMessage>): List<AssistantDraft> {
    val drafts = LinkedHashMap<String, AssistantDraft>()
    val calls = (transcriptItems(messages).filterIsInstance<TranscriptItem.Tools>()).flatMap { it.calls }
    for (call in calls) {
        if (call.result == null || call.isError) continue
        when (call.name) {
            ATOOL.patchConfig -> {
                if (call.input[CFEP.dryRun] == true) continue
                val versions = writtenVersions(call.result)
                for (config in call.input[CFEP.configs].toJsonListOfMaps()) {
                    val name = config.getOptStr(CFEP.name) ?: continue
                    val workflows = config[CFEP.edits].toJsonListOfMaps()
                        .filter { it[CFEP.slot] == CCT.workflowDef && it[GED.action] != GedraEditAction.deleteOrNoOp.name }
                        .mapNotNull { it[GE.data].toJsonMapOrEmpty().getOptStr(CCT.workflowId) }
                    val before = drafts[name]
                    drafts[name] = AssistantDraft(
                        name, versions[name] ?: before?.version, ((before?.workflowIds ?: emptyList()) + workflows).distinct(),
                    )
                }
            }
            ATOOL.setIncludedTraits -> drafts[CLD.includedTraits] = AssistantDraft(null, null, emptyList(), isTraits = true)
            // The bundle a targeted save landed in (issues #1763, #1764), which its answer names; a dry run saved nothing.
            in targetedWrites -> {
                if (call.input[CFEP.dryRun] == true) continue
                val name = call.result.jsonMapOrNull()?.getOptStr(COV.configName) ?: continue
                drafts[name] = drafts[name] ?: AssistantDraft(name, null, emptyList())
            }
        }
    }
    return drafts.values.toList()
}

/** One turn as the page reads it: what was sent, how it went, and what the person is told about how it ended. */
class TurnView(val turnId: String, val text: String, val status: String, val note: String?)

/**
 * What the conversation panel says under the transcript about the last turn, or null when there is nothing to say: it
 * ran to its end, or is still running. Stopped by the person, it says so; any other ending, the backend's own words.
 */
fun turnEndText(turn: TurnView?): String? = when (turn?.status) {
    null, ACNV.statusRunning, ACNV.statusDone -> null
    ACNV.statusCanceled -> turn.note ?: "Stopped, as you asked."
    else -> turn.note ?: "The assistant stopped before it finished."
}

/**
 * Whether the page polls the conversation (issue #1527): while a turn runs in it, and between sending a message and
 * hearing that its turn -- [pendingTurnId] -- has begun and ended. Idle, it reads nothing.
 */
fun pollsConversation(running: Boolean, pendingTurnId: String?, turns: List<TurnView>): Boolean {
    if (running) return true
    val pending = pendingTurnId ?: return false
    return turns.none { it.turnId == pending && it.status != ACNV.statusRunning }
}

/**
 * What the page offers a person to start with (issue #1569), each filling the box: what a new form asks for -- unless
 * the client is [definedInCode], where that is the code's -- and a workflow. Pure, and covered under `jsNodeTest`.
 */
fun assistantExamples(definedInCode: Boolean): List<String> = listOfNotNull(
    "What should a new form ask for?".takeIf { !definedInCode },
    "Add a workflow where a manager reviews and approves each form.",
    "Add a workflow that collects a short answer once a form exists.",
)

/**
 * Whether the page offers [assistantExamples]: on a new conversation, for a person with none yet, while the box is empty
 * and nothing blocks a send ([blocked]). Pure, and covered under `jsNodeTest`.
 */
fun showsExamples(conversationId: String?, conversations: List<ConversationSummary>?, text: String, blocked: String?): Boolean =
    conversationId == null && conversations?.isEmpty() == true && text.isBlank() && blocked == null

/**
 * Where a browser tab keeps the conversation it had open on the assistant page for [client] (issue #1754), in
 * `sessionStorage`, so coming back to the page in the same tab reopens it. Per client, since a person may administer
 * several; the status names the sandbox's parent from inside it, so the sandbox shares its client's.
 */
fun assistantConversationKey(client: String): String = "kdrAssistantConversation.$client"

/**
 * The conversation to reopen from what the tab [remembered] (issue #1754): it, when it is one of the person's
 * [listed] conversations still; null -- a new conversation -- when nothing is remembered, a new one was, or it is gone.
 * Pure, and covered under `jsNodeTest`.
 */
fun restoredConversation(remembered: String?, listed: List<ConversationSummary>): String? =
    remembered?.takeIf { r -> r.isNotBlank() && listed.any { it.conversationId == r } }

/** How often the page polls while it does, in milliseconds. */
const val assistantPollMs = 1_500

/**
 * How long the page waits before its next poll after [failures] polls in a row failed: [assistantPollMs], doubling
 * with each failure up to eight times as long, so a blip is ridden out and an outage is not hammered.
 */
fun pollDelayMs(failures: Int): Int = assistantPollMs shl failures.coerceIn(0, 3)

/** The allowance as the page reads it (`ACNV.statusType`): this month's use as a share of the budget, and when it resets. */
class AllowanceView(val usedShare: Double, val spent: Boolean, val resetsAt: String)

/** A share as a whole percentage, from 0 to 100: "12" for 0.123. */
fun sharePercent(share: Double): Int = if (share.isNaN()) 0 else (share * 100).toInt().coerceIn(0, 100)

/** The allowance's percentage, whole, at most 100: "12". */
fun allowancePercent(allowance: AllowanceView): Int = sharePercent(allowance.usedShare)

/**
 * The allowance panel's line (issue #1527): the share used, or -- once it is used up -- that it is, when it starts again,
 * and that an operator can raise it. When is the start of the month in UTC, which use is kept by, shown in [zone] as
 * every moment is: a client west of UTC sees it start on the last afternoon of the month before.
 */
fun allowanceText(allowance: AllowanceView, zone: String = DisplayZone.current): String {
    val resets = formatTimestamp(allowance.resetsAt, zone)
    return if (allowance.spent) {
        "This month's allowance is used up, so the assistant cannot be sent anything until it starts again, $resets. " +
            "An operator can raise it."
    } else {
        "${allowancePercent(allowance)}% of this month's allowance is used. It starts again $resets."
    }
}

/**
 * The id a message is sent with: the one already given to [text] when it went unanswered -- so sending it again starts
 * no second turn -- and a [fresh] one for anything else. [previous] is the id and the text it was last sent with.
 */
fun messageIdFor(previous: Pair<String, String>?, text: String, fresh: () -> String): String =
    previous?.takeIf { it.second == text }?.first ?: fresh()

/** [text] split on backticks into its plain parts and its code parts, in order: `code` is a part with true. */
fun codeSpans(text: String): List<Pair<String, Boolean>> =
    text.split('`').mapIndexed { i, part -> part to (i % 2 == 1) }.filter { it.first.isNotEmpty() }

/** [content], a stored message's content, as blocks: a plain string is one text block. */
fun blocksOf(content: Any?): List<Map<String, Any?>> = when (content) {
    is String -> listOf(mapOf(ABLK.type to ABLK.text, ABLK.text to content))
    else -> content.toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
}
