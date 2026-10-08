package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.uiblock.UiRoute
import react.createContext
import react.use

/**
 * What the shell knows of the caller that a page **names itself by** (issue #1091), from the home config the app bar
 * reads on every refresh generation -- so a page's heading and a back link say what the menu entry that led there
 * said, from the one answer, and say it at once on a move between pages rather than after a fetch of their own.
 *
 * - [formsEntryLabel] is the label of **the menu entry that opens the forms listing**, as this caller's menu has it
 *   -- the shipped words, or a client's own if its configuration renamed the entry. The page takes its name from
 *   that, so the two cannot differ. Null when the caller's menu has no such entry (a client hid it), or the shell
 *   has not been told.
 * - [administersClient] is whether the caller's listings reach beyond their own rows (`HFEAT.administersClient`):
 *   what the name falls back on. True for a client's administrator, false for everyone else -- and **null until the
 *   shell has been told**, which is a moment after a hard reload, or for good when its config could not be read.
 *
 * A data class, so that a refresh which says the same thing is the same value and redraws nothing.
 */
data class ShellFacts(val administersClient: Boolean? = null, val formsEntryLabel: String? = null)

/** The context carrying the [ShellFacts]; [App] is the sole provider. The default is "not yet told". */
val ShellFactsContext = createContext(ShellFacts())

/** The shell's facts about the caller, for a component that names itself by them. */
fun useShellFacts(): ShellFacts = use(ShellFactsContext)

/**
 * The [ShellFacts] a home config says (issue #1091): the caller's fact, and the label of whichever entry of their
 * menu routes to the forms listing -- the menu offers one per caller, whichever of its two it is. Pure, covered under
 * `jsNodeTest`.
 */
fun shellFactsOf(config: HomeConfig): ShellFacts = ShellFacts(
    administersClient = config.administersClient,
    formsEntryLabel = config.menu.firstOrNull { (it.action as? UiRoute)?.page == HMENU.pageForms }?.label?.ifBlank { null },
)

/**
 * What the forms listing is called for this caller (issue #1091), in its heading and on the way back to it.
 *
 * **The menu entry's own label when the shell has it** -- so the page is called what the entry that opens it is
 * called, a client's renaming of that entry included. Otherwise the shipped words by whose forms the listing shows:
 * "My forms" for a caller who lists only their own, "Forms" for one who administers a client -- and "Forms" while
 * that is not yet known, since it is true of the page whoever is looking and "My forms" is not. Pure, covered under
 * `jsNodeTest`.
 */
fun formsListingName(facts: ShellFacts): String =
    facts.formsEntryLabel ?: if (facts.administersClient == false) "My forms" else "Forms"
