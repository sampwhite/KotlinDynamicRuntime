package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GSORT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.ListingColumn
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.formDocsQueryDefName
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A listing column that reads one field is searched as the field's schema says (issues #1500, #1508): its choices,
 * with their labels, or `true` and `false` for a boolean; a range for a number. The listing hands the choices to the
 * table; a search matches a value or its label; the column sorts in the choices' declared order; and the client's
 * query type advertises the same. Over the shared instance, with a client of its own.
 */
class ColumnChoicesTest : StringSpec({
    val cxt = TestInstances.default("columnChoices1500")
    val client = "choices1500"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("TalkEntry", "talk", setOf(GedraDataType.formDoc), "A talk.") {
            property("level", "Who it is for.") {
                option("beginner", "Beginners")
                option("intermediate", "Some experience")
                option("advanced", "Experts")
            }
            property("firstTalk", "The speaker's first talk.") { type = SCT.boolean }
            property("note", "Anything else.")
            property("minutes", "How long it runs.") { type = SCT.integer }
        }
        traitUsage("talk", "Level", $$"${level}", substring = true, columnId = "level")
        traitUsage("talk", "First talk", $$"${firstTalk}", columnId = "firstTalk")
        // Computed: deduces nothing, though it reads the choice field.
        traitUsage("talk", "Note", $$"${note} (${level})", columnId = "note")
        // No kind: an integer field is searched by a range (issue #1508).
        traitUsage("talk", "Minutes", $$"${minutes}", columnId = "minutes")
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("choices", client).also { it.userId = 15000L }, config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)
    fun create(level: String, firstTalk: Boolean?, note: String, minutes: Int) {
        val data = listOfNotNull("level" to level, firstTalk?.let { "firstTalk" to it }, "note" to note, "minutes" to minutes).toMap()
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "talk", GE.data to data))))
    }
    create("advanced", false, "A", 120)
    create("beginner", true, "B", 30)
    create("intermediate", null, "C", 45)

    val formDocs = clientPath(GEP.formDocs, client)

    /** Each row's note, in the order the listing answered. */
    fun notes(query: Map<String, Any?> = emptyMap()): List<String> = user.getItems(formDocs, query).map { row ->
        row[GDF.displayValues].toJsonListOfMaps().first { it[UF.columnId] == "note" }[UF.value].toString().substringBefore(" (")
    }

    "a column of one field carries the field's choices; a computed one carries none" {
        val listing = user.client.sendJsonGetRequest(formDocs, emptyMap())
        val columns = ListingColumn.listFrom(listing[EP.summary].toJsonMapOrEmpty()[GDF.columns]).associateBy { it.columnId }
        columns.getValue("level").choices.map { it.value to it.label } shouldBe
            listOf("beginner" to "Beginners", "intermediate" to "Some experience", "advanced" to "Experts")
        // A boolean is a yes-or-no of its own (issue #1508), with no choices to suggest.
        columns.getValue("firstTalk").kind shouldBe UsageKind.boolean
        columns.getValue("firstTalk").choices shouldBe emptyList()
        columns.getValue("note").choices shouldBe emptyList()
        // The row's value stays the stored one; the table shows the label.
        val values = user.getItems(formDocs).map { row ->
            row[GDF.displayValues].toJsonListOfMaps().associate { it[UF.columnId] to it[UF.value] }
        }
        values.first { it["note"].toString().startsWith("C") }["level"] shouldBe "intermediate"
    }

    "a search matches a choice's value or its label, as the free-text term does" {
        notes(mapOf("level" to "advanced")) shouldBe listOf("A")
        notes(mapOf("level" to "Experts")) shouldBe listOf("A")
        notes(mapOf("levelContains" to "experi")) shouldBe listOf("C")
        notes(mapOf("firstTalk" to "true")) shouldBe listOf("B")
        // Read as a boolean: any spelling of yes, and no finds only the answered false.
        notes(mapOf("firstTalk" to "yes")) shouldBe listOf("B")
        notes(mapOf("firstTalk" to "false")) shouldBe listOf("A")
        notes(mapOf(EI.q to "beginners")) shouldBe listOf("B")
    }

    "a column with choices sorts in their declared order" {
        fun sorted(dir: String) = notes(mapOf(GSORT.sort to "${GSORT.displayColumnPrefix}level", GSORT.sortDir to dir))
        sorted(GSORT.asc) shouldBe listOf("B", "C", "A")
        sorted(GSORT.desc) shouldBe listOf("A", "C", "B")
    }

    "a column over an integer field with no kind is searched by a range and sorts as a number" {
        notes(mapOf("minutesMin" to 40)).toSet() shouldBe setOf("A", "C")
        notes(mapOf("minutesMin" to 40, "minutesMax" to 60)) shouldBe listOf("C")
        // As text, 120 would sort before 30.
        notes(mapOf(GSORT.sort to "${GSORT.displayColumnPrefix}minutes", GSORT.sortDir to GSORT.asc)) shouldBe listOf("B", "C", "A")
    }

    "the client's query type advertises the same: choices as open options, and a number range" {
        val query = SchemaService.get(cxt).storeFor(client).types.getValue(formDocsQueryDefName()).properties
        val level = query.getValue("level").valueType
        level.options?.map { it.label } shouldBe listOf("Beginners", "Some experience", "Experts")
        level.openOptions shouldBe true
        query.getValue("levelContains").valueType.openOptions shouldBe true
        query.getValue("firstTalk").valueType.jsonType shouldBe SCT.boolean
        // A number, though the field is an integer: a fractional bound is a harmless search, not a refusal.
        query.getValue("minutesMin").valueType.jsonType shouldBe SCT.number
        notes(mapOf("minutesMin" to "44.5", "minutesMax" to "45.5")) shouldBe listOf("C")
        // A computed column offers nothing.
        query.getValue("note").valueType.options shouldBe null
    }

    "a declared kind contradicting its field is refused at write" {
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val column = mapOf(
            CCT.traitId to "talk", CCT.label to "Minutes as text", CCT.display to $$"${minutes}", CCT.kind to "string",
            CCT.columnId to "minutesText",
        )
        val write = mapOf(
            CFEP.name to "more", CFEP.namespaceField to clientNamespace(client), CFEP.slots to mapOf(CCT.usageDef to listOf(column)),
        )
        admin.expectError(EXC.badInput, CFEP.bundleWrite, write)[EP.errorMessage].toString() shouldContain "minutesText"
    }
})
