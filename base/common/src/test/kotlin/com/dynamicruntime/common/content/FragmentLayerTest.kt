package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * How fragment layers merge (issue #456): precedence, what an overlay leaves alone, and the two things that
 * fail silently if nobody checks them.
 *
 * Pure -- layers are supplied here rather than read from a classpath -- so every case is maps in and a map
 * out. The assertions worth reading are the **silent** ones: an overlay that stops winning because its key was
 * renamed, and a build id that fails to change when the content behind it does. Neither throws, and both are
 * only ever noticed as "the wrong words are on the page".
 */
class FragmentLayerTest : StringSpec({

    fun layer(
        fileId: String = "home",
        isOverlay: Boolean = false,
        client: String? = null,
        origin: String = "test",
        content: Map<String, Map<String, String>>,
    ) = FragmentSource(fileId, isOverlay, client, origin) { content }

    val base = layer(
        content = mapOf(
            "welcome" to mapOf("title" to "Welcome", "intro" to "Default intro", "support" to "Default support"),
            "footer" to mapOf("copyright" to "Default copyright"),
        ),
    )

    // --- precedence --------------------------------------------------------------------------------------

    "an overlay wins over the base" {
        val overlay = layer(isOverlay = true, content = mapOf("welcome" to mapOf("title" to "Component title")))
        val merged = mergeFragmentLayers("home", listOf(base, overlay), client = null)
        merged.content.getValue("welcome")["title"] shouldBe "Component title"
    }

    "a client's overlay wins over a component's, whatever order they were declared in" {
        val component = layer(isOverlay = true, content = mapOf("welcome" to mapOf("title" to "Component title")))
        val acme = layer(isOverlay = true, client = "acme", content = mapOf("welcome" to mapOf("title" to "Acme title")))
        // Declared client-first, so declaration order alone would give the wrong answer -- which is the point
        // of the kind being sorted rather than the list being trusted.
        val merged = mergeFragmentLayers("home", listOf(base, acme, component), client = "acme")
        merged.content.getValue("welcome")["title"] shouldBe "Acme title"
    }

    "another client's overlay is not applied" {
        val acme = layer(isOverlay = true, client = "acme", content = mapOf("welcome" to mapOf("title" to "Acme title")))
        mergeFragmentLayers("home", listOf(base, acme), client = "globex")
            .content.getValue("welcome")["title"] shouldBe "Welcome"
        mergeFragmentLayers("home", listOf(base, acme), client = null)
            .content.getValue("welcome")["title"] shouldBe "Welcome"
    }

    "a layer for another file is ignored entirely" {
        val elsewhere = layer(fileId = "auth", isOverlay = true, content = mapOf("welcome" to mapOf("title" to "Wrong")))
        mergeFragmentLayers("home", listOf(base, elsewhere), client = null)
            .content.getValue("welcome")["title"] shouldBe "Welcome"
    }

    // --- what an overlay leaves alone ---------------------------------------------------------------------

    "an overlay naming one key of a namespace keeps the rest of it" {
        // The failure this prevents is silent: a namespace replaced wholesale loses the keys the overlay never
        // mentioned, and the frontend asking for one of those renders its key path rather than failing.
        val overlay = layer(isOverlay = true, content = mapOf("welcome" to mapOf("title" to "New title")))
        val welcome = mergeFragmentLayers("home", listOf(base, overlay), client = null).content.getValue("welcome")
        welcome["title"] shouldBe "New title"
        welcome["intro"] shouldBe "Default intro"
        welcome["support"] shouldBe "Default support"
    }

    "an overlay touching one namespace leaves the others whole" {
        val overlay = layer(isOverlay = true, content = mapOf("welcome" to mapOf("title" to "New title")))
        mergeFragmentLayers("home", listOf(base, overlay), client = null)
            .content.getValue("footer")["copyright"] shouldBe "Default copyright"
    }

    // --- the silent failures ------------------------------------------------------------------------------

    "an overlay of a key no base declares is reported as an orphan" {
        // The rename casualty. Nothing throws: the overlay simply stops winning a lookup that no longer
        // happens, and the base's own wording is served in its place.
        val stale = layer(isOverlay = true, content = mapOf("welcome" to mapOf("titel" to "Typo'd key")))
        val merged = mergeFragmentLayers("home", listOf(base, stale), client = null)
        merged.orphans shouldBe listOf("welcome.titel")
    }

    "an overlay of a key the base does declare is not an orphan" {
        val good = layer(isOverlay = true, content = mapOf("welcome" to mapOf("title" to "Fine")))
        mergeFragmentLayers("home", listOf(base, good), client = null).orphans.shouldBeEmpty()
    }

    "an orphan is judged against the base, not against what earlier overlays added" {
        // Otherwise one stale overlay would launder the next: an overlay adding `welcome.titel` would make a
        // second overlay of the same wrong key look legitimate.
        val first = layer(isOverlay = true, content = mapOf("welcome" to mapOf("titel" to "One")))
        val second = layer(isOverlay = true, content = mapOf("welcome" to mapOf("titel" to "Two")))
        mergeFragmentLayers("home", listOf(base, first, second), client = null).orphans shouldBe listOf("welcome.titel")
    }

    "the build id follows the merged content, not the base file" {
        // The cache correctness this rests on. Two clients reading different copy must not share a URL, and
        // the URL is the build id -- so an id computed from the base resource would be the whole bug.
        val acme = layer(isOverlay = true, client = "acme", content = mapOf("welcome" to mapOf("title" to "Acme title")))
        val shared = mergeFragmentLayers("home", listOf(base, acme), client = null)
        val forAcme = mergeFragmentLayers("home", listOf(base, acme), client = "acme")
        forAcme.buildId shouldNotBe shared.buildId
    }

    "identical content gets an identical build id, whoever it was merged for" {
        // The other half: a client whose overlay happens to say what the base already said shares the URL and
        // so shares the cache entry. The id names content, not a client.
        val sameAgain = layer(isOverlay = true, client = "acme", content = mapOf("welcome" to mapOf("title" to "Welcome")))
        val shared = mergeFragmentLayers("home", listOf(base), client = null)
        val forAcme = mergeFragmentLayers("home", listOf(base, sameAgain), client = "acme")
        forAcme.buildId shouldBe shared.buildId
    }

    "the build id distinguishes contents that a naive join would not" {
        // Length-prefixed rather than delimited: a Markdown value may contain any character, so there is no
        // separator to reserve, and `a.b = "xy"` must not hash as `a.bx = "y"` does.
        val left = fragmentContentBuildId(mapOf("a" to mapOf("b" to "xy")))
        val right = fragmentContentBuildId(mapOf("a" to mapOf("bx" to "y")))
        left shouldNotBe right
    }

    "a marker-tagged encoding would collide here, and this one does not" {
        // Tagging each part with what it *is* rather than where it ends: `n`+`a`, `k`+`b`, `v`+`c` and so on
        // would render both of these as "nakbvcnakdve", because the tags are not reserved characters and a
        // value may contain them.
        val twoEntries = fragmentContentBuildId(mapOf("a" to mapOf("b" to "c", "d" to "e")))
        val oneLongValue = fragmentContentBuildId(mapOf("a" to mapOf("b" to "cnakdve")))
        twoEntries shouldNotBe oneLongValue
    }

    "an empty namespace is part of the content, so it changes the id" {
        // It contributes no keys, and the frontend reads nothing from it -- but the served document differs,
        // and the id has to name the document exactly or the permanent cache is resting on nothing.
        fragmentContentBuildId(mapOf("a" to emptyMap())) shouldNotBe fragmentContentBuildId(emptyMap())
    }

    "an empty namespace is not confusable with a key of the namespace before it" {
        // Why the key count is written rather than the namespace simply being emitted once: without it, these
        // two produce the same sequence of parts.
        val nested = fragmentContentBuildId(mapOf("a" to mapOf("b" to "c", "d" to "e")))
        val flat = fragmentContentBuildId(
            mapOf("a" to emptyMap(), "b" to mapOf("c" to "d"), "e" to emptyMap()),
        )
        nested shouldNotBe flat
    }

    // --- absence -----------------------------------------------------------------------------------------

    "a declared file whose layers supply nothing is not found" {
        val absent = FragmentSource("home", isOverlay = false, client = null, origin = "missing") { null }
        val merged = mergeFragmentLayers("home", listOf(absent), client = null)
        merged.found shouldBe false
        merged.content.isEmpty() shouldBe true
    }

    "an overlay alone does not make a file found" {
        // An overlay changes content; it does not supply it. A file whose base is missing is a packaging
        // failure, and an overlay hiding that would be the worst possible time to find out.
        val overlay = layer(isOverlay = true, content = mapOf("welcome" to mapOf("title" to "Only me")))
        mergeFragmentLayers("home", listOf(overlay), client = null).found shouldBe false
    }

    // --- the inline builder ------------------------------------------------------------------------------

    "the inline builder refuses a namespace declared twice" {
        val e = shouldThrow<KdrException> {
            fragmentInline("home", origin = "test") {
                namespace("welcome") { key("title", "One") }
                namespace("welcome") { key("intro", "Two") }
            }
        }
        e.fullMessage() shouldContain "declared twice in one map"
    }

    "the inline builder refuses a key declared twice" {
        val e = shouldThrow<KdrException> {
            fragmentInline("home", origin = "test") {
                namespace("welcome") {
                    key("title", "One")
                    key("title", "Two")
                }
            }
        }
        e.fullMessage() shouldContain "'welcome.title' is declared twice"
    }

    "an inline layer is an overlay unless it says otherwise" {
        // The default that matters: a layer written in code is nearly always changing somebody else's file,
        // and a base declared by accident would replace content rather than adjust it.
        fragmentInline("home", origin = "test") { namespace("welcome") { key("title", "x") } }.isOverlay shouldBe true
    }

    // --- audience (issue #514) ---------------------------------------------------------------------------

    "a file's audience defaults to frontend" {
        mergeFragmentLayers("home", listOf(base), client = null).audience shouldBe FragmentAudience.frontend
    }

    "a backend base makes the merged file backend" {
        val backendBase = layer(content = mapOf("email" to mapOf("subject" to "Code"))).let {
            FragmentSource(it.fileId, it.isOverlay, it.client, it.origin, FragmentAudience.backend, it.load)
        }
        mergeFragmentLayers("home", listOf(backendBase), client = null).audience shouldBe FragmentAudience.backend
    }

    "an overlay's audience does not change the file's -- the base decides" {
        // Audience is a fact about the file, and the base is what supplies the file. An overlay marked backend
        // over a frontend base is a confused declaration, not a reclassification: the file stays frontend.
        val backendOverlay = FragmentSource(
            "home", isOverlay = true, client = null, origin = "test", FragmentAudience.backend,
        ) { mapOf("welcome" to mapOf("title" to "Overlaid")) }
        mergeFragmentLayers("home", listOf(base, backendOverlay), client = null)
            .audience shouldBe FragmentAudience.frontend
    }

    "any backend base makes the file backend -- the fail-safe direction, and the disagreement is kept" {
        // Two components both claim the file, disagreeing on audience. Erring toward backend withholds a file
        // that might have been public; erring the other way would *serve* one a base marked private. But the
        // safe resolution is still somebody's mistake -- it takes a delivered file private without failing
        // anything here -- so the conflict is recorded for the boot check to report at its cause.
        val frontendBase = base
        val backendBase = FragmentSource(
            "home", isOverlay = false, client = null, origin = "test", FragmentAudience.backend,
        ) { mapOf("email" to mapOf("subject" to "Code")) }
        val merged = mergeFragmentLayers("home", listOf(frontendBase, backendBase), client = null)
        merged.audience shouldBe FragmentAudience.backend
        merged.audienceConflict shouldBe true
    }

    "bases that agree are no conflict, whichever way they agree" {
        mergeFragmentLayers("home", listOf(base), client = null).audienceConflict shouldBe false
        val backendBase = FragmentSource(
            "home", isOverlay = false, client = null, origin = "test", FragmentAudience.backend,
        ) { mapOf("email" to mapOf("subject" to "Code")) }
        mergeFragmentLayers("home", listOf(backendBase), client = null).audienceConflict shouldBe false
        // Two backend bases still agree -- it is disagreement that is the finding, not multiplicity.
        mergeFragmentLayers("home", listOf(backendBase, backendBase), client = null)
            .audienceConflict shouldBe false
    }

    "an overlay is not a base, so it cannot create a conflict" {
        val backendOverlay = FragmentSource(
            "home", isOverlay = true, client = null, origin = "test", FragmentAudience.backend,
        ) { mapOf("welcome" to mapOf("title" to "Overlaid")) }
        val merged = mergeFragmentLayers("home", listOf(base, backendOverlay), client = null)
        merged.audience shouldBe FragmentAudience.frontend
        merged.audienceConflict shouldBe false
    }
})
