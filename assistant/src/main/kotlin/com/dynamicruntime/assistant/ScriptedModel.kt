package com.dynamicruntime.assistant

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.util.jsonMapOrNull
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
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
         * [stopReason]), billed [usage]. Each call's id is unique within the test run.
         */
        fun toolResponse(
            vararg calls: Pair<String, Map<String, Any?>>,
            stopReason: String = MSG.toolUse,
            usage: ModelUsage = ModelUsage(inputTokens = 100, outputTokens = 20),
        ): ModelResponse = response(
            calls.map { (name, input) ->
                mapOf(MSG.type to TOOL.toolUse, TOOL.id to "toolu_${callIds.incrementAndGet()}", TOOL.name to name, TOOL.input to input)
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

        /**
         * The built-in demo for `KDR_ASSISTANT_PROVIDER=fake`, for trying the assistant locally with no key and no
         * spending. Test instances only. It goes through one real tool round, so a person trying the page sees a tool
         * call and its result in the transcript: a message from the person is answered by calling `list_workflows`, and
         * that call's result by naming the workflows it found.
         */
        fun demo(): AssistantModel = object : AssistantModel {
            override val label: String = "demo (${AENV.fakeProvider})"

            override fun respond(cxt: KdrCxt, request: ModelRequest): ModelResponse {
                // What the request ends with: the person's new message -- after an earlier turn's answered calls, it is
                // joined to them, so it is the last block that says -- or the result of the demo's own call.
                val last = request.messages.lastOrNull()?.let { AssistantHistory.blocksOf(it) }.orEmpty().lastOrNull()
                if (last?.get(MSG.type) != TOOL.toolResult) return toolResponse(TOOL.listWorkflows to emptyMap())
                return textResponse(demoSummary(last))
            }
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
                "$found. It is not connected to a model, so it cannot do more; a deployment with a model key answers here instead."
        }
    }
}
