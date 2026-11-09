package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.ListingColumn
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import react.ChildrenBuilder
import react.Key
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import web.cssom.ClassName

/** "Waiting on you"'s sizes (issue #1637). */
@Suppress("ConstPropertyName")
object WAIT {
    /** How many forms the card lists before it links to the rest. */
    const val shownForms = 5
}

/** One step waiting on the caller in a form (issue #1637): its workflow, its task, and what it asks of them in words. */
class WaitingStep(
    val workflowId: String,
    val workflowLabel: String,
    val taskId: String,
    val actionText: String,
    /** A workflow's Start (issue #1662): taking it engages the form first. */
    val starts: Boolean = false,
)

/** One form with steps waiting on the caller: its id, what it is called, and the steps. */
class WaitingForm(val gedraId: String, val heading: String, val steps: List<WaitingStep>)

/** What waits on the caller (issue #1637): how many forms in all, and the first few. */
class WaitingRead(val total: Int, val forms: List<WaitingForm>)

/**
 * The forms listing's answer to `needsMe` as a [WaitingRead] (issue #1637): each row named as the listing names it
 * ([formHeading], through the columns the answer carries, read in [env]) -- "A form" when it has no name -- with its
 * `waitingSteps`. A row with no step, or a step with no workflow or task, is left out. Pure, and covered under
 * `jsNodeTest`.
 */
fun parseWaitingRead(resp: Map<String, Any?>, env: ScriptEnv): WaitingRead {
    val columns = ListingColumn.listFrom(resp[EP.summary].toJsonMapOrEmpty()[GDF.columns])
    val forms = resp[EP.items].toJsonListOfMaps().mapNotNull { item ->
        val steps = item[GDF.waitingSteps].toJsonListOfMaps().mapNotNull { step ->
            val workflowId = step[WFD.workflowId].toOptStr() ?: return@mapNotNull null
            val taskId = step[WFS.ctaTask].toOptStr() ?: return@mapNotNull null
            WaitingStep(
                workflowId, step[WFD.label].toOptStr() ?: workflowId, taskId,
                step[SWF.actionText].toOptStr() ?: "",
                starts = step[SWF.startsWorkflow] == true,
            )
        }
        if (steps.isEmpty()) return@mapNotNull null
        val summary = summarizeForm(item, null)
        WaitingForm(summary.gedraId, formHeading(summary, columns, env) ?: "A form", steps)
    }
    return WaitingRead((resp[EP.numAvailable] as? Number)?.toInt() ?: forms.size, forms)
}

/**
 * The forms listing narrowed to what waits on the caller (issue #1637): the card's "see them all" -- confined to
 * [client] when given, as the card's read was for a caller who sees across clients.
 */
fun waitingListingRoute(client: String? = null): List<Pair<String, String>> =
    listOf(HP.page to HMENU.pageForms, GDF.needsMe to "true") + listOfNotNull(client?.let { EI.client to it })

/**
 * What waits on the caller, for Home's card (issue #1637), or null while it is read, when it cannot be, or when no card
 * in [cards] asks for it -- then nothing is read ([useCardRead], which says what [decidedAt] is). Read again on every
 * refresh, so a step taken elsewhere leaves it. [client], for a caller who sees across clients, confines the read to their
 * own client: Home asks about where they are, never every form of the deployment.
 */
fun useWaitingOnYou(cards: List<HomeCard>, decidedAt: Int, client: String?): WaitingRead? =
    useCardRead(cards.any { it.kind == HCARD.kindWaitingOnYou }, decidedAt, client, "what waits on you") {
        val query = mapOf(GDF.needsMe to true, EP.limit to WAIT.shownForms) + listOfNotNull(client?.let { EI.client to it })
        parseWaitingRead(Http.getApi(GEP.formDocs + queryString(query)), DisplayZone.scriptEnv())
    }

/**
 * The "Waiting on you" card's body (issue #1637): each form, linked to its step, then a link to all of them -- in
 * [client], when the read was confined to it.
 */
fun ChildrenBuilder.waitingCardBody(read: WaitingRead, client: String?) {
    ul {
        className = ClassName("home-waiting")
        for (form in read.forms) {
            val step = form.steps.first()
            li {
                key = form.gedraId.unsafeCast<Key>()
                // The form's current step, ready to take, as the forms listing's workflow cell opens it.
                a {
                    href = routeHref(workflowPageRoute(form.gedraId, step.workflowId, step.taskId, edit = true, engage = step.starts))
                    +form.heading
                }
                span {
                    className = ClassName("type-hint")
                    +(" — " + form.steps.joinToString("; ") { s -> s.actionText.ifBlank { s.workflowLabel } })
                }
            }
        }
    }
    if (read.total > read.forms.size) {
        a {
            href = routeHref(waitingListingRoute(client))
            +"See all ${read.total}"
        }
    }
}
