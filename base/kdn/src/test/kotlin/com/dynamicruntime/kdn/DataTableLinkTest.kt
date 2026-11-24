package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DataTables
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.traitDataTypeIn
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate

/**
 * A table field (issue #1882): a form names a CSV file whose rows its row type's columns check when the form is saved,
 * so a bad row refuses the save as a bad value does.
 */
class DataTableLinkTest : StringSpec({
    val cxt = TestInstances.default("tables1882")
    val client = "tables1882"

    val setup = cxt.mkSubContext("setup", client).also { it.userId = 18820L }
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
                property("category", "What it was for.") {
                    type = SCT.string
                    option("meal", "Meal")
                    option("taxi", "Taxi")
                }
                property("amount", "How much.", required = true) {
                    type = SCT.number
                    minimum = 0
                }
            }
            trait("ClaimEntry", "claim", setOf(GedraDataType.formDoc), "An expense claim.") {
                property("lines", "What was spent.") { dataTable("ClaimLine", maxRows = 3) }
            }
        },
    )
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "pat@tables1882.test", userClient = client)

    fun envelope(handler: com.dynamicruntime.common.http.request.RequestHandler): Map<String, Any?> =
        handler.rptResponseData?.jsonMap().shouldNotBeNull()

    fun upload(name: String, text: String, mime: String = "text/csv"): String {
        val sent = envelope(owner.client.sendRawBodyRequest(DFILE.uploadPath, mapOf(DFILE.name to name, DFILE.mimeType to mime), text.encodeToByteArray()))
        sent[EP.status] shouldBe null
        return sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
    }

    fun create(lines: String): Map<String, Any?> = envelope(
        owner.client.sendEditRequest(
            clientPath(GEP.formDocCreate, client), null,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "claim", GE.data to mapOf("lines" to lines)))), HttpMethod.POST,
        ),
    )

    fun refusal(lines: String): String {
        val answer = create(lines)
        answer[EP.status] shouldBe EXC.badInput.toLong()
        return answer[EP.errorMessage].toOptStr().shouldNotBeNull()
    }

    "a table whose rows fit its columns saves, read in any column order, a choice by its label" {
        val lines = upload("claim.csv", "amount,day,category\r\n12.50,2026-11-02,Meal\r\n30,2026-11-03,\r\n")
        val created = create(lines)
        created[EP.status] shouldBe null
        val formId = created[EP.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()
        owner.getItem(DFILE.usedByPath, mapOf(DFILE.fileId to lines))[DFILE.usedBy].toString() shouldContain formId

        // What a computation on the form reads: the table, typed.
        val union = GedraDataService.get(cxt).entryUnion(cxt, client, GedraDataType.formDoc).shouldNotBeNull()
        val field = traitDataTypeIn(union, "claim").shouldNotBeNull().properties.getValue("lines").valueType
        val read = DataTables.readFor(cxt, lines, field, client).shouldNotBeNull()
        read.rows shouldBe listOf(
            mapOf("amount" to 12.5, "day" to LocalDate(2026, 11, 2), "category" to "meal"),
            mapOf("amount" to 30L, "day" to LocalDate(2026, 11, 3)),
        )
        DataTables.readFor(cxt, lines, field, "another").shouldBeNull()
    }

    "a header-only table is an empty one" {
        create(upload("empty.csv", "day,amount\r\n"))[EP.status] shouldBe null
    }

    "a bad row refuses the save, named by its row and column" {
        refusal(upload("bad.csv", "day,amount\n2026-11-02,5\n2026-11-03,abc\n")) shouldContain "Row 2, amount: 'abc' is not a number."
        refusal(upload("negative.csv", "day,amount\n2026-11-02,-5\n")) shouldContain "Row 1, amount:"
        refusal(upload("choice.csv", "day,amount,category\n2026-11-02,5,hotel\n")) shouldContain
            "Row 1, category: 'hotel' is not one of the choices."
        refusal(upload("many.csv", "day,amount\n2026-11-02,5\n2026-11-02,abc\n2026-11-03,x\n")) shouldContain "(And 1 more problem.)"
    }

    "a header that is not the columns, or too many rows, refuses the save" {
        refusal(upload("tip.csv", "day,amount,tip\n2026-11-02,5,1\n")) shouldContain "'tip', which is not a column"
        refusal(upload("noday.csv", "amount\n5\n")) shouldContain "no 'day'"
        refusal(upload("long.csv", "day,amount\n" + (1..4).joinToString("") { "2026-11-0$it,$it\n" })) shouldContain "The table has 4 rows; this field holds at most 3."
    }

    "a table whose bytes are gone from the store refuses the save, as any unusable file does" {
        val text = "day,amount\n2026-11-09,9\n"
        val lines = upload("gone.csv", text)
        val hash = owner.getItem(DFILE.infoPath, mapOf(DFILE.fileId to lines))[DFILE.hash].toOptStr().shouldNotBeNull()
        FileStoreService.get(cxt).forOwner(cxt, client).delete(cxt, DFILE.bucket, "clients/$client/files/$hash") shouldBe true
        refusal(lines) shouldContain "whose content is no longer stored"
    }

    "a table field takes only a CSV table" {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100)
        val sent = envelope(owner.client.sendRawBodyRequest(DFILE.uploadPath, mapOf(DFILE.name to "a.png", DFILE.mimeType to "image/png"), png))
        val photo = sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
        refusal(photo) shouldContain "takes only CSV table"
    }
})
