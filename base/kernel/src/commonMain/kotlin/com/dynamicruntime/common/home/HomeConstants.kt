package com.dynamicruntime.common.home

import com.dynamicruntime.common.uiblock.UiActionDef

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
     * (e.g., may edit only users in their own client). It is expressed as a **capability of the caller**, not
     * as their role, precisely so that refinement changes what the backend computes here and nothing else: the
     * frontend already asks "may I?" rather than "am I an admin?"
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

    /** A link's stable id (used to address it in the frontend's URL). */
    const val id = "id"

    /** A link's display label. */
    const val label = "label"

    /** The Markdown document a link opens, fetched at `/<staticRoot>/<appId>/doc/<docId:buildId>`. */
    const val docId = "docId"

    /** The document's cache-busting content hash. */
    const val buildId = "buildId"

    /**
     * `state.links[].sourcePath`: the document's repo-relative source path (e.g. `"README.md"`). The frontend
     * resolves the document's interior relative links against it, and builds the repo->in-app-document map from
     * every link's pair of this and [id] (issue #492).
     */
    const val sourcePath = "sourcePath"

    /**
     * `state.sourceRepoBase`: the source repository's blob base (`.../blob/<branch>`), or absent when the
     * deployment did not configure one. Interior links to files that are *not* served as in-app documents are
     * rewritten under it; without it they are left as written (issue #492).
     */
    const val sourceRepoBase = "sourceRepoBase"
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
    /** The UiBlock the menu is registered as (issue #458); its items merge by [HFLD.id]. */
    const val block = "homeMenu"

    // Item ids.
    const val catalog = "catalog"
    const val users = "users"
    const val envReference = "envReference"
    /** Operator boot-checks page (issue #540). */
    const val bootChecks = "bootChecks"

    /** Operator group parent, its overview child, and its remaining pages (issue #540). */
    const val operator = "operator"
    const val operatorOverview = "operatorOverview"
    const val systemInfo = "systemInfo"
    const val dbTables = "dbTables"
    const val fragmentsCheck = "fragmentsCheck"
    const val cfactReference = "cfactReference"
    // No `newForm` item id: creating a form is reached from the "My forms" list, not a menu entry (issue #417).
    // The `pageNewForm` route below still exists -- the list's "New form" button navigates to it.
    const val forms = "forms"
    /** Account group parent (issue #540): identity/session items nest under it. */
    const val account = "account"
    const val profile = "profile"
    const val login = "login"
    const val register = "register"
    const val logout = "logout"
    // Debug (issue #517), offered only in an env-authed session. `debugEnable` (a top-level call) turns debug
    // on; once on, `debug` is a parent item whose children -- the debug pages and `debugOff` -- drill down
    // under it via [UIB.parentId]. `debugEnable` and `debug` are mutually exclusive by cfact.
    const val debugEnable = "debugEnable"
    const val debug = "debug"
    const val debugPages = "debugPages"
    const val debugOff = "debugOff"

    // Frontend page ids, carried in a menu item's `action` as a string; the frontend maps them onto its
    // own routing (issue #483).
    const val pageCatalog = "catalog"
    const val pageUsers = "users"
    const val pageEnv = "env"
    const val pageBootChecks = "bootChecks"
    const val pageSystemInfo = "systemInfo"
    const val pageDbTables = "dbTables"
    const val pageFragmentsCheck = "fragmentsCheck"
    /** The Operator index/landing page (issue #540), mirroring the Debug index. */
    const val pageOperator = "operator"
    const val pageCfacts = "cfacts"
    const val pageNewForm = "newForm"
    const val pageForms = "forms"
    const val pageProfile = "profile"
    const val pageLogin = "login"
    const val pageRegister = "register"
    const val pageDebug = "debug"
}

/** Frontend functions a UiBlock may call, declared so both sides share the vocabulary (issue #483). */
object HACT {
    /**
     * Signing out: a request plus a redirect, which is why it cannot be a link and has to be a call.
     *
     * Takes no parameters, and the count is declared so the backend can refuse a UiBlock that passes some --
     * see [UiActionDef].
     */
    val logout = UiActionDef("logout")

    /**
     * Turn this session's debug behaviors on or off (issue #517). One parameter, the boolean as a string
     * (`"true"`/`"false"`): the "Enable debug" item passes `true` and "Turn off debug" passes `false`. A call
     * rather than a route because it changes session state via the env-auth endpoint and re-reads the config,
     * exactly as [logout] does its own side effect.
     */
    val setEnvDebug = UiActionDef("setEnvDebug", arity = 1)

    /**
     * Signing out of an **environment** (issue #486): the edge's counterpart to [logout], contributed to the
     * menu by `EdgeComponent` as an overlay. A request that clears the env-auth cookie, then a navigation to
     * the environment's sign-in page -- a call for the same reason [logout] is, not a route.
     *
     * **Two parameters, both URLs, and that is deliberate.** The frontend is served *by* an edge but knows
     * nothing about being behind one -- not which endpoint clears the perimeter cookie, nor where an edge's
     * sign-in page lives. Both are the edge's to know, so the edge supplies them as the call's arguments and
     * the function stays pure mechanism: fetch the first, navigate to the second. The order is
     * `(logoutPath, landingUrl)`:
     *
     *  - **`logoutPath`** -- the api-relative path of the clear-cookie endpoint (`EAEP.logout`), so the
     *    frontend's own api root is prepended exactly as for every other call, honoring a custom root.
     *  - **`landingUrl`** -- the absolute path to send the browser to afterward: the edge's landing page
     *    (issue #493), the anonymous surface a signed-out caller lands back on.
     *
     * Declared here beside [logout] because this object *is* the shared vocabulary of callable functions, not
     * a home-page-specific list; the edge is where it is contributed and implemented, but neither side of the
     * boundary can check the other, so the name has to live in the kernel. See `UiActions`.
     */
    val envLogout = UiActionDef("envLogout", arity = 2)

    /**
     * Navigate the whole window to a **same-origin path** (issue #493): a full page load out of this
     * single-page app, as opposed to a `UiRoute`, which routes *within* it by hash.
     *
     * It exists because some destinations are server paths rather than in-app pages -- an edge's sign-in
     * page, the application reached through an edge -- and a `UiRoute` cannot express them: it renders as
     * `#page=`, which the in-app router interprets. The one parameter is the path, supplied by whoever
     * contributes the item (the edge knows its own roots); the implementation **guards** it to a same-origin
     * path, so a client-contributed overlay cannot turn a menu item into an off-site redirect. That guard is
     * why this is one named function rather than a bare "navigate anywhere": the operation is fixed and
     * reviewed, only the destination is data -- the same shape as `UiRoute` varying its page id.
     *
     * A frontend that renders one of these should mark it as leaving the app (issue #493) -- the ↗ affordance
     * is derived from *this action kind*, not from a field on the item, since leaving the app is inherent to
     * what `openPath` does.
     */
    val openPath = UiActionDef("openPath", arity = 1)
}

/** Markdown fragment file ids for the home widget-group (each also the group's fragment namespace). */
@Suppress("ConstPropertyName")
object HFRAG {
    const val home = "home"
}

/**
 * Markdown *document* ids the home page links to (served whole, rendered as a page). The README is the entry
 * point; the rest are the repo docs it links to, registered so those interior links resolve to an in-app
 * document rather than the source repository (issue #492). Each id is also the `md-docs/<id>.md` resource name.
 */
@Suppress("ConstPropertyName")
object HDOC {
    const val readme = "readme"
    const val codeGuide = "code-guide"
    const val clientDefinition = "client-definition"
    const val deferredWork = "deferred-work"
    const val gedraConfigAndData = "gedra-config-and-data"
    const val gedraEntry = "gedra-entry"
    const val gedraPatch = "gedra-patch"
    const val uiBlock = "ui-block"
}
