package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/** One navigable Markdown document offered by the home page, already carrying its cache-busting build id. */
class HomeLink(val id: String, val label: String, val docId: String, val buildId: String)

/** Which of the three link presentations this deployment enabled. Independent toggles: any combination. */
class HomeLayout(val topBar: Boolean, val leftBar: Boolean, val inlineLinks: Boolean)

/**
 * One app-bar menu item, exactly as the backend composed it for this caller: an [id] to key behavior off, a
 * [label] to show, and either a [page] to navigate to or an [action] to run. The frontend renders the list it
 * is given -- an item the caller may not have simply is not in it.
 */
class MenuItem(val id: String, val label: String, val page: String?, val action: String?)

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
)

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
    suspend fun fetchConfig(): HomeConfig {
        val config = fetchUiConfig(HEP.homeUiConfig)
        val links = config.state[HFLD.links].toJsonListOfMaps().map { link ->
            HomeLink(
                id = link[HFLD.id] as? String ?: "",
                label = link[HFLD.label] as? String ?: "",
                docId = link[HFLD.docId] as? String ?: "",
                buildId = link[HFLD.buildId] as? String ?: "",
            )
        }
        val menu = config.state[HFLD.menu].toJsonListOfMaps().map { entry ->
            MenuItem(
                id = entry[HFLD.id] as? String ?: "",
                label = entry[HFLD.label] as? String ?: "",
                page = entry[HFLD.page] as? String,
                action = entry[HFLD.action] as? String,
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
        )
    }

    /** GET a whole Markdown document, verbatim; the caller renders it. */
    suspend fun fetchDoc(docId: String, buildId: String): String = Http.getDoc(docId, buildId)
}
