package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiAction
import com.dynamicruntime.common.uiblock.parseUiAction
import com.dynamicruntime.common.util.resolveDocLink
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * One navigable Markdown document offered by the home page, already carrying its cache-busting build id.
 * [sourcePath] is its repo-relative source, used to resolve the document's interior links (issue #492).
 */
class HomeLink(val id: String, val label: String, val docId: String, val buildId: String, val sourcePath: String)

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
    /** Whether the caller may create and edit other users (drives the Users page, not just the menu). */
    val canManageUsers: Boolean,
    /**
     * The source repository's blob base (`.../blob/<branch>`) for rewriting a document's interior links, or
     * null when the deployment configured none -- then a non-document interior link is left as written (#492).
     */
    val sourceRepoBase: String?,
)

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
        canManageUsers = config.features[HFEAT.canManageUsers] == true,
        sourceRepoBase = config.state[HFLD.sourceRepoBase] as? String,
    )
}

/**
 * The repo source path -> in-app link id map (issue #492): how a document's interior link to a repo file is
 * recognized as naming another document the app serves. Built from every home link that declares a source path.
 * Pure, covered by `jsNodeTest`.
 */
fun docKeyByPath(links: List<HomeLink>): Map<String, String> =
    links.filter { it.sourcePath.isNotEmpty() }.associate { it.sourcePath to it.id }

/**
 * A link resolver for rendering the document at [currentSourcePath], to hand to [Markdown] (issue #492): its
 * interior relative links become in-app document links (`#page=docs&doc=<id>`, via [docHref] so the format cannot drift
 * from what [hashParams] reads) or links into the source repo, per the kernel's [resolveDocLink]. A null
 * [sourceRepoBase] leaves non-document relative links as written. Pure over its inputs; covered by `jsNodeTest`.
 */
fun docLinkResolver(currentSourcePath: String, links: List<HomeLink>, sourceRepoBase: String?): (String) -> String {
    val byPath = docKeyByPath(links)
    return { raw -> resolveDocLink(raw, currentSourcePath, byPath, sourceRepoBase) { key -> docHref(key) } }
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
    /** GET the home UI-config -- cheap and meant to be re-fetched on navigation. */
    suspend fun fetchConfig(): HomeConfig = homeConfigFrom(fetchUiConfig(HEP.homeUiConfig))

    /** GET a whole Markdown document, verbatim; the caller renders it. */
    suspend fun fetchDoc(docId: String, buildId: String): String = Http.getDoc(docId, buildId)
}
