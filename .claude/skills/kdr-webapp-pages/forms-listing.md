## The forms listing is named for whose forms it shows (issue #1091)

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

## The forms listing across clients (issues #668, #714)

An `allClients` admin's forms listing starts as the **cross-client view**: every client's rows, a Client column,
and the admin's *own* client's columns and filters. Choosing a client in the `Client:` selector changes the
**whole surface**, not just the rows: `loadForClient` re-fetches the catalog with `client=X`, so the list,
get, patch, delete and values endpoints become X's `/gedra/X/…` copies, the columns and filter fields come from
X's `formDocs`, and `client=X` still rides as the row filter (a client path alone does not narrow an
unrestricted scope). The rules that follow from that:

- **A form's client comes from its gedra id, never from the selector.** The raw editor and the survey page read
  it with `formClientOf(id)` and ask the backend for the endpoint on *that* client's surface
  (`fetchFormEndpoint` → `/schema/endpoint?resolveClient=true&client=X`). The backend resolves the copy **and
  falls back** to the shared endpoint for a client that varies nothing — a page must never form a
  `/gedra/<client>/…` path itself, because only the catalog knows whether the copy exists (an exact lookup
  found nothing for `public`, and the edit page said the account had "no way to edit forms").
- **The workflow view and its save follow the resolved path.** `clientOfResolvedPath` says whether the view
  came from X's copy; the save posts to the same copy, so a survey is edited under X's rules.
- **Every way home from a save goes through `formsListingReturn`.** The editors' Done (#726) and all three
  create surfaces -- the trait picker, the creation workflow, create-for-user -- go home the same way: the
  listing's search, sort and chosen client carried back, and the row flagged to flash. (A survey *save* stays
  on the form; Done is what returns.) A create goes through `formsCreateReturn`, which adds two rules: it is
  marked (`hlc=1`) so a row the listing cannot show is announced rather than silently absent, and it returns
  **only while the user is still on the page the create was launched from** -- capture `routeParams()` before
  the `launch`, because a slow response must not pull someone out of wherever they went next. Keep the save
  button busy across that navigation (no `running = false`, no `finally` reset): the page unmounts a tick
  later, and a live button in between makes a second form. Create-for-user returns to the listing it was
  launched from, as it was (#762 review; see the next bullet). `formsArrivalNote` is the reading half, and
  names the reason (filter, sort, or neither). A new save surface calls the helpers; it does not build its own
  address. #758 was the workflow create keeping an in-place "Form created" page after the picker had moved to
  this return (#663).
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
  draws the form uneditable, and the workflow pages offer no Save or Engage, each saying why once
  (`TraitLockCopy.keptToOwner`, the server's refusal word for word).
- **Engaging opens the form for editing** (#1496). A workflow page's **Engage**, once it succeeds, puts `edit=1` in
  the address (`engagedRoute`, replaced rather than pushed) before the view is read again, so the page lands where
  the Workflows column's link to an engaged workflow opens (`workflowPageRoute(..., edit = true)`). The remount
  still asks `offersEdit` of the task shown, so a workflow whose open step is someone else's lands read-only.
- **A workflow's listing is its own page** (`page=workflowForms`, issue #792): where a count on the Workflows
  page leads. `FormsPage` draws it in workflow mode -- the workflow as heading, a state switch, `← Workflows` --
  with the workflow and state riding the address as search keys (`formsDrillKeys`) so showing more, sorting and a client
  switch carry them, but kept by Clear and part of the page's history identity, never a chip. Every way home
  reads the listing off `from` (`formsListingOf`, honouring only the two forms listings), so a form opened
  there returns there; a `page=forms` address naming a workflow is routed to the workflow's listing, so old links
  still open where they did.
- **Choosing a client drops the `user` scope** (`formsSearchForClient`): a user belongs to one client. A shared
  `client=X` link opened by a caller without `allClients` drops the selector from the search too
  (`formsInitialSearch`), since no control would let them clear it.
- **Publish the surface with its rows.** `loadForClient` stages the catalog, endpoints, search and first page
  in locals and publishes them together, so a failed switch leaves the previous surface whole rather than X's
  controls over the old rows.

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
