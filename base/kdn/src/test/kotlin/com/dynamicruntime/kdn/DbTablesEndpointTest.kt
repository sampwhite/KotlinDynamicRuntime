package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.sql.TI
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Exercises the SqlTopicService `/db/tables` endpoint through the in-process client, confirming the `db`
 * section is routable (added to the anonymous sections) and the endpoint dumps the table catalog — which
 * now includes the node's `InstanceConfig` table.
 */
class DbTablesEndpointTest : StringSpec({
    "/db/tables is routable and lists the registered tables, including InstanceConfig" {
        val client = TestHttpClient(Startup.mkTestBootCxt("dbTables", "dbTablesTest").instanceConfig)
        val resp = client.sendJsonGetRequest("/db/tables")
        val items = (resp[EP.items] as List<*>).map { it!!.toJsonMap() }
        items.map { it[TI.tableName] } shouldContain "InstanceConfig"
        items.first { it[TI.tableName] == "InstanceConfig" }[TI.topic] shouldBe "node"
    }
})
