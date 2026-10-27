package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
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
import com.dynamicruntime.common.user.ATOK
import com.dynamicruntime.common.user.AccessTokenService
import com.dynamicruntime.common.user.ApiKeyService
import com.dynamicruntime.common.user.Es256
import com.dynamicruntime.common.user.JWS
import com.dynamicruntime.common.user.JwsParts
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UT
import com.dynamicruntime.common.user.authTopic
import com.dynamicruntime.common.util.base64Decode
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Access tokens (issue #1403): an API key exchanged for a signed JWT that any service verifies with the published keys
 * alone, honored here as its key is -- its rules, its revocation, its owner -- until it expires, and through a rotation
 * of the key that signed it. Over the shared instance, with a client of this issue's own.
 */
class AccessTokenTest : StringSpec({
    val cxt = TestInstances.default("accessToken1403")

    val mine = "tokens1403"
    val config = gedraConfig(cxt, "${mine}cfg", clientNamespace(mine), mine) {
        defineClient(
            ClientDef(
                clientId = mine, name = mine, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("accessToken", mine).also { it.userId = 14030L }, config)
    GedraConfigReload.reloadClient(cxt, mine)

    /** A client sending [credential] as a program would: in the header, with no cookies. */
    fun bearer(credential: String): TestHttpClient =
        TestHttpClient(cxt.instanceConfig).apply { setHeader(AKEY.authorizationHeader, "${AKEY.bearerScheme}$credential") }

    fun bodyOf(handler: RequestHandler): Map<String, Any?> = handler.rptResponseData?.jsonMap() ?: emptyMap()

    fun makeKey(user: TestUser, name: String = "script", access: String = AKEY.read): Pair<String, String> {
        val created = user.postData(AKEY.selfCreate, mapOf(AKEY.name to name, AKEY.access to access))
        return created[AKEY.secret].toOptStr().shouldNotBeNull() to created[AKEY.key].toJsonMapOrEmpty()[AKEY.keyId].toOptStr()!!
    }

    /** Exchanges [key] for a token as an OAuth client library would, answering the token answer. */
    fun exchange(key: String): Map<String, Any?> {
        val answer = bearer(key).sendFormRequest(ATOK.token, mapOf(ATOK.grantType to ATOK.clientCredentials))
        answer.rptStatusCode shouldBe 200
        return bodyOf(answer)
    }

    fun tokenOf(key: String): String = exchange(key)[ATOK.accessToken].toOptStr().shouldNotBeNull()

    fun claimsOf(token: String): Map<String, Any?> = JwsParts.parse(token).shouldNotBeNull().claims

    fun setName(credential: String): Int =
        bearer(credential).sendEditRequest(AEP.profileSetName, null, mapOf(AFLD.name to "Pat"), HttpMethod.POST).rptStatusCode

    fun whoIs(credential: String): Long? =
        bodyOf(bearer(credential).sendGetRequest(AEP.selfInfo))[EP.results].toJsonMapOrEmpty()[UPF.userId].toOptLong()

    /**
     * Whether [token]'s signature verifies with the key the published set names for it, and nothing else of this
     * deployment's: the public key is built from the JWK's own coordinates, as a service elsewhere would build it.
     */
    fun verifiesWithJwksAlone(token: String): Boolean {
        val jwks = bodyOf(TestHttpClient(cxt.instanceConfig).sendGetRequest(ATOK.jwks))
        val parts = JwsParts.parse(token).shouldNotBeNull()
        val jwk = jwks[ATOK.keys].toJsonListOfMaps().single { it[JWS.kid] == parts.header[JWS.kid] }
        jwk[JWS.kty] shouldBe JWS.ec
        jwk[JWS.crv] shouldBe JWS.p256
        jwk[JWS.alg] shouldBe JWS.es256
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, jwk[JWS.x].toOptStr()!!.base64Decode()), BigInteger(1, jwk[JWS.y].toOptStr()!!.base64Decode()))
        val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        return Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(key)
            update(parts.signingInput)
            verify(parts.signature)
        }
    }

    /** [token] with its claims changed by [change] and its signature kept: what a forger without the key can make. */
    fun tampered(token: String, change: (MutableMap<String, Any?>) -> Unit): String {
        val segments = token.split(".")
        val claims = claimsOf(token).toMutableMap().also(change)
        return "${segments[0]}.${claims.toJsonStr(compact = true).toByteArray().base64Encode()}.${segments[2]}"
    }

    "a key is exchanged for a token that verifies with the published keys alone" {
        val owner = TestUser.create(cxt, "exchange@$mine.test", userClient = mine)
        val (key, keyId) = makeKey(owner)
        val answered = bearer(key).sendFormRequest(ATOK.token, mapOf(ATOK.grantType to ATOK.clientCredentials))
        answered.rptStatusCode shouldBe 200
        // The answer is the body, as RFC 6749 §5.1 has it, and nothing between here and the program keeps a copy.
        val answer = bodyOf(answered)
        answer.keys shouldBe setOf(ATOK.accessToken, ATOK.tokenType, ATOK.expiresIn, ATOK.scope)
        answer[ATOK.tokenType] shouldBe ATOK.bearer
        answer[ATOK.scope] shouldBe AKEY.read
        answer[ATOK.expiresIn].toOptLong()!! shouldBeInRange (24 * 3600L - 5)..(24 * 3600L)
        answered.rptResponseHeaders["cache-control"] shouldBe listOf("no-store")

        val token = answer[ATOK.accessToken].toOptStr()!!
        verifiesWithJwksAlone(token) shouldBe true
        val parts = JwsParts.parse(token)!!
        parts.header[JWS.alg] shouldBe JWS.es256
        parts.header[JWS.typ] shouldBe JWS.accessTokenType
        val claims = parts.claims
        val issuer = AccessTokenService.issuerOf(cxt)
        claims[JWS.iss] shouldBe issuer
        claims[JWS.aud] shouldBe issuer
        claims[JWS.sub] shouldBe owner.userId.toString()
        claims[AccessTokenService.clientClaim] shouldBe mine
        claims[JWS.scope] shouldBe AKEY.read
        claims[JWS.clientId] shouldBe keyId
        (claims[JWS.exp].toOptLong()!! - claims[JWS.iat].toOptLong()!!) shouldBe 24 * 3600L
        // A forger who changes a claim cannot sign it again.
        verifiesWithJwksAlone(tampered(token) { it[JWS.sub] = (owner.userId + 1000).toString() }) shouldBe false

        // JSON is taken too, for a caller that is not a client library.
        val json = bearer(key).sendEditRequest(ATOK.token, null, mapOf(ATOK.grantType to ATOK.clientCredentials), HttpMethod.POST)
        json.rptStatusCode shouldBe 200
        bodyOf(json)[ATOK.accessToken].toOptStr().shouldNotBeNull() shouldNotBe token
    }

    "only a key is exchanged, and only by the client-credentials grant" {
        val owner = TestUser.create(cxt, "grant@$mine.test", userClient = mine)
        val (key, _) = makeKey(owner)
        // No key: neither anonymous, nor a signed-in session.
        TestHttpClient(cxt.instanceConfig).sendFormRequest(ATOK.token, mapOf(ATOK.grantType to ATOK.clientCredentials))
            .rptStatusCode shouldBe EXC.authNeeded
        owner.client.sendFormRequest(ATOK.token, mapOf(ATOK.grantType to ATOK.clientCredentials)).rptStatusCode shouldBe EXC.authNeeded
        // Another grant.
        bearer(key).sendFormRequest(ATOK.token, mapOf(ATOK.grantType to "password")).rptStatusCode shouldBe EXC.badInput
        // A token buying the next would make a token live forever.
        bearer(tokenOf(key)).sendFormRequest(ATOK.token, mapOf(ATOK.grantType to ATOK.clientCredentials))
            .rptStatusCode shouldBe EXC.notAuthorized
    }

    "a form-encoded body is read for the exchange alone" {
        val owner = TestUser.create(cxt, "form@$mine.test", userClient = mine)
        // A page elsewhere could post this form at a signed-in session: it is not read, so the name it requires is missing,
        // and no key is made.
        owner.client.sendFormRequest(AKEY.selfCreate, mapOf(AKEY.name to "forged")).rptStatusCode shouldBe EXC.badInput
        owner.getItems(AKEY.selfKeys).none { it[AKEY.name] == "forged" } shouldBe true
    }

    "a token acts as its key's owner, with its key's rules" {
        val owner = TestUser.create(cxt, "rules@$mine.test", userClient = mine)
        val (readKey, readKeyId) = makeKey(owner)
        val read = tokenOf(readKey)
        whoIs(read) shouldBe owner.userId
        val answered = bearer(read).sendGetRequest(AEP.selfInfo)
        answered.rptResponseHeaders.keys.none { it.equals("Set-Cookie", ignoreCase = true) } shouldBe true
        setName(read) shouldBe EXC.notAuthorized
        bearer(read).sendGetRequest(AKEY.selfKeys).rptStatusCode shouldBe EXC.notAuthorized

        val (fullKey, _) = makeKey(owner, name = "writer", access = AKEY.full)
        val full = tokenOf(fullKey)
        claimsOf(full)[JWS.scope] shouldBe AKEY.full
        setName(full) shouldBe 200

        // A key narrowed since its token was issued narrows the token with it; widened, the token stays as it was issued.
        val (narrowedKey, narrowedId) = makeKey(owner, name = "narrowed", access = AKEY.full)
        val narrowed = tokenOf(narrowedKey)
        owner.postData(AKEY.selfAccess, mapOf(AKEY.keyId to narrowedId, AKEY.access to AKEY.read))
        setName(narrowed) shouldBe EXC.notAuthorized
        owner.postData(AKEY.selfAccess, mapOf(AKEY.keyId to readKeyId, AKEY.access to AKEY.full))
        setName(read) shouldBe EXC.notAuthorized
    }

    "a token ends with its key or its owner" {
        val owner = TestUser.create(cxt, "ends@$mine.test", userClient = mine)
        val (key, keyId) = makeKey(owner)
        val token = tokenOf(key)
        whoIs(token) shouldBe owner.userId
        owner.postData(AKEY.selfRevoke, mapOf(AKEY.keyId to keyId))
        val revoked = bearer(token).sendGetRequest(AEP.selfInfo)
        revoked.rptStatusCode shouldBe EXC.authNeeded
        bodyOf(revoked)[EP.errorMessage] shouldBe "The access token is not valid."
        // Nor is a revoked key exchanged again.
        bearer(key).sendFormRequest(ATOK.token, mapOf(ATOK.grantType to ATOK.clientCredentials)).rptStatusCode shouldBe EXC.authNeeded

        val (second, _) = makeKey(owner, name = "second")
        val secondToken = tokenOf(second)
        whoIs(secondToken) shouldBe owner.userId
        TestUser.createFullAdmin(cxt, "full@tokens1403.test")
            .postData(UADEP.userSetEnabled, mapOf(ADF.userId to owner.userId, ADF.enabled to false))
        bearer(secondToken).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
    }

    "a token never outlasts its key, and expires" {
        val owner = TestUser.create(cxt, "expires@$mine.test", userClient = mine)
        val (key, keyId) = makeKey(owner)
        // A key with two hours left gives a token two hours.
        val keyExpiry = cxt.instanceNow() + 2.hours
        val prior = ApiKeyService.get(cxt).queryKey(cxt, keyId, forWrite = true)?.get(PF.updatedAt).toOptInstant()
        val stamp = listOfNotNull(cxt.instanceNow(), prior?.plus(1.seconds)).max()
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "uAccessTokenTestExpiry", ApiKeyService.get(cxt).table(cxt).columns,
            "update t:${UT.authApiKeys} set c:${AKEY.expiresAt} = :${AKEY.expiresAt}, c:${PF.updatedAt} = :${PF.updatedAt} " +
                "where c:${AKEY.keyId} = :${AKEY.keyId}",
        )
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(AKEY.expiresAt to keyExpiry, PF.updatedAt to stamp, AKEY.keyId to keyId))
        }
        exchange(key)[ATOK.expiresIn].toOptLong()!! shouldBeInRange (2 * 3600L - 5)..(2 * 3600L)

        // A token past its expiry, on a key that still works.
        val (lasting, _) = makeKey(owner, name = "lasting")
        val token = tokenOf(lasting)
        whoIs(token) shouldBe owner.userId
        cxt.instanceConfig.clock.advanceBy(24.hours + 1.seconds)
        bearer(token).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        whoIs(tokenOf(lasting)) shouldBe owner.userId
    }

    "a forged, foreign or malformed token is not honored" {
        val owner = TestUser.create(cxt, "forged@$mine.test", userClient = mine)
        val other = TestUser.create(cxt, "other@$mine.test", userClient = mine)
        val (key, _) = makeKey(owner)
        val token = tokenOf(key)
        // Another user's id, under the owner's signature.
        bearer(tampered(token) { it[JWS.sub] = other.userId.toString() }).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // Unsigned.
        val unsigned = listOf(mapOf(JWS.alg to "none", JWS.typ to JWS.accessTokenType), claimsOf(token))
            .joinToString(".") { it.toJsonStr(compact = true).toByteArray().base64Encode() } + "."
        bearer(unsigned).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // Signed by a key this deployment never published.
        val stranger = Es256.newKeyPair()
        val header = JwsParts.parse(token)!!.header
        val forged = JwsParts.encode(header, claimsOf(token)) { Es256.sign(stranger.private, it) }
        bearer(forged).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // Another issuer's JWT is not this deployment's to judge: the request is simply not authenticated by it.
        val foreign = JwsParts.encode(header, claimsOf(token) + (JWS.iss to "https://elsewhere.example")) {
            Es256.sign(stranger.private, it)
        }
        val answered = bearer(foreign).sendGetRequest(AEP.selfInfo)
        answered.rptStatusCode shouldBe 200
        whoIs(foreign) shouldNotBe owner.userId
        // Signed with this deployment's own key, which a forger does not have: what the signature check cannot tell apart.
        val signer = AccessTokenService.get(cxt).currentKeys(cxt).signer.shouldNotBeNull()
        fun signedHere(header: Map<String, Any?>, claims: Map<String, Any?>) =
            JwsParts.encode(header + (JWS.kid to signer.kid), claims) { Es256.sign(signer.privateKey, it) }
        bearer(signedHere(header, claimsOf(token))).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe 200
        // A JWT of another kind -- an ID token, say -- does not pass for an access token.
        bearer(signedHere(header + (JWS.typ to "JWT"), claimsOf(token))).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // Nor does one naming a user its key does not belong to.
        bearer(signedHere(header, claimsOf(token) + (JWS.sub to other.userId.toString()))).sendGetRequest(AEP.selfInfo)
            .rptStatusCode shouldBe EXC.authNeeded
        // Not a JWT at all.
        bearer("a.b.c").sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe 200
    }

    "a rotation keeps a token it signed verifying until the token expires" {
        val owner = TestUser.create(cxt, "rotated@$mine.test", userClient = mine)
        val operator = TestUser.create(cxt, "operator@$mine.test", level = ROLE.operator, capabilities = listOf(ROLE.allClients), userClient = mine)
        val (key, _) = makeKey(owner)
        val before = tokenOf(key)
        val beforeKid = JwsParts.parse(before)!!.header[JWS.kid]

        // Not by a key, even an operator's.
        val (operatorKey, _) = makeKey(operator, access = AKEY.full)
        bearer(operatorKey).sendEditRequest(ATOK.rotateSigningKey, null, emptyMap(), HttpMethod.POST).rptStatusCode shouldBe EXC.notAuthorized

        val rotated = operator.postData(ATOK.rotateSigningKey, emptyMap())
        val newKid = rotated[ATOK.signingKeyId].toOptStr().shouldNotBeNull()
        newKid shouldNotBe beforeKid
        val published = bodyOf(TestHttpClient(cxt.instanceConfig).sendGetRequest(ATOK.jwks))[ATOK.keys].toJsonListOfMaps().map { it[JWS.kid] }
        published shouldContain beforeKid
        published shouldContain newKid

        // The old token still works and still verifies; a new one is signed by the new key.
        whoIs(before) shouldBe owner.userId
        verifiesWithJwksAlone(before) shouldBe true
        val after = tokenOf(key)
        JwsParts.parse(after)!!.header[JWS.kid] shouldBe newKid
        verifiesWithJwksAlone(after) shouldBe true

        // Once every token the old key signed has expired, the next rotation stops publishing it.
        cxt.instanceConfig.clock.advanceBy(24.hours + 6.minutes)
        val again = operator.postData(ATOK.rotateSigningKey, emptyMap())
        val nowPublished = bodyOf(TestHttpClient(cxt.instanceConfig).sendGetRequest(ATOK.jwks))[ATOK.keys].toJsonListOfMaps().map { it[JWS.kid] }
        (beforeKid in nowPublished) shouldBe false
        nowPublished shouldContain newKid
        nowPublished shouldContain again[ATOK.signingKeyId]
        bearer(before).sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
    }

    "the key set and the token answer are documented as their standards have them" {
        val jwks = TestHttpClient(cxt.instanceConfig).sendGetRequest(ATOK.jwks)
        jwks.rptStatusCode shouldBe 200
        val body = bodyOf(jwks)
        body.keys shouldBe setOf(ATOK.keys)
        body[ATOK.keys].toJsonListOfMaps().first()[JWS.use] shouldBe JWS.sig
        jwks.rptResponseData.shouldNotBeNull() shouldStartWith "{"
    }
})
