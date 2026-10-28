package com.dynamicruntime.common.home

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.cfact.CFACTS

/**
 * One audience a home-menu item is drawn for (issue #1094): the [condition] the menu writes, and the [name] a person
 * reads and chooses it by. [offered] says whether a client may show **any** item to it; an audience that is not is
 * still named, since the items shipped for it are listed, and may be chosen for those items alone. [deployment] says
 * that it is the deployment's own -- people a client's own are never among -- which the Clients page's menu editor
 * reads to list, by default, only the items a client's people may be offered (issue #1397).
 */
/**
 * One audience: a menu condition and what a person calls it. [retired] is one the shipped menu no longer draws for but a
 * client's stored menu edit may still hold (issue #1432): it keeps its name, so the edit is not shown as custom, and is
 * never offered.
 */
class MenuAudience(
    val condition: String,
    val name: String,
    val offered: Boolean,
    val deployment: Boolean = false,
    val retired: Boolean = false,
)

/**
 * The names of the audiences [homeMenuBlock] draws for (issue #1094), **declared beside the menu** so that a
 * condition and what it is called cannot be kept in two places. A client's administrator choosing who an item is
 * shown to chooses by these names; the expression is what is stored, and stays the detail.
 *
 * **Every condition the shipped menu writes is here**, which `HomeMenuAudiencesTest` holds: a new menu item with a
 * new condition fails that test until the condition is given a name and it is decided whether to offer it. A condition
 * the shipped menu stopped writing stays, [retired][MenuAudience.retired], for the clients whose edits chose it. A
 * condition that is not here -- one a source config or another component wrote by hand -- has no name, reads as
 * custom, and is never offered.
 *
 * **Which are offered.** The ones that divide a client's own people in a way an administrator of it would mean. The
 * rest are named and withheld, each for a reason that is the same reason: within one client they pick out nobody an
 * administrator could want to address, or the same people as an offered one.
 * - The deployment's own -- its operators, a test instance, a debug session, the administrators who see every
 *   client -- can only hide an item from the client's people.
 * - "The administrator level, and editors" (the Users page's, issue #1432) and "administers this client and no other"
 *   are, inside a real client, "Administrators and editors" and the administrators again: several names for one
 *   audience is the confusion this table exists to remove. `administersClient` is the
 *   one offered, being the statement about scope the rest of the menu asks. So are a customer's root administrators
 *   (issue #1114), inside the root client.
 * - `#always` differs from "everyone" only on an edge node, which is not a distinction of people at all.
 */
object HomeMenuAudiences {
    val all: List<MenuAudience> = listOf(
        MenuAudience(CFACTS.app, "Everyone", offered = true),
        MenuAudience("${CFACTS.loggedIn},${CFACTS.app}", "Everyone signed in", offered = true),
        MenuAudience("${CFACTS.anonymous},${CFACTS.app}", "Signed-out visitors", offered = true),
        MenuAudience("${CFACTS.administersClient},${CFACTS.app}", "Administrators", offered = true),
        // Editors (issue #1432) see every form of the client, as its administrators do: the forms list's two entries
        // divide the client's people into those two and everyone else.
        MenuAudience("${CFACTS.seesClientForms},${CFACTS.app}", "Administrators and editors", offered = true),
        MenuAudience(
            "${CFACTS.loggedIn},~${CFACTS.seesClientForms},${CFACTS.app}", "Signed-in people who are neither administrators nor editors",
            offered = true,
        ),
        MenuAudience("${CFACTS.isClientOperator},${CFACTS.app}", "Operators and administrators", offered = true),

        MenuAudience(CFACT.alwaysName, "Everyone, edge nodes included", offered = false),
        MenuAudience(
            "(${CFACTS.hasAdminLevel}|${CFACTS.isEditor}),${CFACTS.app}", "Anyone at the administrator level, and editors",
            offered = false,
        ),
        MenuAudience("${CFACTS.isDeploymentAdmin},${CFACTS.app}", "Administrators of every client", offered = false, deployment = true),
        MenuAudience(
            "${CFACTS.administersClient},~${CFACTS.isDeploymentAdmin},${CFACTS.app}", "Administrators of this client alone",
            offered = false,
        ),
        MenuAudience(
            "${CFACTS.administersCustomer},~${CFACTS.isDeploymentAdmin},${CFACTS.app}",
            "Administrators of a customer's root client", offered = false,
        ),
        MenuAudience(CFACTS.isDeploymentOperator, "Deployment operators", offered = false, deployment = true),
        MenuAudience("${CFACTS.isDeploymentOperator},${CFACTS.app}", "Deployment operators, on an app node", offered = false, deployment = true),
        MenuAudience("${CFACTS.hasTestAccess},${CFACTS.app}", "Env-authed sessions, on a test instance", offered = false, deployment = true),
        MenuAudience("${CFACTS.isEnvDebug},${CFACTS.app}", "Sessions in debug", offered = false, deployment = true),
        MenuAudience("${CFACTS.canEnableDebug},${CFACTS.app}", "Sessions that may turn debug on", offered = false, deployment = true),

        // Retired by the editor (issue #1432), named still for the stored edits that chose them. The first means what it
        // says, so it now includes editors -- who are not administrators -- where it once meant everyone who reads only
        // their own forms, which is "Signed-in people who are neither administrators nor editors" now.
        MenuAudience(
            "${CFACTS.loggedIn},~${CFACTS.administersClient},${CFACTS.app}", "Signed-in people who are not administrators",
            offered = false, retired = true,
        ),
        MenuAudience("${CFACTS.hasAdminLevel},${CFACTS.app}", "Anyone at the administrator level", offered = false, retired = true),
    )

    private val byCondition: Map<String, MenuAudience> = all.associateBy { it.condition }

    /** The audiences any item may be shown to, in the table's order. */
    val offered: List<MenuAudience> = all.filter { it.offered }

    /**
     * The audience [condition] draws for, or null when it has no name: a withdrawn item's `#never`, which is no
     * audience, and an expression this table does not hold. An item with no condition is drawn for everyone, on
     * every node -- `#always`.
     */
    fun of(condition: String?): MenuAudience? = byCondition[CFACT.orAlways(condition)]

    /**
     * The audiences an item **shipped under [shippedCondition]** may be shown to: every [offered]
     * one, and the item's own when that is named and not among them -- so an item can always be put back where it
     * shipped, and an audience that is the deployment's own is not handed to every other item. The one answer to
     * "what may be chosen", for the listing that offers and the write that accepts.
     */
    fun choicesFor(shippedCondition: String?): List<MenuAudience> {
        val own = of(shippedCondition)
        return if (own == null || own.offered) offered else offered + own
    }
}
