package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.formTraitName
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.startup.SchemaService
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
        formTraitName(cxt, SchemaService.get(cxt).storeFor(client), GT.name) shouldBe "Talk title"
        // A client that takes the trait as it is still calls it Name.
        formTraitName(cxt, SchemaService.get(cxt).storeFor(null), GT.name) shouldBe "Name"
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
})
