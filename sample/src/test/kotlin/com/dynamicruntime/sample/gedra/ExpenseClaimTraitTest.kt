package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The sample's table trait (issue #1886), `sample:expenseClaim`: a client taking every global trait carries it, so its
 * forms hold a claim's lines as a CSV table whose columns are `ExpenseLine`'s, with the claim's count, total, and latest
 * day stored on each save -- what a developer exercises the data grid against by hand.
 */
class ExpenseClaimTraitTest : StringSpec({
    val cxt = SampleTestInstances.default("expenseClaim")
    val user = TestUser.create(cxt, "claims@globex.test", userClient = SC.globex)

    fun envelope(handler: com.dynamicruntime.common.http.request.RequestHandler): Map<String, Any?> =
        handler.rptResponseData?.jsonMap().shouldNotBeNull()

    fun upload(name: String, text: String): String {
        val args = mapOf(DFILE.name to name, DFILE.mimeType to "text/csv")
        val sent = envelope(user.client.sendRawBodyRequest(DFILE.uploadPath, args, text.encodeToByteArray()))
        sent[EP.status] shouldBe null
        return sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
    }

    fun claim(lines: String?) = mapOf(GE.traitId to ST.expenseClaim, GE.data to listOfNotNull(lines?.let { ST.lines to it }).toMap())

    fun create(lines: String?): Map<String, Any?> = envelope(
        user.client.sendEditRequest(clientPath(GEP.formDocCreate, SC.globex), null, mapOf(GDF.entries to listOf(claim(lines))), HttpMethod.POST),
    )

    fun formIdOf(created: Map<String, Any?>): String = created[EP.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()

    fun stored(formId: String): Map<String, Any?> {
        val entries = user.getItem(clientPath(GEP.formDoc, SC.globex), mapOf(GDF.gedraId to formId))[GDF.entries].toJsonListOfMaps()
        return entries.single { it[GE.traitId] == ST.expenseClaim }[GE.data].toJsonMapOrEmpty()
    }

    fun setLines(formId: String, lines: String) {
        envelope(
            user.client.sendEditRequest(
                clientPath(GEP.patch, SC.globex), null,
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

    "a globex claim's lines are a table, and its count, total, and latest day are stored on each save" {
        val lines = upload(
            "trip.csv",
            "spentOn,category,description,amount,receipt\r\n" +
                "2026-11-02,Travel,Train to Leeds,42.50,yes\r\n" +
                "2026-11-02,meals,Lunch,12.25,\r\n" +
                "2026-11-03,lodging,Hotel,98,true\r\n",
        )
        val created = create(lines)
        created[EP.status] shouldBe null
        val formId = formIdOf(created)
        stored(formId).filterKeys { it != ST.lines } shouldBe
            mapOf(ST.lineCount to 3L, ST.totalAmount to 152.75, ST.lastSpentOn to "2026-11-03")

        // Another table: the summaries follow it.
        setLines(formId, upload("more.csv", "spentOn,amount\r\n2026-11-09,1.10\r\n2026-11-08,2.20\r\n"))
        stored(formId).filterKeys { it != ST.lines } shouldBe
            mapOf(ST.lineCount to 2L, ST.totalAmount to 3.3, ST.lastSpentOn to "2026-11-09")
    }

    "a line that does not fit the columns refuses the save, named by its row and column" {
        val bad = upload("bad.csv", "spentOn,category,amount\r\n2026-11-02,travel,5\r\n2026-11-03,gifts,-1\r\n")
        val refused = create(bad)
        refused[EP.status] shouldBe EXC.badInput.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "Row 2, Category: 'gifts' is not one of the choices."
        // A line needs its day and its amount.
        create(upload("short.csv", "description,amount\r\nTaxi,5\r\n"))[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain
            "no 'spentOn'"
    }

    "a claim holds at most its lines" {
        val many = "spentOn,amount\r\n" + (1..(ST.claimMaxLines + 1)).joinToString("") { "2026-11-01,1\r\n" }
        create(upload("many.csv", many))[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain
            "this field holds at most ${ST.claimMaxLines}"
    }
})
