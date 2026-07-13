package com.dynamicruntime.kdn

import com.dynamicruntime.common.node.IC
import com.dynamicruntime.common.node.InstanceConfigService
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Stage-3 end-to-end validation (issue #33): boot the full instance against the (default) in-memory
 * database, then write and read back an InstanceConfig entry through [InstanceConfigService]. This exercises
 * the whole stack — the config-driven database, topic/table creation, the topic transaction, and the JSON
 * map column decoding.
 */
class InstanceConfigTest : StringSpec({
    "InstanceConfig upserts and reads back through the in-memory database" {
        val cxt = Startup.mkTestBootCxt("ic", "instanceConfigTest")
        val service = InstanceConfigService.get(cxt).shouldNotBeNull()

        service.setConfig(cxt, "system", "settings", mapOf("a" to 1L, "b" to "two"))

        val row = service.getConfig(cxt, "settings").shouldNotBeNull()
        row[IC.configType] shouldBe "system"
        val data = row[IC.configData]!!.toJsonMap()
        data["a"] shouldBe 1L
        data["b"] shouldBe "two"

        // A missing entry reads back as null.
        service.getConfig(cxt, "absent") shouldBe null
    }

    "getConfig treats a disabled row as absent (issue #48)" {
        val cxt = Startup.mkTestBootCxt("icDisabled", "instanceConfigDisabledTest")
        val service = InstanceConfigService.get(cxt).shouldNotBeNull()

        service.setConfig(cxt, "system", "settings", mapOf("a" to 1L))
        service.getConfig(cxt, "settings").shouldNotBeNull()

        // Disable the row directly (there is no soft-delete write path yet). Once disabled, getConfig treats
        // it as if it were not there -- the same handling a legacy null-enabled row receives.
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, InstanceConfigService.topic)
        val table = cxt.getSchema().tables[InstanceConfigService.tableName].shouldNotBeNull()
        val query = SqlStmtUtil.mkUpdateQuery2(
            InstanceConfigService.tableName, listOf(PF.enabled), listOf(IC.instanceName, IC.configName),
        )
        val stmt = SqlStmtUtil.prepareSql(sqlCxt, "disableSettings", table.columns, query)
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(
                cxt, stmt,
                mapOf(
                    IC.instanceName to cxt.instanceConfig.instanceName,
                    IC.configName to "settings",
                    PF.enabled to false,
                ),
            ) shouldBe 1
        }

        service.getConfig(cxt, "settings") shouldBe null
    }
})
