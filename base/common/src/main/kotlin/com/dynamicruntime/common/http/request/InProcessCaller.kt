package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.RID
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.ApiKeyAccess
import com.dynamicruntime.common.user.InProcessScope
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.toActingProfile
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr

/**
 * Who a call made in this process acts as (issue #1524): the user [userId], the name [keyId] the call goes by -- in
 * logs and refusals, as a key's id would -- and the [scope] of what it may reach.
 */
class InProcessPrincipal(val userId: Long, val keyId: String, val scope: InProcessScope)

/**
 * What a call made in this process answered: its HTTP [status], its JSON [envelope] (empty when it sent none), and the
 * [headers] it would have sent.
 */
class InProcessResult(val status: Int, val envelope: Map<String, Any?>, val headers: Map<String, List<String>>) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Runs one endpoint **in this process, as a user**, through the whole request pipeline (issue #1524): the same
 * dispatcher, section gate, validation, and handler a request from the wire gets, so the call can do exactly what the
 * user could -- and less. It is held to everything an API key is held to (no `sessionOnly` endpoint, so no key-making,
 * switching, sandbox opening or leaving, or logout) and, beyond that, to its principal's [InProcessScope], which the
 * dispatcher and the endpoint catalog both ask through [ApiKeyAccess.refusal].
 *
 * What it is for: work a person asked for that runs on after their request -- an assistant acting for them -- which
 * must hold no more than the person does at the moment of each call. So nothing is carried from one call to the next:
 * each builds its own context, re-reads its user ([bind]), and is refused once the user is disabled or loses the
 * standing the endpoint needs. Safe from a background thread. No cookie is read or kept.
 */
@Suppress("ConstPropertyName")
object InProcessCaller {
    /**
     * Calls [method] [path] -- an application path under the API root, `/clientAdmin/config/bundles` -- as [principal],
     * with [args] in the query and [body] as JSON. [appId] and [traceId] are what the request log names it by.
     */
    fun call(
        instanceConfig: KdrInstanceConfig,
        principal: InProcessPrincipal,
        method: HttpMethod,
        path: String,
        args: Map<String, Any?>? = null,
        body: Map<String, Any?>? = null,
        appId: String = defaultAppId,
        traceId: String? = null,
    ): InProcessResult {
        val root = (instanceConfig.get(ACFG.apiContextRoot) as? String) ?: ContextRoot.kda
        val headers = buildMap {
            put(RID.appIdHeader, listOf(appId))
            traceId?.let { put(RID.traceIdHeader, listOf(it)) }
        }
        val handler = RequestHandler(instanceConfig, method.name, "/$root$path", headers, LinkedHashMap())
        handler.queryStr = args?.let { HttpUtil.encodeArgs(it) } ?: ""
        handler.testPostData = body?.toJsonStr() ?: ""
        handler.inProcessPrincipal = principal
        handler.handleRequest()
        val envelope = handler.rptResponseData?.let { runCatching { it.jsonMap() }.getOrNull() }.orEmpty()
        return InProcessResult(handler.rptStatusCode, envelope, handler.rptResponseHeaders)
    }

    /**
     * Binds [principal]'s user to [cxt], as a key's owner is bound: read afresh, and refused (401) when gone, deleted,
     * disabled, or in a client this node does not carry. Its acting profile is the one a session would get -- in a
     * sandbox, what the sandbox's rules give it now -- and it is recorded on [cxt] as a key with no row ([ApiKeyAccess]
     * with the principal's scope), so every refusal a key gets applies. Called by [RequestService.extractAuth].
     */
    fun bind(cxt: KdrCxt, principal: InProcessPrincipal) {
        val users = UserService.getOrNull(cxt)
            ?: throw KdrException("This node has no users to act for.", code = EXC.authNeeded)
        val row = users.queryByUserId(cxt, principal.userId)
        if (row == null || row.isDeleted || !row.enabled) {
            throw KdrException("The user ${principal.keyId} acts for is gone or disabled.", code = EXC.authNeeded)
        }
        if (ClientService.getOrNull(cxt)?.isPresent(row.client) == false) {
            throw KdrException("The client '${row.client}' is not available here.", code = EXC.authNeeded)
        }
        cxt.bindToUserProfile(row.toActingProfile(cxt))
        cxt.apiKey = ApiKeyAccess(principal.keyId, AKEY.full, inProcess = principal.scope)
    }

    /** What a call names itself in the request log when its caller gives no other name. */
    const val defaultAppId = "inProcess"
}
