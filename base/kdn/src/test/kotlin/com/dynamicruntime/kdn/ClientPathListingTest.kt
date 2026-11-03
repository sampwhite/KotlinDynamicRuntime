package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A client's own copy of a listing lists that client's rows, for every caller (issue #1575).
 *
 * An administrator holding `allClients` reads every client on the shared surface, and its scope said so on a client's
 * copy too: `/gedra/<client>/formDocs` listed every client's forms, checked against that client's schema. A form of
 * another client carrying a field this one's schema does not have then failed the response's own check, a 500; one
 * that happened to fit came back through the wrong client's endpoint. The two clients here are made to differ, so
 * the first symptom shows: each declares a `note` trait of a different shape.
 */
class ClientPathListingTest : StringSpec({
    val cxt = TestInstances.default("pathList1575")
    val alpha = "pathlista1575"
    val beta = "pathlistb1575"

    /** Defines [client] with a `note` trait of the one [field], so it has its own copies of the gedra endpoints. */
    fun defineClient(client: String, field: String) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property(field, "The note.") }
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("pathList", client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    /** A form of [client]'s, made by a user there with a note in its [field]; its id. */
    fun formIn(client: String, field: String): String {
        val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
        val entry = mapOf(GE.traitId to "note", GE.data to mapOf(field to "x"))
        return owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))[GDF.gedraId].toOptStr().shouldNotBeNull()
    }

    defineClient(alpha, "text")
    defineClient(beta, "remark")
    val alphaForm = formIn(alpha, "text")
    val betaForm = formIn(beta, "remark")
    val full = TestUser.createFullAdmin(cxt, "full@pathlist1575.test")

    fun ids(rows: List<Map<String, Any?>>) = rows.map { it[GDF.gedraId].toOptStr() }

    "an all-clients administrator's listing on a client's copy is that client's" {
        // Before #1575 this was a 500: beta's form, listed here, failed alpha's schema ("remark" is not alpha's).
        val listed = full.getItems(clientPath(GEP.formDocs, alpha))
        ids(listed) shouldContain alphaForm
        ids(listed) shouldNotContain betaForm
        listed.map { it[GDF.client] }.toSet() shouldBe setOf(alpha)
    }

    "the shared listing still reaches every client, and its client filter still narrows it" {
        ids(full.getItems(GEP.formDocs)).let {
            it shouldContain alphaForm
            it shouldContain betaForm
        }
        ids(full.getItems(GEP.formDocs, mapOf(EI.client to beta))) shouldBe listOf(betaForm)
    }

    "a client filter on a client's copy may name only that client" {
        ids(full.getItems(clientPath(GEP.formDocs, alpha), mapOf(EI.client to alpha))) shouldContain alphaForm
        // The path said one client and the filter another: refused, as a gedra id of another client is there.
        full.expectError(EXC.badInput, clientPath(GEP.formDocs, alpha), args = mapOf(EI.client to beta))
    }

    "the scope says so: a client's copy confines an all-clients caller to its client" {
        fun acting(path: String?): KdrCxt = KdrCxt(
            "pathScope", cxt.instanceConfig, null,
            UserProfile(authId = "1", userId = 1L, client = alpha, roles = setOf(ROLE.user, ROLE.admin, ROLE.allClients)),
        ).also { it.clientFromPath = path }
        ReadScopeRules.forCaller(acting(null)).isUnrestricted shouldBe true
        ReadScopeRules.forCaller(acting(beta)).client shouldBe beta
    }
})
