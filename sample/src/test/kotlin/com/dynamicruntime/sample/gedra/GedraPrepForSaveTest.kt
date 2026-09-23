package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.prepForSaveData
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.serverTimeZone
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.toLocalDateTime

/**
 * A trait's save-time function (issue #728): the sample's `ExpenseReportPrepForSaveFn` refuses an expense report
 * whose reporting year is in the future, run at the entry to a create or update **before** the write
 * transaction. This pins the mechanism -- a create and a patch both run it, a rejection is a 400 with nothing
 * stored, a merge that does not touch the field is unaffected, and a trait with no function is untouched.
 *
 * Its own clients, as the neighboring gedra sample tests do. `acme`
 * carries `expenseReport`, and the validation turns on the instance clock rather than a static bound, so the
 * "future" year is computed from the same clock the function reads.
 */
class GedraPrepForSaveTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "gedraPrepForSave", "gedraPrepForSaveTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    fun service(): GedraDataService = GedraDataService.get(cxt)
    fun asUser(userId: Long): KdrCxt = cxt.mkSubContext("prep", SC.acme).also { it.userId = userId }
    val currentYear = cxt.instanceNow().toLocalDateTime(serverTimeZone).year

    fun expense(year: Int) =
        mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to year, ST.perItemAmount to 10.0, ST.itemCount to 2))

    val acmeCreate = clientPath(GEP.formDocCreate, SC.acme)
    val acmePatch = clientPath(GEP.patch, SC.acme)

    fun patchBody(gid: String, edit: Map<String, Any?>) =
        mapOf(GPF.targets to mapOf(GedraDataType.formDoc.name to listOf(mapOf(GDF.gedraId to gid, GPF.edits to listOf(edit)))))

    "a create with a future reporting year is refused before the write" {
        val user = TestUser.create(cxt, "prep-create@acme.test", userClient = SC.acme)
        // A future year is within the schema's 2000..2100, so only the save-time function refuses it -- a 400.
        user.expectError(EXC.badInput, acmeCreate, data = mapOf(GDF.entries to listOf(expense(currentYear + 5))))
    }

    "a create with the current reporting year saves" {
        val user = TestUser.create(cxt, "prep-ok@acme.test", userClient = SC.acme)
        val item = user.postItem(acmeCreate, mapOf(GDF.entries to listOf(expense(currentYear))))
        item[GDF.gedraId].toOptStr()!!.startsWith("gd.fd.${SC.acme}.") shouldBe true
    }

    "a patch that moves the reporting year into the future is refused" {
        val user = TestUser.create(cxt, "prep-patch@acme.test", userClient = SC.acme)
        val gid = user.postItem(acmeCreate, mapOf(GDF.entries to listOf(expense(currentYear))))[GDF.gedraId].toOptStr()!!
        val edit = mapOf(
            GED.action to GedraEditAction.addOrReplace.name, GE.traitId to ST.expenseReport,
            GE.data to mapOf(ST.year to currentYear + 5, ST.perItemAmount to 10.0, ST.itemCount to 2),
        )
        user.expectError(EXC.badInput, acmePatch, data = patchBody(gid, edit), method = HttpMethod.POST)
        // Nothing changed: the stored year is still the original, so the refusal happened before the write.
        val stored = service().queryGedra(asUser(user.userId), gid, GedraDataType.formDoc, ReadScope.ofClient(SC.acme))!!
        stored.entries.first()[GE.data].toJsonMapOrEmpty()[ST.year] shouldBe currentYear
    }

    "a merge that does not touch the year is unaffected (fragment-safe)" {
        val user = TestUser.create(cxt, "prep-merge@acme.test", userClient = SC.acme)
        val gid = user.postItem(acmeCreate, mapOf(GDF.entries to listOf(expense(currentYear))))[GDF.gedraId].toOptStr()!!
        // A merge editing only the amount sends no year, so the single-field rule never fires -- the save lands.
        val edit = mapOf(
            GED.action to GedraEditAction.addOrMerge.name, GE.traitId to ST.expenseReport,
            GE.data to mapOf(ST.perItemAmount to 25.0),
        )
        user.postData(acmePatch, patchBody(gid, edit))
        val stored = service().queryGedra(asUser(user.userId), gid, GedraDataType.formDoc, ReadScope.ofClient(SC.acme))!!
        val data = stored.entries.first()[GE.data].toJsonMapOrEmpty()
        data[ST.year] shouldBe currentYear
        (data[ST.perItemAmount] as Number).toDouble() shouldBe 25.0
    }

    val acmeImport = clientPath(GEP.formDocImport, SC.acme)

    "an import cannot store what a create would refuse" {
        val user = TestUser.create(cxt, "prep-import@acme.test", userClient = SC.acme)
        // The default is to reject the whole import on an invalid entry, so a future year fails just as a create
        // does -- the validation is a guarantee about stored data on every write path, not only the create/patch.
        user.expectError(
            EXC.badInput, acmeImport,
            data = mapOf(GIF.data to mapOf(GDF.entries to listOf(expense(currentYear + 5)))),
        )
    }

    "a forgiving import discards the entry the function refuses" {
        val user = TestUser.create(cxt, "prep-import-forgive@acme.test", userClient = SC.acme)
        val result = user.postData(
            acmeImport,
            mapOf(
                GIF.data to mapOf(GDF.entries to listOf(expense(currentYear + 5))),
                GIF.forgiveInvalidEntries to true,
            ),
        )
        // The refused entry is thrown away and counted as an invalid entry, exactly as a schema failure would be;
        // its document has no survivors, so nothing is created.
        result[GIF.imported].toJsonListOfMaps().isEmpty() shouldBe true
        val discarded = result[GIF.discarded].toJsonListOfMaps()
        discarded.any { it[GIF.category].toOptStr() == GIF.invalidEntry && it[GE.traitId].toOptStr() == ST.expenseReport } shouldBe true
    }

    "the function itself validates and passes through" {
        val ctx = asUser(72801L)
        // A future year throws mkInput (a 400); the current year returns the data unchanged (a pure validation).
        shouldThrow<KdrException> {
            prepForSaveData(ctx, GedraDataType.formDoc, ST.expenseReport, mapOf(ST.year to currentYear + 5), SC.acme)
        }
        val ok = mapOf(ST.year to currentYear, ST.perItemAmount to 3.0)
        prepForSaveData(ctx, GedraDataType.formDoc, ST.expenseReport, ok, SC.acme) shouldBe ok
    }

    "a trait with no save-time function is untouched" {
        val ctx = asUser(72802L)
        val data = mapOf("topic" to "anything", "notes" to "no function here")
        prepForSaveData(ctx, GedraDataType.formDoc, ST.questionnaire, data, SC.acme) shouldBe data
    }
})
