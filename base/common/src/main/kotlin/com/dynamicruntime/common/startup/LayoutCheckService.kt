package com.dynamicruntime.common.startup

import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.schema.LayoutPullHit
import com.dynamicruntime.common.util.resolveFragment

/**
 * The boot check that a layout's backend `%{@t(...)}` fragment pulls **resolve** (issue #620) -- the half
 * `SchemaService.checkLayouts` cannot do.
 *
 * It lives in its own **regular-phase** service for one reason: the check needs both the compiled layouts
 * (`SchemaService`, a startup-phase service) and the fragment registry (`MarkdownFragmentService`, a regular
 * service registered later), and the two are only both available in the regular phase. `SchemaService` cannot
 * reach the fragment registry from its own `checkInit` -- it runs first, and the reverse dependency would cycle
 * the content and schema layers. This is the same seam `WorkflowService` uses to validate workflow **label**
 * pulls; a layout copy pull is the same kind of check, so it gets the same kind of home rather than being bolted
 * onto the workflow service.
 *
 * The store iteration and the per-pull rules stay in the schema layer (`SchemaService.checkLayoutPulls` +
 * `layoutPullProblems`, a pure function of a resolver lambda); this service supplies only the one thing that
 * needs the registry -- whether a `(fileId, namespace.key)` resolves as a backend pull for a client -- and
 * judges each problem under the check mode of the config holding the layout (issue #841): a refusal to start for
 * source config outside production, and for stored config only in unit tests. Forgiven, it drops nothing -- delivery
 * already renders an unresolved pull as written, with a warning.
 */
class LayoutCheckService : ServiceInitializer {
    override val serviceName: String = LayoutCheckService.serviceName

    private var isInit = false

    override fun checkInit(cxt: KdrCxt) {
        if (isInit) {
            return
        }
        // Both peers first, which the service contract makes safe: the startup-phase schema (already compiled)
        // and the fragment registry (whose own boot check validates the files these pulls name).
        val schema = SchemaService.get(cxt).also { it.checkInit(cxt) }
        val fragments = MarkdownFragmentService.get(cxt).also { it.checkInit(cxt) }

        val faults = schema.layoutPullFaults { client, fileId, nsKey ->
            val effective = fragments.effectiveFragmentsFor(cxt, fileId, client)
            LayoutPullHit(
                fileFound = effective?.found == true,
                backend = effective?.audience == FragmentAudience.backend,
                keyPresent = effective?.content?.resolveFragment(nsKey) != null,
            )
        }
        // Each judged under the check mode of the config holding the layout (issue #841): refused for source
        // config outside production, forgiven for stored config outside unit tests. Forgiving drops nothing,
        // because delivery already degrades an unresolved pull to its copy as written, with a warning -- the
        // smallest drop there is.
        val collector = SchemaCollector.get(cxt)
        val issues = mutableListOf<GedraConfigIssue>()
        val degradedTo = "Keeping the layout; the pull renders as written at delivery, with a warning."
        for (fault in faults) {
            val client = fault.client ?: GID.globalClient
            val holder = collector?.gedraConfigs?.contributorOf(client, fault.typeName)
            reportConfigProblem(
                cxt,
                holder?.issue(fault.message, degradedTo, GCEL.type, fault.typeName)
                    ?: GedraConfigIssue(
                        fault.message, degradedTo, client, elementKind = GCEL.type, elementId = fault.typeName,
                    ),
                issues,
            )
        }
        isInit = true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "LayoutCheckService"

        fun get(cxt: KdrCxt): LayoutCheckService =
            cxt.instanceConfig.get(serviceName) as? LayoutCheckService ?: LayoutCheckService()
    }
}

/** One unresolvable layout pull (issue #841): the client whose layout it is (null for global's), the type, and why. */
class LayoutPullFault(val client: String?, val typeName: String, val message: String)
