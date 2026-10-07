package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigRow
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Saving several keys of a client's copy at once (issue #1062): `/clientAdmin/client/copy/apply` sets and resets the
 * keys of one file in one save -- one config, one publish, one new build id -- or one draft for a client with a
 * sandbox; a batch the checks refuse leaves every key as it was; and a key changed twice, a reset with nothing
 * stored, or keys held by two stored configs are refused. One booted instance; a client per case.
 */
class ClientCopyApplyTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("clientCopyApply1062", "clientCopyApply1062")
    val svc = GedraConfigService.get(cxt)
    val fragments = MarkdownFragmentService.get(cxt)

    /** Defines [client] in stored configuration, published and reloaded, and returns its administrator. */
    fun defineClient(client: String, sandbox: Boolean = false): TestUser {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 10620L }
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

    /** A stored config of [client]'s overlaying `home` with [keys] of the `home` namespace, published. */
    fun overlayHome(client: String, name: String, keys: Map<String, String>) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 10620L }
        val row = svc.writeConfig(setup, gedraConfig(cxt, name, clientNamespace(client), client) { fragmentOverlay(HFRAG.home, mapOf("home" to keys)) })
        svc.publish(setup, row.configId)
        GedraConfigReload.reloadClient(cxt, client)
    }

    /** The editors' own stored config of [client]'s, as the latest revision has it. */
    fun copyConfig(client: String): GedraConfigRow? =
        svc.readLatest(cxt.mkSubContext("check", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))

    fun served(client: String, ns: String, key: String, fileId: String = HFRAG.home): String? =
        fragments.effectiveFragmentsFor(cxt, fileId, client)?.content?.get(ns)?.get(key)

    fun buildId(client: String, fileId: String = HFRAG.home): String? = fragments.effectiveFragmentsFor(cxt, fileId, client)?.buildId

    fun set(ns: String, key: String, value: String) = mapOf(COV.namespaceField to ns, COV.key to key, COV.value to value)
    fun reset(ns: String, key: String) = mapOf(COV.namespaceField to ns, COV.key to key, CPY.reset to true)
    fun apply(fileId: String, vararg changes: Map<String, Any?>) = mapOf(COV.fileId to fileId, CPY.changes to changes.toList())

    fun keysOf(result: Map<String, Any?>): Map<String, Map<String, Any?>> =
        result[CPY.keys].toJsonListOfMaps().associateBy { "${it[COV.namespaceField]}.${it[COV.key]}" }

    "keys of a file are set together: one config, live at once under one new build id; a mixed batch resets and sets" {
        val client = "applylive"
        val admin = defineClient(client)
        val before = buildId(client)

        val result = admin.postData(
            CPY.applyPath,
            apply(HFRAG.home, set("home", "brand", "Live Co"), set("home", "title", "Hello"), set(HFRAG.formsNs, HFRAG.noWorkflows, "None yet.")),
        )
        result[COV.configName] shouldBe CPY.copyConfigName
        result[CPY.mode] shouldBe EDM.live
        val keys = keysOf(result)
        keys.getValue("home.brand")[COV.value] shouldBe "Live Co"
        keys.getValue("home.title")[CPY.stored] shouldBe true
        keys.getValue("${HFRAG.formsNs}.${HFRAG.noWorkflows}")[COV.value] shouldBe "None yet."
        served(client, "home", "brand") shouldBe "Live Co"
        served(client, "home", "title") shouldBe "Hello"
        result[CPY.buildId] shouldBe buildId(client)
        buildId(client) shouldNotBe before
        // One entry for the file holding every key, published.
        val copy = copyConfig(client)!!
        copy.entriesBySlot()[CCT.fragmentDef].orEmpty().single()[CCT.content].toJsonMapOrEmpty().keys shouldBe setOf("home", HFRAG.formsNs)
        copy.isPublished shouldBe true

        // A reset beside a set: the reset key reads the shipped copy again, the set one its new value.
        val mixed = keysOf(admin.postData(CPY.applyPath, apply(HFRAG.home, reset("home", "brand"), set("home", "intro", "Welcome in."))))
        mixed.getValue("home.brand")[CPY.stored] shouldBe false
        mixed.getValue("home.brand")[COV.value] shouldBe "KDR"
        served(client, "home", "brand") shouldBe "KDR"
        served(client, "home", "intro") shouldBe "Welcome in."
        served(client, "home", "title") shouldBe "Hello"
    }

    "a batch the checks refuse changes nothing, however many of its keys were fine" {
        val client = "applyrefused"
        val admin = defineClient(client)
        val footer = served(client, MCOPY.common, MCOPY.footer, fileId = AFRAG.mail)
        val refused = admin.expectError(
            EXC.badInput, CPY.applyPath,
            data = apply(
                AFRAG.mail,
                set(MCOPY.common, MCOPY.footer, "Fine."),
                set(MCOPY.common, MCOPY.htmlStyle, "See %{@t(\"nope.x.y\")}."),
            ),
        )
        // The refusal leads with the key it faults, which is how the page finds it.
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "${MCOPY.common}.${MCOPY.htmlStyle}"
        served(client, MCOPY.common, MCOPY.footer, fileId = AFRAG.mail) shouldBe footer
        copyConfig(client) shouldBe null
    }

    "a key changed twice, a set and reset at once, a reset with nothing stored and an unknown key are refused" {
        val client = "applybad"
        val admin = defineClient(client)
        admin.expectError(EXC.badInput, CPY.applyPath, data = apply(HFRAG.home, set("home", "brand", "A"), reset("home", "brand")))
            .let { it[EP.errorMessage].toOptStr().orEmpty() shouldContain "more than once" }
        admin.expectError(EXC.badInput, CPY.applyPath, data = apply(HFRAG.home, set("home", "brand", "A") + mapOf(CPY.reset to true)))
            .let { it[EP.errorMessage].toOptStr().orEmpty() shouldContain "not both" }
        admin.expectError(EXC.badInput, CPY.applyPath, data = apply(HFRAG.home, set("home", "brand", "A"), reset("home", "title")))
            .let { it[EP.errorMessage].toOptStr().orEmpty() shouldContain "home.title" }
        admin.expectError(EXC.notFound, CPY.applyPath, data = apply(HFRAG.home, set("home", "brand", "A"), set("home", "noSuchKey", "B")))
        admin.expectError(EXC.badInput, CPY.applyPath, data = apply(HFRAG.home))
        // None of it was stored.
        served(client, "home", "brand") shouldBe "KDR"
    }

    "keys held by two stored configs are refused, naming both; a new key joins the config holding the others" {
        val client = "applysplit"
        val admin = defineClient(client)
        overlayHome(client, "wordsA", mapOf("title" to "Title A"))
        overlayHome(client, "wordsB", mapOf("intro" to "Intro B"))
        val refused = admin.expectError(EXC.badInput, CPY.applyPath, data = apply(HFRAG.home, set("home", "title", "T"), set("home", "intro", "I")))
        refused[EP.errorMessage].toOptStr().orEmpty().let {
            it shouldContain "wordsA"
            it shouldContain "wordsB"
        }
        served(client, "home", "title") shouldBe "Title A"
        // `intro` is wordsB's, so `brand` -- which nothing stored sets -- goes there with it, not to wordsA.
        admin.postData(CPY.applyPath, apply(HFRAG.home, set("home", "brand", "Split Co"), set("home", "intro", "Intro too")))[COV.configName] shouldBe "wordsB"
        served(client, "home", "brand") shouldBe "Split Co"
        served(client, "home", "intro") shouldBe "Intro too"
    }

    "a client with a sandbox saves the batch as a draft its sandbox shows" {
        val client = "applydraft"
        val admin = defineClient(client, sandbox = true)
        val result = admin.postData(CPY.applyPath, apply(HFRAG.home, set("home", "brand", "Draft Co"), set("home", "title", "Draft title")))
        result[CPY.mode] shouldBe EDM.draft
        keysOf(result).getValue("home.brand")[COV.value] shouldBe "Draft Co"
        served(sandboxOf(client), "home", "brand") shouldBe "Draft Co"
        served(sandboxOf(client), "home", "title") shouldBe "Draft title"
        served(client, "home", "brand") shouldBe "KDR"
        result[CPY.issues].toJsonListOrEmpty().size shouldBe 0
    }
})
