package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLongOrNull
import com.dynamicruntime.common.util.toOptStr

/**
 * What the caller may create a client with (issue #1820), as `/clientAdmin/client/createChoices` answers: whether it
 * creates as the platform ([asPlatform], any client) or for its customer ([customerId], the customer whose root it
 * administers), the templates it may build one on, and -- the platform's only -- the customers it may file one under.
 * Each choice is its id to its label.
 */
class ClientCreateChoices(
    val asPlatform: Boolean,
    val customerId: String?,
    val templates: List<Pair<String, String>>,
    val customers: List<Pair<String, String>>,
)

/** The create choices off the wire. Pure, and covered under `jsNodeTest`. */
fun parseClientCreateChoices(item: Map<String, Any?>): ClientCreateChoices = ClientCreateChoices(
    asPlatform = item[CLD.asPlatform] == true,
    customerId = item[CLD.customerId].toOptStr(),
    templates = choicesOf(item[CLD.templateChoices]),
    customers = choicesOf(item[CLD.customerChoices]),
)

private fun choicesOf(raw: Any?): List<Pair<String, String>> = raw.toJsonListOfMaps().mapNotNull { m ->
    m[SCH.value].toOptStr()?.let { it to (m[SCH.label].toOptStr() ?: it) }
}

/**
 * A New client form as typed (issue #1820). [clientId] is the whole id for the platform, and for a customer's
 * administrator the part after `<customer>_`. [customerId] (blank for none), [usageType], [audience], and
 * [environments] are the platform's alone: a customer's client takes them from its root.
 */
data class ClientCreateDraft(
    val clientId: String = "",
    val name: String = "",
    val description: String = "",
    val customerId: String = "",
    /** The template it is built on; blank for a blank client. */
    val template: String = "",
    /** Whether a blank client starts with a creation workflow and a core details workflow. */
    val starterWorkflows: Boolean = true,
    val sandbox: Boolean = false,
    val usageType: String = ClientUsageType.production.name,
    /** Null follows the customer: a customer's client is the customer's, one under none is ours. */
    val audience: String? = null,
    val environments: List<String> = ENV.names,
)

/** A customer's client id from the part typed after its prefix: `acme` and `east` make `acme_east`. Pure. */
fun customerClientId(customerId: String, part: String): String = "$customerId${CUST.separator}${part.trim()}"

/** The id [draft] creates, as [choices] say the caller creates. Pure, and covered under `jsNodeTest`. */
fun draftClientId(choices: ClientCreateChoices, draft: ClientCreateDraft): String =
    choices.customerId?.takeIf { !choices.asPlatform }?.let { customerClientId(it, draft.clientId) } ?: draft.clientId.trim()

/** [draft]'s audience: as chosen, else the customer's for a client filed under one, else ours. Pure. */
fun draftAudience(draft: ClientCreateDraft): String =
    draft.audience ?: if (draft.customerId.isBlank()) ClientAudience.internal.name else ClientAudience.customer.name

/**
 * What keeps [draft] from being sent, each a sentence; empty when it may be (issue #1820). Only what is plainly missing:
 * whether the id fits its customer, is taken, or is well formed is the create's to judge, and its refusal is shown in
 * its words. Pure, and covered under `jsNodeTest`.
 */
fun clientCreateProblems(choices: ClientCreateChoices, draft: ClientCreateDraft): List<String> = buildList {
    if (draft.clientId.isBlank()) add("Give the client an id.")
    if (draft.name.isBlank()) add("Give the client a name.")
    if (choices.asPlatform && draft.environments.isEmpty()) add("Choose at least one environment the client runs in.")
}

/**
 * The body of the create [choices] name -- the platform's or the customer's (issue #1820): the id, name, and note; the
 * template, or whether a blank client starts with workflows (sent only when it does not); a sandbox when asked for;
 * and, for the platform, the customer, usage, audience, and environments. Pure, and covered under `jsNodeTest`.
 */
fun clientCreateRequest(choices: ClientCreateChoices, draft: ClientCreateDraft): Map<String, Any?> = buildMap {
    put(CLD.clientId, draftClientId(choices, draft))
    put(CLD.name, draft.name.trim())
    draft.description.trim().ifEmpty { null }?.let { put(CLD.description, it) }
    val template = draft.template.trim().ifEmpty { null }
    if (template != null) put(CLD.extendsFromClientId, template) else if (!draft.starterWorkflows) put(CLD.starterWorkflows, false)
    if (draft.sandbox) put(CLD.sandbox, true)
    if (choices.asPlatform) {
        draft.customerId.trim().ifEmpty { null }?.let { put(CLD.customerId, it) }
        put(CLD.usageType, draft.usageType)
        put(CLD.audience, draftAudience(draft))
        put(CLD.enabledEnvironments, draft.environments)
    }
}

/** The endpoint [choices] create through: the platform's, or the customer's. */
fun clientCreatePath(choices: ClientCreateChoices): String = if (choices.asPlatform) ADEP.clientCreate else UADEP.clientCreate

/**
 * What a create did (issue #1820): the [client] made, whether this node carries it now ([present], else
 * [loadFailure]), and -- a customer's create -- the creator's own administrator user there ([userId], else
 * [userFailure]).
 */
class ClientCreateOutcome(
    val client: String,
    val present: Boolean,
    val loadFailure: String?,
    val userId: Long?,
    val userFailure: String?,
)

/** A create's `results`. Pure, and covered under `jsNodeTest`. */
fun parseClientCreateOutcome(results: Map<String, Any?>): ClientCreateOutcome = ClientCreateOutcome(
    client = results[CLD.client].toOptStr().orEmpty(),
    present = results[CLD.present] == true,
    loadFailure = results[CLD.loadFailure].toOptStr(),
    userId = results[CLD.userId].toOptLongOrNull(),
    userFailure = results[CLD.userFailure].toOptStr(),
)

/**
 * What the form says once [outcome]'s client is made (issue #1820): that it was, and what did not follow -- a client
 * this node does not carry, or a creator's user that could not be made. The client exists either way, so nothing here
 * is to be created again. Pure, and covered under `jsNodeTest`.
 */
fun clientCreatedText(outcome: ClientCreateOutcome): String = buildString {
    append("Created ${outcome.client}.")
    if (!outcome.present) append(" This node is not running it" + (outcome.loadFailure?.let { ": ${it.trim().trimEnd('.')}." } ?: "."))
    outcome.userFailure?.let { append(" Your administrator user there could not be made: ${it.trim().trimEnd('.')}.") }
}

/** Whether the form offers to switch to the new client: it is carried here and the creator has a user there. Pure. */
fun clientCreatedSwitchable(outcome: ClientCreateOutcome): Boolean = outcome.present && outcome.userId != null

/** The creates of a client (issue #1820): their choices, and the create itself. */
object ClientCreateApi {
    suspend fun choices(): ClientCreateChoices =
        parseClientCreateChoices(Http.getApi(UADEP.clientCreateChoices)[EP.item].toJsonMapOrEmpty())

    suspend fun create(choices: ClientCreateChoices, draft: ClientCreateDraft): ClientCreateOutcome = parseClientCreateOutcome(
        Http.sendApi("POST", clientCreatePath(choices), clientCreateRequest(choices, draft))[EP.results].toJsonMapOrEmpty(),
    )
}
