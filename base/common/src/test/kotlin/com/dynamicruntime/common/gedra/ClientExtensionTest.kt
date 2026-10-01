package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.mergeFragmentLayers
import com.dynamicruntime.common.content.overlayPrecedence
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The merge rules of a client built on a template (issue #945), over the configs alone: what the client's own
 * configuration takes out of the template's copy, kind by kind, and what its definition inherits.
 */
class ClientExtensionTest : StringSpec({
    val cxt = KdrCxt("extension", KdrInstanceConfig("extension", ENV.unit, ENV.liveSource))
    val tpl = "tpl"
    val kid = "kid"

    fun def(clientId: String, extends: String? = null) = ClientDef(
        clientId = clientId, name = clientId, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = extends,
    )

    val template = gedraConfig(cxt, "base", "client.tpl", tpl) {
        defineClient(def(tpl))
        type("Shared") { type = SCT.kObject; property("code", "A code.") }
        type("Other") { type = SCT.kObject; property("x", "X.") }
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property("note", "A note.") }
        trait("ScoreEntry", "score", setOf(GedraDataType.formDoc), "A score.") { property("score", "A score.") }
        stateTrait("TplStateEntry", "tplState", setOf(GedraDataType.formDoc), StateTraitClass.derived, "State.") {
            property("s", "S.")
        }
        cfact("flagA", "tpl", "A.")
        cfact("flagB", "tpl", "B.")
        workflow("audit", WfEntry.normal) { task("t", "T") { trait("note"); save("s", "Save", WfSaveKind.edit) } }
        workflow("review", WfEntry.normal) { task("t", "T") { trait("note"); save("s", "Save", WfSaveKind.edit) } }
        workflow("tplCreate", WfEntry.creation) { task("t", "T") { trait("note"); save("s", "Create") } }
        traitUsage("note", "Note", "note")
        fragmentOverlay("home", mapOf("home" to mapOf("title" to "Template title", "brand" to "TPL")))
    }

    fun copyOf(own: List<GedraConfig>) =
        ClientExtension.clones(kid, tpl, listOf(template), own, GedraConfigOrigin.source).single()

    "with nothing of the client's own, the copy is the template's, under the client, without its definition" {
        val copy = copyOf(emptyList())
        copy.gedraId.client shouldBe kid
        copy.name shouldBe "${CEXT.clonePrefix}tpl_base"
        copy.namespace shouldBe "client.tpl"
        copy.inheritedFrom shouldBe tpl
        copy.client shouldBe null
        copy.traits.keys shouldContainExactly setOf("note", "score")
        copy.workflows.keys shouldContainExactly setOf("audit", "review", "tplCreate")
        copy.usages.map { it.traitId } shouldContainExactly listOf("note")
        // State traits are global; a client's would be dropped.
        copy.stateTraits.shouldBeEmpty()
        copy.fragments.single().client shouldBe kid
        copy.fragments.single().inheritedFrom shouldBe tpl
    }

    "each kind the client defines replaces the template's of the same id, whole" {
        val own = gedraConfig(cxt, "own", "client.kid", kid) {
            defineClient(def(kid, tpl))
            type("client.tpl.Shared") { type = SCT.kObject; property("code", "The client's code.") }
            trait("KidScoreEntry", "score", setOf(GedraDataType.formDoc), "The client's score.") { property("stars", "Stars.") }
            cfact("flagB", "kid", "The client's B.")
            workflow("review", WfEntry.normal) { task("t", "T") { trait("note"); save("s", "Save", WfSaveKind.edit) } }
            traitUsage("score", "Score", "stars")
        }
        val copy = copyOf(listOf(own))
        copy.traits.keys shouldContainExactly setOf("note")
        // The client's `client.tpl.Shared` replaces the template's; the template's other types stay.
        ("client.tpl.Shared" in copy.defs) shouldBe false
        ("client.tpl.Other" in copy.defs) shouldBe true
        copy.cfacts.map { it.name } shouldContainExactly listOf("flagA")
        copy.workflows.keys shouldContainExactly setOf("audit", "tplCreate")
        // Usages are a set: a client that declares any declares all of them.
        copy.usages.shouldBeEmpty()
    }

    "a creation workflow of the client's own replaces the template's whatever its id" {
        val own = gedraConfig(cxt, "own", "client.kid", kid) {
            workflow("kidCreate", WfEntry.creation) { task("t", "T") { trait("note"); save("s", "Create") } }
        }
        copyOf(listOf(own)).workflows.keys shouldContainExactly setOf("audit", "review")
    }

    "the copy's overlays rank between a component's and the client's own" {
        val ranks = listOf(
            overlayPrecedence(null, stored = false),
            overlayPrecedence(kid, stored = false, inherited = true),
            overlayPrecedence(kid, stored = false),
            overlayPrecedence(kid, stored = true),
        )
        ranks shouldBe ranks.sorted().distinct()
        // Merged as the fragment service merges them: a key the client sets is the client's, one only the template
        // sets is the template's -- whichever order the layers arrive in.
        val base = FragmentSource("home", false, null, "base") { mapOf("home" to mapOf("title" to "Welcome", "brand" to "KDR", "intro" to "Hi")) }
        val mine = FragmentSource("home", true, kid, "kid", configName = "own") { mapOf("home" to mapOf("title" to "Kid title")) }
        val merged = mergeFragmentLayers("home", listOf(base, mine) + copyOf(emptyList()).fragments, kid)
        merged.content["home"] shouldBe mapOf("title" to "Kid title", "brand" to "TPL", "intro" to "Hi")
    }

    "the Customized counts are the client's own changes, not what it inherited" {
        val inherited = copyOf(emptyList()).fragments
        countCopyOverrides(inherited, kid) shouldBe 0
        val mine = FragmentSource("home", true, kid, "kid", configName = "own") { mapOf("home" to mapOf("title" to "Kid title")) }
        countCopyOverrides(inherited + mine, kid) shouldBe 1
    }

    // The merge is made only with a base a client may extend, so a refused base is reported as itself: here a
    // functional group the stored-only base includes would otherwise refuse the customer's production client first.
    "a base the client may not extend is reported as itself, not through the merge" {
        val base = gedraConfig(cxt, "base", "client.storedbase", "storedbase", GedraConfigOrigin.stored) {
            defineClient(def("storedbase").copy(includedTraits = listOf(CLD.allGlobal)))
        }
        val child = gedraConfig(cxt, "own", "client.cust", "cust") {
            defineClient(def("cust", "storedbase").copy(audience = ClientAudience.customer, usageType = ClientUsageType.production))
        }
        val collector = GedraConfigCollector().apply { add(cxt, base); add(cxt, child) }
        val message = shouldThrow<KdrException> { checkClientDefs(cxt, collector) }.message.shouldNotBeNull()
        message shouldContain "defined only in stored configuration"
    }

    "the definition takes the template's defaults and keeps its own" {
        val base = def(tpl).copy(
            webResourcesId = "tplres", includedTraits = listOf("note", "#allGlobal"), userLabels = listOf("vip", "both"),
            enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev), domainPrefix = "tpl",
        )
        val merged = ClientExtension.mergeDef(def(kid, tpl).copy(includedTraits = listOf("score", "note"), userLabels = listOf("both", "mine")), base)
        merged.webResourcesId shouldBe "tplres"
        merged.includedTraits shouldContainExactly listOf("note", "#allGlobal", "score")
        merged.userLabels shouldContainExactly listOf("vip", "both", "mine")
        merged.enabledEnvironments shouldBe setOf(ENV.unit, ENV.local)
        merged.domainPrefix shouldBe null
        ClientExtension.mergeDef(def(kid, tpl).copy(webResourcesId = "own"), base).webResourcesId shouldBe "own"
    }
})
