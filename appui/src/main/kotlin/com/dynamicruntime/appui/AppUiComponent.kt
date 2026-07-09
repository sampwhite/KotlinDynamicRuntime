package com.dynamicruntime.appui

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The `appui` module's component. It contributes no schema and one regular service, [AppUiService], which
 * registers itself with the request dispatcher as a content server (serving the self-contained webapp under
 * the `app`-focus context root). Modeled on the base components and the `sample` module's component: a thin
 * wiring shell that the launcher registers before boot.
 */
class AppUiComponent : ComponentDefinition {
    override val componentName: String = "appui"

    /** The webapp host, which registers itself with the dispatcher as a content server during init. */
    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::AppUiService)
}
