package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.UserChoice

/*
 * The shell's Shadow Sandbox pieces (issue #931), pure so they are covered under `jsNodeTest`: whether to offer
 * opening the sandbox, what its chip and popover say while in one, and where "Back" leads. The customer-facing word
 * is "sandbox" throughout; "Shadow" is the feature's internal name and appears in no text here.
 */

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
