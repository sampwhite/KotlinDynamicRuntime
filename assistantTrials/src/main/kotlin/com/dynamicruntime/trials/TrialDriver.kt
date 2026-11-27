package com.dynamicruntime.trials

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.PORT
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.script.ProbeResponse
import com.dynamicruntime.script.ProbeSession
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One signed-in person's calls, as the driver makes them: over HTTP to a node ([ProbeCaller]), or in-process in a test.
 * The names are `TestUser`'s, so a test's user is a caller as it is. Each throws when the call is refused.
 */
interface TrialCaller {
    fun getData(path: String, args: Map<String, Any?>? = null): Map<String, Any?>
    fun getItems(path: String, args: Map<String, Any?>? = null): List<Map<String, Any?>>
    fun getItem(path: String, args: Map<String, Any?>? = null): Map<String, Any?>
    fun postData(path: String, data: Map<String, Any?>): Map<String, Any?>
}

/** A call the node refused: its path, status, and message. */
class TrialCallRefused(path: String, status: Int, message: String) : RuntimeException("$path: HTTP $status, $message")

/** A [TrialCaller] over a [ProbeSession]: a real socket to a running node. */
class ProbeCaller(private val session: ProbeSession) : TrialCaller {
    private fun envelope(path: String, response: ProbeResponse): Map<String, Any?> {
        if (!response.isSuccess) {
            throw TrialCallRefused(path, response.statusCode, response.errorMessage ?: response.rawBody.take(300))
        }
        return response.body
    }

    private fun get(path: String, args: Map<String, Any?>?) = envelope(path, session.sendGetRequest(path, args))

    override fun getData(path: String, args: Map<String, Any?>?) = get(path, args)[EP.results].toJsonMapOrEmpty()
    override fun getItems(path: String, args: Map<String, Any?>?) = get(path, args)[EP.items].toJsonListOfMaps()
    override fun getItem(path: String, args: Map<String, Any?>?) = get(path, args)[EP.item].toJsonMapOrEmpty()
    override fun postData(path: String, data: Map<String, Any?>) =
        envelope(path, session.sendPostRequest(path, data))[EP.results].toJsonMapOrEmpty()
}

/** The people a walk acts as, each a [ProbeSession] of their own on the node at [url]; closed together. */
class ProbePeople(private val url: String) : TrialPeople, AutoCloseable {
    private val sessions = mutableListOf<ProbeSession>()

    override fun become(email: String, client: String, name: String): TrialCaller {
        val session = ProbeSession("walk-$name", url).also { sessions.add(it) }
        session.becomeUser(email, client = client, name = name)
        return ProbeCaller(session)
    }

    override fun close() = sessions.forEach { it.close() }
}

/**
 * Runs one trial as one person (issue #1859): creates their self-serve client, switches the assistant on for it, opens
 * its sandbox, writes the trial's start there (issue #1916), sends the variant's prompts one after another, each waiting
 * for its turn to end, and reads back the
 * conversations and the client's configuration. Then, given [people], it walks through what was configured ([TrialWalk],
 * issue #1915). What goes wrong is recorded in the result's problems, and the run goes on as far as it can: a report of a
 * partial run says more than an exception.
 */
class TrialDriver(
    private val caller: TrialCaller,
    /** How long one turn may run before the driver stops waiting for it. */
    private val turnTimeout: Duration = 20.minutes,
    /** How often a running turn is polled. */
    private val pollEvery: Duration = 2.seconds,
    private val log: (String) -> Unit = ::println,
    /** Who makes the people a walk acts as; null to walk nothing. */
    private val people: TrialPeople? = null,
) {
    fun run(trial: Trial, variant: TrialVariant, runId: String): TrialResult {
        val startedAt = Clock.System.now()
        val problems = mutableListOf<String>()
        var client: String? = null
        var sandbox: String? = null
        val conversationIds = mutableListOf<String>()
        try {
            val created = caller.postData(AEP.createOwnClient, mapOf(CLD.name to trial.clientNameFor(variant)))
            client = created[CLD.client].toOptStr()
            if (created[CLD.switched] != true) error("Creating the client did not move the session into it: $created")
            caller.postData(ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
            caller.postData(AEP.openSandbox, emptyMap())
            sandbox = caller.getData(AEP.selfInfo)[UPF.client].toOptStr()
            log("  ${trial.name}/${variant.name}: client $client, sandbox $sandbox")
            trial.start?.let {
                log("  ${trial.name}/${variant.name}: writing its start")
                writeStart(it)
            }
            var conversationId: String? = null
            variant.prompts.forEachIndexed { i, prompt ->
                if (prompt.newConversation) conversationId = null
                log("  ${trial.name}/${variant.name}: prompt ${i + 1} of ${variant.prompts.size}")
                val started = try {
                    caller.postData(
                        ACNV.messagePath,
                        buildMap {
                            conversationId?.let { put(ACNV.conversationId, it) }
                            put(ACNV.clientMessageId, "$runId-${trial.name}-${variant.name}-${i + 1}")
                            put(ACNV.text, prompt.text)
                        },
                    )
                } catch (e: TrialCallRefused) {
                    problems.add("Prompt ${i + 1} was refused: ${e.message}")
                    return@forEachIndexed
                }
                val id = started[ACNV.conversationId].toOptStr().orEmpty()
                if (id !in conversationIds) conversationIds.add(id)
                conversationId = id
                awaitTurn(id, started[ACNV.turnId].toOptStr().orEmpty())?.let { problems.add("Prompt ${i + 1}: $it") }
            }
        } catch (e: Exception) {
            problems.add("The trial stopped: ${e.message ?: e::class.simpleName}")
        }
        val conversations = conversationIds.mapNotNull { id ->
            runCatching { readConversation(id) }.onFailure { problems.add("Could not read conversation $id: ${it.message}") }.getOrNull()
        }
        val config = runCatching { readConfig(caller) }.onFailure { problems.add("Could not read the configuration: ${it.message}") }
            .getOrDefault(emptyMap())
        val walk = if (people != null && sandbox != null && trial.walk.isNotEmpty()) {
            log("  ${trial.name}/${variant.name}: walking through it")
            TrialWalk(caller, people, sandbox, ConfigView(config), trial.walk, addressOf(trial, variant, runId)).walk()
        } else {
            null
        }
        return TrialResult(trial, variant, client, sandbox, conversations, config, problems, startedAt, Clock.System.now(), walk)
    }

    /**
     * Writes [start] into the sandbox the session is in: the client's definition first, since a bundle may name what it
     * sets (a label a review takes), then each bundle whole, reloading the sandbox after each. A write refused stops the
     * trial before its first prompt, since what the prompts change would not be there.
     */
    private fun writeStart(start: TrialStart) {
        if (start.definition.isNotEmpty()) caller.postData(UADEP.clientDefinitionSet, start.definition)
        for (bundle in start.bundles) {
            caller.postData(
                CFEP.bundleWrite,
                mapOf(
                    CFEP.name to bundle[CFEP.name], CFEP.namespaceField to PORT.namespace, CFEP.slots to bundle[CFEP.slots],
                    CFEP.reloadField to true,
                ),
            )
        }
    }

    /** Waits for [turnId] to end; answers why it did not, or null when it did. */
    private fun awaitTurn(conversationId: String, turnId: String): String? {
        val deadline = Clock.System.now() + turnTimeout
        var failures = 0
        while (Clock.System.now() < deadline) {
            // After the last message there is none, so the poll reads the turns alone.
            val read = try {
                caller.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to conversationId, ACNV.afterSeq to Long.MAX_VALUE))
                    .also { failures = 0 }
            } catch (e: Exception) {
                // One poll failing is a moment's trouble, not the turn's: a paid run gives up only on several in a row.
                if (++failures >= maxPollFailures) {
                    runCatching { caller.postData(ACNV.cancelPath, mapOf(ACNV.conversationId to conversationId)) }
                    return "polling it failed $failures times in a row, and it was asked to stop; the last: ${e.message}"
                }
                null
            }
            val turn = read?.get(ACNV.turns).toJsonListOfMaps().firstOrNull { it[ACNV.turnId] == turnId }
            if (turn != null && turn[ACNV.status] != ACNV.statusRunning) return null
            Thread.sleep(pollEvery.inWholeMilliseconds)
        }
        runCatching { caller.postData(ACNV.cancelPath, mapOf(ACNV.conversationId to conversationId)) }
        return "its turn was still running after $turnTimeout, and was asked to stop."
    }

    private val maxPollFailures = 5

    private fun readConversation(id: String): TrialConversation {
        val read = caller.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to id))
        return TrialConversation(id, read[ACNV.turns].toJsonListOfMaps(), read[ACNV.messages].toJsonListOfMaps())
    }

    companion object {
        /** Every configuration bundle the sandbox's client stores, by name, in portable form so runs compare. */
        fun readConfig(caller: TrialCaller): Map<String, Map<String, Any?>> =
            caller.getItems(CFEP.bundles).mapNotNull { it[CFEP.name].toOptStr() }
                .associateWith { name -> caller.getItem(CFEP.bundle, mapOf(CFEP.name to name, PORT.portableField to true)) }

        /** The address of the person who runs [trial] as [variant] in the run [runId]: its client's administrator. */
        fun addressOf(trial: Trial, variant: TrialVariant, runId: String): String =
            "${trial.name}-${variant.name}-$runId@trials.test".lowercase()
    }
}
