package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignOrigin
import com.dynamicruntime.common.gedra.DesignView
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.simulation.designDemoConfig
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Design View's backend (issue #972), over the demo client it was built to show: the request flag and its gate, the
 * workflow view's Design View block, and the definition read.
 *
 * The demo client is written as **stored** configuration the way the `design-demo` probe scenario writes it, so this
 * also guards the demo itself -- a demo that no longer loads would otherwise be found at demo time.
 */
class DesignViewTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("designView972", "designView972")
    val client = DesignDemo.client
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), designDemoConfig(cxt))
    GedraConfigReload.reloadClient(cxt, client)

    val admin = TestUser.create(cxt, "designer@$client.test", level = ROLE.admin, userClient = client)
    val user = TestUser.create(cxt, "member@$client.test", userClient = client)
    val viewPath = clientPath(GEP.workflowView, client)
    val design = mapOf(EP.view to DSV.design)

    fun typesOf(view: Map<String, Any?>): Map<String, Map<String, Any?>> =
        view[DSV.designBlock].toJsonMapOrEmpty()[DSV.types].toJsonMapOrEmpty().mapValues { it.value.toJsonMapOrEmpty() }

    "an administrator asking for Design View gets the block, and otherwise exactly the ordinary view" {
        val ordinary = admin.getData(viewPath)
        val explained = admin.getData(viewPath, design)
        ordinary.containsKey(DSV.designBlock) shouldBe false
        explained.containsKey(DSV.designBlock) shouldBe true
        // One decision, two presentations: Design View adds its block beside the view and changes nothing in it.
        (explained - DSV.designBlock) shouldBe ordinary
    }

    "anyone else asking for it gets the ordinary view, unchanged" {
        user.getData(viewPath, design) shouldBe user.getData(viewPath)
    }

    "an unknown view is refused when asked for explicitly" {
        user.expectError(400, viewPath, args = mapOf(EP.view to "nonsense"))
    }

    "the block names the workflow and every type the page draws, with where each was declared" {
        val block = admin.getData(viewPath, design)[DSV.designBlock].toJsonMapOrEmpty()
        val workflow = block[DSV.workflow].toJsonMapOrEmpty()
        workflow[DSV.slot] shouldBe CCT.workflowDef
        workflow[DSV.key] shouldBe DesignDemo.requestWorkflow
        workflow[DSV.origin] shouldBe DesignOrigin.stored.name
        workflow[DSV.config] shouldBe DesignDemo.configName
        workflow[DSV.editable] shouldBe true

        val types = typesOf(admin.getData(viewPath, design))
        // The request's inline data type belongs to its trait's declaration, under `dataSchema`...
        val request = types.values.single { it[DSV.key] == DesignDemo.eventRequest }
        request[DSV.slot] shouldBe CCT.traitDef
        request[DSV.path] shouldBe CCT.dataSchema
        request[DSV.editable] shouldBe true
        // ...the shared contact type is a schema entry of its own...
        val contact = types.getValue("client.$client.${DesignDemo.contactType}")
        contact[DSV.slot] shouldBe CCT.schemaDef
        contact[DSV.origin] shouldBe DesignOrigin.stored.name
        // ...and the global name trait is shown, but not as the client's to edit.
        val name = types.values.single { it[DSV.key] == GT.name }
        name[DSV.origin] shouldBe DesignOrigin.global.name
        name[DSV.editable] shouldBe false
    }

    "the definition read returns a stored trait's authored entry, with its bundle's revision" {
        val d = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to DesignDemo.eventRequest))
        d[DSV.origin] shouldBe DesignOrigin.stored.name
        d[DSV.editable] shouldBe true
        d[DSV.version] shouldBe 1
        d[DSV.published] shouldBe false
        val entry = d[DSV.entry].toJsonMapOrEmpty()
        entry[CCT.traitId] shouldBe DesignDemo.eventRequest
        // The authored body, layout copy and the gate included -- what an ordinary user is never sent.
        val data = entry[CCT.dataSchema].toJsonMapOrEmpty()
        data.containsKey(SCH.layout) shouldBe true
        data.toString() shouldContain CFACTS.hasAdminLevel
    }

    "a type a trait generates resolves to that trait, and a global trait reads as global" {
        val types = typesOf(admin.getData(viewPath, design))
        val dataType = types.entries.single { it.value[DSV.key] == DesignDemo.eventRequest }.key
        val viaType = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.schemaDef, DSV.key to dataType))
        viaType[DSV.slot] shouldBe CCT.traitDef
        viaType[DSV.key] shouldBe DesignDemo.eventRequest

        val name = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to GT.name))
        name[DSV.origin] shouldBe DesignOrigin.global.name
        name[DSV.editable] shouldBe false
        name.containsKey(DSV.version) shouldBe false
    }

    "the definition read covers schema types and workflows" {
        val contact = admin.getItem(
            DSV.definition, mapOf(DSV.slot to CCT.schemaDef, DSV.key to "client.$client.${DesignDemo.contactType}"),
        )
        contact[DSV.entry].toJsonMapOrEmpty()[CCT.schema].toJsonMapOrEmpty().containsKey(SCH.layout) shouldBe true
        val workflow = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.workflowDef, DSV.key to DesignDemo.reviewWorkflow))
        workflow[DSV.entry].toJsonMapOrEmpty()[CCT.definition].toJsonMapOrEmpty()[WFD.entry] shouldBe "survey"
    }

    "an unknown address is a 404 that names it, and the read is an administrator's" {
        val missing = admin.expectError(
            404, DSV.definition, args = mapOf(DSV.slot to CCT.traitDef, DSV.key to "noSuchTrait"),
        )
        missing.toString() shouldContain "noSuchTrait"
        user.expectError(403, DSV.definition, args = mapOf(DSV.slot to CCT.traitDef, DSV.key to DesignDemo.eventRequest))
    }

    "where a definition was declared follows its config" {
        fun config(origin: GedraConfigOrigin, owner: String) =
            gedraConfig(LiteCxt(), "probe", if (owner == "global") "kdr.probe" else "client.$owner", owner, origin) {}
        DesignView.originOf(null) shouldBe DesignOrigin.global
        DesignView.originOf(config(GedraConfigOrigin.source, "global")) shouldBe DesignOrigin.global
        DesignView.originOf(config(GedraConfigOrigin.source, client)) shouldBe DesignOrigin.source
        DesignView.originOf(config(GedraConfigOrigin.stored, client)) shouldBe DesignOrigin.stored
    }
})
