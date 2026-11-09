package com.dynamicruntime.common.home

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.UiRoute
import com.dynamicruntime.common.uiblock.uiBlock

/**
 * The home cards, as data (issue #1636): what Home shows each caller beyond the copy everyone in the client reads.
 *
 * Decided here, from cfacts, as the menu is ([homeMenuBlock]): the backend says which cards a caller gets, and the
 * webapp draws the list it is handed. A component adds its own with an overlay -- the workflow assistant's are the
 * assistant module's -- and a client hides one with `#never` or rewords it, as it does a menu item.
 *
 * **Only cheap facts decide a card.** The shell config is polled, so a card whose words depend on data (how many
 * forms wait on you, whether there are drafts) names a [HCARD.kind], and the webapp reads that data when it draws the
 * card -- never here.
 *
 * Core's own cards are shortcuts to the pages a caller who does more than fill in forms works from. Each says where it
 * goes in a line, rather than only repeating the menu: Home is where a person starts. Each is named in the row of links
 * under Home's title too ([HCARD.jumpLabel]), as the public home page's sections are, so an administrator sees at the
 * top what the page offers further down.
 */
fun homeCardsBlock(): UiBlockSource = uiBlock(
    HCARD.block,
    origin = "HomeCardsBlock",
    // Cards are identified by their id: what an overlay names to mean *this* card.
    arrayKeys = mapOf(HCARD.cards to HFLD.id),
) {
    items(HCARD.cards) {
        // An invitation to register (issue #1652), for a visitor who has not signed in, wherever anyone may: the
        // walk-through, the demos, and a client of one's own all start from a user. Its words are copy, the `home`
        // file's, so a deployment rewords them as it does the home page's intro. Only where sign-in is open to every
        // address -- it invites whoever reads it; the menu's Register also serves an administrator's admin domain.
        homeCard(
            HCARD.register, "Register",
            textCopy = HCARD.registerCopy,
            action = UiRoute(HMENU.pageRegister),
            actionLabel = "Register",
            jumpLabel = "Register",
            // What a visitor reads it for (issue #1698): its button before its words, and under the title a Register
            // button of its own, so a phone has Register on its first screen.
            actionFirst = true,
            links = listOf(HomeCardLink("Log in", UiRoute(HMENU.pageLogin))),
            cfactExpression = "${CFACTS.anonymous},${CFACTS.signInOpenToEveryone},${CFACTS.app}",
            displayOrder = UIB.orderStep / 20,
        )
        // What waits on the caller (issue #1637), for anyone signed in to a client with a workflow whose step can wait:
        // drawn only when something does, ahead of every
        // other card -- the assistant's included -- since it is what to do next.
        homeCard(
            HCARD.waiting, "Waiting on you",
            "Forms with a step that is yours to take.",
            jumpLabel = "Waiting on you",
            kind = HCARD.kindWaitingOnYou,
            // Only where a step can wait at all: the card's read is a pass over the forms the caller may see.
            cfactExpression = "${CFACTS.loggedIn},${CFACTS.clientHasWaitingWorkflows},${CFACTS.app}",
            displayOrder = UIB.orderStep / 10,
        )
        // Configuration left as drafts (issue #1638): in a client with a sandbox an edit is a draft until published, and
        // nothing outside the Clients page says one is waiting. Drawn only when one is, next after what waits on the
        // caller, since publishing is also something to finish.
        homeCard(
            HCARD.drafts, "Unpublished drafts",
            "Changes to your client's configuration are tried in its sandbox, and reach the client once published.",
            jumpLabel = "Unpublished drafts",
            kind = HCARD.kindUnpublishedDrafts,
            cfactExpression = "${CFACTS.administersClient},${CFACTS.clientHasSandbox},${CFACTS.app}",
            displayOrder = UIB.orderStep / 5,
        )
        // A new client's checklist (issue #1639): for the administrator of a self-serve client, the few steps that make it
        // useful, checked off from its state. Not inside its sandbox, which has users and forms of its own: the client is
        // set up from the client. Drawn only while a step is left, after the drafts, which finish work already begun.
        homeCard(
            HCARD.checklist, "Getting started",
            "A few steps make your client ready for the people who will use it. Each is checked off once it is done.",
            jumpLabel = "Getting started",
            kind = HCARD.kindNewClientChecklist,
            cfactExpression = "${CFACTS.administersClient},${CFACTS.selfServeClient},~${CFACTS.inSandbox},${CFACTS.app}",
            displayOrder = UIB.orderStep * 3 / 10,
        )
        // An administrator of one client -- the caller "My client" is offered to; every client's administrator gets
        // the oversee card instead.
        homeCard(
            HCARD.administer, "Your client",
            "Set up your client and its home page, add the people who use it, and run its reports.",
            jumpLabel = "Your client",
            links = listOf(
                HomeCardLink("My client", UiRoute(HMENU.pageClients)),
                HomeCardLink("Users", UiRoute(HMENU.pageUsers)),
                HomeCardLink("Reports", UiRoute(HMENU.pageReports)),
            ),
            cfactExpression = "${CFACTS.administersClient},~${CFACTS.isDeploymentAdmin},${CFACTS.app}",
        )
        homeCard(
            HCARD.oversee, "Clients and customers",
            "Every client and customer on this deployment, and the people in them.",
            jumpLabel = "Clients and customers",
            links = listOf(
                HomeCardLink("Clients", UiRoute(HMENU.pageClients)),
                HomeCardLink("Customers", UiRoute(HMENU.pageCustomers)),
                HomeCardLink("Users", UiRoute(HMENU.pageUsers)),
            ),
            cfactExpression = "${CFACTS.isDeploymentAdmin},${CFACTS.app}",
        )
        homeCard(
            HCARD.edit, "Your client's forms",
            "As an editor you see and edit every form in your client, and see who its people are.",
            jumpLabel = "Your client's forms",
            links = listOf(
                HomeCardLink("Forms", UiRoute(HMENU.pageForms)),
                HomeCardLink("Users", UiRoute(HMENU.pageUsers)),
            ),
            cfactExpression = "${CFACTS.isEditor},${CFACTS.app}",
        )
        homeCard(
            HCARD.operate, "Operating this deployment",
            "How the deployment is set up and running: its settings, its boot checks, and the rest of the operator pages.",
            jumpLabel = "Operating this deployment",
            links = listOf(
                HomeCardLink("Operator overview", UiRoute(HMENU.pageOperator)),
                HomeCardLink("Boot checks", UiRoute(HMENU.pageBootChecks)),
                HomeCardLink("Environment", UiRoute(HMENU.pageEnv)),
            ),
            cfactExpression = CFACTS.isDeploymentOperator,
        )
    }
}
