@file:Suppress("MarkdownUnresolvedFileReference")

package com.dynamicruntime.common.home

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.user.UADEP

/**
 * The **what-a-client-is guide** (issue #1808): a client as a whole, read in order, for a person or an assistant about to
 * configure one -- its definition, its people and forms, its bundles, its sandbox and publishing, the layers that make
 * what its people see, and its pages -- each linking the guide or reference that goes deeper. A Markdown document served
 * on the Documents page to anyone, and readable by the assistant's `read_doc`.
 *
 * Generated, as the other references are, so the names it uses -- the slots, the sandbox's id, Home's copy key, the
 * endpoints -- are the code's own constants, and a rename changes the page with it.
 *
 * One Markdown literal: `$${…}` substitutes.
 */
fun whatAClientIsMarkdown(): String =
    // language=Markdown
    $$"""# What a client is

A **client** is one organization's own space on a site: its people, its forms, and the configuration that decides
what they see and do. Nothing crosses from one client to another. This page goes through a client in the order to
read it, from what defines it to what makes the pages its people see, and links the guide or reference that goes
deeper on each part. To make a change, [Making changes](making-changes.md) says which editor to use.

## Its definition

Every client has one **definition**, an entry of the `$${CCT.clientDef}` slot: its id, which never changes and is part of
the id of everything the client stores; its name, which its people read; how it describes itself; and how it behaves,
such as its time zone and how long a session lasts. A few fields belong to the platform rather than the client, such
as what the client is used for, whose it is, and the roles a visitor may join it as, and its own administrator cannot
change them. The Clients page's
**Definition** panel shows and edits it, and the [client configuration
reference](client-configuration.md#kdrclientdef) lists every field.

A client is one of two kinds, named for where it is defined:

- **A configured client** is made in the app, and everything about it is configuration that its administrators
  change there. It may be **built on** a client defined in code, a template, whose traits, workflows, and wording it
  starts from.
- **A client defined in code** is declared by a module of the platform, and changes with a release. It may also carry
  configuration made in the app, which changes it from there.

## Its people and its forms

A client's **users** are its people: each is a user of one client, with its roles and the labels its administrators
give. A `user` fills in forms, an `editor` also sees and edits every form of the client, and an `admin` administers the
client: its users, its configuration, and its API keys ([Roles and access](roles-and-access.md)). One person may have a
user in several clients, each separate. A client's **forms** are its data: each form holds entries, one per trait, and
goes through the client's workflows. A form, like a user, belongs to its client, and no other client's people read it.
(The deployment's own administrators, who oversee every client, are the exception.)

## Its configuration: bundles, slots, and entries

A client's configuration is a set of named **bundles**. Each bundle holds **slots**, and each slot a list of
**entries**, one kind of thing per slot. The client's configuration is every bundle's entries taken together, so a key
names one thing in the whole client, whichever bundle holds it. The slots a client uses most:

| Slot | What its entries are |
|---|---|
| `$${CCT.clientDef}` | The client's definition. |
| `$${CCT.traitDef}` | Its own traits, the kinds of entry its forms hold. The platform's are included by name, in the definition. |
| `$${CCT.workflowDef}` | Its workflows: the processes a form goes through, made of tasks that collect traits. |
| `$${CCT.usageDef}` | The columns of its forms list. |
| `$${CCT.reportDef}` | Its reports. |
| `$${CCT.fragmentDef}` | Its wording: changes to the platform's copy, key by key. |
| `$${CCT.uiBlockDef}` | Its changes to the menu and Home's cards ([Menu and home cards](menu-and-home-cards.md)). |
| `$${CCT.fileRefDef}` | The named images its copy shows. |

The [client configuration reference](client-configuration.md) lists every slot, every field of their entries, and how
an edit of one is written. A bundle is the unit of publishing: a workflow kept in a bundle of its own can be published
on its own.

## Its sandbox, and publishing

A client may have a **sandbox**: a second client, `$${sandboxOf("acme")}` for `acme`, with users and forms of its own,
that runs the client's latest configuration, drafts and all. In a client with a sandbox, every change to the
configuration is made in the sandbox, as a **draft**. The sandbox runs it at once, so it can be tried on a real form,
and the client's people get it only when a person publishes it, from the **Sandbox and publishing** panel of the
sandbox's Clients page. Until then, the client runs only what is published. The client's own page shows its
configuration and leads to the sandbox, and a change made from the client itself is refused. A client a visitor makes
for themselves is made with one.

A client without a sandbox takes a change as it is saved. Its administrator adds or removes the sandbox from the
Clients page (`POST $${UADEP.clientSandbox}`).

Some things are not configuration, and are neither drafted nor published: a workflow's parameters (its time windows,
who sees it, and whether it is on), which are in force at once in whichever of the two they are set; the client's users;
its forms; and the images it uploads.

## The layers that make what its people see

What a client's people see is put together from layers, the later winning:

1. **The platform's core:** its traits, its menu and Home's cards, its pages, and its wording.
2. **The deployment's modules:** what each adds to the core, such as the workflow assistant's cards on Home.
3. **The deployment's flavor:** a variant of the deployment, such as the public reference site, which sets the
   deployment's own switches and may reword copy.
4. **The template** a configured client is built on, whose traits, workflows, and wording it inherits.
5. **The client's own configuration in code**, for a client defined in code.
6. **The client's own configuration made in the app**, which has the last word.

How a later layer changes an earlier one depends on what it changes:

- **Copy, the menu, and Home's cards merge,** key by key and item by item. A layer says only what it changes, so a
  client's change to one key or one item leaves the rest as the layers beneath have them.
- **Traits and workflows are replaced.** A client's own trait or workflow is its own entry, written whole. One
  inherited from a template, or declared in the client's code, is changed by an entry of the client's own that stands
  over it, restating what it keeps.

**Who sees what** within a client is decided by **facts**: yes-or-no statements about the person, the client, and the
node, such as whether someone is signed in or administers the client. A menu item, a card, a part of a page, a field,
or a save names the facts it needs, and `$${CFACT.neverName}` withdraws something from everyone. [Facts](facts.md) lists
them.

## Its pages

**Home** is a page written as copy: the `$${HFRAG.home}` file's `$${HFRAG.pageNs}.$${HFRAG.pageHome}` key, Markdown with **widget
fences** for what Markdown cannot draw, such as the cards or the list of demos. The page is drawn in the order its
document is written, and each widget decides from its own data whether it draws anything. So a client rewords Home's
title and introduction by their own keys, and rearranges the page by changing that one key with the copy editor.

That key is not wording a person reads, so the copy editor does not list it under any page. Find it in the `$${HFRAG.home}`
file's own listing, and write it whole, its fences and all: a value replaces the one beneath it, so a part left out is
gone from the page. [Page widgets](page-widgets.md) describes the fences and every kind of widget, with Home's own
document as the worked example, and [Copy](copy.md) the Markdown and the files copy is kept in.

## Where to go next

- [Making changes](making-changes.md): for each kind of change, the editor, its endpoint, and the assistant's tool.
- [The client configuration reference](client-configuration.md): every slot and field.
- [The conference demo](../demo-client/README.md): a whole client built from configuration, its JSON explained one
  piece at a time.
- [The grants demo](../grants-demo/README.md): a client in its own words, its copy reworded theme by theme.
- [Roles and access](roles-and-access.md): what each role grants, and who may call what.
"""
