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
}

/** Home UI-config response field (JSON key) names, under the shared `state` envelope entry. */
@Suppress("ConstPropertyName")
object HFLD {
    /** `state.links`: the navigable documents, in display order. */
    const val links = "links"

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
