package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A client's trait-usage rules drive its forms-list columns (issue #537) -- the first case of a client's
 * definition changing a page other than its own form. `globex` declares a `name` column (the same rule the
 * global default carries) beside a `Year` over the `yearly` trait (issue #674); `acme` declares its site-audit
 * `auditor` and an expense `Year`, which **override** the global default -- two clients, different column sets,
 * each computed on the backend and attached to the list and read rows.
 */
class TraitUsageTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "traitUsage", "traitUsageTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )
    val globex = TestUser.create(cxt, "usage@globex.test", userClient = SC.globex)
    val acme = TestUser.create(cxt, "usage@acme.test", userClient = SC.acme)

    fun displayOf(row: Map<String, Any?>): List<Map<String, Any?>> = row[GDF.displayValues].toJsonListOfMaps()

    "globex shows the Name column it declares, beside its yearly Year column" {
        globex.postItem(
            clientPath(GEP.formDocCreate, SC.globex),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "Quarterly plan")))),
        )
        val row = globex.getItems(clientPath(GEP.formDocs, SC.globex)).first()
        val name = displayOf(row).first { it[UF.traitId] == GT.name }
        name[UF.label] shouldBe "Name"
        name[UF.value] shouldBe "Quarterly plan"
        name[UF.kind] shouldBe "string"
        // The yearly Year column (issue #674): present on every row so the column set is uniform, a `number`, and
        // blank here since this form carries a name and no yearly entry.
        val year = displayOf(row).first { it[UF.traitId] == ST.yearly }
        year[UF.label] shouldBe "Year"
        year[UF.kind] shouldBe "number"
        year[UF.value] shouldBe ""
    }

    "a client declaring no usage of its own falls back to the global default Name column (issue #674 review)" {
        // Both sample clients now declare their own usages, so this is what still exercises `usagesFor`'s global
        // fallback: a client id with no config of its own resolves to the global usages -- the single `name`
        // column `coreTraits` declares -- which is what preserves the pre-#537 Name column for any such client.
        val usages = SchemaService.get(cxt).traitUsagesFor("nosuchclient")
        usages.map { it.traitId } shouldBe listOf(GT.name)
        usages.first().label shouldBe "Name"
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
        displayOf(read).first { it[UF.traitId] == GT.name }[UF.value] shouldBe "Read me"
    }

    "a row missing the presented trait gets an empty value, not a dropped column" {
        // A globex form with no name entry (an expense report instead): the Name column is present but blank.
        globex.postItem(
            clientPath(GEP.formDocCreate, SC.globex),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)))),
        )
        val rows = globex.getItems(clientPath(GEP.formDocs, SC.globex))
        val noName = rows.first { r -> r[GDF.entries].toJsonListOfMaps().none { it[GE.traitId] == GT.name } }
        val name = displayOf(noName).first { it[UF.traitId] == GT.name }
        name[UF.label] shouldBe "Name"
        name[UF.value] shouldBe ""
    }
})
