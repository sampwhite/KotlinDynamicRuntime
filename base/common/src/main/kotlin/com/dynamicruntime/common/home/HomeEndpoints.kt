package com.dynamicruntime.common.home

import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.fragmentRefs
import com.dynamicruntime.common.content.uiFragmentsProperty
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.common.uiblock.UiCall
import com.dynamicruntime.common.uiblock.UiRoute
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.uiBlock
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.ETAG
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
        property(HFLD.sourcePath, "The document's repo-relative source path, used to resolve its interior links.", required = true)
    }

    // One app-bar menu item: an id the frontend can recognize, a label to show, and either a page to navigate
    // to or a client-side action to run.
    type(HTYPE.menuItem) {
        type = SCT.kObject
        property(HFLD.id, "Stable id for this item (see HMENU); the frontend keys behavior off it.", required = true)
        property(HFLD.label, "Display label.", required = true)
        property(UIB.parentId, "The parent item this drills down under (issue #517); absent for a top-level item.")
        property(
            UIB.action,
            "What the item does when chosen: a **string** is a frontend page id to navigate to, an **array** " +
                "is a call whose first element names a registered frontend function and whose rest are its " +
                "parameters (issue #483). Absent on a drill-down **parent** (issue #517), which is drawn as a " +
                "group header rather than an action -- the boot check refuses a parent that also names one.",
        ) {
            // Unconstrained, because the layer cannot yet say "string or array": `SchParser` does not model
            // `anyOf`. Declaring either type alone would be a schema that rejects half the values this
            // endpoint really sends, so it says "any value" and the description carries the shape in prose.
            anyValue()
        }
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
            property(
                HFLD.sourceRepoBase,
                "The source repository's blob base (.../blob/<branch>) for rewriting a document's interior " +
                    "links; absent when the deployment configured no source repo.",
            )
        }
    }

    generalEndpoint(HEP.homeUiConfig, "Returns the config for constructing the shell (layout, links, menu).",
        HttpMethod.GET, outputRef = HTYPE.homeUiConfig, tags = setOf(ETAG.frontend)) { c, _ ->
        // The menu says what this caller may reach, so it must be decided on live roles rather than the ones
        // their session cookie captured at login -- otherwise a revoked administrator keeps being offered a
        // page that now 401s, for as long as the cookie lives. The `admin` section is refreshed by the
        // dispatcher for the same reason; this is the one config that needs it outside a gated section.
        refreshActingRoles(c)
        mapOf(
            UIC.fragments to fragmentRefs(c, HFRAG.home),
            // Default to a left nav bar alone: the classic shape, and the one that stays usable as the
            // document list grows. A deployment turns on the others (or off) via HCFG.
            UIC.features to mapOf(
                HFEAT.topBar to c.layoutFlag(HCFG.homeTopBar, default = false),
                HFEAT.leftBar to c.layoutFlag(HCFG.homeLeftBar, default = true),
                HFEAT.inlineLinks to c.layoutFlag(HCFG.homeInlineLinks, default = false),
                HFEAT.canManageUsers to AdminRules.canManageUsers(c),
            ),
            UIC.state to buildMap {
                put(HFLD.links, homeLinksFor(c))
                put(HFLD.menu, resolvedMenu(c))
                put(HFLD.userInfo, c.userProfile.toUserInfo())
                // Only when configured -- an absent field says "no source repo", which is how the frontend
                // leaves a non-document interior link as written (issue #492).
                sourceRepoBase(c)?.let { put(HFLD.sourceRepoBase, it) }
            },
        )
    }
}

/**
 * The app-bar menu, as data (issue #458).
 *
 * The menu is decided here rather than in the frontend so that what a user may reach is settled in one place,
 * by the side that knows -- the shell renders whatever list it is handed. What changed in #458 is that the
 * *conditions* are data too: each item carries a cfact expression instead of sitting inside an `if`, which is
 * what lets a client or a boot role vary the menu without another branch being added here. An item whose
 * expression does not match is absent from the response, exactly as before.
 *
 * **Most items are application-only**, and that is a correctness fix rather than tidying (issue #446). The
 * account, forms and profile surfaces are contributed `appOnly` (#432), so an edge that offered them was
 * offering pages whose endpoints are not there: an anonymous edge caller was shown "Log in" and "Register" --
 * the account-creation surface #432 existed to remove -- and an env-authed one, who holds `admin`, was shown
 * Users, My forms, Profile and Log out. Six items, all of which 404 on the node serving them.
 *
 * They say `,app` rather than an edge overlay setting them to `#never`, which is the whole reason the boot
 * role is a cfact: an edge does not *remove* the application's items, it fails to match them, so one list
 * stays readable as everything that exists.
 *
 * `envReference` is deliberately **not** marked, and the difference is worth seeing. Its condition is already
 * the real one -- can this caller reach the operator section -- which is false on an edge today because
 * env auth grants `admin` without `allClients`. Adding `,app` would suppress it for a *second* reason that is
 * not the true one, and would then wrongly hide it if an edge ever did have deployment operators.
 *
 * The three original conditions are worth reading against what they replaced:
 *
 * - **Users** was `AdminRules.canManageUsers`, which is `admin` held -- the same test [CFACTS.hasAdminLevel] makes.
 * - **Environment** was `RequestService.canAccess(...)` on the env-reference path, asked of the dispatcher so
 *   the menu and the gate could not drift (#211). [CFACTS.isDeploymentOperator] asks the dispatcher too, about
 *   the `operator` section rather than one endpoint in it, so the invariant survives the move.
 * - **Signed in / out** was an `if/else` on `isLoggedIn`, and is now the pair [CFACTS.loggedIn] and
 *   [CFACTS.anonymous] -- the positive form on both sides rather than a negation.
 *
 * Labels stay literal, as `homeDocs` link labels do. Their natural home is the `home` fragment file, and
 * moving them is deliberately *not* part of this change: the acceptance criterion is a response that does not
 * change, and moving copy in the same step would spend the one check that makes the move verifiable.
 */
fun homeMenuBlock(): UiBlockSource = uiBlock(
    HMENU.block,
    origin = "HomeEndpoints",
    // Items are identified by their id: what an overlay names to mean *this* item, and so the one thing that
    // must survive a change of label, route or condition.
    arrayKeys = mapOf(HFLD.menu to HFLD.id),
) {
    items(HFLD.menu) {
        menuItem(HMENU.catalog, "Endpoint catalog", UiRoute(HMENU.pageCatalog))
        menuItem(HMENU.users, "Users", UiRoute(HMENU.pageUsers), cfactExpression = "${CFACTS.hasAdminLevel},${BOOT.app}")
        menuItem(
            HMENU.envReference, "Environment", UiRoute(HMENU.pageEnv),
            cfactExpression = CFACTS.isDeploymentOperator,
        )
        // Operator diagnostics surfaced via the schema-driven renderer (issue #540); same deployment-operator
        // gate as Environment. Grouping these under an "Operator" sub-menu is Part B of #540.
        menuItem(
            HMENU.bootChecks, "Boot checks", UiRoute(HMENU.pageBootChecks),
            cfactExpression = CFACTS.isDeploymentOperator,
        )
        // Offered to a client-scoped operator or admin (issue #488) -- the same caller the `clientOperator`
        // section admits, asked as a cfact so the menu offer and the gate cannot drift.
        menuItem(
            HMENU.cfactReference, "Client facts", UiRoute(HMENU.pageCfacts),
            cfactExpression = "${CFACTS.isClientOperator},${BOOT.app}",
        )
        // Forms are login-gated only (the `gedra` section), so every signed-in caller is offered the list; how
        // far it reaches is a scope question the endpoints answer, not a menu one (issue #408). Only "My forms"
        // is an entry: the list is the hub for the whole lifecycle, so creating a form is reached by its
        // "New form" button rather than a second, redundant nav item (issue #417).
        menuItem(HMENU.forms, "My forms", UiRoute(HMENU.pageForms), cfactExpression = "${CFACTS.loggedIn},${BOOT.app}")
        menuItem(HMENU.profile, "Profile", UiRoute(HMENU.pageProfile), cfactExpression = "${CFACTS.loggedIn},${BOOT.app}")
        menuItem(HMENU.logout, "Log out", UiCall(HACT.logout), cfactExpression = "${CFACTS.loggedIn},${BOOT.app}")
        menuItem(HMENU.login, "Log in", UiRoute(HMENU.pageLogin), cfactExpression = "${CFACTS.anonymous},${BOOT.app}")
        menuItem(HMENU.register, "Register", UiRoute(HMENU.pageRegister), cfactExpression = "${CFACTS.anonymous},${BOOT.app}")

        // Debug (issue #517), offered only in an env-authed session. Not in debug yet: one top-level call to
        // turn it on. In debug: a "Debug" parent whose children drill down under it via parentId -- the debug
        // pages (which list the usable tools), and the switch back off. The two "Debug" entries are mutually
        // exclusive by cfact, so only ever one shows.
        menuItem(HMENU.debugEnable, "Debug", UiCall(HACT.setEnvDebug, listOf("true")),
            cfactExpression = "${CFACTS.canEnableDebug},${BOOT.app}")
        menuItem(HMENU.debug, "Debug", cfactExpression = "${CFACTS.isEnvDebug},${BOOT.app}")
        menuItem(HMENU.debugPages, "Debug pages", UiRoute(HMENU.pageDebug),
            cfactExpression = "${CFACTS.isEnvDebug},${BOOT.app}", parentId = HMENU.debug)
        menuItem(HMENU.debugOff, "Turn off debug", UiCall(HACT.setEnvDebug, listOf("false")),
            cfactExpression = "${CFACTS.isEnvDebug},${BOOT.app}", parentId = HMENU.debug)
    }
}

/** [HMENU.block] resolved for this caller: the items their cfacts satisfy, in display order. */
private fun resolvedMenu(cxt: KdrCxt): List<Any?> =
    (UiBlockService.get(cxt).resolve(cxt, HMENU.block)?.get(HFLD.menu) as? List<*>) ?: emptyList()

/** A layout toggle from the deployment's instance config, or [default] when it is not configured. */
private fun KdrCxt.layoutFlag(key: String, default: Boolean): Boolean =
    (instanceConfig.get(key) as? Boolean) ?: default

/**
 * The documents the home page links to, in display order. Each is paired with its current build id, so the
 * frontend fetches an immutably cacheable URL. A document whose resource is absent (its owning module is not
 * in the deployment) is simply left out, rather than offered as a link that would 404.
 */
private fun homeLinks(): List<Map<String, Any?>> = homeDocs.mapNotNull { doc ->
    val buildId = MarkdownDocService.docBuildId(doc.docId) ?: return@mapNotNull null
    mapOf(
        HFLD.id to doc.id,
        HFLD.label to doc.label,
        HFLD.docId to doc.docId,
        HFLD.buildId to buildId,
        HFLD.sourcePath to doc.sourcePath,
    )
}

/**
 * The Documents list for this caller: empty on an edge's anonymous landing, [homeLinks] everywhere else
 * (issue #493). An anonymous visitor to an edge lands on a marketing page, not a document index.
 *
 * **A quick gate, deliberately not the principled form.** The list is computed in code and returned whole
 * under `state.links`, so a cfact cannot reach into it the way it filters a UiBlock's items. The natural
 * direction is *links become data* -- a UiBlock the edge overlays away exactly as it does the menu, the move
 * #458 made for the menu -- at which point this evaluate-and-null-it disappears. Until then, it is decided by
 * the *same cfact vocabulary the menu uses* (`anonymous` and the boot role), evaluated through the registry,
 * rather than a raw boot-role branch -- so the shortcut is in the shape (links are still not data), not in
 * reaching past the vocabulary.
 */
private fun homeLinksFor(cxt: KdrCxt): List<Map<String, Any?>> {
    val registry = SchemaService.get(cxt).cfactsFor(null)
    val onAnonymousEdgeLanding = registry.parse("${CFACTS.anonymous},${BOOT.edge}").matches(registry.assemble(cxt))
    return if (onAnonymousEdgeLanding) emptyList() else homeLinks()
}

/** One row of the home page's document registry: how it is addressed and labelled, and where it came from. */
private class HomeDocDef(val id: String, val label: String, val docId: String, val sourcePath: String)

/**
 * The home page's document registry (issue #492): the README plus the repo docs it links to. Registering a
 * target here does two things at once -- it is offered in the Documents list, and its repo source path lets the
 * frontend rewrite a link *to* it (from any document) into an in-app link rather than a link into the source
 * repository. A doc whose resource is not in the deployment is simply dropped by [homeLinks]. Grows as more
 * documents are published.
 */
private val homeDocs: List<HomeDocDef> = listOf(
    HomeDocDef(HDOC.readme, "Read me", HDOC.readme, "README.md"),
    HomeDocDef(HDOC.codeGuide, "Code guide", HDOC.codeGuide, "code-guide.md"),
    HomeDocDef(HDOC.clientDefinition, "Client definition", HDOC.clientDefinition, "client-definition.md"),
    HomeDocDef(HDOC.deferredWork, "Deferred work", HDOC.deferredWork, "deferred-work.md"),
    HomeDocDef(HDOC.gedraConfigAndData, "Gedra config and data", HDOC.gedraConfigAndData, "gedra-config-and-data.md"),
    HomeDocDef(HDOC.gedraEntry, "Gedra entry", HDOC.gedraEntry, "gedra-entry.md"),
    HomeDocDef(HDOC.gedraPatch, "Gedra patch", HDOC.gedraPatch, "gedra-patch.md"),
    HomeDocDef(HDOC.uiBlock, "UI block", HDOC.uiBlock, "ui-block.md"),
)

/**
 * The source repository's blob base for this deployment (`.../blob/<branch>`), or null when
 * [HDOCENV.sourceRepoUrl] is unset. A document's interior link to a repo file that is not itself served in-app
 * is rewritten under this; unset, such a link is left as written (issue #492).
 */
private fun sourceRepoBase(cxt: KdrCxt): String? {
    val repoUrl = cxt.getEnvVar(HDOCENV.sourceRepoUrl)?.trim()?.ifEmpty { null } ?: return null
    val branch = cxt.getEnvVar(HDOCENV.sourceRepoBranch)?.trim()?.ifEmpty { null } ?: HDOCENV.defaultBranch
    return "${repoUrl.trimEnd('/')}/blob/$branch"
}

/** Environment variables for linking a served document back to its source repository (issue #492). */
@Suppress("ConstPropertyName")
object HDOCENV {
    /** The branch a source-repo link points at when [sourceRepoBranch] is unset. */
    const val defaultBranch = "main"

    val sourceRepoUrl = EnvVarDef(
        "KDR_SOURCE_REPO_URL", group = ENVGRP.content, defaultDoc = "unset",
        description = "The web base URL of the deployment's source repository, e.g. " +
            "'https://github.com/owner/repo'. When set, a served Markdown document's interior links to repo " +
            "files that are not themselves served in-app are rewritten to '<this>/blob/<branch>/<path>' " +
            "(issue #492). Unset (the default) and such links are left exactly as written -- the documents " +
            "still render, but their repo-relative interior links do not resolve from inside the app.",
    )

    val sourceRepoBranch = EnvVarDef(
        "KDR_SOURCE_REPO_BRANCH", group = ENVGRP.content, defaultDoc = "'main'",
        description = "The branch that source-repository document links point at (see KDR_SOURCE_REPO_URL). " +
            "Defaults to 'main'. Has no effect unless KDR_SOURCE_REPO_URL is set.",
    )
}

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
