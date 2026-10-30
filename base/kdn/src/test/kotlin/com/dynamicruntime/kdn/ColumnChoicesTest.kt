package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
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
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A listing column that reads one field offers what the field's schema says it can be (issue #1500): its choices,
 * with their labels, or `true` and `false` for a boolean. The listing hands them to the table; a search matches a
 * value or its label; and the column sorts in the choices' declared order. Over the shared instance, with a client
 * of its own.
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
        }
        traitUsage("talk", "Level", $$"${level}", substring = true, columnId = "level")
        traitUsage("talk", "First talk", $$"${firstTalk}", columnId = "firstTalk")
        // Computed: deduces nothing, though it reads the choice field.
        traitUsage("talk", "Note", $$"${note} (${level})", columnId = "note")
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("choices", client).also { it.userId = 15000L }, config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)
    fun create(level: String, firstTalk: Boolean?, note: String) {
        val data = listOfNotNull("level" to level, firstTalk?.let { "firstTalk" to it }, "note" to note).toMap()
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "talk", GE.data to data))))
    }
    create("advanced", false, "A")
    create("beginner", true, "B")
    create("intermediate", null, "C")

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
        columns.getValue("firstTalk").choices.map { it.value } shouldBe listOf("true", "false")
        columns.getValue("note").choices shouldBe emptyList()
        // The row's value stays the stored one; the table shows the label.
        user.getItems(formDocs).first()[GDF.displayValues].toJsonListOfMaps().first { it[UF.columnId] == "level" }[UF.value] shouldBe
            "intermediate"
    }

    "a search matches a choice's value or its label, as the free-text term does" {
        notes(mapOf("level" to "advanced")) shouldBe listOf("A")
        notes(mapOf("level" to "Experts")) shouldBe listOf("A")
        notes(mapOf("levelContains" to "experi")) shouldBe listOf("C")
        notes(mapOf("firstTalk" to "true")) shouldBe listOf("B")
        notes(mapOf(EI.q to "beginners")) shouldBe listOf("B")
    }

    "a column with choices sorts in their declared order" {
        fun sorted(dir: String) = notes(mapOf(GSORT.sort to "${GSORT.displayColumnPrefix}level", GSORT.sortDir to dir))
        sorted(GSORT.asc) shouldBe listOf("B", "C", "A")
        sorted(GSORT.desc) shouldBe listOf("A", "C", "B")
    }
})
