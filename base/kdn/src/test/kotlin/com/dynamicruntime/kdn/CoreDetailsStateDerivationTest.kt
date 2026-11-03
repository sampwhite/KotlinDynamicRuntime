package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The core details state producer (issue #657): a form records its `derived` core details state on creation, without
 * any interactive workflow step, because `CoreDetailsStateDeriver` runs inside the create transaction. Exercised
 * through a **per-test dynamic client** (the `kdr-test-clients` technique) rather than the sample -- the deriver is
 * production `base/common` code, and a purpose-built client with a core details workflow requiring one trait proves the
 * whole path (deriver -> `writeState` -> states cache -> the `withStates` read) without depending on acme's shapes.
 */
class CoreDetailsStateDerivationTest : StringSpec({
    val cxt = TestInstances.default("coreDetailsState657")
    val client = "coreDetails657"

    // A write is attributed, so bind a sub-context to the client with a userId before writing its config.
    fun asClient(): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

    // One dynamic client, shared by both scenarios: two formDoc traits and a core details workflow that requires `detail`.
    // Trait ids are unique per instance, so the two scenarios are two forms in one client, not two clients.
    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("DetailEntry", "detail", setOf(GedraDataType.formDoc), "The detail the core details workflow requires.") {
            property("text", "A value.", required = true)
        }
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "An aside the core details workflow does not ask for.") {
            property("text", "A value.")
        }
        workflow("reviewForm", WfEntry.coreDetails) {
            task("only", "Review") { trait("detail"); save("save", "Save changes", WfSaveKind.edit) }
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(), config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    fun stateEntry(user: TestUser, gedraId: String, traitId: String): Map<String, Any?> {
        val row = user.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).first { it[GDF.gedraId] == gedraId }
        return row[GDF.states].toJsonListOfMaps().first { it[GE.traitId].toOptStr() == traitId }[GE.data].toJsonMapOrEmpty()
    }

    fun create(user: TestUser, traitId: String, text: String): String =
        user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to traitId, GE.data to mapOf("text" to text)))),
        )[GDF.gedraId].toOptStr().orEmpty()

    "a form carrying the core details workflow's required trait is complete and valid, and asserts both cfacts" {
        val gid = create(user, "detail", "here")

        val completion = stateEntry(user, gid, CDF.coreDetailsCompletion)
        completion[CDF.complete] shouldBe true
        completion[CDF.valid] shouldBe true
        completion[CDF.missingTraits].toJsonListOrEmpty().shouldBeEmpty()

        // The two facts are asserted into the shared cfacts state, which is what the eligibility bridge reads.
        val facts = stateEntry(user, gid, GT.cfacts)[GT.facts].toJsonListOrEmpty()
        facts shouldContain CDF.coreDetailsComplete
        facts shouldContain CDF.coreDetailsValid
    }

    "a form missing the core details workflow's required trait is incomplete, and names what is missing" {
        // Only a trait outside the core details present, so the core details workflow's `detail` is missing.
        val gid = create(user, "note", "aside")

        val completion = stateEntry(user, gid, CDF.coreDetailsCompletion)
        completion[CDF.complete] shouldBe false
        completion[CDF.missingTraits].toJsonListOrEmpty() shouldContainExactly listOf<Any?>("detail")
        // Present data is still valid (nothing the core details workflow collects is present to be wrong), so
        // coreDetailsValid stands and coreDetailsComplete does not -- absence of the good fact reads as "not ready".
        completion[CDF.valid] shouldBe true
        val facts = stateEntry(user, gid, GT.cfacts)[GT.facts].toJsonListOrEmpty()
        facts shouldContain CDF.coreDetailsValid
        facts shouldNotContain CDF.coreDetailsComplete
    }
})
