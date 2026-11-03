package com.dynamicruntime.assistant

import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr

/**
 * The rules a conversation's history keeps (issue #1526). It is **append-only**: what was sent and answered is stored
 * exactly and never changed, since the model's thinking is bound to the history it was produced on, and an edited one
 * is refused. And every tool call is answered, in the very next message, before the model is asked again.
 *
 * [historyProblems] is the check, pure over the messages, run in the tests and before every model call -- a history
 * that breaks a rule is never sent, so a bug here ends a turn instead of being answered with a 400.
 */
object AssistantHistory {
    /**
     * [messages] as a request sends them: consecutive messages of one role joined into one, their content in order.
     * A turn that ended on its tool results is followed by the person's next message, and the two go as one.
     */
    fun forRequest(messages: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val joined = mutableListOf<Map<String, Any?>>()
        for (message in messages) {
            val last = joined.lastOrNull()
            if (last != null && last[MSG.role] == message[MSG.role]) {
                joined[joined.size - 1] = mapOf(MSG.role to last[MSG.role], MSG.content to blocksOf(last) + blocksOf(message))
            } else {
                joined.add(mapOf(MSG.role to message[MSG.role], MSG.content to blocksOf(message)))
            }
        }
        return joined
    }

    /** The content blocks of [message]; a plain-text content is one text block. */
    fun blocksOf(message: Map<String, Any?>): List<Map<String, Any?>> = when (val content = message[MSG.content]) {
        is String -> listOf(mapOf(MSG.type to MSG.text, MSG.text to content))
        else -> content.toJsonListOfMaps()
    }

    /** The `tool_use` blocks of [message]. */
    fun toolUses(message: Map<String, Any?>): List<Map<String, Any?>> = blocksOf(message).filter { it[MSG.type] == TOOL.toolUse }

    /**
     * What is wrong with [messages] as a conversation to send the model next: empty when nothing is. Read as the request
     * sends them ([forRequest]). It must begin with the person, give every block a type, answer every tool call in the
     * next message -- with its results first, as the API wants them -- answer no call that was not made, never use a
     * call's id twice, and end with the person, since the model answers last -- unless [continuing] the model's own
     * answer, which it paused (`pause_turn`): then it ends with that answer, and makes no call.
     */
    fun historyProblems(messages: List<Map<String, Any?>>, continuing: Boolean = false): List<String> {
        val problems = mutableListOf<String>()
        val joined = forRequest(messages)
        if (joined.isEmpty()) return listOf("The conversation is empty.")
        if (joined.first()[MSG.role] != MSG.user) problems.add("The conversation does not begin with the person.")
        val last = joined.last()
        if (continuing) {
            if (last[MSG.role] != MSG.assistant || toolUses(last).isNotEmpty()) {
                problems.add("A paused answer is continued only from the model's own answer, with no call in it.")
            }
        } else if (last[MSG.role] != MSG.user) {
            problems.add("The conversation ends with the model, which has nothing to answer.")
        }
        val seenIds = mutableSetOf<String>()
        joined.forEachIndexed { i, message ->
            val role = message[MSG.role]
            val blocks = blocksOf(message)
            if (role != MSG.user && role != MSG.assistant) problems.add("Message ${i + 1} has the role '$role'.")
            if (blocks.isEmpty()) problems.add("Message ${i + 1} has no content.")
            if (blocks.any { it[MSG.type].toOptStr() == null }) problems.add("Message ${i + 1} has a block with no type.")
            val calls = toolUses(message).mapNotNull { it[TOOL.id].toOptStr() }
            for (id in calls) {
                if (!seenIds.add(id)) problems.add("Tool call '$id' is made twice.")
            }
            val answers = blocks.filter { it[MSG.type] == TOOL.toolResult }
            val previous = if (i > 0) joined[i - 1] else null
            val asked = previous?.let { p -> toolUses(p).mapNotNull { it[TOOL.id].toOptStr() } }.orEmpty()
            if (role == MSG.user) {
                val answered = answers.mapNotNull { it[TOOL.toolUseId].toOptStr() }
                (asked - answered.toSet()).forEach { problems.add("Tool call '$it' in message $i is not answered.") }
                (answered - asked.toSet()).forEach { problems.add("Message ${i + 1} answers '$it', which was not called just before.") }
                if (answers.isNotEmpty() && blocks.take(answers.size).any { it[MSG.type] != TOOL.toolResult }) {
                    problems.add("Message ${i + 1} puts something before its tool results.")
                }
            } else if (answers.isNotEmpty()) {
                problems.add("Message ${i + 1}, the model's, holds tool results.")
            }
        }
        return problems
    }
}
