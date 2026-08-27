package com.dynamicruntime.common

import com.dynamicruntime.common.cfact.addCoreCFacts
import com.dynamicruntime.common.cfact.cfactSchema
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.http.client.OutboundHttpService
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.node.InstanceConfigService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.operator.operatorSchema
import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.fragmentFiles
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraService
import com.dynamicruntime.common.gedra.clientCatalogSchema
import com.dynamicruntime.common.gedra.coreClients
import com.dynamicruntime.common.gedra.coreTraits
import com.dynamicruntime.common.gedra.gedraDataTables
import com.dynamicruntime.common.gedra.gedraSchema
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.app.appSchema
import com.dynamicruntime.common.home.homeSchema
import com.dynamicruntime.common.test.testSchema
import com.dynamicruntime.common.http.request.VariantBehavior
import com.dynamicruntime.common.http.request.variantSchema
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.portal.PortalService
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.adminSchema
import com.dynamicruntime.common.user.scopedUserAdminSchema
import com.dynamicruntime.common.user.authSchema
import com.dynamicruntime.common.user.authTables
import com.dynamicruntime.common.user.profileSchema
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.PRI
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.Presence
import com.dynamicruntime.common.startup.ServiceEntry
import com.dynamicruntime.common.startup.service

/**
 * The `common` module's component. It owns the foundational schema and services: the [SchemaService]
 * that compiles all contributed schemas at startup, the [NodeService] (which also contributes the
 * `/health` endpoint), and the [RequestService] dispatcher. Dn's separate `core` component is folded
 * in here (see [ComponentDefinition]).
 *
 * Lives at the module's root package, beside [Common], matching how `KdnComponent` sits at the root of
 * the `kdn` module.
 */
class CommonComponent : ComponentDefinition {
    override val providerName: String = "common"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        // The surface that belongs to an application and not to a perimeter (issues #432, #433). An edge is
        // deliberately exposed to the internet, and every one of these is a way in that has nothing to do with
        // its job: it has no user store, so serving the application's account endpoints from its own database
        // is an account-creation surface on the one node that should have none.
        val appOnly = Presence(roles = setOf(BOOT.app))
        // The cfacts every deployment has (issue #455). Not presence-gated, and that is the point: a cfact
        // an expression may name has to be declared on every node that could serve the data naming it, or
        // shared data would parse on some nodes and refuse the boot on others.
        addCoreCFacts(collector)
        // Endpoints/types live with the services that own them; the component just wires them in.
        collector.addModule(NodeService.schema(cxt))
        collector.addModule(SchemaService.schema(cxt))
        // The topic service contributes the list-tables endpoint.
        collector.addModule(SqlTopicService.schema(cxt))
        // Domain tables: the node's private InstanceConfig table (owned by InstanceConfigService).
        collector.addTables(InstanceConfigService.tables(cxt))
        // The one row every node reads to find out which cached tables have changed (see SqlTableCacheService),
        // plus the operator endpoint reporting this node's caches against it.
        collector.addTables(SqlTableCacheService.tables(cxt))
        collector.addModule(SqlTableCacheService.schema(cxt))
        // Auth (issue #67): the user/auth endpoints and the AuthUsers/AuthUserDevices tables.
        collector.addModule(authSchema(cxt), appOnly)
        collector.addTables(authTables(cxt), appOnly)
        // Profile (issue #70): the login-gated profile page endpoints (its own widget-group namespace).
        collector.addModule(profileSchema(cxt), appOnly)
        // Admin: the user-management endpoints, gated on ROLE.admin by their `admin` section.
        collector.addModule(adminSchema(cxt), appOnly)
        // Admin: the clients this deployment carries (issue #343), in the same full-scope section.
        // NOT app-only, deliberately. It registers the `clientOptions` choice list, which `schema.EndpointQuery`
        // sources -- and that type belongs to the endpoint catalog, which every node serves. Marking this
        // app-only makes an edge refuse to boot: "property 'client' sources its options from 'clientOptions',
        // which no component registered."
        //
        // The provider could be split out and contributed everywhere, but it is co-located with the listing it
        // agrees with on purpose (see ClientEndpoints, and #390, whose lesson was that a comment saying two
        // lists must match is not a mechanism). Its listing is `/admin/clients` -- read-only, fenced by the
        // admin section, and no part of the account surface #432 is about -- so carrying it costs an edge
        // little. Worth revisiting when the endpoint axis lands and the two can be declared apart without
        // being written apart.
        collector.addModule(clientCatalogSchema(cxt))
        // The same user-administration operations, scoped to the caller's client (issue #225): the
        // `clientAdmin` section (renamed from `userAdmin` in #466).
        collector.addModule(scopedUserAdminSchema(cxt), appOnly)
        // The cfacts an expression may name, for whoever is authoring configuration against them. In the
        // `clientAdmin` section rather than `operator`, and everywhere rather than app-only, because an edge
        // has a registry of its own to report -- see `cfactSchema`.
        collector.addModule(cfactSchema(cxt))
        // Operator: running-the-deployment diagnostics, gated on ROLE.operator by their `operator` section.
        collector.addModule(operatorSchema(cxt))
        // Home/shell: the UI-config endpoint that tells the frontend which layout to build and which
        // Markdown documents to link to.
        collector.addModule(homeSchema(cxt))
        // App-level (issue #118): deployment-global config the whole frontend shares (the error-display policy).
        collector.addModule(appSchema(cxt))
        // Test-only endpoints (issue #125): filtered out of the store unless the deployment allows them.
        collector.addModule(testSchema(cxt))
        // Request-variant escape hatch (issue #471): selects a configured misbehavior scenario by cookie so the
        // frontend's loading/failure states can be driven. App-only -- it exists for the browser talking to this
        // node -- and registered only when the deployment configured scenarios, so it is not a squatting,
        // always-400 endpoint on a node that never opted in (the `fixture` section's every-endpoint-is-gated rule).
        if (VariantBehavior.isEnabled(cxt.instanceConfig)) {
            collector.addModule(variantSchema(cxt), appOnly)
        }
        // Fragment checking: the operator endpoint that validates this instance's Markdown fragment files.
        collector.addModule(MarkdownFragmentService.schema(cxt))
        // Gedra data (issue #310): the form-document endpoints and the two tables under them.
        collector.addModule(gedraSchema(cxt), appOnly)
        collector.addTables(gedraDataTables(cxt), appOnly)
    }

    /**
     * The Gedra configs this module defines: the traits every deployment has, in the reserved `globalconfig`
     * namespace (issue #300), and the two clients every deployment has (issue #343). Declared here rather
     * than in a sample, because these are part of what the runtime is -- and because anything a test needs to
     * reach has to come from a component that always loads.
     */
    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(coreTraits(cxt)) + coreClients(cxt)

    /**
     * The fragment files `base/common` ships. `errors` and `sample` are here as much as the widget-group
     * files: `errors` is reached through the error-message path rather than a UI-config, so nothing else
     * names it, and an unchecked error fragment is exactly the one you find out about during an incident.
     */
    override fun fragments(cxt: KdrCxt): List<FragmentSource> =
        fragmentFiles(AFRAG.auth, AFRAG.profile, HFRAG.home, FRAG.errors, FRAG.sample)

    /**
     * Startup services -- fully initialized before regular services. [ClientService] leads (issue #343),
     * because schema compilation is heading toward being per-client and a variant cannot be built before it
     * is known which clients there are; nothing depends on that ordering yet, which is when it is cheap to
     * establish. Schema compilation comes next, and must be ready before the topic service reads the compiled
     * table definitions; [NodeService] is here so the node's identity and basic facts are known early;
     * [SqlTopicService] is here so that by the time any regular service's `onCreate` runs -- notably
     * [InstanceConfigService], which queries the database in its own -- the database configuration is
     * resolved *and* every topic's tables exist (issue #162). Regular services (and future startup services)
     * may need all of them during their init.
     */
    override fun startupServices(cxt: KdrCxt): List<ServiceEntry> =
        listOf(
            service(::ClientService), service(::SchemaService),
            service(::NodeService), service(::SqlTopicService),
        )

    /**
     * The request dispatcher, the portal (which registers itself with the dispatcher as a content server),
     * and the instance-config service (whose `onCreate` loads/creates the node's encryption key from the
     * database, relying on the startup-tier [SqlTopicService] having already resolved the database and
     * created the topic's tables).
     */
    override fun services(cxt: KdrCxt): List<ServiceEntry> =
        listOf(
            // The one outbound HTTP client (issue #420). Unscoped on purpose: an edge needs it too, for
            // `GoogleJwksKeySource` on the env-auth sign-in path. Its clients are created lazily on first call,
            // so a node (or a test) that never calls out pays nothing for it.
            service(::OutboundHttpService),
            // Its `checkReady` performs every cache's initial load, by which point the whole set is
            // registered. Its position here is no longer load-bearing: it reads the caching environment in
            // `onCreate`, which the whole tier completes before any `checkInit` registers a cache.
            service(::SqlTableCacheService),
            service(::RequestService),
            // The application's landing page, and the reason EdgeComponent had to load early: content servers
            // answer in registration order, so a portal on an edge claimed the bare content root before the
            // sign-in page could. Absent here, that race cannot happen (issues #432, #433).
            service(::PortalService, roles = setOf(BOOT.app)),
            service(::MarkdownFragmentService), service(::MarkdownDocService),
            service(::InstanceConfigService),
            // Application-only, matching the schema they serve (issues #432, #433). An edge keeps the
            // dispatcher, the content servers and the instance config -- everything it needs to answer for
            // itself -- and none of the account machinery.
            //
            // Removing UserService is safe on the per-request path because `refreshActingRoles` returns before
            // reaching it: no profile an edge can hold is row-backed, which is the distinction `isRowBacked`
            // was added for (#386). Every other caller lives in the endpoints removed alongside it.
            service(::MailService, roles = setOf(BOOT.app)),
            service(::UserService, roles = setOf(BOOT.app)),
            // GedraService before the data service that reads it, though the ordering is a courtesy rather
            // than a requirement: every service is published into the config before any `checkInit` runs.
            service(::GedraService, roles = setOf(BOOT.app)),
            service(::GedraDataService, roles = setOf(BOOT.app)),
        )

    /** Load just ahead of the standard components (demonstrates relative priority). */
    override fun loadPriority(): Int = PRI.standard - 1
}
