package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.userKeyLabel
import com.dynamicruntime.common.util.jsonResult
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr

/*
 * Becoming someone on a test instance (issue #1183) -- the `becomeUser` fixture in the UI, for a caller with test
 * access. The pure half: the deployment admin's request, what a recent identity keeps and how it is read back, and its
 * label. Covered by `BecomeUserTest` under `jsNodeTest`.
 */

object BecomeUserApi {
    /** The fixture endpoint, from the catalog, so its form is drawn from the endpoint's own schema. */
    suspend fun fetchForm(): Catalog = SchemaCatalogApi.fetchEndpoint(HttpMethod.POST.name, TEP.becomeUser)

    /** Signs this browser in as the user [body] describes: the `becomeUser` fixture. */
    suspend fun become(body: Map<String, Any?>): ApiResult<Map<String, Any?>> =
        Http.sendApiResult(HttpMethod.POST.name, TEP.becomeUser, body)
}

/** The deployment admin's request (issue #1183): `admin` plus `allClients`, as [TEP.deploymentAdminEmail]. */
fun deploymentAdminBody(): Map<String, Any?> =
    mapOf(TEP.email to TEP.deploymentAdminEmail, TEP.level to ROLE.admin, TEP.capabilities to listOf(ROLE.allClients))

/** How many identities this browser remembers. */
const val recentIdentitiesLimit = 8

/**
 * A user this browser became (issue #1183), kept so becoming them again is one click: the address, and the client,
 * persona and personaSuffix that pick which of its users -- what a `becomeUser` call needs to find them again -- with
 * the name for the label, the data they belong to ([dataId], as the Simulations page keeps its runs, #1099) and when.
 */
class RecentIdentity(
    val email: String,
    val client: String,
    val persona: String,
    val personaSuffix: String,
    val name: String?,
    val dataId: String?,
    val at: String,
) {
    /** What makes two of these the same user: the address and which of its users. */
    val identityKey: String get() = "$email|$client|$persona|$personaSuffix"
}

/**
 * The identity [body] asked for, as the fixture's reply [userInfo] says it landed: the address and personaSuffix from
 * the request (the reply carries neither), the client, persona and name from the user it became.
 */
fun recentIdentityOf(body: Map<String, Any?>, userInfo: Map<String, Any?>, dataId: String?, at: String): RecentIdentity =
    RecentIdentity(
        email = body[TEP.email].toOptStr().orEmpty(),
        client = userInfo[UPF.client].toOptStr().orEmpty(),
        persona = userInfo[UPF.persona].toOptStr() ?: PERSONA.member,
        personaSuffix = body[TEP.personaSuffix].toOptStr().orEmpty(),
        name = userInfo[UPF.name].toOptStr()?.ifBlank { null },
        dataId = dataId,
        at = at,
    )

/** [identity] at the front of [prior], any earlier copy of the same user dropped, at most [recentIdentitiesLimit]. */
fun rememberIdentity(prior: List<RecentIdentity>, identity: RecentIdentity): List<RecentIdentity> =
    (listOf(identity) + prior.filter { it.identityKey != identity.identityKey }).take(recentIdentitiesLimit)

/** [identities] without the one under [key] (issue #1503), the rest as they were. Forgets it; the user is untouched. */
fun forgetIdentity(identities: List<RecentIdentity>, key: String): List<RecentIdentity> =
    identities.filter { it.identityKey != key }

/** The request that becomes one user: the address and which of its users. */
private fun becomeBody(email: String, client: String, persona: String, personaSuffix: String): Map<String, Any?> = buildMap {
    put(TEP.email, email)
    if (client.isNotEmpty()) put(TEP.client, client)
    put(TEP.persona, persona)
    if (personaSuffix.isNotEmpty()) put(TEP.personaSuffix, personaSuffix)
}

/** The request that becomes [identity] again. */
fun becomeAgainBody(identity: RecentIdentity): Map<String, Any?> =
    becomeBody(identity.email, identity.client, identity.persona, identity.personaSuffix)

/**
 * The request that becomes [user] from the Users page (issue #1511): that one of its address's users, by its whole key,
 * and refused if it has gone since the page read it, rather than a new user made in its place.
 */
fun becomeUserBody(user: AdminUser): Map<String, Any?> =
    becomeBody(user.primaryId, user.client, user.persona, user.personaSuffix) + (TEP.failIfUserMissing to true)

/**
 * Whether the Users page offers to become [user] (issue #1511): to a caller with test access ([testAccess]), for whom
 * the fixture exists, and not on their own user ([callerUserId]), a user who could not sign in (disabled, deleted, or
 * in a client this node does not carry, [carried] false), or a sandbox user somebody opened, who is reached by opening
 * the sandbox. Pure, covered under `jsNodeTest`.
 */
fun becomeOffered(user: AdminUser, testAccess: Boolean, callerUserId: Long?, carried: Boolean): Boolean =
    testAccess && user.userId != callerUserId && user.enabled && !user.deleted && !user.opened && carried

/** Whether becoming [user] would claim it (issue #1511), as the fixture does, which ends its invitation, so it asks first. */
fun becomeClaims(user: AdminUser): Boolean = !user.registered

/** How a remembered identity is shown: `pat@acme.test · acme / Member B -- Pat Lee`. */
fun identityLabel(identity: RecentIdentity): String =
    "${identity.email} \u00b7 ${userKeyLabel(identity.client, identity.persona, identity.personaSuffix, identity.name)}"

/**
 * [identities] split into the ones from the data the node serves now and the ones from data since reset, whose users
 * are gone -- as [recentRunsFor] splits runs (#1099). A node that serves no data id makes every identity current.
 */
fun identitiesFor(identities: List<RecentIdentity>, dataId: String?): Pair<List<RecentIdentity>, List<RecentIdentity>> =
    if (dataId == null) identities to emptyList() else identities.partition { it.dataId == dataId }

private const val ridEmail = "email"
private const val ridClient = "client"
private const val ridPersona = "persona"
private const val ridPersonaSuffix = "personaSuffix"
private const val ridName = "name"
private const val ridDataId = "dataId"
private const val ridAt = "at"

/** [identities] as the text `localStorage` keeps. */
fun encodeIdentities(identities: List<RecentIdentity>): String = identities.map {
    mapOf(
        ridEmail to it.email, ridClient to it.client, ridPersona to it.persona, ridPersonaSuffix to it.personaSuffix,
        ridName to it.name, ridDataId to it.dataId, ridAt to it.at,
    )
}.toJsonStr(compact = true)

/** What [encodeIdentities] wrote, or nothing for text that is absent or does not parse; an entry with no address is dropped. */
fun decodeIdentities(text: String?): List<RecentIdentity> {
    val list = text?.jsonResult()?.valueOrNull() ?: return emptyList()
    return list.toJsonListOfMaps().mapNotNull { m ->
        RecentIdentity(
            email = m[ridEmail].toOptStr() ?: return@mapNotNull null,
            client = m[ridClient].toOptStr().orEmpty(),
            persona = m[ridPersona].toOptStr() ?: PERSONA.member,
            personaSuffix = m[ridPersonaSuffix].toOptStr().orEmpty(),
            name = m[ridName].toOptStr(),
            dataId = m[ridDataId].toOptStr(),
            at = m[ridAt].toOptStr().orEmpty(),
        )
    }
}
