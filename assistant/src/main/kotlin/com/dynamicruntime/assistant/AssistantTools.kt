package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.MDOC
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.PORT
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.http.request.InProcessCaller
import com.dynamicruntime.common.http.request.InProcessPrincipal
import com.dynamicruntime.common.user.InProcessScope
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.confdemo.ConfDemo

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
 * The assistant's tools (issue #1526): reads of the client's configuration, workflows, traits, and cfacts, and two
 * writes -- `patch_config`, a dry run before every write, and `set_included_traits` -- that can only make drafts: the
 * tools act as the person's own user in the client's sandbox, whose configuration edits become drafts on the parent.
 *
 * **What is left out is the point.** Publishing, reverting, deleting a config, `publishedOnly`, `bundle/write` (which
 * deletes what it is not given), switching the sandbox, `workflows/params` (in force at once), users, keys, sessions,
 * form data, and every `admin`, `operator`, and `fixture` endpoint: none is a tool, so the seam refuses each.
 */
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

    /** A document `read_doc` may read: a reference ([TOOL.docs]) or a page of the demo client's. */
    fun readableDoc(cxt: KdrCxt, docId: String): String? {
        if (docId !in TOOL.docs && !docId.startsWith(ConfDemo.docIdPrefix)) return null
        val declared = MarkdownDocService.declaredDoc(cxt, docId) ?: return null
        return declared.generatedText ?: ContentResources.readText(MarkdownDocService.resourceDir, docId)
    }

    /** The ids [readableDoc] reads on this node. */
    @Suppress("UNCHECKED_CAST")
    fun readableDocIds(cxt: KdrCxt): List<String> = (cxt.instanceConfig.get(MDOC.registryKey) as? List<MarkdownDocDef>).orEmpty()
        .map { it.docId }.filter { it in TOOL.docs || it.startsWith(ConfDemo.docIdPrefix) }

    @KdrPrivate
    fun build(): List<AssistantTool> = listOf(
        AssistantTool(
            ATOOL.readDoc,
            "Reads a reference document as Markdown: '${HDOC.clientConfiguration}' (every config slot and its fields), " +
                "'${HDOC.schemaDialect}' (how a trait's data type is written), '${HDOC.validationMessages}' (the codes " +
                "a refused value is reported with, and how a schema gives one a message), '${HDOC.expressions}' (the " +
                $$"${…} language of copy, columns, layouts, and computed values), '$${HDOC.facts}' (the facts a menu entry, " +
                "a field's visibility, a save rule, or an eligibility test is written over), '${HDOC.copy}' (the Markdown copy " +
                "is written in, its roles, and the fragment file format), '${HDOC.reportPaths}' (how a report column's path is " +
                "written, and every attribute it can end in), '${HDOC.formLayouts}' (what a type's g-layout may hold, and " +
                "how a client's layout changes the shared one), or a page of the demo client's " +
                "('${ConfDemo.docIdPrefix}README' lists them), which shows a whole client's configuration worked through.",
            objectSchema(ATOOL.doc to stringSchema("The document's id."), required = listOf(ATOOL.doc)),
            method = null, path = null,
        ) { run, input ->
            val docId = input[ATOOL.doc].toOptStr().orEmpty()
            readableDoc(run.cxt, docId)?.let {
                ToolResult(ToolRun.capped(it, TOOL.maxDocChars, "The rest of this document cannot be read here."), isError = false)
            }
                ?: ToolResult("No document '$docId'. Readable here: ${readableDocIds(run.cxt).joinToString(", ")}.", isError = true)
        },
        AssistantTool(
            ATOOL.listConfigs,
            "Lists the client's configuration bundles: each one's name, version, whether its latest revision is published, " +
                "and its slot and entry counts.",
            objectSchema(), HttpMethod.GET, CFEP.bundles,
        ) { run, _ -> run.call(HttpMethod.GET, CFEP.bundles) },
        AssistantTool(
            ATOOL.readConfig,
            "Reads one configuration bundle whole, as the sandbox runs it (drafts included), in portable form: the client's " +
                "own namespace is written 'client.@self'. Data in that form may be patched back as it is.",
            objectSchema(CFEP.name to stringSchema("The bundle's name."), required = listOf(CFEP.name)),
            HttpMethod.GET, CFEP.bundle, setOf(CFEP.name, PORT.portableField),
        ) { run, input -> run.call(HttpMethod.GET, CFEP.bundle, mapOf(CFEP.name to input[CFEP.name], PORT.portableField to true)) },
        AssistantTool(
            ATOOL.readConfigEntries,
            "Reads one bundle's entries, each with its slot, data (portable form), and 'updatedAt'. Pass an entry's " +
                "'updatedAt' as 'expectUpdatedAt' when patching it, so a change someone else made since is not overwritten.",
            objectSchema(CFEP.name to stringSchema("The bundle's name."), required = listOf(CFEP.name)),
            HttpMethod.GET, CFEP.slotEntries, setOf(CFEP.name, PORT.portableField),
        ) { run, input -> run.call(HttpMethod.GET, CFEP.slotEntries, mapOf(CFEP.name to input[CFEP.name], PORT.portableField to true)) },
        AssistantTool(
            ATOOL.listWorkflows,
            "Lists the client's workflows as its sandbox runs them, drafts included, with the templates a new one may start " +
                "from and why any is refused (a plan's limit, say).",
            objectSchema(), HttpMethod.GET, UADEP.clientWorkflows,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientWorkflows) },
        AssistantTool(
            ATOOL.listTraits,
            "Lists the global traits a client may include, with their data types, the client's own traits, and its plan's " +
                "quotas for schemas and attributes.",
            objectSchema(), HttpMethod.GET, UADEP.clientTraits,
        ) { run, _ -> run.call(HttpMethod.GET, UADEP.clientTraits) },
        AssistantTool(
            ATOOL.readCfacts,
            "Reads the cfacts reference as Markdown: every cfact a workflow's conditions may name, what makes it true, and " +
                "whether it is true for the caller now.",
            objectSchema(), HttpMethod.GET, CFD.cfactsPath,
        ) { run, _ -> run.call(HttpMethod.GET, CFD.cfactsPath) },
        AssistantTool(
            ATOOL.viewWorkflow,
            "Shows a workflow as the sandbox runs it -- its tasks, the traits each shows, and who may act -- so a draft can " +
                "be checked to have loaded. With no workflowId, the client's creation workflow.",
            objectSchema(GDF.workflowId to stringSchema("The workflow's id.")),
            HttpMethod.GET, GEP.workflowView, setOf(GDF.workflowId),
        ) { run, input ->
            run.call(HttpMethod.GET, GEP.workflowView, input[GDF.workflowId].toOptStr()?.let { mapOf(GDF.workflowId to it) })
        },
        AssistantTool(
            ATOOL.patchConfig,
            "Changes the client's configuration, as a draft: nothing is live until a person publishes it. Each config " +
                "names a bundle (a new name makes one) and its edits; each edit names a slot, an action ('addOrMerge', " +
                "'addOrReplace', or 'deleteOrNoOp'), and the entry's data, in portable form. **Dry run first**: with " +
                "dryRun true nothing is written, and the answer is what the change would affect. A write answers with " +
                "the bundles as written, each entry's new 'updatedAt' among them. A write (dryRun false) is refused " +
                "unless the same configs passed a dry run in an earlier step of this turn: dry run, read the answer, " +
                "then write.",
            objectSchema(
                CFEP.configs to arraySchema(
                    "The bundles to change.",
                    objectSchema(
                        CFEP.name to stringSchema("The bundle's name."),
                        CFEP.edits to arraySchema(
                            "Its edits, in order.",
                            objectSchema(
                                CFEP.slot to stringSchema("The slot, such as 'kdr:workflowDef' or 'kdr:traitDef'."),
                                GED.action to stringSchema("'addOrMerge', 'addOrReplace', or 'deleteOrNoOp'."),
                                GE.data to mapOf("type" to "object", "description" to "The entry, in the slot's form."),
                                CFEP.expectUpdatedAt to stringSchema("The entry's 'updatedAt' as read, to refuse a stale write."),
                                CFEP.expectNew to mapOf("type" to "boolean", "description" to "True when the entry must not exist yet."),
                                required = listOf(CFEP.slot, GED.action),
                            ),
                        ),
                        required = listOf(CFEP.name, CFEP.edits),
                    ),
                ),
                CFEP.dryRun to mapOf("type" to "boolean", "description" to "True to check the change without writing it."),
                required = listOf(CFEP.configs, CFEP.dryRun),
            ),
            HttpMethod.POST, CFEP.bundlePatch, setOf(CFEP.configs, CFEP.dryRun),
        ) { run, input -> patch(run, input) },
        AssistantTool(
            ATOOL.setIncludedTraits,
            "Sets which global traits the client includes -- the whole list, replacing the one it has -- as a draft, " +
                "which a person publishes. What the change does to stored forms is judged when they do.",
            objectSchema(
                CLD.includedTraits to arraySchema("The global trait ids to include, or '${CLD.allGlobal}'.", stringSchema("A trait id.")),
                required = listOf(CLD.includedTraits),
            ),
            HttpMethod.POST, UADEP.clientTraitsSet, setOf(CLD.includedTraits, CPY.mode),
        ) { run, input ->
            // Expected as a draft: were it to take effect at once, the endpoint refuses rather than saving it.
            val body = mapOf(CLD.includedTraits to input[CLD.includedTraits], CPY.mode to EDM.draft)
            run.call(HttpMethod.POST, UADEP.clientTraitsSet, body = body)
        },
    )

    /**
     * `patch_config`: a dry run is recorded by what it was given, and a write must match one the model has seen the
     * answer to. No reload is asked for: a patch made from a sandbox reloads its parent, and with it the sandbox, so
     * `view_workflow` shows the draft at once. A dry run's answer leaves out the bundles as they would be -- the model
     * sent them -- so its impact, which is what it is for, is never what a long answer cuts. A write's answer keeps
     * them: they say what was written, and each entry's new `updatedAt`, for the next edit's `expectUpdatedAt`.
     */
    @KdrPrivate
    fun patch(run: ToolRun, input: Map<String, Any?>): ToolResult {
        val configs = input[CFEP.configs]
        val key = canonical(configs, 0).toJsonStr(compact = true)
        val withoutBundles: (Any?) -> Any? = { payload -> (payload as? Map<*, *>)?.filterKeys { it != CFEP.configs } ?: payload }
        if (input[CFEP.dryRun] == true) {
            val body = mapOf(CFEP.configs to configs, CFEP.dryRun to true)
            val result = run.call(HttpMethod.POST, CFEP.bundlePatch, body = body, shape = withoutBundles)
            if (!result.isError) run.stepDryRuns.add(key)
            return result
        }
        if (key !in run.dryRuns) {
            return ToolResult(
                "Not written: dry run these configs first (dryRun true), exactly as they will be written, read the answer, " +
                    "and write them in a later step.",
                isError = true,
            )
        }
        return run.call(HttpMethod.POST, CFEP.bundlePatch, body = mapOf(CFEP.configs to configs, CFEP.dryRun to false))
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
