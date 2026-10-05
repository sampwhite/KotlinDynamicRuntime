package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
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
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.layout
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
    val nameType = "kdr.core.NameData"

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

        val types = typesOf(admin.getData(viewPath, design))
        // The request's inline data type belongs to its trait's declaration, under `dataSchema`...
        val request = types.values.single { it[DSV.key] == DesignDemo.eventRequest }
        request[DSV.slot] shouldBe CCT.traitDef
        request[DSV.path] shouldBe CCT.dataSchema
        request[DSV.origin] shouldBe DesignOrigin.stored.name
        request.containsKey(DSV.alteredBy) shouldBe false
        // ...the shared contact type is a schema entry of its own...
        val contact = types.getValue("client.$client.${DesignDemo.contactType}")
        contact[DSV.slot] shouldBe CCT.schemaDef
        contact[DSV.origin] shouldBe DesignOrigin.stored.name
        // ...and the global name trait is shown as shared, which this client does not alter.
        val name = types.values.single { it[DSV.key] == GT.name }
        name[DSV.origin] shouldBe DesignOrigin.global.name
        name.containsKey(DSV.alteredBy) shouldBe false
    }

    "the definition read returns a stored trait's authored entry, with its bundle's revision" {
        val d = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to DesignDemo.eventRequest))
        d[DSV.origin] shouldBe DesignOrigin.stored.name
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

    "a shared type the client alters is credited to where it is declared, with the alteration beside it" {
        // A client whose stored configuration rewords the global name trait's data type (issue #1013).
        val altering = "altersName1013"
        val config = gedraConfig(cxt, "names", clientNamespace(altering), altering) {
            defineClient(
                ClientDef(
                    clientId = altering, name = altering, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    includedTraits = listOf(GT.name),
                ),
            )
            // Copy only: a client may reword a shared type's layout freely.
            type(nameType) { layout { field("name", label = "Your name") } }
            workflow("make", WfEntry.creation) {
                task("ask", "Ask") {
                    trait(GT.name)
                    save("create", "Create")
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", altering), config)
        GedraConfigReload.reloadClient(cxt, altering)
        val alteringAdmin = TestUser.create(cxt, "designer@$altering.test", level = ROLE.admin, userClient = altering)

        // On the page: declared by the global trait, altered by this client's stored config.
        val view = alteringAdmin.getData(clientPath(GEP.workflowView, altering), design)
        val address = typesOf(view).getValue(nameType)
        address[DSV.slot] shouldBe CCT.traitDef
        address[DSV.key] shouldBe GT.name
        address[DSV.origin] shouldBe DesignOrigin.global.name
        address[DSV.alteredBy].toJsonMapOrEmpty() shouldBe mapOf(DSV.origin to DesignOrigin.stored.name, DSV.config to "names")

        // The definition read: the trait as declared, and the alteration's own authored body beside it.
        val d = alteringAdmin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to GT.name))
        d[DSV.origin] shouldBe DesignOrigin.global.name
        d[DSV.entry].toJsonMapOrEmpty()[CCT.dataSchema].toString() shouldContain "maxLength"
        val altered = d[DSV.alteredBy].toJsonMapOrEmpty()
        altered[DSV.config] shouldBe "names"
        altered[DSV.key] shouldBe nameType
        altered[DSV.entry].toString() shouldContain "Your name"
        // Its bundle is the client's own stored one, so its revision is said.
        d[DSV.version] shouldBe 1

        // Asked by the type's name, the same: the trait that declares it, and the alteration.
        val viaType = alteringAdmin.getItem(DSV.definition, mapOf(DSV.slot to CCT.schemaDef, DSV.key to nameType))
        viaType[DSV.key] shouldBe GT.name
        viaType[DSV.alteredBy].toJsonMapOrEmpty()[DSV.config] shouldBe "names"
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
