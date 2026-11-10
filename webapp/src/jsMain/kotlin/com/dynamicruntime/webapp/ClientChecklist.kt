package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CHKL
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.isSelfServeClient
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import react.ChildrenBuilder
import react.Key
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import web.cssom.ClassName

/** A new client's checklist on Home (issue #1639): what the browser remembers of it. */
@Suppress("ConstPropertyName")
object CHKV {
    /** The `localStorage` key, before the client's id, under which hiding the list on Home is remembered. */
    const val hiddenKeyPrefix = "kdr.home.checklistHidden."

    /**
     * What Home's hide says beside it (issue #1757): the list is put away on Home only, and where it can be brought
     * back from, so hiding it never looks like losing it.
     */
    const val hideNote = "It stays on your client's page, where you can bring it back to Home."

    /** What the client page's Getting started panel offers while the list is hidden on Home (issue #1757). */
    const val showOnHome = "Show on Home again"
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
 * on -- since it helps with the steps after it. Left out where only an operator switches it on, which the person cannot
 * do, or its status could not be read. **None at all while an offered assistant's status is unread**, so the list never
 * grows a step after it is drawn, and never looks done before it is. Pure, and covered under `jsNodeTest`.
 */
fun checklistSteps(read: ChecklistRead, assistantOffered: Boolean, assistantView: AssistantView?): List<ChecklistStep> {
    if (assistantOffered && (assistantView == null || assistantView == AssistantView.loading)) return emptyList()
    val assistant = if (assistantOffered && (assistantView == AssistantView.on || assistantView == AssistantView.switchOn)) {
        ChecklistStep(CHKL.assistant, "Switch on the workflow assistant", assistantView == AssistantView.on, listOf(HP.page to ASST.page))
    } else {
        null
    }
    return listOfNotNull(assistant) + read.steps.mapNotNull { (id, done) -> checklistStepOf(id, done, read.client) }
}

/** The `localStorage` key under which hiding [client]'s checklist on Home is remembered. */
fun checklistHiddenKey(client: String): String = CHKV.hiddenKeyPrefix + client

/**
 * Whether this browser put [client]'s checklist away on Home: hidden there, or every step done. Home's alone (issue
 * #1757): the client page's Getting started panel shows while a step is left, whatever this says.
 */
fun checklistHiddenHere(client: String): Boolean = localStorageGet(checklistHiddenKey(client), "the checklist's hide choice") == "true"

/** Brings [client]'s checklist back to Home in this browser (issue #1757): what the client page's panel offers. */
fun showChecklistOnHome(client: String) = localStorageRemove(checklistHiddenKey(client), "the checklist's hide choice")

/**
 * Whether the client page's Getting started panel offers to bring the list back to Home (issue #1757): while it is
 * hidden there ([hiddenOnHome]) and a step is left ([stepsLeft]) -- once every step is done, there is nothing to bring
 * back. Pure, and covered under `jsNodeTest`.
 */
fun showOnHomeOffered(hiddenOnHome: Boolean, stepsLeft: Boolean): Boolean = hiddenOnHome && stepsLeft

/**
 * The new client's checklist, for Home's card (issue #1639), or null while it is read, when it cannot be, or when not
 * [wanted] -- no card asks for it, or this browser put it away -- and then nothing is read ([useCardRead], which says what
 * [decidedAt] is). Read again on every refresh, so a step done elsewhere is checked off.
 */
fun useClientChecklist(wanted: Boolean, decidedAt: Int): ChecklistRead? =
    useCardRead(wanted, decidedAt, null, "the client's checklist") {
        parseChecklistRead(Http.getApi(UADEP.clientChecklist)[EP.item].toJsonMapOrEmpty())
    }

/**
 * The checklist card's body (issue #1639): each step, checked when done and linked when not, then **Hide here**, which
 * says where the list stays and how it comes back ([CHKV.hideNote], issue #1757). The mark is drawn for the eye alone; a
 * screen reader hears "Done" or "To do" before the step instead.
 */
fun ChildrenBuilder.checklistCardBody(steps: List<ChecklistStep>, onHide: () -> Unit) {
    ul {
        className = ClassName("home-checklist")
        for (step in steps) {
            li {
                key = step.id.unsafeCast<Key>()
                className = ClassName(if (step.done) "done" else "todo")
                span {
                    className = ClassName("home-checklist-mark")
                    ariaHidden = true
                    +(if (step.done) "✓" else "○")
                }
                span {
                    className = ClassName("visually-hidden")
                    +(if (step.done) "Done: " else "To do: ")
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
    div {
        className = ClassName("home-checklist-hide")
        Button {
            type = "link"
            size = "small"
            onClick = { onHide() }
            +"Hide here"
        }
        span {
            className = ClassName("type-hint")
            +CHKV.hideNote
        }
    }
}

/**
 * Whether the client page opens [clientId] on what to do next (issue #1700): a self-serve client, or its sandbox, shown to
 * its own administrator. One who sees across clients -- a platform operator -- keeps the page as it is. Pure, and covered
 * under `jsNodeTest`.
 */
fun clientOpensOnNextSteps(clientId: String, acrossClients: Boolean): Boolean =
    !acrossClients && isSelfServeClient(sandboxParentOf(clientId) ?: clientId)

/**
 * One step as the client page's "Getting started" panel lists it (issue #1700): the [step], and where it is done -- a
 * [panel] of the page itself, scrolled to; else a page, the step's own route; or, where neither is right, a [note]
 * saying where, with no link.
 */
class ClientNextStep(val step: ChecklistStep, val panel: String? = null, val note: String? = null)

/**
 * Where each of [steps] is done, from the client page (issue #1700). Its workflow steps lead to the page's own Forms and
 * data panel when it is drawn ([formsDataShown]). Inviting someone and a first form are the client's own users and forms,
 * which a sandbox has apart, so from [inSandbox] they say so rather than lead to the sandbox's. Pure, and covered under
 * `jsNodeTest`.
 */
fun clientNextSteps(steps: List<ChecklistStep>, inSandbox: Boolean, formsDataShown: Boolean): List<ClientNextStep> =
    steps.map { step ->
        when {
            step.id == CHKL.newForm || step.id == CHKL.workflow ->
                if (formsDataShown) ClientNextStep(step, panel = panelFormsData) else ClientNextStep(step)
            inSandbox && (step.id == CHKL.invite || step.id == CHKL.firstForm) ->
                ClientNextStep(step, note = "in your client itself, not its sandbox")
            else -> ClientNextStep(step)
        }
    }

/**
 * The client page's "Getting started" list (issue #1700), as Home's card draws its steps: each checked when done, and
 * linked when not -- to its panel of the page, scrolled to, or to its page -- or followed by where it is done.
 */
fun ChildrenBuilder.clientNextStepsBody(steps: List<ClientNextStep>) {
    ul {
        className = ClassName("home-checklist")
        for (next in steps) {
            val step = next.step
            li {
                key = step.id.unsafeCast<Key>()
                className = ClassName(if (step.done) "done" else "todo")
                span {
                    className = ClassName("home-checklist-mark")
                    ariaHidden = true
                    +(if (step.done) "✓" else "○")
                }
                span {
                    className = ClassName("visually-hidden")
                    +(if (step.done) "Done: " else "To do: ")
                }
                val panel = next.panel
                val note = next.note
                when {
                    step.done -> span { +step.label }
                    panel != null -> panelLink(panel, step.label)
                    note != null -> span { +"${step.label} ($note)" }
                    else -> a {
                        href = routeHref(step.route)
                        +step.label
                    }
                }
            }
        }
    }
}
