<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Reference

These pages list what the runtime reads and offers: every schema keyword, validation code, expression function, fact,
copy role, report path, form layout key, role, and configuration slot, and every environment variable and endpoint.
Each is generated from the code it describes, so it says what the runtime does, not what it was once meant to do. A
copy of each is committed beside the code, and the build's `check` fails while one is behind it.

Read a guide first for how the pieces fit together: [Start here](../start-here.md) says which, by what you want to do.

| Reference | What it covers |
|---|---|
| [Schema dialect](schema-dialect.md) | Every keyword the runtime reads in a JSON Schema, the ones it refuses, and its own -- made from the parser's tables. |
| [Validation messages](validation-messages.md) | Every code a refused value is reported with, what a message for it may say, and how to write your own -- made from the validator's codes. |
| [Expressions](expressions.md) | The expression language copy, columns, layouts, and computed values are written in, and its functions -- made from the functions. |
| [Facts](facts.md) | The yes-or-no facts that decide who sees a menu entry or a field, who may save, and which forms a workflow takes. |
| [Copy](copy.md) | The Markdown copy is written in, the roles that set a phrase or an image apart, and the fragment files copy is kept in. |
| [Page widgets](page-widgets.md) | How a page written as copy places widgets with fences, what the checks refuse, and every kind of widget -- made from the kinds. |
| [Report paths](report-paths.md) | How a report column names where its value comes from, and every attribute a path can end in, with its kind. |
| [Form layouts](form-layouts.md) | What a type's layout may hold: field copy, which fields show and in what order, form wording, and a client's changes. |
| [Roles and access](roles-and-access.md) | What each role grants, who may call each section of the API, whose data a caller reads, and how to ask why not. |
| [Client configuration](client-configuration.md) | Every slot of a client's configuration, what its entries hold, and what a write or an edit of one must send. |

## In the repository only

A running server does not serve these two, since it cannot list either whole: it knows only the environment variables
it has read so far, and only the endpoints of the components its deployment includes. Each links to its copy in the
repository.

| Reference | What it covers |
|---|---|
| [Environment variables](environment-variables.md) | Every environment variable the runtime reads, with its default and what it does. A running node lists the ones it has read, with their values, at `GET /operator/env/reference`. |
| [Endpoints](endpoints.md) | Every endpoint the runtime offers, one line each, grouped by the section that decides who may call it. |
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated** from the declarations of the documents a server serves, so a new
reference is listed here once it is declared. Read it on the server's **Documents** page, under **Reference**, where
anyone may open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/reference](https://dynamicruntime.org/wa/docs/reference). `./gradlew :launch:refDocsDump`
copies it into this file, and the suite fails while the copy is behind the code, so edit the generator (below), never
the copy.

On a running server, a link to this file opens the index itself.

## Where it comes from

| What | Source |
|---|---|
| The served references, with their labels and descriptions | `coreDocs` in [`HomeEndpoints.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/home/HomeEndpoints.kt) |
| The two kept in the repository only, and the index made from these | [`ReferenceIndex.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/home/ReferenceIndex.kt) |
