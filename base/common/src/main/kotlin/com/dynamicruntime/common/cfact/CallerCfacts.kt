package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.SchemaService

/**
 * The cfacts that hold for one request (issue #1793): assembled once against the caller's own client's registry, and
 * read by everything one response decides on them -- the menu and the home cards, and what the frontend is sent. Made
 * only by [assemble], and tied to the request it was assembled for ([isFor]), so a set worked out for another client or
 * another request cannot be handed to a check in its place.
 *
 * The client's registry serves a block merged for everybody as well: that block's expressions name only global
 * cfacts, which the client's registry has and assembles alike.
 */
class CallerCfacts private constructor(
    private val cxt: KdrCxt,
    /** The registry they were assembled against: the caller's own client's. */
    val registry: CFactRegistry,
    /** The cfacts present, as [CFactRegistry.assemble] gives them. */
    val present: Set<String>,
) {
    /** Whether these are the cfacts of [cxt]'s request: assembled for that very context. */
    fun isFor(cxt: KdrCxt): Boolean = this.cxt === cxt

    /**
     * What a page's conditions are judged on in the browser (issue #1793): each of the registry's [pageCfactNames] to
     * whether it is present. The whole such vocabulary, so a condition naming an absent one still parses.
     */
    val forPages: Map<String, Boolean> get() = registry.pageCfactNames().associateWith { it in present }

    companion object {
        /** The cfacts of [cxt]'s request, about one thing being rendered too when given [targetFacts]. */
        fun assemble(cxt: KdrCxt, targetFacts: Set<String> = emptySet()): CallerCfacts {
            val registry = SchemaService.get(cxt).cfactsFor(cxt.client)
            return CallerCfacts(cxt, registry, registry.assemble(cxt, targetFacts))
        }
    }
}

/**
 * The cfacts a page's condition may name (issue #1793): those sent to the frontend that something computes from the
 * request. A page is drawn for a caller, not a form, so a fact about a form -- a client's own, or a workflow's state --
 * never holds there, even where it is sent for a form's own page.
 */
fun CFactRegistry.pageCfactNames(): Set<String> =
    defs.values.filter { it.toFrontend && it.name in requestNames }.mapTo(LinkedHashSet()) { it.name }
