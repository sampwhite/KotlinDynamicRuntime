package com.dynamicruntime.trials

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.cfact.CFactParser
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportSource
import com.dynamicruntime.common.gedra.workflow.ULH
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.home.ClientPages
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr

/**
 * One field of a trait's data, read for matching by meaning: its [words] are its name, its layout's [label], its title, and
 * its description, lowercased,
 * so a check can ask whether a field is about an amount without knowing what the model called it.
 */
class FieldView(val traitId: String, val name: String, val schema: Map<String, Any?>, val label: String? = null) {
    /** Its JSON Schema type; an absent type is a string, as the schema dialect reads it. */
    val type: String get() = schema[SCH.type].toOptStr() ?: "string"
    val format: String? get() = schema[SCH.format].toOptStr()
    val options: List<Any?> get() = schema[SCH.options] as? List<*> ?: schema[SCH.enum] as? List<*> ?: emptyList()
    /** What its choices are called, lowercased: each by its label where it has one, else its value. */
    val optionNames: Set<String> get() = options.mapNotNull { o ->
        ((o as? Map<*, *>)?.let { it[SCH.label] ?: it[SCH.value] } ?: o).toOptStr()?.lowercase()
    }.toSet()
    val longText: Boolean get() = schema[SCH.longText] == true
    val isNumber: Boolean get() = type == "number" || type == "integer"
    val isDate: Boolean get() = format == "date" || format == "date-time"

    val words: String get() =
        listOfNotNull(name, label, schema[SCH.title].toOptStr(), schema[SCH.description].toOptStr()).joinToString(" ").lowercase()

    /** Whether any of [terms] appears in its [words]. */
    fun mentions(terms: List<String>): Boolean = terms.any { words.contains(it.lowercase()) }

    /** What kind of field it is, in a few words. */
    val kind: String get() = when {
        options.isNotEmpty() -> "a choice of ${options.size}"
        longText -> "long text"
        isDate -> format.orEmpty()
        else -> type
    }

    override fun toString(): String = "'$name' ($kind) of trait '$traitId'"
}

/**
 * A client's configuration as a trial reads it: every bundle's entries by slot, the traits' fields, the workflows, the
 * copy, Home's cards, and the reports. Built from the bundles `GET` [CFEP.bundle] answers.
 */
class ConfigView(val bundles: Map<String, Map<String, Any?>>) {
    /** Every entry of [slot], across the bundles. */
    fun entries(slot: String): List<Map<String, Any?>> =
        bundles.values.flatMap { it[CFEP.slots].toJsonMapOrEmpty()[slot].toJsonListOfMaps() }

    val fields: List<FieldView> by lazy {
        entries(CCT.traitDef).flatMap { trait ->
            val traitId = trait[CCT.traitId].toOptStr().orEmpty()
            val data = trait[CCT.dataSchema].toJsonMapOrEmpty()
            val labels = data[SCH.layout].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
                .associate { it[SL.field].toOptStr() to it[SL.label].toOptStr() }
            data[SCH.properties].toJsonMapOrEmpty().map { (name, schema) ->
                FieldView(traitId, name, schema.toJsonMapOrEmpty(), labels[name])
            }
        }
    }

    /** Each workflow's definition. */
    val workflows: List<Map<String, Any?>> by lazy { entries(CCT.workflowDef).map { it[CCT.definition].toJsonMapOrEmpty() } }

    /**
     * The creation workflow: the one whose entry is `creation`, or the starter New form, which is stored as an overlay of
     * its template and so may not say its entry.
     */
    val creation: Map<String, Any?>? get() = workflows.firstOrNull {
        it[WFD.entry] == WfEntry.creation.name || it[WFD.fromTemplate] == WFT.createForm
    }

    /** The core details workflow: the one whose entry is `coreDetails`, or the starter made from its template. */
    val coreDetails: Map<String, Any?>? get() = workflows.firstOrNull {
        it[WFD.entry] == WfEntry.coreDetails.name || it[WFD.fromTemplate] == WFT.coreDetails
    }

    /** The normal workflows: neither the creation workflow nor the core details one, which a template's overlay may not say. */
    val normalWorkflows: List<Map<String, Any?>> get() = workflows.filter {
        it !== creation && it[WFD.entry] != WfEntry.coreDetails.name && it[WFD.fromTemplate] != WFT.coreDetails
    }

    /** The review: the first normal workflow with an approval task. */
    val review: Map<String, Any?>? get() =
        normalWorkflows.firstOrNull { wf -> wf[WFD.tasks].toJsonListOfMaps().any { it[WFD.approval] != null } }

    /** The traits [workflow]'s tasks collect. */
    fun collected(workflow: Map<String, Any?>?): Set<String> = workflow?.get(WFD.tasks).toJsonListOfMaps()
        .flatMap { task -> task[WFD.traits].toJsonListOfMaps().mapNotNull { it[WFD.traitId].toOptStr() } }.toSet()

    /**
     * The copy the client rewords: file, then namespace, then key, to its value. Entries for one file, in one bundle or
     * several, are merged, a later entry's key winning.
     */
    val copy: Map<String, Map<String, Map<String, String>>> by lazy {
        val merged = mutableMapOf<String, MutableMap<String, MutableMap<String, String>>>()
        for (entry in entries(CCT.fragmentDef)) {
            val file = merged.getOrPut(entry[CCT.fileId].toOptStr().orEmpty()) { mutableMapOf() }
            for ((ns, keys) in entry[CCT.content].toJsonMapOrEmpty()) {
                val namespace = file.getOrPut(ns) { mutableMapOf() }
                keys.toJsonMapOrEmpty().forEach { (key, value) -> namespace[key] = value.toOptStr().orEmpty() }
            }
        }
        merged
    }

    /** Every reworded key as `file.namespace.key` to its value. */
    val copyKeys: Map<String, String> get() = copy.flatMap { (file, namespaces) ->
        namespaces.flatMap { (ns, keys) -> keys.map { (key, value) -> "$file.$ns.$key" to value } }
    }.toMap()

    /** Home's cards as the client's overlay gives them. */
    val homeCards: List<Map<String, Any?>> get() = entries(CCT.uiBlockDef).filter { it[CCT.blockId] == HCARD.block }
        .flatMap { it[CCT.content].toJsonMapOrEmpty()[HCARD.cards].toJsonListOfMaps() }

    /** Each report's definition. */
    val reports: List<Map<String, Any?>> get() = entries(CCT.reportDef).map { it[CCT.definition].toJsonMapOrEmpty() }

    /** What the fields are, in a line, for a detail that says what was found instead. */
    fun fieldList(): String = fields.joinToString("; ").ifEmpty { "no fields of the client's own" }
}

/** Checks the trials share, for the shapes most trials meet. */
object Checks {
    /**
     * A field that [terms] describe and that is [wanted] -- found in a trait the New form collects. Varied when such a field
     * is there but not as wanted, or not on the New form.
     */
    fun newFormField(r: TrialResult, what: String, terms: List<String>, wanted: (FieldView) -> Boolean): Outcome {
        val view = r.view
        val onForm = view.collected(view.creation)
        val candidates = view.fields.filter { it.mentions(terms) }
        val good = candidates.filter(wanted)
        return when {
            good.any { it.traitId in onForm } -> Outcome.met("${good.first { it.traitId in onForm }}.")
            good.isNotEmpty() -> Outcome.varied("${good.first()}, but the New form does not collect '${good.first().traitId}'.")
            candidates.isNotEmpty() -> Outcome.varied("Found ${candidates.joinToString()}, not as $what.")
            else -> Outcome.notMet("No field about ${terms.joinToString("/")}. Fields: ${view.fieldList()}.")
        }
    }

    /**
     * The core details workflow collects the client's own traits the New form does. The two cover the same data except in
     * unusual cases, so a request for a form's fields means both, whether or not it names the core details workflow. A
     * rooted trait (`kdr:name`) is left out: the templates collect those themselves.
     */
    fun coreDetailsMatchesNewForm(r: TrialResult): Outcome {
        val view = r.view
        val own = view.collected(view.creation).filterNot { ':' in it }
        val details = view.coreDetails ?: return Outcome.notMet("No core details workflow.")
        val missing = own - view.collected(details)
        return when {
            own.isEmpty() -> Outcome.info("The New form collects no trait of the client's own.")
            missing.isEmpty() -> Outcome.met("It collects ${own.joinToString()}.")
            missing.size < own.size -> Outcome.varied("It does not collect ${missing.joinToString()}.")
            else -> Outcome.notMet("It collects none of ${own.joinToString()}.")
        }
    }

    /**
     * The review's approval shows the client's own traits the New form collects, by naming them (issue #1875) -- the
     * answers it approves. Varied when it names only some, or shows them through a task of its own workflow, which takes
     * a step that asks nothing of anyone. A rooted trait (`kdr:name`) is left out, as for the core details check.
     */
    fun approvalShowsNewForm(r: TrialResult): Outcome {
        val view = r.view
        val own = view.collected(view.creation).filterNot { ':' in it }
        val workflow = view.review ?: return Outcome.notMet("No workflow with an approval task.")
        if (own.isEmpty()) return Outcome.info("The New form collects no trait of the client's own.")
        val tasks = workflow[WFD.tasks].toJsonListOfMaps()
        val at = tasks.indexOfFirst { it[WFD.approval] != null }
        val approval = tasks[at][WFD.approval].toJsonMapOrEmpty()
        val named = approval[WFD.showTraits].toJsonListOfStrings()
        if (WFD.showTraits in approval) {
            val missing = own - named.toSet()
            return when {
                missing.isEmpty() -> Outcome.met("It names ${own.joinToString()}.")
                missing.size < own.size -> Outcome.varied("It does not name ${missing.joinToString()}.")
                else -> Outcome.notMet("It names ${named.joinToString().ifEmpty { "nothing" }}, none of ${own.joinToString()}.")
            }
        }
        // Without names: the tasks it names, else the nearest earlier task that collects traits.
        val shownTasks = approval[WFD.showTasks].toJsonListOfStrings().ifEmpty { null }
            ?.let { ids -> tasks.filter { it[WFD.id] in ids } }
            ?: listOfNotNull(tasks.take(at).lastOrNull { it[WFD.traits].toJsonListOfMaps().isNotEmpty() })
        val viaTasks = shownTasks.flatMap { t -> t[WFD.traits].toJsonListOfMaps().mapNotNull { it[WFD.traitId].toOptStr() } }.toSet()
        return when {
            own.any { it in viaTasks } -> Outcome.varied(
                "It shows them through task ${shownTasks.joinToString { "'${it[WFD.id]}'" }} of its workflow, not by naming them.",
            )
            else -> Outcome.notMet("It shows none of ${own.joinToString()}.")
        }
    }

    /**
     * A card on Home that opens the New form (issue #1869): by its button, which takes an action naming the page **and** an
     * action label, by one of its links, or by a link in its words -- and that an applicant is shown. Varied when the card
     * opens some other page, or is hidden from applicants. A card whose action is an address, or has no words for its
     * button, opens nothing: until the guide said how, every run wrote one such, and a check that asked only for a card
     * passed it.
     */
    fun homeCardStartsForm(r: TrialResult): Outcome {
        val cards = r.view.homeCards.filter { it[HCARD.title] != null }
        if (cards.isEmpty()) {
            return Outcome.notMet("No card added or reworded on Home." + r.view.homeCards.takeIf { it.isNotEmpty() }
                ?.let { " Its overlay: ${it.joinToString { c -> cardSummary(c) }}." }.orEmpty())
        }
        val summary = cards.joinToString("; ") { cardSummary(it) }
        val opened = cards.flatMap { cardOpens(it) }
        val starting = cards.filter { HMENU.pageNewForm in cardOpens(it) }
        return when {
            starting.any { shownToApplicant(it[UIB.cfactExpression].toOptStr()) } -> Outcome.met(summary)
            starting.isNotEmpty() -> Outcome.varied("Opens the New form, but an applicant is not shown it: $summary")
            opened.isNotEmpty() -> Outcome.varied("Opens ${opened.joinToString()}, not the New form: $summary")
            else -> Outcome.notMet("Opens no page: $summary")
        }
    }

    /**
     * Whether [condition] shows an item to an applicant: someone signed in to the client, who administers and edits
     * nothing. A condition that does not parse shows it to nobody.
     */
    fun shownToApplicant(condition: String?): Boolean {
        val expression = condition ?: return true
        val names = cfactName.findAll(expression).map { it.value }.toSet()
        return CFactParser.analyze(expression, names, applicantFacts).value == true
    }

    private val cfactName = Regex("[A-Za-z][A-Za-z0-9_.:]*")

    /** What is true of an applicant in a self-serve client with a sandbox and a review, as the trials make it. */
    private val applicantFacts = setOf(
        CFACTS.app, CFACTS.loggedIn, CFACTS.selfServeClient, CFACTS.clientHasSandbox, CFACTS.clientHasWaitingWorkflows,
    )

    /** The pages [card] opens, by name: its button's, its links', and those its words link. */
    private fun cardOpens(card: Map<String, Any?>): List<String> {
        fun named(item: Map<String, Any?>, label: String): String? =
            (item[UIB.action] as? String)?.takeIf { !item[label].toOptStr().isNullOrBlank() && ClientPages.forName(it) != null }
        val text = card[HCARD.text].toOptStr().orEmpty()
        return buildList {
            addAll(listOfNotNull(named(card, HCARD.actionLabel)))
            card[HCARD.links].toJsonListOfMaps().mapNotNullTo(this) { named(it, HCARD.linkLabel) }
            ClientPages.all.filter { "](${ClientPages.address(it)})" in text }.mapTo(this) { it.page }
        }
    }

    /**
     * The review highlights its Start (issue #1895), so whoever could take its first step sees a form that may enter it as
     * their turn. Asked only of a review that starts with someone else's step: an approval, or a task whose save names
     * who may save it. One that starts with a step of the applicant's own has nobody else to point at the form.
     */
    fun reviewHighlightsStart(r: TrialResult): Outcome {
        val review = r.view.review ?: return Outcome.notMet("No workflow with an approval task.")
        val name = "'${review[WFD.label] ?: review[WFD.workflowId]}'"
        val first = review[WFD.tasks].toJsonListOfMaps().first()
        return when {
            review[WFD.highlightStart] == true -> Outcome.met("$name does.")
            first[WFD.approval] == null && first[WFD.saveWhen] == null ->
                Outcome.info("$name starts with '${first[WFD.label] ?: first[WFD.id]}', a step of the applicant's own.")
            else -> Outcome.notMet("$name does not, so a reviewer is not pointed at a form until someone starts it.")
        }
    }

    /**
     * The reply to the turn that wrote the review says a reviewer needs the `editor` role as well as the label (issue
     * #1911): with the label alone, they see no form but their own. The assistant can give neither, so saying it is all it
     * can do. Read from the reply's words ([saysEditorRole]), so a reply saying it some other way comes out not met, and is
     * worth reading. Asked only of a review that takes a label.
     */
    fun reviewersToldRole(r: TrialResult): Outcome {
        val review = r.view.review ?: return Outcome.notMet("No workflow with an approval task.")
        val takesLabel = review[WFD.tasks].toJsonListOfMaps().any { task ->
            task[WFD.functions].toJsonListOfMaps().any { it[WFD.fn] == ULH.fn }
        }
        if (!takesLabel) return Outcome.info("The review takes no label.")
        val replies = r.conversations.flatMap { c ->
            c.calls.filter { it.name == ATOOL.patchConfig && it.dryRun != true && !it.isError && CCT.workflowDef in patchedSlots(it) }
                .map { it.turnId }.distinct().map { c.reply(it) }
        }
        val said = replies.flatMap { it.lines() }.firstOrNull { saysEditorRole(it) }
        return when {
            replies.isEmpty() -> Outcome.info("No turn wrote the review with ${ATOOL.patchConfig}.")
            said != null -> Outcome.met(said.trim().take(240))
            else -> Outcome.notMet("The reply does not mention the editor role.")
        }
    }

    /**
     * Whether [line] says a person is to be given the `editor` role: names it as a role or a box to check, or says to make
     * them an editor, and does not say they do without it. The words are whole and beside each other, so the bundle editor
     * in the sandbox ("box") is not taken for it.
     */
    fun saysEditorRole(line: String): Boolean {
        // Markdown's marks and quotes may stand around the word.
        val text = line.replace(Regex("[*`'\"“”‘]"), "")
        return editorRoleForms.any { it.containsMatchIn(text) } && !notNeeded.containsMatchIn(text)
    }

    private val editorRoleForms = listOf(
        Regex("\\beditor (role|access|persona|permission|box|checkbox)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(role|access|persona)( of| called|:)? editor\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(make|makes|be|is|are|also) (them |each |each one |also )?(an )?editors?\\b", RegexOption.IGNORE_CASE),
    )
    private val notNeeded = Regex("\\b(not|nt|dont|doesnt|without|no) need(ing|ed)?\\b[^.;]*\\beditors?\\b", RegexOption.IGNORE_CASE)

    /**
     * The report is made whole by [ATOOL.createReport]: no write of [ATOOL.patchConfig] touches a report afterward
     * (issue #1894). Before the tool took a column's rollup, every run made the report without its total and then patched
     * the total and the chart in.
     */
    fun reportMadeWhole(r: TrialResult): Outcome {
        val created = r.calls.filter { it.name == ATOOL.createReport && it.dryRun != true && !it.isError }
        val patches = r.calls.filter {
            it.name == ATOOL.patchConfig && it.dryRun != true && !it.isError && CCT.reportDef in patchedSlots(it)
        }
        val after = created.firstOrNull()?.let { first -> patches.filter { r.calls.indexOf(it) > r.calls.indexOf(first) } }.orEmpty()
        return when {
            created.isEmpty() && patches.isEmpty() -> Outcome.notMet("No report was made.")
            created.isEmpty() -> Outcome.varied("Made with ${ATOOL.patchConfig} alone, in ${patches.size} write(s).")
            after.isNotEmpty() -> Outcome.varied("Made with ${ATOOL.createReport}, then patched in ${after.size} write(s).")
            else -> Outcome.met("Made with ${ATOOL.createReport}, and not patched afterward.")
        }
    }

    /**
     * A report grouped by the New form's choice that [categoryTerms] describe, totaling the number [amountTerms] do
     * (issue #1914): both by the fields its columns read, not by words found anywhere in it. How many forms a group holds
     * needs no column: a grouped run says it beside every group. Varied when a report has one and not the other. The
     * starter report, which every new client has, is nobody's answer.
     */
    fun reportTotals(r: TrialResult, categoryTerms: List<String>, amountTerms: List<String>): Outcome {
        fun reads(column: Map<String, Any?>, terms: List<String>, wanted: (FieldView) -> Boolean): Boolean {
            val path = column[RDEF.path].toOptStr().orEmpty()
            return r.view.fields.any { field ->
                // The trait, with a keyed one's selector after it, then the field, however deep.
                val rest = path.removePrefix("${ReportSource.form.name}.${field.traitId}")
                rest != path && (rest[0] == '.' || rest[0] == '[') && rest.endsWith(".${field.name}") &&
                    field.mentions(terms) && wanted(field)
            }
        }
        class Held(val report: Map<String, Any?>) {
            val columns = report[RDEF.columns].toJsonListOfMaps()
            val groupBy = report[RDEF.groupBy].toJsonListOfStrings()
            val grouped = columns.any { it[RDEF.columnId] in groupBy && reads(it, categoryTerms) { f -> f.options.isNotEmpty() } }
            val totaled = columns.any { it[RDEF.rollup] == ReportCombine.sum.name && reads(it, amountTerms) { f -> f.isNumber } }
            val lacks = listOfNotNull("not grouped by the category".takeIf { !grouped }, "not totaling the amount".takeIf { !totaled })
        }
        val reports = r.view.reports.filter { it[RDEF.reportId] != ClientCreate.starterReport }.map { Held(it) }
        val whole = reports.firstOrNull { it.lacks.isEmpty() }
        return when {
            whole != null -> Outcome.met(reportSummary(whole.report))
            reports.any { it.lacks.size == 1 } || reports.size > 1 ->
                Outcome.varied(reports.joinToString("; ") { "${it.lacks.joinToString(", ")}: ${reportSummary(it.report)}" })
            reports.isNotEmpty() -> reports.single().let {
                Outcome.notMet("It is ${it.lacks.joinToString(", and ")}: ${reportSummary(it.report)}")
            }
            else -> Outcome.notMet("No report but the starter one.")
        }
    }

    /** The slots the edits of [call], a [ATOOL.patchConfig] call, name. */
    fun patchedSlots(call: ToolCall): List<String> = call.input[CFEP.configs].toJsonListOfMaps().flatMap { config ->
        config[CFEP.edits].toJsonListOfMaps().mapNotNull { it[CFEP.slot].toOptStr() }
    }

    /**
     * Reworded copy of [file] that mentions [term]: in any of its keys, in those of [namespace], or in the one [key] of it
     * (issue #1914), so a check titled for one line is not met by another.
     */
    fun copyMentions(r: TrialResult, file: String, namespace: String?, term: String, key: String? = null): Outcome {
        val under = listOfNotNull(file, namespace, key).joinToString(".")
        val keys = r.view.copyKeys.filterKeys { it == under || it.startsWith("$under.") }
        val hit = keys.entries.firstOrNull { it.value.contains(term, ignoreCase = true) }
        return when {
            hit != null -> Outcome.met("${hit.key}: ${hit.value.take(160)}")
            keys.isNotEmpty() -> Outcome.varied("Reworded ${keys.keys.joinToString()}, none naming '$term'.")
            else -> {
                val reworded = r.view.copyKeys.keys.joinToString().ifEmpty { "none" }
                Outcome.notMet("Nothing under '$under' reworded. Reworded keys: $reworded.")
            }
        }
    }
}

/** What every trial is held to: how the assistant worked, as its transcript shows. */
object MethodChecks {
    /** Information alone: the guide is for when the tool is not plain, which a transcript does not say (issue #1914). */
    val readsMakingChanges = Expectation("Conversations that read making-changes before their first change") { r ->
        // A conversation has only what was read in it. A dry run changes nothing, so it may come before the read, and a call
        // refused changed and read nothing.
        val changing = r.conversations.mapNotNull { c ->
            val calls = c.calls.filter { !it.isError }
            val first = calls.indexOfFirst { !it.isRead && it.name != ATOOL.readDoc && it.dryRun != true }
            if (first < 0) return@mapNotNull null
            val read = calls.indexOfFirst { it.name == ATOOL.readDoc && it.input[ATOOL.doc] == HDOC.makingChanges }
            Triple(c, calls[first], read in 0..<first)
        }
        val unread = changing.filter { !it.third }
        val without = unread.joinToString("; ") { (c, change, _) -> "'${c.firstText.take(50)}' (${change.name})" }
        Outcome.info(
            when {
                changing.isEmpty() -> "It changed nothing."
                unread.isEmpty() -> "All ${changing.size} that changed something."
                else -> "${changing.size - unread.size} of the ${changing.size} that changed something. Without it: $without."
            },
        )
    }

    val dryRunsFirst = Expectation("Dry-ran each change that offers one before making it") { r ->
        // A tool offers a dry run when any of the run's calls to it said dryRun; patch_config always does. A change made
        // through one without saying dryRun at all is made outright, as dryRun false is. Rehearsed is the same change, in
        // the same turn, by a dry run that was accepted (issue #1914).
        fun change(call: ToolCall) = call.input - CFEP.dryRun
        val offering = r.calls.filter { it.dryRun != null }.map { it.name }.toSet() + ATOOL.patchConfig
        val writes = r.calls.filter { !it.isRead && it.name != ATOOL.readDoc && !it.isError }
        val made = writes.filter { it.name in offering && it.dryRun != true }
        val unrehearsed = made.filter { call ->
            r.calls.subList(0, r.calls.indexOf(call)).none {
                it.name == call.name && it.turnId == call.turnId && it.dryRun == true && !it.isError && change(it) == change(call)
            }
        }
        val noOption = writes.count { it.name !in offering }
        val also = if (noOption > 0) " $noOption change(s) offered no dry run." else ""
        when {
            made.isEmpty() -> Outcome.info("No change was made through a tool that offers a dry run.$also")
            unrehearsed.isEmpty() -> Outcome.met("All ${made.size} were dry-run first.$also")
            else -> Outcome.varied("Made without a dry run: ${unrehearsed.joinToString { it.name }}.$also")
        }
    }

    val turnsEnded = Expectation("Every turn ended in a reply") { r ->
        val unfinished = r.turns.filter { it.status != ACNV.statusDone }
        when {
            r.turns.isEmpty() -> Outcome.notMet("No turn ran.")
            unfinished.isEmpty() -> Outcome.met("${r.turns.size} turns, each done.")
            else -> Outcome.varied(
                unfinished.joinToString("; ") { "'${it.text.take(60)}' ${it.status}${it.note?.let { n -> ": $n" }.orEmpty()}" },
            )
        }
    }

    val refusals = Expectation("Tool calls refused") { r ->
        val refused = r.calls.filter { it.isError }
        if (refused.isEmpty()) {
            Outcome.info("None of ${r.calls.size} calls.")
        } else {
            Outcome.info("${refused.size} of ${r.calls.size}: " + refused.joinToString("; ") { "${it.name}: ${it.result.take(160)}" })
        }
    }

    val patchConfigUse = Expectation("Writes made with patch_config rather than a targeted tool") { r ->
        val patches = r.calls.filter { it.name == ATOOL.patchConfig && it.dryRun != true && !it.isError }
        if (patches.isEmpty()) {
            Outcome.info("None.")
        } else {
            val slots = patches.flatMap { Checks.patchedSlots(it) }.distinct()
            Outcome.info("${patches.size}, writing ${slots.joinToString().ifEmpty { "no slot named" }}.")
        }
    }

    val all: List<Expectation> = listOf(readsMakingChanges, dryRunsFirst, turnsEnded, refusals, patchConfigUse)
}

/** A report's columns, each in a line, for a detail. */
fun reportSummary(report: Map<String, Any?>): String =
    "'${report[RDEF.label]}': " + report[RDEF.columns].toJsonListOfMaps().joinToString { col ->
        "${col[RDEF.label]} = ${col[RDEF.path]}" + listOfNotNull(col[RDEF.combine], col[RDEF.rollup]).joinToString("") { " ($it)" }
    } + (report[RDEF.groupBy] as? List<*>)?.let { ", grouped by ${it.joinToString()}" }.orEmpty()

/** A card in one line. */
fun cardSummary(card: Map<String, Any?>): String = card.toJsonStr(compact = true).take(200)
