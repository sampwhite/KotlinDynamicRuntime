package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.startup.SchemaCollector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A report written to a client's stored configuration (issue #979): the write's trial accepts it against the slot's
 * schema, the reload reassembles it, and the client sees it -- on a booted node, where the slot's reference to the
 * definition schema has to resolve.
 */
class StoredReportConfigTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("storedReport", "storedReportConfigTest")
    val client = "rptstored"
    fun writer(): KdrCxt = cxt.mkSubContext("reportWrite", client).also { it.userId = 9470L }

    "a stored report is written, reloaded and seen by its client" {
        val config = gedraConfig(cxt, "main", "client.$client", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            report("owners", "Owners") {
                column("email", "Email", "user.email")
                column("labels", "Labels", "user.labels", combine = ReportCombine.count)
                excludeEmpty = listOf("email")
            }
        }
        val row = GedraConfigService.get(cxt).writeConfig(writer(), config, trial = true)
        row.entriesBySlot()[CCT.reportDef].shouldNotBeNull().single()[CCT.reportId] shouldBe "owners"
        GedraConfigReload.reloadClient(cxt, client)
        val loaded = GedraConfigLoadService.get(cxt).loadedFor(client).single()
        loaded.reports.getValue("owners").columns.map { it.columnId } shouldContainExactly listOf("email", "labels")
        val collector = SchemaCollector.get(cxt).shouldNotBeNull()
        collector.gedraConfigs.reportsFor(client).map { it.reportId } shouldContain "owners"
    }

    "a stored report under a rooted id is refused at the write" {
        val config = gedraConfig(cxt, "rooted", "client.$client", client) {
            report("kdr:mine", "Mine") { column("email", "Email", "user.email") }
        }
        shouldThrow<KdrException> { GedraConfigService.get(cxt).writeConfig(writer(), config, trial = true) }
            .message.shouldNotBeNull() shouldContain "kdr:mine"
    }
})
