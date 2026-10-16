package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientStoredEdit
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Every editor that saves a client's configuration is held to how its page told its user the save would take effect
 * (issue #1191), by the one check in `ClientStoredEdit.target`: a save sent as a draft that would be live is refused
 * with a 409 and nothing is written, since a change the person meant to look at first must not reach the client's
 * people; one sent as live that is a draft goes ahead, and says it is a draft. And a save naming a sandbox this node no
 * longer carries is refused, whatever was expected. One booted instance; a client per case.
 */
class ExpectedModeTest : StringSpec({
    val cxt = TestInstances.default("expectedMode1191")
    val svc = GedraConfigService.get(cxt)
    val notADraft = "not as a draft: it has no sandbox now. Nothing was changed."

    /** Defines [client] in stored configuration -- with a sandbox when asked -- published and loaded; its administrator. */
    fun defineClient(client: String, sandbox: Boolean = false): TestUser {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11910L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                    ),
                )
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    fun brand(client: String): String? =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, client)?.content?.get("home")?.get("brand")

    fun brandKey() = mapOf(COV.fileId to HFRAG.home, COV.namespaceField to "home", COV.key to "brand")

    /** The refusal's message, having checked it is the 409 a page answers by reading the client again. */
    fun refused(user: TestUser, path: String, data: Map<String, Any?>): String {
        val env = user.expectError(EXC.conflict, path, data)
        env[EP.errorCode] shouldBe EDM.notAsExpected
        return env[EP.errorMessage].toString()
    }

    fun saved(user: TestUser, path: String, data: Map<String, Any?>): Map<String, Any?> {
        val env = user.client.sendJsonPostRequest(path, data)
        withClue(env) { env[EP.status].shouldBeNull() }
        return env[EP.results].toJsonMapOrEmpty()
    }

    "a save sent as a draft for a client that would take it live is refused by every editor, and nothing is written" {
        val client = "modelive"
        val admin = defineClient(client)
        val shipped = brand(client)
        val draft = CPY.mode to EDM.draft

        refused(admin, CPY.setPath, brandKey() + (COV.value to "Not yet") + draft) shouldContain notADraft
        refused(
            admin, CPY.applyPath,
            mapOf(COV.fileId to HFRAG.home, CPY.changes to listOf(mapOf(COV.namespaceField to "home", COV.key to "brand", COV.value to "Not yet")), draft),
        ) shouldContain notADraft
        refused(admin, MNU.setPath, mapOf(COV.itemId to HMENU.docs, MNU.label to "Papers", draft)) shouldContain notADraft
        refused(admin, UADEP.clientDefinitionSet, mapOf(CLD.name to "Renamed", draft)) shouldContain notADraft
        // Nothing written: the editors' own configuration was not even made, and the definition is as it was.
        ClientStoredEdit.editConfig(cxt.mkSubContext("check", client), client).shouldBeNull()
        brand(client) shouldBe shipped
        ClientService.get(cxt).known(client)?.name shouldBe "Client $client"

        // A reset of what is held, likewise, and the same saves sent as live go through.
        saved(admin, CPY.setPath, brandKey() + (COV.value to "Live Co") + (CPY.mode to EDM.live))[CPY.mode] shouldBe EDM.live
        saved(admin, MNU.setPath, mapOf(COV.itemId to HMENU.docs, MNU.label to "Papers", CPY.mode to EDM.live))[CPY.mode] shouldBe EDM.live
        refused(admin, CPY.resetPath, brandKey() + draft) shouldContain notADraft
        refused(admin, MNU.resetPath, mapOf(COV.itemId to HMENU.docs, draft)) shouldContain notADraft
        brand(client) shouldBe "Live Co"
        // Absent, nothing is asked: a script, or an older page.
        saved(admin, UADEP.clientDefinitionSet, mapOf(CLD.name to "Renamed"))[CPY.mode] shouldBe EDM.live
        // A mode that is neither is a caller's mistake, not a save that asks nothing: the endpoints' options refuse
        // it, and so does the check itself, for a caller in process.
        shouldThrow<KdrException> { ClientStoredEdit.target(cxt, client, "check", expected = "Draft") }.code shouldBe EXC.badInput
    }

    "a save sent as live for a client with a sandbox goes ahead as a draft, and says so" {
        val client = "modedraft"
        val admin = defineClient(client, sandbox = true)
        val shipped = brand(client)
        val live = CPY.mode to EDM.live
        saved(admin, CPY.setPath, brandKey() + (COV.value to "Previewed") + live)[CPY.mode] shouldBe EDM.draft
        saved(admin, MNU.setPath, mapOf(COV.itemId to HMENU.docs, MNU.label to "Papers", live))[CPY.mode] shouldBe EDM.draft
        saved(admin, UADEP.clientDefinitionSet, mapOf(CLD.name to "Renamed", live))[CPY.mode] shouldBe EDM.draft
        // The sandbox shows it; the client's people are not shown it until it is published.
        brand(sandboxOf(client)) shouldBe "Previewed"
        brand(client) shouldBe shipped
    }

    // Issue #1192: a client whose definition asks for a sandbox this node does not carry -- here, one whose latest
    // definition, which its sandbox is built from, is not enabled in this environment -- would have a save made a
    // draft that no sandbox shows. Refused, whatever was expected.
    "a save for a client whose sandbox this node does not carry is refused" {
        val client = "modeabsent"
        val admin = defineClient(client, sandbox = true)
        ClientService.get(cxt).isPresent(sandboxOf(client)) shouldBe true
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11911L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.local), sandbox = true,
                    ),
                )
            },
        )
        GedraConfigReload.reloadClient(cxt, client)
        ClientService.get(cxt).isPresent(client) shouldBe true
        ClientService.get(cxt).isPresent(sandboxOf(client)) shouldBe false
        refused(admin, CPY.setPath, brandKey() + (COV.value to "Nowhere")) shouldContain "asks for a sandbox, which this node does not carry"
    }

    // An administrator who sees every client can name a sandbox, and their save of it is a draft of its parent's
    // configuration. Once the sandbox is removed, the parent may run its latest configuration again, so such a "draft"
    // would be live at the parent's next load.
    "a save naming a sandbox this node no longer carries is refused, whatever was expected" {
        val client = "moderemoved"
        val admin = defineClient(client, sandbox = true)
        val full = TestUser.createFullAdmin(cxt, "full@moderemoved.test")
        val sandbox = sandboxOf(client)
        saved(full, CPY.setPath, brandKey() + (COV.client to sandbox) + (COV.value to "Drafted"))[CPY.mode] shouldBe EDM.draft

        saved(admin, UADEP.clientSandbox, mapOf(CLD.sandbox to false))
        ClientService.get(cxt).isPresent(sandbox) shouldBe false
        val removed = "'$sandbox' is a sandbox this node no longer carries: its parent's sandbox has been removed. Nothing was changed."
        refused(full, CPY.setPath, brandKey() + (COV.client to sandbox) + (COV.value to "Too late")) shouldContain removed
        refused(full, CPY.setPath, brandKey() + (COV.client to sandbox) + (COV.value to "Too late") + (CPY.mode to EDM.draft)) shouldContain removed
        // What the earlier "draft" became once the sandbox went: the parent runs its latest configuration again, so it
        // is live -- what a further save named for the sandbox would have done too.
        GedraConfigReload.reloadClient(cxt, client)
        brand(client) shouldBe "Drafted"
    }
})
