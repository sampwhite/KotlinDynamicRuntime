package com.dynamicruntime.kdn

import com.dynamicruntime.common.node.IC
import com.dynamicruntime.common.node.InstanceConfigService
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
})
