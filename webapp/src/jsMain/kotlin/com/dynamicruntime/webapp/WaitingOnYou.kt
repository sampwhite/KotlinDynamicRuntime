package com.dynamicruntime.webapp

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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.Key
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useState
import web.cssom.ClassName

private val waitingScope = MainScope()

/** How many forms the "Waiting on you" card lists before it links to the rest (issue #1637). */
const val waitingShownForms = 5

/** One step waiting on the caller in a form (issue #1637): its workflow, its task, and what it asks of them in words. */
class WaitingStep(val workflowId: String, val workflowLabel: String, val taskId: String, val actionText: String)

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
            )
        }
        if (steps.isEmpty()) return@mapNotNull null
        val summary = summarizeForm(item, null)
        WaitingForm(summary.gedraId, formHeading(summary, columns, env) ?: "A form", steps)
    }
    return WaitingRead((resp[EP.numAvailable] as? Number)?.toInt() ?: forms.size, forms)
}

/** The forms listing narrowed to what waits on the caller (issue #1637): the card's "see them all". */
fun waitingListingRoute(): List<Pair<String, String>> = listOf(HP.page to HMENU.pageForms, GDF.needsMe to "true")

/**
 * The cards Home draws (issue #1637): all of [cards] but "Waiting on you" while nothing is known to wait -- before its
 * read answers, when it cannot be read, or when it found nothing -- so neither the card nor a link to it shows empty.
 * Pure, and covered under `jsNodeTest`.
 */
fun visibleHomeCards(cards: List<HomeCard>, waiting: WaitingRead?): List<HomeCard> =
    cards.filter { it.kind != HCARD.kindWaitingOnYou || (waiting?.total ?: 0) > 0 }

/**
 * What waits on the caller, for Home's card (issue #1637), or null while it is read, when it cannot be, or when no card
 * in [cards] asks for it -- then nothing is read. Read again on every refresh, so a step taken elsewhere leaves it.
 */
fun useWaitingOnYou(cards: List<HomeCard>): WaitingRead? {
    val shown = cards.any { it.kind == HCARD.kindWaitingOnYou }
    val generation = useRefreshGeneration()
    var read by useState<WaitingRead?>(null)
    useEffect(shown, generation) {
        if (!shown) {
            read = null
            return@useEffect
        }
        waitingScope.launch {
            val result = apiResult {
                Http.getApi(GEP.formDocs + queryString(mapOf(GDF.needsMe to true, EP.limit to waitingShownForms)))
            }
            result.failureOrNull()?.let { console.warn("$errorLogPrefix could not read what waits on you for Home: ${it.message}") }
            read = result.valueOrNull()?.let { parseWaitingRead(it, DisplayZone.scriptEnv()) }
        }
    }
    return read
}

/** The "Waiting on you" card's body (issue #1637): each form, linked to its step, then a link to all of them. */
fun ChildrenBuilder.waitingCardBody(read: WaitingRead) {
    ul {
        className = ClassName("home-waiting")
        for (form in read.forms) {
            val step = form.steps.first()
            li {
                key = form.gedraId.unsafeCast<Key>()
                // The form's current step, ready to take, as the forms listing's workflow cell opens it.
                a {
                    href = routeHref(workflowPageRoute(form.gedraId, step.workflowId, step.taskId, edit = true))
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
            href = routeHref(waitingListingRoute())
            +"See all ${read.total}"
        }
    }
}
