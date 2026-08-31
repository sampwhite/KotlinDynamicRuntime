package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.SECT

/** Path and response field names for the cfact reference endpoint (issues #455, #488). */
@Suppress("ConstPropertyName")
object CFD {
    /**
     * The cfacts reference endpoint's path. In the kernel beside the field names for the reason #393 moved the
     * gedra paths there: the page that presents this is built from the same string the backend serves it
     * under, and a path spelled twice is a rename that empties a page.
     *
     * The `clientOperator` section (issue #488), moved down from `clientAdmin`. Reading what a cfact means --
     * and what it currently evaluates to -- is what somebody keeping a *client's* deployment running does, not
     * only a config author, so it belongs a rung below admin: `clientOperator` admits an operator **or** an
     * admin, still confined by scope, which is the confinement this needs since one client must not read what
     * another declared. The bare `operator` section would be wrong for the opposite reason `clientAdmin` was:
     * it is for somebody running the *deployment* and takes `allClients`, which a client's own operator lacks.
     */
    const val cfactsPath = "/${SECT.clientOperator}/cfacts"

    /** The one field of the [CFactDef] reference's response: the assembled Markdown document. */
    const val markdown = "markdown"

    /** Schema type name for the reference response ({ [markdown] }). */
    const val referenceType = "CFactReference"
}

/**
 * One declared cfact: the name an expression may write, the group it presents under, and what makes it
 * true (issue #455).
 *
 * **Declaring is what makes a name writable.** [CFactParser] refuses any name outside the registered set,
 * so the registry is not documentation that happens to exist -- it is the thing that turns a typo into a
 * refused boot instead of a menu item shown to everybody (see [CFactNot] for why a mistyped negation is the
 * dangerous direction).
 *
 * **Only components and clients declare, never an activity.** A client declares up front whatever a workflow
 * it plans to build will need. The cost is a declaration ahead of time, and what it buys is that the known
 * set does not vary by activity: if the registry could grow while something ran, "unknown cfact" would mean
 * either a typo *or* a name belonging to something not loaded here, and the safe answer to that ambiguity is
 * to allow -- which would weaken the check exactly where it matters. Static per deployment and client keeps
 * "unknown" unambiguously wrong.
 */
class CFactDef(
    /**
     * The name an expression writes. Not namespaced by [group]: an author should not have to write the group
     * every time, and two things called `active` that mean different things is a modeling problem better
     * refused than silently namespaced apart.
     *
     * **Unique within a scope, not across clients.** A component's name is refused a second declaration, and
     * a client may not take a name a component declared -- but two clients may each declare `underAudit` and
     * mean different things by it, and that is allowed on purpose rather than by omission.
     *
     * Refusing it would make one client's boot fail because of what *another* client declared, which leaks:
     * a client could learn what its neighbors are building by trying names until one was refused. The two
     * registries cannot see each other, so nothing is ambiguous at the point an expression is parsed, and the
     * cost is paid only later -- a name promoted to a component-level declaration collides with every client
     * already using it. The mature answer is an operator report of the conflicts that exist, so we can
     * suggest a rename to the client concerned; refusing at boot is not it. The same reasoning a trait id
     * gets, and for the same reason.
     */
    val name: String,
    /**
     * The thematic group this presents under -- a **friendly label**, metadata only.
     *
     * It has no effect on names, on matching, or on validation. It exists so a page listing what a deployment
     * knows can be read in sections rather than as one alphabetical wall.
     */
    val group: String,
    /**
     * What makes this cfact **true** -- not what it means.
     *
     * Somebody writing an expression is trying to predict when it fires, so "true when the caller holds
     * `admin`, or `operator` by the ladder" is useful where "admin-ness" is not. This is the difference
     * between a discovery endpoint people use and one they read once.
     */
    val description: String,
) {
    init {
        if (!isCFactName(name)) {
            throw KdrException.mkConv(
                "'$name' cannot be a cfact name. A name is one or more letters, digits, '_' or '.', which is " +
                    "exactly what an expression can spell -- a name an expression cannot write could never be " +
                    "referred to. (A leading '${CFACT.literal}' marks a literal such as ${CFACT.alwaysName}, " +
                    "which is not a cfact at all.)",
            )
        }
    }

    override fun toString(): String = name
}
