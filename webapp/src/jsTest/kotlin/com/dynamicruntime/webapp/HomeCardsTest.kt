package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CHKL
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.HSEC
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.webasset.WAS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Home's cards (issue #1740 gathered these into one file): the cards off the shell config, the new client checklist,
// unpublished drafts, what waits on you, the jump links, the public home page, and creating one's own client. Each
// class is the file it used to be, kept whole with its own helpers.

/** The home cards' pure half (issue #1636): reading them off the shell config, and the rules their drawing follows. */
class HomeCardsTest {
    private val administer = mapOf(
        HFLD.id to HCARD.administer,
        HCARD.title to "Your client",
        HCARD.text to "Set up your client.",
        HCARD.links to listOf(
            mapOf(HCARD.linkLabel to "My client", UIB.action to HMENU.pageClients),
            // A call is no place a card goes: dropped.
            mapOf(HCARD.linkLabel to "Log out", UIB.action to listOf("logout")),
        ),
    )

    @Test
    fun cardsAreReadInOrderAndKeepOnlyWhatACardCanDo() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to ASST.homeCard, HCARD.title to "Workflow assistant", HCARD.text to "Describe it.",
                    UIB.action to ASST.page, HCARD.actionLabel to "Open the workflow assistant", HCARD.kind to ASST.cardKind,
                ),
                administer,
                // No id: nothing an overlay could name, so not a card.
                mapOf(HCARD.title to "Nameless"),
            ),
        )
        assertEquals(listOf(ASST.homeCard, HCARD.administer), cards.map { it.id })
        val assistant = cards[0]
        assertEquals(ASST.page, assistant.actionPage)
        assertEquals("Open the workflow assistant", assistant.actionLabel)
        assertEquals(ASST.cardKind, assistant.kind)
        val admin = cards[1]
        assertNull(admin.actionPage)
        assertNull(admin.kind)
        assertEquals(listOf("My client" to HMENU.pageClients), admin.links.map { it.label to it.page })
    }

    @Test
    fun theShellConfigCarriesTheCardsAndAnOlderNodeNone() {
        fun configWith(state: Map<String, Any?>) = homeConfigFrom(UiConfig(FragmentRef("frag", "b1"), emptyMap(), emptyMap(), state))
        assertEquals(listOf(HCARD.administer), configWith(mapOf(HCARD.cards to listOf(administer))).cards.map { it.id })
        assertEquals(emptyList(), configWith(emptyMap()).cards)
    }

    @Test
    fun theAssistantsButtonSaysTheCardsOfferOnlyWhileThePersonMaySwitchItOn() {
        val card = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to ASST.homeCard, UIB.action to ASST.page, HCARD.actionLabel to "Open it",
                    HCARD.offerLabel to "Switch it on", HCARD.kind to ASST.cardKind,
                ),
            ),
        ).single()
        assertEquals("Switch it on", assistantCardActionLabel(card, AssistantView.switchOn))
        for (view in AssistantView.entries.filter { it != AssistantView.switchOn } + null) {
            assertEquals("Open it", assistantCardActionLabel(card, view), "$view keeps the card's own words")
        }
        // A card an overlay left without an offer keeps its own words throughout.
        val plain = homeCardsFrom(listOf(mapOf(HFLD.id to ASST.homeCard, UIB.action to ASST.page, HCARD.actionLabel to "Open it"))).single()
        assertEquals("Open it", assistantCardActionLabel(plain, AssistantView.switchOn))
    }

    @Test
    fun aLinkWithNoWordsIsLeftOffRatherThanShowingAPageId() {
        val card = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to "c",
                    HCARD.links to listOf(
                        mapOf(UIB.action to HMENU.pageBootChecks),
                        mapOf(HCARD.linkLabel to " ", UIB.action to HMENU.pageEnv),
                        mapOf(HCARD.linkLabel to "Users", UIB.action to HMENU.pageUsers),
                    ),
                ),
            ),
        ).single()
        assertEquals(listOf("Users"), card.links.map { it.label })
    }

    @Test
    fun aClientMadeFromPublicComesWithTheAssistantWhereItsCardIsSent() {
        val create = homeCardsFrom(listOf(mapOf(HFLD.id to ASST.createHomeCard, HCARD.title to "Design your own forms")))
        assertTrue(ownClientAssistantOffered(create))
        assertFalse(ownClientAssistantOffered(homeCardsFrom(listOf(administer))))
        assertFalse(ownClientAssistantOffered(emptyList()))
    }

    @Test
    fun aCardsElementIdIsUnderHome() {
        assertEquals("home.card.administer", homeCardElementId(HCARD.administer))
    }

    @Test
    fun aCardNamedInTheRowUnderTheTitleSaysWhatItsButtonSays() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to ASST.homeCard, UIB.action to ASST.page, HCARD.actionLabel to "Open it",
                    HCARD.offerLabel to "Switch on the assistant", HCARD.jumpLabel to "Workflow assistant",
                    HCARD.kind to ASST.cardKind,
                ),
                // Not named in the row: no jump label.
                administer,
            ),
        )
        val assistantId = homeCardElementId(ASST.homeCard)
        assertEquals(listOf("Workflow assistant" to assistantId), homeCardJumpLinks(cards, null))
        assertEquals(listOf("Workflow assistant" to assistantId), homeCardJumpLinks(cards, AssistantView.on))
        // Off, and the person may switch it on: the link says so, as the button does.
        assertEquals(listOf("Switch on the assistant" to assistantId), homeCardJumpLinks(cards, AssistantView.switchOn))
    }

    @Test
    fun theCallToActionLeadsTheRowAsAButtonAndNotAsALink() {
        // Issue #1698: the Register card is the page's call to action, so the row draws a button doing what its button
        // does -- opening Register -- not a link to the card, which on a window showing the whole page would do nothing.
        val cards = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to HCARD.register, UIB.action to HMENU.pageRegister, HCARD.actionLabel to "Register",
                    HCARD.jumpLabel to "Register", HCARD.actionFirst to true,
                ),
                mapOf(HFLD.id to ASST.homeCard, HCARD.jumpLabel to "Workflow assistant", HCARD.kind to ASST.cardKind),
                administer,
            ),
        )
        assertEquals(listOf(true, false, false), cards.map { it.actionFirst })
        assertEquals(listOf("Register" to HMENU.pageRegister), homeCardJumpButtons(cards))
        // A call to action with nowhere to go keeps its link: a button must do something.
        val noAction = homeCardsFrom(listOf(mapOf(HFLD.id to "nowhere", HCARD.jumpLabel to "Nowhere", HCARD.actionFirst to true)))
        assertEquals(emptyList(), homeCardJumpButtons(noAction))
        assertEquals(listOf("Nowhere" to homeCardElementId("nowhere")), homeCardJumpLinks(noAction, null))
        assertEquals(listOf("Workflow assistant" to homeCardElementId(ASST.homeCard)), homeCardJumpLinks(cards, null))
        // And the copy's own link to the card is dropped for the button, its card being left out of what is drawn.
        val drawn = cards.filterNot { it.leadsJumpRow }.map { homeCardElementId(it.id) }.toSet()
        assertEquals(
            "- [Start here](/docs/start-here.md)",
            jumpLinksFor("- [Register](#home.card.register)\n- [Start here](/docs/start-here.md)", drawn),
        )
    }

    @Test
    fun aCardMayAskToComeBeforeTheIntroduction() {
        // Issue #1723: a new client's Getting started list, which a long introduction would put below a phone's first screen.
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.checklist, HCARD.kind to HCARD.kindNewClientChecklist, HCARD.aboveIntro to true),
                administer,
            ),
        )
        assertEquals(listOf(true, false), cards.map { it.aboveIntro })
        val (above, below) = cards.partition { it.aboveIntro }
        assertEquals(listOf(HCARD.checklist) to listOf(HCARD.administer), above.map { it.id } to below.map { it.id })
    }

    @Test
    fun cardLinksJoinTheCopysRowInItsOwnStyle() {
        val links = listOf("Workflow assistant" to "home.card.assistant")
        // After a list, as list items -- the shipped copy, and the reference flavor's "Start here" before it.
        assertEquals(
            "- [Start here](/docs/start-here.md)\n- [Workflow assistant](#home.card.assistant)",
            withCardJumpLinks("- [Start here](/docs/start-here.md)\n", links),
        )
        // After a row written on one line, separated as it is.
        assertEquals(
            "[Start here](/docs/start-here.md) · [Workflow assistant](#home.card.assistant)",
            withCardJumpLinks("[Start here](/docs/start-here.md)", links),
        )
        // With no copy, the links alone; with no links, the copy as it was.
        assertEquals("- [Workflow assistant](#home.card.assistant)", withCardJumpLinks(null, links))
        assertEquals("- [A](#x)", withCardJumpLinks("- [A](#x)", emptyList()))
        // A label's brackets cannot break the link.
        assertEquals("- [Odd \\[1\\]](#home.card.x)", withCardJumpLinks(null, listOf("Odd [1]" to "home.card.x")))
        // And the filter keeps the card's link where the card is drawn, dropping only sections that are not.
        val shipped = "- [Try a demo](#home.demos)\n- [Your own client](#home.own-client)"
        assertEquals(
            "- [Workflow assistant](#home.card.assistant)",
            jumpLinksFor(withCardJumpLinks(shipped, links), setOf("home.card.assistant")),
        )
        // A card the copy already links to is not added again (issue #1652), so the copy can put it first.
        val registerFirst = "- [Register](#home.card.register)\n- [Start here](/docs/start-here.md)"
        assertEquals(registerFirst, withCardJumpLinks(registerFirst, listOf("Register" to "home.card.register")))
    }

    @Test
    fun aCardNamingCopyDrawsTheCopyInPlaceOfItsText() {
        val (ns, key) = HCARD.registerCopy.split('.')
        val copy = Copy(mapOf(ns to mapOf(key to "Register *here*.")))
        val raw = mapOf(HFLD.id to HCARD.register, HCARD.text to "Literal.", HCARD.textCopy to HCARD.registerCopy)
        val card = homeCardsFrom(listOf(raw)).single()
        assertEquals("Register *here*.", homeCardBody(card, copy))
        // Before the copy arrives, or where a deployment emptied it, no body -- never the literal words in its place.
        assertEquals("", homeCardBody(card, Copy.empty))
        assertEquals("", homeCardBody(card, Copy(mapOf(ns to mapOf(key to "")))))
        // A card naming no copy draws its own words.
        assertEquals("Literal.", homeCardBody(homeCardsFrom(listOf(raw - HCARD.textCopy)).single(), copy))
    }
}

/** Home's new client checklist (issue #1639): reading the steps, the assistant's step beside them, and when the card shows. */
class ClientChecklistTest {
    private val read = ChecklistRead(
        "free_acme", listOf(CHKL.newForm to true, CHKL.workflow to false, CHKL.invite to false, CHKL.firstForm to false),
    )

    private fun readsOf(steps: List<ChecklistStep>, hidden: Boolean = false) = HomeLiveReads(
        assistantView = null, waiting = null, waitingClient = null, drafts = null,
        checklist = steps, checklistHidden = hidden, hideChecklist = {},
    )

    @Test
    fun theAnswerIsReadAsStepsInOrder() {
        val parsed = parseChecklistRead(
            mapOf(
                CLD.client to "free_acme",
                CHKL.steps to listOf(
                    mapOf(CHKL.id to CHKL.newForm, CHKL.done to true),
                    mapOf(CHKL.id to CHKL.invite, CHKL.done to false),
                    // No id: not a step.
                    mapOf(CHKL.done to true),
                ),
            ),
        )!!
        assertEquals("free_acme", parsed.client)
        assertEquals(listOf(CHKL.newForm to true, CHKL.invite to false), parsed.steps)
        assertNull(parseChecklistRead(mapOf(CHKL.steps to emptyList<Any>())))
    }

    @Test
    fun theAssistantsStepLeadsWhereTheMenuOffersItAndThePersonMaySwitchItOn() {
        // Offered and off: a step to take, first, going to the assistant's page.
        val off = checklistSteps(read, assistantOffered = true, assistantView = AssistantView.switchOn)
        assertEquals(listOf(CHKL.assistant, CHKL.newForm, CHKL.workflow, CHKL.invite, CHKL.firstForm), off.map { it.id })
        assertEquals(false, off.first().done)
        assertEquals(listOf(HP.page to ASST.page), off.first().route)
        // On: done.
        assertEquals(true, checklistSteps(read, assistantOffered = true, assistantView = AssistantView.on).first().done)
        // Not offered, only an operator's to switch on, or its status unreadable: no step for it.
        for ((offered, view) in listOf(false to AssistantView.switchOn, true to AssistantView.off, true to AssistantView.failed)) {
            assertEquals(CHKL.newForm, checklistSteps(read, offered, view).first().id)
        }
        // Offered and its status not yet read: no steps at all, rather than a list that grows one, or one that looks done.
        val done = ChecklistRead("free_acme", read.steps.map { it.first to true })
        assertEquals(emptyList(), checklistSteps(done, assistantOffered = true, assistantView = null))
        // Not offered: the backend's steps, without waiting.
        assertEquals(4, checklistSteps(done, assistantOffered = false, assistantView = null).size)
    }

    @Test
    fun eachStepGoesWhereItIsDoneAndAnUnknownOneIsLeftOut() {
        val steps = checklistSteps(ChecklistRead("free_acme", read.steps + ("somethingNew" to false)), false, null)
        assertEquals(listOf(CHKL.newForm, CHKL.workflow, CHKL.invite, CHKL.firstForm), steps.map { it.id })
        val workflows = listOf(HP.page to HMENU.pageClients, HP.client to "free_acme", HP.section to panelFormsData)
        assertEquals(workflows, steps[0].route)
        assertEquals(workflows, steps[1].route)
        assertEquals(listOf(HP.page to HMENU.pageUsers), steps[2].route)
        assertEquals(listOf(HP.page to HMENU.pageNewForm), steps[3].route)
    }

    @Test
    fun theCardShowsWhileAStepIsLeftAndUntilItIsHidden() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.checklist, HCARD.title to "Getting started", HCARD.kind to HCARD.kindNewClientChecklist),
                mapOf(HFLD.id to HCARD.administer, HCARD.title to "Your client"),
            ),
        )
        val left = checklistSteps(read, false, null)
        val allDone = checklistSteps(ChecklistRead("free_acme", read.steps.map { it.first to true }), false, null)
        assertEquals(listOf(HCARD.checklist, HCARD.administer), visibleHomeCards(cards, readsOf(left)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(allDone)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(left, hidden = true)).map { it.id })
        // Before its read answers.
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(emptyList())).map { it.id })
    }

    @Test
    fun aSelfServeClientOpensOnItsStepsForItsOwnAdministratorOnly() {
        // Issue #1700: a client made from the public home page, or its sandbox, shown to its own administrator.
        assertTrue(clientOpensOnNextSteps("free_pat", acrossClients = false))
        assertTrue(clientOpensOnNextSteps(sandboxOf("free_pat"), acrossClients = false))
        // A platform operator keeps the page as it is, and any other client is unchanged.
        assertFalse(clientOpensOnNextSteps("free_pat", acrossClients = true))
        assertFalse(clientOpensOnNextSteps("acme", acrossClients = false))
        assertFalse(clientOpensOnNextSteps("acme_east", acrossClients = false))
    }

    @Test
    fun eachStepLeadsToThePanelOfThePageThatDoesIt() {
        val steps = checklistSteps(
            ChecklistRead("free_pat", listOf(CHKL.newForm to false, CHKL.workflow to true, CHKL.invite to false, CHKL.firstForm to false)),
            assistantOffered = true,
            assistantView = AssistantView.switchOn,
        )
        val client = clientNextSteps(steps, inSandbox = false, formsDataShown = true)
        assertEquals(listOf(null, panelFormsData, panelFormsData, null, null), client.map { it.panel })
        assertEquals(listOf<String?>(null, null, null, null, null), client.map { it.note })
        // The rest go to their pages, as Home's card leads them.
        assertEquals(listOf(HP.page to ASST.page), client.first().step.route)
        // From the sandbox, the client's own people and forms are the client's, not the sandbox's: said, not linked.
        val sandbox = clientNextSteps(steps, inSandbox = true, formsDataShown = true)
        assertEquals(listOf(false, false, false, true, true), sandbox.map { it.note != null })
        // With no Forms and data panel drawn, a workflow step goes nowhere rather than to a panel that is not there.
        assertEquals(listOf<String?>(null, null), clientNextSteps(steps.drop(1).take(2), inSandbox = false, formsDataShown = false).map { it.panel })
    }
}

/** Home's "Unpublished drafts" (issue #1638): reading the drafts' answer, when the card shows, and where it links. */
class UnpublishedDraftsTest {
    /** What Home read, with [drafts] alone answered. */
    private fun readsOf(drafts: DraftsRead?) = HomeLiveReads(
        assistantView = null, waiting = null, waitingClient = null, drafts = drafts,
        checklist = emptyList(), checklistHidden = false, hideChecklist = {},
    )

    @Test
    fun theAnswerIsReadAsTheClientsDrafts() {
        val read = parseDraftsRead(
            mapOf(CFEP.client to "acme", CFEP.count to 2, CFEP.names to listOf("acmeForms", "acmeMenu"), CFEP.inSandbox to true),
        )!!
        assertEquals("acme", read.client)
        assertEquals(2, read.count)
        assertEquals(listOf("acmeForms", "acmeMenu"), read.names)
        assertEquals(true, read.inSandbox)
        // No count: as many as it names. No client: not an answer.
        assertEquals(1, parseDraftsRead(mapOf(CFEP.client to "acme", CFEP.names to listOf("acmeForms")))?.count)
        assertNull(parseDraftsRead(mapOf(CFEP.count to 1)))
    }

    @Test
    fun theCardShowsOnlyOnceADraftIsKnown() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.drafts, HCARD.title to "Unpublished drafts", HCARD.kind to HCARD.kindUnpublishedDrafts),
                mapOf(HFLD.id to HCARD.administer, HCARD.title to "Your client"),
            ),
        )
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(null)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(DraftsRead("acme", 0, emptyList(), false))).map { it.id })
        assertEquals(
            listOf(HCARD.drafts, HCARD.administer),
            visibleHomeCards(cards, readsOf(DraftsRead("acme", 1, listOf("acmeForms"), false))).map { it.id },
        )
    }

    @Test
    fun itLinksToThePublishingPanelOfThePageThePersonIsOn() {
        val outside = DraftsRead("acme", 1, listOf("acmeForms"), inSandbox = false)
        assertEquals(
            listOf(HP.page to HMENU.pageClients, HP.client to "acme", HP.section to panelPublishing),
            draftsPublishRoute(outside),
        )
        // From inside the sandbox, the sandbox's own page, where the drafts are tried and published.
        assertEquals(sandboxOf("acme"), draftsPublishRoute(DraftsRead("acme", 1, listOf("acmeForms"), inSandbox = true))[1].second)
        assertEquals("1 configuration has unpublished changes:", draftsCountText(1))
        assertEquals("3 configurations have unpublished changes:", draftsCountText(3))
    }
}

/** Home's "Waiting on you" (issue #1637): reading the listing's answer, when the card shows, and its filter's chip. */
class WaitingOnYouTest {
    /** What Home read, with [waiting] alone answered. */
    private fun readsOf(waiting: WaitingRead?) = HomeLiveReads(
        assistantView = null, waiting = waiting, waitingClient = null, drafts = null,
        checklist = emptyList(), checklistHidden = false, hideChecklist = {},
    )

    private fun step(task: String, text: String) =
        mapOf(WFD.workflowId to "siteReview", WFD.label to "Site review", WFS.ctaTask to task, SWF.actionText to text)

    @Test
    fun theListingsAnswerIsReadAsFormsAndTheirSteps() {
        val read = parseWaitingRead(
            mapOf(
                EP.numAvailable to 7,
                EP.items to listOf(
                    mapOf(GDF.gedraId to "fd:acme:1", GDF.waitingSteps to listOf(step("approve", "Approve"))),
                    // A row with no step (an older node) is not one.
                    mapOf(GDF.gedraId to "fd:acme:2"),
                ),
            ),
            DisplayZone.scriptEnv(),
        )
        assertEquals(7, read.total)
        val form = read.forms.single()
        assertEquals("fd:acme:1", form.gedraId)
        // No display column names it.
        assertEquals("A form", form.heading)
        assertEquals(listOf("approve" to "Approve"), form.steps.map { it.taskId to it.actionText })
    }

    @Test
    fun theCardShowsOnlyOnceSomethingIsKnownToWait() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.waiting, HCARD.title to "Waiting on you", HCARD.kind to HCARD.kindWaitingOnYou),
                mapOf(HFLD.id to HCARD.administer, HCARD.title to "Your client"),
            ),
        )
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(null)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(WaitingRead(0, emptyList()))).map { it.id })
        assertEquals(
            listOf(HCARD.waiting, HCARD.administer),
            visibleHomeCards(cards, readsOf(WaitingRead(1, listOf(WaitingForm("g", "A form", emptyList()))))).map { it.id },
        )
    }

    @Test
    fun theFormsListingSaysWhenItIsNarrowedToWhatWaits() {
        // The toolbar's toggle says it is on, so the filter takes no chip; its count is Home's card's, whatever is applied.
        waitingToggle(mapOf(GDF.needsMe to "true", "track" to "kotlin"), 3, "Waiting on you").let {
            assertEquals("Waiting on you (3)", it.label)
            assertTrue(it.pressed && it.enabled)
        }
        waitingToggle(emptyMap(), 3, "Waiting on you").let { assertFalse(it.pressed); assertTrue(it.enabled) }
        // At zero it stays, dimmed, unless it is on and must be turned off; and while the count is unknown it has none.
        assertFalse(waitingToggle(emptyMap(), 0, "Waiting on you").enabled)
        assertTrue(waitingToggle(mapOf(GDF.needsMe to "true"), 0, "Waiting on you").enabled)
        assertEquals("Mine to do", waitingToggle(emptyMap(), null, "Mine to do").label)
        // One click flips it, keeping every other filter.
        assertEquals(mapOf("track" to "kotlin", GDF.needsMe to "true"), withWaitingToggled(mapOf("track" to "kotlin")))
        assertEquals(mapOf("track" to "kotlin"), withWaitingToggled(mapOf("track" to "kotlin", GDF.needsMe to "true")))
        assertEquals(listOf(HP.page to HMENU.pageForms, GDF.needsMe to "true"), waitingListingRoute())
        // Confined to a client, for a caller who sees across clients, as the card's read was.
        assertEquals(
            listOf(HP.page to HMENU.pageForms, GDF.needsMe to "true", EI.client to "acme"),
            waitingListingRoute("acme"),
        )
    }
}

/** Pure-logic coverage for the home page's jump links (issue #1576): which of the copy's links are kept. */
class JumpLinksTest {
    private val shipped = """
        - [Try a demo](#home.demos)
        - [Your own client](#home.own-client)
    """.trimIndent()

    @Test
    fun aLinkToASectionIsKeptOnlyWhileTheSectionIsDrawn() {
        assertEquals(shipped, jumpLinksFor(shipped, setOf(HSEC.demos, HSEC.ownClient)))
        assertEquals("- [Your own client](#home.own-client)", jumpLinksFor(shipped, setOf(HSEC.ownClient)))
    }

    @Test
    fun nothingIsDrawnWhenNoLinkIsLeftOrTheCopyIsBlank() {
        assertNull(jumpLinksFor(shipped, emptySet()))
        assertNull(jumpLinksFor("", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor("   \n", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor(null, setOf(HSEC.ownClient)))
        // Prose alone is no row of links, and neither is an image.
        assertNull(jumpLinksFor("Jump to:", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor("![](img:sample:arrow)\n- [Try a demo](#home.demos)", emptySet()))
    }

    @Test
    fun aLinkToAnythingButAnAnchorIsAlwaysKept() {
        val mixed = """
            - [Start here](/docs/start-here.md)
            - [Try a demo](#home.demos)
            - [Join](/wa/join "Join a demo")
            - [The project](https://example.com)
        """.trimIndent()
        assertEquals(
            "- [Start here](/docs/start-here.md)\n- [Join](/wa/join \"Join a demo\")\n- [The project](https://example.com)",
            jumpLinksFor(mixed, emptySet()),
        )
    }

    @Test
    fun aRowOnOneLineLosesTheHiddenLinkAndItsSeparator() {
        val row = "[Try a demo](#home.demos) · [Start here](/docs/start-here.md) · [Your own client](#home.own-client)"
        assertEquals(row, jumpLinksFor(row, setOf(HSEC.demos, HSEC.ownClient)))
        assertEquals(
            "[Start here](/docs/start-here.md) · [Your own client](#home.own-client)",
            jumpLinksFor(row, setOf(HSEC.ownClient)),
        )
        assertEquals(
            "[Try a demo](#home.demos) · [Start here](/docs/start-here.md)",
            jumpLinksFor(row, setOf(HSEC.demos)),
        )
        assertEquals("[Start here](/docs/start-here.md)", jumpLinksFor(row, emptySet()))
        val sectionsOnly = "[Try a demo](#home.demos) · [Your own client](#home.own-client)"
        assertEquals("[Try a demo](#home.demos)", jumpLinksFor(sectionsOnly, setOf(HSEC.demos)))
        assertNull(jumpLinksFor(sectionsOnly, emptySet()))
        // A lead-in stays; the separator after a removed first link goes with it.
        assertEquals(
            "Jump to: [Your own client](#home.own-client).",
            jumpLinksFor("Jump to: [Try a demo](#home.demos) | [Your own client](#home.own-client).", setOf(HSEC.ownClient)),
        )
    }

    @Test
    fun aHiddenLinkInProseKeepsItsWords() {
        assertEquals(
            "- Try a demo, or [read first](/docs/start-here.md)",
            jumpLinksFor("- [Try a demo](#home.demos), or [read first](/docs/start-here.md)", emptySet()),
        )
        assertEquals(
            "Start with a demo, then [read on](/docs/start-here.md).",
            jumpLinksFor("Start with [a demo](#home.demos), then [read on](/docs/start-here.md).", emptySet()),
        )
    }

    @Test
    fun aDroppedItemTakesItsContinuationAndNestedItemsWithIt() {
        val source = """
            Jump to:

            * [Try a demo](#home.demos)
              which is shared with everyone
              - [How joining works](/docs/joining.md)

              More about demos.
            + [Your own client](#home.own-client)
            1. [Demos again](#home.demos)
        """.trimIndent()
        assertEquals(
            "Jump to:\n\n\n+ [Your own client](#home.own-client)",
            jumpLinksFor(source, setOf(HSEC.ownClient)),
        )
        // Kept, the item keeps what is under it.
        assertEquals(
            "- [Try a demo](#home.demos)\n  - [How joining works](/docs/joining.md)",
            jumpLinksFor("- [Try a demo](#home.demos)\n  - [How joining works](/docs/joining.md)", setOf(HSEC.demos)),
        )
    }
}

/** Pure-logic coverage for the public home page (issue #1324): who gets it, and what its sections list. */
class PublicHomeTest {

    @Test
    fun itIsForSomeoneSignedInToPublicOnly() {
        assertTrue(isPublicHome(UserProfile(authId = "a1", client = CL.public)))
        // A visitor who has not signed in acts in public too, and keeps the ordinary home page.
        assertTrue(!isPublicHome(UserProfile.anonymous()))
        assertTrue(!isPublicHome(UserProfile(authId = "a2", client = "acme")))
    }

    @Test
    fun aDemoOffersToOpenWhatWasJoinedAndToJoinWhatRemains() {
        fun role(name: String, userId: Long? = null, removed: Boolean = false) = JoinRoleView(name, name, PERSONA.admin, "", userId, removed)
        val none = JoinableView("acme", "Acme", null, listOf(role("admin")))
        assertTrue(joinedRoles(none).isEmpty())
        assertTrue(hasRoleToJoin(none))
        val joined = JoinableView("acme", "Acme", null, listOf(role("admin", userId = 7)))
        assertEquals(listOf("admin"), joinedRoles(joined).map { it.name })
        assertTrue(!hasRoleToJoin(joined))
        // A role an administrator took away is neither opened nor offered again.
        val taken = JoinableView("acme", "Acme", null, listOf(role("admin", removed = true)))
        assertTrue(joinedRoles(taken).isEmpty())
        assertTrue(!hasRoleToJoin(taken))
        // ...and is said, so the card is not left with nothing to do and no reason.
        assertEquals(listOf("admin"), removedRoles(taken).map { it.name })
        assertTrue(removedRoles(none).isEmpty())
        val partly = JoinableView("confdemo", "Conference demo", null, listOf(role("speaker", userId = 9), role("organizer")))
        assertEquals(listOf("speaker"), joinedRoles(partly).map { it.name })
        assertTrue(hasRoleToJoin(partly))
    }

    @Test
    fun theSectionsDrawnAreTheOnesThatHaveSomethingToOffer() {
        fun config(joinOffered: Boolean, users: List<UserChoice>) = HomeConfig(
            fragment = FragmentRef("home", "b1"),
            layout = HomeLayout(topBar = false, leftBar = false, inlineLinks = false),
            links = emptyList(),
            menu = emptyList(),
            user = UserProfile(authId = "a1", client = CL.public),
            users = users,
            canManageUsers = false,
            canSeeAllClients = false,
            hasCoreDetails = false,
            sourceRepoBase = null,
            joinOffered = joinOffered,
        )
        val me = UserChoice(1, CL.public, PERSONA.member, isCurrent = true)
        val other = UserChoice(2, "acme", PERSONA.admin, clientName = "Acme")
        assertEquals(setOf(HSEC.demos, HSEC.ownClient), publicHomeSections(config(true, listOf(me, other))))
        // No joining offered: no demos. Your own client is always there. Other users add no section: the account
        // menu lists them (issue #1672).
        assertEquals(setOf(HSEC.ownClient), publicHomeSections(config(false, listOf(me, other))))
        assertEquals(setOf(HSEC.demos, HSEC.ownClient), publicHomeSections(config(true, listOf(me))))
        assertEquals(setOf(HSEC.ownClient), publicHomeSections(config(false, emptyList())))
    }
}

/** Pure-logic coverage for creating one's own client from the home page (issue #1228). */
class CreateOwnClientTest {

    @Test
    fun theRequestSendsTheNameAndOnlyWhatWasGiven() {
        assertEquals(
            mapOf(CLD.name to "Maker's Shop", CLD.description to "Mine.", CLD.timeZone to "America/Chicago"),
            createOwnClientRequest("  Maker's Shop ", " Mine. ", "America/Chicago"),
        )
        // No note and no zone: neither is sent, so the deployment's zone applies.
        assertEquals(mapOf(CLD.name to "Shop"), createOwnClientRequest("Shop", " ", ""))
        // The starter workflows (issue #1346) are the server's default; only asking for none is sent.
        assertEquals(mapOf(CLD.name to "Shop", CLD.starterWorkflows to false), createOwnClientRequest("Shop", "", "", starterWorkflows = false))
    }

    @Test
    fun aClientStartedFromATemplateNamesItAndSaysNothingOfStarters() {
        assertEquals(
            mapOf(CLD.name to "Shop", CLD.extendsFromClientId to "starterkit"),
            createOwnClientRequest("Shop", "", "", extendsFrom = " starterkit "),
        )
        // The template's workflows are inherited, so the starters' switch is not sent even when it was unticked.
        assertEquals(
            mapOf(CLD.name to "Shop", CLD.extendsFromClientId to "starterkit"),
            createOwnClientRequest("Shop", "", "", starterWorkflows = false, extendsFrom = "starterkit"),
        )
    }

    @Test
    fun theTemplatesOfferedAreReadFromTheShellConfig() {
        val templates = selfServeTemplatesFrom(
            listOf(mapOf(HFLD.id to "starterkit", HFLD.label to "Starter kit"), mapOf(HFLD.id to "bare"), mapOf(HFLD.label to "No id")),
        )
        assertEquals(listOf("starterkit" to "Starter kit", "bare" to "bare"), templates.map { it.clientId to it.name })
        assertEquals(emptyList(), selfServeTemplatesFrom(null))
    }

    @Test
    fun theChosenTemplateHoldsOnlyWhileItIsOffered() {
        val offered = listOf(SelfServeTemplate("starterkit", "Starter kit"))
        assertEquals("starterkit", effectiveStartFrom("starterkit", offered))
        // Dropped by a re-read of the shell config: a blank client, not a stale id the create would refuse.
        assertEquals("", effectiveStartFrom("starterkit", emptyList()))
        assertEquals("", effectiveStartFrom("gone", offered))
        assertEquals("", effectiveStartFrom("", offered))
    }

    @Test
    fun theHintSaysWhatTheClientStartsWith() {
        assertTrue(startFromHint("starterkit", starters = true).contains("New form is the template's"))
        // A blank client takes the two starters unless the person unticks them.
        assertTrue(startFromHint("", starters = true).contains("two workflows"))
        assertTrue(startFromHint("", starters = false).contains("starts empty"))
    }

    @Test
    fun theRequestCarriesTheHomeCopyGivenAndLeavesOutABlankOne() {
        val request = createOwnClientRequest(
            "Shop", "", "", mapOf(CLD.homeBrand to " Shop Co ", CLD.homeIntro to "**Hi**", CLD.homeNote to "  ", "other" to "x"),
        )
        assertEquals(mapOf(CLD.name to "Shop", CLD.homeBrand to "Shop Co", CLD.homeIntro to "**Hi**"), request)
    }

    @Test
    fun anImageIsCheckedAgainstItsSlotBeforeTheClientIsMade() {
        assertNull(imageProblem(WAS.favicon, "image/png", 10_000))
        assertNull(imageProblem(WAS.brandMark, "image/svg+xml", 900_000))
        // Too large for the favicon, though a brand mark that size would do.
        imageProblem(WAS.favicon, "image/png", 300 * 1024)!!.let { assertTrue("300 KB" in it && "256 KB" in it, it) }
        assertNull(imageProblem(WAS.brandMark, "image/png", 300 * 1024))
        // A type no slot takes, and an empty file, are refused before the client is made, each for its own reason.
        val wrongType = imageProblem(WAS.brandMark, "image/gif", 10)!!
        val empty = imageProblem(WAS.favicon, "image/png", 0)!!
        assertTrue(wrongType != empty)
        assertTrue("256 KB" in imageLimitText(WAS.favicon))
    }

    @Test
    fun anImageThatDidNotUploadIsSaidAndTheCardStays() {
        assertNull(imageUploadsNote(emptyList()))
        imageUploadsNote(listOf(ImageUploadFailure("Favicon", "Not a PNG.")))!!.let {
            assertTrue("the favicon" in it && "Not a PNG." in it, it)
        }
        val both = imageUploadsNote(listOf(ImageUploadFailure("Favicon", "A."), ImageUploadFailure("Brand mark", "B.")))!!
        assertTrue(both.contains("the favicon") && both.contains("the brand mark"), both)
    }

    @Test
    fun aCreateThatDidNotMoveSaysTheImagesChosenWereNotUploaded() {
        assertNull(imagesNotUploadedNote(emptyList()))
        assertTrue("favicon" in imagesNotUploadedNote(listOf("Favicon"))!!)
        imagesNotUploadedNote(listOf("Favicon", "Brand mark"))!!.let { assertTrue("favicon" in it && "brand mark" in it, it) }
    }

    @Test
    fun theCardOffersTheCreateUntilTheClientIsMadeThenOpensItOrSaysItIsGone() {
        assertEquals(OwnClientCardMode.create, ownClientCardMode(null, madeAbsent = false))
        assertEquals(OwnClientCardMode.open, ownClientCardMode("free_x", madeAbsent = false))
        assertEquals(OwnClientCardMode.unavailable, ownClientCardMode("free_x", madeAbsent = true))
    }

    @Test
    fun theCardStartsOnTheBrowsersZoneOnlyWhenAClientMayNameIt() {
        assertEquals("America/Chicago", ownClientStartZone("America/Chicago"))
        assertEquals("UTC", ownClientStartZone("UTC"))
        // An offset a browser may report is refused by the create: start on none instead.
        assertEquals("", ownClientStartZone("Etc/GMT+5"))
        assertEquals("", ownClientStartZone(null))
    }

    @Test
    fun aCreateThatMovedMovesThePageAndOneThatDidNotRereadsWithTheReason() {
        val moved = createOwnClientNext(parseCreateOwnClientResult(mapOf(CLD.client to "free_a", CLD.switched to true, CLD.present to true)))
        assertTrue(moved.moves)
        assertNull(moved.note)
        val stuck = createOwnClientNext(
            parseCreateOwnClientResult(mapOf(CLD.client to "free_b", CLD.switched to false, CLD.present to true, CLD.userFailure to "Full.")),
        )
        assertTrue(stuck.rereads, "made, so the config is re-read and the create not offered again")
        assertTrue(stuck.note!!.contains("free_b"))
    }

    @Test
    fun aCreateThatMovedTheSessionSaysNothingMore() {
        val moved = parseCreateOwnClientResult(mapOf(CLD.client to "free_shopab12cd", CLD.switched to true, CLD.present to true))
        assertEquals("free_shopab12cd", moved.client)
        assertTrue(moved.switched)
        assertNull(createOwnClientFailureText(moved))
    }

    @Test
    fun aCreateThatDidNotMoveSaysWhyAndThatTheClientIsMade() {
        val noUser = parseCreateOwnClientResult(
            mapOf(CLD.client to "free_x", CLD.switched to false, CLD.present to true, CLD.userFailure to "The plan is full."),
        )
        // Each says the client was made, naming it, and why the session did not move into it.
        createOwnClientFailureText(noUser)!!.let { assertTrue("free_x" in it && it.endsWith("The plan is full."), it) }
        val absent = parseCreateOwnClientResult(mapOf(CLD.client to "free_y", CLD.switched to false, CLD.present to false))
        val loadFailed = parseCreateOwnClientResult(
            mapOf(CLD.client to "free_z", CLD.switched to false, CLD.present to false, CLD.loadFailure to "A trait is missing."),
        )
        assertTrue("free_y" in createOwnClientFailureText(absent)!!)
        createOwnClientFailureText(loadFailed)!!.let { assertTrue("free_z" in it && it.endsWith("A trait is missing."), it) }
        assertEquals(3, setOf(noUser, absent, loadFailed).map { createOwnClientFailureText(it) }.toSet().size)
    }
}
