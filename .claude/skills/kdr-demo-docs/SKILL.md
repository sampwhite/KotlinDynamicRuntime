---
name: kdr-demo-docs
description: >-
  Write or edit the developer documentation in `docs/demo-client/` — the conference demo (`confdemo`) walkthrough,
  which shows a client's stored JSON bundle by bundle and workflow by workflow for a human developer reader. Covers
  the page shape, the voice and word rules Sam settled in review (you/the demo, keys vs fields, no house verbs,
  wordier and plainer), the `<!-- kdr-doc: ... -->` markers and what `ConfDemoDocsTest` holds them to, and the process
  (page before DSL, append traits never insert, try the page's own steps on your own server). Use when adding a page
  for a new workflow, editing an existing page, extending the reference page, or changing `ConfDemo.kt` in a way that
  changes a bundle the pages show. Expect to augment this skill as pages accumulate; it was written after the first
  four.
---

# Writing the conference demo's documentation

`docs/demo-client/` walks a **human developer** through the `confdemo` client's configuration: the JSON of each
bundle, piece by piece, example first. It is not reference material (for schema keywords, link the generated
[schema dialect reference](../../../docs/reference/schema-dialect.md), whose repo file is a stub that a running server
replaces with the page) and not a design note (those explain *why*; these explain *what is*). The pages are served in-app and on GitHub alike,
so every link has to work in both.

The tracking issue is #1134; the pages were piloted in #1137 and shaped by Sam's review of #1138. **This skill
records what that review settled.** It will be revisited as pages accumulate -- when a rule here fights a page,
say so rather than contorting the page.

## The reader, and the voice

- The reader is a developer meeting the product for the first time. They want what a thing is, how to use it, and
  one example. **Never** issue numbers, history ("used to", "since #983"), or internal vocabulary the JSON does not
  show (Gedra, slots are fine since the JSON shows them; overlay tiers, cfact internals are not).
- **"you" for the reader, "the demo" for the demo. Never "we" or "our"** in prose. (The questionnaire's own
  questions say "we", because that is the conference talking to the speaker; that is data, not prose.)
- **No sentence starts with code.** `` `demo` is a client that... `` reads as a missing capital. Say "The value
  `demo` marks...", "Its `required: true` means...".
- **A definition has keys; a trait's data has fields.** Tables that explain a definition are headed
  `| Key | What it means |`. "Field" is reserved for data (`track`, `firstTalk`): what a form shows and a field
  layout arranges. Never "attribute". The reference page states the distinction once.
- **Everyday verbs, no house verbs.** These are mine and readers do not meet them elsewhere; replace them:

  | Not this | This |
  | --- | --- |
  | "every form *carries* the id" | "every form has this id stored on it, so ..." |
  | "`workflowId` *names* the workflow" | "the entry has the workflow's id in `workflowId`" |
  | "the entry is *filed under* it" | "the entry includes this id, so it can be matched to its trait" |
  | "the trait the column *reads*" | "the trait the column takes its value from" |
  | "what can *carry* the trait" | "what the trait can be part of" |
  | "the task is not *done* until" | "the task is not complete until" |
  | "nothing is *special-cased* in code" | "there is no code written just for it" |

- **Wordier and plainer beats short and precise.** When a compact phrase goes, keep its accuracy by adding the
  *why* or the *consequence*: "...has this id stored on it, **so there is never any doubt which client it belongs
  to**." "so" is fine for a causal link. Short declaratives in a row read as a spec; let sentences connect.
- **Define a term where the reader first meets it, and hint at what is coming** ("A client can also define traits
  of its own ... the pages on later workflows show several"). Do not name specifics that may shift.
- **An abbreviated example says so**, or the reader takes it for the real thing and is confused when the full one
  appears: "Their entries are shortened placeholders; the pages that follow show each one in full."
- **One term for one thing.** The page is "the forms list" (the app says Forms / My forms). Its configurable columns
  are "columns in the forms list". Check a new term against the pages before coining it.
- Semicolons and em dashes read as spec. Prefer a period, or parentheses for an aside.
- Section headings drop the article: "Workflow", "Task", "Traits". Page titles keep it where natural.

## The shape of a page

Every workflow page follows one shape, so the reader learns it once:

1. Nav line: `[← Previous page](prev.md) · [Contents](README.md#the-pages)`.
2. Title, then one or two paragraphs saying what this kind of workflow is and what the demo's instance of it does.
   If the page covers more than the workflow (the survey page also introduces traits and columns), say so up front
   and list the pieces with in-page links.
3. The whole bundle, marked (below), introduced with "Here is the whole `<name>` bundle:".
4. Sections for what the page explains. Two patterns, deliberately:
   - **A key table** the first time a shape appears (a trait, a task, a column rule): one row per key, meaning
     starts with a capital.
   - **Bold-lead paragraphs** for what is new since the previous page: "**The entry is `survey`.** A survey runs
     on a form that already exists...". Link back to the page that explained the rest.
5. **What the speaker sees**: the page title, the fields and buttons by their on-screen labels in bold, and what
   happens in the forms list. This is the one place the docs connect JSON to a screen; every workflow page has it.
6. `Next: [Page](page.md).` as the last line. The last page says more are coming and links to Contents.

Two pages break the workflow shape, deliberately. `reports.md` follows it with "What the organizer sees" in place
of the speaker, since a report is for administrators. `bundles.md` is the last page and is only JSON: every bundle
whole, marked `bundle=<name>` with no pointer, one `## <bundle>` section each, in the order the demo writes them.
A change to any bundle changes that page too, so the test's mismatch output is worth reading in full there: kotest
prints only the first mismatch, so a dump of every bundle (temporarily, from the test's `slotsOf`) is faster than
fixing them one run at a time.

The README is orientation only: the reading-order list ("The pages"), "Try it", how a client is stored, the bundles
table, and its own Next. The client's definition has its own page. `reference.md` holds anything two pages would
otherwise repeat (reading the JSON, comments, choices, field layouts, ...); a page links there the first time it
uses one, and a new shared concept goes there, not into the page that happened to meet it first.

JSON is laid out for reading: short arrays and small objects inline when they fit on one line, otherwise expanded.
The instance returns keys in another order; the test compares values, so lay keys out in explanation order.

## The markers, and what the test holds them to

Every ```` ```json ```` block has a marker on the line directly before its fence, an HTML comment the renderers drop
(the kernel's `renderMarkdown` skips a line that is wholly `<!-- ... -->`, as GitHub does):

```markdown
<!-- kdr-doc: bundle=createTalk -->
<!-- kdr-doc: bundle=base pointer=/kdr:traitDef/1 -->
<!-- kdr-doc: illustration -->
```

- `bundle=<name>` alone: the block is the bundle's whole `slots`, as `GET /clientAdmin/config/bundle` returns them.
- `pointer=<JSON Pointer>` (RFC 6901, into the slots): the block is that part. `/kdr:traitDef/1` is the second
  trait entry; `/kdr:workflowDef/0/definition/tasks/0` the first task of the first workflow.
- `illustration`: a shape that is not the demo's (the reference's examples, the README's placeholder). Not
  compared.

`ConfDemoDocsTest` (base/kdn) provisions the demo as the simulation does -- a configuration that does not load
cleanly fails there -- then, for every `.md` in `docs/demo-client/`:

- **every marked block equals what it names**, compared as values, so layout is free. A mismatch prints the block
  as it should read, to paste in. An unmarked JSON block is a failure.
- **every backticked `g-...` is a keyword the schema layer knows** (`SchGKeywords`).
- **the pages and `confDemoDocs()` agree**: a page without a declaration, or the reverse, fails.

Links are checked with the rest of the repository's documentation by `RepoDocLinksTest` (`base:common`, issue
#1294): a page or repo file that exists, and a `#section` that is a heading of the page it names (slugs are
GitHub's, which `headingSlug` matches). Links to a page of the app (`/wa/...`) and outside URLs are left alone. It
runs in a task of its own: `./gradlew :base:common:docLinksTest`.

What the test does **not** check is prose: a claim like "the form shows one field" is yours to verify (below).

Run it alone with `cd <workspace> && ./gradlew :base:kdn:test --tests '*ConfDemoDocsTest'`.

## Process

- **Write the page before its DSL.** The page decides the key names, their order and the `$comment` text for a
  reader; then `ConfDemo.kt` is written to produce it. Built the other way, the JSON comes out shaped for the code.
- **Append, never insert.** Pointers are positional (`/kdr:traitDef/1`). A new trait goes at the end of `base`'s
  trait list; a new column at the end of the usages. Inserting renumbers every page after it.
- **One bundle per workflow**, `base` for the client, its traits and its columns, `reports` for the reports (not
  workflows, and they change together). Declare a new page in
  `confDemoDocs()` (`ConfDemo.kt`), unlisted; only the README is listed. The build copies
  `docs/demo-client/<name>.md` to doc id `demo-client-<name>` (`base/kdn/build.gradle.kts`), so the README's id is
  `demo-client-README`.
- **Verify "What the speaker sees" on your own server.** Start it (`KDR_PORT` per your workspace's `CLAUDE.md`),
  run the `conf-demo` simulation, sign in as the user the page names, and follow the page's own steps. Read the
  labels off the page rather than from the DSL. The browser pane must be visible for clicks; data setup goes
  through the API (`/kda/gedra/confdemo/...`), not the UI.
- **Open the page in the app** (`/wa/docs/demo-client-<name>`) and check: no marker text visible, every link
  an in-app link, a section link scrolls to its heading.
- **`$comment` goes where a reader needs a why**: a workflow, a task, an eligibility test, a report, a layout
  block or entry. It is accepted nowhere else (#1135), so do not add one to a save or a column.
- Keep the `kdr-test-clients` skill's pointer to `ConfDemo.kt` true: it is the full worked example of a client
  definition, so a change there is a change to what that skill promises.

## Files

- `docs/demo-client/*.md` -- the pages. `README.md` first, `reference.md` for the shared material.
- `base/kdn/src/main/kotlin/com/dynamicruntime/kdn/confdemo/ConfDemo.kt` -- the client, its bundles, the
  simulation, and `confDemoDocs()`.
- `base/kdn/src/test/kotlin/com/dynamicruntime/kdn/ConfDemoDocsTest.kt` -- the maintenance test.
- `kdr-docs/thoughts-client-and-wf-example.md` in the private Actions notes -- the design, the domain, and why the
  pages are ordered as they are.
