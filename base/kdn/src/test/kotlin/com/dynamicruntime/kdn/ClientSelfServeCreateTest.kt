package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientSelfServeCreate
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.gedra.PlanLimits
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.IDD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.home.HFRAG

/**
 * A person's own self-serve client (issue #1113): created from `public`, one per person, on the free plan, with the
 * creator as its administrator and the session moved there. Over the shared instance.
 */
class ClientSelfServeCreateTest : StringSpec({
    val cxt = TestInstances.default("selfServe1113")

    "a person in public creates their client, and is its administrator there" {
        val person = TestUser.create(cxt, "maker@self1113.test")
        person.selfClient() shouldBe CL.public
        val result = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Maker's Shop!", CLD.description to "Mine."))
        val client = result[CLD.client].toOptStr().shouldNotBeNull()
        // The person names the client; the system names its id.
        client shouldMatch Regex("free_makersshop[a-z0-9]{6}")
        val def = result[CLD.definition].toJsonMapOrEmpty()
        def[CLD.name] shouldBe "Maker's Shop!"
        def[CLD.description] shouldBe "Mine."
        def[CLD.customerId] shouldBe null
        def[CLD.audience] shouldBe ClientAudience.customer.name
        def[CLD.usageType] shouldBe ClientUsageType.dev.name
        def[CLD.enabledEnvironments] shouldBe listOf(ENV.unit, ENV.local)
        // On the free plan, with a sandbox, so it runs only what is published.
        PlanLimits.planOf(cxt, client) shouldBe PLAN.free
        ClientService.get(cxt).present(sandboxOf(client)).shouldNotBeNull()
        GedraConfigService.get(cxt).publishedOnly(cxt, client) shouldBe true

        // The session moved to the creator's own admin user there.
        result[CLD.switched] shouldBe true
        val info = person.getData(AEP.selfInfo)
        info[UPF.client] shouldBe client
        info[UPF.userId] shouldBe result[CLD.userId]
        info[UPF.roles].toJsonListOfStrings() shouldContain ROLE.admin
    }

    "the shell config says which client the person made, so the home page offers to open it (issue #1228)" {
        fun made(user: TestUser) = user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.selfServeClient]
        val person = TestUser.create(cxt, "shell@self1228.test")
        made(person) shouldBe null
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Shell"))[CLD.client].toOptStr().shouldNotBeNull()
        // In the new client it is not said: the home page's card is for public.
        made(person) shouldBe null
        made(TestUser.create(cxt, "shell@self1228.test", userClient = CL.public)) shouldBe client
        // Carried here, so it is offered to open: no word of its being absent.
        TestUser.create(cxt, "shell@self1228.test", userClient = CL.public).getData(HEP.homeUiConfig)[UIC.state]
            .toJsonMapOrEmpty()[HFLD.selfServeClientAbsent] shouldBe null
        // Retired by the platform, it is still the person's one, and said to be absent rather than offered to open.
        TestUser.createFullAdmin(cxt, "platform@self1228.test")
            .postData(ADEP.clientRetirementSet, mapOf(CLD.client to client, CLD.retired to true))
        val state = TestUser.create(cxt, "shell@self1228.test", userClient = CL.public).getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()
        state[HFLD.selfServeClient] shouldBe client
        state[HFLD.selfServeClientAbsent] shouldBe true
        // A visitor who has not signed in acts in public too, and has made nothing.
        TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty()[UIC.state]
            .toJsonMapOrEmpty()[HFLD.selfServeClient] shouldBe null
    }

    "the new client's home copy is written and published with it, so its home page reads it at once (issue #1336)" {
        val person = TestUser.create(cxt, "branded@self1336.test")
        val client = person.postData(
            AEP.createOwnClient,
            mapOf(
                CLD.name to "Branded", CLD.homeBrand to " Maker Co ", CLD.homeTitle to "Welcome to Maker",
                CLD.homeIntro to "**Hello** there.", CLD.homeNote to "   ",
            ),
        )[CLD.client].toOptStr().shouldNotBeNull()
        // In the editors' own config, published -- though the client has a sandbox, whose copy edits are drafts.
        GedraConfigService.get(cxt).publishedOnly(cxt, client) shouldBe true
        // Read as the client's own, as the copy editor reads it: a client's configs are its own rows.
        val bound = cxt.mkSubContext("selfServe1336", client)
        val copyRow = GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))
            .shouldNotBeNull()
        copyRow.isPublished shouldBe true
        // What the client's home page reads: trimmed, and a blank note left out, so the shipped one stands.
        val home = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, client).shouldNotBeNull().content[HFRAG.homeNs]
            .shouldNotBeNull()
        home[HFRAG.brand] shouldBe "Maker Co"
        home[HFRAG.title] shouldBe "Welcome to Maker"
        home[HFRAG.intro] shouldBe "**Hello** there."
        val stored = copyRow.entriesBySlot()[CCT.fragmentDef].orEmpty().single()[CCT.content].toJsonMapOrEmpty()[HFRAG.homeNs]
            .toJsonMapOrEmpty()
        stored.keys shouldBe setOf(HFRAG.brand, HFRAG.title, HFRAG.intro)
    }

    "without home copy, the create writes no copy config" {
        val person = TestUser.create(cxt, "plain@self1336.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Plain"))[CLD.client].toOptStr().shouldNotBeNull()
        val bound = cxt.mkSubContext("selfServe1336", client)
        // Its definition is there, read the same way -- so the absence below is the copy config's, not the read's.
        GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, client, CLD.definitionConfigName)).shouldNotBeNull()
        GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName)) shouldBe null
    }

    "a wordmark past its limit is refused, and no client is made" {
        val person = TestUser.create(cxt, "long@self1336.test")
        person.expectError(400, AEP.createOwnClient, mapOf(CLD.name to "Long", CLD.homeBrand to "x".repeat(CLD.homeBrandMaxLength + 1)))
        // Refused before anything was made: the person's one client is still theirs to create.
        person.postData(AEP.createOwnClient, mapOf(CLD.name to "Long"))[CLD.client].toOptStr().shouldNotBeNull()
    }

    "only the home keys a self-serve create sets are taken, each trimmed, a blank one left out" {
        ClientSelfServeCreate.homeCopy(mapOf(HFRAG.brand to " B ", HFRAG.note to " ", HFRAG.intro to "I", "other" to "x")) shouldBe
            mapOf(HFRAG.home to mapOf(HFRAG.homeNs to mapOf(HFRAG.brand to "B", HFRAG.intro to "I")))
        ClientSelfServeCreate.homeCopy(mapOf(HFRAG.title to "  ")) shouldBe emptyMap()
    }

    "a person creates one, from public only" {
        val person = TestUser.create(cxt, "once@self1113.test")
        val first = person.postData(AEP.createOwnClient, mapOf(CLD.name to "First"))[CLD.client]
        // Back in public -- or as any other user of the same person -- the one is used.
        val again = TestUser.create(cxt, "once@self1113.test", userClient = CL.public)
        again.expectError(EXC.conflict, AEP.createOwnClient, mapOf(CLD.name to "Second"))
            .toString() shouldContain "You have created your client, '$first'; a person creates one."
        // Another client's user does not create one.
        val elsewhere = TestUser.create(cxt, "away@self1113.test", userClient = first.toOptStr())
        elsewhere.expectError(EXC.badInput, AEP.createOwnClient, mapOf(CLD.name to "Away"))
            .toString() shouldContain "A self-serve client is created from 'public'"
    }

    "the one is claimed by a guarded write, so two creates at once cannot both pass" {
        // Two requests read the identity before either writes: the second's claim is refused.
        val person = TestUser.create(cxt, "race@self1113.test")
        val users = UserService.get(cxt)
        val identityId = users.queryByUserId(cxt, person.userId).shouldNotBeNull().identityId
        val one = users.queryIdentityFresh(cxt, identityId).shouldNotBeNull()
        val two = users.queryIdentityFresh(cxt, identityId).shouldNotBeNull()
        one.identityData[IDD.selfServeClient] = "free_one"
        users.updateIdentity(cxt, one, guarded = true)
        two.identityData[IDD.selfServeClient] = "free_two"
        shouldThrow<KdrException> { users.updateIdentity(cxt, two, guarded = true) }.code shouldBe EXC.conflict
        users.queryIdentityFresh(cxt, identityId).shouldNotBeNull().identityData[IDD.selfServeClient] shouldBe "free_one"
    }

    "the usage type follows the deployment's environment" {
        ClientSelfServeCreate.usageTypeIn(ENV.prod) shouldBe ClientUsageType.production
        ClientSelfServeCreate.usageTypeIn(ENV.dev) shouldBe ClientUsageType.dev
        ClientSelfServeCreate.usageTypeIn(ENV.unit) shouldBe ClientUsageType.dev
    }
})
