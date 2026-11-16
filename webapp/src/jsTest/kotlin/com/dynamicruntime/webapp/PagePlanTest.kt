package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.page.PGW
import com.dynamicruntime.common.page.PageWidgets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page renderer's plan (issue #1794): which parts of a page document are drawn, in what order, with what copy, and
 * the jump row it leads with. A fence that goes wrong is left out rather than failing the page, so each way one is
 * left out is tried here; the checks at boot and at a write are where it is reported.
 */
class PagePlanTest {
    private fun fence(json: String) = "```${PGW.infoString}\n$json\n```"

    private val copy = Copy(
        mapOf(
            "home" to mapOf("title" to "Welcome", "intro" to "Hello.", "note" to "", "brand" to "KDR"),
            "pages" to mapOf("heading" to "## \${@t(\"home.title\")}"),
        ),
    )

    private fun plan(
        document: String,
        cfacts: Map<String, Boolean> = mapOf("kdr:loggedIn" to true, "kdr:anonymous" to false),
        shows: (PagePlaced) -> Boolean = { true },
    ) = planPage(document, copy, cfacts, drawable = { true }, shows = shows)

    private fun ids(parts: List<PagePart>) = parts.map { if (it is PagePlaced) it.fence.id else (it as PageText).markdown }

    @Test
    fun aPageIsDrawnInItsOrderWithItsCopy() {
        val doc = fence("""{"id": "hero", "widget": "hero"}""") + "\n" +
            fence("""{"id": "title", "widget": "pageTitle", "copy": "home.title"}""") + "\nSome words.\n" +
            fence("""{"id": "intro", "copy": "home.intro"}""")
        val parts = plan(doc)
        assertEquals(listOf("hero", "title", "Some words.", "intro"), ids(parts))
        val title = parts[1] as PagePlaced
        assertEquals("Welcome", title.copy)
        assertEquals("page.title", title.elementId)
        assertEquals(PageWidgets.pageTitle, title.kind)
        assertEquals("Hello.", (parts[3] as PagePlaced).copy)
        assertNull((parts[3] as PagePlaced).kind)
    }

    @Test
    fun copyPullsResolveAgainstThePagesOwnCopy() {
        // In a fence's copy, and in the Markdown between fences.
        assertEquals("## Welcome", (plan(fence("""{"id": "h", "copy": "pages.heading"}""")).single() as PagePlaced).copy)
        assertEquals("Before Welcome.", (plan("Before \${@t(\"home.title\")}.").single() as PageText).markdown)
        // Copy the pass cannot fill in -- a missing key, or a literal written before pages were drawn this way -- is drawn
        // as written, as it was before, never left out.
        assertEquals(
            listOf("Use \${user.name} in a mail.", "intro"),
            ids(plan("Use \${user.name} in a mail.\n" + fence("""{"id": "intro", "copy": "home.intro"}"""))),
        )
    }

    @Test
    fun aFenceIsLeftOutForWhatItIs() {
        val kept = fence("""{"id": "intro", "copy": "home.intro"}""")
        fun left(json: String) = assertEquals(listOf("intro"), ids(plan(fence(json) + "\n" + kept)), json)
        left("""{"widget": "hero"}""")                                                 // no id
        left("""{"id": "x", "widget": "carousel"}""")                                  // a kind there is none of
        left("""{"id": "x", "copy": "home.note"}""")                                   // copy alone, and blank
        left("""{"id": "x", "copy": "home.missing"}""")                                // copy alone, and absent
        left("""{"id": "x", "widget": "hero", "cfactExpression": "kdr:anonymous"}""")  // a condition that fails
        left("""{"id": "x", "widget": "hero", "cfactExpression": "kdr:nope"}""")       // one that does not parse
        // An id already drawn above it: the second fence is left out, the first kept.
        assertEquals(listOf(null), plan(kept + "\n" + fence("""{"id": "intro", "widget": "hero"}""")).map { (it as PagePlaced).kind })
        // Never closed: it runs to the end of the copy, so what is before it is drawn and nothing after.
        assertEquals(listOf("intro"), ids(plan(kept + "\n```${PGW.infoString}\n{\"id\": \"x\"}\n" + kept)))
        // A condition that holds, and a widget that says it has something to show, are drawn.
        assertEquals(listOf("x"), ids(plan(fence("""{"id": "x", "widget": "hero", "cfactExpression": "kdr:loggedIn"}"""))))
        assertEquals(emptyList(), ids(plan(fence("""{"id": "x", "widget": "hero"}"""), shows = { false })))
    }

    @Test
    fun aKindTheSurfaceDoesNotDrawIsLeftOut() {
        val parts = planPage(
            fence("""{"id": "x", "widget": "hero"}"""), copy, emptyMap(), drawable = { it != PageWidgets.hero }, shows = { true },
        )
        assertEquals(emptyList(), parts)
    }

    @Test
    fun conditionsAreJudgedOnTheFactsSent() {
        val facts = mapOf("kdr:loggedIn" to true, "kdr:anonymous" to false)
        assertTrue(pageConditionHolds("kdr:loggedIn", facts))
        assertFalse(pageConditionHolds("kdr:anonymous", facts))
        assertTrue(pageConditionHolds("~kdr:anonymous", facts))
        // A fact the page is not sent does not parse, so the fence is left out.
        assertFalse(pageConditionHolds("kdr:hasOperatorLevel", facts))
    }

    private fun card(id: String, jumpLabel: String? = null, actionFirst: Boolean = false) = HomeCard(
        id, id, "", actionPage = if (actionFirst) "register" else null, actionLabel = null, offerLabel = null,
        jumpLabel = jumpLabel, links = emptyList(), kind = null, actionFirst = actionFirst,
    )

    private val cards = listOf(card("register", "Register", actionFirst = true), card("checklist", "Getting started"), card("administer"))

    private fun cardsFence(params: String) =
        (plan(fence("""{"id": "c", "widget": "cardsBlock", "block": "${HCARD.block}"$params}""")).single() as PagePlaced).fence

    @Test
    fun aCardsBlockDrawsTheCardsItNames() {
        assertEquals(listOf("register", "checklist", "administer"), pageCardsOf(cardsFence(""), cards).map { it.id })
        assertEquals(listOf("administer", "checklist"), pageCardsOf(cardsFence(""", "ids": ["administer", "checklist", "gone"]"""), cards).map { it.id })
        assertEquals(listOf("register", "administer"), pageCardsOf(cardsFence(""", "except": ["checklist"]"""), cards).map { it.id })
        // Only the home cards reach the page.
        val other = (plan(fence("""{"id": "c", "widget": "cardsBlock", "block": "otherCards"}""")).single() as PagePlaced).fence
        assertEquals(emptyList(), pageCardsOf(other, cards))
    }

    @Test
    fun aCardIsDrawnByTheFirstBlockThatNamesIt() {
        val doc = fence("""{"id": "first", "widget": "cardsBlock", "block": "${HCARD.block}", "ids": ["administer"]}""") + "\n" +
            fence("""{"id": "rest", "widget": "cardsBlock", "block": "${HCARD.block}", "except": ["checklist"]}""")
        val byFence = pageCardsByFence(plan(doc), cards)
        assertEquals(listOf("administer"), byFence.getValue("page.first").map { it.id })
        assertEquals(listOf("register"), byFence.getValue("page.rest").map { it.id })
    }

    @Test
    fun theJumpRowNamesWhatIsDrawnFurtherDown() {
        val doc = fence("""{"id": "jump", "widget": "jumpLinks", "copy": "pages.links"}""") + "\n" +
            fence("""{"id": "cards", "widget": "cardsBlock", "block": "${HCARD.block}"}""") + "\n" +
            fence("""{"id": "demos", "widget": "hero", "jumpLabel": "Try a demo"}""")
        val linksCopy = Copy(mapOf("pages" to mapOf("links" to "- [Start here](/docs/start-here.md)\n- [Elsewhere](#page.elsewhere)")))
        val parts = planPage(doc, linksCopy, emptyMap(), drawable = { true }, shows = { true })
        val row = pageJumpRow(parts, parts.first() as PagePlaced, { cards }, null)
        // The call to action is a button; the copy's links lead, drawn as written (issue #1795), then the fences', then
        // the cards' -- each only for what is drawn.
        assertEquals(listOf("Register" to "register"), row.buttons)
        assertEquals(
            "- [Start here](/docs/start-here.md)\n- [Elsewhere](#page.elsewhere)\n- [Try a demo](#page.demos)\n" +
                "- [Getting started](#home.card.checklist)",
            row.links,
        )
        // With nothing drawn further down and no copy, the row has nothing to hold.
        val alone = planPage(fence("""{"id": "jump", "widget": "jumpLinks"}"""), copy, emptyMap(), drawable = { true }, shows = { true })
        assertTrue(pageJumpRow(alone, alone.single() as PagePlaced, { emptyList() }, null).isEmpty)
    }

    @Test
    fun aDrawnFenceIsStyledByItsIdAndItsKind() {
        val parts = plan(fence("""{"id": "note", "copy": "home.intro"}""") + "\n" + fence("""{"id": "demos", "widget": "hero"}"""))
        assertEquals("page-part page-part-note", pagePartClasses(parts[0] as PagePlaced))
        assertEquals("page-part page-part-demos page-widget-hero", pagePartClasses(parts[1] as PagePlaced))
    }
}
