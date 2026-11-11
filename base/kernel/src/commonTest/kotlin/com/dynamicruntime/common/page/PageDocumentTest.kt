package com.dynamicruntime.common.page

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A page document cut at its widget fences, and the fences held to their rules (issue #1792). A fence that goes wrong
 * fails silently where a page is drawn -- it is left out -- so the checks are the one place it is caught, and each rule
 * is tried here.
 */
class PageDocumentTest {
    private fun fence(json: String, ticks: String = "```") = "$ticks${PGW.infoString}\n$json\n$ticks"

    @Test
    fun aDocumentIsCutAtItsFencesInOrder() {
        val doc = "# Welcome\n\nSome words.\n\n" + fence("""{"id": "cards", "widget": "cardsBlock", "block": "homeCards"}""") +
            "\n\nMore words.\n" + fence("""{"id": "note", "copy": "home.note"}""")
        val segments = splitPageDocument(doc)
        assertEquals(4, segments.size)
        assertEquals("# Welcome\n\nSome words.\n", (segments[0] as PageMarkdown).text)
        val first = segments[1] as PageFenceSource
        assertEquals(5, first.line)
        assertTrue(first.closed)
        assertEquals("""{"id": "cards", "widget": "cardsBlock", "block": "homeCards"}""", first.body)
        assertEquals("\nMore words.", (segments[2] as PageMarkdown).text)
        assertTrue(segments[3] is PageFenceSource)
        assertTrue(doc.hasPageFences())
        assertFalse("A ```kotlin\ncode\n``` block, and kdr-ui in prose.".hasPageFences())
    }

    @Test
    fun onlyATopLevelFenceIsDrawn() {
        // Shown inside another code block, as a document shows an example: that block's text.
        val example = "````markdown\n" + fence("""{"id": "x", "widget": "hero"}""") + "\n````"
        assertEquals(listOf(example), splitPageDocument(example).map { (it as PageMarkdown).text })
        // Indented: inside a list item or another block, not the page's.
        val indented = "- An item\n\n  " + fence("""{"id": "x", "widget": "hero"}""").replace("\n", "\n  ")
        assertFalse(indented.hasPageFences())
        // Tildes are a code block, never a fence.
        assertFalse("~~~${PGW.infoString}\n{}\n~~~".hasPageFences())
    }

    @Test
    fun aFenceOpenedWithFourBackticksHoldsThreeBacktickCode() {
        val doc = fence("{\"id\": \"x\", \"widget\": \"hero\", \"note\": \"```\"}\n```\nstill inside", ticks = "````") + "\nAfter."
        val segments = splitPageDocument(doc)
        val source = segments[0] as PageFenceSource
        assertTrue(source.body.endsWith("still inside"))
        assertEquals("After.", (segments[1] as PageMarkdown).text)
    }

    @Test
    fun anUnclosedFenceRunsToTheEndAndIsAProblem() {
        val source = splitPageDocument("Words.\n```${PGW.infoString}\n{\"id\": \"x\"}").single { it is PageFenceSource } as PageFenceSource
        assertFalse(source.closed)
        assertEquals(
            listOf("The widget fence at line 2 is never closed, so it runs to the end of the copy."),
            problems("Words.\n```${PGW.infoString}\n{}"),
        )
    }

    private fun scope(shipped: Boolean = true) = PageCheckScope(
        PageWidgets.core, shipped,
        copyKeyExists = { namespace, key -> namespace == "home" && key in setOf("intro", "note") },
        knownCfacts = setOf("kdr:hasAdminLevel", "kdr:loggedIn"),
        frontendCfacts = setOf("kdr:hasAdminLevel"),
    )

    private fun problems(doc: String, shipped: Boolean = true) = pageDocumentProblems(doc, scope(shipped))

    private fun problem(json: String, shipped: Boolean = true): String = problems(fence(json), shipped).single()

    @Test
    fun aSoundFenceHasNoProblems() {
        val doc = fence("""{"id": "cards", "widget": "cardsBlock", "block": "homeCards", "except": ["register"]}""") + "\n" +
            fence("""{"id": "adminNote", "copy": "home.note", "cfactExpression": "kdr:hasAdminLevel", "jumpLabel": "Note"}""") +
            "\n" + fence("""{"id": "demos", "widget": "joinableDemos", "copy": "home.intro"}""")
        assertEquals(emptyList(), problems(doc))
        assertEquals(emptyList(), problems("No fences here."))
    }

    @Test
    fun eachRuleRefusesWhatBreaksIt() {
        assertTrue(problem("""["not", "an", "object"]""").contains("its JSON is not an object"))
        assertTrue(problem("""{"id": "x", "widget": """).contains("cannot be read"))
        assertTrue(problem("""{"widget": "hero"}""").contains("has no id"))
        assertTrue(problem("""{"id": "not an id", "widget": "hero"}""").contains("not a variable name"))
        val twice = fence("""{"id": "x", "widget": "hero"}""") + "\n" + fence("""{"id": "x", "widget": "docLinks"}""")
        assertTrue(problems(twice).single().contains("the id of an earlier fence"))
        assertTrue(problem("""{"id": "x", "widget": "carousel"}""").contains("names the widget 'carousel', which is not one of"))
        assertTrue(problem("""{"id": "x"}""").contains("names neither a widget nor a copy"))
        assertTrue(problem("""{"id": "x", "widget": "hero", "size": 3}""").contains("'size', which it does not take"))
        assertTrue(problem("""{"id": "x", "widget": "cardsBlock"}""").contains("parameters it refuses"))
        assertTrue(problem("""{"id": "x", "copy": "intro"}""").contains("not a 'namespace.key'"))
        assertTrue(problem("""{"id": "x", "copy": "home.missing"}""").contains("does not have"))
        assertTrue(problem("""{"id": "x", "copy": "home.intro", "cfactExpression": "kdr:loggedIn"}""").contains("never sent"))
        assertTrue(problem("""{"id": "x", "copy": "home.intro", "cfactExpression": "kdr:nope"}""").contains("does not parse"))
        assertTrue(problem("""{"id": "x", "widget": "hero", "jumpLabel": 3}""").contains("has a jumpLabel that is not text"))
    }

    @Test
    fun aKindForShippedCopyIsRefusedInAClientsCopy() {
        val json = """{"id": "demos", "widget": "joinableDemos"}"""
        assertEquals(emptyList(), problems(fence(json), shipped = true))
        assertTrue(problem(json, shipped = false).contains("only copy a component ships may place"))
        assertEquals(emptyList(), problems(fence("""{"id": "hero", "widget": "hero"}"""), shipped = false))
    }

    @Test
    fun aCatalogRefusesTwoKindsOfOneName() {
        val twin = PageWidgetKind(PageWidgets.hero.name, "Another hero.", PageWidgetPlacement.anyCopy, setOf(PageSurface.app))
        val refused = assertFailsWith<KdrException> { PageWidgetCatalog(PageWidgets.coreKinds + twin) }
        assertTrue("'${PageWidgets.hero.name}'" in refused.message.orEmpty())
    }

    @Test
    fun theReferenceListsEveryKindAndItsParameters() {
        val reference = pageWidgetsMarkdown()
        for (kind in PageWidgets.coreKinds) assertTrue("### ${kind.name}" in reference, kind.name)
        assertTrue("| `${PGW.block}` | string | Yes |" in reference)
    }
}
