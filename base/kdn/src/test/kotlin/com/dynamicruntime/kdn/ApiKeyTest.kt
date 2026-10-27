package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.APK
import com.dynamicruntime.common.user.ApiKeyService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UT
import com.dynamicruntime.common.user.authTopic
import com.dynamicruntime.common.util.addDays
import com.dynamicruntime.common.util.addMonths
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.truncateToMs
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * API keys (issue #1402): made by a person for themselves or by an administrator for a user in their scope, sent as
 * `Authorization: Bearer`, honored as the owner until expired or revoked, read-only when made so, refused by the
 * endpoints only a signed-in session may call, and never writing a cookie. Over the shared instance, with clients of
 * this issue's own.
 */
class ApiKeyTest : StringSpec({
    val cxt = TestInstances.default("apiKey1402")

    fun defineClient(client: String) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("apiKey", client).also { it.userId = 14020L }, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    val mine = "keysmine1402"
    val theirs = "keystheirs1402"
    defineClient(mine)
    defineClient(theirs)

    /** A client that sends [key] as a program would: in the header, with no cookies. */
    fun keyed(key: String): TestHttpClient =
        TestHttpClient(cxt.instanceConfig).apply { setHeader(AKEY.authorizationHeader, "${AKEY.bearerScheme}$key") }

    fun bodyOf(handler: RequestHandler): Map<String, Any?> = handler.rptResponseData?.jsonMap() ?: emptyMap()

    fun setsCookie(handler: RequestHandler): Boolean = handler.rptResponseHeaders.keys.any { it.equals("Set-Cookie", ignoreCase = true) }

    /** Makes a key for [user] themselves, answering the whole key and its info. */
    fun makeKey(user: TestUser, name: String = "script", access: String = AKEY.read, days: Int? = null): Pair<String, Map<String, Any?>> {
        val body = buildMap {
            put(AKEY.name, name)
            put(AKEY.access, access)
            days?.let { put(AKEY.days, it) }
        }
        val created = user.postData(AKEY.selfCreate, body)
        return created[AKEY.secret].toOptStr().shouldNotBeNull() to created[AKEY.key].toJsonMapOrEmpty()
    }

    /**
     * Sets key [keyId]'s expiry directly, as a clock moving past it would find it. Stamped after the row's own
     * `updatedAt`, as the service's writes are: other specs move this shared instance's clock, so a row made while it
     * ran ahead would otherwise read as newer than this write, and the cache would keep it.
     */
    fun setExpiry(keyId: String, expiry: Instant) {
        val prior = ApiKeyService.get(cxt).queryKey(cxt, keyId, forWrite = true)?.get(PF.updatedAt).toOptInstant()
        val stamp = listOfNotNull(cxt.instanceNow(), prior?.plus(1.seconds)).max()
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = ApiKeyService.get(cxt).table(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "uApiKeyTestExpiry", table.columns,
            "update t:${UT.authApiKeys} set c:${AKEY.expiresAt} = :${AKEY.expiresAt}, c:${PF.updatedAt} = :${PF.updatedAt} " +
                "where c:${AKEY.keyId} = :${AKEY.keyId}",
        )
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(AKEY.expiresAt to expiry, PF.updatedAt to stamp, AKEY.keyId to keyId))
        }
    }

    fun secondsApart(a: Instant?, b: Instant): Long = abs((a.shouldNotBeNull() - b).inWholeSeconds)

    "a key acts as its owner, and sets no cookie" {
        val owner = TestUser.create(cxt, "owner@$mine.test", userClient = mine)
        val (key, info) = makeKey(owner)
        key shouldStartWith AKEY.prefix
        info[AKEY.userId] shouldBe owner.userId
        info[AKEY.status] shouldBe AKEY.live

        val answer = keyed(key).sendGetRequest(AEP.selfInfo)
        answer.rptStatusCode shouldBe 200
        bodyOf(answer)[EP.results].toJsonMapOrEmpty()[UPF.userId] shouldBe owner.userId
        setsCookie(answer) shouldBe false
    }

    "a read-only key reads, and is refused anything else" {
        val owner = TestUser.create(cxt, "reader@$mine.test", userClient = mine)
        val (key, _) = makeKey(owner, access = AKEY.read)
        keyed(key).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe 200
        val posted = keyed(key).sendEditRequest(AEP.profileSetName, null, mapOf(AFLD.name to "Pat"), HttpMethod.POST)
        posted.rptStatusCode shouldBe EXC.notAuthorized
        bodyOf(posted).toString() shouldContain "read-only"

        // A full key may do what its owner may.
        val (full, _) = makeKey(owner, name = "writer", access = AKEY.full)
        keyed(full).sendEditRequest(AEP.profileSetName, null, mapOf(AFLD.name to "Pat"), HttpMethod.POST).rptStatusCode shouldBe 200
    }

    "no key reaches what only a signed-in session may do" {
        val owner = TestUser.create(cxt, "session@$mine.test", userClient = mine)
        val (full, _) = makeKey(owner, access = AKEY.full)
        // Not even its own keys: a leaked key cannot make or extend more.
        keyed(full).sendGetRequest(AKEY.selfKeys).rptStatusCode shouldBe EXC.notAuthorized
        val minted = keyed(full).sendEditRequest(AKEY.selfCreate, null, mapOf(AKEY.name to "more"), HttpMethod.POST)
        minted.rptStatusCode shouldBe EXC.notAuthorized
        bodyOf(minted).toString() shouldContain "needs a signed-in session"
        // Nor end, or move, a session.
        keyed(full).sendGetRequest(AEP.logout).rptStatusCode shouldBe EXC.notAuthorized
        keyed(full).sendEditRequest(AEP.switchClient, null, mapOf(AFLD.client to mine), HttpMethod.POST).rptStatusCode shouldBe EXC.notAuthorized
    }

    "a wrong, revoked or expired key, or one whose owner is disabled, is refused the same way" {
        val owner = TestUser.create(cxt, "refused@$mine.test", userClient = mine)
        fun refusal(key: String): Pair<Int, String> {
            val answer = keyed(key).sendGetRequest(AEP.selfInfo)
            return answer.rptStatusCode to bodyOf(answer)[EP.errorMessage].toOptStr().orEmpty()
        }
        val (good, goodInfo) = makeKey(owner)
        val expected = EXC.authNeeded to "The API key is not valid."

        // A wrong secret, and a key nobody issued.
        refusal(good.dropLast(2) + "xx") shouldBe expected
        refusal("${AKEY.prefix}AAAAAAAAAAAA_nothing") shouldBe expected

        // Expired.
        val (expiring, expiringInfo) = makeKey(owner, name = "expiring")
        setExpiry(expiringInfo[AKEY.keyId].toOptStr()!!, cxt.now().addDays(-30))
        refusal(expiring) shouldBe expected

        // Revoked.
        owner.postData(AKEY.selfRevoke, mapOf(AKEY.keyId to goodInfo[AKEY.keyId]))[AKEY.status] shouldBe AKEY.revoked
        refusal(good) shouldBe expected

        // A disabled owner.
        val (third, _) = makeKey(owner, name = "third")
        refusal(third).first shouldBe 200
        val full = TestUser.createFullAdmin(cxt, "full@keys1402.test")
        full.postData(UADEP.userSetEnabled, mapOf(ADF.userId to owner.userId, ADF.enabled to false))
        refusal(third) shouldBe expected
    }

    "a key acts with its owner's roles as they are now" {
        val owner = TestUser.create(cxt, "demoted@$mine.test", level = ROLE.admin, userClient = mine)
        val (key, _) = makeKey(owner)
        keyed(key).sendGetRequest(UADEP.users).rptStatusCode shouldBe 200
        val full = TestUser.createFullAdmin(cxt, "demoter@keys1402.test")
        full.postData(UADEP.userSetRoles, mapOf(ADF.userId to owner.userId, ADF.roles to listOf(ROLE.user)))
        keyed(key).sendGetRequest(UADEP.users).rptStatusCode shouldBe EXC.notAuthorized
    }

    "a key lasts a year by default, and is extended by days or months from its expiry, or from today once past" {
        val owner = TestUser.create(cxt, "lifetime@$mine.test", userClient = mine)
        val (_, info) = makeKey(owner)
        val keyId = info[AKEY.keyId].toOptStr()!!
        val created = info[AKEY.expiresAt].toOptInstant()!!
        secondsApart(created, cxt.now().addDays(AKEY.defaultDays)) shouldBeLessThan 60

        val byMonths = owner.postData(AKEY.selfExtend, mapOf(AKEY.keyId to keyId, AKEY.months to 2))
        byMonths[AKEY.expiresAt].toOptInstant() shouldBe created.truncateToMs().addMonths(2)
        val byDays = owner.postData(AKEY.selfExtend, mapOf(AKEY.keyId to keyId, AKEY.days to 10))
        byDays[AKEY.expiresAt].toOptInstant() shouldBe created.truncateToMs().addMonths(2).addDays(10)

        // Expired: from today.
        setExpiry(keyId, cxt.now().addDays(-30))
        val revived = owner.postData(AKEY.selfExtend, mapOf(AKEY.keyId to keyId, AKEY.days to 5))
        secondsApart(revived[AKEY.expiresAt].toOptInstant(), cxt.now().addDays(5)) shouldBeLessThan 60
    }

    "a key's last use is kept when the key is extended or revoked" {
        val owner = TestUser.create(cxt, "lastused@$mine.test", userClient = mine)
        val (key, info) = makeKey(owner)
        val keyId = info[AKEY.keyId]
        fun lastUsed() = owner.getItems(AKEY.selfKeys).single { it[AKEY.keyId] == keyId }[AKEY.lastUsedAt]
        lastUsed() shouldBe null
        keyed(key).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe 200
        val used = lastUsed().shouldNotBeNull()
        owner.postData(AKEY.selfExtend, mapOf(AKEY.keyId to keyId, AKEY.days to 1))
        lastUsed() shouldBe used
        owner.postData(AKEY.selfRevoke, mapOf(AKEY.keyId to keyId))
        lastUsed() shouldBe used
    }

    "no expiry may be more than two years away, and a revoked key is not extended" {
        val owner = TestUser.create(cxt, "capped@$mine.test", userClient = mine)
        owner.expectError(EXC.badInput, AKEY.selfCreate, mapOf(AKEY.name to "long", AKEY.days to AKEY.defaultMaxDays + 1))
            .toString() shouldContain "at most ${AKEY.defaultMaxDays} days from today"
        val (_, info) = makeKey(owner, days = 700)
        val keyId = info[AKEY.keyId]
        owner.expectError(EXC.badInput, AKEY.selfExtend, mapOf(AKEY.keyId to keyId, AKEY.days to 100))
            .toString() shouldContain "at most ${AKEY.defaultMaxDays} days from today"
        owner.expectError(EXC.badInput, AKEY.selfExtend, mapOf(AKEY.keyId to keyId, AKEY.days to 1, AKEY.months to 1))
            .toString() shouldContain "not both"
        owner.postData(AKEY.selfRevoke, mapOf(AKEY.keyId to keyId))
        owner.expectError(EXC.badInput, AKEY.selfExtend, mapOf(AKEY.keyId to keyId, AKEY.days to 1))
            .toString() shouldContain "is revoked"
    }

    "a user holds at most ten keys that work" {
        val owner = TestUser.create(cxt, "many@$mine.test", userClient = mine)
        repeat(AKEY.maxLiveKeys) { makeKey(owner, name = "key $it") }
        owner.expectError(EXC.badInput, AKEY.selfCreate, mapOf(AKEY.name to "one more")).toString() shouldContain "revoke one"
        // A revoked key frees its place.
        val first = owner.getItems(AKEY.selfKeys).first()[AKEY.keyId]
        owner.postData(AKEY.selfRevoke, mapOf(AKEY.keyId to first))
        makeKey(owner, name = "replacement")
    }

    "only a hash of the secret is kept" {
        val owner = TestUser.create(cxt, "hashed@$mine.test", userClient = mine)
        val (key, info) = makeKey(owner)
        val row = ApiKeyService.get(cxt).queryKey(cxt, info[AKEY.keyId].toOptStr()!!).shouldNotBeNull()
        val secret = ApiKeyService.parseKey(key).shouldNotBeNull().second
        row[APK.secretHash] shouldBe ApiKeyService.hashOf(secret)
        row.values.none { it.toString().contains(secret) } shouldBe true
        // Nor is it listed again.
        owner.getItems(AKEY.selfKeys).none { it.toString().contains(secret) } shouldBe true
    }

    "an administrator makes a key for a user in their client, which acts as that user and says who made it" {
        val admin = TestUser.create(cxt, "admin@$mine.test", level = ROLE.admin, userClient = mine)
        val member = TestUser.create(cxt, "member@$mine.test", userClient = mine)
        val created = admin.postData(AKEY.adminCreate, mapOf(AKEY.userId to member.userId, AKEY.name to "for member"))
        val key = created[AKEY.secret].toOptStr().shouldNotBeNull()
        val info = created[AKEY.key].toJsonMapOrEmpty()
        info[AKEY.userId] shouldBe member.userId
        info[AKEY.createdBy] shouldBe admin.userId
        bodyOf(keyed(key).sendGetRequest(AEP.selfInfo))[EP.results].toJsonMapOrEmpty()[UPF.userId] shouldBe member.userId

        // The member sees it, and who made it.
        val listed = member.getItems(AKEY.selfKeys).single { it[AKEY.keyId] == info[AKEY.keyId] }
        listed[AKEY.createdByName] shouldBe "admin@$mine.test"
        // The administrator's listing names its owner, and narrows to one user.
        val mineListed = admin.getItems(AKEY.adminKeys, mapOf(AKEY.userId to member.userId))
        mineListed.map { it[AKEY.keyId] } shouldContainExactly listOf(info[AKEY.keyId])
        mineListed.single()[AKEY.ownerAddress] shouldBe "member@$mine.test"

        // Extend and revoke it, as its administrator.
        admin.postData(AKEY.adminExtend, mapOf(AKEY.keyId to info[AKEY.keyId], AKEY.months to 1))[AKEY.ownerAddress] shouldBe "member@$mine.test"
        admin.postData(AKEY.adminRevoke, mapOf(AKEY.keyId to info[AKEY.keyId]))[AKEY.status] shouldBe AKEY.revoked
    }

    "an administrator reaches only the users in their scope, and cannot make a key that acts above them" {
        val admin = TestUser.create(cxt, "scoped@$mine.test", level = ROLE.admin, userClient = mine)
        val outsider = TestUser.create(cxt, "outsider@$theirs.test", userClient = theirs)
        admin.expectError(EXC.notFound, AKEY.adminCreate, mapOf(AKEY.userId to outsider.userId, AKEY.name to "no"))
        val (outsiderKey, outsiderInfo) = makeKey(outsider)
        outsiderKey.isNotEmpty() shouldBe true
        admin.expectError(EXC.notFound, AKEY.adminRevoke, mapOf(AKEY.keyId to outsiderInfo[AKEY.keyId]))
        admin.getItems(AKEY.adminKeys).none { it[AKEY.keyId] == outsiderInfo[AKEY.keyId] } shouldBe true

        // A user of their own client who holds what they do not.
        val wider = TestUser.create(cxt, "wider@$mine.test", level = ROLE.admin, capabilities = listOf(ROLE.allClients), userClient = mine)
        admin.expectError(EXC.badInput, AKEY.adminCreate, mapOf(AKEY.userId to wider.userId, AKEY.name to "up"))
            .toString() shouldContain "'${ROLE.allClients}'"
    }

    "an administrator confined to an organization lists only its users' keys" {
        val full = TestUser.createFullAdmin(cxt, "orgs@keys1402.test")
        val confined = TestUser.create(cxt, "north-admin@$mine.test", level = ROLE.admin, userClient = mine)
        val north = TestUser.create(cxt, "north@$mine.test", userClient = mine)
        val south = TestUser.create(cxt, "south@$mine.test", userClient = mine)
        for ((user, org) in listOf(confined to "north", north to "north", south to "south")) {
            full.postData(UADEP.userSetOrg, mapOf(ADF.userId to user.userId, ADF.org to org))
        }
        val (_, northKey) = makeKey(north)
        val (_, southKey) = makeKey(south)
        val listed = confined.getItems(AKEY.adminKeys).map { it[AKEY.keyId] }
        (northKey[AKEY.keyId] in listed) shouldBe true
        (southKey[AKEY.keyId] in listed) shouldBe false
        confined.expectError(EXC.notFound, AKEY.adminRevoke, mapOf(AKEY.keyId to southKey[AKEY.keyId]))
    }

    "a public self-administrator manages their own keys, not as an administrator" {
        val person = TestUser.create(cxt, "person@keys1402public.test")
        makeKey(person)
        person.getItems(AKEY.selfKeys).size shouldBe 1
        person.expectError(EXC.notAuthorized, AKEY.adminKeys)
    }

    "a listing of one's own keys says what a create may ask for" {
        val owner = TestUser.create(cxt, "limits@$mine.test", userClient = mine)
        makeKey(owner)
        val page = owner.client.sendJsonGetRequest(AKEY.selfKeys)
        val summary = page[EP.summary].toJsonMapOrEmpty()
        summary[AKEY.days] shouldBe AKEY.defaultDays.toLong()
        summary[AKEY.maxDays] shouldBe AKEY.defaultMaxDays.toLong()
        summary[AKEY.liveCount] shouldBe 1L
        page[EP.items].toJsonListOfMaps().size shouldBe 1
    }
})
