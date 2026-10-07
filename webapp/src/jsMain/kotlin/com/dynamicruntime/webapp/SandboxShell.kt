package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.user.UserChoice
import web.dom.document

/*
 * The shell's Shadow Sandbox pieces (issue #931), pure so they are covered under `jsNodeTest`: whether to offer
 * opening the sandbox, what its chip and popover say while in one, and where "Back" leads. The customer-facing word
 * is "sandbox" throughout; "Shadow" is the feature's internal name and appears in no text here.
 */

/**
 * The page root's attribute while in a sandbox (issue #1049). `app.css` keys off it to tint the page and draw a band
 * along the top edge, so being in a sandbox shows at a glance -- in a screen share too -- and not only in the bar's
 * chip. Only the sandbox is marked: of the states a page could signal, it is the one where a wrong belief about
 * where you are destroys real data.
 */
const val sandboxRootAttr = "data-sandbox"

/** Whether the page should carry [sandboxRootAttr]: the shell [config] says the caller is in a sandbox. */
fun pageInSandbox(config: HomeConfig?): Boolean = config?.sandboxOf != null

/** Sets or clears [sandboxRootAttr] on the page root, as [inSandbox] says. */
fun markPageSandbox(inSandbox: Boolean) {
    val root = document.documentElement
    if (inSandbox) root.setAttribute(sandboxRootAttr, "") else root.removeAttribute(sandboxRootAttr)
}

/**
 * Where a delete acts, as its confirmation names it (issue #1049): `acme (live data)`, or `acme's sandbox`. The
 * page's tint shows only in a sandbox, so someone in the live client who believes otherwise sees no cue missing;
 * the question at the point of damage names the client instead. [client] is the thing's own -- a form's from its
 * gedra id, a user's from their row -- never the page's, since an administrator's listing may span clients.
 */
fun deleteTargetText(client: String): String = sandboxParentOf(client)?.let { "$it's sandbox" } ?: "$client (live data)"

/** The form view's delete question for the form [gedraId] names: where it is deleted from, when the id says. */
fun deleteFormQuestion(gedraId: String?): String =
    formClientOf(gedraId)?.let { "Delete this form from ${deleteTargetText(it)}?" } ?: "Delete this form?"

/** The forms listing's compact row question for the form [gedraId] names. */
fun deleteRowQuestion(gedraId: String?): String =
    formClientOf(gedraId)?.let { "Delete from ${deleteTargetText(it)}?" } ?: "Delete?"

/** The Users page's question beside its permanent delete, for a user of [client]. */
fun deleteUserQuestion(client: String): String = "Delete this user from ${deleteTargetText(client)}?"

/** Whether the bar offers "Open sandbox": the caller may open their client's, and is not in one already. */
fun showOpenSandbox(config: HomeConfig?): Boolean = config != null && config.canOpenSandbox && config.sandboxOf == null

/**
 * The bar's chip while in a sandbox -- "Acme Sandbox" -- in the place "Open sandbox" takes outside one: always on
 * screen, as the bar is, so nobody mistakes the sandbox for the real client. Clicked, it explains itself.
 */
fun sandboxChipLabel(of: SandboxOf): String = "${of.name} Sandbox"

/** What the chip's popover says, above its way back: whose sandbox this is, what it runs, and what an edit here does. */
fun sandboxInfoText(of: SandboxOf): String =
    "You are in ${of.name}'s sandbox. It runs ${of.name}'s latest configuration, published or not, with users and " +
        "data of its own. Configuration changed here is a draft of ${of.name}'s, live there only once published."

/**
 * The user "Back to <client>" switches to: the person's own user in the client whose sandbox this is -- the one the
 * switcher (issue #749) also lists, of the current user's persona when there are several -- or null when they have
 * none to go back to there.
 */
fun sandboxWayBack(of: SandboxOf, users: List<UserChoice>): UserChoice? {
    val there = users.filter { it.client == of.client }
    // The one the sandbox user was opened from shares its persona; any other of theirs there will do.
    val persona = users.firstOrNull { it.isCurrent }?.persona
    return there.firstOrNull { it.persona == persona } ?: there.firstOrNull()
}
