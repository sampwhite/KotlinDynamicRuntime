package com.dynamicruntime.common

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.node.InstanceConfigService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.operator.operatorSchema
import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.coreTraits
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.app.appSchema
import com.dynamicruntime.common.home.homeSchema
import com.dynamicruntime.common.test.testSchema
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
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer

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
        // Endpoints/types live with the services that own them; the component just wires them in.
        collector.addModule(NodeService.schema(cxt))
        collector.addModule(SchemaService.schema(cxt))
        // The topic service contributes the list-tables endpoint.
        collector.addModule(SqlTopicService.schema(cxt))
        // Domain tables: the node's private InstanceConfig table (owned by InstanceConfigService).
        collector.addTables(InstanceConfigService.tables(cxt))
        // Auth (issue #67): the user/auth endpoints and the AuthUsers/AuthUserDevices tables.
        collector.addModule(authSchema(cxt))
        collector.addTables(authTables(cxt))
        // Profile (issue #70): the login-gated profile page endpoints (its own widget-group namespace).
        collector.addModule(profileSchema(cxt))
        // Admin: the user-management endpoints, gated on ROLE.admin by their `admin` section.
        collector.addModule(adminSchema(cxt))
        // The same user-administration operations, scoped to the caller's client (issue #225).
        collector.addModule(scopedUserAdminSchema(cxt))
        // Operator: running-the-deployment diagnostics, gated on ROLE.operator by their `operator` section.
        collector.addModule(operatorSchema(cxt))
        // Home/shell: the UI-config endpoint that tells the frontend which layout to build and which
        // Markdown documents to link to.
        collector.addModule(homeSchema(cxt))
        // App-level (issue #118): deployment-global config the whole frontend shares (the error-display policy).
        collector.addModule(appSchema(cxt))
        // Test-only endpoints (issue #125): filtered out of the store unless the deployment allows them.
        collector.addModule(testSchema(cxt))
        // Fragment checking: the operator endpoint that validates this instance's Markdown fragment files.
        collector.addModule(MarkdownFragmentService.schema(cxt))
    }

    /**
     * The fragment files `base/common` ships. `errors` and `sample` are here as much as the widget-group
     * files: `errors` is reached through the error-message path rather than a UI-config, so nothing else
     * names it, and an unchecked error fragment is exactly the one you find out about during an incident.
     */
    /**
     * The Gedra config this module defines (issue #300): the traits every deployment has, in the reserved
     * `globalconfig` namespace. Declared here rather than in a sample, because these are part of what the
     * runtime is -- and because anything a test needs to reach has to come from a component that always loads.
     */
    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(coreTraits(cxt))

    override fun fragmentFiles(cxt: KdrCxt): List<String> =
        listOf(AFRAG.auth, AFRAG.profile, HFRAG.home, FRAG.errors, FRAG.sample)

    /**
     * Startup services -- fully initialized before regular services. Schema compilation must be ready first
     * (the topic service reads the compiled table definitions); [NodeService] is here so the node's identity
     * and basic facts are known early; [SqlTopicService] is here so that by the time any regular service's
     * `onCreate` runs -- notably [InstanceConfigService], which queries the database in its own -- the
     * database configuration is resolved *and* every topic's tables exist (issue #162). Regular services (and
     * future startup services) may need all three during their init.
     */
    override fun startupServices(cxt: KdrCxt): List<() -> ServiceInitializer> =
        listOf(::SchemaService, ::NodeService, ::SqlTopicService)

    /**
     * The request dispatcher, the portal (which registers itself with the dispatcher as a content server),
     * and the instance-config service (whose `onCreate` loads/creates the node's encryption key from the
     * database, relying on the startup-tier [SqlTopicService] having already resolved the database and
     * created the topic's tables).
     */
    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> =
        listOf(
            ::RequestService, ::PortalService, ::MarkdownFragmentService, ::MarkdownDocService,
            ::InstanceConfigService, ::MailService, ::UserService,
        )

    /** Load just ahead of the standard components (demonstrates relative priority). */
    override fun loadPriority(): Int = PRI.standard - 1
}
