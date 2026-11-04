package com.dynamicruntime.common.gedra.workflow

/**
 * The **search keywords** a call to action may carry (issue #1594): what kind of action a workflow's CTA asks for, so
 * the forms list, its search, and reports can single it out. A closed list, since a keyword names a behavior the
 * platform provides -- a verb on a link, a filter, a column -- rather than something a client invents; a task's
 * [WFD.keyword] is held to it. Each keyword's copy (its verb, filter label, column header, report label, and status
 * text) is issue #1596's.
 */
@Suppress("ConstPropertyName")
object CTAK {
    /** The CTA waits on someone to look the form over and act on it. What an approval task carries without saying so. */
    const val review = "review"

    /** Every keyword, in the order a page lists them. */
    val all: List<String> = listOf(review)
}
