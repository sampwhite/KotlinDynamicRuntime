package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignPulledCopy
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Design View's copy of a list's entry label (issue #1748): an entry label that pulls a fragment key is named in the
 * definition read's pulled copy, as a label, description or hint is, so the inspector shows it as its words and edits it
 * at its key. Over a client of its own, whose trait's list field pulls its entry label from the `mail` file.
 */
class DesignEntryLabelTest : StringSpec({
    val cxt = TestInstances.default("designEntryLabel1748")
    val client = "dventry1748"
    val entryType = "SpeakersEntry"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait(entryType, "speakers", setOf(GedraDataType.formDoc), "Who speaks.") {
            property("people", "The speakers.") {
                type = SCT.array
                items { property("name", "A speaker's name.") }
            }
            layout(fragmentFileId = "mail") { field("people", entryLabel = """%{@t("common.footer")}""") }
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 9000L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    "an entry label that pulls a fragment key is named in the pulled copy, with the label, description and hint" {
        DesignPulledCopy.copySlots shouldContainExactly listOf(SL.label, SL.description, SL.hint, SL.entryLabel)
        val typeName = "${clientNamespace(client)}.${traitDataTypeName(entryType)}"
        val facts = DesignPulledCopy.facts(cxt.mkSubContext("read", client), setOf(typeName))
        val slots = facts[typeName].toJsonMapOrEmpty()["people"].toJsonMapOrEmpty()
        slots.keys.toList() shouldContainExactly listOf(SL.entryLabel)
        // The key it pulls, read against the layout's file, and the words this client reads there.
        val pull = slots[SL.entryLabel].toJsonMapOrEmpty()[DSV.pulls].toJsonListOfMaps().single()
        pull[COV.fileId] shouldBe "mail"
        pull[COV.namespaceField] shouldBe "common"
        pull[COV.key] shouldBe "footer"
        pull[COV.value].toOptStr().orEmpty() shouldStartWith "This message was sent automatically."
    }
})
