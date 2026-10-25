package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiAction
import com.dynamicruntime.common.uiblock.parseUiAction
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.util.resolveDocLink
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async

/**
 * One navigable Markdown document offered by the home page, already carrying its cache-busting build id.
 * [sourcePath] is its repo-relative source, used to resolve the document's interior links (issue #492). A data
 * class, so a refresh that serves the same documents is the same value and redraws nothing that holds them.
 */
data class HomeLink(
    val id: String, val label: String, val docId: String, val buildId: String, val sourcePath: String,
    /** One line on what the document covers (issue #554); empty when the config carries none. */
    val description: String = "",
    /**
     * Whether the Documents listing offers it (issue #1136). An unlisted document is a page of a suite, reached by a
     * link; it is still here, since a link to it resolves through this list.
     */
    val listed: Boolean = true,
)

/** Which of the three link presentations this deployment enabled. Independent toggles: any combination. */
class HomeLayout(val topBar: Boolean, val leftBar: Boolean, val inlineLinks: Boolean)

/**
 * One app-bar menu item, exactly as the backend composed it for this caller: an [id] to key behavior off, a
 * [label] to show, and an [action] saying what it does. The frontend renders the list it is given -- an item
 * the caller may not have simply is not in it.
 *
 * [action] is **one** value, a route or a call (issue #483), where it was once a `page` and an `action` that
 * could be both or neither.
 */
class MenuItem(val id: String, val label: String, val action: UiAction?, val parentId: String? = null)

/** The home page's construction manifest: where its copy lives, how to lay it out, and what to link to. */
class HomeConfig(
    val fragment: FragmentRef,
    val layout: HomeLayout,
    val links: List<HomeLink>,
    /** The app-bar menu for this caller, in display order. */
    val menu: List<MenuItem>,
    /** Who the caller is; the anonymous profile when signed out. */
    val user: UserProfile,
    /**
     * The users the caller's identity may act as (issue #749), the current one marked -- what the account menu
     * offers to switch to. Empty when signed out; a list of one means there is nothing to switch to.
     */
    val users: List<UserChoice>,
    /** Whether the caller may create and edit other users (drives the Users page, not just the menu). */
    val canManageUsers: Boolean,
    /** Whether the caller administers across clients (holds allClients, issue #668) -- drives the forms list's
     *  Client column and its filter-by-client. */
    val canSeeAllClients: Boolean,
    /** Whether the caller's client declares a survey workflow (issue #695) -- drives the forms list's
     *  survey-status filter. */
    val hasSurvey: Boolean,
    /**
     * The source repository's blob base (`.../blob/<branch>`) for rewriting a document's interior links, or
     * null when the deployment configured none -- then a non-document interior link is left as written (#492).
     */
    val sourceRepoBase: String?,
    /** Whether the caller may open their own client's sandbox (issue #931) -- offers the bar's "Open sandbox". */
    val canOpenSandbox: Boolean = false,
    /**
     * Whether the caller administers a client -- their own or every one (issue #1091) -- and so lists more than their
     * own rows. What a page named for whose rows it shows keys on ([formsListingName]); false for an administrator in
     * `public`, whom [canManageUsers] is true for.
     */
    val administersClient: Boolean = false,
    /** When the caller is in a client's sandbox (issue #931): which client's, for the marker and the way back. */
    val sandboxOf: SandboxOf? = null,
    /**
     * Whether the caller's client is a shared demo space visitors join (issue #1248), so the bar carries the standing
     * note that what they do there, other visitors see.
     */
    val sharedDemo: Boolean = false,
    /**
     * For a caller signed in to `public`, the self-serve client their person created (issue #1228) -- one per
     * person -- so the home page offers to open it rather than to create another; null when they have made none.
     */
    val selfServeClient: String? = null,
    /** Beside [selfServeClient], whether this site no longer carries that client (retired, or run elsewhere). */
    val selfServeClientAbsent: Boolean = false,
    /**
     * Whether this deployment offers a caller signed in to `public` to join a demo client (issue #1248), so the public
     * home page (issue #1324) asks which demos there are.
     */
    val joinOffered: Boolean = false,
    /**
     * The caller's client's own web assets (issue #1130): each slot it has set (`WAS.favicon`, `WAS.brandMark`) to
     * its image by name and content. A slot not in it is the deployment's own image.
     */
    val webAssets: Map<String, String> = emptyMap(),
    /**
     * The file refs the caller's client is served (issue #1186), each id to its name (`logo:<hash>`) -- what copy
     * naming the image `ref:logo` is drawn from ([ShellFacts.copyRefAddress]). A ref not in it draws its alt text.
     */
    val fileRefs: Map<String, String> = emptyMap(),
    /**
     * The images components ship (issue #1339), each rooted name to its served name (`sample:diagram:<hash>`) -- what
     * copy naming the image `img:sample:diagram` is drawn from ([ShellFacts.copyImageAddress]). The same for everybody.
     */
    val images: Map<String, String> = emptyMap(),
    /**
     * The IANA time zone the caller's moments are shown in (issue #1115, [DisplayZone]): their client's, else the
     * deployment's display default; null when neither is set, for the deployment's day start.
     */
    val timeZone: String? = null,
    /** Beside [timeZone], the backend's generic short name for it ("CT"), its moments' label (issue #1321). */
    val timeZoneLabel: String? = null,
)

/**
 * The client whose sandbox the caller is in (issue #931): its id, the name the marker speaks of, and whether nobody
 * stands behind the caller's sandbox user any more ([stranded], issue #1197), so it acts with no roles.
 */
class SandboxOf(val client: String, val name: String, val stranded: Boolean = false)

/**
 * The pure [UiConfig] -> [HomeConfig] mapping, separated from the fetch so it is unit-testable (issue #161):
 * the links and menu lists, the layout toggles, the caller's profile, and the manage-users flag, all read off
 * the group's own keys with per-field fallbacks.
 */
fun homeConfigFrom(config: UiConfig): HomeConfig {
    val links = config.state[HFLD.links].toJsonListOfMaps().map { link ->
        HomeLink(
            id = link[HFLD.id] as? String ?: "",
            label = link[HFLD.label] as? String ?: "",
            docId = link[HFLD.docId] as? String ?: "",
            buildId = link[HFLD.buildId] as? String ?: "",
            sourcePath = link[HFLD.sourcePath] as? String ?: "",
            description = link[HFLD.description] as? String ?: "",
            // Absent from an older node, which listed every document it sent.
            listed = link[HFLD.listed] as? Boolean ?: true,
        )
    }
    val menu = config.state[HFLD.menu].toJsonListOfMaps().map { entry ->
        MenuItem(
            id = entry[HFLD.id] as? String ?: "",
            label = entry[HFLD.label] as? String ?: "",
            // Read by the kernel's own parser, so the side that writes the union and the side that acts on
            // it cannot come to disagree about what a given shape meant.
            action = parseUiAction(entry[UIB.action]),
            parentId = entry[UIB.parentId] as? String,
        )
    }
    return HomeConfig(
        fragment = config.fragment,
        layout = HomeLayout(
            topBar = config.features[HFEAT.topBar] == true,
            leftBar = config.features[HFEAT.leftBar] == true,
            inlineLinks = config.features[HFEAT.inlineLinks] == true,
        ),
        links = links,
        menu = menu,
        user = UserProfile.fromUserInfo(config.state[HFLD.userInfo].toJsonMapOrEmpty()),
        users = userChoicesFrom(config.state[HFLD.users]),
        canManageUsers = config.features[HFEAT.canManageUsers] == true,
        canSeeAllClients = config.features[HFEAT.canSeeAllClients] == true,
        hasSurvey = config.features[HFEAT.hasSurvey] == true,
        sourceRepoBase = config.state[HFLD.sourceRepoBase] as? String,
        canOpenSandbox = config.features[HFEAT.canOpenSandbox] == true,
        administersClient = config.features[HFEAT.administersClient] == true,
        sandboxOf = (config.state[HFLD.sandboxOf] as? String)?.let {
            SandboxOf(it, config.state[HFLD.sandboxOfName] as? String ?: it, stranded = config.state[HFLD.sandboxStranded] == true)
        },
        sharedDemo = config.state[HFLD.sharedDemo] == true,
        selfServeClient = (config.state[HFLD.selfServeClient] as? String)?.ifBlank { null },
        selfServeClientAbsent = config.state[HFLD.selfServeClientAbsent] == true,
        joinOffered = config.state[HFLD.joinOffered] == true,
        webAssets = webAssetNamesFrom(config.state[HFLD.webAssets]),
        fileRefs = fileRefNamesFrom(config.state[HFLD.fileRefs]),
        images = componentImageNamesFrom(config.state[HFLD.images]),
        timeZone = (config.state[HFLD.timeZone] as? String)?.ifBlank { null },
        timeZoneLabel = (config.state[HFLD.timeZoneLabel] as? String)?.ifBlank { null },
    )
}

/**
 * The `UserChoice`s in a `users` list as the backend serves it (issue #749), read by the kernel's own
 * `UserChoice.fromInfo` so the two sides cannot disagree about the shape; an entry without an id is dropped.
 * Pure, covered by `jsNodeTest`.
 */
fun userChoicesFrom(raw: Any?): List<UserChoice> = raw.toJsonListOfMaps().mapNotNull { UserChoice.fromInfo(it) }

/**
 * The repo source path -> in-app link id map (issue #492): how a document's interior link to a repo file is
 * recognized as naming another document the app serves. Built from every home link that declares a source path.
 * Pure, covered by `jsNodeTest`.
 */
fun docKeyByPath(links: List<HomeLink>): Map<String, String> =
    links.filter { it.sourcePath.isNotEmpty() }.associate { it.sourcePath to it.id }

/**
 * How a document links to a page of the app (issue #1371): an absolute path under the default app root,
 * `/wa/test/simulations`, which is what the address bar shows. A document cannot know the root a deployment serves
 * the app under, so [docLinkResolver] puts the live one in its place.
 */
private const val docAppRoot = "/wa/"

/**
 * A link resolver for rendering the document at [currentSourcePath], to hand to [Markdown] (issue #492): its
 * interior relative links become in-app document links (`/wa/docs/<id>`, via [docHref] so the format cannot drift
 * from what [routeParams] reads) or links into the source repo, per the kernel's [resolveDocLink], and a link to a
 * page of the app ([docAppRoot]) is put under the app's live root. With a null [sourceRepoBase] a non-document
 * relative link resolves to empty, which the renderer draws as its text (issue #1380). Pure over its inputs;
 * covered by `jsNodeTest`.
 */
fun docLinkResolver(currentSourcePath: String, links: List<HomeLink>, sourceRepoBase: String?): (String) -> String {
    val byPath = docKeyByPath(links)
    return { raw ->
        val url = raw.trim()
        if (url.startsWith(docAppRoot)) {
            appRootPrefix() + url.substring(docAppRoot.length - 1)
        } else {
            resolveDocLink(raw, currentSourcePath, byPath, sourceRepoBase) { key, section -> docHref(key, section) }
        }
    }
}

/**
 * The home widget-group's backend calls, all keyed off the shared kernel constants so the frontend never
 * re-hardcodes a path or a JSON key the backend serves:
 *  - The **UI-config** (fetchConfig) -- the construction manifest: which fragment holds the copy, which
 *    layout affordances are on, and which documents to link to;
 *  - A linked **document** (fetchDoc) -- whole Markdown, rendered by [Markdown].
 *
 * The group's copy comes from the shared [fetchCopy]. Everything goes through [Http], which carries the
 * runtime's conventions (the roots, the app id, the error envelope) for every group alike.
 */
object HomeApi {
    /**
     * GET the home UI-config -- cheap and meant to be re-fetched on navigation.
     *
     * Read by the app bar and by most pages, each on every refresh generation, so it is shared (issue #1313): a call
     * joins a read still in flight for the same generation ([refreshGenerationNow]) rather than making its own, and
     * an answer the same as the last one ([answersSameAs]) returns the [HomeConfig] built from it, so a consumer
     * setting it into state redraws nothing. A finished read is never handed to a later call: one asking again --
     * for a fresh fragment ref after a stale one, say -- gets a new read.
     */
    suspend fun fetchConfig(): HomeConfig {
        val generation = refreshGenerationNow
        val read = inFlight?.takeIf { it.first == generation && it.second.isActive }?.second
            ?: homeApiScope.async { readConfig() }.also { inFlight = generation to it }
        return read.await()
    }

    private suspend fun readConfig(): HomeConfig {
        val raw = fetchUiConfig(HEP.homeUiConfig)
        lastAnswer?.let { (answer, config) -> if (raw.answersSameAs(answer)) return config }
        return homeConfigFrom(raw).also { lastAnswer = raw to it }
    }

    /** Shared reads, outliving any one caller so a caller's cancellation does not cancel another's read. */
    private val homeApiScope = MainScope()
    private var inFlight: Pair<Int, Deferred<HomeConfig>>? = null
    private var lastAnswer: Pair<UiConfig, HomeConfig>? = null

    /** GET a whole Markdown document, verbatim; the caller renders it. */
    suspend fun fetchDoc(docId: String, buildId: String): String = Http.getDoc(docId, buildId)
}
