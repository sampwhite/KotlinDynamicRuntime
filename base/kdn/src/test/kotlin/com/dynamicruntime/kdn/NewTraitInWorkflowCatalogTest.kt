package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * A trait a client has just made is offered to the workflow it makes next (issue #1425). A self-serve client has a
 * sandbox, so the trait is a draft only the sandbox runs; the workflow editor's catalog lists the sandbox's workflows,
 * and must offer the sandbox's traits with them -- and its user labels -- not only the published ones the client itself
 * runs. A client without a sandbox is offered its own, as it runs them. Over the shared instance, a client per case.
 */
class NewTraitInWorkflowCatalogTest : StringSpec({
    val cxt = TestInstances.default("newTraitCatalog1425")

    "a draft trait is offered to a new workflow at once, and a workflow collecting it saves as a draft" {
        val person = TestUser.create(cxt, "maker@newtrait1425.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Trait Shop"))[CLD.client].toOptStr()!!
        val design = TraitDesign(null, "Site safety", "How a site is kept safe.", listOf(TraitFieldDesign(null, "Notes", TraitFieldKind.text)))
        val made = person.postData(UADEP.clientTraitDesign, mapOf(CIT.design to design.toInfo()))
        made[CPY.mode] shouldBe EDM.draft
        val traitId = made[CIT.design].toJsonMapOrEmpty()[CIT.traitId].toOptStr()!!

        person.getItem(UADEP.clientWorkflows, mapOf(CLD.client to client))[CWF.traits].toJsonListOfMaps()
            .map { it[CCT.traitId] } shouldContain traitId

        val slots = WfTemplateSlots(null, WFT.collect, "Safety check", listOf(WfTaskSlot("collect", "Safety", listOf(WfTraitRef(traitId)))))
        person.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))[CPY.mode] shouldBe EDM.draft
    }

    "a user label just added to the definition is offered to a new workflow's visibility at once" {
        val person = TestUser.create(cxt, "labels@newtrait1425.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Label Shop"))[CLD.client].toOptStr()!!
        person.postData(UADEP.clientDefinitionSet, mapOf(CLD.userLabels to listOf("siteLead")))[CPY.mode] shouldBe EDM.draft
        person.getItem(UADEP.clientWorkflows, mapOf(CLD.client to client))[CWF.userLabels].toJsonListOfStrings() shouldContain "siteLead"
    }

    "a client without a sandbox is offered its own traits, as it runs them" {
        val client = "plain1425"
        GedraConfigService.get(cxt).writeConfig(
            cxt.mkSubContext("setup", client).also { it.userId = 14250L },
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("InspectionEntry", "inspection", setOf(GedraDataType.formDoc), "An inspection.") { property("notes", "What was found.") }
            },
        )
        GedraConfigReload.reloadClient(cxt, client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        admin.getItem(UADEP.clientWorkflows)[CWF.traits].toJsonListOfMaps().map { it[CCT.traitId] } shouldContain "inspection"
    }
})
