package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
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
 * - [seesClientForms] is whether the caller's listings reach beyond their own rows (`HFEAT.seesClientForms`): what
 *   the name falls back on. True for a client's administrator and for an editor (issue #1432), false for everyone
 *   else -- and **null until the shell has been told**, which is a moment after a hard reload, or for good when its
 *   config could not be read.
 * - [docs] and [sourceRepoBase] are the served documents and the source repository's blob base (issue #1136), what a
 *   link in form copy resolves against -- see [copyLinkResolver]. Empty until the shell has been told, when a link to
 *   a document is drawn as its text (issue #1380).
 * - [fileRefs] are the file refs the caller's client is served (issue #1186), what an image in copy naming one is
 *   drawn from -- see [copyRefAddress]. Empty until the shell has been told, when such an image draws its alt text.
 * - [inSandbox] is whether the session is in a sandbox (issue #1330), which decides how a switch to another of the
 *   person's users is made -- see [switchesFromSandbox]. False until the shell has been told.
 * - [sandboxWayBackUserId] is, in a sandbox, the user the app bar's "Back to <client>" switches to ([sandboxWayBack]),
 *   so a page can offer the same way back where it is wanted (a sandbox's own Clients page, issue #1422). Null outside
 *   a sandbox, when the person has no user to go back to, and until the shell has been told.
 * - [reach] is what the caller may do (issue #1479), [timeZone] and [timeZoneLabel] the zone their moments are shown in,
 *   and [sandboxOfName] the client whose sandbox they are in -- what Profile's account panel says of them. Nothing
 *   (no reach, no zone, no sandbox) until the shell has been told.
 * - [whatIsThisLink] and [whatIsThisHide] are the words of every "What is this?" link and of closing what it opened
 *   ([WhatIsThis], issue #1330): the app's words, not a page's, so they are the shell's copy (the `home` file's
 *   `whatIsThis` namespace) rather than each page's. Null until the shell's copy has arrived, when the link says it
 *   in English.
 *
 * A data class, so that a refresh which says the same thing is the same value and redraws nothing.
 */
data class ShellFacts(
    val seesClientForms: Boolean? = null,
    val formsEntryLabel: String? = null,
    val docs: List<HomeLink> = emptyList(),
    val sourceRepoBase: String? = null,
    val fileRefs: Map<String, String> = emptyMap(),
    val images: Map<String, String> = emptyMap(),
    val inSandbox: Boolean = false,
    val sandboxWayBackUserId: Long? = null,
    val reach: CallerReach = CallerReach(),
    val timeZone: String? = null,
    val timeZoneLabel: String? = null,
    val sandboxOfName: String? = null,
    val whatIsThisLink: String? = null,
    val whatIsThisHide: String? = null,
)

/**
 * What the caller may do (issue #1479), as the shell's flags say it: administer users at all ([managesUsers]), across
 * every client ([allClients]), a client of their own or every one ([administersClient]), be an editor of their client
 * ([editsClient]), and open its sandbox ([opensSandbox]). What Profile's "What you can do here" is built from
 * ([profileAbilities]); the pages each flag drives keep reading the shell config they always have.
 */
data class CallerReach(
    val managesUsers: Boolean = false,
    val allClients: Boolean = false,
    val administersClient: Boolean = false,
    val editsClient: Boolean = false,
    val opensSandbox: Boolean = false,
)

/** The context carrying the [ShellFacts]; [App] is the sole provider. The default is "not yet told". */
val ShellFactsContext = createContext(ShellFacts())

/** The shell's facts about the caller, for a component that names itself by them. */
fun useShellFacts(): ShellFacts = use(ShellFactsContext)

/**
 * The [ShellFacts] a home config says (issue #1091): the caller's fact, and the label of whichever entry of their
 * menu routes to the forms listing -- the menu offers one per caller, whichever of its two it is -- and, once the
 * shell's copy ([shellCopy], the `home` file) has arrived, the words of a "What is this?" link. Pure, covered under
 * `jsNodeTest`.
 */
fun shellFactsOf(config: HomeConfig, shellCopy: Copy? = null): ShellFacts = ShellFacts(
    seesClientForms = config.seesClientForms,
    formsEntryLabel = config.menu.firstOrNull { (it.action as? UiRoute)?.page == HMENU.pageForms }?.label?.ifBlank { null },
    docs = config.links,
    sourceRepoBase = config.sourceRepoBase,
    fileRefs = config.fileRefs,
    images = config.images,
    inSandbox = switchesFromSandbox(config),
    sandboxWayBackUserId = config.sandboxOf?.let { sandboxWayBack(it, config.users)?.userId },
    reach = CallerReach(
        managesUsers = config.canManageUsers,
        allClients = config.canSeeAllClients,
        administersClient = config.administersClient,
        editsClient = config.browsesUsers,
        opensSandbox = config.canOpenSandbox,
    ),
    timeZone = config.timeZone,
    timeZoneLabel = config.timeZoneLabel,
    sandboxOfName = config.sandboxOf?.name,
    whatIsThisLink = shellCopy?.opt(HFRAG.whatIsThisNs, HFRAG.whatIsThisLink)?.ifBlank { null },
    whatIsThisHide = shellCopy?.opt(HFRAG.whatIsThisNs, HFRAG.whatIsThisHide)?.ifBlank { null },
)

/**
 * Where an image in copy naming one of the caller's client's file refs (`![Our logo](ref:logo)`) is drawn from
 * (issue #1186): the static root's address of its current file, `/<appId>/ref/logo:<hash>`, which a browser keeps for
 * good -- a replaced image is a new name, so a new address -- and nothing for a ref the client is not served, which
 * then draws its alt text. What [Markdown] uses unless it is told otherwise.
 */
fun ShellFacts.copyRefAddress(addressOf: (String) -> String = Http::fileRefUrl): (String) -> String? = refAddressFrom(fileRefs, addressOf)

/**
 * Where an image in copy naming one a component ships (`![A diagram](img:sample:diagram)`) is drawn from (issue #1339):
 * the static root's address of it, `/<appId>/img/sample:diagram:<hash>`, kept for good, and nothing for an image no
 * component ships, which then draws its alt text. The same on every surface, the copy editor's included.
 */
fun ShellFacts.copyImageAddress(addressOf: (String) -> String = Http::componentImageUrl): (String) -> String? =
    imageAddressFrom(images, addressOf)

/**
 * How a link in **form copy** -- a label, a description, a task's text -- is resolved (issue #1136): as a link in a
 * document is, from the repo root, since form copy belongs to no document. So `[Review tasks](/docs/demo-client/review.md)`
 * opens that document in the app where it is served, or in the source repository where it is not and one is
 * configured; a link to anything else is left as written. Pure, covered under `jsNodeTest`.
 */
fun ShellFacts.copyLinkResolver(): (String) -> String = docLinkResolver("", docs, sourceRepoBase)

/**
 * What the forms listing is called for this caller (issue #1091), in its heading and on the way back to it.
 *
 * **The menu entry's own label when the shell has it** -- so the page is called what the entry that opens it is
 * called, a client's renaming of that entry included. Otherwise the shipped words by whose forms the listing shows:
 * "My forms" for a caller who lists only their own, "Forms" for one who reads a whole client's -- and "Forms" while
 * that is not yet known, since it is true of the page whoever is looking and "My forms" is not. Pure, covered under
 * `jsNodeTest`.
 */
fun formsListingName(facts: ShellFacts): String =
    facts.formsEntryLabel ?: if (facts.seesClientForms == false) "My forms" else "Forms"
