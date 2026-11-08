package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.JOIN
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.workflowDefStamp
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.confDemoConfigs
import com.dynamicruntime.kdn.confdemo.provisionConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The demo's deployment switches (issue #1668), each tested alone, at its default and turned on, and the `reference`
 * flavor's configuration, which turns both on:
 *
 * - [ConfDemo.fromCodeKey]: off, the conference demo is a data-only client its provisioning writes; on, its bundles are
 *   declared in code and provisioning makes only its users.
 * - [JOIN.visitorsEditConfigKey]: off, a visitor -- a user stamped with the role they joined a demo client as -- may not
 *   change that client's configuration, though the client's own users may, and the visitor may change a client they
 *   made for themselves; on, they may.
 *
 * Instances of their own, since each is a deployment's configuration.
 */
class DemoSwitchesTest : StringSpec({
    // Joining offered, as on a reference deployment, and visitors' edits at their default: off.
    val joinable = Startup.mkTestBootCxt("demoSwitchesJoin", "kdnDemoSwitchesJoin1668", mapOf(JOIN.offeredKey to true))
    provisionConfDemo(joinable)
    // The demo declared in code, and nothing else.
    val fromCode = Startup.mkTestBootCxt("demoSwitchesCode", "kdnDemoSwitchesCode1668", mapOf(ConfDemo.fromCodeKey to true))
    val reference = TestInstances.reference("demoSwitchesReference1668")

    fun codeBundles(on: KdrCxt) =
        GedraConfigLoadService.get(on).ownSourceOf(SchemaCollector.get(on).shouldNotBeNull(), ConfDemo.client).map { it.name }
    fun storedBundles(on: KdrCxt) =
        GedraConfigService.get(on).listConfigs(on.mkSubContext("demoSwitches", ConfDemo.client)).map { it.configId.baseId }.sorted()

    /** [by] joins the demo as [role], and the session moves to that user. */
    fun join(by: TestUser, role: String): Long =
        by.postData(JOIN.joinPath, mapOf(JOIN.client to ConfDemo.client, JOIN.role to role, JOIN.acknowledged to true))[JOIN.userId]
            .toOptLong().shouldNotBeNull()

    /** A dry run of a patch giving the caller's client a cfact in a bundle of its own: a change to its configuration. */
    val change = mapOf(
        CFEP.configs to listOf(
            mapOf(
                CFEP.name to "visitorTry",
                CFEP.edits to listOf(
                    mapOf(
                        CFEP.slot to CCT.cfactDef, GED.action to GedraEditAction.addOrReplace.name,
                        GE.data to mapOf(CCT.name to "visitorTried", CCT.group to "visitor", CCT.description to "A visitor tried."),
                    ),
                ),
            ),
        ),
        CFEP.dryRun to true,
    )

    "by default the demo is data-only; with its switch on it is declared in code, and provisioning makes only its users" {
        codeBundles(joinable).shouldBeEmpty()
        storedBundles(joinable) shouldBe confDemoConfigs().map { it.name }.sorted()

        codeBundles(fromCode) shouldBe confDemoConfigs().map { it.name }
        ClientService.get(fromCode).present(ConfDemo.client).shouldNotBeNull()
        val report = provisionConfDemo(fromCode)
        storedBundles(fromCode).shouldBeEmpty()
        report.users.size shouldBe 3
    }

    "a visitor's user carries the role they joined as; the demo's own users carry none" {
        val visitor = TestUser.create(joinable, "stamped@switches1668.test")
        val joined = join(visitor, ConfDemo.organizerRole)
        val users = UserService.get(joinable)
        users.queryByUserId(joinable, joined).shouldNotBeNull().joinedAs shouldBe ConfDemo.organizerRole
        val own = users.queryByLoginId(joinable, "organizer@${ConfDemo.client}.example")
        own.shouldNotBeNull().joinedAs.shouldBeNull()
    }

    "by default a visitor may not change a demo client's configuration, though its own users may, and their own client is theirs" {
        val visitor = TestUser.create(joinable, "visitor@switches1668.test")
        val publicUser = visitor.getData(AEP.selfInfo)[UPF.userId].toOptLong().shouldNotBeNull()
        join(visitor, ConfDemo.organizerRole)
        visitor.expectError(EXC.notAuthorized, CFEP.bundlePatch, change).toString() shouldContain
            "as a visitor, and this deployment does not let visitors change a demo client's configuration"

        // The demo's own organizer, who did not join it, may.
        TestUser.create(joinable, "organizer@${ConfDemo.client}.example", userClient = ConfDemo.client).postData(CFEP.bundlePatch, change)

        // A client the visitor makes for themselves is theirs, whatever the switch says.
        visitor.postData(AEP.switchUser, mapOf(AFLD.userId to publicUser))
        val made = visitor.postData(AEP.createOwnClient, mapOf(CLD.name to "Visitor's own"))[CLD.client].toOptStr().shouldNotBeNull()
        visitor.getData(AEP.selfInfo)[UPF.client] shouldBe made
        visitor.postData(CFEP.bundlePatch, change)
    }

    "the reference deployment declares the demo in code, and a visitor may change it" {
        codeBundles(reference) shouldBe confDemoConfigs().map { it.name }
        val visitor = TestUser.create(reference, "visitor@reference1668.test")
        visitor.selfClient() shouldBe CL.public
        join(visitor, ConfDemo.organizerRole)
        visitor.postData(CFEP.bundlePatch, change)

        // And in Design View (issue #1670): a change to the demo's code workflow is stored as an overlay of it.
        val flow = WorkflowService.get(reference).forClient(ConfDemo.client).workflow(ConfDemo.createTalkWorkflow).shouldNotBeNull().def
        visitor.postData(
            DSV.labelEdit,
            mapOf(DSV.workflowId to ConfDemo.createTalkWorkflow, DSV.label to "Submit a talk", DSV.basedOn to workflowDefStamp(flow)),
        )
        val stored = GedraConfigService.get(reference).listConfigs(reference.mkSubContext("demoSwitches", ConfDemo.client))
            .single { it.configId.baseId == ConfDemo.createTalkConfig }.entriesBySlot()[CCT.workflowDef].orEmpty().single()
        stored[CCT.overrides] shouldBe CCT.overridesCodeOverlayValue
        stored[CCT.definition] shouldBe mapOf(WFD.workflowId to ConfDemo.createTalkWorkflow, WFD.label to "Submit a talk")
    }
})
