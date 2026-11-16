@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.home

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.util.joinAsPhrase
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr

/**
 * The **menu and home cards guide** (issue #1767): how a client's menu and Home's cards are made, how to see a client's
 * menu as it stands, and how to change an item -- by an overlay in its configuration, or with the menu editor. A
 * Markdown document served on the Documents page to anyone, and readable by the assistant's `read_doc`.
 *
 * Its tables are made from the core blocks themselves ([homeMenuBlock], [homeCardsBlock]) and from the audiences the
 * menu editor offers ([HomeMenuAudiences]), so an item cannot be added, renamed, or given another condition without the
 * guide saying so. They are the core layer alone: a component's overlays (the workflow assistant's cards) and a client's
 * own are not in them, which the page says.
 *
 * One Markdown literal, as the other generated references are: `$${…}` substitutes.
 */
fun menuAndHomeCardsMarkdown(): String {
    // The deployment's own items are left out: a client's people are never among their audience, so to anyone configuring
    // a client they are only a distraction.
    fun deploymentOnly(item: Map<String, Any?>) = HomeMenuAudiences.deploymentOnly(item[UIB.cfactExpression].toOptStr())
    val wholeMenu = homeMenuBlock().content[HFLD.menu].toJsonListOfMaps()
    val menu = wholeMenu.filterNot(::deploymentOnly)
    val cards = homeCardsBlock().content[HCARD.cards].toJsonListOfMaps().filterNot(::deploymentOnly)
    // What the page says it left out, each by its label: an item under a header left out goes with its header.
    val leftOutIds = wholeMenu.filter(::deploymentOnly).map { it[HFLD.id].toOptStr() }.toSet()
    val leftOut = wholeMenu.filter { deploymentOnly(it) && it[UIB.parentId].toOptStr() !in leftOutIds }
        .map { "**${it[HFLD.label].toOptStr()}**" }.distinct()
    // From the whole menu, so a kept item under a header left out still names its header.
    val labels = wholeMenu.associate { it[HFLD.id].toOptStr() to it[HFLD.label].toOptStr() }
    // A card whose body is copy, named by its copy key: its `text` is not drawn.
    val copyCards = cards.mapNotNull { card ->
        card[HCARD.textCopy].toOptStr()?.let { "**${card[HCARD.title].toOptStr()}** (`$it`)" }
    }
    // language=Markdown
    return $$"""# Menu and home cards

The menu along the top of every page and the cards on Home are each a **UI block**: a list of items, each with an id,
its words, what it does when chosen, and a condition deciding who is offered it. The platform ships both, a component
may add to them (the workflow assistant adds cards of its own), and a client changes them for itself. Every layer
names an item by its id, so a client's change says only what it changes, and the rest of the item stays as shipped.

This page says how to see a client's menu as it stands, how to change an item, and what the platform ships. The lists
at the end are made from the platform's own declarations.

## A client's menu as it stands

The lists at the end of this page are the **core layer**, not any one client's menu: a component may have added
items, and a client may have renamed, hidden, or shown some. To see what a client's people are offered, read the
client's own:

- **On the Clients page**, the client's **Menu** panel lists every item with its label and who it is shown to, as
  shipped and for this client, and marks what the client changed.
- **Through the API**, `GET $${MNU.itemsPath}` answers the same, one entry per item: its label and
  condition as shipped (`$${COV.baseLabel}`, `$${MNU.baseCondition}`) and for this client (`$${MNU.label}`, `$${MNU.condition}`),
  the audience each condition is called, the item it sits under, and the audiences it may be shown to. An item marked
  `$${MNU.deploymentOnly}` is the deployment's own, shown only to its operators, to a test instance's sessions, or to a
  debug session, which a client's people are never among.
- **The workflow assistant** reads it with its `read_menu` tool.

Who sees an item is decided by its condition, a test written in [facts](facts.md): `kdr:loggedIn,kdr:app` is offered to
anyone signed in to the application, `#never` to nobody. A condition changes what is offered, never what anyone may
reach: the pages and the API behind an item still decide who may use them.

## Changing an item with an overlay

A client changes a block with an overlay: an entry of the `$${CCT.uiBlockDef}` slot of its configuration, naming the
block and, in its content, each item it changes. The menu's block is `$${HMENU.block}` and its items are under
`$${HFLD.menu}`. An item is named by its `$${HFLD.id}`, and given only what changes:

| Key | What it means |
|---|---|
| `$${HFLD.id}` | Which item. Required, and never changed: it is how every layer finds the item. |
| `$${HFLD.label}` | The words shown. |
| `$${UIB.cfactExpression}` | Who is offered it, as a test written in [facts](facts.md). |
| `$${UIB.action}` | What choosing it does: a string opens that page, and an array calls a function, then its arguments. |
| `$${UIB.parentId}` | The item it sits under, for one level of nesting. A parent is drawn as a group header and has no action of its own. |
| `$${UIB.displayOrder}` | Where it sorts. The platform's items are spaced $${UIB.orderStep} apart, so an item can land between two. |

**Turning one on.** The platform ships the **Workflows** item withdrawn, since only a client with normal workflows has
anything to show there. The conference demo turns it on for everyone signed in, with one entry in its `base` bundle
(see [the abstract page](../demo-client/abstract.md)):

```json
{
  "$${CCT.blockId}": "$${HMENU.block}",
  "$${CCT.content}": {
    "$${HFLD.menu}": [{ "$${HFLD.id}": "$${HMENU.workflows}", "$${UIB.cfactExpression}": "kdr:loggedIn,kdr:app" }]
  }
}
```

**Hiding one.** Nothing is ever removed from a block: an item is withdrawn by giving it the condition `$${CFACT.neverName}`.
So the shipped item stays in the list, and why it is gone can be read from the item:

```json
{ "$${HFLD.id}": "$${HMENU.catalog}", "$${UIB.cfactExpression}": "$${CFACT.neverName}" }
```

**Relabeling one.** Give it only the new words; who sees it and where it goes stay as shipped:

```json
{ "$${HFLD.id}": "$${HMENU.docs}", "$${HFLD.label}": "Help" }
```

**Putting one back.** Remove the entry from the overlay, and the item is as shipped again. An overlay can also give
`$${CFACT.alwaysName}`, which shows an item to everyone, on every node, whatever a layer beneath it said.

**Adding one.** An item with an id no layer beneath has is added to the block. It needs a label and a
`$${UIB.displayOrder}`, since an overlay's items are never numbered for it, and almost always a condition: without one it
is offered to everyone, on every node. An item that is chosen needs an action too. A group header for items of the
client's own has none, since a header is only drawn over the items naming it in their `$${UIB.parentId}`.

An overlay is checked when its configuration is written, and refused for a block nobody registered, a fact that is not
declared, a call to a function the application does not have, or nesting that is not one level under a header with no
action. In a client with a sandbox, the write is a draft until it is published.

## Changing an item with the menu editor

The client's **Menu** panel changes one item at a time without any JSON, and the endpoints behind it do the same:
`POST $${MNU.setPath}` renames an item, hides it (`$${CFACT.neverName}`), or shows it to an audience, and
`POST $${MNU.resetPath}` takes the client's changes to it back. Each writes the same overlay a
hand-written one is, into the client's stored configuration: live at once, or, for a client with a sandbox, a draft its
sandbox shows until it is published.

The editor shows an item to an audience chosen by name, never to a condition written by hand. These are the audiences
it offers, and an item may also be put back to the one it shipped with:

$${audienceTable()}

## Home cards

Home's cards are the block `$${HCARD.block}`, their items under `$${HCARD.cards}`, and change the way the menu's items do:
named by id, hidden with `$${CFACT.neverName}`, reworded with a new `$${HCARD.title}` or `$${HCARD.text}`. They have no editor, so a
change is an overlay in the client's configuration.

A card whose body is copy is the exception: it names a key of the `home` copy file in `$${HCARD.textCopy}`, and the page
draws that key's words in place of its `$${HCARD.text}`, so an overlay's `$${HCARD.text}` changes nothing there. Reword such a card's
body as any copy is reworded, with the copy editor. The platform's are
$${joinAsPhrase(copyCards)}.

A card's condition decides only from facts that are cheap to know. A card whose words depend on the client's data, such
as what waits on the reader, names a `$${HCARD.kind}`, and the page reads that data when it draws the card. This overlay
hides the new client's checklist:

```json
{
  "$${CCT.blockId}": "$${HCARD.block}",
  "$${CCT.content}": {
    "$${HCARD.cards}": [{ "$${HFLD.id}": "$${HCARD.checklist}","$${UIB.cfactExpression}": "$${CFACT.neverName}" }]
  }
}
```

## The core menu

Every item the platform ships to a client's people, in the order it is drawn. "Shown to" is the item's condition as
shipped, with the name the menu editor gives it where it has one. An item with no condition is offered on every node; a
header is drawn only while an item under it is shown.

The deployment's own items are left out, each with what is under it:
$${joinAsPhrase(leftOut)}.
Each is shown only to the deployment's operators and administrators, to a test instance's sessions, or to a debug
session, never to a client's people.

$${menuTable(menu, labels)}

## The core home cards

Every card the platform ships to a client's people, in the order it is drawn, leaving out the deployment's own as the
menu above does. A component's cards, such as the workflow assistant's, are not among them.

$${cardTable(cards)}
"""
}

/** The audiences the menu editor offers, each by its name and the condition stored for it. */
private fun audienceTable(): String = buildString {
    appendLine("| Audience | Condition |")
    appendLine("|---|---|")
    for (audience in HomeMenuAudiences.offered) appendLine("| ${audience.name} | ${code(audience.condition)} |")
}.trimEnd()

/** One row per core menu item: its id, label, the item it sits under, what choosing it does, and who it is shown to. */
private fun menuTable(menu: List<Map<String, Any?>>, labels: Map<String?, String?>): String = buildString {
    val parents = menu.mapNotNull { it[UIB.parentId].toOptStr() }.toSet()
    appendLine("| Id | Label | Under | Does | Shown to |")
    appendLine("|---|---|---|---|---|")
    for (item in menu) {
        val id = item[HFLD.id].toOptStr().orEmpty()
        val under = item[UIB.parentId].toOptStr()?.let { "${labels[it]} (${code(it)})" }.orEmpty()
        val does = when (val action = item[UIB.action]) {
            is String -> "Opens ${code(action)}"
            is List<*> -> "Calls ${code(action.joinToString(", ", "[", "]") { "\"$it\"" })}"
            else -> if (id in parents) "Group header" else ""
        }
        appendLine("| ${code(id)} | ${cell(item[HFLD.label].toOptStr())} | $under | $does | ${shownTo(item)} |")
    }
}.trimEnd()

/** One row per core home card: its id, its title, and who it is shown to. */
private fun cardTable(cards: List<Map<String, Any?>>): String = buildString {
    appendLine("| Id | Title | Shown to |")
    appendLine("|---|---|---|")
    for (card in cards) {
        appendLine("| ${code(card[HFLD.id].toOptStr().orEmpty())} | ${cell(card[HCARD.title].toOptStr())} | ${shownTo(card)} |")
    }
}.trimEnd()

/** Who an item is offered to: its condition, after the menu editor's name for it where it has one. */
private fun shownTo(item: Map<String, Any?>): String {
    val condition = item[UIB.cfactExpression].toOptStr() ?: return "Everyone, on every node"
    if (condition == CFACT.neverName) return "Nobody until a client turns it on (${code(condition)})"
    val name = HomeMenuAudiences.of(condition)?.takeIf { !it.retired }?.name
    return if (name != null) "$name: ${code(condition)}" else code(condition)
}

/** [text] as a table cell, its bars escaped so they do not end the cell. */
private fun cell(text: String?): String = text.orEmpty().replace("|", "\\|")

/** [text] as code in a table cell. */
private fun code(text: String): String = "`${cell(text)}`"
