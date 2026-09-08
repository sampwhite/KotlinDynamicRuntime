package com.dynamicruntime.common.startup

import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.LayoutPullHit
import com.dynamicruntime.common.schema.SCH
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
 * turns any problem into a refusal to start, the same loud failure every layout boot check makes.
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

        val problems = schema.checkLayoutPulls { client, fileId, nsKey ->
            val effective = fragments.effectiveFragmentsFor(cxt, fileId, client)
            LayoutPullHit(
                fileFound = effective?.found == true,
                backend = effective?.audience == FragmentAudience.backend,
                keyPresent = effective?.content?.resolveFragment(nsKey) != null,
            )
        }
        if (problems.isNotEmpty()) {
            throw KdrException(
                "Refusing to start: ${problems.size} unresolvable '${SCH.layout}' fragment pull(s).\n" +
                    problems.joinToString("\n"),
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
