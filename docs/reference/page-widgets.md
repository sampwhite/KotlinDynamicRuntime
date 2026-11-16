<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Page widgets

A page can be written as copy: Markdown, with **widget fences** placing what Markdown cannot draw, such as a list of
cards or a form to create a client. The order of the page is the order of its document, so a client or a deployment
rearranges a page by overlaying its copy. This page describes how a fence is written, what the checks refuse, and every
kind of widget a fence may name. The list of kinds is made from the kinds themselves.

## Writing a fence

A fence is a code block whose info string is `kdr-ui`, opened in the first column of a line at the top level
of the copy (not inside a list or a quote). Its body is one JSON object:

````markdown
```kdr-ui
{
  "id": "cards",
  "widget": "cardsBlock",
  "block": "homeCards",
  "copy": "home.cardsIntro",
  "jumpLabel": "What you can do"
}
```
````

A fence opened with four backticks may hold code written with three. A fence inside another code block is that block's
text, so copy can show a fence as an example, as this page does, without drawing it.

| Key | What it means |
|---|---|
| `id` | The fence's name, unique in its page: a letter or underscore, then letters, digits, and underscores. The fence is drawn with the element id `page.<id>`, which a link can name. |
| `widget` | The kind of widget to draw, one of [the kinds](#the-kinds) below. Left out, the fence draws only its `copy`, which is how a page shows copy under a condition. |
| `copy` | Copy drawn before the widget, as `namespace.key` of the page's own fragment file, unless the kind draws it itself in its own place, as a page's title does. It is drawn as any other copy is, so it may pull other keys and fill placeholders. |
| `cfactExpression` | Whether the fence is drawn: a test of [facts](facts.md), judged in the browser, so it may name only the facts about the caller and the node that are sent to it. A fact about a form, a client's own included, never holds on a page. Left out, the fence is always drawn. |
| `jumpLabel` | Puts a link to the fence, with these words, among the page's jump links. |
| `$comment` | A note for whoever reads or rearranges the page, never drawn: why the fence is there, or when its widget draws anything, since a widget decides that from its own data and the page does not otherwise say. It stays with the fence, so a client's copy of the page keeps it. |

Every other key is a parameter of the widget's kind, held to the kind's own list below.

Long copy belongs in its own key, named by `copy`, rather than inside the JSON: copy is changed one key at a time,
so words kept in their own key can be reworded without restating the page. The Markdown between fences may pull a key
too, with `${@t("namespace.key")}`, as any copy may.

A line that begins with `#` and a space ends a value in a fragment file, so a page's top-level heading is a
`pageTitle` fence rather than a Markdown line. A smaller heading (`##`) is written as Markdown.

## What the checks refuse

A fence in copy a component ships is checked when the server starts, and one in a client's copy when the copy is
written. A fence is refused when:

- it is never closed;
- its body is not a JSON object;
- it has no `id`, an id that is not a name, or the id of an earlier fence in the same copy;
- it names a kind there is none of;
- it gives a parameter the kind does not take, or one the kind's schema refuses;
- it names neither a `widget` nor a `copy`, so it would draw nothing;
- its `copy` is not a `namespace.key` the page's fragment file has;
- its `cfactExpression` does not parse, or names a fact a page never sees, which would never hold;
- one of the keys above holds something other than text.

Where copy is drawn, a fence with a problem is left out and the rest of the page is drawn.

## Pages of your own

The app's home page is the page `home`: a key of the `home` file's `page` namespace. Every key
there is a page, opened at `/wa/page/<key>` and drawn by the same renderer as the home page, which knows nothing of
which page it draws. A client or a deployment adds a page of its own by overlaying a new key in that namespace, the
one key an overlay may add to a file; anywhere else, a key the file does not have is refused.

A menu item opens a page with the `openPage` function, naming the page's key:
`["openPage", "siteVisits"]`. A menu item naming a page the client does not have is refused, and so is
removing a page a menu item opens. Copy links to one at its address, `/wa/page/siteVisits`.

A client's page adds no copy key but its document, so its words are the Markdown between its fences, and its title is
a `pageTitle` fence's `text`. The sample's Acme client has one, `siteVisits`, which its menu
opens and its home page links: a title, a few paragraphs and an image, and a cards block drawing the Your client card.

## Surfaces

A kind says where it can be drawn. A surface meeting a fence whose kind it cannot draw leaves the fence out.

| Surface | What it is |
|---|---|
| `app` | The app's pages, drawn for each caller, signed in or not. |
| `site` | A website generated from a client's data, drawn once rather than for each caller. |
| `mail` | A mail's body, which has no caller's page to draw a widget that reads or acts. |

## The kinds

Any copy may place any kind, a client's own included. A widget decides from its own data whether it draws anything for
the caller, so a page a client copies from Home keeps the demos and the rest, which draw nothing for the client's people.

| Kind | What it draws | Drawn on |
|---|---|---|
| [`hero`](#hero) | The brand mark beside the wordmark (the home copy's brand). Nothing when the copy names no brand. | `app`, `site` |
| [`pageTitle`](#pagetitle) | The page's title, drawn as its one top-level heading: its fence's copy, or its text. Nothing when both are blank. | `app`, `site`, `mail` |
| [`jumpLinks`](#jumplinks) | A row of links to what the page draws further down: led by its fence's copy, links written in Markdown (a list, or a row on one line), then a link to each drawn fence that has a jumpLabel, and to each card that asks for one. A link to a fence or a card not drawn for the caller is left out, and with no link left, so is the row. | `app`, `site` |
| [`cardsBlock`](#cardsblock) | A UiBlock of cards, resolved for the caller as the home cards are: every card the block gives them, or those named. | `app` |
| [`joinableDemos`](#joinabledemos) | The demo clients a visitor signed in to the public client may join, each with its roles, Open as, and Join. Nothing where joining is not offered. | `app` |
| [`createOwnClient`](#createownclient) | The card that creates a person's own client, or opens the one they made. Only for a caller signed in to the public client. | `app` |
| [`testAccess`](#testaccess) | Becoming another user, for a caller with test access on a test instance. Nothing for anyone else. | `app` |
| [`docLinks`](#doclinks) | Links to the documents the deployment lists. | `app` |

### hero

The brand mark beside the wordmark (the home copy's brand). Nothing when the copy names no brand.

It takes no parameters.

### pageTitle

The page's title, drawn as its one top-level heading: its fence's copy, or its text. Nothing when both are blank.

It draws its fence's `copy` itself, as described above, rather than before it.

| Parameter | Type | Required | What it means |
|---|---|---|---|
| `text` | string | No | The title's words, where no copy key holds them: a client's own page adds no key but its document (issue #1796). The fence's copy, where it has one, is drawn instead. |

### jumpLinks

A row of links to what the page draws further down: led by its fence's copy, links written in Markdown (a list, or a row on one line), then a link to each drawn fence that has a jumpLabel, and to each card that asks for one. A link to a fence or a card not drawn for the caller is left out, and with no link left, so is the row.

It draws its fence's `copy` itself, as described above, rather than before it.

It takes no parameters.

### cardsBlock

A UiBlock of cards, resolved for the caller as the home cards are: every card the block gives them, or those named.

| Parameter | Type | Required | What it means |
|---|---|---|---|
| `block` | string | Yes | The UiBlock whose cards are drawn. |
| `ids` | array | No | Only these cards, in this order: how a page places a card apart from the rest. |
| `except` | array | No | Every card but these: the cards placed elsewhere on the page. |

### joinableDemos

The demo clients a visitor signed in to the public client may join, each with its roles, Open as, and Join. Nothing where joining is not offered.

It takes no parameters.

### createOwnClient

The card that creates a person's own client, or opens the one they made. Only for a caller signed in to the public client.

It takes no parameters.

### testAccess

Becoming another user, for a caller with test access on a test instance. Nothing for anyone else.

It takes no parameters.

### docLinks

Links to the documents the deployment lists.

It takes no parameters.

## Home, a worked example

The app's home page is a page document: the `home` file's `page.home` key, as shipped below. Each widget decides from its own data whether it draws anything, so who sees what needs no conditions: the demos only for a visitor signed in to the public client where joining is offered, say. Its one condition is on the walk-through's note, which only such a visitor is shown; each fence's `$comment` says when it draws. A client or a deployment rewords the title and the introduction by their own keys, and rearranges the page, or adds a word of its own, by overlaying this one. A second page, drawn the same way, is a client's own (above).

````markdown
```kdr-ui
{"id": "testAccess", "widget": "testAccess",
 "$comment": "Drawn only for a session with test access: env-authed, on a test instance."}
```
```kdr-ui
{"id": "hero", "widget": "hero",
 "$comment": "Drawn while home.brand is not blank."}
```
```kdr-ui
{"id": "title", "widget": "pageTitle", "copy": "home.title"}
```
```kdr-ui
{"id": "jumpLinks", "widget": "jumpLinks", "copy": "home.jumpLinks",
 "$comment": "Links to what is drawn below with a jump label, and a call-to-action card's button; drawn only when it has one."}
```
```kdr-ui
{"id": "firstCards", "widget": "cardsBlock", "block": "homeCards", "ids": ["checklist"],
 "$comment": "The new client's checklist, above the introduction: for an administrator of a self-serve client while a step is left."}
```
```kdr-ui
{"id": "intro", "copy": "home.intro"}
```
```kdr-ui
{"id": "cards", "widget": "cardsBlock", "block": "homeCards", "except": ["checklist"],
 "$comment": "Every other card the caller is sent, each by its own condition; a live card only while its read finds something."}
```
```kdr-ui
{"id": "walkThrough", "copy": "publicHome.walkThrough", "cfactExpression": "kdr:publicMember",
 "$comment": "The walk-through's note, for a visitor signed in to the public client."}
```
```kdr-ui
{"id": "demos", "widget": "joinableDemos", "copy": "publicHome.demos", "jumpLabel": "Try a demo",
 "$comment": "Drawn only for a visitor signed in to the public client, on a deployment that offers joining a demo."}
```
```kdr-ui
{"id": "ownClient", "widget": "createOwnClient", "jumpLabel": "Your own client",
 "$comment": "Drawn only for a visitor signed in to the public client."}
```
```kdr-ui
{"id": "docs", "widget": "docLinks",
 "$comment": "Drawn only where the deployment lists its documents inline on Home (homeInlineLinks, off by default)."}
```
````
<!-- kdr-generated: end -->
## About this file

**Everything above this section is generated.** A running server makes its list of widget kinds, and each kind's
parameters, from the kinds themselves, which the checks hold copy to, so it cannot disagree with them. Read it on the
server's **Documents** page, under **Reference**, then **Page widgets**, where anyone may open it without signing in. On
the public reference site it is at
[dynamicruntime.org/wa/docs/page-widgets](https://dynamicruntime.org/wa/docs/page-widgets). `./gradlew
:launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit the
generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
| --- | --- |
| The widget kinds, each with what it draws, who may place it, where it draws, and its parameters | `PageWidgets` in [`PageWidgets.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/page/PageWidgets.kt) |
| The fence's keys and how a document is cut at its fences | [`PageDocument.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/page/PageDocument.kt) |
| What the checks refuse | [`PageCheck.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/page/PageCheck.kt) |
| The reference page, made from these | [`PageWidgetsReference.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/page/PageWidgetsReference.kt) |
