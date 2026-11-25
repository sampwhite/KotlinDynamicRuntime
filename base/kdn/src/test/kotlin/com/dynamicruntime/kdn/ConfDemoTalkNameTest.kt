package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ColumnWidth
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.ListingColumn
import com.dynamicruntime.common.gedra.formTraitName
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.provisionConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

/**
 * The conference demo calls the platform's `kdr:name` trait **Talk title** (issue #1545): a layout-only alteration of its data
 * type in the `base` bundle names it, introduces it with an icon, and labels its field **Title**. On a copy of the demo
 * of its own (`confdemo<suffix>`).
 */
class ConfDemoTalkNameTest : StringSpec({
    val cxt = TestInstances.default("confDemoTalkName1545")
    val client = provisionConfDemo(cxt, "t1545").clients.single()
    val nameData = "${GCFG.globalNamespace}.${traitDataTypeName(GT.nameEntry)}"

    "the demo names the platform's name trait Talk title, wherever a trait is named" {
        // The rule every page and message names a trait by.
        formTraitName(cxt, client, GT.name) shouldBe "Talk title"
        // A client that takes the trait as it is still calls it Name.
        formTraitName(cxt, null, GT.name) shouldBe "Name"
        // The workflow editor's catalog, which names the traits a workflow may collect.
        val admin = TestUser.create(cxt, "admin@$client.example", ROLE.admin, userClient = client)
        admin.getItem(UADEP.clientWorkflows)[CWF.traits].toJsonListOfMaps()
            .single { it[CCT.traitId] == GT.name }[CWF.name] shouldBe "Talk title"
    }

    "the creation form heads the trait Talk title, introduces it with the icon, and labels its field Title" {
        val speaker = TestUser.create(cxt, "speaker@$client.example", userClient = client)
        val view = speaker.getData(GEP.workflowView, mapOf(WFD.workflowId to ConfDemo.createTalkWorkflow))
        val layout = view[WVF.fieldLayouts].toJsonMapOrEmpty()[nameData].toJsonMapOrEmpty()
        layout[SL.label] shouldBe "Talk title"
        layout[SL.description].toOptStr().orEmpty() shouldStartWith "![A presentation screen](img:${ConfDemo.talkIcon})"
        layout[SL.schemaFields].toJsonListOfMaps().single { it[SL.field] == GT.nameField }[SL.label] shouldBe "Title"
        // The icon the introduction names is one the deployment serves.
        ComponentImageService.get(cxt).served[ConfDemo.talkIcon] shouldNotBe null
    }

    // Issue #1855: the form's id, from the demo's counter, a narrow column of the forms list found by its search.
    "a talk is given its id from the demo's counter, and the forms list shows it in a narrow column" {
        val speaker = TestUser.create(cxt, "idspeaker@$client.example", userClient = client)
        val issued = speaker.postData(GEP.counterIssue, mapOf(CTR.counter to ConfDemo.talkCounter))
        val id = issued[CTR.value].toOptStr().orEmpty()
        id shouldStartWith "T-"
        id.length shouldBe 5
        speaker.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to ConfDemo.createTalkWorkflow, GDF.taskId to ConfDemo.nameTalkTask, GDF.saveId to ConfDemo.createSave,
                GDF.entries to listOf(
                    mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "Counting talks")),
                    mapOf(GE.traitId to ConfDemo.submission, GE.data to mapOf(ConfDemo.talkId to id)),
                ),
                CTR.counterTokens to listOf(issued[CTR.token]),
            ),
        )
        val listing = speaker.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(ConfDemo.talkId to id))
        listing[EP.items].toJsonListOfMaps().size shouldBe 1
        val columns = ListingColumn.listFrom(listing[EP.summary].toJsonMapOrEmpty()[GDF.columns])
        columns.map { it.label } shouldBe listOf("Talk", "Talk id", "Track")
        columns.single { it.columnId == ConfDemo.talkId }.width shouldBe ColumnWidth.narrow
    }

    "a speaker's talks have different titles, and the refusal says which field, for the form to show it under (issues #1851, #1918)" {
        val speaker = TestUser.create(cxt, "titles@$client.example", userClient = client)
        val path = clientPath(GEP.formDocCreate, client)
        fun talk(title: String) = mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to title))))
        speaker.postItem(path, talk("Kotlin for Two"))
        speaker.expectError(EXC.badInput, path, talk("kotlin for two!"))[EP.errorMessage] shouldBe
            "${GT.name}, data.${GT.nameField}: Value 'kotlin for two!' is already used by another of your forms, as " +
            "'Kotlin for Two'. Choose a different one."
        // Another speaker's talk may have it.
        TestUser.create(cxt, "titles2@$client.example", userClient = client).postItem(path, talk("Kotlin for Two"))
        // And an administrator adapts the trait, its uniqueness one of the editor's own moves.
        val admin = TestUser.create(cxt, "adapter@$client.example", ROLE.admin, userClient = client)
        val narrowing = admin.getItem(UADEP.clientTraits)[CIT.traits].toJsonListOfMaps().single { it[CIT.traitId] == GT.name }
        narrowing[CIT.narrowing].toJsonMapOrEmpty()[CIT.unique] shouldBe listOf(GT.nameField)
    }
})
