package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Editing a client's copy (issue #918): a key set for acme lands in a stored `copy` config, is published and served
 * at once under a new build id, keeps the file's other keys, and leaves globex alone; a second key of the same file
 * lands in the same config; a reset restores what the source config says; a change the trial refuses is not stored;
 * the keys listing offers the backend files too; and the scoping is the client overview's.
 */
class ClientCopyEditEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "clientCopyEdit", "clientCopyEditTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.createFullAdmin(cxt, "copy-admin@example.com")
    val fragments = MarkdownFragmentService.get(cxt)

    fun served(client: String, fileId: String = HFRAG.home, ns: String = "home", key: String = "brand"): Pair<String?, String?> {
        val effective = fragments.effectiveFragmentsFor(cxt, fileId, client)
        return effective?.content?.get(ns)?.get(key) to effective?.buildId
    }

    fun address(client: String, ns: String, key: String, fileId: String = HFRAG.home) =
        mapOf(COV.client to client, COV.fileId to fileId, COV.namespaceField to ns, COV.key to key)

    fun copyRow(client: String, ns: String, key: String, fileId: String = HFRAG.home): Map<String, Any?>? =
        admin.getItem(UADEP.clientOverrides, mapOf(COV.client to client))[COV.copy].toJsonListOrEmpty()
            .map { it.toJsonMapOrEmpty() }
            .firstOrNull { it[COV.fileId] == fileId && it[COV.namespaceField] == ns && it[COV.key] == key }

    "the keys listing offers every shipped key with the client's value, backend files included" {
        val keys = admin.getItems(CPY.keysPath, mapOf(COV.client to SC.acme)).associateBy {
            "${it[COV.fileId]}:${it[COV.namespaceField]}.${it[COV.key]}"
        }
        keys.getValue("${HFRAG.home}:home.brand")[COV.value] shouldBe "ACME KDR"
        keys.getValue("${HFRAG.home}:home.brand")[COV.audience] shouldBe "frontend"
        keys.getValue("${AFRAG.mail}:${MCOPY.common}.${MCOPY.footer}")[COV.audience] shouldBe "backend"
        // Where each is shown, as the declaration says (issue #933); a fixture nobody shows says nothing.
        keys.getValue("${HFRAG.home}:home.brand")[COV.shownOn].toOptStr().orEmpty() shouldContain "app bar"
        keys.getValue("${AFRAG.mail}:${MCOPY.common}.${MCOPY.footer}")[COV.shownOn].toOptStr() shouldNotBe null
        keys.getValue("${SF.content}:${SF.welcome}.${SF.title}").containsKey(COV.shownOn) shouldBe false
        // The fragment check reports it too.
        val checked = fragments.checkFragments(cxt).filter { it.client == null }.associateBy { it.fileId }
        checked.getValue(HFRAG.home).shownOn.orEmpty() shouldContain "app bar"
        checked.getValue(SF.content).shownOn shouldBe null
        // Another client's value is its own.
        admin.getItems(CPY.keysPath, mapOf(COV.client to SC.globex))
            .first { it[COV.fileId] == HFRAG.home && it[COV.key] == "brand" }[COV.value] shouldBe "KDR"
    }

    "setting a key lands in a new copy config, published and served at once, and leaves the rest alone" {
        val (before, buildBefore) = served(SC.acme)
        before shouldBe "ACME KDR"
        val (_, globexBefore) = served(SC.globex)

        val result = admin.postData(CPY.setPath, address(SC.acme, "home", "brand") + mapOf(COV.value to "Acme Co"))
        result[COV.configName] shouldBe CPY.copyConfigName
        result[COV.value] shouldBe "Acme Co"
        result[CPY.stored] shouldBe true
        result[CPY.issues].toJsonListOrEmpty().shouldBeEmpty()

        val (after, buildAfter) = served(SC.acme)
        after shouldBe "Acme Co"
        buildAfter shouldNotBe buildBefore
        result[CPY.buildId] shouldBe buildAfter
        // The file's other keys, and the other client, are as they were.
        served(SC.acme, key = "title").first shouldBe "Welcome"
        served(SC.acme, ns = HFRAG.formsNs, key = HFRAG.noWorkflows).first shouldBe "No workflows available."
        served(SC.globex) shouldBe ("KDR" to globexBefore)
        // Stored and published, under the conventional name.
        val bundle = admin.getItems(ACEP.bundles, mapOf(CFEP.client to SC.acme)).single { it[CFEP.name] == CPY.copyConfigName }
        bundle[CFEP.published] shouldBe true
        // The overrides report attributes it to the stored config, and keeps the source value for a revert.
        val row = copyRow(SC.acme, "home", "brand")!!
        row[COV.origin] shouldBe GedraConfigOrigin.stored.name
        row[COV.sourceValue] shouldBe "ACME KDR"
        row[COV.shownOn].toOptStr().orEmpty() shouldContain "app bar"
        // acme's source override of the fixture is reported with no place it is shown.
        copyRow(SC.acme, SF.welcome, SF.title, fileId = SF.content)!!.containsKey(COV.shownOn) shouldBe false
    }

    "a second key of the same file joins the same config; a key of another file, too, when nothing else overlays it" {
        admin.postData(CPY.setPath, address(SC.acme, HFRAG.formsNs, HFRAG.noWorkflows) + mapOf(COV.value to "Nothing yet."))[COV.configName] shouldBe CPY.copyConfigName
        served(SC.acme, ns = HFRAG.formsNs, key = HFRAG.noWorkflows).first shouldBe "Nothing yet."
        served(SC.acme).first shouldBe "Acme Co"
        // A key the client's source config never set: now overridden from the same config.
        admin.postData(CPY.setPath, address(SC.acme, "home", "title") + mapOf(COV.value to "Hello, Acme"))[COV.configName] shouldBe CPY.copyConfigName
        served(SC.acme, key = "title").first shouldBe "Hello, Acme"
        copyRow(SC.acme, "home", "title")!![COV.sourceValue].toOptStr() shouldBe null
        // The bundle holds one entry for the file with all three keys.
        val bundle = admin.getItem(ACEP.bundle, mapOf(CFEP.client to SC.acme, CFEP.name to CPY.copyConfigName))
        val slots = bundle[CFEP.slots].toJsonMapOrEmpty()
        val homeEntry = slots[CCT.fragmentDef].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }.single { it[CCT.fileId] == HFRAG.home }
        homeEntry[CCT.content].toJsonMapOrEmpty().keys shouldBe setOf("home", HFRAG.formsNs)
    }

    "a reset removes the stored value, so the source value shows again; nothing stored is refused" {
        val result = admin.postData(CPY.resetPath, address(SC.acme, "home", "brand"))
        result[COV.value] shouldBe "ACME KDR"
        result[CPY.stored] shouldBe false
        served(SC.acme).first shouldBe "ACME KDR"
        copyRow(SC.acme, "home", "brand")!![COV.origin] shouldBe GedraConfigOrigin.source.name
        // The other stored keys survived the reset.
        served(SC.acme, key = "title").first shouldBe "Hello, Acme"
        // Now the source alone sets it: a second reset has nothing to remove.
        val refused = admin.expectError(EXC.badInput, CPY.resetPath, data = address(SC.acme, "home", "brand"))
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "source code or the shipped copy"
        // A key the client never overrides at all, likewise.
        admin.expectError(EXC.badInput, CPY.resetPath, data = address(SC.globex, "home", "title"))
    }

    "a change the trial refuses is not stored: an unresolved pull in a backend file, a key no file declares" {
        val before = served(SC.acme, fileId = AFRAG.mail, ns = MCOPY.common, key = MCOPY.footer)
        val refused = admin.expectError(
            EXC.badInput, CPY.setPath,
            data = address(SC.acme, MCOPY.common, MCOPY.footer, fileId = AFRAG.mail) + mapOf(COV.value to "See %{@t(\"nope.x.y\")}."),
        )
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "problem"
        served(SC.acme, fileId = AFRAG.mail, ns = MCOPY.common, key = MCOPY.footer) shouldBe before
        // Nothing of it is stored either: the mail file has no entry in the copy config.
        val slots = admin.getItem(ACEP.bundle, mapOf(CFEP.client to SC.acme, CFEP.name to CPY.copyConfigName))[CFEP.slots].toJsonMapOrEmpty()
        slots[CCT.fragmentDef].toJsonListOrEmpty().map { it.toJsonMapOrEmpty()[CCT.fileId] } shouldBe listOf(HFRAG.home)
        // A key no shipped file declares would be stored and never read, so it is refused before any write.
        admin.expectError(EXC.notFound, CPY.setPath, data = address(SC.acme, "home", "noSuchKey") + mapOf(COV.value to "x"))
    }

    "an edit lands in the stored config that already sets the key, and a config with a draft of its own is refused" {
        // Two published stored configs of globex's, each overlaying `home` with one key.
        val svc = GedraConfigService.get(cxt)
        val writer = cxt.mkSubContext("seed", SC.globex).also { it.userId = 9180L }
        for ((name, key, value) in listOf(Triple("wordsA", "title", "Globex welcome"), Triple("wordsB", "intro", "Globex intro"))) {
            val row = svc.writeConfig(writer, gedraConfig(cxt, name, "globexWords", SC.globex) { fragmentOverlay(HFRAG.home, mapOf("home" to mapOf(key to value))) })
            svc.publish(writer, row.configId)
        }
        GedraConfigReload.reloadClient(cxt, SC.globex)
        // Setting `intro` goes to wordsB, which holds it -- not wordsA, which merely overlays the file first.
        admin.postData(CPY.setPath, address(SC.globex, "home", "intro") + mapOf(COV.value to "Globex, introduced"))[COV.configName] shouldBe "wordsB"
        served(SC.globex, key = "intro").first shouldBe "Globex, introduced"
        // A reset of a key the second config holds is found there.
        admin.postData(CPY.resetPath, address(SC.globex, "home", "title"))[COV.configName] shouldBe "wordsA"
        served(SC.globex, key = "title").first shouldBe "Welcome"
        // Give wordsA a draft of its own through the bundle API: a new revision on top of the published head, unpublished.
        val bundle = admin.getItem(ACEP.bundle, mapOf(CFEP.client to SC.globex, CFEP.name to "wordsA"))
        admin.postData(
            ACEP.bundleWrite,
            mapOf(CFEP.client to SC.globex, CFEP.name to "wordsA", CFEP.namespaceField to bundle[CFEP.namespaceField],
                CFEP.slots to mapOf(CCT.fragmentDef to listOf(mapOf(CCT.fileId to HFRAG.home, CCT.content to mapOf("home" to mapOf("title" to "Draft title")))))),
        )
        admin.getItems(ACEP.bundles, mapOf(CFEP.client to SC.globex)).single { it[CFEP.name] == "wordsA" }[CFEP.published] shouldBe false
        // A copy edit of that key would publish the draft: refused, naming the config; the draft stays unpublished.
        val refused = admin.expectError(EXC.badInput, CPY.setPath, data = address(SC.globex, "home", "title") + mapOf(COV.value to "Nope"))
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "wordsA"
        admin.getItems(ACEP.bundles, mapOf(CFEP.client to SC.globex)).single { it[CFEP.name] == "wordsA" }[CFEP.published] shouldBe false
        served(SC.globex, key = "title").first shouldBe "Welcome"
    }

    "a client-scoped administrator edits their own client and may not name another" {
        val scoped = TestUser.create(cxt, "copy-scoped@acme.test", level = ROLE.admin, userClient = SC.acme)
        scoped.getItems(CPY.keysPath).map { "${it[COV.fileId]}:${it[COV.key]}" } shouldContain "${HFRAG.home}:brand"
        val own = address(SC.acme, "home", "brand") - COV.client
        scoped.postData(CPY.setPath, own + mapOf(COV.value to "Acme, by Acme"))[COV.value] shouldBe "Acme, by Acme"
        served(SC.acme).first shouldBe "Acme, by Acme"
        scoped.expectError(EXC.notAuthorized, CPY.setPath, data = address(SC.globex, "home", "brand") + mapOf(COV.value to "Nope"))
        scoped.expectError(EXC.notAuthorized, CPY.keysPath, args = mapOf(COV.client to SC.globex))
        served(SC.globex).first shouldBe "KDR"
    }
})
