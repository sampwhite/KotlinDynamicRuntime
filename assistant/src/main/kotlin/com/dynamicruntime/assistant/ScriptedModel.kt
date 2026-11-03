package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.util.jsonMapOrNull
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * A model that answers from a script (issue #1525): each call takes the next of [steps], a function of the request
 * that returns the response, so a test can answer what it was asked. The requests it was sent are kept in [requests]
 * for the test to read. Running out of steps is a test's mistake, and throws.
 *
 * A test hands one in through the instance config under [AssistantService.modelConfigKey], as a test hands the file
 * store a `FakeS3Api`. With `KDR_ASSISTANT_PROVIDER=fake` a test instance runs [demo] instead.
 */
class ScriptedModel(steps: List<(ModelRequest) -> ModelResponse>, override val label: String = "scripted") : AssistantModel {
    private val remaining = ArrayDeque(steps)

    /** The requests made so far, in order. */
    val requests: MutableList<ModelRequest> = mutableListOf()

    /** Drops any steps left: a case that failed part way leaves none for the next. */
    fun clear() = synchronized(this) { remaining.clear() }

    /** Queues [steps] after those left: a test sharing one model across cases scripts each case as it starts. */
    fun add(vararg steps: (ModelRequest) -> ModelResponse) = synchronized(this) { remaining.addAll(steps) }

    override fun respond(cxt: KdrCxt, request: ModelRequest): ModelResponse {
        // The step runs outside the lock, so one that waits on the test does not hold up another turn's.
        val step = synchronized(this) {
            requests.add(request)
            remaining.removeFirstOrNull() ?: throw KdrException("The scripted model has no step left for request ${requests.size}.")
        }
        return step(request)
    }

    companion object {
        /** A response of one text block that ends the turn, billed [usage]. */
        fun textResponse(
            text: String,
            usage: ModelUsage = ModelUsage(inputTokens = 100, outputTokens = 20),
            model: String = AENV.defaultModel,
        ): ModelResponse =
            ModelResponse.fromWire(
                mapOf(
                    MSG.role to MSG.assistant,
                    MSG.model to model,
                    MSG.content to listOf(mapOf(MSG.type to MSG.text, MSG.text to text)),
                    MSG.stopReason to MSG.endTurn,
                    MSG.usage to mapOf(
                        MSG.inputTokens to usage.inputTokens,
                        MSG.cacheCreationInputTokens to usage.cacheWriteTokens,
                        MSG.cacheReadInputTokens to usage.cacheReadTokens,
                        MSG.outputTokens to usage.outputTokens,
                    ),
                ),
            )

        /**
         * A response that calls the tools [calls] names, each with its input, stopping for their results (or for
         * [stopReason]), billed [usage]. Each call's id is unique, across boots too: a conversation stored in a database
         * outlives the node, and a history that makes one call twice cannot go on.
         */
        fun toolResponse(
            vararg calls: Pair<String, Map<String, Any?>>,
            stopReason: String = MSG.toolUse,
            usage: ModelUsage = ModelUsage(inputTokens = 100, outputTokens = 20),
        ): ModelResponse = response(
            calls.map { (name, input) ->
                mapOf(
                    MSG.type to TOOL.toolUse, TOOL.id to "toolu_$callIdPrefix${callIds.incrementAndGet()}", TOOL.name to name,
                    TOOL.input to input,
                )
            },
            stopReason, usage,
        )

        /** A response of [content] blocks, stopping for [stopReason], billed [usage]. */
        fun response(
            content: List<Map<String, Any?>>,
            stopReason: String,
            usage: ModelUsage = ModelUsage(inputTokens = 100, outputTokens = 20),
        ) =
            ModelResponse.fromWire(
                mapOf(
                    MSG.role to MSG.assistant, MSG.model to AENV.defaultModel, MSG.content to content, MSG.stopReason to stopReason,
                    MSG.usage to mapOf(
                        MSG.inputTokens to usage.inputTokens, MSG.cacheCreationInputTokens to usage.cacheWriteTokens,
                        MSG.cacheReadInputTokens to usage.cacheReadTokens, MSG.outputTokens to usage.outputTokens,
                    ),
                ),
            )

        @KdrPrivate
        val callIds = AtomicLong()

        /** This boot's own start to a call's id, so the counter, which starts again with every boot, repeats no id. */
        @KdrPrivate
        val callIdPrefix = UUID.randomUUID().toString().take(8) + "_"

        /**
         * The built-in demo for `KDR_ASSISTANT_PROVIDER=fake`, for trying the assistant locally with no key and no
         * spending. Test instances only. It goes through real tool rounds, so a person trying the page sees tool calls
         * and their results in the transcript: a message from the person is answered by calling `list_workflows`, and
         * that call's result by naming the workflows it found. Asked to draft (a message saying "draft"), it drafts a
         * copy of a workflow ([demoDraft]), as the model would -- a dry run, then the write -- into a bundle of its own
         * ([DEMO.bundle]), so the page's drafts can be tried in the sandbox and published.
         */
        fun demo(): AssistantModel = object : AssistantModel {
            override val label: String = "demo (${AENV.fakeProvider})"

            override fun respond(cxt: KdrCxt, request: ModelRequest): ModelResponse {
                // What the request ends with: the person's new message -- after an earlier turn's answered calls, it is
                // joined to them, so it is the last block that says -- or the result of the demo's own call.
                val last = request.messages.lastOrNull()?.let { AssistantHistory.blocksOf(it) }.orEmpty().lastOrNull()
                if (last?.get(MSG.type) != TOOL.toolResult) return toolResponse(ATOOL.listWorkflows to emptyMap())
                val call = callAnswered(request.messages, last[TOOL.toolUseId].toOptStr())
                val input = call?.get(TOOL.input).toJsonMapOrEmpty()
                return when {
                    last[TOOL.isError] == true -> textResponse("The demo assistant was refused: ${last[TOOL.content]}")
                    call?.get(TOOL.name) == ATOOL.patchConfig && input[CFEP.dryRun] == true ->
                        toolResponse(ATOOL.patchConfig to (input + (CFEP.dryRun to false)))
                    call?.get(TOOL.name) == ATOOL.patchConfig -> textResponse(
                        "Drafted the workflow '${DEMO.workflowId}' in the bundle '${DEMO.bundle}', a copy of another. Try it " +
                            "in the sandbox, then publish it from the Clients page.",
                    )
                    asksToDraft(request.messages) -> demoDraft(last)?.let { toolResponse(ATOOL.patchConfig to it) }
                        ?: textResponse("The demo assistant found no workflow or template to copy, so it drafted nothing.")
                    else -> textResponse(demoSummary(last))
                }
            }
        }

        /** The `tool_use` block in [messages] whose id is [id]: the call a result answers. */
        @KdrPrivate
        fun callAnswered(messages: List<Map<String, Any?>>, id: String?): Map<String, Any?>? =
            messages.asReversed().asSequence().flatMap { AssistantHistory.toolUses(it) }.firstOrNull { it[TOOL.id] == id }

        /** Whether the person's latest words -- their last text, not a tool's result -- ask the demo to draft. */
        @KdrPrivate
        fun asksToDraft(messages: List<Map<String, Any?>>): Boolean = messages.asReversed().asSequence()
            .filter { it[MSG.role] == MSG.user }.flatMap { AssistantHistory.blocksOf(it).asReversed() }
            .firstOrNull { it[MSG.type] == MSG.text }?.get(MSG.text).toString().contains(DEMO.keyword, ignoreCase = true)

        /**
         * The patch the demo dry runs from a `list_workflows` [result]: a copy of the client's first normal workflow --
         * which, being the client's own, collects only what the client supports -- else of the first normal template not
         * refused, as a new workflow [DEMO.workflowId] in [DEMO.bundle]. Null when there is neither, or the answer cannot
         * be read.
         */
        @KdrPrivate
        fun demoDraft(result: Map<String, Any?>): Map<String, Any?>? {
            val catalog = result[TOOL.content].toString().jsonMapOrNull() ?: return null
            val normal = { def: Map<String, Any?> -> def[WFD.entry] == WfEntry.normal.name && def[CCT.workflowId] != DEMO.workflowId }
            val source = catalog[CWF.workflows].toJsonListOfMaps().map { it[CWF.definition].toJsonMapOrEmpty() }.firstOrNull(normal)
                ?: catalog[CWF.templates].toJsonListOfMaps().filter { it[CWF.refusal] == null }
                    .map { it[CWF.definition].toJsonMapOrEmpty() }.firstOrNull(normal)
                ?: return null
            // Whole, not as an overlay of a template: a definition naming its template is stored as only what it changes,
            // and the listing's is the template expanded, so a copy that kept the name would be refused as changing it.
            val definition = (source - WFD.fromTemplate) +
                mapOf(CCT.workflowId to DEMO.workflowId, WFD.label to "Demo: ${source[WFD.label] ?: "workflow"}")
            val edit = mapOf(
                CFEP.slot to CCT.workflowDef, GED.action to GedraEditAction.addOrReplace.name,
                GE.data to mapOf(CCT.workflowId to DEMO.workflowId, CCT.definition to definition),
            )
            return mapOf(CFEP.configs to listOf(mapOf(CFEP.name to DEMO.bundle, CFEP.edits to listOf(edit))), CFEP.dryRun to true)
        }

        /** What the demo says of a `list_workflows` [result]: the workflows it names, or why it has none to name. */
        fun demoSummary(result: Map<String, Any?>): String {
            val text = result[TOOL.content].toString()
            if (result[TOOL.isError] == true) return "The demo assistant asked for the client's workflows and was refused: $text"
            // A long catalog is cut, and is then no longer JSON.
            val catalog = text.jsonMapOrNull() ?: return "The demo assistant asked for the client's workflows, and the answer " +
                "was too long to read here."
            val workflows = catalog[CWF.workflows].toJsonListOfMaps().map { it[CWF.definition].toJsonMapOrEmpty() }
            val named = workflows.map { def ->
                val id = def[CCT.workflowId].toString()
                def[WFD.label].toOptStr()?.let { "$it ($id)" } ?: id
            }
            val found = if (named.isEmpty()) "no workflows yet" else "${named.size}: ${named.joinToString(", ")}"
            return "This is the demo assistant: it looked up the client's workflows as its sandbox runs them, and found " +
                "$found. It is not connected to a model; ask it to ${DEMO.keyword} a workflow and it drafts a copy of one, " +
                "to try. A deployment with a model key answers here instead."
        }
    }
}

/** What the demo drafts when asked. */
@Suppress("ConstPropertyName")
object DEMO {
    /** The word in the person's message that asks the demo to draft. */
    const val keyword = "draft"

    /** The bundle the demo drafts into, and the workflow it drafts. */
    const val bundle = "assistantDemo"
    const val workflowId = "assistantDemo"
}
