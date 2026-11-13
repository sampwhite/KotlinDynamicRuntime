package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.content.CPAGE
import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.FragmentNotes
import com.dynamicruntime.common.content.MDOC
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.ClientPresentationFields
import com.dynamicruntime.common.gedra.WorkflowLayout
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.PORT
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.http.request.InProcessCaller
import com.dynamicruntime.common.http.request.InProcessPrincipal
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.InProcessScope
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.grantsdemo.GrantsDemo

/**
 * The wire names of a tool's definition and its use (each name matches its value; a block's are the kernel's `ABLK`), and
 * the tools' limits. The tool names are the kernel's `ATOOL`, which the page labels calls by.
 */
@Suppress("ConstPropertyName")
object TOOL {
    const val name = ABLK.name
    const val description = "description"
    const val inputSchema = "input_schema"
    const val toolUse = ABLK.toolUse
    const val toolResult = ABLK.toolResult
    const val id = ABLK.id
    const val input = ABLK.input
    const val toolUseId = ABLK.toolUseId
    const val content = ABLK.content
    const val isError = ABLK.isError

    /** The most characters of a tool's result the model is shown. */
    const val maxResultChars = 30_000

    /** The most characters of a document `read_doc` shows: a reference is read whole, and has no pages. */
    const val maxDocChars = 100_000

    /** What a request's client identity says when the assistant made it. */
    const val appId = "assistant"

    /** The documents `read_doc` may read ([AssistantTools.readableDoc]), beyond the demo client's pages. */
    val docs = listOf(
        HDOC.clientConfiguration, HDOC.schemaDialect, HDOC.validationMessages, HDOC.expressions, HDOC.facts, HDOC.copy,
        HDOC.reportPaths, HDOC.formLayouts,
    )

    /** How deep a tool's input may nest before it is refused rather than walked. */
    const val maxDepth = 30
}

/** The wire names of `read_copy`'s index (issue #1762) that are not the copy keys' own. */
@Suppress("ConstPropertyName")
object CPYIDX {
    /** Per file: its namespaces, each with how many keys it holds. */
    const val namespaces = "namespaces"

    /** Said instead when a file or namespace holds no keys. */
    const val none = "none"

    /** The index's two parts: the pages, each with how many keys a client is likely to reword there, and the files. */
    const val pages = "pages"
    const val files = "files"
}

/** What a tool answered: [text] for the model, and whether it was an error the model can correct from. */
class ToolResult(val text: String, val isError: Boolean)

/**
 * One tool's use within a turn: the [cxt] of the worker running it, the [principal] its calls act as -- the person's
 * own user in the sandbox, re-read on every call -- and what the turn has learned so far: [dryRuns], the patches a dry
 * run accepted in an earlier step, which a write must match. A dry run counts only once the model has seen its answer,
 * so one made in the same step as its write ([stepDryRuns]) does not yet.
 */
class ToolRun(val cxt: KdrCxt, val principal: InProcessPrincipal, val traceId: String) {
    val dryRuns: MutableSet<String> = mutableSetOf()
    val stepDryRuns: MutableSet<String> = mutableSetOf()

    /** The step's tools are answered: its dry runs now count. */
    fun endStep() {
        dryRuns.addAll(stepDryRuns)
        stepDryRuns.clear()
    }

    /**
     * Calls one endpoint in process, as [principal], and puts its answer in the model's form: the payload without its
     * envelope, reshaped by [shape] when given, as JSON.
     */
    fun call(
        method: HttpMethod,
        path: String,
        args: Map<String, Any?>? = null,
        body: Map<String, Any?>? = null,
        shape: ((Any?) -> Any?)? = null,
    ): ToolResult {
        val result = InProcessCaller.call(cxt.instanceConfig, principal, method, path, args, body, traceId, appId = TOOL.appId)
        return when {
            result.isSuccess -> {
                val envelope = result.envelope
                val payload = envelope[EP.results] ?: envelope[EP.item] ?: envelope[EP.items]?.let { items ->
                    buildMap {
                        put(EP.items, items)
                        envelope[EP.summary]?.let { put(EP.summary, it) }
                    }
                } ?: envelope
                ToolResult(capped((shape?.invoke(payload) ?: payload).toJsonStr(compact = true)), isError = false)
            }
            // A refusal the model can correct from: the endpoint's own words, and whatever detail it attached.
            result.status in 400..499 -> {
                val refusal = result.envelope.ifEmpty { mapOf(EP.status to result.status) }
                ToolResult(capped(refusal.toJsonStr(compact = true)), isError = true)
            }
            else -> ToolResult("The endpoint failed (${result.status}): ${result.errorMessage ?: "no message"}", isError = true)
        }
    }

    companion object {
        /**
         * [text] cut to [max] characters -- never inside a character written as two -- saying that it was, and what
         * to do instead ([advice]).
         */
        fun capped(
            text: String,
            max: Int = TOOL.maxResultChars,
            advice: String = "Read one config, or its entries, rather than all at once.",
        ): String {
            if (text.length <= max) return text
            val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
            return text.take(end) + "\n[Cut: $end of ${text.length} characters shown. $advice]"
        }
    }
}

/**
 * One tool the model may use (issue #1526): its [name], [description], and JSON-schema [inputSchema], and what it does
 * with an input ([run]). A tool that calls an endpoint names it ([method], [path]) and the inputs it sends there
 * ([sends]); the set of those endpoints *is* the allowlist the in-process seam holds the assistant to.
 */
class AssistantTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val method: HttpMethod?,
    val path: String?,
    val sends: Set<String> = emptySet(),
    val run: (ToolRun, Map<String, Any?>) -> ToolResult,
) {
    /** The definition the model is sent. */
    fun toWire(): Map<String, Any?> = linkedMapOf(TOOL.name to name, TOOL.description to description, TOOL.inputSchema to inputSchema)
}

/**
 * The assistant's tools (issue #1526): reads of the client's configuration, workflows, traits, and cfacts, and of the
 * client as its sandbox runs it -- its definition, menu, copy, forms list, reports, images, drafts and their impact
 * ([clientReads], issue #1762) -- and writes that can only make drafts: `patch_config`, a dry run before every write,
 * `set_included_traits`, the copy and menu editors' targeted edits ([wordingAndMenuWrites], issue #1763), and the
 * definition and forms-list editors' ([definitionAndFormsListWrites], issue #1764), and Design View's ([designWrites],
 * issue #1765). The tools
 * act as the person's own user in the client's sandbox, whose configuration edits become drafts on the parent.
 *
 * **What is left out is the point.** Publishing, reverting, deleting a config, `publishedOnly`, `bundle/write` (which
 * deletes what it is not given), switching the sandbox, `workflows/params` (in force at once), users, keys, sessions,
 * form data, and every `admin`, `operator`, and `fixture` endpoint: none is a tool, so the seam refuses each.
 */
@Suppress("CollectionConcatenationToBuildCollection")
object AssistantTools {
    val all: List<AssistantTool> by lazy { build() }

    val byName: Map<String, AssistantTool> by lazy { all.associateBy { it.name } }

    /** The scope the seam holds every call to: exactly the endpoints the tools name. */
    val scope: InProcessScope by lazy {
        InProcessScope.of("the workflow assistant", all.mapNotNull { t -> t.path?.let { t.method!!.name to it } }.toSet())
    }

    /** The tool definitions, as the model is sent them. */
    fun definitions(): List<Map<String, Any?>> = all.map { it.toWire() }

    /** Runs the [name]d tool on [input]: an unknown tool or a failure is an error result, never a thrown one. */
    fun run(run: ToolRun, name: String, input: Map<String, Any?>): ToolResult {
        val tool = byName[name] ?: return ToolResult("There is no tool named '$name'.", isError = true)
        return try {
            tool.run(run, input)
        } catch (e: KdrException) {
            ToolResult(e.message.orEmpty(), isError = true)
        } catch (e: Exception) {
            // A tool's own failure is its answer, so the turn's other calls are still answered for what they did.
            LogAssistant.error(run.cxt, "The '$name' tool failed.", e)
            ToolResult("The tool failed: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    /**
     * A document `read_doc` may read: a reference ([TOOL.docs]), a page of the demo client's, or a page of the grants
     * demo's (issue #1799), which shows each theme of a client's wording reworded.
     */
    fun readableDoc(cxt: KdrCxt, docId: String): String? {
        if (!isReadableDocId(docId)) return null
        val declared = MarkdownDocService.declaredDoc(cxt, docId) ?: return null
        return declared.generatedText ?: ContentResources.readText(MarkdownDocService.resourceDir, docId)
    }

    /** The ids [readableDoc] reads on this node. */
    @Suppress("UNCHECKED_CAST")
    fun readableDocIds(cxt: KdrCxt): List<String> = (cxt.instanceConfig.get(MDOC.registryKey) as? List<MarkdownDocDef>).orEmpty()
        .map { it.docId }.filter { isReadableDocId(it) }

    private fun isReadableDocId(docId: String): Boolean =
        docId in TOOL.docs || docId.startsWith(ConfDemo.docIdPrefix) || docId.startsWith(GrantsDemo.docIdPrefix)

    @KdrPrivate
    fun build(): List<AssistantTool> = buildList {
        addAll(
            listOf(
                AssistantTool(
                    ATOOL.readDoc,
                    "Reads a reference document as Markdown: '${HDOC.clientConfiguration}' (every config slot and its fields), " +
                            "'${HDOC.schemaDialect}' (how a trait's data type is written), '${HDOC.validationMessages}' (the codes " +
                            "a refused value is reported with, and how a schema gives one a message), '${HDOC.expressions}' (the " +
                            $$"${…} language of copy, columns, layouts, and computed values), '$${HDOC.facts}' (the facts a menu entry, " +
                            "a field's visibility, a save rule, or an eligibility test is written over), '${HDOC.copy}' (the Markdown copy " +
                            "is written in, its roles, and the fragment file format), '${HDOC.reportPaths}' (how a report column's path is " +
                            "written, and every attribute it can end in), '${HDOC.formLayouts}' (what a type's g-layout may hold, and " +
                            "how a client's layout changes the shared one), a page of the demo client's " +
                            "('${ConfDemo.docIdPrefix}README' lists them), which shows a whole client's configuration worked through, " +
                            "or a page of the grants demo's ('${GrantsDemo.docIdPrefix}README' lists them), which rewords every piece " +
                            "of copy a client is likely to, theme by theme.",
                    this@AssistantTools.objectSchema(
                        ATOOL.doc to this@AssistantTools.stringSchema("The document's id."),
                        required = listOf(ATOOL.doc)
                    ),
                    method = null, path = null,
                ) { run, input ->
                    val docId = input[ATOOL.doc].toOptStr().orEmpty()
                    this@AssistantTools.readableDoc(run.cxt, docId)?.let {
                        ToolResult(ToolRun.capped(it, TOOL.maxDocChars, "The rest of this document cannot be read here."), isError = false)
                    }
                        ?: ToolResult("No document '$docId'. Readable here: ${this@AssistantTools.readableDocIds(run.cxt).joinToString(", ")}.", isError = true)
                },
                AssistantTool(
                    ATOOL.listConfigs,
                    "Lists the client's configuration bundles: each one's name, version, whether its latest revision is published, " +
                            "and its slot and entry counts.",
                    this@AssistantTools.objectSchema(), HttpMethod.GET, CFEP.bundles,
                ) { run, _ -> run.call(HttpMethod.GET, CFEP.bundles) },
                AssistantTool(
                    ATOOL.readConfig,
                    "Reads one configuration bundle whole, as the sandbox runs it (drafts included), in portable form: the client's " +
                            "own namespace is written 'client.@self'. Data in that form may be patched back as it is.",
                    this@AssistantTools.objectSchema(
                        CFEP.name to this@AssistantTools.stringSchema("The bundle's name."),
                        required = listOf(CFEP.name)
                    ),
                    HttpMethod.GET, CFEP.bundle, setOf(CFEP.name, PORT.portableField),
                ) { run, input -> run.call(HttpMethod.GET, CFEP.bundle, mapOf(CFEP.name to input[CFEP.name], PORT.portableField to true)) },
                AssistantTool(
                    ATOOL.readConfigEntries,
                    "Reads one bundle's entries, each with its slot, data (portable form), and 'updatedAt'. Pass an entry's " +
                            "'updatedAt' as 'expectUpdatedAt' when patching it, so a change someone else made since is not overwritten.",
                    this@AssistantTools.objectSchema(
                        CFEP.name to this@AssistantTools.stringSchema("The bundle's name."),
                        required = listOf(CFEP.name)
                    ),
                    HttpMethod.GET, CFEP.slotEntries, setOf(CFEP.name, PORT.portableField),
                ) { run, input -> run.call(HttpMethod.GET, CFEP.slotEntries, mapOf(CFEP.name to input[CFEP.name], PORT.portableField to true)) },
                AssistantTool(
                    ATOOL.listWorkflows,
                    "Lists the client's workflows as its sandbox runs them, drafts included, with the templates a new one may start " +
                            "from and why any is refused (a plan's limit, say).",
                    this@AssistantTools.objectSchema(), HttpMethod.GET, UADEP.clientWorkflows,
                ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientWorkflows) },
                AssistantTool(
                    ATOOL.listTraits,
                    "Lists the global traits a client may include, with their data types, the client's own traits, and its plan's " +
                            "quotas for schemas and attributes.",
                    this@AssistantTools.objectSchema(), HttpMethod.GET, UADEP.clientTraits,
                ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientTraits) },
                AssistantTool(
                    ATOOL.readCfacts,
                    "Reads the cfacts reference as Markdown: every cfact a workflow's conditions may name, what makes it true, and " +
                            "whether it is true for the caller now.",
                    this@AssistantTools.objectSchema(), HttpMethod.GET, CFD.cfactsPath,
                ) { run, _ -> run.call(HttpMethod.GET, CFD.cfactsPath) },
                AssistantTool(
                    ATOOL.viewWorkflow,
                    "Shows a workflow as the sandbox runs it -- its tasks, the traits each shows, and who may act -- so a draft can " +
                            "be checked to have loaded. With no workflowId, the client's creation workflow.",
                    this@AssistantTools.objectSchema(GDF.workflowId to this@AssistantTools.stringSchema("The workflow's id.")),
                    HttpMethod.GET, GEP.workflowView, setOf(GDF.workflowId),
                ) { run, input ->
                    run.call(HttpMethod.GET, GEP.workflowView, input[GDF.workflowId].toOptStr()?.let { mapOf(GDF.workflowId to it) })
                },
            )
        )
        addAll(this@AssistantTools.clientReads())
        addAll(this@AssistantTools.wordingAndMenuWrites())
        addAll(this@AssistantTools.definitionAndFormsListWrites())
        addAll(this@AssistantTools.designWrites())
        addAll(
            listOf(
                AssistantTool(
                    ATOOL.patchConfig,
                    "Changes the client's configuration, as a draft: nothing is live until a person publishes it. Each config " +
                            "names a bundle (a new name makes one) and its edits; each edit names a slot, an action ('addOrMerge', " +
                            "'addOrReplace', or 'deleteOrNoOp'), and the entry's data, in portable form. **Dry run first**: with " +
                            "dryRun true nothing is written, and the answer is what the change would affect. A write answers with " +
                            "the bundles as written, each entry's new 'updatedAt' among them, and under '${FSR.auto}' whether the " +
                            "forms the sandbox already holds were brought up to date, so a new workflow is offered to them. " +
                            "A write (dryRun false) is refused " +
                            "unless the same configs passed a dry run in an earlier step of this turn: dry run, read the answer, " +
                            "then write.",
                    this@AssistantTools.objectSchema(
                        CFEP.configs to this@AssistantTools.arraySchema(
                            "The bundles to change.",
                            this@AssistantTools.objectSchema(
                                CFEP.name to this@AssistantTools.stringSchema("The bundle's name."),
                                CFEP.edits to this@AssistantTools.arraySchema(
                                    "Its edits, in order.",
                                    this@AssistantTools.objectSchema(
                                        CFEP.slot to this@AssistantTools.stringSchema("The slot, such as 'kdr:workflowDef' or 'kdr:traitDef'."),
                                        GED.action to this@AssistantTools.stringSchema("'addOrMerge', 'addOrReplace', or 'deleteOrNoOp'."),
                                        GE.data to mapOf("type" to "object", "description" to "The entry, in the slot's form."),
                                        CFEP.expectUpdatedAt to this@AssistantTools.stringSchema("The entry's 'updatedAt' as read, to refuse a stale write."),
                                        CFEP.expectNew to mapOf(
                                            "type" to "boolean",
                                            "description" to "True when the entry must not exist yet."
                                        ),
                                        required = listOf(CFEP.slot, GED.action),
                                    ),
                                ),
                                required = listOf(CFEP.name, CFEP.edits),
                            ),
                        ),
                        CFEP.dryRun to mapOf("type" to "boolean", "description" to "True to check the change without writing it."),
                        required = listOf(CFEP.configs, CFEP.dryRun),
                    ),
                    HttpMethod.POST, CFEP.bundlePatch, setOf(CFEP.configs, CFEP.dryRun, CFEP.reloadField),
                ) { run, input -> this@AssistantTools.patch(run, input) },
                AssistantTool(
                    ATOOL.setIncludedTraits,
                    "Sets which global traits the client includes -- the whole list, replacing the one it has -- as a draft, " +
                            "which a person publishes. What the change does to stored forms is judged when they do.",
                    this@AssistantTools.objectSchema(
                        CLD.includedTraits to this@AssistantTools.arraySchema(
                            "The global trait ids to include, or '${CLD.allGlobal}'.",
                            this@AssistantTools.stringSchema("A trait id.")
                        ),
                        required = listOf(CLD.includedTraits),
                    ),
                    HttpMethod.POST, UADEP.clientTraitsSet, setOf(CLD.includedTraits, CPY.mode),
                ) { run, input ->
                    // Expected as a draft: were it to take effect at once, the endpoint refuses rather than saving it.
                    val body = mapOf(CLD.includedTraits to input[CLD.includedTraits], CPY.mode to EDM.draft)
                    run.call(HttpMethod.POST, UADEP.clientTraitsSet, body = body)
                },
            )
        )
    }

    /**
     * The reads of the client as its sandbox runs it (issue #1762): what the client's own configuration does not show,
     * because it is merged from the platform's, its components', its flavor's, and the client's -- its definition, menu,
     * copy, forms list, reports, images -- and what is pending: its drafts, what publishing them would do to stored
     * forms, and its getting-started steps. Each is the endpoint the client's administrator reads on its page, called as
     * the person's user in the sandbox, so each answers for the sandbox (its id ends ':sandbox'), which runs the client's
     * drafts; the drafts, their impact, and the checklist answer for the client itself.
     *
     * Left out on purpose: the client's users and its stored forms. From the sandbox both answer for the sandbox's own,
     * which would say nothing true of the client's people or data.
     */
    @KdrPrivate
    fun clientReads(): List<AssistantTool> = listOf(
        AssistantTool(
            ATOOL.readClient,
            "Reads the client's definition as its sandbox runs it (its id ends ':sandbox'): its name, time zone, the user " +
                "labels it suggests and what each is for, the roles a visitor may join as, and the traits it includes. A " +
                "workflow's userHasLabel should name one of its labels.",
            objectSchema(), HttpMethod.GET, UADEP.clientDefinition,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientDefinition) },
        AssistantTool(
            ATOOL.readMenu,
            "Reads the home menu as the client's people are offered it, in order: each item's id, its label and the " +
                "condition that shows it -- the platform's and, where the client changed them, the client's -- and the " +
                "named audiences a condition may be. An item the platform ships turned off, such as 'workflows', has the " +
                "condition '#never' until a client turns it on.",
            objectSchema(), HttpMethod.GET, MNU.itemsPath,
        ) { run, _ -> run.call(HttpMethod.GET, MNU.itemsPath, shape = ::menuShaped) },
        AssistantTool(
            ATOOL.readCopy,
            "Reads the copy the client may reword: the words its pages, forms, and mails show, as the client reads them, " +
                "each a key of a namespace of a fragment file, with where the file is shown. A key a client is likely to " +
                "reword also says where a person sees it, in '${CPY.where}': '<page>: <where on it>'. With no input, an " +
                "index: the pages, each with how many such keys it has, and each file, where it is shown, and its " +
                "namespaces with how many keys each holds. With a '${ATOOL.page}', the keys a person sees on that page, " +
                "across files: start there when someone describes words by where they are. With a '${ATOOL.file}', that " +
                "file's keys and their values, narrowed to one '${ATOOL.namespace}' when given.",
            objectSchema(
                ATOOL.page to stringSchema("A page, as the index names it, such as '${CPAGE.home}' or '${CPAGE.mail}'."),
                ATOOL.file to stringSchema("A fragment file's id, such as 'home'."),
                ATOOL.namespace to stringSchema("A namespace of that file, to read only its keys."),
            ),
            HttpMethod.GET, CPY.keysPath,
        ) { run, input ->
            val page = input[ATOOL.page].toOptStr()
            val file = input[ATOOL.file].toOptStr()
            val namespace = input[ATOOL.namespace].toOptStr()
            run.call(HttpMethod.GET, CPY.keysPath, shape = { payload -> copyKeysShaped(payload, file, namespace, page) })
        },
        AssistantTool(
            ATOOL.readOverrides,
            "Reads what the client's own configuration changes: each copy key it rewords, with the platform's value beside " +
                "its own, and each menu or home-card item it changes. What the client has made its own, as opposed to what " +
                "it inherits.",
            objectSchema(), HttpMethod.GET, UADEP.clientOverrides,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientOverrides) },
        AssistantTool(
            ATOOL.readFormsList,
            "Reads the forms list's columns: each column, the trait field its value comes from and how it reads, the " +
                "fields a new column could show, and how the list draws each form's workflows.",
            objectSchema(), HttpMethod.GET, UADEP.clientUsages,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientUsages) },
        AssistantTool(
            ATOOL.listReports,
            "Lists the reports the client may run, the platform's and its own, each with its columns, and whether it may " +
                "make one.",
            objectSchema(), HttpMethod.GET, UADEP.reports,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.reports) },
        AssistantTool(
            ATOOL.readReportPaths,
            "Reads the paths a report column of this client may read, grouped by where they come from -- the form itself, " +
                "and each trait's fields -- each with its kind.",
            objectSchema(), HttpMethod.GET, UADEP.reportPaths,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.reportPaths) },
        AssistantTool(
            ATOOL.readWebAssets,
            "Reads the client's web assets -- its favicon and brand mark -- each with what image it shows now, if any. " +
                "Images are uploaded by a person on the Clients page, never by you.",
            objectSchema(), HttpMethod.GET, UADEP.clientWeb,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientWeb) },
        AssistantTool(
            ATOOL.readFileRefs,
            "Reads the client's file refs: the named images its copy may show as ![alt](ref:<name>), each with the file it " +
                "names. Files are uploaded by a person on the Clients page, never by you.",
            objectSchema(), HttpMethod.GET, UADEP.clientFiles,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientFiles) },
        AssistantTool(
            ATOOL.listDrafts,
            "Lists the client's bundles that have unpublished changes -- drafts, yours or the administrator's -- by name.",
            objectSchema(), HttpMethod.GET, CFEP.drafts,
        ) { run, _ -> run.call(HttpMethod.GET, CFEP.drafts) },
        AssistantTool(
            ATOOL.readImpact,
            "Reads what publishing bundles' drafts together would do to the forms the client stores: each form a narrowed " +
                "schema would no longer accept, and why. You cannot publish; read this to tell the administrator what " +
                "publishing would do before they do.",
            objectSchema(
                CFEP.names to arraySchema("The bundles whose drafts would be published together.", stringSchema("A bundle's name.")),
                required = listOf(CFEP.names),
            ),
            HttpMethod.GET, CFEP.bundleImpact, setOf(CFEP.names),
        ) { run, input ->
            // A GET's input is query text, which takes the names comma-separated rather than as a list.
            val names = (input[CFEP.names] as? List<*>)?.joinToString(",") ?: input[CFEP.names]
            run.call(HttpMethod.GET, CFEP.bundleImpact, mapOf(CFEP.names to names))
        },
        AssistantTool(
            ATOOL.readChecklist,
            "Reads the client's getting-started steps -- its own New form, a workflow of its own, someone invited, a first " +
                "form -- and which are done.",
            objectSchema(), HttpMethod.GET, UADEP.clientChecklist,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientChecklist) },
        AssistantTool(
            ATOOL.readDefinition,
            "Reads where one definition comes from -- the platform, the client's code, or its own configuration, and which " +
                "bundle -- with its authored entry, the workflows that use it, and whether its shared copy may be changed " +
                "here. Slot '${CCT.traitDef}', '${CCT.schemaDef}', or '${CCT.workflowDef}', and the entry's key in it.",
            objectSchema(
                DSV.slot to stringSchema("'${CCT.traitDef}', '${CCT.schemaDef}', or '${CCT.workflowDef}'."),
                DSV.key to stringSchema("The entry's key: a trait's id, a type's name, or a workflow's id."),
                required = listOf(DSV.slot, DSV.key),
            ),
            HttpMethod.GET, DSV.definition, setOf(DSV.slot, DSV.key),
        ) { run, input ->
            run.call(HttpMethod.GET, DSV.definition, mapOf(DSV.slot to input[DSV.slot], DSV.key to input[DSV.key]))
        },
    )

    /**
     * The targeted writes of the client's wording and menu (issue #1763): the copy and menu editors' own saves, so the
     * assistant changes a key or an item by its address rather than patching raw `kdr:fragmentDef` or `kdr:uiBlockDef`
     * entries. Called as the person's user in the sandbox and naming no client, each save lands in the client's
     * configuration as a draft -- a save named for a sandbox goes to its parent, as the editors' do from the sandbox --
     * and says so: each sends [EDM.draft] as what it expects, so one that would take effect at once is refused instead.
     *
     * **No dry run.** A wording or menu change cannot fail in a way a dry run would show: each save is trial-checked as
     * it is made, and refused with its findings. The saves recalculate no form's state, as the editors' do not.
     */
    @KdrPrivate
    fun wordingAndMenuWrites(): List<AssistantTool> {
        val copyKey = arrayOf(
            COV.fileId to stringSchema("The fragment file, as ${ATOOL.readCopy} names it, such as 'home'."),
            COV.namespaceField to stringSchema("The namespace within the file."),
            COV.key to stringSchema("The key within the namespace."),
        )
        val copyKeyFields = listOf(COV.fileId, COV.namespaceField, COV.key)
        fun drafted(input: Map<String, Any?>, fields: List<String>): Map<String, Any?> = given(input, fields) + expectDraft
        return listOf(
            AssistantTool(
                ATOOL.setCopy,
                "Rewords one key of the client's copy, as a draft: its value, as Markdown, which may name values with " +
                    $$"${…}. The client's wording applies wherever the key is shown -- every form, page, and mail of the " +
                    "client's that reads it -- so find the key and what it says now with ${ATOOL.readCopy}, and what the " +
                    "client already rewords with ${ATOOL.readOverrides}. There is no dry run: the save is checked as it " +
                    "is made, and refused, saying why, when it would break something. To change several keys of one file " +
                    "together, use ${ATOOL.applyCopy}.",
                objectSchema(
                    *copyKey,
                    COV.value to stringSchema("The new wording, as Markdown."),
                    required = copyKeyFields + COV.value,
                ),
                HttpMethod.POST, CPY.setPath, (copyKeyFields + COV.value + CPY.mode).toSet(),
            ) { run, input -> run.call(HttpMethod.POST, CPY.setPath, body = drafted(input, copyKeyFields + COV.value)) },
            AssistantTool(
                ATOOL.applyCopy,
                "Rewords, or puts back, several keys of one fragment file of the client's copy in one save, as a draft, so " +
                    "they change together: each change names its namespace and key, and either a new value or " +
                    "'${CPY.reset}': true to put the platform's wording back. A key may be changed once. The client's " +
                    "wording applies wherever a key is shown. There is no dry run: the save is checked as it is made, and " +
                    "refused, saying why, with nothing changed.",
                objectSchema(
                    COV.fileId to stringSchema("The fragment file, as ${ATOOL.readCopy} names it."),
                    CPY.changes to arraySchema(
                        "The keys to change.",
                        objectSchema(
                            COV.namespaceField to stringSchema("The namespace within the file."),
                            COV.key to stringSchema("The key within the namespace."),
                            COV.value to stringSchema("The new wording, as Markdown; absent with '${CPY.reset}'."),
                            CPY.reset to mapOf("type" to "boolean", "description" to "True to put the platform's wording back."),
                            required = listOf(COV.namespaceField, COV.key),
                        ),
                    ),
                    required = listOf(COV.fileId, CPY.changes),
                ),
                HttpMethod.POST, CPY.applyPath, setOf(COV.fileId, CPY.changes, CPY.mode),
            ) { run, input -> run.call(HttpMethod.POST, CPY.applyPath, body = drafted(input, listOf(COV.fileId, CPY.changes))) },
            AssistantTool(
                ATOOL.resetCopy,
                "Puts one key of the client's copy back to the platform's wording (or the client's code's), as a draft, " +
                    "by removing the client's own value for it. Refused when the client has none: ${ATOOL.readOverrides} " +
                    "lists the keys it rewords.",
                objectSchema(*copyKey, required = copyKeyFields),
                HttpMethod.POST, CPY.resetPath, (copyKeyFields + CPY.mode).toSet(),
            ) { run, input -> run.call(HttpMethod.POST, CPY.resetPath, body = drafted(input, copyKeyFields)) },
            AssistantTool(
                ATOOL.setMenuItem,
                "Renames, hides, or shows one item of the client's home menu, as a draft. A menu change changes what the " +
                    "menu offers, not who may reach a page: hiding an item leaves its page open to whoever its access " +
                    "allows, and showing one opens it to nobody new. '${MNU.visibility}' '${MNU.hide}' withdraws the " +
                    "item; '${MNU.show}' offers it under '${MNU.condition}', which must be the condition of one of the " +
                    "audiences ${ATOOL.readMenu} lists. Give a '${MNU.label}', a '${MNU.visibility}', or both. There is no " +
                    "dry run: the save is checked as it is made.",
                objectSchema(
                    COV.itemId to stringSchema("The item's id, as ${ATOOL.readMenu} lists it."),
                    MNU.label to stringSchema("The new label; absent leaves it."),
                    MNU.visibility to stringSchema("'${MNU.hide}' or '${MNU.show}'; absent leaves it."),
                    MNU.condition to stringSchema("For '${MNU.show}': an audience's condition, from ${ATOOL.readMenu}."),
                    required = listOf(COV.itemId),
                ),
                HttpMethod.POST, MNU.setPath, setOf(COV.itemId, MNU.label, MNU.visibility, MNU.condition, CPY.mode),
            ) { run, input ->
                run.call(HttpMethod.POST, MNU.setPath, body = drafted(input, listOf(COV.itemId, MNU.label, MNU.visibility, MNU.condition)))
            },
            AssistantTool(
                ATOOL.resetMenuItem,
                "Puts one item of the client's home menu back as the platform (or the client's code) ships it, as a " +
                    "draft, by removing the client's changes to it. Refused when the client has changed nothing of it.",
                objectSchema(COV.itemId to stringSchema("The item's id, as ${ATOOL.readMenu} lists it."), required = listOf(COV.itemId)),
                HttpMethod.POST, MNU.resetPath, setOf(COV.itemId, CPY.mode),
            ) { run, input -> run.call(HttpMethod.POST, MNU.resetPath, body = drafted(input, listOf(COV.itemId))) },
        )
    }

    /**
     * The targeted writes of the client's definition and its forms list (issue #1764): the definition and listing
     * editors' own saves, drafts as the wording and menu writes are ([wordingAndMenuWrites]). A definition edit and a
     * column's save or removal can be refused -- a label a workflow still names, removed; a column whose value does
     * not resolve -- so each takes a dry run, and is written only after the same change passed one ([dryRunFirst]),
     * as `patch_config` is. The layout is one of two values, and has none.
     *
     * The definition tool offers the client's own fields. What only the platform's operator sets -- what the client is
     * for, whose it is, where it runs, its domains -- is passed to the endpoint as given, which refuses it from the
     * person's user, and the description says so.
     */
    @KdrPrivate
    fun definitionAndFormsListWrites(): List<AssistantTool> {
        val dryRun = CFEP.dryRun to mapOf("type" to "boolean", "description" to "True to check the change without saving it.")
        val definitionFields = ClientPresentationFields.names.associateWith { name ->
            val description = ClientPresentationFields.descriptions[name].orEmpty()
            when (ClientPresentationFields.kind(name)) {
                ClientPresentationFields.Kind.labels -> arraySchema(description, stringSchema("A label."))
                ClientPresentationFields.Kind.labelNotes -> mapOf(
                    "type" to "object", "description" to description, "additionalProperties" to mapOf("type" to "string"),
                )
                else -> stringSchema(description)
            }
        }
        val column = objectSchema(
            CCT.traitId to stringSchema("The trait the column takes its value from, one ${ATOOL.readFormsList} lists."),
            CCT.label to stringSchema("The column's heading."),
            CCT.display to stringSchema($$"The column's value: a field of the trait as ${field}, or an expression."),
            CCT.kind to stringSchema("How the value is compared and sorted, such as 'number' or 'date'; absent for text."),
            CCT.presentation to stringSchema($$"How the value reads in the list, an expression over ${value}; absent as it is."),
            required = listOf(CCT.traitId, CCT.label, CCT.display),
        )
        return listOf(
            AssistantTool(
                ATOOL.setClientDefinition,
                "Changes the client's definition, as a draft: ${ClientPresentationFields.names.joinToString(", ")}. Only " +
                    "the fields given change; a blank clears an optional one, and the name may not be blank. Read it " +
                    "first with ${ATOOL.readClient}. A label a workflow names must stay among the user labels: removing " +
                    "one is refused. What only the platform's operator sets -- what the client is for, whose it is, where " +
                    "it runs, its domains -- is refused here. **Dry run first**: the same change with dryRun true, then " +
                    "with dryRun false in a later step.",
                objectSchema(*definitionFields.toList().toTypedArray(), dryRun, required = listOf(CFEP.dryRun)),
                HttpMethod.POST, UADEP.clientDefinitionSet,
                (ClientPresentationFields.namesFor(platform = true) + CFEP.dryRun + CPY.mode).toSet(),
            ) { run, input ->
                val change = given(input, ClientPresentationFields.namesFor(platform = true))
                dryRunFirst(run, ATOOL.setClientDefinition, UADEP.clientDefinitionSet, change, input[CFEP.dryRun] == true)
            },
            AssistantTool(
                ATOOL.saveFormsListColumn,
                "Adds a column to the client's forms list, or changes one, as a draft. A column is known by its trait and " +
                    "its heading: to change one, give the heading it has now as '${CUS.previousLabel}'. Its trait is " +
                    "fixed once saved. A column whose value does not resolve on its trait is refused. Read the columns " +
                    "and the fields a column may show with ${ATOOL.readFormsList}. **Dry run first**, then save in a " +
                    "later step.",
                objectSchema(
                    CUS.column to column,
                    CUS.previousLabel to stringSchema("The heading of the column this changes; absent for a new column."),
                    dryRun,
                    required = listOf(CUS.column, CFEP.dryRun),
                ),
                HttpMethod.POST, UADEP.clientUsageSave, setOf(CUS.column, CUS.previousLabel, CFEP.dryRun, CPY.mode),
            ) { run, input ->
                val change = given(input, listOf(CUS.column, CUS.previousLabel))
                dryRunFirst(run, ATOOL.saveFormsListColumn, UADEP.clientUsageSave, change, input[CFEP.dryRun] == true)
            },
            AssistantTool(
                ATOOL.removeFormsListColumn,
                "Removes a column from the client's forms list, as a draft. A client showing the platform's default " +
                    "columns makes the others its own; removing its last column of its own shows the defaults again. " +
                    "**Dry run first**, then remove in a later step.",
                objectSchema(
                    CCT.traitId to stringSchema("The trait the column takes its value from."),
                    CCT.label to stringSchema("The column's heading."),
                    dryRun,
                    required = listOf(CCT.traitId, CCT.label, CFEP.dryRun),
                ),
                HttpMethod.POST, UADEP.clientUsageRemove, setOf(CCT.traitId, CCT.label, CFEP.dryRun, CPY.mode),
            ) { run, input ->
                val change = given(input, listOf(CCT.traitId, CCT.label))
                dryRunFirst(run, ATOOL.removeFormsListColumn, UADEP.clientUsageRemove, change, input[CFEP.dryRun] == true)
            },
            AssistantTool(
                ATOOL.setFormsListLayout,
                "Sets how the client's forms list draws each form's workflows, as a draft: '${WorkflowLayout.split.name}', " +
                    "a Your turn column beside the Workflows cell, or '${WorkflowLayout.combined.name}', one cell. There " +
                    "is no dry run: it is one of two values.",
                objectSchema(
                    CUS.workflowLayout to stringSchema("'${WorkflowLayout.split.name}' or '${WorkflowLayout.combined.name}'."),
                    required = listOf(CUS.workflowLayout),
                ),
                HttpMethod.POST, UADEP.clientUsageLayout, setOf(CUS.workflowLayout, CPY.mode),
            ) { run, input ->
                val body = mapOf(CUS.workflowLayout to input[CUS.workflowLayout], expectDraft)
                run.call(HttpMethod.POST, UADEP.clientUsageLayout, body = body)
            },
        )
    }

    /**
     * The targeted writes of Design View's edits (issue #1765): a field's copy, a heading and its introduction, and a
     * workflow's labels, for one workflow or shared by every workflow on the client, and which fields a workflow's form
     * shows. Each changes exactly what it names and keeps every key it does not, where `patch_config` would replace an
     * entry whole -- so the assistant need not restate what it means to keep. Called as the person's user in the sandbox,
     * each saves a draft, as Design View's saves from the sandbox do.
     *
     * **Each is judged against a basis**, as the page sends one: an edit of a workflow sends the `basedOn` that
     * `read_definition` gives for it, and a shared edit the `sharedBasedOn` it gives for the trait or type. A definition
     * changed since is refused (409), so the model reads it again rather than overwriting a change it has not seen.
     *
     * **A dry run where an edit can reach stored data**: a change of a field's choices -- a removed one is judged against
     * the forms that hold it -- and of the fields a form shows. Those are written only after the same change passed one
     * ([dryRunFirst]); a wording-only edit has none.
     */
    @KdrPrivate
    fun designWrites(): List<AssistantTool> {
        val basedOn = DSV.basedOn to stringSchema(
            "The workflow's stamp, from ${ATOOL.readDefinition} of slot '${CCT.workflowDef}' and the workflow's id: an edit " +
                "of a workflow changed since is refused, and is read again.",
        )
        val sharedBasedOn = DSV.sharedBasedOn to stringSchema(
            "The definition's stamp, from ${ATOOL.readDefinition} of the trait (or the type): a shared edit of one changed " +
                "since is refused, and is read again.",
        )
        val workflowId = DSV.workflowId to stringSchema("The workflow, by its id.")
        val typeName = DSV.typeName to stringSchema(
            "The type whose field it is: a trait's data type, as ${ATOOL.viewWorkflow} names it in a trait's schemaRef " +
                    $$"(after '#/$defs/').",
        )
        val field = DSV.field to stringSchema("The field, by its key in the type.")
        val entry = DSV.entry to mapOf(
            "type" to "object",
            "description" to "The field's copy: 'label', 'description' (the line under it), 'hint', and the rest of a layout " +
                "entry, as the client-configuration reference's field layouts give them.",
        )
        val label = DSV.label to stringSchema("The heading; absent or blank to go back to the shared one.")
        val description = DSV.description to stringSchema("The introduction under the heading, Markdown; absent or blank to go back.")
        val dryRun = CFEP.dryRun to mapOf("type" to "boolean", "description" to "True to check the change without saving it.")
        // A wording-only edit: the fields given, sent to the path as they are, with no dry run -- unless [guard] refuses
        // the input first, with what to send instead.
        fun wording(
            name: String,
            description: String,
            schema: Map<String, Any?>,
            path: String,
            fields: List<String>,
            guard: (Map<String, Any?>) -> String? = { null },
        ) = AssistantTool(name, description, schema, HttpMethod.POST, path, fields.toSet()) { run, input ->
            guard(input)?.let { ToolResult(it, isError = true) } ?: run.call(HttpMethod.POST, path, body = given(input, fields))
        }
        val sharedFieldEdit = listOf(DSV.typeName, DSV.field, DSV.entry, DSV.options, DSV.sharedBasedOn)
        val shownFieldsEdit = listOf(DSV.workflowId, DSV.typeName, DSV.fields, DSV.basedOn)
        return listOf(
            wording(
                ATOOL.setFieldCopy,
                "Sets a field's copy for one workflow's form, as a draft: its label, the line under it, its hint, and the rest " +
                    "of its layout entry, over the copy every workflow shares. To remove the workflow's own copy, so the " +
                    "shared copy shows again, give '${ATOOL.remove}': true and no '${DSV.entry}'. Wording only, so no dry run.",
                objectSchema(
                    workflowId, typeName, field, entry, basedOn,
                    ATOOL.remove to mapOf("type" to "boolean", "description" to "True to remove the workflow's own copy."),
                    required = listOf(DSV.workflowId, DSV.typeName, DSV.field, DSV.basedOn),
                ),
                DSV.layoutEntryEdit, listOf(DSV.workflowId, DSV.typeName, DSV.field, DSV.entry, DSV.basedOn),
                // An absent entry removes the copy at the endpoint, so the tool asks for it said, never by omission.
                guard = { input ->
                    when {
                        input[DSV.entry] != null && input[ATOOL.remove] == true ->
                            "Give '${DSV.entry}' to set the copy, or '${ATOOL.remove}': true to remove it, not both."
                        input[DSV.entry] == null && input[ATOOL.remove] != true ->
                            "Give the copy as '${DSV.entry}', or '${ATOOL.remove}': true to remove the workflow's own copy."
                        else -> null
                    }
                },
            ),
            AssistantTool(
                ATOOL.setSharedField,
                "Sets a field's copy, its choices, or both, in the trait the client declares, as a draft, for every workflow " +
                    "on the client. '${DSV.options}' is the choices as they should stand, each {value, label}: relabeled, " +
                    "added, or removed -- a value left out is removed. **A change of choices is dry-run first**: a removed " +
                    "choice is judged against the forms that hold it, and the dry run says which would no longer be " +
                    "valid once published. Copy alone needs none.",
                objectSchema(
                    typeName, field, entry,
                    DSV.options to arraySchema(
                        "The field's choices as they should stand.",
                        objectSchema(
                            SCH.value to stringSchema("What the form stores for the choice; a value left out is removed."),
                            SCH.label to stringSchema("What the form shows for it."),
                            required = listOf(SCH.value, SCH.label),
                        ),
                    ),
                    sharedBasedOn, dryRun,
                    required = listOf(DSV.typeName, DSV.field, DSV.sharedBasedOn),
                ),
                HttpMethod.POST, DSV.sharedFieldEdit, (sharedFieldEdit + CFEP.dryRun).toSet(),
            ) { run, input ->
                val change = given(input, sharedFieldEdit)
                val dry = input[CFEP.dryRun] == true
                if (input[DSV.options] == null) {
                    // Copy alone needs no dry run, but one asked for is one: checked, nothing saved.
                    run.call(HttpMethod.POST, DSV.sharedFieldEdit, body = if (dry) change + (CFEP.dryRun to true) else change)
                } else {
                    dryRunFirst(run, ATOOL.setSharedField, DSV.sharedFieldEdit, change, dry, alsoSend = emptyMap())
                }
            },
            wording(
                ATOOL.setHeading,
                "Sets the heading one workflow's form shows over a trait, and the introduction under it, as a draft, over the " +
                    "shared ones; either absent or blank goes back to the shared one. Wording only, so no dry run.",
                objectSchema(
                    workflowId, typeName, label, description, basedOn,
                    required = listOf(DSV.workflowId, DSV.typeName, DSV.basedOn),
                ),
                DSV.headingEdit, listOf(DSV.workflowId, DSV.typeName, DSV.label, DSV.description, DSV.basedOn),
            ),
            wording(
                ATOOL.setSharedHeading,
                "Sets a trait's heading, and the introduction under it, in the trait the client declares, as a draft, for " +
                    "every workflow on the client; either absent or blank removes it. Wording only, so no dry run.",
                objectSchema(
                    typeName,
                    DSV.label to stringSchema("The heading; absent or blank to remove it, so forms show the type's own title."),
                    DSV.description to stringSchema("The introduction under it, Markdown; absent or blank to remove it."),
                    sharedBasedOn,
                    required = listOf(DSV.typeName, DSV.sharedBasedOn),
                ),
                DSV.sharedHeadingEdit, listOf(DSV.typeName, DSV.label, DSV.description, DSV.sharedBasedOn),
            ),
            wording(
                ATOOL.setWorkflowLabel,
                "Sets a label a workflow owns, as a draft: its own (its page's title) with no '${DSV.taskId}', a task's with " +
                    "one, or a save's -- its button -- with a '${DSV.saveId}' too. A label is a template, like any workflow " +
                    "label. Wording only, so no dry run.",
                objectSchema(
                    workflowId,
                    DSV.taskId to stringSchema("The task whose label changes, or whose save's does; absent for the workflow's own."),
                    DSV.saveId to stringSchema("The save, within the task, whose label changes."),
                    DSV.label to stringSchema("The label; blank clears the workflow's own, which a task's and a save's cannot be."),
                    basedOn,
                    required = listOf(DSV.workflowId, DSV.basedOn),
                ),
                DSV.labelEdit, listOf(DSV.workflowId, DSV.taskId, DSV.saveId, DSV.label, DSV.basedOn),
            ),
            AssistantTool(
                ATOOL.setShownFields,
                "Sets which fields of a trait one workflow's form shows, in order, as a draft -- and so which its save writes; " +
                    "without '${DSV.fields}', it stops choosing, and the form shows them all. A field the type requires " +
                    "may not be left out. **Dry run first**, then save in a later step.",
                objectSchema(
                    workflowId, typeName,
                    DSV.fields to arraySchema("The fields the form shows, in order.", stringSchema("A field's key.")),
                    basedOn, dryRun,
                    required = listOf(DSV.workflowId, DSV.typeName, DSV.basedOn, CFEP.dryRun),
                ),
                HttpMethod.POST, DSV.shownFieldsEdit, (shownFieldsEdit + CFEP.dryRun).toSet(),
            ) { run, input ->
                val change = given(input, shownFieldsEdit)
                dryRunFirst(run, ATOOL.setShownFields, DSV.shownFieldsEdit, change, input[CFEP.dryRun] == true, alsoSend = emptyMap())
            },
        )
    }

    /**
     * A write held to the dry-run rule (issues #1526, #1764): with [dryRun] it is checked and nothing written, and a
     * write is refused unless exactly the same [change], as [name] sends it, passed a dry run in an earlier step of this
     * turn -- so the model has read what it would do first. [alsoSend] rides both calls (a targeted save's expected
     * draft), [alsoWrite] the write alone (`patch_config`'s reload), and [dryShape] reshapes a dry run's answer.
     */
    @KdrPrivate
    fun dryRunFirst(
        run: ToolRun,
        name: String,
        path: String,
        change: Map<String, Any?>,
        dryRun: Boolean,
        alsoSend: Map<String, Any?> = mapOf(expectDraft),
        alsoWrite: Map<String, Any?> = emptyMap(),
        dryShape: ((Any?) -> Any?)? = null,
    ): ToolResult {
        val key = name + canonical(change, 0).toJsonStr(compact = true)
        val body = change + alsoSend
        if (dryRun) {
            val result = run.call(HttpMethod.POST, path, body = body + (CFEP.dryRun to true), shape = dryShape)
            if (!result.isError) run.stepDryRuns.add(key)
            return result
        }
        if (key !in run.dryRuns) {
            return ToolResult(
                "Not written: dry run this change first (dryRun true), exactly as it will be written, read the answer, " +
                    "and write it in a later step.",
                isError = true,
            )
        }
        return run.call(HttpMethod.POST, path, body = body + (CFEP.dryRun to false) + alsoWrite)
    }

    /**
     * The [fields] of a tool's [input] that it gives, the change a targeted save sends: the one way every targeted
     * write picks what it passes on (issues #1763-#1765). A field given as null is passed as null, as the endpoint reads it.
     */
    @KdrPrivate
    fun given(input: Map<String, Any?>, fields: List<String>): Map<String, Any?> =
        fields.filter { input.containsKey(it) }.associateWith { input[it] }

    /** What a targeted save that takes one sends to say it expects a draft ([CPY.mode]), so a live one is refused. */
    @KdrPrivate
    val expectDraft: Pair<String, Any?> = CPY.mode to EDM.draft

    /**
     * `read_menu`'s answer (issue #1762) from the menu items [payload]: each item repeats the audiences a condition may be,
     * the same for every item, which runs a menu past what one answer holds -- so they are said once, beside the items --
     * and an item's platform label, condition, and audience are kept only where the client's differ from them.
     */
    @KdrPrivate
    fun menuShaped(payload: Any?): Any {
        val items = (payload as? Map<*, *>)?.get(EP.items).toJsonListOfMaps()
        val pairs = listOf(COV.baseLabel to MNU.label, MNU.baseCondition to MNU.condition, MNU.baseAudience to MNU.audience)
        return linkedMapOf(
            MNU.audiences to items.flatMap { it[MNU.audiences].toJsonListOfMaps() }.distinct(),
            EP.items to items.map { item ->
                item.filterKeys { key -> key != MNU.audiences && pairs.none { (base, own) -> key == base && item[base] == item[own] } }
            },
        )
    }

    /**
     * `read_copy`'s answer (issue #1762) from the copy keys [payload]: every key is more than one answer holds, so with no
     * [file] or [page] an index -- the pages with how many located keys each has (issue #1799), and each file, where it
     * is shown, and its namespaces with their key counts. With a [page], the keys whose note names it, across files;
     * with a [file], its keys, narrowed to [namespace] when given. A page, file, or namespace with no keys says so,
     * naming the ones there are.
     *
     * Theme: #CUSTX.
     */
    @KdrPrivate
    fun copyKeysShaped(payload: Any?, file: String?, namespace: String?, page: String? = null): Any {
        val keys = (payload as? Map<*, *>)?.get(EP.items).toJsonListOfMaps()
        val pageOf = { key: Map<String, Any?> -> key[CPY.where].toOptStr()?.let { FragmentNotes.pageOf(it) } }
        if (page != null) {
            val onPage = keys.filter { pageOf(it).equals(page, ignoreCase = true) && (file == null || it[COV.fileId] == file) }
            if (onPage.isEmpty()) {
                return mapOf(
                    CPYIDX.none to "No copy keys say they are on the page '$page'. The pages are: " +
                        "${CPAGE.pages.joinToString(", ")}; a key on none of them can be found by its file.",
                )
            }
            return onPage
        }
        if (file == null) {
            return linkedMapOf(
                CPYIDX.pages to CPAGE.pages.associateWith { p -> keys.count { pageOf(it) == p } }.filterValues { it > 0 },
                CPYIDX.files to keys.groupBy { it[COV.fileId].toOptStr().orEmpty() }.map { (fileId, inFile) ->
                    linkedMapOf(
                        COV.fileId to fileId,
                        COV.shownOn to inFile.firstNotNullOfOrNull { it[COV.shownOn] },
                        CPYIDX.namespaces to inFile.groupingBy { it[COV.namespaceField].toOptStr().orEmpty() }.eachCount(),
                    )
                },
            )
        }
        val chosen = keys.filter { it[COV.fileId] == file && (namespace == null || it[COV.namespaceField] == namespace) }
        if (chosen.isEmpty()) {
            val files = keys.mapNotNull { it[COV.fileId].toOptStr() }.distinct()
            return mapOf(
                CPYIDX.none to "No copy keys in file '$file'" + (namespace?.let { ", namespace '$it'" } ?: "") +
                    ". The files are: ${files.joinToString(", ")}.",
            )
        }
        return chosen.map { k -> k.filterKeys { it != COV.fileId } }
    }

    /**
     * `patch_config`: a dry run is recorded by what it was given, and a write must match one the model has seen the
     * answer to. A write asks for a reload ([CFEP.reloadField]), as the bundle editor's does (issue #1770): a patch made
     * from a sandbox reloads its parent, and with it the sandbox, so `view_workflow` shows the draft at once either way,
     * but only an asked reload brings the forms the sandbox already holds up to date (issue #1741's
     * `autoRecalculateFormStates`) -- without it, a workflow drafted for existing forms is offered to none of them until
     * someone recalculates them, or the nightly run does. The parent is then reloaded twice, as for the bundle editor; the
     * answer says what the recalculation did under `formStatesAuto`. A dry run asks for none: it stores nothing. A dry
     * run's answer leaves out the bundles as they would be -- the model sent them -- so its impact, which is what it is
     * for, is never what a long answer cuts. A write's answer keeps them: they say what was written, and each entry's
     * new `updatedAt`, for the next edit's `expectUpdatedAt`.
     */
    @KdrPrivate
    fun patch(run: ToolRun, input: Map<String, Any?>): ToolResult {
        val withoutBundles: (Any?) -> Any? = { payload -> (payload as? Map<*, *>)?.filterKeys { it != CFEP.configs } ?: payload }
        return dryRunFirst(
            run, ATOOL.patchConfig, CFEP.bundlePatch, mapOf(CFEP.configs to input[CFEP.configs]), input[CFEP.dryRun] == true,
            alsoSend = emptyMap(), alsoWrite = mapOf(CFEP.reloadField to true), dryShape = withoutBundles,
        )
    }

    /** [value] with every map's keys in order, so two inputs differing only in key order compare equal. */
    @KdrPrivate
    fun canonical(value: Any?, depth: Int): Any? {
        if (depth > TOOL.maxDepth) throw KdrException.mkInput("The input nests deeper than ${TOOL.maxDepth}.")
        return when (value) {
            is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
                .associateTo(LinkedHashMap()) { it.key.toString() to canonical(it.value, depth + 1) }
            is List<*> -> value.map { canonical(it, depth + 1) }
            else -> value
        }
    }

    @KdrPrivate
    fun objectSchema(vararg properties: Pair<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> = buildMap {
        put("type", "object")
        put("properties", linkedMapOf(*properties))
        if (required.isNotEmpty()) put("required", required)
    }

    @KdrPrivate
    fun stringSchema(description: String): Map<String, Any?> = mapOf("type" to "string", "description" to description)

    @KdrPrivate
    fun arraySchema(description: String, items: Map<String, Any?>): Map<String, Any?> =
        mapOf("type" to "array", "description" to description, "items" to items)
}
