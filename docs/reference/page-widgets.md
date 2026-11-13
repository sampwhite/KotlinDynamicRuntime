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
| `copy` | Copy drawn before the widget, as `namespace.key` of the page's own fragment file. It is drawn as any other copy is, so it may pull other keys and fill placeholders. |
| `cfactExpression` | Whether the fence is drawn: a test of [facts](facts.md), judged in the browser, so it may name only the facts about the caller and the node that are sent to it. A fact about a form, a client's own included, never holds on a page. Left out, the fence is always drawn. |
| `jumpLabel` | Puts a link to the fence, with these words, among the page's jump links. |

Every other key is a parameter of the widget's kind, held to the kind's own list below.

Long copy belongs in its own key, named by `copy`, rather than inside the JSON: copy is changed one key at a time,
so words kept in their own key can be reworded without restating the page.

## What the checks refuse

A fence in copy a component ships is checked when the server starts, and one in a client's copy when the copy is
written. A fence is refused when:

- it is never closed;
- its body is not a JSON object;
- it has no `id`, an id that is not a name, or the id of an earlier fence in the same copy;
- it names a kind there is none of, or one that only copy a component ships may place;
- it gives a parameter the kind does not take, or one the kind's schema refuses;
- it names neither a `widget` nor a `copy`, so it would draw nothing;
- its `copy` is not a `namespace.key` the page's fragment file has;
- its `cfactExpression` does not parse, or names a fact a page never sees, which would never hold;
- one of the keys above holds something other than text.

Where copy is drawn, a fence with a problem is left out and the rest of the page is drawn.

## Surfaces

A kind says where it can be drawn. A surface meeting a fence whose kind it cannot draw leaves the fence out.

| Surface | What it is |
|---|---|
| `app` | The app's pages, drawn for each caller, signed in or not. |
| `site` | A website generated from a client's data, drawn once rather than for each caller. |
| `mail` | A mail's body, which has no caller's page to draw a widget that reads or acts. |

## The kinds

| Kind | What it draws | Who may place it | Drawn on |
|---|---|---|---|
| [`hero`](#hero) | The brand mark beside the wordmark (the home copy's brand). Nothing when the copy names no brand. | Any copy, a client's included | `app`, `site` |
| [`jumpLinks`](#jumplinks) | A row of links to the page's drawn fences that have a jumpLabel, and to the cards that ask for one. | Any copy, a client's included | `app`, `site` |
| [`cardsBlock`](#cardsblock) | A UiBlock of cards, resolved for the caller as the home cards are: every card the block gives them, or those named. | Any copy, a client's included | `app` |
| [`joinableDemos`](#joinabledemos) | The demo clients a visitor signed in to the public client may join, each with its roles, Open as, and Join. Nothing where joining is not offered. | Copy a component ships | `app` |
| [`createOwnClient`](#createownclient) | The card that creates a person's own client, or opens the one they made. Only for a caller signed in to the public client. | Copy a component ships | `app` |
| [`testAccess`](#testaccess) | Becoming another user, for a caller with test access on a test instance. Nothing for anyone else. | Copy a component ships | `app` |
| [`docLinks`](#doclinks) | Links to the documents the deployment lists. | Any copy, a client's included | `app` |

### hero

The brand mark beside the wordmark (the home copy's brand). Nothing when the copy names no brand.

It takes no parameters.

### jumpLinks

A row of links to the page's drawn fences that have a jumpLabel, and to the cards that ask for one.

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
