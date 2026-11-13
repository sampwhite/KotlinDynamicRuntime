package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.sharedCopyPageProblems
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.page.PGW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Widget fences in copy are checked where the copy enters (issue #1792): a client's own copy when its configuration is
 * written, and copy a component ships at boot. The rules themselves are the kernel's (`PageDocumentTest`); this holds
 * that both doors apply them, with what each knows -- a client's copy may not place a kind meant for shipped copy, and a
 * fence's `copy` key is looked for in the file as the client has it.
 */
class PageCopyChecksTest : StringSpec({
    val cxt = TestInstances.default("pageCopyChecks")

    fun fence(json: String) = "```${PGW.infoString}\n$json\n```"

    "a client's copy with a sound fence is accepted, and one with a wrong fence is refused with its configuration" {
        val client = "pagecopy1792"
        fun write(intro: String) {
            val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                // The client's own facts: one sent to the frontend and one not, neither of which a page sees.
                cfact("pageShown", "Page", "A fact the frontend is sent.", toFrontend = true)
                cfact("pageHidden", "Page", "A fact the frontend is not sent.", toFrontend = false)
                fragmentOverlay(HFRAG.home) { namespace(HFRAG.homeNs) { key(HFRAG.intro, intro) } }
            }
            GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("pageCopy", client), config)
            GedraConfigReload.reloadClient(cxt, client)
        }
        // A kind any copy may place, copy from the file as shipped, and a condition on a fact sent to the frontend.
        val sound = """{"id": "cards", "widget": "cardsBlock", "block": "homeCards", "copy": "home.title", """ +
            """"cfactExpression": "kdr:hasAdminLevel"}"""
        write("Welcome.\n\n" + fence(sound))
        shouldThrow<Exception> { write(fence("""{"id": "demos", "widget": "joinableDemos"}""")) }.message.orEmpty() shouldContain
            "only copy a component ships may place"
        shouldThrow<Exception> { write(fence("""{"id": "x", "copy": "home.noSuchKey"}""")) }.message.orEmpty() shouldContain
            "which the page's fragment file does not have"
        shouldThrow<Exception> { write(fence("""{"id": "x", "copy": "home.title", "cfactExpression": "kdr:hasOperatorLevel"}""")) }
            .message.orEmpty() shouldContain "which a page never sees"
        // The client's own facts are about its forms, so a page never sees one (issue #1793), sent to the frontend or not.
        for (fact in listOf("pageShown", "pageHidden")) {
            shouldThrow<Exception> { write(fence("""{"id": "x", "copy": "home.title", "cfactExpression": "$fact"}""")) }
                .message.orEmpty() shouldContain "'$fact', which a page never sees"
        }
    }

    "copy a component ships is held to the same rules by the fragment check at boot" {
        fun source(content: Map<String, Map<String, String>>, overlay: Boolean = false, client: String? = null) =
            FragmentSource("pagecheck1792", isOverlay = overlay, client = client, origin = "test", load = { content })
        val demos = fence("""{"id": "demos", "widget": "joinableDemos", "copy": "page.intro"}""")
        val base = source(mapOf("page" to mapOf("intro" to "Hello.", "doc" to demos)))
        // Shipped copy may place a kind for shipped copy, and finds its copy key in the file.
        sharedCopyPageProblems(cxt, listOf(base)).shouldBeEmpty()
        val carousel = mapOf("page" to mapOf("doc" to fence("""{"id": "x", "widget": "carousel"}""")))
        val wrong = source(carousel, overlay = true)
        sharedCopyPageProblems(cxt, listOf(base, wrong)).single() shouldContain
            "'pagecheck1792' page.doc: The widget fence at line 1 ('x')"
        // A client's layer is not shared copy; its own check judges it with its configuration.
        val clients = source(carousel, overlay = true, client = "acme")
        sharedCopyPageProblems(cxt, listOf(base, clients)).size shouldBe 0
    }
})
