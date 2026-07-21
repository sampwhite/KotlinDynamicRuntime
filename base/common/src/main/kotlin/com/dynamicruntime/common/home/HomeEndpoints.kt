package com.dynamicruntime.common.home

import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.fragmentRefs
import com.dynamicruntime.common.content.uiFragmentsProperty
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.refreshActingRoles

/**
 * The home/shell widget-group's endpoints (issue #70's `nav`/`shell` hub). Follows the per-group UI-config
 * model: one cheap "construction manifest" endpoint returning the shared `{ fragments, features, state }`
 * envelope, so the frontend learns how to build the home page rather than hardcoding it.
 *
 * What makes the home page *flexible* is that both halves are data:
 *  - `features` -- the layout affordances ([HFEAT]), read from the deployment's instance config ([HCFG]), so a
 *    deployment chooses a top bar, a left bar, inline links, any combination, or none. They are independent
 *    toggles, not one mode.
 *  - `state.links` -- the navigable Markdown documents, each already carrying its `docId:buildId` so the
 *    frontend can fetch it from the cache-immutable document server ([MarkdownDocService]). The home page's own
 *    copy comes from the `home` fragment file.
 *
 * Anonymous (no `userSections` gate): the home page is the shell a logged-out visitor lands on.
 * Registered by the `common` component.
 */
fun homeSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "home") {
    // One navigable document: a display label plus the versioned id the frontend fetches it by.
    type(HTYPE.homeLink) {
        type = SCT.kObject
        property(HFLD.id, "Stable id for this link (the frontend addresses it in its own URL).", required = true)
        property(HFLD.label, "Display label for the link.", required = true)
        property(HFLD.docId, "Markdown document id, fetched at /<staticRoot>/<appId>/doc/<docId:buildId>.", required = true)
        property(HFLD.buildId, "Cache-busting content hash for the document.", required = true)
    }

    // One app-bar menu item: an id the frontend can recognize, a label to show, and either a page to navigate
    // to or a client-side action to run.
    type(HTYPE.menuItem) {
        type = SCT.kObject
        property(HFLD.id, "Stable id for this item (see HMENU); the frontend keys behavior off it.", required = true)
        property(HFLD.label, "Display label.", required = true)
        property(HFLD.page, "Frontend page id to navigate to; absent when the item carries an action.")
        property(HFLD.action, "Client-side action to run instead of navigating (see HACT).")
    }

    // UserInfo (declared with UserProfile) describes who the caller is, for the menu's signed-in label.
    UserProfile.defineInfoType(this)

    // The home widget-group's UI config: which fragment file holds its copy, which layout affordances are
    // enabled, the links to offer, and the menu this particular caller gets.
    type(HTYPE.homeUiConfig) {
        type = SCT.kObject
        uiFragmentsProperty()
        property(UIC.features, "Which home layout affordances are enabled (independent toggles).", required = true) {
            type = SCT.kObject
            property(HFEAT.topBar, "Whether the shell shows a top menu bar.", required = true) { type = SCT.boolean }
            property(HFEAT.leftBar, "Whether the shell shows a left nav bar listing the links.", required = true) {
                type = SCT.boolean
            }
            property(HFEAT.inlineLinks, "Whether the links are also listed inline on the page body.", required = true) {
                type = SCT.boolean
            }
            property(HFEAT.canManageUsers, "Whether the caller may create and edit other users.", required = true) {
                type = SCT.boolean
            }
        }
        property(UIC.state, "Dynamic state for constructing the home page.", required = true) {
            type = SCT.kObject
            property(HFLD.links, "The navigable documents, in display order.", required = true) {
                type = SCT.array
                items { ref(HTYPE.homeLink) }
            }
            property(HFLD.menu, "The app-bar menu items for this caller, in display order.", required = true) {
                type = SCT.array
                items { ref(HTYPE.menuItem) }
            }
            property(HFLD.userInfo, "Who the caller is (the anonymous profile when signed out).", required = true) {
                ref(UserProfile.infoTypeName)
            }
        }
    }

    generalEndpoint(HEP.homeUiConfig, "Returns the config for constructing the shell (layout, links, menu).",
        HttpMethod.GET, outputRef = HTYPE.homeUiConfig) { c, _ ->
        // The menu says what this caller may reach, so it must be decided on live roles rather than the ones
        // their session cookie captured at login -- otherwise a revoked administrator keeps being offered a
        // page that now 401s, for as long as the cookie lives. The `admin` section is refreshed by the
        // dispatcher for the same reason; this is the one config that needs it outside a gated section.
        refreshActingRoles(c)
        mapOf(
            UIC.fragments to fragmentRefs(HFRAG.home),
            // Default to a left nav bar alone: the classic shape, and the one that stays usable as the
            // document list grows. A deployment turns on the others (or off) via HCFG.
            UIC.features to mapOf(
                HFEAT.topBar to c.layoutFlag(HCFG.homeTopBar, default = false),
                HFEAT.leftBar to c.layoutFlag(HCFG.homeLeftBar, default = true),
                HFEAT.inlineLinks to c.layoutFlag(HCFG.homeInlineLinks, default = false),
                HFEAT.canManageUsers to AdminRules.canManageUsers(c),
            ),
            UIC.state to mapOf(
                HFLD.links to homeLinks(),
                HFLD.menu to menuItems(c),
                HFLD.userInfo to c.userProfile.toUserInfo(),
            ),
        )
    }
}

/**
 * The app-bar menu for *this* caller, in display order.
 *
 * The menu is built here rather than in the frontend so that what a user may reach is decided in one place, by
 * the side that actually knows -- the shell then renders whatever list it is handed. That matters most for the
 * user-administration entry: it appears only for a caller with the [AdminRules.canManageUsers] capability, and
 * when that capability grows narrower (per-account administration, say) the menu narrows with it and no
 * frontend changes. It also means a signed-out visitor is never sent the labels of pages they cannot open.
 *
 * Labels are literal here, as the [homeDocs] link labels already are. Their natural home is the `home`
 * fragment file once this menu needs translating.
 */
private fun menuItems(cxt: KdrCxt): List<Map<String, Any?>> = buildList {
    add(item(HMENU.catalog, "Endpoint catalog", page = HMENU.pageCatalog))
    if (AdminRules.canManageUsers(cxt)) {
        add(item(HMENU.users, "Users", page = HMENU.pageUsers))
    }
    if (cxt.userProfile.isLoggedIn) {
        add(item(HMENU.profile, "Profile", page = HMENU.pageProfile))
        add(item(HMENU.logout, "Log out", action = HACT.logout))
    } else {
        add(item(HMENU.login, "Log in", page = HMENU.pageLogin))
        add(item(HMENU.register, "Register", page = HMENU.pageRegister))
    }
}

/** One [HTYPE.menuItem]: a navigation ([page]) or an action, never both. */
private fun item(id: String, label: String, page: String? = null, action: String? = null): Map<String, Any?> =
    buildMap {
        put(HFLD.id, id)
        put(HFLD.label, label)
        if (page != null) put(HFLD.page, page)
        if (action != null) put(HFLD.action, action)
    }

/** A layout toggle from the deployment's instance config, or [default] when it is not configured. */
private fun KdrCxt.layoutFlag(key: String, default: Boolean): Boolean =
    (instanceConfig.get(key) as? Boolean) ?: default

/**
 * The documents the home page links to, in display order. Each is paired with its current build id, so the
 * frontend fetches an immutably cacheable URL. A document whose resource is absent (its owning module is not
 * in the deployment) is simply left out, rather than offered as a link that would 404.
 */
private fun homeLinks(): List<Map<String, Any?>> = homeDocs.mapNotNull { (id, label, docId) ->
    val buildId = MarkdownDocService.docBuildId(docId) ?: return@mapNotNull null
    mapOf(HFLD.id to id, HFLD.label to label, HFLD.docId to docId, HFLD.buildId to buildId)
}

/** The home page's link table: `(id, label, docId)`. Grows as more documents are published. */
private val homeDocs: List<Triple<String, String, String>> = listOf(
    Triple(HDOC.readme, "Read me", HDOC.readme),
)

/**
 * Instance-config keys for the home layout (backend-only -- the frontend receives the resolved [HFEAT] flags,
 * never these). Defaults live at the read sites in [homeSchema].
 */
@Suppress("ConstPropertyName")
object HCFG {
    const val homeTopBar = "homeTopBar"
    const val homeLeftBar = "homeLeftBar"
    const val homeInlineLinks = "homeInlineLinks"
}
