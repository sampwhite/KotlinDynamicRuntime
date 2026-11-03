@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.workflow.ULH
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.gedra.workflow.CFD as ComputeFacts

/**
 * The **facts reference** (issue #1561): what a cfact is, where one comes from, the places a client's configuration
 * tests one, and the syntax of a test -- which is not the `${…}` expression language -- then every fact `base:common`
 * declares, by group. A Markdown document served on the Documents page to anyone, signed in or not, and readable by the
 * assistant's `read_doc`.
 *
 * The list is made from the declarations themselves: [addCommonCFacts] run into a collector of its own, which is the
 * same call `CommonComponent` declares them with, so a fact declared beside the others is listed with its description
 * and whether it reaches the page. Depends on nothing but code, as a generated document must -- which is why a fact an
 * optional component declares (the assistant's) is named in the prose rather than listed: it is not on every node.
 *
 * The live counterpart is `renderCFactReference`, an operator's view of every fact one client knows, with whether each
 * is present for them now. A client's own facts are its configuration, and are listed only there.
 *
 * One Markdown literal, as the other generated references are: `$${…}` substitutes, and a single `${…}` is the page's
 * own text. It sits at the margin because `trimIndent` runs after substitution, where a table's lines would defeat it.
 */
fun factsMarkdown(): String =
    // language=Markdown
    $$"""# Facts

A **fact** is a yes-or-no statement about the request, a task, or a form: the caller is signed in, a task is the next
one to do, a form's core details are complete. A fact is present or absent, and never holds a value. A client's configuration
tests facts to decide who sees a menu entry or a field, who may save a task, and which forms a workflow takes. This
page says where facts come from and how a test is written, and lists the facts the platform declares. The list is made
from the declarations themselves.

## Where facts come from

- **The request.** For every request, the platform works out facts about the caller and the node: whether the caller
  is signed in, how far their administration reaches, and whether the node is a test instance.
- **A task.** As a task is shown, the platform works out facts about it, such as whether it is the one to do next
  (`$${WFC.isCta}`). A task's own functions add facts about the person looking at it: `$${ULH.fn}` gives
  `$${WFC.reviewer}` to a user with the label it names.
- **A form.** Facts about a form are kept with it and worked out again whenever it is saved: whether its core details
  are complete, what the core details workflow's functions concluded from its data, and what its workflows say about
  it, such as `$${WSC.finished}`.
- **A workflow.** While a form is in a normal workflow, the workflow has facts of its own about it: what the workflow's
  functions concluded from the form's data, and the fact of each approval a reviewer gave.

A client can declare facts of its own, in the `$${CCT.cfactDef}` slot. A declaration only says that a fact exists, so
that a test may name it. Something then has to set it:

- **A `$${ComputeFacts.fn}` function** reads one field and gives the fact when the field's value is one it maps. In the
  core details workflow, the fact is the form's; in a normal workflow, it is the workflow's.
- **An approval task** names, in its `$${WFD.approval}`, the fact a reviewer's approval gives.

| Key | What it means |
|---|---|
| `$${CCT.name}` | The name a test writes. A client's own is a bare name, such as `isWorkshop`; the platform's begin with `kdr:`. |
| `$${CCT.group}` | A heading the fact is listed under, for people. |
| `$${CCT.description}` | What makes the fact true. |
| `$${CCT.toFrontend}` | Whether the fact is sent to the browser with the page. Off unless set. |

See [Workshop logistics](../demo-client/workshop-logistics.md#a-computed-fact) for a fact the core details workflow
computes, and [Committee review](../demo-client/committee-review.md) for one an approval gives.

## Where you write a test

What a test can see depends on where it is written.

$${placesTable()}

## How a test is written

A test is not an [expression](expressions.md). It has no `${` and `}`, and no values to compare: it names facts, and
joins them.

| Write | Means |
|---|---|
| `$${CFACTS.loggedIn}` | The fact is present. |
| `a$${CFACT.and}b` | Both: every part is present. |
| `a\$${CFACT.or}b` | Either: at least one part is present. |
| `$${CFACT.not}a` | Not: the part is absent. |
| `(a$${CFACT.and}b)\$${CFACT.or}c` | Grouping. |
| `$${CFACT.alwaysName}` | Always true. |
| `$${CFACT.neverName}` | Never true. |

- **`$${CFACT.and}` and `$${CFACT.or}` are not mixed without parentheses.** A test such as `a$${CFACT.and}b$${CFACT.or}c` is refused, since
  a reader would have to know which joins tighter. Write `(a$${CFACT.and}b)$${CFACT.or}c` or `a$${CFACT.and}(b$${CFACT.or}c)`.
- **Every name must be declared.** A test that names a fact nobody declared is refused when its configuration is
  checked, so a misspelling is caught at once. Otherwise `$${CFACT.not}kdr:admn` would always be true, and show something
  to everyone.
- **Prefer the positive form.** `$${CFACTS.anonymous}` says what is meant, where `$${CFACT.not}$${CFACTS.loggedIn}` says it
  by exclusion.
- **Leaving a test out means always,** where a test may be left out. An empty test is refused; write `$${CFACT.alwaysName}`
  to say so.
- **`$${CFACT.neverName}` takes something away.** A client hides one of the platform's menu entries by giving it `$${CFACT.neverName}`,
  and brings one back that a layer beneath turned off by giving it `$${CFACT.alwaysName}`.

## The platform's facts

Every deployment declares these. The last column says whether a fact is sent to the browser with the page. A field's
`$${SCH.visibleWhen}` may name only a fact that is, and sees only the request's facts among them, so the caller's facts
marked Yes are the ones it can test.

A component a deployment adds can declare facts under a name of its own, as the workflow assistant declares
`$${ASST.onCFact}`. The **Client facts** page, for a client's operators and administrators, lists every fact the client
knows, its own included, with whether each is present for them now.

$${factSections()}
"""

/** Each place a test is written: what it decides there, and which facts it sees. */
private fun placesTable(): String {
    val same = "The same as `${WFD.saveWhen}`."
    val formAndWorkflow = "The form's, and the workflow's own."
    val rows = listOf(
        Triple("A menu entry's `${UIB.cfactExpression}`", "Whether the entry is shown.", "The request's: the caller and the node."),
        Triple(
            "A field's `${SCH.visibleWhen}`, in its schema",
            "Whether the field is on the form. A write that would change a field kept off is refused.",
            "The request's, and only those sent to the browser.",
        ),
        Triple(
            "A task's `${WFD.saveWhen}`", "Who may save the task. Without one, anyone who can see the form may.",
            "The request's, the task's, and those the task's functions give the person looking.",
        ),
        Triple(
            "A `${UIB.cfactExpression}` in a task's `${WFD.display}`",
            "Which of the task's displays is shown: the first whose test passes.", same,
        ),
        Triple(
            "A `${WFD.test}` in a workflow's `${WFD.eligibility}`", "Whether a form may enter the workflow.",
            "The form's alone, never the caller's, so the answer can be kept with the form.",
        ),
        Triple(
            "A `${WFD.kWhen}` in a workflow's `${WFD.singletons}`", "Whether the workflow gives the form the rule's fact.",
            formAndWorkflow,
        ),
        Triple("A `${WFD.kWhen}` in a workflow's `${WFD.locks}`", "Whether the lock holds.", formAndWorkflow),
        Triple(
            "A lock's `${WFD.overrideWhen}`", "Who may change the locked trait anyway.", "The request's, for whoever is writing.",
        ),
    ).map { (where, decides, sees) -> "| $where | $decides | $sees |" }
    return (listOf("| Where | What it decides | The facts it sees |", "|---|---|---|") + rows).joinToString("\n")
}

/** The platform's facts, a section for each group in the order the groups are first declared. */
private fun factSections(): String {
    val defs = SchemaCollector().also { addCommonCFacts(it) }.cfacts.values
    return defs.groupBy { it.group }.entries.joinToString("\n\n") { (group, inGroup) ->
        val rows = inGroup.map { def ->
            "| `${def.name}` | ${def.description.replace("|", "\\|")} | ${if (def.toFrontend) "Yes" else "No"} |"
        }
        (listOf("### $group", "", "| Fact | What makes it true | Reaches the page |", "|---|---|---|") + rows).joinToString("\n")
    }
}
