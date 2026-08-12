package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.sql.TI
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Exercises the SqlTopicService `/operator/db/tables` endpoint through the in-process client (issue #273).
 *
 * The point of the change this covers is that the endpoint is **gated**, not anonymous. It dumps the whole
 * data model -- table names, columns, their schema, unique indexes -- and unlike `/schema/endpoints` it
 * filters nothing by caller, so the section gate is its only protection. The two assertions are therefore the
 * refusal and the success, not just the shape of the dump.
 */
class DbTablesEndpointTest : StringSpec({

    val path = "/operator/db/tables"
    val cxt = Startup.mkTestBootCxt("dbTables", "dbTablesTest")

    "an anonymous caller is refused the table catalog" {
        // No login at all: the operator section needs one, so this is a 401 rather than a 403.
        TestHttpClient(cxt.instanceConfig).sendGetRequest(path).rptStatusCode shouldBe EXC.authNeeded
    }

    "an ordinary user is refused it" {
        // Logged in, but without the operator rung -- 403 (issue #211).
        TestUser.create(cxt, "plain-db@example.com").expectError(EXC.notAuthorized, path)
    }

    "an operator lists the registered tables, including InstanceConfig" {
        val admin = TestUser.createFullAdmin(cxt, "grantor-db@example.com")
        val operator = TestUser.create(cxt, "operator-db@example.com")
        admin.postData(
            com.dynamicruntime.common.user.ADEP.userSetRoles,
            mapOf(
                com.dynamicruntime.common.user.ADF.userId to operator.userId,
                com.dynamicruntime.common.user.ADF.roles to listOf(ROLE.user, ROLE.operator),
            ),
        )

        val items = operator.getItems(path)
        items.map { it[TI.tableName] } shouldContain "InstanceConfig"
        items.first { it[TI.tableName] == "InstanceConfig" }[TI.topic] shouldBe "node"
    }
})
