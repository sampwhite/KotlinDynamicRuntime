package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CHKL
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.UADEP
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

/** A new client's checklist on Home (issue #1639): what the browser remembers of it. */
@Suppress("ConstPropertyName")
object CHKV {
    /** The `localStorage` key, before the client's id, under which "Hide this checklist" is remembered. */
    const val hiddenKeyPrefix = "kdr.home.checklistHidden."
}

/** The checklist's read (issue #1639): whose it is -- the sandbox's parent from inside it -- and each step's id and state. */
class ChecklistRead(val client: String, val steps: List<Pair<String, Boolean>>)

/**
 * `/clientAdmin/client/checklist`'s answer as a [ChecklistRead] (issue #1639), or null when it names no client. A step
 * with no id is left out. Pure, and covered under `jsNodeTest`.
 */
fun parseChecklistRead(item: Map<String, Any?>): ChecklistRead? {
    val client = item[CLD.client].toOptStr() ?: return null
    val steps = item[CHKL.steps].toJsonListOfMaps().mapNotNull { step ->
        val id = step[CHKL.id].toOptStr() ?: return@mapNotNull null
        id to (step[CHKL.done] == true)
    }
    return ChecklistRead(client, steps)
}

/** One step as the card lists it: its words, whether it is done, and the page where it is done. */
class ChecklistStep(val id: String, val label: String, val done: Boolean, val route: List<Pair<String, String>>)

/**
 * The words and the page for each step the backend names (issue #1639), for [client]: null for an id the page does not
 * know, which is left out rather than shown by its id.
 */
fun checklistStepOf(id: String, done: Boolean, client: String): ChecklistStep? {
    // Workflows, the New form among them, are made and changed in the client page's Forms and data panel.
    val workflows = listOf(HP.page to HMENU.pageClients, HP.client to client, HP.section to panelFormsData)
    return when (id) {
        CHKL.newForm -> ChecklistStep(id, "Make the New form your own", done, workflows)
        CHKL.workflow -> ChecklistStep(id, "Add a workflow of your own", done, workflows)
        CHKL.invite -> ChecklistStep(id, "Invite someone to your client", done, listOf(HP.page to HMENU.pageUsers))
        CHKL.firstForm -> ChecklistStep(id, "Fill in a first form", done, listOf(HP.page to HMENU.pageNewForm))
        else -> null
    }
}

/**
 * The steps the card lists (issue #1639): the backend's ([read]), led by switching on the workflow assistant when the
 * menu offers it ([assistantOffered]) and its status ([assistantView]) says it is on, or off and the person may switch it
 * on -- since it helps with the steps after it. Left out while the status is unread, and where only an operator switches
 * it on, which the person cannot do. Pure, and covered under `jsNodeTest`.
 */
fun checklistSteps(read: ChecklistRead, assistantOffered: Boolean, assistantView: AssistantView?): List<ChecklistStep> {
    val assistant = if (assistantOffered && (assistantView == AssistantView.on || assistantView == AssistantView.switchOn)) {
        ChecklistStep(CHKL.assistant, "Switch on the workflow assistant", assistantView == AssistantView.on, listOf(HP.page to ASST.page))
    } else {
        null
    }
    return listOfNotNull(assistant) + read.steps.mapNotNull { (id, done) -> checklistStepOf(id, done, read.client) }
}

/** The `localStorage` key under which "Hide this checklist" is remembered for [client]. */
fun checklistHiddenKey(client: String): String = CHKV.hiddenKeyPrefix + client

/** Whether this browser was told to hide [client]'s checklist. */
fun checklistHiddenHere(client: String): Boolean = localStorageGet(checklistHiddenKey(client), "the checklist's hide choice") == "true"

/**
 * The new client's checklist, for Home's card (issue #1639), or null while it is read, when it cannot be, or when no card
 * in [cards] asks for it -- then nothing is read ([useCardRead]). Read again on every refresh, so a step done elsewhere
 * is checked off.
 */
fun useClientChecklist(cards: List<HomeCard>): ChecklistRead? =
    useCardRead(cards.any { it.kind == HCARD.kindNewClientChecklist }, null, "the client's checklist") {
        parseChecklistRead(Http.getApi(UADEP.clientChecklist)[EP.item].toJsonMapOrEmpty())
    }

/** The checklist card's body (issue #1639): each step, checked when done and linked when not, then "Hide this checklist". */
fun ChildrenBuilder.checklistCardBody(steps: List<ChecklistStep>, onHide: () -> Unit) {
    ul {
        className = ClassName("home-checklist")
        for (step in steps) {
            li {
                key = step.id.unsafeCast<Key>()
                className = ClassName(if (step.done) "done" else "todo")
                span {
                    className = ClassName("home-checklist-mark")
                    +(if (step.done) "✓" else "○")
                }
                if (step.done) {
                    span { +step.label }
                } else {
                    a {
                        href = routeHref(step.route)
                        +step.label
                    }
                }
            }
        }
    }
    Button {
        type = "link"
        size = "small"
        onClick = { onHide() }
        +"Hide this checklist"
    }
}
