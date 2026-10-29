package com.dynamicruntime.common.home

import com.dynamicruntime.common.user.SandboxAccess
import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.gedra.clientConfigurationMarkdown
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientJoin
import com.dynamicruntime.common.content.MDOC
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.content.lastDocOrder
import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.fragmentRefs
import com.dynamicruntime.common.content.uiFragmentsProperty
import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.gedra.workflow.WorkflowService
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
import com.dynamicruntime.common.schema.schemaDialectMarkdown
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.refreshActingRoles
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.user.IDD
import com.dynamicruntime.common.util.genericZoneLabel
import com.dynamicruntime.common.util.toOptStr

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
fun homeSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.home") {
    // One navigable document: a display label plus the versioned id the frontend fetches it by.
    type(HTYPE.homeLink) {
        type = SCT.kObject
        property(HFLD.id, "Stable id for this link (the frontend addresses it in its own URL).", required = true)
        property(HFLD.label, "Display label for the link.", required = true)
        property(HFLD.docId, "Markdown document id, fetched at /<staticRoot>/<appId>/doc/<docId:buildId>.", required = true)
        property(HFLD.buildId, "Cache-busting content hash for the document.", required = true)
        property(HFLD.sourcePath, "The document's repo-relative source path, used to resolve its interior links.", required = true)
        property(HFLD.description, "One line on what the document covers, shown beside its link (issue #554).", required = true)
        property(
            HFLD.listed,
            "Whether the Documents list offers it (issue #1136); an unlisted document is a page of a suite, reached by links.",
            required = true,
        ) { type = SCT.boolean }
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
    UserChoice.defineInfoType(this)

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
            property(HFEAT.canSeeAllClients, "Whether the caller administers across clients (holds allClients).", required = true) {
                type = SCT.boolean
            }
            property(
                HFEAT.administersClient,
                "Whether the caller administers a client -- their own or every one -- and so lists more than their own rows (issue #1091).",
                required = true,
            ) {
                type = SCT.boolean
            }
            property(
                HFEAT.browsesUsers,
                "Whether the caller is an editor of its client (issue #1432), offered the Users page read-only.",
                required = true,
            ) {
                type = SCT.boolean
            }
            property(
                HFEAT.seesClientForms,
                "Whether the caller's reads reach every form of a client -- an administrator of one, or an editor (issue " +
                    "#1432) -- so the forms list is \"Forms\" and shows whose each is.",
                required = true,
            ) {
                type = SCT.boolean
            }
            property(HFEAT.hasSurvey, "Whether the caller's client declares a survey workflow (issue #695) -- the forms list then offers its survey-status filter.", required = true) {
                type = SCT.boolean
            }
            property(HFEAT.canOpenSandbox, "Whether the caller may open their own client's sandbox (issue #931).", required = true) {
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
            property(HFLD.users, "The users the caller's identity may act as (issue #749); empty when signed out.", required = true) {
                type = SCT.array
                items { ref(UserChoice.infoTypeName) }
            }
            property(
                HFLD.sourceRepoBase,
                "The source repository's blob base (.../blob/<branch>) for rewriting a document's interior " +
                    "links; absent when the deployment configured no source repo.",
            )
            property(
                HFLD.timeZone,
                "The IANA time zone the caller's moments are shown in (issue #1115): their client's, else the " +
                    "deployment's display default (KDR_DISPLAY_TIME_ZONE); absent when neither is set, for the " +
                    "deployment's day start.",
            )
            property(
                HFLD.timeZoneLabel,
                "Beside timeZone, the zone's generic short name (CT), which a moment shown in it is labeled with (issue " +
                    "#1321); absent for a zone named only by its offset.",
            )
            property(
                HFLD.sharedDemo,
                "True when the caller's client is a demo client visitors join (issue #1248), or its sandbox, on a deployment that offers " +
                    "joining, so every page notes it is a shared space; absent otherwise.",
            ) { type = SCT.boolean }
            property(
                HFLD.selfServeClient,
                "For a caller signed in to public, the self-serve client their person created (issue #1228); absent when " +
                    "they have made none.",
            )
            property(
                HFLD.selfServeClientAbsent,
                "Beside selfServeClient, true when this node does not carry that client (retired, or run elsewhere); absent " +
                    "otherwise.",
            ) { type = SCT.boolean }
            property(
                HFLD.joinOffered,
                "True for a caller signed in to public on a deployment that offers joining a demo client (issue #1248); " +
                    "absent otherwise.",
            ) { type = SCT.boolean }
            property(HFLD.sandboxOf, "When the caller is in a client's sandbox (issue #931), that client's id; absent otherwise.")
            property(HFLD.sandboxOfName, "Beside sandboxOf, that client's display name.")
            property(
                HFLD.sandboxStranded,
                "Beside sandboxOf, true when the caller's sandbox user acts with no roles (issue #1197) -- nobody stands behind it any more, or it was disabled; absent otherwise.",
            ) { type = SCT.boolean }
            property(
                HFLD.webAssets,
                "The caller's client's own web assets (issue #1130): each slot it has set, to its image by name and " +
                    "content, served at the static root's `/<appId>/web/<name>`. An absent slot is the deployment's own image.",
            ) {
                type = SCT.kObject
                for (def in ClientWebAssetService.slotDefs) property(def.slot, "The client's ${def.label.lowercase()}, as `${def.slot}:<hash>`.")
            }
            property(
                HFLD.fileRefs,
                "The file refs the caller's client is served (issue #1186), each id to its file by name and content " +
                    "(`<refId>:<hash>`), served at the static root's `/<appId>/ref/<name>`: what copy naming an image " +
                    "`ref:<refId>` is drawn from. A ref whose file is not there is absent.",
            ) { mapOfValues { type = SCT.string } }
            property(
                HFLD.images,
                "The images components ship (issue #1339), each rooted name to its image by name and content " +
                    "(`<name>:<hash>`), served at the static root's `/<appId>/img/<name>`: what copy naming an image " +
                    "`img:<name>` is drawn from. The same for every caller.",
            ) { mapOfValues { type = SCT.string } }
            property(
                HFLD.imagePaths,
                "The images components ship that are kept in the repository (issue #1463), each path there to its " +
                    "rooted name, a key of `${HFLD.images}`: a document's link to that path is drawn as the image.",
            ) { mapOfValues { type = SCT.string } }
        }
    }

    generalEndpoint(HEP.homeUiConfig, "Returns the config for constructing the shell (layout, links, menu).",
        // Polled (issue #1372): the webapp's idle poll re-reads it, so a call to it renews no session.
        HttpMethod.GET, outputRef = HTYPE.homeUiConfig, tags = setOf(ETAG.frontend), polled = true) { c, _ ->
        // The menu says what this caller may reach, so it must be decided on live roles rather than the ones
        // their session cookie captured at login -- otherwise a revoked administrator keeps being offered a
        // page that now 401s, for as long as the cookie lives. The `admin` section is refreshed by the
        // dispatcher for the same reason; this is the one config that needs it outside a gated section.
        refreshActingRoles(c)
        mapOf(
            // The shell's own file first, then the files of the pages with no UI-config of their own (issue #1337), which
            // read their copy through this one.
            UIC.fragments to fragmentRefs(c, HFRAG.home, *PFRAG.files.toTypedArray()),
            // Default to a left nav bar alone: the classic shape, and the one that stays usable as the
            // document list grows. A deployment turns on the others (or off) via HCFG.
            UIC.features to mapOf(
                HFEAT.topBar to c.layoutFlag(HCFG.homeTopBar, default = false),
                // Off by default since the Documents page exists (issue #554): the bar only duplicated its listing.
                HFEAT.leftBar to c.layoutFlag(HCFG.homeLeftBar, default = false),
                HFEAT.inlineLinks to c.layoutFlag(HCFG.homeInlineLinks, default = false),
                HFEAT.canManageUsers to AdminRules.canManageUsers(c),
                HFEAT.canSeeAllClients to AdminRules.canSeeAllClients(c),
                // The rule the menu's "Forms" / "My forms" entries ask as a cfact (issue #1091), so they agree.
                HFEAT.administersClient to AdminRules.isClientAdministrator(c),
                // What an editor (issue #1432) is shown: the Users page read-only, and every form of its client with
                // its owner -- the rules the `clientDirectory` section and the forms' read scope enforce.
                HFEAT.browsesUsers to AdminRules.editsClient(c),
                HFEAT.seesClientForms to (AdminRules.isClientAdministrator(c) || AdminRules.editsClient(c)),
                // The caller's own client's registry (issue #695): a cross-client admin working in another
                // client's surface reads that client's rows, but the filter keys on where the caller belongs.
                HFEAT.hasSurvey to (WorkflowService.get(c).forClient(c.client).survey != null),
                // The rule the open endpoint enforces (issue #931), so the shell offers only what it would admit.
                // An edge has no clients to have sandboxes.
                HFEAT.canOpenSandbox to (ClientService.getOrNull(c) != null && c.userProfile.isLoggedIn &&
                    SandboxAccess.mayOpen(c, c.client) && SandboxAccess.hasSandbox(c, c.client)),
            ),
            UIC.state to buildMap {
                put(HFLD.links, homeLinksFor(c))
                put(HFLD.menu, resolvedMenu(c))
                put(HFLD.userInfo, c.userProfile.toUserInfo())
                // The switcher's list rides the shell config (issue #749), which the bar already re-reads on
                // every refresh generation -- a sign-in, a switch -- so it needs no fetch of its own. An edge
                // carries no user service, and no user to list: empty there.
                put(HFLD.users, UserService.getOrNull(c)?.selfUserChoices(c).orEmpty().map { it.toInfo() })
                // Only when configured -- an absent field says "no source repo", which is how the frontend
                // leaves a non-document interior link as written (issue #492).
                sourceRepoBase(c)?.let { put(HFLD.sourceRepoBase, it) }
                // Where the client's people are (issue #1115): the zone the webapp shows moments in -- the client's, else
                // the deployment's display default. Absent, the webapp shows the deployment's day start.
                ClientService.getOrNull(c)?.displayZoneOf(c.client)?.let { zone ->
                    put(HFLD.timeZone, zone)
                    // Named as a moment shown in it is labeled (issue #1321), so the browser's wording is the backend's.
                    genericZoneLabel(zone)?.let { put(HFLD.timeZoneLabel, it) }
                }
                // The self-serve client the caller's person made (issue #1228), read off the identity: in `public`, where
                // a person creates one, so the home page offers to open it rather than to create a second.
                if (c.client == CL.public && c.userProfile.isLoggedIn) {
                    // Joining a demo client (issue #1248), so the public home page (issue #1324) asks which there are.
                    if (ClientJoin.isOffered(c)) put(HFLD.joinOffered, true)
                    val identityId = c.userProfile.identityId
                    val users = UserService.getOrNull(c)
                    if (identityId != null && users != null) {
                        users.queryIdentityById(c, identityId)?.identityData?.get(IDD.selfServeClient).toOptStr()?.let {
                            put(HFLD.selfServeClient, it)
                            // A person creates one, so a client retired or run elsewhere is said, not offered to open.
                            if (ClientService.getOrNull(c)?.isPresent(it) != true) put(HFLD.selfServeClientAbsent, true)
                        }
                    }
                }
                // A shared demo space (issue #1248): what the caller does here, other visitors see -- and in its sandbox
                // too, which every administrator who joined may open, and whose own definition declares no roles.
                val demo = sandboxParentOf(c.client) ?: c.client
                if (ClientJoin.isOffered(c) && ClientService.getOrNull(c)?.present(demo)?.joinRoles?.isNotEmpty() == true) {
                    put(HFLD.sharedDemo, true)
                }
                // In a sandbox (issue #931): whose it is, so the shell marks it and offers the way back.
                sandboxParentOf(c.client)?.let { parent ->
                    put(HFLD.sandboxOf, parent)
                    put(HFLD.sandboxOfName, ClientService.getOrNull(c)?.known(parent)?.name ?: parent)
                    // Said, so the shell explains a sandbox where nothing opens rather than offering doors (issue #1197):
                    // a signed-in sandbox user acting with no roles, whatever the reason -- nobody stands behind it
                    // any more, or it was itself disabled. Read off the profile refreshed above; no second lookup.
                    val profile = c.userProfile
                    if (profile.isLoggedIn && profile.isRowBacked && profile.roles.isEmpty()) put(HFLD.sandboxStranded, true)
                }
                // The client's own tab icon and brand mark (issue #1130), where it has set them. An edge keeps none.
                ClientWebAssetService.getOrNull(c)?.let { put(HFLD.webAssets, it.versionedNames(c, c.client)) }
                // The images its copy may name (issue #1186), as the static root serves them to this caller.
                FileRefService.getOrNull(c)?.let { put(HFLD.fileRefs, it.servedNames(c, c.client)) }
                // The images components ship (issue #1339): copy's `img:` names, the same for every caller.
                ComponentImageService.getOrNull(c)?.let {
                    put(HFLD.images, it.servedNames())
                    // And the paths in the repository a document names some of them by (issue #1463).
                    put(HFLD.imagePaths, it.paths)
                }
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
 * account, forms, and profile surfaces are contributed `appOnly` (#432), so an edge that offered them was
 * offering pages whose endpoints are not there: an anonymous edge caller was shown "Log in" and "Register" --
 * the account-creation surface #432 existed to remove -- and an env-authed one, who holds `admin`, was shown
 * Users, My forms, Profile, and Log out. Six items, all of which 404 on the node serving them.
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
 * Labels stay literal, as `coreDocs` link labels do. Their natural home is the `home` fragment file, and
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
        // The Account group leads the menu (issue #540): the signed-in user's account menu belongs at the
        // top, the way a user menu sits top-right in a conventional shell. The parent is gated on the app
        // boot role alone, so it shows to any app caller -- logged in or anonymous -- and always has at least
        // one surviving child (a caller is one or the other), so it never renders bare. The children keep
        // their own loggedIn/anonymous gates, so the group shows Profile+Log out or Log in+Register.
        menuItem(HMENU.account, "Account", cfactExpression = CFACTS.app)
        menuItem(HMENU.profile, "Profile", UiRoute(HMENU.pageProfile),
            cfactExpression = "${CFACTS.loggedIn},${CFACTS.app}", parentId = HMENU.account)
        menuItem(HMENU.logout, "Log out", UiCall(HACT.logout),
            cfactExpression = "${CFACTS.loggedIn},${CFACTS.app}", parentId = HMENU.account)
        menuItem(HMENU.login, "Log in", UiRoute(HMENU.pageLogin),
            cfactExpression = "${CFACTS.anonymous},${CFACTS.app}", parentId = HMENU.account)
        menuItem(HMENU.register, "Register", UiRoute(HMENU.pageRegister),
            cfactExpression = "${CFACTS.anonymous},${CFACTS.app}", parentId = HMENU.account)
        // Becoming someone (issue #1183) -- the first thing anyone wants on a test instance -- for a caller with test
        // access, signed in or not: the fixture they call exists for nobody else (issue #1149).
        menuItem(HMENU.becomeDeploymentAdmin, "Become deployment admin", UiCall(HACT.becomeDeploymentAdmin),
            cfactExpression = "${CFACTS.hasTestAccess},${CFACTS.app}", parentId = HMENU.account)
        menuItem(HMENU.becomeUser, "Become a user…", UiRoute(HMENU.pageBecomeUser),
            cfactExpression = "${CFACTS.hasTestAccess},${CFACTS.app}", parentId = HMENU.account)

        // An editor (issue #1432) is offered the page too, which it reads without the controls that change a user.
        menuItem(
            HMENU.users, "Users", UiRoute(HMENU.pageUsers),
            cfactExpression = "(${CFACTS.hasAdminLevel}|${CFACTS.isEditor}),${CFACTS.app}",
        )
        // The clients an administrator oversees (issue #905), as two entries for the one page, mutually exclusive by
        // cfact as the two "Debug" entries are, so only ever one shows (issue #1082). An administrator who sees across
        // clients gets a listing, and "Clients". Any other administrator of a client oversees that one, and the page
        // opens it directly -- so their entry says "My client", since "Clients" promised a list that is not there. A
        // `public` self-administrator administers no client (issue #805) and is offered neither (issue #1091): the
        // page would only refuse them.
        menuItem(HMENU.clients, "Clients", UiRoute(HMENU.pageClients), cfactExpression = "${CFACTS.isDeploymentAdmin},${CFACTS.app}")
        menuItem(
            HMENU.myClient, "My client", UiRoute(HMENU.pageClients),
            cfactExpression = "${CFACTS.administersClient},~${CFACTS.isDeploymentAdmin},${CFACTS.app}",
        )
        // A customer's page (issue #1114), as two entries for the one page as the Clients entries are: a deployment
        // administrator's list of customers, and for an administrator of a customer's root client their own customer,
        // opened directly.
        menuItem(HMENU.customers, "Customers", UiRoute(HMENU.pageCustomers), cfactExpression = "${CFACTS.isDeploymentAdmin},${CFACTS.app}")
        menuItem(
            HMENU.myCustomer, "My customer", UiRoute(HMENU.pageCustomers),
            cfactExpression = "${CFACTS.administersCustomer},~${CFACTS.isDeploymentAdmin},${CFACTS.app}",
        )
        // The client's named reports (issue #981), offered to whoever administers a client -- the caller the report
        // endpoints admit, so a `public` self-administrator is not offered a page that would refuse them (issue
        // #1091). The endpoints stay the authority.
        menuItem(HMENU.reports, "Reports", UiRoute(HMENU.pageReports), cfactExpression = "${CFACTS.administersClient},${CFACTS.app}")
        // The API keys of the users a client's administrator administers (issue #1402): offered as Reports is, to the
        // caller the key endpoints admit, so a `public` self-administrator -- who manages their own on Profile -- is not.
        menuItem(HMENU.apiKeys, "API keys", UiRoute(HMENU.pageApiKeys), cfactExpression = "${CFACTS.administersClient},${CFACTS.app}")
        // The Operator group (issue #540): a parent header and the deployment-operator diagnostic pages under
        // it, plus an "Overview" landing page that explains each. Parent and children share the one cfact
        // (isDeploymentOperator), so a non-operator sees neither the header nor an orphaned child, and the
        // header never renders bare -- filtering removes the whole group together. Client facts is not here:
        // its gate is client-operator, a different axis, and it is under Reference.
        menuItem(HMENU.operator, "Operator", cfactExpression = CFACTS.isDeploymentOperator)
        menuItem(
            HMENU.operatorOverview, "Overview", UiRoute(HMENU.pageOperator),
            cfactExpression = CFACTS.isDeploymentOperator, parentId = HMENU.operator,
        )
        menuItem(
            HMENU.envReference, "Environment", UiRoute(HMENU.pageEnv),
            cfactExpression = CFACTS.isDeploymentOperator, parentId = HMENU.operator,
        )
        menuItem(
            HMENU.systemInfo, "System info", UiRoute(HMENU.pageSystemInfo),
            cfactExpression = CFACTS.isDeploymentOperator, parentId = HMENU.operator,
        )
        menuItem(
            HMENU.bootChecks, "Boot checks", UiRoute(HMENU.pageBootChecks),
            cfactExpression = CFACTS.isDeploymentOperator, parentId = HMENU.operator,
        )
        menuItem(
            HMENU.dbTables, "Database tables", UiRoute(HMENU.pageDbTables),
            cfactExpression = CFACTS.isDeploymentOperator, parentId = HMENU.operator,
        )
        menuItem(
            HMENU.fragmentsCheck, "Fragments check", UiRoute(HMENU.pageFragmentsCheck),
            cfactExpression = CFACTS.isDeploymentOperator, parentId = HMENU.operator,
        )
        menuItem(
            HMENU.cacheState, "Cache state", UiRoute(HMENU.pageCacheState),
            cfactExpression = CFACTS.isDeploymentOperator, parentId = HMENU.operator,
        )
        // The file-store browser (issue #1297): app-only, since an edge keeps no files.
        menuItem(
            HMENU.fileStore, "File store", UiRoute(HMENU.pageFileStore),
            cfactExpression = "${CFACTS.isDeploymentOperator},${CFACTS.app}", parentId = HMENU.operator,
        )
        // Forms are login-gated only (the `gedra` section), so every signed-in caller is offered the list; how
        // far it reaches is a scope question the endpoints answer, not a menu one (issue #408). Only "My forms"
        // is an entry: the list is the hub for the whole lifecycle, so creating a form is reached by its
        // "New form" button rather than a second, redundant nav item (issue #417).
        //
        // Two entries for the one page, mutually exclusive by cfact (issue #1091): the listing is named for whose
        // forms it shows. A caller who reads every form of a client -- an administrator of it, or an editor (issue
        // #1432) -- lists them all: "Forms". Everyone else lists their own -- "My forms" -- which is written by
        // exclusion on purpose: whatever kind of caller is invented later reads their own forms until something says
        // they read more.
        menuItem(
            HMENU.forms, "My forms", UiRoute(HMENU.pageForms),
            cfactExpression = "${CFACTS.loggedIn},~${CFACTS.seesClientForms},${CFACTS.app}",
        )
        menuItem(
            HMENU.clientForms, "Forms", UiRoute(HMENU.pageForms),
            cfactExpression = "${CFACTS.seesClientForms},${CFACTS.app}",
        )
        // The workflow pages (issue #792): off by default -- a client with normal workflows turns it on by overlaying
        // this item with a real condition (the sample's acme does), the way an overlay turns an item off.
        menuItem(HMENU.workflows, "Workflows", UiRoute(HMENU.pageWorkflows), cfactExpression = CFACT.neverName)

        // The Reference group: pages read to learn something, visited far less than the daily ones above, so a
        // level down. Grouped by how often they are visited, not by who sees them -- each child keeps its own
        // condition, and the header has none: a header whose children are all filtered out is not drawn (the
        // app bar's `menuTree`), so it needs no union of theirs.
        menuItem(HMENU.reference, "Reference")
        // The documents page (issue #554). App-only: an edge serves no documents (its `links` are empty), so
        // offering the listing there would be an entry that opens onto nothing.
        menuItem(HMENU.docs, "Documents", UiRoute(HMENU.pageDocs), cfactExpression = CFACTS.app, parentId = HMENU.reference)
        menuItem(HMENU.catalog, "Endpoint catalog", UiRoute(HMENU.pageCatalog), parentId = HMENU.reference)
        // Offered to a client-scoped operator or admin (issue #488) -- the same caller the `clientOperator`
        // section admits, asked as a cfact so the menu offer and the gate cannot drift.
        menuItem(
            HMENU.cfactReference, "Client facts", UiRoute(HMENU.pageCfacts),
            cfactExpression = "${CFACTS.isClientOperator},${CFACTS.app}", parentId = HMENU.reference,
        )

        // Simulations (issue #997): for a caller with test access (issue #1149) -- the simulations exist for nobody
        // else. No login is asked for: provisioning one then signing in as its users is the point.
        menuItem(HMENU.simulations, "Simulations", UiRoute(HMENU.pageSimulations),
            cfactExpression = "${CFACTS.hasTestAccess},${CFACTS.app}")

        // Debug (issue #517), offered only in an env-authed session. Not in debug yet: one top-level call to
        // turn it on. In debug: a "Debug" parent whose children drill down under it via parentId -- the debug
        // pages (which list the usable tools), and the switch back off. The two "Debug" entries are mutually
        // exclusive by cfact, so only ever one shows.
        menuItem(HMENU.debugEnable, "Debug", UiCall(HACT.setEnvDebug, listOf("true")),
            cfactExpression = "${CFACTS.canEnableDebug},${CFACTS.app}")
        menuItem(HMENU.debug, "Debug", cfactExpression = "${CFACTS.isEnvDebug},${CFACTS.app}")
        menuItem(HMENU.debugPages, "Debug pages", UiRoute(HMENU.pageDebug),
            cfactExpression = "${CFACTS.isEnvDebug},${CFACTS.app}", parentId = HMENU.debug)
        menuItem(HMENU.debugOff, "Turn off debug", UiCall(HACT.setEnvDebug, listOf("false")),
            cfactExpression = "${CFACTS.isEnvDebug},${CFACTS.app}", parentId = HMENU.debug)
    }
}

/** [HMENU.block] resolved for this caller: the items their cfacts satisfy, in display order. */
private fun resolvedMenu(cxt: KdrCxt): List<Any?> =
    (UiBlockService.get(cxt).resolve(cxt, HMENU.block)?.get(HFLD.menu) as? List<*>) ?: emptyList()

/** A layout toggle from the deployment's instance config, or [default] when it is not configured. */
private fun KdrCxt.layoutFlag(key: String, default: Boolean): Boolean =
    (instanceConfig.get(key) as? Boolean) ?: default

/**
 * The documents the home page links to, in display order: every one a component declared (`docs`, issue #1136),
 * each with [HFLD.listed] saying whether the Documents list offers it -- an unlisted one is still sent, since a link
 * to it from another document or from form copy resolves by its source path. Each is paired with its current build
 * id, so the frontend fetches an immutably cacheable URL. A document whose resource is absent (its owning module is
 * not in the deployment) is simply left out, rather than offered as a link that would 404.
 */
private fun homeLinks(cxt: KdrCxt): List<Map<String, Any?>> = declaredDocs(cxt).mapNotNull { doc ->
    val buildId = MarkdownDocService.docBuildId(doc) ?: return@mapNotNull null
    mapOf(
        HFLD.id to doc.docId,
        HFLD.label to doc.label,
        HFLD.docId to doc.docId,
        HFLD.buildId to buildId,
        HFLD.sourcePath to doc.sourcePath,
        HFLD.description to doc.description,
        HFLD.listed to doc.listed,
    )
}

/** The documents the components declared, as the boot collected them (empty on an instance built without them). */
@Suppress("UNCHECKED_CAST")
private fun declaredDocs(cxt: KdrCxt): List<MarkdownDocDef> =
    cxt.instanceConfig.get(MDOC.registryKey) as? List<MarkdownDocDef> ?: emptyList()

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
    val onAnonymousEdgeLanding = registry.parse("${CFACTS.anonymous},${CFACTS.edge}").matches(registry.assemble(cxt))
    return if (onAnonymousEdgeLanding) emptyList() else homeLinks(cxt)
}

/**
 * The documents `base/common` serves (issue #492): the README plus the repo docs it links to, declared by the common
 * component. Other components declare their own (`docs`, issue #1136). The listing offers the README, the extending
 * guide, the API guide (issue #1493), the API keys and tokens guide (issue #1403), the code guide, the schema dialect reference (generated, issue
 * #1347), and the license; the design documents are served but unlisted, reached from the README's
 * links (issue #1238).
 */
fun coreDocs(): List<MarkdownDocDef> = listOf(
    MarkdownDocDef(HDOC.readme, "Read me", "README.md",
        "What the runtime is, where it came from, and how to set it up."),
    MarkdownDocDef(HDOC.extending, "Extending", "docs/extending.md",
        "How a deployment, a component, or a client varies what the runtime does, and the rule behind it."),
    MarkdownDocDef(HDOC.apiGuide, "Using the API", "docs/api-guide.md",
        "For a program or an AI assistant with a key: the model, the conventions every call shares, and a recipe for each job."),
    MarkdownDocDef(HDOC.apiKeysAndTokens, "API keys and access tokens", "docs/api-keys-and-tokens.md",
        "How a program calls the API with a key or a token, and how another service checks a token."),
    MarkdownDocDef(HDOC.codeGuide, "Code guide", "docs/development/code-guide.md",
        "How code here is written, including where it deliberately departs from Kotlin norms."),
    MarkdownDocDef(HDOC.clientDefinition, "Client definition", "docs/design/client-definition.md",
        "How a client is described, what it owns, and what a deployment does with one.", listed = false),
    MarkdownDocDef(HDOC.deferredWork, "Deferred work", "docs/development/deferred-work.md",
        "What has deliberately not been done yet, each item under the condition that should reopen it.", listed = false),
    MarkdownDocDef(HDOC.gedraConfigAndData, "Gedra config and data", "docs/design/gedra/config-and-data.md",
        "How gedra definitions and stored data are separated, identified, and kept apart.", listed = false),
    MarkdownDocDef(HDOC.gedraEntry, "Gedra entry", "docs/design/gedra/entry.md",
        "The design of the universal stored entity and the schema constructs it needs.", listed = false),
    MarkdownDocDef(HDOC.gedraStates, "Gedra states", "docs/design/gedra/states.md",
        "What a gedra records beyond its data: derived and asserted state, and who writes and reads it.", listed = false),
    MarkdownDocDef(HDOC.uiBlock, "UI block", "docs/design/ui-block.md",
        "The UiBlock: declared presentation structure, contributed by components and varied per client.", listed = false),
    MarkdownDocDef(HDOC.reporting, "Reporting", "docs/design/reporting.md",
        "Named reports over a client's forms: paths, combines, the load-time checks, and the cursor-paged run.", listed = false),
    MarkdownDocDef(HDOC.fileRefs, "File refs", "docs/design/file-refs.md",
        "A client's named files: where the bytes are, who may name them, and how they are judged and served.", listed = false),
    MarkdownDocDef(HDOC.sandboxes, "Sandboxes", "docs/design/sandboxes.md",
        "A client's sandbox: a second client that runs its drafts, so a change is tried before it is published.", listed = false),
    MarkdownDocDef(HDOC.userSearch, "User search", "docs/design/user-search.md",
        "The admin user search, and the server-driven shape its console is built from.", listed = false),
    MarkdownDocDef(HDOC.apiKeys, "API keys (design)", "docs/design/api-keys.md",
        "How keys and the tokens exchanged for them are made, checked, and limited.", listed = false),
    MarkdownDocDef(HDOC.gedraWorkflow, "Gedra workflow (superseded)", "docs/history/gedra-workflow.md",
        "The first workflow design, retired under #533 and kept for its reasoning.", listed = false),
    MarkdownDocDef(HDOC.schemaDialect, "Schema dialect", "docs/reference/schema-dialect.md",
        "Every keyword the runtime reads in a JSON Schema, the ones it refuses, and its own -- made from the parser's tables.",
        generate = ::schemaDialectMarkdown),
    MarkdownDocDef(HDOC.clientConfiguration, "Client configuration", "docs/reference/client-configuration.md",
        "Every slot of a client's configuration, what its entries hold, and what a write or an edit of one must send.",
        generate = ::clientConfigurationMarkdown),
    MarkdownDocDef(HDOC.license, "License", "LICENSE",
        "The MIT license the runtime is released under.", order = lastDocOrder),
)

/**
 * The source repository's blob base for this deployment (`.../blob/<branch>`), or null when
 * [HDOCENV.sourceRepoUrl] is set empty. A document's interior link to a repo file that is not itself served in-app
 * is rewritten under this (issue #492); with none, such a link is drawn as its text (issue #1380). Unset is the
 * upstream repository: the documents served are the runtime's own, so that is where their links lead.
 */
private fun sourceRepoBase(cxt: KdrCxt): String? {
    val repoUrl = (cxt.getEnvVar(HDOCENV.sourceRepoUrl) ?: HDOCENV.defaultRepoUrl).trim()
    if (repoUrl.isEmpty()) {
        return null
    }
    val branch = cxt.getEnvVar(HDOCENV.sourceRepoBranch)?.trim()?.ifEmpty { null } ?: HDOCENV.defaultBranch
    return "${repoUrl.trimEnd('/')}/blob/$branch"
}

/** Environment variables for linking a served document back to its source repository (issue #492). */
@Suppress("ConstPropertyName")
object HDOCENV {
    /** The branch a source-repo link points at when [sourceRepoBranch] is unset. */
    const val defaultBranch = "main"

    /** The repository a source-repo link points into when [sourceRepoUrl] is unset (issue #1380). */
    const val defaultRepoUrl = "https://github.com/sampwhite/KotlinDynamicRuntime"

    val sourceRepoUrl = EnvVarDef(
        "KDR_SOURCE_REPO_URL", group = ENVGRP.content, defaultDoc = "'$defaultRepoUrl'",
        description = "The web base URL of the source repository that served documents link into, e.g. " +
            "'https://github.com/owner/repo'. A served Markdown document's interior links to repo files that are " +
            "not themselves served in-app are rewritten to '<this>/blob/<branch>/<path>' (issue #492). Unset, it is " +
            "the upstream repository, since the documents served are the runtime's own (issue #1380); a fork that " +
            "serves its own copies sets its own. Set empty, nothing is rewritten, and such a link is drawn as its " +
            "text, since it has nowhere to go from inside the app.",
    )

    val sourceRepoBranch = EnvVarDef(
        "KDR_SOURCE_REPO_BRANCH", group = ENVGRP.content, defaultDoc = "'main'",
        description = "The branch that source-repository document links point at (see KDR_SOURCE_REPO_URL). " +
            "Defaults to 'main'. Has no effect when KDR_SOURCE_REPO_URL is set empty.",
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
