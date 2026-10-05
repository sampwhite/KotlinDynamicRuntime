package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * A scoped endpoint's `client` field (issue #1000): one shared declaration (`overseenClientField`) that offers the
 * clients the caller may name and is shown only to a caller who may name another -- an `allClients` administrator,
 * the `kdr:isDeploymentAdmin` cfact delivered to the frontend. The served schema is the same for every caller; the
 * delivered cfacts decide who sees the field, and the handler still refuses another client to anyone else.
 */
class ScopedClientFieldTest : StringSpec({
    // Env-authed, as ClientOptionsTest explains: the caller must see the catalog endpoint itself.
    val cxt = Startup.mkTestBootCxt("scopedClient1000", "scopedClient1000", mapOf(ACFG.assumeEnvAuth to true))
    val full = TestUser.createFullAdmin(cxt, "full@scoped1000.test")
    val scoped = TestUser.create(cxt, "scoped@scoped1000.test", level = ROLE.admin)

    /** [field] of [path]'s ([method]) input schema, as [user] is served it. */
    fun inputField(user: TestUser, path: String, field: String, method: String = "GET"): Map<String, Any?> =
        user.getData("/schema/endpoints", mapOf(SS.pathRegex to path))[EI.endpoints].toJsonListOfMaps()
            .single { it[EI.path].toOptStr() == path && it[EI.method] == method }[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[field].toJsonMapOrEmpty()

    fun values(choices: Any?): List<String?> = choices.toJsonListOfMaps().map { it[SCH.value].toOptStr() }

    fun delivered(user: TestUser): Map<String, Any?> = user.getData("/schema/endpoints")[EI.cfacts].toJsonMapOrEmpty()

    "the deployment-admin cfact is delivered, true only for an allClients administrator" {
        delivered(full)[CFACTS.isDeploymentAdmin] shouldBe true
        delivered(scoped)[CFACTS.isDeploymentAdmin] shouldBe false
    }

    "a scoped client field offers the clients the caller may name, and is shown only to one who may name another" {
        val forFull = inputField(full, CPY.keysPath, COV.client)
        forFull[SCH.visibleWhen] shouldBe CFACTS.isDeploymentAdmin
        values(forFull[SCH.options]) shouldContain scoped.selfClient()
        // The client-scoped administrator is served the same field -- its condition fails their delivered cfacts, so
        // the frontend hides it -- and its choices are only their own client, the one they may name.
        val forScoped = inputField(scoped, CPY.keysPath, COV.client)
        forScoped[SCH.visibleWhen] shouldBe CFACTS.isDeploymentAdmin
        values(forScoped[SCH.options]) shouldContainExactly listOf(scoped.selfClient())
    }

    "the forms listing's client filter follows the same fact" {
        inputField(full, GEP.formDocs, EI.client)[SCH.visibleWhen] shouldBe CFACTS.isDeploymentAdmin
    }

    "the full-scope config endpoints offer a choice of client, with no visibility condition" {
        val field = inputField(full, ACEP.bundle, CFEP.client)
        values(field[SCH.options]) shouldContain scoped.selfClient()
        field.containsKey(SCH.visibleWhen) shouldBe false
    }
})
