## The forms listing is named for whose forms it shows (issue #1091)
<!-- Theme: #RDSCP -->

"My forms" for a caller who lists only their own; "Forms" for one who administers a client, whose listing is every
form in it. Three places say it and all three agree: the **menu entry**, the **page heading** and the **back link**
from a form.

- **One rule, asked twice.** The backend's `AdminRules.isClientAdministrator` -- which is also how far the caller's
  reads reach (`ReadScopeRules.forCaller`). The menu asks it as a cfact (`kdr:administersClient`): two entries for
  the one page, mutually exclusive, `HMENU.forms` "My forms" and `HMENU.clientForms` "Forms". The page is told it
  as the home config's `administersClient` feature. **Not** `canManageUsers` and not the admin level: both are true
  for a `public` self-administrator, who reads only their own forms.
- **The page is called what its menu entry is called.** The page reads its name from the shell, not from a fetch of
  its own (`ShellFacts`, `useShellFacts`): `App` provides what the app bar's config read said (`shellFactsOf`), which
  includes the label of whichever entry of the caller's menu routes to the forms listing. So the heading and the back
  link follow a client's renaming of the entry, and a move between pages names the page at once. With no such entry
  (a client hid it) the name falls back on the caller's fact, and until the shell has been told -- a moment after a
  hard reload, or for good if its config could not be read -- it is "Forms", which is true whoever is looking
  (`formsListingName`).
- **`backToListing` draws a component** (`BackLink`) so its label can follow the context; `backLabel(page, facts)` is
  the pure rule. Every other listing has one name.
- **The page's own copy says "the forms", never "your forms"** (`formsLoadFailureLead`, the raw editor's hint): it is
  read by administrators looking at other people's forms as often as by their owners.
- **Reports is offered by the same fact** (`administersClient`): its endpoints admit a client's administrator, so a
  `public` self-administrator is offered neither it nor the Clients entries.
- **Two ids to a client's menu overlay.** A rename or a hide of `forms` keeps meaning what the client's ordinary
  users see; `clientForms` is its administrators' entry, listed beside it in the Clients page's menu editor.

## A trait is named by one rule (issue #1520)

The Contains column and the form view's "Contains:" line name each trait as every page does: its data type's layout
`label` (one line since issue #1534), then its data type's `title`, then its id humanized (`summarizeForm`, through the kernel's
`branchDisplayName` and the catalog's `fieldLayouts`). So the listing says what the form's heading says, follows a
rename in the trait designer, and follows a client's alteration of a global trait's heading. The trait choice and the
entry headers in a friendly form use the same rule (`friendlyBranchLabel`); the sort by Contains still orders by the
trait ids.

**The names come from the row first** (issue #1543). Each listed row, and a single read, carries `traitNames`: its
entries' trait names worked out on the server by the same rule (`formTraitNames`) for the **row's** client, once per
client on the page, with that client's copy for a pulled heading. `summarizeForm` uses a row's name and falls back to the
catalog for a trait it has none for. This is for the cross-client view below, whose rows come from every client while
the catalog is the admin's own client's: from the catalog alone, another client's own trait would be named by its id,
and a global trait that client renamed would get the admin's client's name for it.

## A form's info page shows its core details (issue #1571)

A row's **View Info** opens the form through its client's core details workflow (`CoreDetailsEditPage`), so the page
shows the form's **core details** -- what every form carries -- and not the traits its workflows collect. A line under
the title says so (`coreDetailsNote`), unless the workflow has help of its own (`workflowHelp` copy), which says it in
the client's words instead; **View raw** shows everything. Listing the workflow traits on the page is in
`deferred-work.md`, under someone wanting a form's whole data in one place.

## The forms listing across clients (issues #668, #714)
<!-- Theme: #CLCPY -->

An `allClients` admin's forms listing starts as the **cross-client view**: every client's rows, a Client ID column
(headed as an id, since that is what it shows and sorts by, issue #1438), and the admin's *own* client's columns and
filters. Choosing a client in the `Client:` selector changes the **whole surface**, not just the rows: `loadForClient`
re-fetches the catalog with `client=X`, so the list, get, patch, delete and values endpoints become X's `/gedra/X/…`
copies, the columns and filter fields come from X's `formDocs`, and `client=X` still rides as the row filter. The path
alone also confines the rows to X: on a client's own copy, an `allClients` caller reads that client's rows
(`ReadScopeRules.forCaller` and `KdrCxt.clientFromPath`, issue #1575). The rules that follow from that:

- **A form's client comes from its gedra id, never from the selector.** The raw editor and the core details page read
  it with `formClientOf(id)` and ask the backend for the endpoint on *that* client's surface
  (`fetchFormEndpoint` → `/schema/endpoint?resolveClient=true&client=X`). The backend resolves the copy **and
  falls back** to the shared endpoint for a client that varies nothing — a page must never form a
  `/gedra/<client>/…` path itself, because only the catalog knows whether the copy exists (an exact lookup
  found nothing for `public`, and the edit page said the account had "no way to edit forms").
- **The workflow view and its save follow the resolved path.** `clientOfResolvedPath` says whether the view
  came from X's copy; the save posts to the same copy, so a core details workflow is edited under X's rules.
- **Every way home from a save goes through `formsListingReturn`.** The editors' Done (#726) and all three create
  surfaces -- the trait picker, the creation workflow, create-for-user -- go home the same way: the listing's search,
  sort and chosen client carried back, and the row flagged to flash. (A core details workflow *save* stays on the form;
  Done is what returns.) A create goes through `formsCreateReturn`, which adds two rules: it is marked (`hlc=1`) so a
  row the listing cannot show is announced rather than silently absent, and it returns **only while the user is still on
  the page the create was launched from** -- capture `routeParams()` before the `launch`, because a slow response must
  not pull someone out of wherever they went next. Keep the save button busy across that navigation (no `running =
  false`, no `finally` reset): the page unmounts a tick later, and a live button in between makes a second form.
  Create-for-user returns to the listing it was launched from, as it was (#762 review; see the next bullet).
  `formsArrivalNote` is the reading half, and names the reason (filter, sort, or neither). A new save surface calls the
  helpers; it does not build its own address. #758 was the workflow create keeping an in-place "Form created" page after
  the picker had moved to this return (#663).
- **"New form" creates in the caller's own client**, so it is not offered under a chosen client and the note
  under the selector says so. Creating for someone else is its own surface, **Create a form for a user**
  (`CreateForUserPage`, #727): pick the user, then fill in the form drawn from *that user's client's* create
  schema and posted to the admin on-behalf endpoint. Both create pages draw the client's create schema and
  omit the same fields (`formCreateOmittedFields`: the power flag and `user`) -- the user is the page's answer,
  appended to the body by `formForUserBody`, never a box in the form (#762 was one page drawing it). A success
  returns to the listing the page was launched from **as it was**, never to the user's client's listing: that
  switched the admin's Client selector to a client they never chose, and the cross-client view shows the new
  row with its Client column anyway.
  Within the caller's own client, **New form** and the creation workflow offer a user picker
  (`FormUserPicker`) to whoever `createsFormsForOthers` admits: an administrator or an editor (#1433).
- **A form kept to its owner** (`kdr:ownerOnly`, #1433) is read-only for an editor who is neither its owner
  nor a reviewer of its current work: the locks answer and the workflow view say `keptToOwner`, the raw editor
  draws the form uneditable, and the workflow pages offer no Save or Start, each saying why once
  (`TraitLockCopy.keptToOwner`, the server's refusal word for word).
- **Engaging opens the form for editing** (#1496). A workflow page's **Start** (issue #1691: the button says **Start**
  and its line *This form has not started this workflow...*, both the `workflowCell` copy's `start` and `notStarted`
  through `ShellFacts.workflowWords`, as the list's link says it; the endpoint and the code keep "engage"), once it
  succeeds, puts `edit=1` in
  the address (`engagedRoute`, replaced rather than pushed) before the view is read again, so the page lands where
  the Workflows column's link to a workflow that is the caller's turn opens (`workflowPageRoute(..., edit = true)`). The remount
  still asks `offersEdit` of the task shown, so a workflow whose open step is someone else's lands read-only.
- **A highlighted Start engages on arrival** (#1662). Its link carries `engage=1` (`HP.engage`, `workflowPageRoute(...,
  engage = true)`) beside the first task and `edit=1`; the workflow page engages as its Start button would, then takes
  the flag out of the address, so a reload or Back never engages twice. A form that can no longer engage (already in,
  or not eligible now) drops the flag, the refusal shown as Start's is.
- **A satisfied step says so, and offers Next** (issue #1694). On a workflow page that shows one task at a time beside
  the rail, a step whose rail entry draws the green check, with nothing unsaved (`stepSatisfied`), says *Nothing more
  is needed on this step* beside its Save, with the rail's check: what the step asks of anyone is there and valid.
  Never "complete", since the step can still be changed; the rail's tooltip says the same (`railExplanation`). Both
  are the `workflowCell` copy's `stepSatisfied`. When the workflow has a next task (`nextTaskAfter`), a link-styled
  **Next: <step> →** (`nextStep`) opens it as choosing it in the rail does -- the task being left gets its whole
  check, its working values stay -- and asks nothing about whether the caller may act on it, so on a finished workflow
  it steps forward one task at a time. It shows while the step is complete with unsaved edits too, and is not offered
  for a next task its display disables, as its rail entry is inert; a task's own availability, when tasks gain one,
  belongs in the same rule.
- **A workflow's listing is its own page** (`page=workflowForms`, issue #792): where a count on the Workflows
  page leads. `FormsPage` draws it in workflow mode -- the workflow as heading, a state switch, `← Workflows` --
  with the workflow and state riding the address as search keys (`formsDrillKeys`) so showing more, sorting and a client
  switch carry them, but kept by Clear and part of the page's history identity, never a chip. Every way home
  reads the listing off `from` (`formsListingOf`, honouring only the two forms listings), so a form opened
  there returns there; a `page=forms` address naming a workflow is routed to the workflow's listing, so old links
  still open where they did.
- **A keyword count on the Workflows page** (issue #1600): for each search keyword some listed workflow's calls to
  action may carry, the aggregate's `keywords` count the forms whose call to action in that workflow carries it --
  **Anyone's** (`any`) and **Yours** (`mine`) -- under the keyword's `columnHeader` ("To review"), a blank pair for a
  workflow that carries none. The words are the aggregate summary's `ctaKeywords` (across clients, a count's tooltip
  takes its own client's from `ctaKeywordsByClient`; the header stays the caller's) and the `home` file's
  `workflowCell.keywordAny`/`keywordMine`. A count leads to the workflow's **engaged** listing narrowed by
  `workflowKeyword`/`workflowKeywordWhose` (`workflowKeywordDrillRoute`), a drill-down pair of its own declared on every
  listing endpoint: the keyword's search parameter (`review=mine`) asks of every workflow and only a client's own listing
  takes it, while a cross-client caller's drill goes to the shared one. The line under the heading adds the chip's words,
  "-- Needs review: Yours" (`keywordCountWords`), and choosing a state, Engaged included, drops the pair. Backend and page
  count by one rule (`matchesWorkflowKeyword`), so a count and its forms cannot disagree.
- **Choosing a client drops the `user` scope** (`formsSearchForClient`): a user belongs to one client. A shared
  `client=X` link opened by a caller without `allClients` drops the selector from the search too
  (`formsInitialSearch`), since no control would let them clear it.
- **Publish the surface with its rows.** `loadForClient` stages the catalog, endpoints, search and first page
  in locals and publishes them together, so a failed switch leaves the previous surface whole rather than X's
  controls over the old rows.

## A status a search keyword sets reads in the client's words (issue #1596)

The listing's summary carries `ctaKeywords`: each search keyword's words (verb, filter label, column header, report
label, status text), resolved on the backend for the caller's client, so a client's overlay of the `home` file's
`ctaReview` namespace is what shows. The Status column's chip and the status filter read a status a keyword sets --
Needs Review is `review`'s -- through `formStatusLabel`, falling back to `FormStatus.label` before the summary arrives.
In the cross-client view a row's chip reads in **its own** client's words, as its trait names do: the summary adds
`ctaKeywordsByClient`, the same words for each other client with a row on the page, and the table picks the row's
(`CtaKeywordWords.byClientFrom`). The filter and its chip stay in the caller's client's words.
The verb is on the Workflows cell's links (#1598, below); the other words wait for the pages that show them (#1599, #1600).

## What each workflow asks of the caller, and the keyword search (issue #1597)

The backend half of the call-to-action cell, which the Workflows cell draws (#1598, below):

- **The classification** is `formWorkflowWeights` in the kernel: each workflow of a row's cell, from its states, weighed
  `yourTurn`, `waiting`, `available`, or `settled`. It replaces `WfColumnCategory` for the cell, which stays the
  workflow pages' phase (their counts and drill-down). The one input it cannot read from state, whether the caller may
  act on a call to action, is the rule of "Waiting on you" below: a workflow is the caller's turn exactly when a step of
  it waits on them. Asked `withStates`, a row carries its `waitingSteps` as it does with `needsMe`, and the page passes
  their workflow ids as the function's `mayAct`. So the page, `needsMe`, and the keyword search run one rule.
- **The keyword search** is a parameter per keyword on a **client's own** listing (`/gedra/<client>/formDocs`), for the keywords
  its workflows use: `review=mine` (the caller's turn for a review) or `review=any` (awaiting review, whoever acts). The
  shared listing has none, so the page offers the quick filters only where it lists one client. Applied before paging,
  so `numAvailable` counts the matches.

## The Workflows cell draws what each workflow asks of the caller (issue #1598)

The cell is the classification drawn: all of it in the `combined` layout, and the rest beside a Your turn column in
`split` (#1599, below). The page
decides nothing about what is actionable: `workflowCellOf` runs `formWorkflowWeights` with the row's `waitingSteps` as
its `mayAct`, joins each workflow to the summary, and gives each link its words. `FormsTable` only draws.

The page draws five kinds of link (`WorkflowCellKind`), in this order. The classification's `settled` weight is split
into its two kinds, since "settled" is not a word for anyone to read.

| Kind | Looks | Says |
| --- | --- | --- |
| Your turn | a filled accent pill, opening the task ready to edit | a verb and the workflow: the CTA keyword's verb ("Review", the client's words), else the step's `actionText`, else "Continue" -- or, for a highlighted Start (#1662), "Start", its link engaging the form and opening the first task |
| Available | an accent outline, opening the workflow to engage | "Start" and the workflow |
| Waiting | a muted link, opening the task to look at, with its reason under it | why, from the row's `waitingReasons` or the summary -- also a highlighted Start that waits on someone else (#1690), opening the workflow without engaging |
| Finished | small, with a check, opening its last task | the workflow |
| Not available | small and faint, opening a dialog with the reasons | the workflow |

- **A muted link always says why.** The backend gives each row `waitingReasons` (with `withStates`): for each step
  waiting on someone else, whom (`WfWaitsOn`: `filter`, `approval`, `owner`, or `others`) and, when the CTA filter is
  what keeps the caller out, its label resolved in the row's client ("Waiting on a regional reviewer"). A workflow
  frozen outside its relevancy window has none; the summary's entry for it carries `opensAt` or `closedAt`, and the
  link says "Opens in 3d" or "Closed 2026-10-30", the moment as the table writes one. A step the row gives no reason
  for (a call to action not computed yet) says "Waiting". `waitingReasonOf` picks the words.
- **Every word is copy**: the `home` file's `workflowCell` namespace (`WorkflowCellWords`), which a client overlays --
  the verbs and reasons, each kind's heading (`...Group`) and count (`...Count`, over `${n}`), the fold's button
  (`more`) and its popovers' titles, the tooltips, and the reasons dialog. `opens` and `closed` are templates over
  `${at}`. Until the copy arrives the shipped words show.
- **A fixed order**: by kind as in the table, then the workflows' declared order (the summary's), never the order the
  state entries arrive in. So as people act, a link moves only when its kind changes, and the rest keep their places.
  It is the page's order, not `WfWeight`'s, which puts waiting ahead of available.
- **A crowded cell folds** (`workflowCellFold`): up to four links are all drawn; past four it draws three and folds the
  rest behind "+N more", so a fold hides at least two. **The caller's turn never folds**: those links come first, are
  all drawn however many there are, and count toward the three -- two of them, two to start, and two not eligible draw
  the two and one to start, and fold three. The fold opens a popover, "More workflows", with each kind under its own
  heading. In the `split` layout the cell holds no turn of the caller's, so it folds sooner: up to three drawn, past
  that two and "+N more" (`WorkflowCellFoldAt.split`).
- **A narrow screen folds the whole cell to its counts** ("1 your turn · 2 available", `workflowCellCounts`), a button
  opening all the links, grouped the same way. `app.css` chooses which shows (below 640 px), so the cell draws both.
- **Only the caller's turn opens in edit mode** (`linkEdits`): a waiting link opens its task to be looked at, which the
  drilled listing's double-click follows too.

## The split layout: a Your turn column, and the keyword filters (issue #1599)

How the cell is drawn is the client's **listing setting**, `workflowLayout` (`combined` or `split`, default `split`), in
the `kdr:listingDef` slot: one entry per client, holding what applies to its listing as a whole. A stored entry stands
over its code's or template's, which stands over the default (`GedraConfigCollector.listingFor`). The listing's summary
always carries it (`GDF.workflowLayout`); the page reads it and decides nothing, and the setting changes the drawing,
never the classification.

- **The Your turn column** (`split` only, before the Workflows cell): the strong links alone, never folded, beside the
  rest. It sorts on the backend by how many of a form's steps wait on the caller (`GSORT.yourTurn`, worked out over
  the whole matched set before paging, as `needsMe` is), most first on the first click, so the caller's work comes to
  the top. A row whose every link is the caller's turn leaves the Workflows cell empty rather than a dash.
- **Its header** (`yourTurnHeader`): the one keyword in use's `columnHeader` ("To review", the client's words), else
  -- none in use, or several -- "Your turn" (`workflowCell.yourTurnGroup`), with the count of forms that are the
  caller's turn, "To review (4)". The count (`GDF.yourTurnCount`) comes with the workflow summary: what `needsMe`
  would list over the client's scope, whatever the search, so it does not change as the reader filters. The keywords
  in use are those whose parameter the listing's endpoint declares (`keywordsInUse`), so the shared listing has none.
  One keyword in use heads the column even when some of its links carry no keyword (a save, say).
- **The keyword filters**: one filter-well group per keyword in use, headed by its `filterLabel`, choosing **Yours**
  (`review=mine`) or **Anyone's** (`review=any`, for someone overseeing the work), with a chip, "Needs review: Yours"
  (`keywordFilterChips`). The choices' words are `workflowCell.keywordMine` and `keywordAny`.
- **On a narrow screen** only the Your turn column has to survive: the Workflows cell folds to its counts as ever.
- **The Clients page sets it** (`clients-page.md`).
- **The listing is short of width, not of height**, so with the Your turn column the others gave some back: the Actions
  links stack one a line (`row-actions-stacked`), halving the column (115 px, 165 in a drilled listing); `Contains` is
  guaranteed 160 px rather than 200; and each display column is 144 px rather than 160.
- **The columns a reader acts in come first** after the client's display columns: Your turn, Workflows, then Status;
  then Contains, the owner, Client ID, the two dates, and Actions. So on a demo screen about 1100 pixels wide what
  asks something of the reader is in view without scrolling sideways. With no display column, Contains stays first,
  since the first column is pinned left as the row's identity.

## A column's filter and cells follow its field (issues #1500, #1508)

The listing's summary carries each column's `choices` when its `display` is a lone `${field}` over a field that
offers them (`ListingColumn.choices`, from the column's search shape, which the backend resolves
against the client's schema). The page reads them from the columns it already has; there is no second fetch. A column
over a number field arrives with kind `number` even when its usage declares none, so the page draws a range for it;
the query schema says the same.

- **The cell shows the label** (`presentedValue` → `presentColumnValue`), unless the column has a presentation of its
  own. The row's value stays the stored one, which is what the backend searches and sorts by.
- **The filter box suggests the choices by label** before anything is typed, then the choices matching what is typed
  ahead of the values the values endpoint finds (`valueSuggestions`, pure). It stays an `AutoComplete`, open to
  anything typed or pasted. A box is drawn even where the surface has no values endpoint, when the column has choices.
  A pick fills the label, which the backend's search matches as well as the value.
- **A boolean column is a yes-or-no** (kind `boolean`, issue #1508): its cells read Yes or No (`yesNoText`, unless
  it has a presentation), its filter is a closed Yes/No `Select` cleared for any, as the Status filter is, and its
  chip says `First talk: Yes`. Closed, unlike a choice column's box: a boolean has only two answers, and none can
  have gone stale.
- **A date column's range is two day pickers** (`dayBoundInput`), writing the `yyyy-mm-dd` the bound takes; the
  backend reads a day as the upper bound as the whole day. A bound that does not read as a date (an old link) gets
  the text box instead, so it can be seen and cleared. The number range keeps its text boxes.

## Waiting on you (issue #1637)

The listing's `needsMe` keeps the forms with a step **waiting on the caller**: an engaged workflow, not done, whose
current task is theirs to take. Done is the cell's finished (`workflowDoneIn`): every task complete, or the workflow has
asserted `kdr:finished`, which it may do before its last task -- so a workflow the cell draws finished waits on nobody. The backend judges it (`WaitingSteps`), from each form's stored state, by the rule the save and
approve gates apply, narrowed to the person the step names:

- an approval waits on whoever may approve it (by default a reviewer who does not own the form), and its CTA filter;
- a save whose task names someone (`saveWhen`) waits on whoever that admits, and the filter;
- a save that names nobody waits on the form's **owner**, not on every administrator its gate would admit;
- a task with neither a save nor an approval waits on nobody;
- a workflow's **Start** (issue #1662) waits on whoever could act on its first task, by the same rules, when the
  workflow highlights it (`highlightStart`) and the form is eligible for it, not yet in it, and open to new forms. Such
  a step carries `startsWorkflow`, so its link (here, and on Home's card) engages the form first. It only routes: who
  may engage is the eligibility tests' alone, and no CTA filter applies before the form is in. To anyone the first task
  does not ask, such a Start is **waiting** rather than available (issue #1690): the summary says the workflow
  highlights its Start (`highlightStart`), and `waitingReasons` gives the first task's reason, so nobody is invited to
  begin another person's work.

And a save waits on nobody the owner-only rule (issue #1433) keeps out: an editor, on a form kept to its owner, who is
neither its owner nor a reviewer of its current work. An approval is not a change to the form, so that rule never keeps
anyone from one.

Since #1597 this is also the classification's one input it cannot read from state: a workflow is the caller's turn
exactly when its step waits on them, so the Workflows cell's strong links and a keyword search's `mine` (`review=mine`)
follow the same rule.

Each row listed with it, or with `withStates`, carries its `waitingSteps` (the workflow, the task, and what it asks of
the caller in words); a `withStates` row also carries `waitingReasons`, whom each other step waits on (issue #1598).
Two controls set it, and Clear removes it. The **toolbar toggle**, beside Filters, is the one-click way to the forms
Home's card lists (`waitingToggle`, `withWaitingToggled`): "Waiting on you (3)" in the `forms.waitingToggle` copy, an
accent outline off and the filled your-turn pill on, applied at once over the applied search (a draft not applied
stays a draft). Its count is the summary's `yourTurnCount`, every form waiting on the caller whatever else is
filtered, so it does not move as a search narrows; at zero it stays, dimmed, and while on it can always be turned
off. Since the pressed toggle says the filter is on, the filter takes no chip. The filter well's **Waiting on you**
select (`Only those`, cleared for any) is the other, beside the rest of the filters. Both are offered where a step can
wait -- the caller's client has a normal or core details workflow (the shell's `hasWaitingWorkflows`, the rule Home's
card is offered by) -- to a caller who sees across clients, and whenever it is applied, so it can be cleared. It
combines with every other filter and the search, and rides the address like them. Home's "Waiting on you" card links
to this listing (`waitingListingRoute`), where the toggle then shows pressed.

The backend judges each form once per request (`WaitingSteps.of` keeps each form's answer), so a row's steps are the
answer that let it through the filter. It asks whether the caller owns a form only once a workflow of it has a step to
judge, and in production reads the owners' identities for all of a listing's rows in one batch (`readOwners`).

Within the caller's read scope, like every listing, and `review=mine` too: a reviewer who is neither an administrator
nor an editor cannot list other people's forms, so their reviews do not show here (`deferred-work.md`).

