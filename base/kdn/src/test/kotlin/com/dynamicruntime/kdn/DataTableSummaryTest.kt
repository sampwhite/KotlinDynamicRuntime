package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DataFileService
import com.dynamicruntime.common.datafile.STF
import com.dynamicruntime.common.datafile.SummarizeTableCreation
import com.dynamicruntime.common.datafile.summarizeTable
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.traitDataTypeIn
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchWriteFunction
import com.dynamicruntime.common.schema.resolveWriteFunctions
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A table's summaries (issue #1883): `summarizeTable` storing a count, a total, and the latest day of a table field's
 * table in the trait's derived fields on each save, updated when the field names another table and cleared when it
 * names none; current as stored, so the nightly recompute leaves the form alone; and reading, for the system, only a
 * file some live form links.
 */
class DataTableSummaryTest : StringSpec({
    val cxt = TestInstances.default("sums1883")
    val client = "sums1883"

    val setup = cxt.mkSubContext("setup", client).also { it.userId = 18830L }
    GedraConfigService.get(cxt).writeConfig(
        setup,
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            type("ClaimLine") {
                type = SCT.kObject
                property("day", "The day it was spent.", required = true) { dayOnlyDate() }
                property("amount", "How much.", required = true) { type = SCT.number }
            }
            trait("ClaimEntry", "claim", setOf(GedraDataType.formDoc), "An expense claim.") {
                property("note", "What it was for.")
                property("lines", "What was spent.") { dataTable("ClaimLine") }
                property("lineCount", "How many lines.") {
                    type = SCT.integer
                    derived = true
                }
                property("totalAmount", "The lines' total.") {
                    type = SCT.number
                    derived = true
                }
                property("lastSpentOn", "The latest day.") {
                    dayOnlyDate()
                    derived = true
                }
                onWrite(
                    summarizeTable("lineCount", "lines", ReportCombine.count),
                    summarizeTable("totalAmount", "lines", ReportCombine.sum, "amount"),
                    summarizeTable("lastSpentOn", "lines", ReportCombine.max, "day"),
                )
            }
        },
    )
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "pat@sums1883.test", userClient = client)

    fun envelope(handler: com.dynamicruntime.common.http.request.RequestHandler): Map<String, Any?> =
        handler.rptResponseData?.jsonMap().shouldNotBeNull()

    fun upload(name: String, text: String): String {
        val sent = envelope(owner.client.sendRawBodyRequest(DFILE.uploadPath, mapOf(DFILE.name to name, DFILE.mimeType to "text/csv"), text.encodeToByteArray()))
        sent[EP.status] shouldBe null
        return sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
    }

    fun claim(lines: String?): Map<String, Any?> =
        mapOf(GE.traitId to "claim", GE.data to listOfNotNull("note" to "a trip", lines?.let { "lines" to it }).toMap())

    fun create(lines: String?): String {
        val created = envelope(
            owner.client.sendEditRequest(clientPath(GEP.formDocCreate, client), null, mapOf(GDF.entries to listOf(claim(lines))), HttpMethod.POST),
        )
        created[EP.status] shouldBe null
        return created[EP.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()
    }

    fun setLines(formId: String, lines: String?) {
        envelope(
            owner.client.sendEditRequest(
                clientPath(GEP.patch, client), null,
                mapOf(
                    GPF.targets to mapOf(
                        GedraDataType.formDoc.name to listOf(
                            mapOf(GDF.gedraId to formId, GPF.edits to listOf(claim(lines) + (GED.action to GedraEditAction.addOrReplace.name))),
                        ),
                    ),
                ),
                HttpMethod.POST,
            ),
        )[EP.status] shouldBe null
    }

    fun summaries(formId: String): Map<String, Any?> {
        val entries = owner.getItem(clientPath(GEP.formDoc, client), mapOf(GDF.gedraId to formId))[GDF.entries].toJsonListOfMaps()
        val data = entries.single { it[GE.traitId] == "claim" }[GE.data].toJsonMapOrEmpty()
        return data.filterKeys { it in setOf("lineCount", "totalAmount", "lastSpentOn") }
    }

    fun row(formId: String) = GedraDataService.get(cxt).queryGedra(cxt, formId, GedraDataType.formDoc, ReadScope.unrestricted).shouldNotBeNull()

    "a save stores the table's summaries in the trait's derived fields, current as stored" {
        val lines = upload("trip.csv", "day,amount\r\n2026-11-03,12.50\r\n2026-11-01,30\r\n")
        val formId = create(lines)
        summaries(formId) shouldBe mapOf("lineCount" to 2L, "totalAmount" to 42.5, "lastSpentOn" to "2026-11-03")
        // What a recompute would store is what is stored, so the nightly job leaves the form alone.
        GedraDataService.get(cxt).computedDataCurrent(cxt, row(formId)) shouldBe true
    }

    "another table updates the summaries and unlinks the old file; none clears them" {
        val first = upload("first.csv", "day,amount\r\n2026-11-02,5\r\n")
        val formId = create(first)
        summaries(formId) shouldBe mapOf("lineCount" to 1L, "totalAmount" to 5L, "lastSpentOn" to "2026-11-02")

        val second = upload("second.csv", "day,amount\r\n2026-11-04,1\r\n2026-11-05,2\r\n2026-11-06,3\r\n")
        setLines(formId, second)
        summaries(formId) shouldBe mapOf("lineCount" to 3L, "totalAmount" to 6L, "lastSpentOn" to "2026-11-06")
        val old = GedraDataService.get(cxt).queryGedra(cxt, first, GedraDataType.dataFile, ReadScope.unrestricted).shouldNotBeNull()
        DataFileService.fileOf(old)[DFILE.unlinkedAt].shouldNotBeNull()

        setLines(formId, null)
        summaries(formId) shouldBe emptyMap()
    }

    "a header-only table counts no rows and has no total" {
        val formId = create(upload("empty.csv", "day,amount\r\n"))
        summaries(formId) shouldBe mapOf("lineCount" to 0L)
    }

    "a system recompute reads only a file some live form links" {
        val union = GedraDataService.get(cxt).entryUnion(cxt, client, GedraDataType.formDoc).shouldNotBeNull()
        val type = traitDataTypeIn(union, "claim").shouldNotBeNull()
        val functions = resolveWriteFunctions(cxt, mapOf(STF.fn to SummarizeTableCreation), type).map { it.function as SchWriteFunction }
        val system = cxt.mkSubContext("recompute", client)
        system.userProfile.authId.shouldBeNull()
        fun counted(fileId: String) = functions.first { "lineCount" in it.writes }.onWrite(system, mapOf("lines" to fileId))["lineCount"]

        val linked = upload("linked.csv", "day,amount\r\n2026-11-07,7\r\n")
        create(linked)
        counted(linked) shouldBe 1L
        // Uploaded, never saved on a form: a person's write may name it, the system's does not read it.
        val unlinked = upload("loose.csv", "day,amount\r\n2026-11-08,8\r\n")
        counted(unlinked).shouldBeNull()
    }
})
