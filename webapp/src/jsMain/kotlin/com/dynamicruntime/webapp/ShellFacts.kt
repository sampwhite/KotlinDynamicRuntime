package com.dynamicruntime.webapp

import react.createContext
import react.use

/**
 * What the shell knows of the caller that a page **names itself by** (issue #1091), from the home config the app bar
 * reads on every refresh generation -- so a page's heading and a back link say what the menu entry that led there
 * said, from the one answer, and say it at once on a move between pages rather than after a fetch of their own.
 *
 * [administersClient] is whether the caller's listings reach beyond their own rows (`HFEAT.administersClient`): true
 * for a client's administrator, false for everyone else -- and **null until the shell has been told**, which is a
 * moment after a hard reload, or for good when its config could not be read. A name chosen from it has to be right
 * for that case too ([formsListingName]).
 */
class ShellFacts(val administersClient: Boolean? = null)

/** The context carrying the [ShellFacts]; [App] is the sole provider. The default is "not yet told". */
val ShellFactsContext = createContext(ShellFacts())

/** The shell's facts about the caller, for a component that names itself by them. */
fun useShellFacts(): ShellFacts = use(ShellFactsContext)

/**
 * What the forms listing is called for this caller (issue #1091), in its heading and on the way back to it -- the
 * menu's two entries for the page say the same, from the same rule asked as a cfact. "My forms" for a caller who
 * lists only their own. "Forms" for one who [administersClient], whose listing is every form in their client -- and
 * while that is not yet known, since "Forms" is true of the page whoever is looking and "My forms" is not. Pure,
 * covered under `jsNodeTest`.
 */
fun formsListingName(administersClient: Boolean?): String = if (administersClient == false) "My forms" else "Forms"
