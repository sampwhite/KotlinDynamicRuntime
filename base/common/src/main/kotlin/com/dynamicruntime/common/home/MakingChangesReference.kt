@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.home

import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.ULH
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.UADEP

/**
 * One way to make one kind of change to a client (issue #1807): where a person makes it, the endpoint behind that, the
 * configuration assistant's tool for it, and whether a save is dry-run first. A row of the making-changes guide
 * ([makingChangesMarkdown]).
 */
class ChangeWay(
    /** The change, in a reader's words. */
    val change: String,
    /** Where a person makes it: a page, and the panel or control on it. */
    val where: String,
    /** The endpoint a save calls, or null for a change no endpoint makes from data (an uploaded file). */
    val path: String?,
    /** The assistant's tool for it, or null when the assistant has none. */
    val tool: String?,
    /** Whether the endpoint takes a dry run, and the assistant must take one before it saves. */
    val dryRun: Boolean = false,
)

/**
 * The kinds of change about one part of a client, under a heading of the guide, with [notes] (Markdown, or null) on
 * what to change as well as how, shown above the table.
 */
class ChangeGroup(val title: String, val ways: List<ChangeWay>, val notes: String? = null)

/** Where the bundle editor is, for a change no editor makes. */
private const val bundleEditor = "Clients page, **Sandbox and publishing**, then **Open every bundle**"

// Where a person makes the changes several rows share: each named once, so a renamed panel is one edit.
private const val traitsPanel = "Clients page, **Forms and data**, **Traits**"
private const val designView = "Design View, on the form"
private const val workflowsPanel = "Clients page, **Forms and data**, **Workflows**"
private const val listingColumns = "Clients page, **Forms and data**, **Listing columns**"
private const val copyPanel = "Clients page, **Copy**"
private const val menuPanel = "Clients page, **Menu**"

/**
 * Every kind of change to a client and the way to make it, grouped as the guide shows them (issue #1807). The
 * assistant's tests hold its write tools to these rows: each tool is a row's, with the row's endpoint and dry run, so a
 * tool cannot be added or moved without the guide saying so.
 */
val changeGroups: List<ChangeGroup> = listOf(
    ChangeGroup(
        "The client",
        listOf(
            ChangeWay(
                "Its own definition: its name, how it describes itself, and how it behaves",
                "Clients page, **Definition**", UADEP.clientDefinitionSet, ATOOL.setClientDefinition, dryRun = true,
            ),
        ),
    ),
    ChangeGroup(
        "Traits",
        listOf(
            ChangeWay(
                "Which of the platform's traits it includes",
                traitsPanel, UADEP.clientTraitsSet, ATOOL.setIncludedTraits,
            ),
            ChangeWay(
                "A trait of its own, made or changed: its fields, their kinds, and their choices",
                traitsPanel, UADEP.clientTraitDesign, ATOOL.designTrait, dryRun = true,
            ),
            ChangeWay(
                "One of the platform's traits narrowed: fields hidden or required, choices kept, a field made unique for each " +
                    "owner (`g-unique`), a name of its own",
                traitsPanel, UADEP.clientTraitNarrow, ATOOL.narrowTrait, dryRun = true,
            ),
            // No editor offers counters yet (issue #1849): a counter, and a field taking its ids, are written as JSON.
            ChangeWay(
                "A counter, made or changed: a form's ids, a prefix and then a zero-padded count (`${CCT.counterDef}`)",
                bundleEditor, CFEP.bundlePatch, ATOOL.patchConfig, dryRun = true,
            ),
            ChangeWay(
                "A field that takes its ids from a counter (`g-counter` in its trait's `dataSchema`), or a trait holding one",
                bundleEditor, CFEP.bundlePatch, ATOOL.patchConfig, dryRun = true,
            ),
            // No editor offers form types yet (issue #1902).
            ChangeWay(
                "A form type, made or changed: a kind of form, the traits it collects, and who may set it (`${CCT.formTypeDef}`)",
                bundleEditor, CFEP.bundlePatch, ATOOL.patchConfig, dryRun = true,
            ),
        ),
    ),
    ChangeGroup(
        "A workflow's form",
        listOf(
            ChangeWay(
                "A field's label, description, or hint, on one workflow's form",
                designView, DSV.layoutEntryEdit, ATOOL.setFieldCopy,
            ),
            ChangeWay(
                "A field's words or choices, on every workflow's form",
                designView, DSV.sharedFieldEdit, ATOOL.setSharedField, dryRun = true,
            ),
            ChangeWay(
                "A trait's heading and introduction, on one workflow's form",
                designView, DSV.headingEdit, ATOOL.setHeading,
            ),
            ChangeWay(
                "A trait's heading and introduction, on every workflow's form",
                designView, DSV.sharedHeadingEdit, ATOOL.setSharedHeading,
            ),
            ChangeWay(
                "A workflow's, a task's, or a save's label",
                designView, DSV.labelEdit, ATOOL.setWorkflowLabel,
            ),
            ChangeWay(
                "Which fields a workflow's form shows",
                designView, DSV.shownFieldsEdit, ATOOL.setShownFields, dryRun = true,
            ),
        ),
    ),
    ChangeGroup(
        "Workflows",
        listOf(
            ChangeWay(
                "A workflow made from a template, the starter New form and core details among them: made, renamed, or its " +
                    "tasks' traits changed",
                workflowsPanel, UADEP.clientWorkflowSave, ATOOL.saveTemplateWorkflow,
                dryRun = true,
            ),
            ChangeWay(
                "A workflow made from a template, removed",
                workflowsPanel, UADEP.clientWorkflowRemove, ATOOL.removeTemplateWorkflow,
                dryRun = true,
            ),
            ChangeWay(
                "A workflow's time windows, who sees it, and whether it is turned on",
                workflowsPanel, UADEP.clientWorkflowParams, ATOOL.setWorkflowParams,
                dryRun = true,
            ),
            ChangeWay(
                "A workflow written whole: its tasks, saves, approvals, and conditions",
                bundleEditor, CFEP.bundlePatch, ATOOL.patchConfig, dryRun = true,
            ),
        ),
        // What the configuration assistant got wrong in the first trial run (issue #1868), said where it reads first.
        notes = """
            **A form's fields go on both its creation and core details workflows.** The New form, which the creation
            workflow makes, asks for them, and the core details workflow is where they are seen and changed once the form
            exists. So a form that collects a grant's amount and description has the trait holding them on both, whether
            or not the request names core details. The two differ only when a client wants them to, such as core details
            asking for more than the New form, once the form exists.

            **A review builds on core details.** A review workflow is made eligible once the form has the fact
            `${CDF.coreDetailsComplete}`, so the answers it reviews are there. Its approval task collects no traits and
            offers no save. To show the form's answers above its button, the approval names their traits in its
            `${WFD.showTraits}`, and shows them as the form holds them, whichever workflow collected them. It can lay
            them out its own way in a `${WFD.types}` of its own. Naming none, it shows the nearest task before it that
            collects traits, or the tasks its `${WFD.showTasks}` names, never both lists.
            [The committee review](../demo-client/committee-review.md) is a whole example.

            **A reviewer needs a label and the `${ROLE.editor}` role.** The label, which the task's `${ULH.fn}` function
            names, lets a person act on the review. The role lets them see forms that are not their own: with the label
            alone, the forms to review are not in their list, and they cannot open one. An administrator gives both on
            the Users page, the role with the **Editor** box under **More options**. Someone at the administrator level
            has that reach already, and needs only the label. In a client that groups its users in organizations, an
            editor or administrator with a primary one sees that organization's forms and those that belong to none,
            not every form. See [Roles and access](roles-and-access.md).

            **A workflow that starts with someone else's step highlights its Start.** When a workflow's first step is
            not the form owner's to take, such as a review or an approval, its definition sets
            `${WFD.highlightStart}` to `true`. A form that is eligible for it, and not yet in it, then shows in the
            forms list as the turn of whoever could take that step. Without it, a reviewer is not told of the form until
            someone has started the review. It never decides who may start it.
        """.trimIndent(),
    ),
    ChangeGroup(
        "The forms list",
        listOf(
            ChangeWay(
                "A column, added or changed",
                listingColumns, UADEP.clientUsageSave, ATOOL.saveFormsListColumn,
                dryRun = true,
            ),
            ChangeWay(
                "A column, removed",
                listingColumns, UADEP.clientUsageRemove, ATOOL.removeFormsListColumn,
                dryRun = true,
            ),
            ChangeWay(
                "How it shows the workflows a form takes part in",
                "Clients page, **Forms and data**, **Workflows in the forms list**", UADEP.clientUsageLayout,
                ATOOL.setFormsListLayout,
            ),
        ),
    ),
    ChangeGroup(
        "Reports",
        listOf(
            ChangeWay(
                "A report made from simple choices: its name, its columns, and its chart",
                "Reports page, **New report**", UADEP.reportCreate, ATOOL.createReport, dryRun = true,
            ),
            ChangeWay(
                "A report the client stores, removed",
                "Reports page, under the open report", UADEP.reportRemove, ATOOL.removeReport, dryRun = true,
            ),
            ChangeWay(
                "A report changed once it is made",
                bundleEditor, CFEP.bundlePatch, ATOOL.patchConfig, dryRun = true,
            ),
        ),
    ),
    ChangeGroup(
        "Words, the menu, and Home",
        listOf(
            ChangeWay(
                "One piece of wording, on a page or in a mail",
                copyPanel, CPY.setPath, ATOOL.setCopy,
            ),
            ChangeWay(
                "Several pieces of one copy file's wording, set or put back together",
                copyPanel, CPY.applyPath, ATOOL.applyCopy,
            ),
            ChangeWay(
                "A piece of wording put back to the platform's",
                copyPanel, CPY.resetPath, ATOOL.resetCopy,
            ),
            ChangeWay(
                "A menu item renamed, hidden, or shown",
                menuPanel, MNU.setPath, ATOOL.setMenuItem,
            ),
            ChangeWay(
                "A menu item put back as shipped",
                menuPanel, MNU.resetPath, ATOOL.resetMenuItem,
            ),
            ChangeWay(
                "A card on Home added, hidden, or reworded",
                bundleEditor, CFEP.bundlePatch, ATOOL.patchConfig, dryRun = true,
            ),
            // Home is a page document kept as copy (issue #1794), so rearranging it is a change of one copy key.
            ChangeWay(
                "Home's page: what it shows, and in what order",
                "$copyPanel, the `${HFRAG.home}` file's own listing: its `${HFRAG.pageNs}.${HFRAG.pageHome}` key, " +
                    "written whole, fences and all",
                CPY.setPath, ATOOL.setCopy,
            ),
        ),
    ),
    ChangeGroup(
        "Images",
        listOf(
            ChangeWay("The favicon or the brand mark", "Clients page, **Images**, **Web assets**", null, null),
            ChangeWay("A named image the client's copy shows", "Clients page, **Images**, **Files**", null, null),
        ),
    ),
    ChangeGroup(
        "Anything else",
        listOf(
            ChangeWay(
                "Anything no editor above makes: a slot's entries, written as JSON",
                bundleEditor, CFEP.bundlePatch, ATOOL.patchConfig, dryRun = true,
            ),
        ),
    ),
)

/**
 * The **making-changes guide** (issue #1807): for each kind of change to a client, the editor to make it with, the
 * endpoint behind that editor, and the configuration assistant's tool -- and why an editor is preferred to writing the
 * configuration by hand. A Markdown document served on the Documents page to anyone, and readable by the assistant's
 * `read_doc`.
 *
 * Its tables are made from [changeGroups], whose endpoints and tools are the code's own constants, so a renamed endpoint
 * or tool changes the guide with it; the assistant's tests hold its tools to the rows.
 *
 * One Markdown literal, as the other generated references are: `$${…}` substitutes.
 */
fun makingChangesMarkdown(): String =
    // language=Markdown
    $$"""# Making changes

A client is configuration: bundles of JSON, each holding entries in slots (the
[client configuration reference](client-configuration.md) lists every slot). Anything in it can be changed by writing
JSON, with a bundle patch. Most kinds of change also have an **editor**: a panel of the Clients page, Design View on a
form, or the Reports page, each with an endpoint behind it, and a tool the configuration assistant uses. This page says, for
each kind of change, which to use.

## Why an editor, where there is one

- **It keeps what it does not change.** A bundle patch changes an entry field by field at most: whatever field it gives
  is written whole. Much of a client lives in one field of an entry -- a workflow's whole `$${CCT.definition}`, a trait's
  `$${CCT.dataSchema}` -- so a patch that changes one task's label restates every task. An editor writes only what it is
  told to change: a field's label, a task's traits, a menu item's name. A workflow made from a template, for example, is
  stored as only what it changes in the template, and its editor keeps it that way.
- **It checks what the change means.** Removing a choice is judged against the stored forms that hold it, and a
  narrowed trait against the data it would no longer accept. Such a change is refused with what it would do, until it
  is acknowledged.
- **It speaks in the client's terms.** A person picks a menu item's audience by its name, or a field by its label, and
  the editor writes the condition or the key.

## How a change takes effect

- **In a client with a sandbox**, every change is made in the sandbox, as a **draft**: the sandbox runs it at once, so it
  can be tried on a real form, and the client's people see it once it is published, from the sandbox's **Sandbox and
  publishing** on the Clients page. The client's own page shows its configuration and leads to the sandbox, and a change
  made from the client itself is refused. In a client without one, a change is published as it is saved.
- **A workflow's parameters** (its time windows, who sees it, and whether it is on) are the exception: they are in force
  at once and never drafted. Set in the sandbox, they are the sandbox's own, and the client's are set in the client.
- **A dry run** checks a change as its save would, and stores nothing. Each endpoint below marked "yes" takes one
  (`$${CFEP.dryRun}: true`), and the configuration assistant always takes it before it saves, since a save that can be refused
  or can reach stored forms is better seen first.
- **The assistant** works as the person's own user in the client's sandbox, so every change it makes to the configuration
  is a draft, and it cannot publish. A workflow's parameters it sets are the sandbox's own, in force there at once. It
  has no tool for images, which are files a person uploads.

$${changeGroups.joinToString("\n\n") { groupSection(it) }}

## Writing a bundle patch

For what no editor makes, a patch (`POST $${CFEP.bundlePatch}`) changes a bundle's entries in place:

1. **Read the bundle and its entries** first. Each entry has an `updatedAt`; send it back as `$${CFEP.expectUpdatedAt}`
   when changing that entry, so a change made meanwhile is not overwritten.
2. **Choose each edit's `$${GED.action}`.** `$${GedraEditAction.addOrMerge.name}` changes the fields it gives and keeps the rest, so it is
   the one to use for a change to an entry that exists. `$${GedraEditAction.addOrReplace.name}` replaces the entry with what it gives, and
   `$${GedraEditAction.deleteOrNoOp.name}` removes it. Either way, a field it gives is written whole: to change one task of a workflow,
   give its whole `$${CCT.definition}` as it should be.
3. **Dry run it** (`$${CFEP.dryRun}: true`), read what it says, and then send the same patch to save it.

The [client configuration reference](client-configuration.md) says what each slot's entries hold. The menu and Home's
cards are written as described in [Menu and home cards](menu-and-home-cards.md), Home's page as in
[Page widgets](page-widgets.md), and the `$${CCT.workflowDef}` slot's entries are explained, piece by piece, in
[the conference demo](../demo-client/README.md). For how the parts of a client fit together, read
[What a client is](what-a-client-is.md).
"""

/** One group's section: its heading, and a table of its ways. */
private fun groupSection(group: ChangeGroup): String = buildString {
    appendLine("## ${group.title}")
    appendLine()
    group.notes?.let {
        appendLine(it)
        appendLine()
    }
    appendLine("| Change | Where a person makes it | Endpoint | Assistant tool | Dry run |")
    appendLine("|---|---|---|---|---|")
    for (way in group.ways) {
        val endpoint = way.path?.let { "`POST $it`" } ?: "Uploaded by a person"
        val tool = way.tool?.let { "`$it`" } ?: "None"
        appendLine("| ${way.change} | ${way.where} | $endpoint | $tool | ${if (way.dryRun) "Yes" else "No"} |")
    }
}.trimEnd()
