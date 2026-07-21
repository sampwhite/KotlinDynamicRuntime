package com.dynamicruntime.common.home

// Home/shell constants that the *frontend* (Kotlin/JS) shares with the backend: the UI-config endpoint path,
// the layout feature flags, the response field names, the schema type name, and the fragment/document ids.
// They live in the KMP kernel (not base:common) so the transpiled frontend references the same strings the
// backend serves, instead of re-hardcoding them -- the same arrangement as the auth constants (issue #70).

/** Home/shell endpoint paths (as the frontend calls them, before the API context root is prepended). */
@Suppress("ConstPropertyName")
object HEP {
    const val homeUiConfig = "/home/ui/config"
}

/**
 * Layout feature flags for the home/shell widget-group: the three ways the home page can present its document
 * links ([HFLD.links]). Independent toggles, not a single mode -- a deployment may enable any combination, or
 * none (leaving just the page copy). They are about the *links*; the application's own top app bar (brand and
 * account menu) is separate chrome and is not governed by these.
 */
@Suppress("ConstPropertyName")
object HFEAT {
    /** Whether the links appear as a horizontal menu bar above the content. */
    const val topBar = "topBar"

    /** Whether the links appear in a left nav bar beside the content. */
    const val leftBar = "leftBar"

    /** Whether the links are listed inline in the page body. */
    const val inlineLinks = "inlineLinks"

    /**
     * Whether this caller may administer other users -- today "has the admin role", later something narrower
     * (e.g. may edit only users in their own account). It is expressed as a **capability of the caller**, not
     * as their role, precisely so that refinement changes what the backend computes here and nothing else: the
     * frontend already asks "may I?" rather than "am I an admin?".
     */
    const val canManageUsers = "canManageUsers"
}

/** Home UI-config response field (JSON key) names, under the shared `state` envelope entry. */
@Suppress("ConstPropertyName")
object HFLD {
    /** `state.links`: the navigable documents, in display order. */
    const val links = "links"

    /**
     * `state.menu`: the app-bar menu items for **this caller**, in display order. The backend decides what a
     * given user may see (see [HMENU]); the frontend renders the list it is handed and adds nothing of its own.
     */
    const val menu = "menu"

    /** `state.userInfo`: who the caller is (the anonymous profile when signed out). */
    const val userInfo = "userInfo"

    /** A menu item's navigation target: a frontend page id, e.g. [HMENU.pageProfile]. Absent for an action. */
    const val page = "page"

    /** A menu item's client-side action ([HACT]) instead of a navigation, e.g. logging out. */
    const val action = "action"

    /** A link's stable id (used to address it in the frontend's URL). */
    const val id = "id"

    /** A link's display label. */
    const val label = "label"

    /** The Markdown document a link opens, fetched at `/<staticRoot>/<appId>/doc/<docId:buildId>`. */
    const val docId = "docId"

    /** The document's cache-busting content hash. */
    const val buildId = "buildId"
}

/** Home schema type names (the backend's output type refs; also useful to the frontend). */
@Suppress("ConstPropertyName")
object HTYPE {
    const val homeUiConfig = "HomeUiConfig"

    /** One navigable document in `state.links`. */
    const val homeLink = "HomeLink"

    /** One app-bar menu item in `state.menu`. */
    const val menuItem = "MenuItem"
}

/**
 * Menu item ids and the frontend page ids they open.
 *
 * The **id** is the contract: it identifies an item across backend and frontend regardless of its label, so
 * the frontend can style or place one specially without matching on display text. Which items a given caller
 * receives is decided entirely by the backend -- an item absent from `state.menu` is one this user may not
 * have, so the frontend never re-derives visibility from roles it happens to know.
 */
@Suppress("ConstPropertyName")
object HMENU {
    // Item ids.
    const val catalog = "catalog"
    const val users = "users"
    const val profile = "profile"
    const val login = "login"
    const val register = "register"
    const val logout = "logout"

    // Frontend page ids ([HFLD.page]); the frontend maps these onto its own routing.
    const val pageCatalog = "catalog"
    const val pageUsers = "users"
    const val pageProfile = "profile"
    const val pageLogin = "login"
    const val pageRegister = "register"
}

/** Client-side actions a menu item can carry ([HFLD.action]) instead of navigating. */
@Suppress("ConstPropertyName")
object HACT {
    const val logout = "logout"
}

/** Markdown fragment file ids for the home widget-group (each also the group's fragment namespace). */
@Suppress("ConstPropertyName")
object HFRAG {
    const val home = "home"
}

/** Markdown *document* ids the home page links to (served whole, rendered as a page). */
@Suppress("ConstPropertyName")
object HDOC {
    const val readme = "readme"
}
