package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.RID
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.ApiKeyAccess
import com.dynamicruntime.common.user.InProcessScope
import com.dynamicruntime.common.user.SandboxAccess
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.toActingProfile
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr

/**
 * Who a call made in this process acts as (issue #1524): the user [userId], which must still be the user of the person
 * [identityId] in [client] -- the user the work was started for, not whatever the row has become since -- the name
 * [keyId] the call goes by in refusals and on its context, as a key's id would, and the [scope] of what it may reach.
 */
class InProcessPrincipal(
    val userId: Long,
    val identityId: String,
    val client: String,
    val keyId: String,
    val scope: InProcessScope,
)

/**
 * What a call made in this process answered: its HTTP [status]; its JSON [envelope], empty when the answer was not JSON;
 * the answer's [mimeType] and its body as [text] or, for a binary answer, [bytes]; and the [headers] it would have sent.
 */
class InProcessResult(
    val status: Int,
    val envelope: Map<String, Any?>,
    val mimeType: String?,
    val text: String?,
    val bytes: ByteArray?,
    val headers: Map<String, List<String>>,
) {
    val isSuccess: Boolean get() = status in 200..299

    /** The message a refusal or failure carried, when it carried one. */
    val errorMessage: String? get() = envelope[EP.errorMessage] as? String
}

/**
 * Runs one endpoint **in this process, as a user**, through the whole request pipeline (issue #1524): the same
 * dispatcher, section gate, validation, and handler a request from the wire gets, so the call can do exactly what the
 * user could -- and less. It is held to everything an API key is held to (no `sessionOnly` endpoint, so no key-making,
 * switching, sandbox opening or leaving, or logout), to its principal's [InProcessScope], which the dispatcher and the
 * endpoint catalog both ask through [ApiKeyAccess.refusal], and never to env auth, whatever the node assumes ([bind]).
 *
 * What it is for: work a person asked for that runs on after their request -- an assistant acting for them -- which
 * must hold no more than the person does at the moment of each call. So nothing is carried from one call to the next:
 * each builds its own context, re-reads its user ([bind]), and is refused once the user is disabled, has become
 * another's, or loses the standing the endpoint needs. No cookie is read or kept.
 *
 * **Call it from a thread of its own**, as a background worker is: never from inside a database transaction, whose
 * connection and table-cache bracket the call's own request would sit beside rather than share.
 */
@Suppress("ConstPropertyName")
object InProcessCaller {
    /**
     * Calls [method] [path] -- an application path under the API root, beginning with `/`, such as
     * `/clientAdmin/config/bundles` -- as [principal], with [args] in the query (single values only; a list belongs in
     * [body]) and [body] as JSON. [traceId] ties the call's log lines to the work that made it; [appId] names the
     * caller to whatever reads a request's client identity.
     *
     * An input this cannot send -- a path that is not one, a list in [args], a [body] that is not JSON -- is answered
     * as the dispatcher answers a bad request: a 400 with an `errorMessage`, so the caller has one way to read every
     * refusal.
     */
    fun call(
        instanceConfig: KdrInstanceConfig,
        principal: InProcessPrincipal,
        method: HttpMethod,
        path: String,
        args: Map<String, Any?>? = null,
        body: Map<String, Any?>? = null,
        traceId: String,
        appId: String = defaultAppId,
    ): InProcessResult {
        if (!path.startsWith("/")) return badInput("'$path' is not an application path: it must begin with '/'.")
        args?.entries?.firstOrNull { (_, v) -> v != null && v !is String && v !is Number && v !is Boolean }?.let {
            return badInput("Query argument '${it.key}' must be a single value; send a list in the body.")
        }
        val bodyText = try {
            body?.toJsonStr()
        } catch (e: Exception) {
            return badInput("The body could not be written as JSON: ${e.message}")
        }
        val headers = mapOf(RID.appIdHeader to listOf(appId), RID.traceIdHeader to listOf(traceId))
        val handler = RequestHandler(instanceConfig, method.name, "/${ContextRoot.apiOf(instanceConfig)}$path", headers, LinkedHashMap())
        handler.queryStr = args?.let { HttpUtil.encodeArgs(it) } ?: ""
        handler.testPostData = bodyText ?: ""
        handler.inProcessPrincipal = principal
        handler.handleRequest()
        val text = handler.rptResponseData
        val mimeType = handler.rptResponseMimeType
        val isJson = mimeType == null || mimeType.contains("json")
        val envelope = if (isJson) text?.let { runCatching { it.jsonMap() }.getOrNull() }.orEmpty() else emptyMap()
        return InProcessResult(handler.rptStatusCode, envelope, mimeType, text, handler.rptResponseBytes, handler.rptResponseHeaders)
    }

    private fun badInput(message: String) = InProcessResult(
        EXC.badInput, mapOf(EP.status to EXC.badInput, EP.errorMessage to message), "application/json", null, null, emptyMap(),
    )

    /**
     * Binds [principal]'s user to [cxt], as a key's owner is bound: read afresh, and refused (401) when gone, deleted,
     * disabled, no longer the user of the principal's person and client, in a client this node does not carry, or -- in a
     * sandbox -- stranded, with nobody outside standing behind it. Its acting profile is the one a session would get (in a
     * sandbox, what the sandbox's rules give it now), and it is recorded on [cxt] as a key with no row ([ApiKeyAccess]
     * with the principal's scope), so every refusal a key gets applies. Called by [RequestService.extractAuth].
     *
     * Never env-authed: env auth is a fact about a channel -- an edge vouching, or a node assuming it of a request that
     * came straight to it -- and a call made in this process came through no channel. A node that assumes env auth of a
     * request with no forwarding header would otherwise assume it of every in-process call, and with it test access and
     * the `_debug` tags, which the person need not hold.
     */
    fun bind(cxt: KdrCxt, principal: InProcessPrincipal) {
        cxt.envAuthEmail = null
        cxt.envAuthSuppressed = false
        cxt.envAuthDebug = false
        val users = UserService.getOrNull(cxt)
            ?: throw KdrException("This node has no users to act for.", code = EXC.authNeeded)
        val row = users.queryByUserId(cxt, principal.userId)
        if (row == null || row.isDeleted || !row.enabled || row.identityId != principal.identityId || row.client != principal.client) {
            throw KdrException("The user ${principal.keyId} acts for is gone, disabled, or no longer theirs.", code = EXC.authNeeded)
        }
        if (ClientService.getOrNull(cxt)?.isPresent(row.client) == false) {
            throw KdrException("The client '${row.client}' is not available here.", code = EXC.authNeeded)
        }
        if (isSandboxClient(row.client) && SandboxAccess.standingOf(cxt, row).stranded) {
            throw KdrException("Nobody outside the sandbox stands behind the user ${principal.keyId} acts for.", code = EXC.authNeeded)
        }
        cxt.bindToUserProfile(row.toActingProfile(cxt))
        cxt.apiKey = ApiKeyAccess(principal.keyId, AKEY.full, inProcess = principal.scope)
    }

    /** What a call names itself as when its caller gives no other name. */
    const val defaultAppId = "inProcess"
}
