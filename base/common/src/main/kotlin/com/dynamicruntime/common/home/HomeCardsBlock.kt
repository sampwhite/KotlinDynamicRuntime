package com.dynamicruntime.common.home

import com.dynamicruntime.common.cfact.CFACTS
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
