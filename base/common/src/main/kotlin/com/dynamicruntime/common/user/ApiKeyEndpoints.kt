package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.getReqLong
import com.dynamicruntime.common.util.getReqStr
import com.dynamicruntime.common.util.toOptLong

/**
 * The API-key endpoints (issue #1402), on two surfaces over [ApiKeyService]:
 * - **A person's own**, in the login-gated `user` section: list, make, extend, change the access of, and revoke the
 *   signed-in user's keys.
 * - **An administrator's**, in the scoped `clientAdmin` section: every key the caller may see ([ReadScopeRules.forCaller]),
 *   and any user's in their scope ([ReadScopeRules.forUserAdmin]); a user outside it reads as absent (404).
 *
 * Every one is [session-only][com.dynamicruntime.common.endpoint.KdrEndpoint.sessionOnly]: a key cannot make, extend or
 * revoke keys, so one that leaks reaches no further, and lasts no longer, than its maker gave it.
 */
fun apiKeySchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.apiKey") {
    ApiKeyInfo.defineInfoTypes(this)

    // --- a person's own keys ---

    listEndpoint(
        AKEY.selfKeys, "Lists the signed-in user's API keys, revoked and expired ones included, newest first.",
        outputRef = ApiKeyInfo.infoTypeName, noLimit = true, hasMore = true, hasNumAvailable = true,
        summaryRef = ApiKeyInfo.limitsTypeName, sessionOnly = true,
    ) { c, _ ->
        val profile = c.userProfile
        val keys = ApiKeyService.get(c)
        val rows = keys.keysOf(c, profile.client, profile.userId)
        val now = c.now()
        val creators = creatorNamesOf(c, rows, profile.userId, null)
        val items = rows.map { keys.infoOf(it, creatorName = creators[it[PF.createdBy].toOptLong()]).toInfo(now) }
        ListPage(items, items.size, hasMore = false, summary = limitsOf(c, keys.liveCount(c, rows)))
    }
    generalEndpoint(
        AKEY.selfCreate, "Makes an API key for the signed-in user. The answer carries the whole key, the one time it is sent.",
        HttpMethod.POST, outputRef = ApiKeyInfo.createdTypeName, inputFields = { createFields() }, sessionOnly = true,
    ) { c, req ->
        val owner = UserService.get(c).queryByUserId(c, c.userProfile.userId)
            ?: throw KdrException("The current user could not be found.", code = EXC.notFound)
        createdOf(c, ApiKeyService.get(c).create(c, owner, req.getOptStr(AKEY.name), req.getOptStr(AKEY.access), optInt(req, AKEY.days)))
    }
    generalEndpoint(
        AKEY.selfExtend, "Extends one of the signed-in user's API keys by days or by months.",
        HttpMethod.POST, outputRef = ApiKeyInfo.infoTypeName, inputFields = { extendFields() }, sessionOnly = true,
    ) { c, req ->
        val row = ownKey(c, req.getReqStr(AKEY.keyId))
        ApiKeyService.get(c).extend(c, row, optInt(req, AKEY.days), optInt(req, AKEY.months)).toInfo(c.now())
    }
    generalEndpoint(
        AKEY.selfAccess, "Makes one of the signed-in user's API keys read-only, or gives it full access.",
        HttpMethod.POST, outputRef = ApiKeyInfo.infoTypeName, inputFields = { accessFields() }, sessionOnly = true,
    ) { c, req ->
        val row = ownKey(c, req.getReqStr(AKEY.keyId))
        ApiKeyService.get(c).setAccess(c, row, req.getReqStr(AKEY.access)).toInfo(c.now())
    }
    generalEndpoint(
        AKEY.selfRevoke, "Revokes one of the signed-in user's API keys, at once and for good.",
        HttpMethod.POST, outputRef = ApiKeyInfo.infoTypeName, inputFields = { keyIdField() }, sessionOnly = true,
    ) { c, req ->
        ApiKeyService.get(c).revoke(c, ownKey(c, req.getReqStr(AKEY.keyId))).toInfo(c.now())
    }

    // --- an administrator's ---

    listEndpoint(
        AKEY.adminKeys, "Lists the API keys of the users the caller administers, soonest to expire first.",
        outputRef = ApiKeyInfo.infoTypeName, hasMore = true, summaryRef = ApiKeyInfo.limitsTypeName, sessionOnly = true,
        inputFields = {
            field(AKEY.userId, "Only this user's keys.") { type = SCT.integer }
            field(AKEY.status, "Only keys with this status.") { for (s in AKEY.statuses) option(s) }
        },
    ) { c, req ->
        refusePublicAdmin(c)
        val keys = ApiKeyService.get(c)
        val scope = ReadScopeRules.forCaller(c)
        val userId = req[AKEY.userId].toOptLong()
        val now = c.now()
        val status = req.getOptStr(AKEY.status)
        val limit = (req[EP.limit] as? Number)?.toInt() ?: defaultListLimit
        val (page, total) = keys.listScoped(c, scope, userId, limit) { status == null || keys.infoOf(it).status(now) == status }
        val creators = creatorNamesOf(c, page.map { it.row }, null, scope)
        val items = page.map { keys.infoOf(it.row, it.owner, creators[it.row[PF.createdBy].toOptLong()]).toInfo(now) }
        // One user's listing says how many of their keys still work, which is what a create is held to.
        val liveCount = userId?.let { id -> keys.listScoped(c, scope, id, AKEY.maxLiveKeys + 1) { keys.infoOf(it).isUsable(now) }.second }
        ListPage(items, total, hasMore = total > limit, summary = limitsOf(c, liveCount))
    }
    generalEndpoint(
        AKEY.adminCreate,
        "Makes an API key for a user the caller administers. The key acts as that user, never as the caller; the answer " +
            "carries the whole key, the one time it is sent.",
        HttpMethod.POST, outputRef = ApiKeyInfo.createdTypeName, sessionOnly = true,
        inputFields = {
            field(AKEY.userId, "The user the key acts as.", required = true) { type = SCT.integer }
            createFields()
        },
    ) { c, req ->
        refusePublicAdmin(c)
        val owner = UserService.get(c).queryAdministrableUser(c, req.getReqLong(AKEY.userId), ReadScopeRules.forUserAdmin(c))
            ?: throw KdrException("No user ${req.getReqLong(AKEY.userId)} is within your administration.", code = EXC.notFound)
        requireMayActAs(c, owner)
        createdOf(c, ApiKeyService.get(c).create(c, owner, req.getOptStr(AKEY.name), req.getOptStr(AKEY.access), optInt(req, AKEY.days)))
    }
    generalEndpoint(
        AKEY.adminExtend, "Extends the API key of a user the caller administers, by days or by months.",
        HttpMethod.POST, outputRef = ApiKeyInfo.infoTypeName, inputFields = { extendFields() }, sessionOnly = true,
    ) { c, req ->
        refusePublicAdmin(c)
        val (row, owner) = administeredKey(c, req.getReqStr(AKEY.keyId))
        ApiKeyService.get(c).extend(c, row, optInt(req, AKEY.days), optInt(req, AKEY.months)).withOwner(owner).toInfo(c.now())
    }
    generalEndpoint(
        AKEY.adminAccess,
        "Makes the API key of a user the caller administers read-only, or gives it full access. Giving it full access " +
            "is held to what making a key for that user is.",
        HttpMethod.POST, outputRef = ApiKeyInfo.infoTypeName, inputFields = { accessFields() }, sessionOnly = true,
    ) { c, req ->
        refusePublicAdmin(c)
        val (row, owner) = administeredKey(c, req.getReqStr(AKEY.keyId))
        val access = req.getReqStr(AKEY.access)
        // Widening hands an existing secret more than it had, which is acting as the owner as making a key is.
        // Narrowing only takes away, as a revoke does.
        if (access == AKEY.full) requireMayActAs(c, owner)
        ApiKeyService.get(c).setAccess(c, row, access).withOwner(owner).toInfo(c.now())
    }
    generalEndpoint(
        AKEY.adminRevoke, "Revokes the API key of a user the caller administers, at once and for good.",
        HttpMethod.POST, outputRef = ApiKeyInfo.infoTypeName, inputFields = { keyIdField() }, sessionOnly = true,
    ) { c, req ->
        refusePublicAdmin(c)
        val (row, owner) = administeredKey(c, req.getReqStr(AKEY.keyId))
        ApiKeyService.get(c).revoke(c, row).withOwner(owner).toInfo(c.now())
    }
}

/** [this] info as an administrator's answer gives it: with the owner's name and address. */
private fun ApiKeyInfo.withOwner(owner: AuthUserRow): ApiKeyInfo = copy(ownerName = owner.name, ownerAddress = owner.primaryId)

/** How many keys an administrator's listing shows when the request names no `limit`. */
private const val defaultListLimit = 200

private fun InputFieldsBuilder.keyIdField() {
    field(AKEY.keyId, "The key's public id.", required = true)
}

private fun InputFieldsBuilder.createFields() {
    field(AKEY.name, "What the key is for: shown wherever the key is listed.", required = true) { maxLength = AKEY.maxNameLength }
    field(AKEY.access, "'${AKEY.read}' (GET requests only, the default) or '${AKEY.full}'.") {
        for (a in AKEY.accesses) option(a)
    }
    field(AKEY.days, "How many days the key lasts; the deployment's default (one year) when absent.") {
        type = SCT.integer
        minimum = 1
    }
}

private fun InputFieldsBuilder.accessFields() {
    keyIdField()
    field(AKEY.access, "'${AKEY.read}' (GET requests only) or '${AKEY.full}'.", required = true) {
        for (a in AKEY.accesses) option(a)
    }
}

private fun InputFieldsBuilder.extendFields() {
    keyIdField()
    field(AKEY.days, "Days to add to the key's expiry (or to today, once it has passed). Exactly one of days and months.") {
        type = SCT.integer
        minimum = 1
    }
    field(AKEY.months, "Calendar months to add to the key's expiry (or to today, once it has passed).") {
        type = SCT.integer
        minimum = 1
    }
}

private fun optInt(req: Map<String, Any?>, key: String): Int? = req[key].toOptLong()?.toInt()

private fun createdOf(c: KdrCxt, created: ApiKeyCreated): Map<String, Any?> =
    mapOf(AKEY.key to created.info.toInfo(c.now()), AKEY.secret to created.secret)

/** What a create or an extension may ask for here, and -- for one user's listing -- how many of their keys work. */
private fun limitsOf(c: KdrCxt, liveCount: Int?): Map<String, Any?> = buildMap {
    val maxDays = ApiKeyLifetime.maxDays(c)
    put(AKEY.days, ApiKeyLifetime.days(c))
    put(AKEY.maxDays, maxDays)
    put(AKEY.latestExpiry, ApiKeyRules.latestExpiry(c.now(), maxDays))
    put(AKEY.maxLive, AKEY.maxLiveKeys)
    liveCount?.let { put(AKEY.liveCount, it) }
}

/** The signed-in user's key [keyId], read to be changed; another user's, like a key nobody issued, is a 404. */
private fun ownKey(c: KdrCxt, keyId: String): Map<String, Any?> {
    val row = ApiKeyService.get(c).queryKey(c, keyId, forWrite = true)
    if (row == null || row[PF.userId].toOptLong() != c.userProfile.userId || row[PF.client] != c.userProfile.client) {
        throw KdrException("You have no API key '$keyId'.", code = EXC.notFound)
    }
    return row
}

/** The key [keyId], read to be changed, and its owner, when the owner is within the caller's administration; otherwise a 404. */
private fun administeredKey(c: KdrCxt, keyId: String): Pair<Map<String, Any?>, AuthUserRow> {
    val absent = KdrException("No API key '$keyId' is within your administration.", code = EXC.notFound)
    val row = ApiKeyService.get(c).queryKey(c, keyId, forWrite = true) ?: throw absent
    val owner = UserService.get(c).queryAdministrableUser(c, row[PF.userId].toOptLong() ?: throw absent, ReadScopeRules.forUserAdmin(c))
        ?: throw absent
    if (owner.client != row[PF.client]) throw absent
    return row to owner
}

/**
 * Refuses a `public` self-administrator the administrator's surface: they administer only their own users, and manage
 * their own keys from their profile (the self endpoints), as the configuration endpoints refuse them.
 */
private fun refusePublicAdmin(c: KdrCxt) {
    if (c.userProfile.client == CL.public) {
        throw KdrException("API keys are managed from your profile here, not by an administrator.", code = EXC.notAuthorized)
    }
}

/**
 * Refuses a key for [owner] that would let the caller act as somebody with authority of their own. A key's secret goes
 * to whoever makes it, so making one for somebody is acting as them, and two things follow:
 * - **Every role [owner] holds** -- a level by the ladder, a capability outright -- must be one the caller holds too, as
 *   granting a role asks.
 * - **Another administrator's keys are their own to make.** An administrator acting as a peer could do what only that
 *   peer may: approve a form as a reviewer who is not its owner, defeating the second-person rule (#787), since a
 *   reviewer is an administrator with a label, and labels are not roles. A user below the administrator level holds no
 *   authority to borrow, so their administrator may make their keys -- what keys are for, a program working as them.
 */
private fun requireMayActAs(c: KdrCxt, owner: AuthUserRow) {
    if (owner.userId != c.userProfile.userId && RoleLadder.satisfies(owner.roles.toSet(), ROLE.admin)) {
        throw KdrException.mkInput(
            "User ${owner.userId} is an administrator, whose API keys are theirs to make, from their own profile.",
        )
    }
    val held = c.userProfile.roles
    val beyond = owner.roles.filterNot { RoleLadder.satisfies(held, it) }
    if (beyond.isNotEmpty()) {
        throw KdrException.mkInput(
            "You cannot make a key for user ${owner.userId}: it would act with ${beyond.joinToString(", ") { "'$it'" }}, " +
                "which you do not hold.",
        )
    }
}

/**
 * The names of the users who made [rows]' keys, by id, for those made by somebody other than the owner. [scope] limits
 * whom an administrator's listing names, as it limits whom they see; a person's own listing ([ownerId]) names its
 * makers whoever they are, since an administrator who made a key for you made it in your name.
 */
private fun creatorNamesOf(c: KdrCxt, rows: List<Map<String, Any?>>, ownerId: Long?, scope: ReadScope?): Map<Long, String> {
    val ids = rows.mapNotNull { row -> row[PF.createdBy].toOptLong()?.takeIf { it != row[PF.userId].toOptLong() } }.toSet()
    if (ids.isEmpty()) return emptyMap()
    val users = UserService.get(c).queryUsersByIds(c, ids, scope ?: ReadScope.unrestricted)
    return users.mapValues { (_, u) -> u.name ?: u.primaryId }.filterKeys { ownerId == null || it != ownerId }
}
