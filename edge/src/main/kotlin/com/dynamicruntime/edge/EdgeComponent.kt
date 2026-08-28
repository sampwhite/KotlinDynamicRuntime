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
import com.dynamicruntime.common.http.request.ContextRoot
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
     * The shell's wordmark and landing hero, marked so an edge is recognizable as one (issues #446, #493).
     *
     * An overlay of the `home` fragment rather than a frontend conditional: the shell renders the copy it is
     * handed and still does not know edges exist. It needs no cfact either -- this component loads only on an
     * edge, so its overlay simply is not present anywhere else, and it is deliberately edge-wide rather than
     * anonymous-only: an env-authed operator is in a KDR-hosted environment too. (Varying the hero *by caller*
     * would need conditional text, which fragments cannot express; the answer, deferred, is a home UiBlock
     * with a cfacts selector for the fragment -- see #493.)
     *
     * Only the **edge** is marked, not the application. The application is the ordinary case, and labeling it
     * would tell nearly every viewer something they never needed to be told; more to the point, a deployment's
     * application will carry the *customer's* brand, where a marker would be wrong exactly where it matters.
     * An edge is ours in every deployment, so a marker on it stays true.
     *
     * The values are literal rather than composed from the base copy, which is the limitation to know: a
     * deployment that renames the product renames these too. Composing them would need the base split apart,
     * which is not worth doing before a second deployment brand exists.
     */
    override fun fragments(cxt: KdrCxt): List<FragmentSource> = listOf(
        fragmentInline(HFRAG.home, origin = name) {
            namespace(HFRAG.home) {
                key(EDGEUI.brandKey, EDGEUI.brand)
                key(EDGEUI.titleKey, EDGEUI.landingTitle)
                key(EDGEUI.introKey, EDGEUI.landingIntro)
            }
        },
    )

    /**
     * The menu items an edge adds (issues #486, #493): a way into the application, and the two account actions.
     *
     * An overlay onto the shared `homeMenu`, needing no boot-role cfact for the same reason the fragment
     * overlay does not -- this component loads only on an edge, so the items are simply absent on every other
     * node. The three:
     *
     *  - **Open application** -- to the app reached *through* this edge (`/wa`), shown to everyone. An
     *    env-authed operator passes straight through the proxy; an anonymous caller's navigation is challenged
     *    by `EdgeProxyHandler` and redirected to sign-in (then back), so "click to sign in" needs no code here
     *    -- it is the existing perimeter challenge.
     *  - **Log in** ([CFACTS.anonymous]) -- to the environment's sign-in page, carrying `next` back to the
     *    edge landing so a caller returns here once signed in.
     *  - **Log out** ([CFACTS.loggedIn]) -- clears the perimeter cookie, then lands on the edge landing (#493,
     *    revising #486, which landed on the sign-in page because no landing existed yet). On an edge "logged
     *    in" can only mean env-authed, so the pair reads exactly as intended; the two never coexist.
     *
     * The first two are `openPath` calls -- a full-window navigation to a **server path**, which a `UiRoute`
     * cannot express (it routes within the app by hash). Every URL is the edge's to know and travels as the
     * call's arguments, built from this node's *own* roots so a deployment that renamed one is honored.
     */
    override fun uiBlocks(cxt: KdrCxt): List<UiBlockSource> {
        val config = cxt.instanceConfig
        val contentRoot = config.get(ACFG.contentContextRoot) as? String ?: EdgeRoot.ec
        val landingUrl = "/" + (config.get(ACFG.appContextRoot) as? String ?: EdgeRoot.ew)
        val appUrl = "/" + ContextRoot.wa
        val loginUrl = "/$contentRoot${EDGEP.loginPage}?${EnvAuthReturn.param}=" +
            java.net.URLEncoder.encode(landingUrl, Charsets.UTF_8)
        return listOf(
            uiBlockOverlay(HMENU.block, origin = name) {
                items(HFLD.menu) {
                    menuItem(
                        EDGEUI.openAppItem, "Open application",
                        UiCall(HACT.openPath, listOf(appUrl)),
                        displayOrder = EDGEUI.openAppOrder,
                    )
                    menuItem(
                        EDGEUI.loginItem, "Log in",
                        UiCall(HACT.openPath, listOf(loginUrl)),
                        cfactExpression = CFACTS.anonymous,
                        displayOrder = EDGEUI.loginOrder,
                    )
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
