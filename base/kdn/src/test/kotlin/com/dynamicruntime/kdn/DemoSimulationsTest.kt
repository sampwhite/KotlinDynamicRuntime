package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.JOIN
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WDSP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.workflowDefStamp
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.simulation.ListingDemo
import com.dynamicruntime.common.simulation.Simulations
import com.dynamicruntime.common.simulation.listingDemoOwnerIndex
import com.dynamicruntime.common.simulation.listingDemoUser
import com.dynamicruntime.common.simulation.provisionDesignDemo
import com.dynamicruntime.common.simulation.provisionListingDemo
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.confDemoConfigs
import com.dynamicruntime.kdn.confdemo.provisionConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * The demo simulations and switches (issue #1739 gathered these): the design demo (issue #997) and its logistics workflow
 * (issue #1071), the listing demo (issue #1316), the conference demo's provisioning (issue #1455), and the deployment
 * switches the `reference` flavor turns on (issue #1668). Each part below is one of the specs that used to be a file of
 * its own, kept whole.
 */
class DemoSimulationsTest : StringSpec({
    include(simulationTests)
    include(designDemoLogisticsTests)
    include(listingDemoTests)
    include(confDemoProvisionTests)
    include(demoSwitchesTests)
})

/**
 * Simulations (issue #997): the `design-demo` simulation provisions what its report says -- a client defined in data
 * and users to sign in as -- a suffix makes an independent copy, a rerun is safe, and the listing offers it to a caller
 * with test access, who alone may run it (issue #1149).
 */
private val simulationTests = stringSpec {
    val cxt = TestInstances.default("simulation997")
    // An ordinary signed-in user, env-authed: whoever is testing the instance. Test access, not a role, is what a
    // simulation asks of its caller (issue #1149).
    val tester = TestUser.create(cxt, "tester@simulation997.test").also { it.client.alwaysWithTestAccess() }
    val designDemo = SIM.pathRoot + DesignDemo.simulationName

    fun usersOf(report: Map<String, Any?>) = report[SIM.users].toJsonListOfMaps()

    "design-demo provisions the client and the users its report lists" {
        val report = tester.postData(designDemo, emptyMap())
        report[SIM.clients].toJsonListOfStrings() shouldBe listOf(DesignDemo.client)
        report[SIM.startPage] shouldNotBe null
        // Two administrators since the reviewer (issue #1071); the designer is the one by that address.
        val designer = usersOf(report).single { it[SIM.level] == ROLE.admin && it[SIM.email].toString().startsWith("designer@") }
        designer[SIM.client] shouldBe DesignDemo.client

        // Signing in as the reported designer reaches the client's workflow page in Design View.
        val signedIn = TestUser.create(cxt, designer[SIM.email] as String, level = ROLE.admin, userClient = DesignDemo.client)
        val view = signedIn.getData(clientPath(GEP.workflowView, DesignDemo.client), mapOf(EP.view to DSV.design))
        view.containsKey(DSV.designBlock) shouldBe true
    }

    "a rerun is safe: the same client, the same users" {
        val first = usersOf(tester.postData(designDemo, emptyMap())).map { it[SIM.email] }
        val second = usersOf(tester.postData(designDemo, emptyMap())).map { it[SIM.email] }
        second shouldBe first
    }

    "a suffix makes an independent copy, and a bad one is refused" {
        val report = tester.postData(designDemo, mapOf(SIM.suffix to "two"))
        report[SIM.clients].toJsonListOfStrings() shouldBe listOf("${DesignDemo.client}two")
        // Its types take its own namespace, so the copy shares no definition with the demo client.
        val schema = SchemaService.get(cxt)
        schema.storeFor("${DesignDemo.client}two").defs.keys shouldContain "client.${DesignDemo.client}two.${DesignDemo.contactType}"
        schema.storeFor(DesignDemo.client).defs.keys shouldContain "client.${DesignDemo.client}.${DesignDemo.contactType}"

        tester.expectError(400, designDemo, mapOf(SIM.suffix to "Two!")).toString() shouldContain "lowercase letters and digits"
    }

    "the listing offers the simulations to a caller with test access, in the catalog's shape" {
        val listed = tester.getData(SIM.list)[EI.endpoints].toJsonListOfMaps()
        listed.map { it[EI.path] } shouldContain designDemo
        // Only simulations: everything listed carries the tag.
        listed.all { SIM.tag in it[EI.tags].toJsonListOfStrings() } shouldBe true
    }

    // A caller without env auth on a test instance takes no advantage of it (issue #1149): to them neither the listing
    // nor a simulation exists, and the catalog, which confines them to the published API, lists neither.
    "a caller without env auth can neither list nor run a simulation" {
        val plain = TestUser.create(cxt, "plain@simulation997.test")
        plain.expectError(EXC.notFound, SIM.list)
        plain.expectError(EXC.notFound, designDemo, emptyMap())
        plain.getData("/schema/endpoints").toJsonMapOrEmpty()[EI.endpoints].toJsonListOfMaps()
            .none { it[EI.path] == designDemo || it[EI.path] == SIM.list } shouldBe true
    }
}

/**
 * The design demo's logistics workflow (issue #1071): a normal workflow whose form shows only the fields it arranges,
 * so it and the core details workflow each save their own part of one request, ending in a reviewer's approval -- a
 * step the designer, an administrator without the `reviewer` label, sees as someone else's. Over a fresh copy of the
 * demo, provisioned by its simulation with its labeled reviewer.
 */
private val designDemoLogisticsTests = stringSpec {
    val cxt = TestInstances.default("designDemoLogistics1071")
    val client = provisionDesignDemo(cxt, "logistics1071").clients.single()
    val designer = TestUser.create(cxt, "designer@$client.example", level = ROLE.admin, userClient = client)
    val requester = TestUser.create(cxt, "requester@$client.example", userClient = client)
    // An administrator, as the simulation makes them -- a reviewer reads forms other people own -- with the label.
    val reviewer = TestUser.create(cxt, "reviewer@$client.example", level = ROLE.admin, userClient = client)

    fun request(by: TestUser): String = by.postData(
        GEP.workflowSave,
        mapOf(
            WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to DesignDemo.submitSave,
            GDF.entries to listOf(
                mapOf(GE.traitId to DesignDemo.eventRequest, GE.data to mapOf(DesignDemo.title to "Offsite", DesignDemo.attendees to 30, DesignDemo.venue to "office")),
            ),
        ),
    )[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!
    fun answers(by: TestUser, gid: String): Map<String, Any?> = by.getItem(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.entries]
        .toJsonListOfMaps().single { it[GE.traitId] == DesignDemo.eventRequest }[GE.data].toJsonMapOrEmpty()
    fun approvalStep(by: TestUser, gid: String): Map<String, Any?> =
        by.getData(GEP.workflowView, mapOf(WFD.workflowId to DesignDemo.logisticsWorkflow, GDF.gedraId to gid))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == DesignDemo.approveTask }

    "the logistics save writes the fields its form shows, and keeps the request's other answers" {
        val gid = request(requester)
        requester.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to DesignDemo.logisticsWorkflow))
        requester.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.arrangeTask, GDF.saveId to DesignDemo.arrangeSave,
                GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to DesignDemo.eventRequest, GE.data to mapOf(DesignDemo.title to "Offsite", DesignDemo.venue to "hotel", DesignDemo.catering to true))),
            ),
        )
        // The headcount is the request's and the core details workflow's, not the logistics form's: it stays.
        answers(requester, gid) shouldBe mapOf(DesignDemo.title to "Offsite", DesignDemo.attendees to 30L, DesignDemo.venue to "hotel", DesignDemo.catering to true)
    }

    "the approval is the labeled reviewer's: the designer sees it as someone else's, and the reviewer approves" {
        val gid = request(requester)
        requester.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to DesignDemo.logisticsWorkflow))
        requester.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.arrangeTask, GDF.saveId to DesignDemo.arrangeSave,
                GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to DesignDemo.eventRequest, GE.data to mapOf(DesignDemo.title to "Offsite", DesignDemo.venue to "office"))),
            ),
        )
        // An administrator is not a reviewer: the step tells them to wait, and the approve endpoint refuses them.
        approvalStep(designer, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.text] shouldBe "A reviewer approves the plan."
        designer.expectError(
            403, GEP.workflowApprove,
            mapOf(GDF.gedraId to gid, GDF.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.approveTask),
        )
        approvalStep(reviewer, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.mode] shouldBe WDSP.defaultMode
        reviewer.postData(
            GEP.workflowApprove,
            mapOf(GDF.gedraId to gid, GDF.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.approveTask),
        )
        approvalStep(designer, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.text] shouldBe $$"The plan has been approved by ${approvedByName}."
    }
}

/** The listing demo (issue #1316): the counts it is asked for, topped up on a rerun, spread unevenly over owners. */
private val listingDemoTests = stringSpec {
    val cxt = TestInstances.default("listingDemo1316")

    fun formsOf(client: String, scope: ReadScope = ReadScope.ofClient(client)): Int {
        val bound = cxt.mkSubContext("check", client)
        return GedraDataService.get(bound).listGedras(bound, GedraDataType.formDoc, scope, limit = 0).numAvailable
    }

    "a run holds the counts asked for, and a rerun tops up rather than adding again" {
        val client = "${ListingDemo.client}t"
        provisionListingDemo(cxt, "t", formCount = 30, userCount = 12).clients shouldBe listOf(client)
        formsOf(client) shouldBe 30
        // The twelve, besides the administrator; one in six is left for the person to claim.
        val users = UserService.get(cxt).countActiveUsers(cxt, client)
        users.total shouldBe 13
        users.unclaimed shouldBe (0 until 12).count { !listingDemoUser(it, client).registered }

        provisionListingDemo(cxt, "t", formCount = 30, userCount = 12)
        formsOf(client) shouldBe 30
        UserService.get(cxt).countActiveUsers(cxt, client).total shouldBe 13

        provisionListingDemo(cxt, "t", formCount = 40, userCount = 15)
        formsOf(client) shouldBe 40
        UserService.get(cxt).countActiveUsers(cxt, client).total shouldBe 16
    }

    "the forms are spread unevenly: a few owners hold many, and some hold none" {
        val forms = ListingDemo.defaultForms
        val users = ListingDemo.defaultUsers
        val byOwner = (0 until forms).groupingBy { listingDemoOwnerIndex(it, users) }.eachCount()
        // Four in ten go to the heavy owners, so each of them holds a long listing of their own.
        (0 until ListingDemo.heavyOwners).sumOf { byOwner[it] ?: 0 } shouldBe forms * 4 / 10
        (0 until ListingDemo.heavyOwners).forEach { (byOwner[it] ?: 0) shouldBeGreaterThan 40 }
        // About two in five of the users own nothing.
        (users - byOwner.size) shouldBeInRange (users * 3 / 10)..(users / 2)
    }
}

/**
 * The conference demo's simulation provisions its committee member as an editor (issue #1455) -- on a fresh copy, and
 * over one an earlier run made an administrator, which a persistent store (a developer's Postgres, a reference node
 * provisioning at boot) still holds. Each case on a copy of its own (`confdemo<suffix>`).
 */
private val confDemoProvisionTests = stringSpec {
    val cxt = TestInstances.default("confDemoProvision1455")

    /** The committee member of [client] as stored: found, never made, by the simulation's own lookup. */
    fun committeeOf(client: String) = Simulations.provisionUser(cxt, "committee@$client.example", client, ROLE.user)

    "the committee member a fresh run provisions is an editor carrying the committee label" {
        val report = provisionConfDemo(cxt, "p1455a")
        val reviewer = committeeOf(report.clients.single())
        reviewer.persona shouldBe PERSONA.editor
        reviewer.roles shouldBe listOf(ROLE.user, ROLE.editor)
        reviewer.labels shouldBe listOf(ConfDemo.committeeLabel)
        // What the Simulations page signs in with asks for the same.
        val reported = report.users.single { it.email == reviewer.primaryId }
        reported.level shouldBe ROLE.user
        reported.capabilities shouldBe listOf(ROLE.editor)
    }

    "a run over a committee member made an administrator before makes it an editor, keeping its persona" {
        val client = ConfDemo.client + "p1455b"
        for (config in confDemoConfigs(cxt, client)) Simulations.provisionConfig(cxt, config)
        // As the simulation provisioned it before issue #1455.
        Simulations.provisionUser(cxt, "committee@$client.example", client, ROLE.admin, name = "Casey Committee")
        provisionConfDemo(cxt, "p1455b")
        val reviewer = committeeOf(client)
        reviewer.persona shouldBe PERSONA.admin
        reviewer.roles shouldBe listOf(ROLE.user, ROLE.editor)
        reviewer.labels shouldBe listOf(ConfDemo.committeeLabel)
    }
}

/**
 * The demo's deployment switches (issue #1668), each tested alone, at its default and turned on, and the `reference`
 * flavor's configuration, which turns both on:
 *
 * - [ConfDemo.fromCodeKey]: off, the conference demo is a data-only client its provisioning writes; on, its bundles are
 *   declared in code and provisioning makes only its users.
 * - [JOIN.visitorsEditConfigKey]: off, a visitor -- a user stamped with the role they joined a demo client as -- may not
 *   change that client's configuration, though the client's own users may, and the visitor may change a client they
 *   made for themselves; on, they may. Either way a visitor may not add or remove the client's sandbox (issue #1677).
 *
 * Instances of their own, since each is a deployment's configuration.
 */
private val demoSwitchesTests = stringSpec {
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

    // Issue #1677: a demo client is shared by every visitor, so whether it has a sandbox is the deployment's to set.
    "a visitor may not add or remove a demo client's sandbox, whether or not the deployment lets them edit it" {
        for ((on, email) in listOf(joinable to "nosandbox@switches1677.test", reference to "nosandbox@reference1677.test")) {
            val visitor = TestUser.create(on, email)
            join(visitor, ConfDemo.organizerRole)
            visitor.expectError(EXC.notAuthorized, UADEP.clientSandbox, mapOf(CLD.sandbox to true)).toString() shouldContain
                "whether it has a sandbox is set by the deployment"
            ClientService.get(on).isPresent(sandboxOf(ConfDemo.client)) shouldBe false
        }
    }

    "a visitor uses a demo client's sandbox the deployment added, where visitors may change its configuration" {
        // Its own instance: a sandbox on the demo changes it for every visitor.
        val sandboxed = Startup.mkTestBootCxt(
            "demoSwitchesSandbox", "kdnDemoSwitchesSandbox1677",
            mapOf(JOIN.offeredKey to true, ConfDemo.fromCodeKey to true, JOIN.visitorsEditConfigKey to true),
        )
        provisionConfDemo(sandboxed)
        // The demo's own organizer, who did not join it, adds the sandbox to the demo declared in code.
        TestUser.create(sandboxed, "organizer@${ConfDemo.client}.example", userClient = ConfDemo.client)
            .postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))[CLD.sandbox] shouldBe true

        val visitor = TestUser.create(sandboxed, "sandboxed@switches1677.test")
        join(visitor, ConfDemo.organizerRole)
        visitor.postData(AEP.openSandbox, emptyMap())
        visitor.postData(CFEP.bundlePatch, change - CFEP.dryRun)
        val facts = SchemaService.get(sandboxed)
        ("visitorTried" in facts.cfactsFor(sandboxOf(ConfDemo.client)).names) shouldBe true
        ("visitorTried" in facts.cfactsFor(ConfDemo.client).names) shouldBe false
        visitor.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("visitorTry")))
        ("visitorTried" in facts.cfactsFor(ConfDemo.client).names) shouldBe true
    }
}
