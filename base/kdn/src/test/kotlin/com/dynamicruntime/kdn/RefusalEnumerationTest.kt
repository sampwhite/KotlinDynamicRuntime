package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * No refusal says whether something the caller may not see exists (issue #1293). For each endpoint that takes, from
 * the caller, a name for data outside their reach -- a client, a gedra id, a user, an address -- the answer for one
 * that exists and one that does not must be the same: the status, and the message with the named value masked.
 * Over the shared instance, with clients of this issue's own.
 */
/** The catalog's single lookup. */
private const val catalogLookupPath = "/schema/endpoint"

class RefusalEnumerationTest : StringSpec({
    val cxt = TestInstances.default("enum1293")
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("enum", client).also { it.userId = 12930L }

    /** Defines [client] with a trait of its own, so it has its own copies of the gedra endpoints. */
    fun defineClient(client: String) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property("text", "The note.") }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    val mine = "enummine1293"
    val theirs = "enumtheirs1293"
    val missing = "enumnosuch1293"
    defineClient(mine)
    defineClient(theirs)
    val admin = TestUser.create(cxt, "admin@$mine.test", level = ROLE.admin, userClient = mine)

    /**
     * What [user] is answered for [path]: the status (200 for an answer) and the message with every value in [masked]
     * replaced, so two refusals naming different things compare equal when they say the same.
     */
    fun answer(
        user: TestUser, path: String, masked: List<String>, method: HttpMethod = HttpMethod.GET,
        args: Map<String, Any?>? = null, data: Map<String, Any?>? = null,
    ): Pair<Int, String> {
        val env = when (method) {
            HttpMethod.GET -> user.client.sendJsonGetRequest(path, args)
            else -> user.client.sendEditRequest(path, args, data, method).rptResponseData?.jsonMap() ?: emptyMap()
        }
        val status = (env[EP.status] as? Number)?.toInt() ?: 200
        var message = env[EP.errorMessage]?.toString().orEmpty()
        for (value in masked) message = message.replace(value, "<named>")
        return status to message
    }

    "another client's own copy of an endpoint answers as a client that has none" {
        val masked = listOf(theirs, missing)
        val none = answer(admin, clientPath(GEP.formDocs, missing), masked)
        none.first shouldBe EXC.notFound
        answer(admin, clientPath(GEP.formDocs, theirs), masked) shouldBe none
        // Their own copy is theirs to call, and every client's is a deployment administrator's.
        answer(admin, clientPath(GEP.formDocs, mine), masked).first shouldBe 200
        answer(TestUser.createFullAdmin(cxt, "full@enum1293.test"), clientPath(GEP.formDocs, theirs), masked).first shouldBe 200
    }

    "setting a user's login data answers alike for a user who exists and one who does not" {
        // Anonymous: a form token is all it takes to ask, so an answer that told the two apart would let anybody
        // count the user ids there are.
        val browser = TestHttpClient(cxt.instanceConfig)
        val token = browser.sendJsonGetRequest(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken].toOptStr()!!
        fun attempt(userId: Long): Pair<Int, String> {
            val env = browser.sendEditRequest(
                AEP.setLoginData, null,
                mapOf(AFLD.userId to userId, AFLD.formAuthToken to token, AFLD.verifyCode to "000000"), HttpMethod.PUT,
            ).rptResponseData?.jsonMap() ?: emptyMap()
            return ((env[EP.status] as? Number)?.toInt() ?: 200) to env[EP.errorMessage].toString()
        }
        attempt(admin.userId) shouldBe attempt(987_654_321L)
    }

    "a patch of a form the caller cannot reach says nothing of the client the id names" {
        // Two edits of one trait would be refused for asking two things of it -- which depends on how the id's client
        // declares the trait, so it must not be judged before the form is known to be the caller's.
        fun patch(client: String): Pair<Int, String> {
            val edit = mapOf(GED.action to GedraEditAction.addOrReplace.name, GE.traitId to "note", GE.data to mapOf("text" to "x"))
            val target = mapOf(GDF.gedraId to GedraId.of(GedraDataType.formDoc, client, "Fx1293").fullId, GPF.edits to listOf(edit, edit))
            return answer(
                admin, GEP.patch, listOf(theirs, missing), HttpMethod.POST,
                data = mapOf(GPF.targets to mapOf(GedraDataType.formDoc.name to listOf(target))),
            )
        }
        val none = patch(missing)
        none.first shouldBe EXC.notFound
        patch(theirs) shouldBe none
    }

    "a user named by address is found by the users the caller may see, wherever the person's default user is" {
        // Users in both clients, the default in theirs (the last one signed in as): still this client's user to its
        // administrator -- rather than "not found", which would say the person's default user is somewhere else.
        val both = "both@enum1293.test"
        TestUser.create(cxt, both, userClient = mine)
        TestUser.create(cxt, both, userClient = theirs)
        answer(admin, GEP.formDocs, emptyList(), args = mapOf(EI.user to both)).first shouldBe 200
        // Someone with no user here is answered as an address nobody has.
        TestUser.create(cxt, "elsewhere@enum1293.test", userClient = theirs)
        val masked = listOf("elsewhere@enum1293.test", "nobody@enum1293.test")
        answer(admin, GEP.formDocs, masked, args = mapOf(EI.user to "elsewhere@enum1293.test")) shouldBe
            answer(admin, GEP.formDocs, masked, args = mapOf(EI.user to "nobody@enum1293.test"))
    }

    "the catalog describes another client's own endpoint to nobody who may not look at that client" {
        fun lookup(client: String) = admin.getData(catalogLookupPath, mapOf(EI.method to "GET", EI.path to clientPath(GEP.formDocs, client)))
        // Not another client's schema, and nothing that tells their copy from a client's that has none.
        lookup(theirs)[EI.endpoints].toJsonListOfMaps() shouldBe emptyList()
        lookup(missing)[EI.endpoints].toJsonListOfMaps() shouldBe emptyList()
        lookup(mine)[EI.endpoints].toJsonListOfMaps().size shouldBe 1
    }
})
