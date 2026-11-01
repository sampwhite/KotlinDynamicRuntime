package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A client's listing columns on the client page (issue #1491): the catalog, a new column stored with the client's
 * definition, a change where the column is stored -- a heading renamed in place, a column id made and kept -- removal,
 * the deployment's defaults made the client's own, and a sandbox's drafts. A client per case, on the shared instance.
 */
class ClientUsagesEditTest : StringSpec({
    val cxt = TestInstances.default("clientUsages1491")
    val svc = GedraConfigService.get(cxt)

    fun defineClient(client: String, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 14910L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name), sandbox = sandbox,
                    ),
                )
                trait("TalkEntry", "talk", setOf(GedraDataType.formDoc), "A talk.") {
                    property("level", "Who it is for.") {
                        option("beginner", "Beginners")
                        option("advanced", "Experts")
                    }
                    property("firstTalk", "The speaker's first talk.") { type = SCT.boolean }
                    property("minutes", "How long it runs.") { type = SCT.integer }
                    property("heldOn", "The day it is held.") { dayOnlyDate() }
                    property("tags", "Words it is found by.") {
                        type = SCT.array
                        items { type = SCT.string }
                    }
                }
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun column(label: String, display: String, extra: Map<String, Any?> = emptyMap()) =
        mapOf(CCT.traitId to "talk", CCT.label to label, CCT.display to display) + extra

    fun save(user: TestUser, column: Map<String, Any?>, previousLabel: String? = null) =
        user.postData(UADEP.clientUsageSave, listOfNotNull(CUS.column to column, previousLabel?.let { CUS.previousLabel to it }).toMap())

    fun labels(client: String) = SchemaService.get(cxt).listingColumnsFor(client).map { it.label }

    "a client with none of its own lists the deployment's columns, and the traits a column may read" {
        val client = "usageCatalog1491"
        defineClient(client)
        val catalog = admin(client).getItem(UADEP.clientUsages)
        catalog[CUS.defaults] shouldBe true
        catalog[CUS.newColumnsIn] shouldBe "main"
        val name = catalog[CUS.columns].toJsonListOfMaps().single()
        name[CCT.traitId] shouldBe GT.name
        name[CUS.readsAs] shouldBe UsageKind.string.name
        // The defaults are changed here, by making them the client's own; they are stored nowhere yet.
        name.containsKey(CUS.configName) shouldBe false
        name.containsKey(CUS.notEditable) shouldBe false
        val talk = catalog[CUS.traits].toJsonListOfMaps().single { it[CCT.traitId] == "talk" }
        val fields = talk[CUS.fields].toJsonListOfMaps().associateBy { it[CUS.field] }
        // An array is no column's value, so it is not offered.
        fields.keys shouldBe setOf("level", "firstTalk", "minutes", "heldOn")
        fields.mapValues { it.value[CUS.readsAs] } shouldBe mapOf(
            "level" to "string", "firstTalk" to "boolean", "minutes" to "number", "heldOn" to "date",
        )
        fields.getValue("level")[CUS.hasChoices] shouldBe true
        fields.getValue("heldOn")[CUS.label] shouldBe "Held on"
    }

    "a new column keeps the defaults, made the client's own, in the configuration holding its definition" {
        val client = "usageAdd1491"
        defineClient(client)
        val chief = admin(client)
        val saved = save(chief, column("Minutes", $$"${minutes}"))
        saved[CIT.configName] shouldBe "main"
        saved[CPY.mode] shouldBe EDM.live
        saved[CUS.column].toJsonMapOrEmpty().containsKey(CCT.columnId) shouldBe false
        labels(client) shouldBe listOf("Name", "Minutes")
        SchemaService.get(cxt).listingColumnsFor(client).last().kind shouldBe UsageKind.number
        val catalog = chief.getItem(UADEP.clientUsages)
        catalog[CUS.defaults] shouldBe false
        catalog[CUS.columns].toJsonListOfMaps().map { it[CUS.configName] } shouldBe listOf("main", "main")
    }

    "a second column of a trait gets a column id from its heading, kept when the heading changes" {
        val client = "usageRename1491"
        defineClient(client)
        val chief = admin(client)
        save(chief, column("Minutes", $$"${minutes}"))
        save(chief, column("Level", $$"${level}"))[CUS.column].toJsonMapOrEmpty()[CCT.columnId] shouldBe "level"
        // A heading is part of the key, so a rename replaces the entry where it stands.
        save(chief, column("Audience", $$"${level}", mapOf(CCT.substring to true)), previousLabel = "Level")
        labels(client) shouldBe listOf("Name", "Minutes", "Audience")
        val audience = SchemaService.get(cxt).listingColumnsFor(client).last()
        audience.columnKey shouldBe "level"
        audience.usage.substring shouldBe true
        // A heading its trait already has, and a change that changes nothing, are refused.
        chief.expectError(EXC.badInput, UADEP.clientUsageSave, mapOf(CUS.column to column("Minutes", $$"${level}")))
        chief.expectError(
            EXC.badInput, UADEP.clientUsageSave,
            mapOf(CUS.column to column("Minutes", $$"${minutes}"), CUS.previousLabel to "Minutes"),
        )
        chief.expectError(
            EXC.notFound, UADEP.clientUsageSave,
            mapOf(CUS.column to column("Gone", $$"${minutes}"), CUS.previousLabel to "Never"),
        )
    }

    "the trial refuses a column that would add a problem, and nothing is changed" {
        val client = "usageTrial1491"
        defineClient(client)
        val chief = admin(client)
        save(chief, column("Minutes", $$"${minutes}"))
        // A presentation that does not parse, and a kind its field contradicts.
        chief.expectError(
            EXC.badInput, UADEP.clientUsageSave,
            mapOf(CUS.column to column("Minutes", $$"${minutes}", mapOf(CCT.presentation to $$"${value")), CUS.previousLabel to "Minutes"),
        )
        chief.expectError(
            EXC.badInput, UADEP.clientUsageSave,
            mapOf(CUS.column to column("Minutes", $$"${minutes}", mapOf(CCT.kind to "date")), CUS.previousLabel to "Minutes"),
        )
        SchemaService.get(cxt).listingColumnsFor(client).last().usage.presentation shouldBe null
        SchemaService.get(cxt).listingColumnsFor(client).last().kind shouldBe UsageKind.number
    }

    "removing columns; removing the last of its own brings the defaults back" {
        val client = "usageRemove1491"
        defineClient(client)
        val chief = admin(client)
        fun remove(label: String, traitId: String = "talk") =
            chief.postData(UADEP.clientUsageRemove, mapOf(CCT.traitId to traitId, CCT.label to label))
        // The one default cannot go while it is all the client shows.
        chief.expectError(EXC.badInput, UADEP.clientUsageRemove, mapOf(CCT.traitId to GT.name, CCT.label to "Name"))
        save(chief, column("Minutes", $$"${minutes}"))
        remove("Name", GT.name)[CIT.configName] shouldBe "main"
        labels(client) shouldBe listOf("Minutes")
        remove("Minutes")
        labels(client) shouldBe listOf("Name")
        chief.getItem(UADEP.clientUsages)[CUS.defaults] shouldBe true
        chief.expectError(EXC.notFound, UADEP.clientUsageRemove, mapOf(CCT.traitId to "talk", CCT.label to "Minutes"))
    }

    "a client with a sandbox saves its columns as drafts, which only the sandbox runs" {
        val client = "usageSandbox1491"
        defineClient(client, sandbox = true)
        val chief = admin(client)
        val saved = chief.postData(UADEP.clientUsageSave, mapOf(CUS.column to column("Minutes", $$"${minutes}"), CPY.mode to EDM.draft))
        saved[CPY.mode] shouldBe EDM.draft
        labels(client) shouldBe listOf("Name")
        labels(sandboxOf(client)) shouldBe listOf("Name", "Minutes")
        // The catalog shows what the sandbox runs, drafts included.
        chief.getItem(UADEP.clientUsages)[CUS.columns].toJsonListOfMaps().map { it[CCT.label] } shouldBe listOf("Name", "Minutes")
        // A save its page said would be a draft is refused where it would be live: that client has no sandbox.
        val plain = "usageNoSandbox1491"
        defineClient(plain)
        admin(plain).expectError(
            EXC.conflict, UADEP.clientUsageSave, mapOf(CUS.column to column("Minutes", $$"${minutes}"), CPY.mode to EDM.draft),
        )
        labels(plain) shouldBe listOf("Name")
    }
})
