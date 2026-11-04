@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.gedra.CCT

/**
 * The **report paths reference** (issue #1563): how a report column's path is written -- its sources, a keyed trait's
 * selectors, `[*]`, and an entry's envelope after `@` -- then every name a path may end in outside a trait's own data,
 * source by source, with its kind and what it holds, and how a column makes a path's several values one. A Markdown
 * document served on the Documents page to anyone, signed in or not, and readable by the assistant's `read_doc`.
 *
 * The grammar is prose, stated from [ReportPath]'s KDoc and the binding rules in [bindReportPath]; the tables are made
 * from the vocabulary the parser reads ([RWF], [RUSR], [RMETA], [RENV]) and from [ReportCombine], so a name cannot be
 * added without appearing here with its description.
 *
 * One Markdown literal, as the other generated references are: `$${…}` substitutes, and the page's own text needs no
 * escape. It sits at the margin because `trimIndent` runs after substitution, where a table's lines would defeat it.
 */
fun reportPathsMarkdown(): String =
    // language=Markdown
    $$"""# Report paths

Each column of a report takes its value from somewhere on a form, named by its `$${RDEF.path}`: a field of one of the
form's traits, where the form stands with a workflow, something about the person who owns it, or a fact about the form
itself. This page describes how a path is written, lists every name a path can end in outside a trait's own data, and
says how a column makes several values one. The lists are made from the names the path parser reads.

A report is declared in the `$${CCT.reportDef}` slot of a client's configuration. Its `$${RDEF.groupBy}` and
`$${RDEF.excludeEmpty}` name columns by their `$${RDEF.columnId}`, not by path.

## The shape of a path

```
form.<traitId>.<field>.<field>...
form.<traitId>[<selector>].<field>...
form.<traitId>.@<envelopeField>
workflow.<workflowId>.<attribute>
workflow.<workflowId>.approval[<taskId>].<attribute>
user.<attribute>
meta.<attribute>
```

The first part is the **source**, and what follows depends on it.

| Source | Reads | Example |
|---|---|---|
| `$${ReportSource.form.name}` | A field of one of the form's traits, or a field of a trait entry's envelope. | `form.session.track` |
| `$${ReportSource.workflow.name}` | Where the form stands with one workflow, or one of its approvals. | `workflow.review.category` |
| `$${ReportSource.user.name}` | The account of the person who owns the form. | `user.name` |
| `$${ReportSource.meta.name}` | The form itself: its id, its dates, its status. | `meta.formStatus` |

## A trait's data: `$${ReportSource.form.name}`

- **The trait's id comes first,** as the configuration has it. A client's own trait has a bare id, such as `session`;
  one the platform ships keeps its full id, such as `kdr:name`.
- **Then the fields of its data,** separated by dots, as deep as the data goes: `form.abstract.coSpeakers.name`. A path
  must end at a value, not at an object or a list of objects.
- **A list has several values.** A path through a list in the data reads every item, and the column's
  `$${RDEF.combine}` makes them one (see [Several values](#several-values)).
- **The value's kind comes from the field's schema:** text, a number, a date, or yes or no. A field with choices shows
  its label in the report's table, and its stored value in a downloaded file.

### Keyed traits

A keyed trait has an entry per key, such as one per year, so its path names which entries it reads with a selector in
square brackets after the trait's id. An unkeyed trait has one entry and takes no selector.

| Write | Reads |
|---|---|
| `form.yearly[*].revenue` | Every entry, so the path has several values. |
| `form.yearly[2024].revenue` | The entry whose key is 2024. A key of several fields gives each, in the key's order: `[2024,Q1]`. |
| `form.quarterly[year=2024].revenue` | The entries whose named key fields have these values. Naming only some reads several. |
| `form.offices["New York"].staff` | A quoted key value, for one holding anything but letters, digits, `_`, `.`, `:`, and `-`. |

A selector gives every key field by position, or names fields, but not both in one selector. Each key value must read
as its field's kind.

### An entry's envelope: `@`

After the trait and its selector, `@` reads a field of the entry itself rather than of its data:
`form.yearly[2024].@updatedAt`.

$${attrTable(RENV.attrs.values)}

## A workflow: `$${ReportSource.workflow.name}`

A workflow path names a normal workflow the client has, then one of these. A report says about a workflow only what
the forms list shows: every attribute is empty for a workflow the client no longer has, one past its lifetime, and one
closed to new forms that this form never entered.

$${attrTable(RWF.attrs.values)}

### An approval

`$${RWF.approval}[<taskId>]` names one of the workflow's approval tasks, then one of these:
`workflow.review.$${RWF.approval}[accept].$${RWF.approved}`.

$${attrTable(RWF.approvalAttrs.values)}

## The owner: `$${ReportSource.user.name}`

The account of the person who owns the form. Every attribute is empty when the owner is unknown, or one the person
running the report may not see.

$${attrTable(RUSR.attrs.values)}

## The form: `$${ReportSource.meta.name}`

$${attrTable(RMETA.attrs.values)}

## Several values

A path has several values when it reads every entry of a keyed trait, goes through a list, or names an attribute
marked "several" above. A column's `$${RDEF.combine}` makes them one value for the form. Left out, a path that can have
only one value takes the `$${ReportCombine.first.name}`, and one that can have several lists them, so no value is
dropped without saying so.

$${combineTable()}

A column's `$${RDEF.rollup}` takes the same choices, applied across the forms of a group when the report is run
grouped. A column with no rollup is left out of a grouped run, and one the run groups by is shown once, as the group's
value. A column whose value is a list rolls up only by `$${ReportCombine.count.name}`, and cannot be grouped by.

A column's `$${RDEF.kind}` reads its value as another kind: any value can be read as text, and text can be read as a
number or a date. Between two other kinds there is no reading, and declaring one is refused.

## When a path is checked

A path is checked when the configuration loads: that it is written correctly, and that what it names exists for the
client, with a selector that fits the trait's key. A path naming a trait, a field, or a workflow
the client does not have costs the report that holds it, not the rest of the configuration.

See the demo's [Reports](../demo-client/reports.md) page for two reports worked through, and the
[client configuration reference](client-configuration.md) for the rest of a report's definition.
"""

/** A source's attributes: each name, its kind, whether it holds several values, and what it holds. */
private fun attrTable(attrs: Collection<ReportAttr>): String {
    val rows = attrs.map { a ->
        "| `${a.name}` | ${kindText(a.kind)}${if (a.multiValued) ", several" else ""} | ${a.description} |"
    }
    return (listOf("| Attribute | Kind | Holds |", "|---|---|---|") + rows).joinToString("\n")
}

/** A kind as the page names it. */
private fun kindText(kind: ReportKind): String = when (kind) {
    ReportKind.string -> "Text"
    ReportKind.number -> "Number"
    ReportKind.date -> "Date"
    ReportKind.boolean -> "Yes or no"
}

/** Every combine, with what it gives. */
private fun combineTable(): String =
    (listOf("| Combine | Gives |", "|---|---|") + ReportCombine.entries.map { "| `${it.name}` | ${it.description} |" })
        .joinToString("\n")
