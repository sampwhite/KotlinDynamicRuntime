package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.StateSetter
import react.dom.html.ReactHTML.p
import react.useState
import web.cssom.ClassName

/**
 * Where a section's per-row actions stand (issue #1388): which control is running ([busyKey], [webAssetBusyKey]'s
 * `<row>/<action>`, so the control that was used is the one that shows it), which row's confirmation is open, and where
 * the last failure was -- an error from the backend ([failure]) or a problem the page found itself without sending
 * anything ([problem]), each shown beside its row. The web asset and file ref sections share it.
 *
 * One value, changed only by the transitions below, so a step is never half made: starting an action clears the last
 * failure with it, and a failure or a refusal replaces the other kind. Pure, and covered under `jsNodeTest`.
 */
class RowActionStatus(
    val busyKey: String? = null,
    val confirming: String? = null,
    val failedAt: String? = null,
    val failure: DisplayError? = null,
    val problem: String? = null,
) {
    /** Whether any control is running, so nothing else is started beside it. */
    val running: Boolean get() = busyKey != null

    /** Whether [action] of row [at] is the one running. */
    fun isBusy(at: String, action: String): Boolean = busyKey == webAssetBusyKey(at, action)

    /** Whether something other than [action] of row [at] is running: that control is disabled, not shown busy. */
    fun othersBusy(at: String, action: String): Boolean = running && !isBusy(at, action)

    /** The backend's refusal of the last action, when it was row [at]'s. */
    fun failureAt(at: String): DisplayError? = failure.takeIf { failedAt == at }

    /** The page's own objection to the last action, when it was row [at]'s. */
    fun problemAt(at: String): String? = problem.takeIf { failedAt == at }

    /** [action] of row [at] started: it is the one running, and the last failure is gone. */
    fun started(at: String, action: String): RowActionStatus = RowActionStatus(webAssetBusyKey(at, action), confirming)

    /** The running action ended, whatever came of it. */
    fun finished(): RowActionStatus = RowActionStatus(null, confirming, failedAt, failure, problem)

    /** Row [at]'s action was refused by the backend with [error]. */
    fun failed(at: String, error: DisplayError): RowActionStatus = RowActionStatus(busyKey, confirming, at, error, null)

    /** Row [at]'s action was not sent: the page found [text] wrong with it by itself. */
    fun refused(at: String, text: String): RowActionStatus = RowActionStatus(busyKey, confirming, at, null, text)

    /** Row [at]'s confirmation is open, or none is for null: one question at a time. */
    fun confirming(at: String?): RowActionStatus = RowActionStatus(busyKey, at, failedAt, failure, problem)

    companion object {
        /** Nothing running, asked, or failed: where a section starts, and where it returns for another client. */
        val cleared = RowActionStatus()
    }
}

/**
 * A section's [RowActionStatus], held in one state (issue #1388). Every change goes through the state's transform, not
 * over [status] as last drawn: an action ends after an await, and a value captured when it started would put back what
 * changed in between -- its own failure, set a moment before it finished.
 */
class RowActions(val status: RowActionStatus, private val set: StateSetter<RowActionStatus>) {
    private fun change(step: (RowActionStatus) -> RowActionStatus) = set { step(it) }

    fun start(at: String, action: String) = change { it.started(at, action) }

    fun finish() = change { it.finished() }

    /**
     * Row [at]'s action failed with [e], worded as every editor's save words one ([saveFailure]): a save that did not take
     * effect as the page said it would also has the page [reread].
     */
    fun fail(at: String, e: Throwable, reread: () -> Unit) {
        val shown = saveFailure(e, reread)
        change { it.failed(at, shown) }
    }

    fun refuse(at: String, text: String) = change { it.refused(at, text) }

    fun confirm(at: String?) = change { it.confirming(at) }

    fun clear() = set(RowActionStatus.cleared)
}

/** The per-row action status of a section (issue #1388): see [RowActions]. */
fun useRowActions(): RowActions {
    val (status, set) = useState(RowActionStatus.cleared)
    return RowActions(status, set)
}

/**
 * Row [at]'s **Remove** (issue #1388): a `danger` button behind a confirmation that asks [question] and says
 * [consequence] -- which names where it acts, as every delete's does (issue #1049) -- whose open question is
 * [rows]' one. Confirmed, it closes and calls [onConfirm]; the button shows [action] running, and is disabled while
 * another control's is.
 */
internal fun ChildrenBuilder.confirmedRemove(
    rows: RowActions,
    at: String,
    action: String,
    question: String,
    consequence: String,
    onConfirm: () -> Unit,
) {
    val status = rows.status
    Popconfirm {
        title = question
        description = consequence
        okText = "Remove"
        cancelText = "Cancel"
        placement = "bottomLeft"
        open = status.confirming == at
        onOpenChange = { open -> rows.confirm(if (open) at else null) }
        this.onConfirm = {
            rows.confirm(null)
            onConfirm()
        }
        okButtonProps = js("({ danger: true })")
        disabled = status.running
        Button {
            danger = true
            loading = status.isBusy(at, action)
            disabled = status.othersBusy(at, action)
            +"Remove"
        }
    }
}

/** What went wrong with row [at]'s last action, beside it: the page's own objection, or the backend's refusal under [lead]. */
internal fun ChildrenBuilder.rowFailure(status: RowActionStatus, at: String, lead: String) {
    status.problemAt(at)?.let {
        p {
            className = ClassName("error-text")
            +it
        }
    }
    status.failureAt(at)?.let { errorText(lead, it) }
}
