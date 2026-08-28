package com.dynamicruntime.edge

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.HACT
import com.dynamicruntime.common.home.menuItem
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.Presence
import com.dynamicruntime.common.startup.ServiceEntry
import com.dynamicruntime.common.startup.service
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.UiCall
import com.dynamicruntime.common.uiblock.uiBlockOverlay

/**
 * The **KdrEdge** component (issues #347, #386): what makes a node booted by `StartEdge` an edge rather than
 * an ordinary KDR node on a different port.
 *
 * The class is `EdgeComponent`, matching `CommonComponent` / `AppUiComponent` and carrying no `Kdr` prefix,
 * while [providerName] announces **KdrEdge** -- so the name a human meets in a log line or a selector carries
 * the weight, and the type names stay short.
 */
class EdgeComponent : ComponentDefinition {
    override val providerName: String = name

    /**
     * Present only on a node booted in the [BOOT.edge] role.
     *
     * This is what lets the component be **discovered** rather than referenced: `ServiceLoader` finds it in
     * every launcher, including the ordinary one, and it is absent there. `InstanceRegistry` gates both schema
     * and services on this, so being absent keeps the whole component out rather than half of it.
     *
     * Declared rather than decided in [isLoaded] (issue #433): the answer is a plain fact about which nodes
     * carry this, and a declaration can be read by anything asking what an edge contains. It was a predicate
     * only because there was nothing else to be.
     */
    override fun presence(cxt: KdrCxt): Presence = Presence(roles = setOf(BOOT.edge))

    /**
     * The instance config that makes this node an edge: its context roots, and the port its role binds.
     *
     * Defaults, not overrides -- each reads before it writes, so a deployment that chose its own keeps it.
     * These are what make the node an edge rather than preferences about it, which is why they are supplied by
     * the component rather than left to a deployment's config object to remember.
     */
    override fun applyInstanceConfig(cxt: KdrCxt) {
        val config = cxt.instanceConfig
        config.put(ACFG.apiContextRoot, config.get(ACFG.apiContextRoot) ?: EdgeRoot.ea)
        config.put(ACFG.contentContextRoot, config.get(ACFG.contentContextRoot) ?: EdgeRoot.ec)
        config.put(ACFG.appContextRoot, config.get(ACFG.appContextRoot) ?: EdgeRoot.ew)
        config.put(ACFG.staticContextRoot, config.get(ACFG.staticContextRoot) ?: EdgeRoot.es)
        config.put(ACFG.defaultPort, config.get(ACFG.defaultPort) ?: EdgeRole.defaultPort)
    }

    /*
     * No load-priority override anymore (issue #433).
     *
     * This used to return `PRI.early` so `EdgeService` registered its content server before `PortalService`
     * did -- content servers answer in registration order, and without it `/ec` reached the application's
     * portal instead of the sign-in page. Its own comment called it "a stopgap for as long as an edge loads
     * the portal at all", and an edge no longer does: `PortalService` is declared application-only, so there
     * is nothing to lose the race to. The stopgap retiring is what shows the declaration did the real work
     * rather than merely moving the ordering around.
     */

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(envAuthSchema(cxt))
    }

    /**
     * The shell's wordmark, marked so an edge is recognizable as one (issue #446).
     *
     * An overlay of the `home` fragment rather than a frontend conditional: the shell renders the brand it is
     * handed and still does not know edges exist. It needs no cfact either -- this component loads only on an
     * edge, so its overlay simply is not present anywhere else.
     *
     * Only the **edge** is marked, not the application. The application is the ordinary case, and labeling it
     * would tell nearly every viewer something they never needed to be told; more to the point, a deployment's
     * application will carry the *customer's* brand, where a marker would be wrong exactly where it matters.
     * An edge is ours in every deployment, so a marker on it stays true.
     *
     * The value is literal rather than composed from the base brand, which is the limitation to know: a
     * deployment that renames the product renames this too. Composing it would need the base split into a
     * separate key, which is not worth doing before a second deployment brand exists.
     */
    override fun fragments(cxt: KdrCxt): List<FragmentSource> = listOf(
        fragmentInline(HFRAG.home, origin = name) {
            namespace(HFRAG.home) { key(EDGEUI.brandKey, EDGEUI.brand) }
        },
    )

    /**
     * The one menu item an edge adds: signing out of the environment (issue #486).
     *
     * An overlay onto the shared `homeMenu`, and needing no boot-role cfact for the same reason the brand
     * overlay does not -- this component loads only on an edge, so the item is simply absent on every other
     * node. It **does** carry [CFACTS.loggedIn], and that is the one dimension the brand analogy does not
     * cover: a wordmark is shown to anyone, but an offer to *log out* is meaningless to a caller who is not
     * logged in, and the anonymous menu (a bare sign-in surface) must not carry it. On an edge "logged in"
     * can only mean env-authed, so the cfact reads exactly as intended -- the same gate the application's own
     * "Log out" uses, minus the `,app` this side does not need.
     *
     * The item is a **call**, not a route, as signing out is everywhere: a request that clears the perimeter
     * cookie, then a navigation. Both URLs it needs are the edge's to know and travel as the call's arguments
     * (see [HACT.envLogout]) -- the api-relative clear-cookie path, and the absolute sign-in page to land on,
     * carrying [EDGEP.loggedOutParam] so that page greets the caller as freshly signed out. The landing URL is
     * built from this node's *own* content root, so a deployment that renamed it is honored rather than
     * assumed.
     */
    override fun uiBlocks(cxt: KdrCxt): List<UiBlockSource> {
        val contentRoot = cxt.instanceConfig.get(ACFG.contentContextRoot) as? String ?: EdgeRoot.ec
        val landingUrl = "/$contentRoot${EDGEP.loginPage}?${EDGEP.loggedOutParam}=1"
        return listOf(
            uiBlockOverlay(HMENU.block, origin = name) {
                items(HFLD.menu) {
                    menuItem(
                        EDGEUI.logoutItem, "Log out",
                        UiCall(HACT.envLogout, listOf(EAEP.logout, landingUrl)),
                        cfactExpression = CFACTS.loggedIn,
                        displayOrder = EDGEUI.logoutOrder,
                    )
                }
            },
        )
    }

    override fun services(cxt: KdrCxt): List<ServiceEntry> = listOf(service(::EdgeService))

    @Suppress("ConstPropertyName")
    companion object {
        /** The name this component announces itself under, in logs and provider selection. */
        const val name = BOOT.edgeComponent

    }
}
