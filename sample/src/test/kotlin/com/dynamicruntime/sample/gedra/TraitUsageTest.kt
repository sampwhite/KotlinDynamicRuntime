package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A client's trait-usage rules drive its forms-list columns (issue #537) -- the first case of a client's
 * definition changing a page other than its own form. The `globex` client declares no usage of its own, so it
 * inherits the **global default** `name` column (`coreTraits`); the `acme` client declares its site-audit
 * `auditor`, which **overrides** the default -- two clients, two different columns, each computed on the
 * backend and attached to the list and read rows.
 */
class TraitUsageTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "traitUsage", "traitUsageTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
    val globex = TestUser.create(cxt, "usage@globex.test", userClient = SC.globex)
    val acme = TestUser.create(cxt, "usage@acme.test", userClient = SC.acme)

    fun displayOf(row: Map<String, Any?>): List<Map<String, Any?>> = row[GDF.displayValues].toJsonListOfMaps()

    "globex, declaring no usage of its own, inherits the default Name column" {
        globex.postItem(
            clientPath(GEP.formDocCreate, SC.globex),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Quarterly plan")))),
        )
        val row = globex.getItems(clientPath(GEP.formDocs, SC.globex)).first()
        val display = displayOf(row).single()
        display[UF.traitId] shouldBe GT.name
        display[UF.label] shouldBe "Name"
        display[UF.value] shouldBe "Quarterly plan"
        display[UF.kind] shouldBe "string"
    }

    "acme's own rules override the global default -- Auditor and Year columns, no Name" {
        acme.postItem(
            clientPath(GEP.formDocCreate, SC.acme),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "Dana Reyes", SC.findings to "ok")))),
        )
        val row = acme.getItems(clientPath(GEP.formDocs, SC.acme)).first()
        val auditor = displayOf(row).first { it[UF.traitId] == SC.siteAudit }
        auditor[UF.label] shouldBe "Auditor"
        auditor[UF.value] shouldBe "Dana Reyes"
        // The second usage acme declares (issue #538): a Year column, blank on a row that carries no expense
        // report -- present, so every row shares the column set, but empty here.
        val year = displayOf(row).first { it[UF.traitId] == ST.expenseReport }
        year[UF.label] shouldBe "Year"
        year[UF.value] shouldBe ""
        // Override, not additive: acme's own rules replace the global default, so it gets no `name` column.
        displayOf(row).none { it[UF.traitId] == GT.name } shouldBe true
    }

    "a read of one form carries the same display values as the list" {
        val id = globex.postItem(
            clientPath(GEP.formDocCreate, SC.globex),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Read me")))),
        )[GDF.gedraId] as String
        val read = globex.getItem(clientPath(GEP.formDoc, SC.globex), mapOf(GDF.gedraId to id))
        displayOf(read).single()[UF.value] shouldBe "Read me"
    }

    "a row missing the presented trait gets an empty value, not a dropped column" {
        // A globex form with no name entry (an expense report instead): the Name column is present but blank.
        globex.postItem(
            clientPath(GEP.formDocCreate, SC.globex),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)))),
        )
        val rows = globex.getItems(clientPath(GEP.formDocs, SC.globex))
        val noName = rows.first { r -> r[GDF.entries].toJsonListOfMaps().none { it[GE.traitId] == GT.name } }
        val display = displayOf(noName).single()
        display[UF.label] shouldBe "Name"
        display[UF.value] shouldBe ""
    }
})
