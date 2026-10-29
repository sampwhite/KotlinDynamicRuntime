## The Clients page (issues #903, #905)

`/wa/clients` lists the clients an administrator oversees, from `GET /clientAdmin/clients/overview`
(`ClientsApi.listOverview`, rows parsed by the pure `parseClientOverview`): an `allClients` administrator sees
every client the node knows of -- present, not enabled here, dropped by a check, or known only from stored
configuration. A client-scoped one is answered with their own client alone, and the page shows it as that
client's detail rather than as a listing of one row (issue #1082, below). It is the **scoped** surface on purpose:
that section admits both kinds of administrator and an `allClients` holder is unconfined there, so the calls need no
branch on who is asking. Per row: the load status (`clientLoadText`), where the
definition came from (`clientOriginText`: source, stored, and how many stored configurations are loaded), live
forms, active users with the unclaimed ones told apart (`userCountText`), and workflows. Counts are client-wide
and count active users only -- disabled and deleted users are never in the cache the count reads. A present
client's Forms and Users counts link to the listings behind them (`clientFormsHref`, `clientUsersHref`), in the
listing and again in the detail's summary (`clientSummaryHref`): with the client chosen or filtered for an
`allClients` administrator, bare for a scoped one -- who meets them only in the summary -- whose listings are their
own client already (the Users page draws no client filter for them, so a `client=` in the address would be one they
could not clear). The Workflows page takes no client, so that count is plain text until it does.

**An administrator of one client opens it in place** (issue #1082): with no `c=` in the address, a caller who may
administer and does not see across clients is shown their own client's detail, not a listing of one row
(`clientsOpenId`, pure). So `/wa/clients` and `/wa/clients/<their client>` are the same page for them, drawn
with no `← Clients` link -- there is nothing behind it -- and the copy editor's leave guard treats both addresses as
staying (`staysOnClientPage`) -- which it must whatever else is done, since the menu item is that bare address and
is one click away from the named one.

**The menu says what the page will open.** The home menu declares two items for the one page, mutually exclusive by
cfact as the two Debug entries are: `HMENU.clients`, "Clients", for an administrator who sees across clients
(`isDeploymentAdmin`), and `HMENU.myClient`, "My client", for any other administrator of a client
(`administersClient`, issue #1091) -- "Clients" promised a list that is no longer there. Both route to `pageClients`, so the app bar's current-page match is unchanged. They are two
ids to a client's menu overlay: a rename or a hide of `clients` does not touch `myClient`, which the Clients page's
menu editor lists beside it. Two callers keep the old path: an `allClients` administrator, who has a listing, and a
`public` self-administrator, whom the overview endpoint refuses -- shown as that refusal rather than as a detail that
would refuse piece by piece. Nothing is opened in place until the shell config has said who is asking.

**One client** (`c=<id>`, issue #906) shows, on the same route with `← Clients` back: the listing's facts for it,
its definition from `/clientAdmin/client/definition` (`clientSummaryRows`, drawn with `readOnlyField`; the
operator-only `audience` and `usageType` (#820) are noted "(set by the platform)" for a scoped administrator, so
the later editor disables exactly those), the issues its checks forgave, and the stored configurations this node
holds for it -- the full-scope bundles listing naming the client for an `allClients` administrator, the scoped
one otherwise. Each configuration row (`StoredConfigTable`, issue #1001) says whether its latest revision is
**live** by the client's tier (`bundleLiveText`: "Live", "v3 draft; v2 live" for a published-only client --
every client with a sandbox is one -- or "Live, unpublished" for one on its latest revision; the summary carries
`publishedVersion`, the overview row `publishedOnly` and `staticHere`). **Publishing is of a group** (issue #1353):
a row whose latest revision is unpublished has a box and nothing else (`selectableBundles`), and a bar above the
table selects -- **Select all unpublished** (`toggleAllUnpublished`), which also clears -- and acts on the selection
(`selectedBundles`, which drops a bundle published meanwhile): **Publish selected (N)** publishes it in one request,
judged together (`ClientsApi.publishBundles`, issue #1352), so bundles sound only as a set -- a workflow moved from
one to another -- go live together, and **Check impact** reports on exactly that selection. What the bar offers is
`publishGroup` (read off `bundleAction`): publishing on a client without a sandbox and on a sandbox's page -- which
lists its parent's bundles, the configuration it runs, and publishes them there -- and check-only elsewhere, with
the note once in the bar rather than on every row: a client with a sandbox publishes from it, after previewing --
the word "sandbox" leads to the sandbox's page, directly for an `allClients` administrator, and for a client-scoped
one by opening the sandbox first (a fresh session as their user there) and landing on that page -- and a client
static in production takes nothing stored, so nothing is selectable. A publish reloads the client.
A publish is judged against the client's **stored data** (issue #935): one that would affect it is refused with an
impact report on the whole selection (the envelope's `errorCode` `IMP.refusedCode`, the report in its `extraData`,
which `ApiError` carries for this), and the table opens it in a dialog (`ImpactReportDialog`, titled by the
selection's names, `bundleNamesText`: `impactSummary`, a line per finding from `impactFindingText`, at most five form
ids per finding beside the full count) whose **Publish anyway** resends the same selection with `acknowledgeImpact`
(`publishBundleRequest`). **Check impact** (`BundleAction.checkImpact`) fetches the same report before any publish
(`ClientsApi.bundleImpact`): beside Publish selected where publishing changes what the client runs, and on a client
with a sandbox beside the "publish from its sandbox" note too, with no publish button in the dialog there. Each
form id opens the raw trait editor in a new tab (`impactFormHref`) where the session can read it
(`impactFormsOpenable`); a session in the sandbox is a user of the sandbox and cannot read its parent's forms, so there
the ids stay plain and a note says to open them from the parent's own page. The `impact-demo` simulation leaves a harmful draft to look at -- run it from the
**Simulations** page, which signs you in as the client's administrator, or `kdr-probe --url <your server> impact-demo`. The definition and the configurations are fetched apart and keyed on the open id with a
monotonic token, so a client this node does not carry still shows what the listing knows above the retrieve's
404, and a slow answer for a client the user moved on from is dropped. The constants the frontend reads these by
(`CFEP`, `ACEP`, `CCT`, `GCI`) live in the kernel for that reason.

**The detail's layout** (issue #1397; the pure half is `ClientDetailLayout.kt`, covered by `ClientDetailLayoutTest`).
The page has a header, and then panels: each a keyed `section` (`clientPanel`), set apart as a copy file is.

- **The header** holds the client's name. Next to it are its chips (`clientStatusChips`: the load, and how it relates to a sandbox), then its links, **N forms · N users · Reports** (`clientHeaderLinks`). The header also holds:
  - a line saying how a save takes effect here (`clientSaveEffectLine`);
  - a banner, linking down to publishing, while any configuration could be published (`unpublishedBannerText`);
  - the issues, as a warning block;
  - links to the panels, which scroll rather than set a hash.
- **The panels**, in order:
  1. **Definition**: the editor first, then the read-only rows. The rows are grouped as the editor's sections are (`clientSummaryGroups`: About, How it behaves, Platform), plus *Where it runs*. The groups sit side by side where there is room.
  2. **Forms and data**: Traits, then Workflows, then Form states.
  3. **Copy**.
  4. **Images**: Web assets, then Files.
  5. **Sandbox and publishing**: Sandbox, then Stored configuration. Drafts made in the panels above are published here.
     Under the table, **Open every bundle** leads to the bundle editor (below).
  6. **Menu**: closed (`Disclosure`), its editor read only once opened. By default it lists only the items a client's people may be offered (`menuItemsForPeople`). It hides those shipped for the deployment's own people, which the items listing marks `deploymentOnly` from `MenuAudience.deployment`.
  7. **Retirement**: last, as a danger zone.
- **What is not repeated.** The definition does not repeat what the page shows elsewhere: the counts are links in the header, the load is a chip, and the workflows have a panel. The traits are listed in the definition only where the Traits section is not drawn.
- **Explanations.** Each panel and each definition group has a "What is this?" from the `clients` file (`PFRAG.clientsExplained`).
- **Every child of the card is keyed.** A section keeps its state by what it is, so issues appearing or clearing do not remake the editors below.

**Sandbox** (issue #932, `SandboxSection` in `ClientsPage.kt`): the detail says whether the client has a Shadow
Sandbox and offers **Add a sandbox** / **Remove the sandbox** (`sandboxControl`), posting
`/clientAdmin/client/sandbox {client, sandbox}` (`ClientsApi.setSandbox`), which sets the flag in the client's
stored definition, **publishes** it and reloads -- the published definition decides both the sandbox and the tier,
so a draft would change nothing -- and is refused for a source-defined client (its flag is set in source), for a
sandbox, and while that config has unpublished changes. A client with a sandbox runs only published configuration.
A sandbox's own detail says whose it is. It also offers the way back (issue #1422), in the same panel as the publishing
bar's "Publish from its sandbox" and whatever is published (`sandboxWayBackTarget`): **Back to <client>**, which in a
sandbox session leaves it as the app bar's **Back to** does (`ShellFacts.sandboxWayBackUserId`, the same user) and
lands on the client's page, and for an `allClients` administrator looking from outside is a plain link. It is in this
section rather than beside the publishing bar because the bar is not drawn for a sandbox whose client stores no
configuration (one defined in source code, as the sample's acme is). In the listing each sandbox follows its parent
(`withSandboxesBesideParents`) with a "sandbox of <id>" note (`sandboxRowNote`); the overview rows carry
`sandboxOf` and `hasSandbox`. A scoped administrator has no listing (issue #1082), so only an `allClients` one
sees sandbox rows.

**Retirement** (issue #1182, `RetirementSection` in `ClientsPage.kt`): under Sandbox, for an administrator who sees
every client -- the platform's switch, `POST /admin/client/retirement/set {client, retired}` (#1111,
`ClientsApi.setRetired`). **Retire** is a `danger` button behind a confirmation saying what follows
(`retireConsequence`): the data is kept, the users can no longer sign in, open sessions end, and the sandbox goes with
it. A retired client shows **Bring back** instead, unconfirmed since it only restores. Either is live at once. The
action is offered only where the switch can take it (`retirementControl`, pure): a sandbox, a client defined in source
code, a customer's root (the overview row's `rootOf`), and a definition whose configuration has unpublished changes
each say why instead. A retired client stays in the listing, as "Retired", which is the way to its page and back.

**Files** (issue #1176, `FileRefsSection.kt`, its pure half in `FileRefs.kt`, covered by `FileRefsTest`): under
Web assets and drawn as they are, a client's **file refs** -- the named images its configuration holds (the calls
are listed under "A client's file refs" in `client-web-assets.md`). A row a ref: a preview, its id with a mark for what is unusual
(`fileRefTags`: a draft, a missing file, one the client was shipped with), its description, what the file is
(`fileRefHoldsText`), where it is by its source and which configuration holds it (`fileRefWhereText`), and the
address it is served at; then **Replace…** (and **Use a kept file…** where a root is open to clients), and for a
ref a stored configuration holds, **Reword…** and **Remove**. Under the list, the way to add one: an id and a description, then **Upload…** -- or, where
`…/files/takes` lists a root open to clients, a root and a typed path. What is decided here, and why:

- **A section, not a page of its own**, because it mirrors Web assets. A client may hold 200 refs; previews load
  as they near the screen (`loading="lazy"`), and there is no paging or filter yet.
- **Unlike a web asset, a ref is configuration.** For a client with a sandbox a change is a **draft**, and the list
  is the *sandbox's* view: the client's latest, drafts marked. So `fileRefsIntro` says how a change takes effect
  for this client, and that a file *removed* as a draft is no longer listed though the client's people are still
  served it. Drafts are published from the **Stored configuration** table below the section, not from here.
- **A sandbox's own page edits too** -- unlike Web assets, which a sandbox may not change. A change made there is
  a draft of its parent's, and the calls are sent with the sandbox's id as they are with any client's.
- **A client that takes nothing stored on this node** (`staticHere`, which for a sandbox is its parent's answer)
  is listed with no actions, and told so and nothing of drafts.
- **An id the client already has is refused by the add form**: that ref is replaced, reworded or removed from its
  own row, where it can be seen. Where a root is open to clients, the row offers **Use a kept file…** beside
  Replace… (issue #1189, `fileRefRepointable`): a root and a typed path in place under the row -- the add form's
  own fields (`keptFileFields`) -- sent as `…/files/local` with the ref's own id and no description, so the
  description stays. A shipped ref becomes a stored override, which Remove takes back off; an upload is replaced;
  for a client with a sandbox it is a draft, as every change is. `fileRefRepointProblem` refuses the file the ref
  already names, before anything is sent; where the editor starts is `fileRefRepointStart`. The section has one
  row editor open at a time (`editing`: which ref, and Reword… or Use a kept file…), so opening one closes any other.
- **What a ref takes is asked for separately**, so the list is drawn even when `…/files/takes` cannot be had;
  nothing is added from the page then, and it says so. At the limit of refs the form gives way to a sentence,
  since an upload would be sent whole and then refused -- one that says what can be done (`fileRefAtLimitText`,
  issue #1169): a ref the client was shipped with cannot be removed here, so a client whose every ref was shipped
  is told to replace one, or to have its source configuration changed, and the backend's refusal says the same.
- **The section keeps its place among the page's.** It and Web assets are keyed, and the Issues block above them
  is one element with or without issues in it: mending a file ref clears an issue, and were Issues two elements
  that come and go, every section after it would be remounted and lose what was typed in it.

**Web assets** (issue #1130, `WebAssetsSection.kt`, its pure half in `WebAssets.kt`, covered by
`WebAssetsTest`): under Sandbox, for a client this node carries, each slot of the client's own web assets -- the
favicon and the brand mark -- from `GET /clientAdmin/client/web` (`ClientsApi.webAssets`, parsed by
`parseWebAssetSlots`; wire names `WAS`, the file's `FMETA`, both in the kernel). The listing is **metadata only**:
per slot a preview, what it holds (`webAssetHoldsText`: type, size, the name it was uploaded under), who uploaded
it and when (`webAssetUploadedText`), what it takes (`webAssetTakesText`, from the listing's `kinds` and
`maxSize` -- **do not hardcode either here**; a size is worded by the kernel's `byteSizeText`, which the backend's
refusals use too), and **Upload…** / **Replace…** and **Remove**. Picking a file *is*
the upload: `POST /clientAdmin/client/web/upload` as `multipart/form-data` (`Http.uploadApi`, the one multipart
call outside the endpoint catalog; both build their body with `formDataOf`). A file over the slot's size is said
before it is sent (`webAssetFileProblem`); whether it is the image it claims to be is the backend's to say, and
its refusal shows beside the slot in its words. **There is no draft and no publish** -- a web asset is not stored
configuration, and the section says so -- so a change is live for the whole client at once. That is why
**Remove** is a `danger` button behind a confirmation that names where it acts (`webAssetRemoveQuestion`:
"Remove the favicon of acme (live data)?", issue #1049's rule), where "Remove the sandbox" beside it is not: this
one has no draft to come back from. Whichever control was used is the one that shows the work (`webAssetBusyKey`),
and the slot is redrawn from the change's own answer (`withWebAssetSlot`) before the re-read arrives. Each success
bumps the refresh generation, which is what makes the shell redraw its mark and tab icon. A change that answers
after the page has moved to another client is dropped, result and refusal alike. What is running, the open
confirmation, and the last failure or refusal beside its row are one `RowActionStatus` (issue #1388, `useRowActions`),
shared with the Files section: change it only through its transitions, which the hook applies through the state's
transform, since an action ends after an await. A slot showing the client's **web resources package**'s image
(issue #1301: the listing's `source` is `package`, beside the `packageId`) says it was shipped with the client's
source code rather than who uploaded it, and offers **Replace…** but no Remove (`webAssetFromPackage`,
`webAssetRemovable`): an upload stands in front of it, and removing that upload -- whose confirmation says the
package's image comes back when the listing's `packaged` says it ships one (`webAssetRemoveConsequence`) -- shows it
again.

The **preview is not the static address**: that serves the caller's own client's, and the client on this page
need not be the caller's. It is the named download, `GET /clientAdmin/client/web/download {client, slot, hash}`
(`webAssetPreviewUrl`), carrying the image's hash so that a replaced image is a new address -- a browser redraws
an address it has already drawn without asking again. Beside it, `GET …/web/download/meta {client, slot}` answers
what the image is without the image (issue #1131); the page reads the listing instead, which carries the same. A
**sandbox** shows its parent's images and may change none (a change would be live for the parent's people): its
page draws the slots with no actions under a note saying where to make one, and the backend refuses the attempt
anyway.

**Copy & menu** (issue #917): what a client's own configuration changes about what its people see, from
`GET /clientAdmin/client/overrides` (`ClientsApi.overrides`, parsed by the pure `parseClientOverrides`; the wire
names are the kernel's `COV`). Each copy row says who set the key -- `config (source|stored)`, `setByText`, or
`config (template <id>)` for a value the client inherits from the template it extends, issue #945 -- the shipped
value, and for a stored change over the client's own source value that source value; an orphan is a key no shipped
file declares, so its value is never read. The copy is drawn by the file view below; the interface items the client
changes are a table (`menuChangeText`: added, hidden, shown, renamed, reordered; `blockValueText`; the shipped label
rides on the row as `baseLabel`, so a row that only hides an item still names it; "Set by" names every config that
set a field, a stored one first -- `blockSetByText`), each value rendered with `MarkdownInline` and clamped to one
line by `.cell-clamp` with the whole on hover -- clamped after rendering, never cut before it, since a cut through a
link would show its syntax. The listing's **Customized** column says how much (`customizedText`, "3 copy,
2 menu" -- "menu" because every block a client can overlay today is one) and links to the detail; an `allClients`
administrator also gets **Copy & menu across clients** (`ov=1` on the same route, `overridesAcrossHref`): one
retrieve per listed client, grouped by the pure `overridesAcrossClients` into file-or-block → key → the clients
overriding it. A scoped administrator has one client and is never shown that view, whatever the address says.

**How a save takes effect, said before and sent with it** (issues #930, #1176, #1191). Every editor on a client's
page that saves its configuration -- the copy, the menu, the definition, the traits, the workflows, the files -- is live at once for a client
that runs its latest configuration, and a draft its sandbox shows for one with a sandbox (or on a sandbox's own
page). The page decides which **once**, from the overview row it last read (`savesAsDraft`, in `clientDetail`), says
it beside the save (`saveEffectText`) and sends it as `mode` (`expectedMode`), so the wording and what is sent cannot
differ. The backend decides at the request (`ClientStoredEdit.target`): a save sent as a draft that would be live is
a 409 and not made, and the editor answers it by re-reading the page, which then says how a save takes effect
(`saveConflict`); one sent as live that is a draft goes ahead, and its note says so (`savedNote`). A save naming a
sandbox the node no longer carries is a 409 too. Send `mode` with any new save you add. Design View sends none: on a
client with a sandbox it refuses to save, so its only drafts are a sandbox's, which that last refusal covers.

**Editing the copy** (issues #918, #1062; `CopyFileEditor`, its pure half `CopyFileView.kt`, covered by
`CopyFileViewTest`): the client's copy **file by file**. It reads `/clientAdmin/client/copy/keys`
(`ClientsApi.copyKeys`; every shipped key with the client's value, backend files included, since `mail` is never
served) and joins each key to its overrides row (`copyKeyRows`, which also places each orphan in its file). Choosing
a **File** -- the files the application shows first, each with where (`copyFileChoices`, `copyFileLabel`, from the
keys' `shownOn`), the rest under "Not shown by this application" (issue #933), which the view repeats over such a
file -- lays out every namespace and key with the value the client reads, in a panel of its own so the file reads
apart from the rest of the page (`.copy-file`: a shade darker than the card, headed in a tint of the accent by the
file, where it is shown and its key count -- `copyFileCountText`). Each value is rendered as Markdown in a box that
scrolls on its own past a few lines (`.copy-value`), or **As written** per key; placeholders and pulls show as written,
since there is nothing to fill them from. **Find** searches every file's keys *and values* instead (`copyRowMatches`,
`copyFileGroups`) -- an administrator usually knows the words on screen, not the key -- and **Only changed** keeps
the keys the client's configuration sets. The view opens on **no** file (`copyViewOpenFile`): a preselected one read as
nothing having been chosen, and choosing it again changed nothing. With no file chosen, **Only changed** lists every
file's changed keys -- the client's customizations at a glance (`copyViewSpansFiles`). A changed key says who set it (`copyRowStatus`), with "What it replaces"
(the shipped value, and the source value a reset returns to) a click away.

**Edit** opens the key's text with a **Preview** (the hint says which template syntax the file takes, by its
`audience` -- `copySyntaxHint`, issue #1001), and, for a client with file refs, **Insert an image…**: the client's refs
(`fileRefInsertChoices`), the chosen one put in as `![<its words>](ref:<id>)` where the cursor last was
(`insertAtCaret`; at the end, on a line of its own, when the box has not been clicked). Every value the editor draws
-- preview, rendered value, what it replaces -- draws a ref image from the **page's client's** refs, by the named
download (`refPreviewAddressFrom`), never the static root, which answers the caller's own client (issue #1186).
**Reset**, for a stored value only, removes it -- data cannot remove
what source code or the shipped file says (`copyRowOffersReset`). Neither saves: each is a **pending** change
(`pendingAfterEdit`; typed back to the value it is no change), marked down the key's left edge with a **Revert**, and
the panel shows it as it will read once saved (`copyRowShownValue`). A sticky bar saves every pending change to the
file **together** -- `POST /clientAdmin/client/copy/apply` (`copyApplyRequest`), one stored config (the one already
setting any of the keys, else the first overlaying the file, else `copy`, created on first use), one trial, one
publish and reload, so the changes go live at once -- or discards them. A save is of one file, so while one file has
pending changes another's keys are not editable (`copyRowEditable`), and opening a key's editor makes its file the
chosen one. For a client with a Shadow Sandbox (issue #930) -- always published-only -- the save is a **draft** of
the parent's configuration that the sandbox shows, and the result's `mode` (`EDM.live` / `EDM.draft`) says which,
which `copyAppliedNote` turns into the note; called in the sandbox, it edits the parent's. A batch the checks fault
(a raw `%{...}` in a frontend file, an unresolved pull in a backend one) is refused whole and nothing changes; the
refusal shows under the bar in the backend's words, the keys it names marked (`copyKeysNamedIn` -- its findings lead
with `namespace.key`), and the changes stay to correct. While anything is pending, leaving the client's page asks
first (`LeaveGuard`). A success bumps the refresh generation, so the page re-reads the client and its keys, and the
shell re-reads its copy: a changed `home.brand` appears in the app bar without a reload. The one-key `/copy/set` and
`/copy/reset` remain, each a batch of one; Design View's shared wording (#1010) saves through them. Wire names: `COV`
(addresses) and `CPY` (paths, `changes`, `reset`, `keys`, `stored`, `buildId`, `issues`, `mode`), both in the kernel.

**Editing the menu** (issue #919, `MenuEditor` in `ClientsPage.kt`): the detail's "Menu" table lists every home-menu
item for the client from `/clientAdmin/client/menu/items` (`ClientsApi.menuItems`; shipped and effective label
and condition, before any one caller's cfacts -- an editor lists what can be changed), with **Rename**, **Hide**,
**Show** (a choice of audiences, by name -- below) and, where the client's stored config changed the item, **Reset**; a group (`menuGroups`: anything another item
sits under) gets no Hide, since the bar draws a child only under a parent it keeps, and the backend refuses it
too. "Set by" comes from the overrides report's rows, which know source from stored.

**An audience is chosen and read by its name** (issue #1094), never as its cfact expression. The names are the
backend's, declared beside the menu (`HomeMenuAudiences`, held in step with the shipped menu by
`HomeMenuAudiencesTest`): each item's row carries what its shipped and its current condition are called
(`baseAudience`, `audience`) and the audiences it may be shown to (`audiences`, each a condition and a name).
`menuVisibilityText` reads "hidden", the name, or "Custom" for a condition the table does not hold -- one written by
hand in a source config -- noting the shipped state when the client changed it; the expression is the cell's tooltip
(`menuVisibilityDetail`). The Show control lists exactly the item's `audiences` (`menuShowChoices`, the shipped one
marked "(as shipped)"), and the write accepts exactly those: six audiences of a client's own people for any item,
plus the item's own shipped audience when that is one of the withheld ones (the deployment's operators, a test
instance, a debug session, administrators of every client), so an item can always be put back and no other item can
be given an audience that would only hide it. **Do not add a name, or a choice, in the webapp**: a new menu
condition is named in `HomeMenuAudiences`, where the test asks for it. An item the client's own configuration
**added** (`added`) has nothing shipped behind it: no shipped audience is noted, nothing is offered, and its row
shows "added" in place of the actions, every one of which changes a shipped item and would be refused.
Each action posts `/clientAdmin/client/menu/set` or `/reset` (`menuEditRequest`), the same write-trial-publish-
reload path as a copy edit (a draft, likewise, for a client with a sandbox), landing in the config already changing the item, else one overlaying the menu, else
`copy`. Hiding or showing is presentation, not permission -- the section gate still decides -- and the hint under
the table says so. Blocks other than the home menu (the sample's nav) stay read-only under "Other interface
changes". Wire names: `MNU` in the kernel.

**Editing the definition** (issue #1026, `DefinitionEditor` in `ClientsPage.kt`): under the summary rows of a client
defined in **stored** configuration (`definitionEditable`: present, origin stored, not static here -- the backend
refuses each in its own words; the page only spares showing an editor that cannot save), **Edit
definition** opens the fields the caller's policy lets them edit (issue #1116, `ClientPresentationFields.namesFor`):
for a client's administrator its name, description, time zone (issue #1115; the editor says a change moves no stored
moment; picked, not typed -- see "The time-zone picker" below), user labels as one comma line (`labelsOfText`), and
session lifetime in hours (issue #1308); for an administrator who sees every client also its refresh interval in
seconds, usage type and audience (selects), environments (a multi-select), preload (a checkbox), domain prefix, custom
domain, and web resources -- seeded from the client's **stored** definition, the retrieve's
`storedDefinition` (`definitionDraftOf`; what the client runs differs from it by an unpublished draft, which the
editor must show and be able to take back, and by what a template fills in, which written back would become the
client's own). The fields are drawn under the kernel's sections (`ClientPresentationFields.bySection`: About, How it
behaves, Platform), declared beside each field rather than here. The two numbers (`Kind.number`) ride as text, and a
blank one takes the platform's, which the retrieve's `platformDefaults` gives and each field's placeholder says
(`platformDefaultText`); the summary rows show each as the value in force (`inForceText`: "30 days (platform
default)"). Save posts
`/clientAdmin/client/definition/set` (`ClientsApi.setDefinition`) with **only the fields that changed**
(`definitionEditRequest`; a cleared field goes as blank, which the backend clears, and Save is disabled while
nothing differs), the same write-trial-publish-reload path as a copy edit, a draft likewise for a client with a
sandbox (`savedNote` says which). Every field the caller does not edit there stays in the read-only rows, saying who
sets it (`whoSetsNote`): "(set by the platform)" to a client's administrator, and to an administrator who sees every
client "(set in its configuration)" for the customer and the template, or "(set in source code)" for the static lock.
The endpoint refuses a platform field from a client's administrator (403). A domain prefix or custom domain another client declares is
refused by the stored write itself (`GedraConfigService`), whatever write carries it. When the configuration
holding the definition (the retrieve's `storedDefinitionConfig`) has unpublished changes and the definition does not
ask for a sandbox (its saves would be drafts), the backend's draft rule would refuse every save, so the page says so
instead of offering the editor (`definitionEditOffer`; nothing is offered until the configurations have loaded, so
an open editor is never replaced by the note), linking down to the Stored configuration table, where a row
that could be published there is tinted (`configRowNeedsPublish`, `.op-row-attention`). After a save the editor keeps the stored
definition the result returned as its baseline until the page re-reads, so a quick reopen does not send the old
values back. A success bumps the refresh generation, so the heading, the
listing row and the shell follow the new name. The editable set is the kernel's `ClientPresentationFields`, which
the endpoint's input and this editor both read.

**On a sandbox's page too** (issue #1358). A sandbox holds no configuration, so the definition editor, Traits and
Workflows -- like copy, menu and files -- edit its **parent's**, as drafts the sandbox runs and publishes from here:
`definitionEditable` admits a sandbox, the definition retrieve hands it its parent's stored definition, and the
catalogs read what the sandbox runs (its parent's latest, drafts included) beside the parent's stored configuration.
The backend binds each save to the parent as the person's user there (`ClientStoredEdit.target`). Whether there is a
sandbox stays the parent's to say: its Sandbox section offers nothing on a sandbox's page.

**Traits** (issue #1117, `ClientTraitsSection.kt`, its pure half in `ClientTraits.kt`, covered by `ClientTraitsTest`):
under Issues, offered where the definition editor is (`definitionEditable` and `definitionEditOffer`), since a save
lands in the same configuration under the same rules. It reads `GET /clientAdmin/client/traits` (`ClientsApi.traitCatalog`):
every global trait as the platform declares it -- id, description, the kinds it applies to, and its fields, drawn
read-only by `SchemaOutline` from the catalog's own `$defs` -- each marked when the client customizes it, and the
reason the client may not take `#allGlobal`, when it may not (a `customer` client in `production`). A box per trait,
and above them one switch for every global trait, now and to come; a customized trait, and every trait under the
switch, is drawn ticked and locked, saying why (`traitLockedReason`), and turning the switch off keeps the traits
ticked one by one. **Save** posts `/clientAdmin/client/traits/set` with the whole list (`includedTraitsAfter`: the
stored list with the picker's changes made in place -- an entry it does not show, such as a trait a code change
removed, is kept as it is) and `mode`, and takes effect as the definition editor's save does. A live save that would
take away a trait the client's forms hold is refused with the impact report (`impactOf`), shown in a dialog whose
**Save anyway** sends `acknowledgeImpact`; one the trial refuses -- a trait a workflow still collects -- shows the
backend's words. The read-only "Traits" summary row above still lists what the client supports. Wire names: `CIT`
and `CCT`, in the kernel.

**Its own traits** (issue #1118, `TraitDesignEditor.kt`, its pure half in `TraitDesigner.kt`, covered by
`TraitDesignerTest`): under the picker, the client's own traits from the same catalog read (`ownTraits`) -- each
stored one with its design, or why it cannot be edited here (a field not of the editor's kinds, or declared in source
code) -- with **Edit**, then **New trait**. The editor designs a trait from a fixed set of field kinds (`TraitFieldKind`:
text, long text, number, whole number, yes/no, date, choice, several choices): a name and description, then per field
a label, a kind, **Required** (in the data -- every entry, whoever writes it; a form's own requirement is Design
View's) and a choice field's choices. The model is the kernel's `TraitDesign`, so the **live preview** is `SchemaForm`
drawn from exactly what the backend will write (`previewDefs`: `toDataSchema` with its labels in the `g-layout`, long
text as `multiline`). Keys are made by the backend from first labels (`withKeys`) and never change: a saved field shows
its key and may change kind only between text and long text (`kindsFor`). What is wrong with the draft is listed in
the kernel's words (`traitDesignProblems`), and Save waits for it. The catalog's `quotas` -- the plan's schemas and
attributes per schema (#1109) -- are said beside the fields (`draftQuotaText`), **Add field** stops at the attribute
limit and **New trait** at the schema limit; the backend's trial holds them anyway. Save posts
`/clientAdmin/client/traits/design` (`traitDesignRequest`, with `mode`): a new trait lands in a configuration of its
own, `trait_<id>`; an edit patches the trait where it is stored, carrying everything the design does not own -- a
field's other keywords, a layout's hints, errors and strings from Design View. An edit that strands stored data (a
field made required, a choice removed) is refused with the impact report, in the same dialog and **Save anyway** as
the picker's.

**Narrowing and extending a global trait** (issue #1119; `TraitNarrowEditor.kt`, its pure half in `TraitNarrower.kt`,
covered by `TraitNarrowerTest`). Each global trait's row offers **Narrow…** (**Narrowed…** once the client has one), or
says it was altered by hand. The narrowing editor lists the trait's fields (`narrowFieldRows`, labeled from the
catalog's resolved `layouts`) with **Shown**, **Required**, and **Choices kept**, offering only moves that narrow: hiding
is the layout's -- an authoritative field list, the default for every workflow, the data unchanged -- and a field the
data requires cannot be hidden (`hideLockedReason`); requiring and keeping fewer choices change the data
(`requireLockedReason`). The model is the kernel's `TraitNarrowing`, whose `toAlteration` the preview merges over the
platform's type (`narrowPreviewDefs`) exactly as the backend writes it: the client's alteration of the trait's data
type, saved through `/clientAdmin/client/traits/narrow` (`traitNarrowRequest`) into `narrow_<trait>` or where a stored
alteration is. **As the trait is declared** clears it. A narrowing does not include a trait, and says so for one the
client does not. A **new trait** may **Start from** a global trait (`extending`): its fields are added to the base's,
which are listed and kept, and the preview resolves the extension onto the base (`previewDefs` with a
`TraitBaseView`); what a trait extends is fixed once saved. Every one of these saves, as a sandbox's draft, says what
publishing it would do to the live client's forms (`draftImpact`, `draftImpactText`) -- for information.

**Form states** (issue #1412, `FormStatesSection.kt`, its pure half in `FormStates.kt`, covered by `FormStatesTest`):
under Workflows, **Recalculate form states** brings the stored state of the client's forms up to date with its
configuration now, rather than at the nightly recalculation, through `POST /clientAdmin/client/formStates/recalculate`
(`ClientsApi.recalculateFormStates`). Synchronous, so it is offered only while the overview row's
`formStatesRecalcCount` -- the backend's own count of what a run looks at, not `forms` -- is within its
`formStatesRecalcLimit` (`formStatesOffer`); past it, the section says the nightly run brings them up to date. The answer
is said as counts (`formStatesRecalcText`), including a run that changed nothing, and "busy" while another
recalculation holds the client. Not asked about first: it changes no data anybody entered. A sandbox's page offers it
too, since its forms are its own. `kdr-probe --url <your server> form-states-timing [counts...]` times it.

**The bundle editor** (issue #1436, phase 1, read-only; `BundleEditorPage.kt`, its pure half in `BundleEditor.kt`,
covered by `BundleEditorTest`) is its own page, `page=bundleEditor&client=<id>`, reached from **Open every bundle** on a
client's page. One read answers it, `GET /clientAdmin/config/editor` or, for a caller who sees every client,
`/admin/client/config/editor?client=` naming the owner (`bundleEditorPath`, as `storedConfigsPath` decides the listing).
It answers every configuration's latest revision, with the published one beside it when the latest is a draft over it,
and each slot's entry type, key fields, and `$defs`, which phase 2's editing will need. The page reuses the client
page's `StoredConfigTable` for publishing, with its impact report and its "publish from its sandbox", closed to start
behind a `Disclosure` whose line says how many configurations are unpublished (`publishingSummaryText`), and then lists the
configurations -- the one holding the client's definition first, then by name (`editorBundlesInOrder`), so a publish or a
save does not move what was just touched. Each opens, by `Disclosure`, to its slots (`editorSlots`, in `editorSlotOrder`), each slot to its
entries, matched to the published revision's by their key fields (`addressesEntry`) and marked added, changed,
unchanged, or removed, and each entry to its JSON, highlighted (`JsonBlock`, the kernel's `highlightJson`, which a
Markdown `json` block uses). A changed entry shows its draft and published JSON side by side. The
page is wide (`.card.full`), and draws a level only once it is opened. Both headings, Publishing and Bundles, have a "What is this?" from the `clients` copy file
(`PFRAG.bundlePublishingNs`, `PFRAG.bundleListNs`).

**Workflows** (issue #1120, `ClientWorkflowsSection.kt`, its pure half in `ClientWorkflows.kt`, covered by
`ClientWorkflowsTest`): under Traits, offered where they are, from `GET /clientAdmin/client/workflows`
(`ClientsApi.workflowCatalog`): the client's workflows -- for a client with a sandbox, as the sandbox runs them, its
latest configuration with drafts in, so the list shows what a draft made or removed, as Files does -- then the
deployment's **templates** -- the global workflows,
which no client runs as they are. A template is greyed with the backend's reason when a workflow cannot be made from
it now (a client has one creation and one survey, and a plan limits workflows and their tasks); a workflow not made
from a template says why its slots cannot be changed here. **Use…** and **Edit…** open `WorkflowSlotEditor` on the
**slots** -- the kernel's `WfTemplateSlots`: the workflow's name, and per data-entry task its label and the traits it
collects, each required or not -- and say in words what the template fixes (`workflowFixedParts`), from the workflow
the slots make (`workflowPreview`, the kernel's `withTemplateSlots`). Save posts `/clientAdmin/client/workflows/save`
(`workflowSaveRequest`, with `mode`): a new workflow lands in a configuration of its own, `workflow_<id>`; a change is
patched where it is. What is stored is the workflow's overlay of the template, read over the template at load, so a
fix to the template reaches it; Design View edits the same workflow and keeps its changes in the same overlay.
**Remove** (issue #1346; `workflowRemovable`, a `danger` link behind a confirmation that names where it acts,
`workflowRemoveQuestion`) takes a template-made workflow out, through `/clientAdmin/client/workflows/remove`: the
workflow's entry leaves its configuration, the template can make it again, and a live removal of a workflow forms take
part in is refused with the impact report until acknowledged. **A new client starts with two**: every create makes a
creation workflow and a survey from `WFT.starters` (`newForm`, `formDetails`), each in its own `workflow_<id>`
configuration published with the definition, and includes the trait they collect (`kdr:name`) -- unless the create's
`starterWorkflows` is false. The self-serve card asks with a ticked checkbox; the platform's and a customer's creates
draw it from their input schema. They count toward a plan's workflows, which is what Remove is for.

**Time windows** (issues #1121, #1356, `WorkflowWindowsEditor.kt`, its pure half in `WorkflowWindows.kt`, covered by
`WorkflowWindowsTest`): a normal workflow's row in Workflows says where it stands now (`windowPhaseText`) and offers
**Time windows…** on every normal workflow, whoever declared it, which opens the editor in a row of its own beneath
that workflow's (the table's height cap lifted while it is open, `.op-table-scroll.detail-open`). **Windows are the workflow's parameters, not part of
its definition** (issue #1356): kept per client or sandbox in a table of their own (`WorkflowParamsService`), in force
at once with no draft and no publish. **Each parameter resolves on its own** (issue #1386): the scope's own value, else a
sandbox's client's, else the defaults the client's source code declares (`GedraConfigBuilder.workflowParams`), else no
limit -- the kernel's `WfParamsLayers`, which the page runs as the backend does. The catalog carries each layer for the
page's own scope (`CWF.params`: `WFP.current`, `WFP.parent`, `WFP.source`, and `WFP.history`; `parseWorkflowParams` →
`WorkflowParamsView`). The editor sets **Available** (lifetime), **Tracked** (relevancy) and **New forms
may join** (engagement), each a start and an end entered as a **date and a time** in the client's zone -- the
catalog's `timeZone`, not the caller's, since the page may show another client -- through the zoned picker
(`zonedPickerValue` / `zonedPickerIso`). An empty bound shows what it falls back to (`boundFallbackText`: "Same as
Available (Oct 20, 2026, 9:30 AM CT)"). Beneath them a timeline of the three bands with now marked
(`timelineLayout`, plain `div`s on a track coloured `--chart-1..3`, declared at `:root`), where the workflow stands
now, and a **Try a date** picker that says where it would stand then ("On Dec 10: …") -- the kernel's `phaseAt` in the
browser, no clock moved, nothing saved. Where the windows
in force come from is said under the title (`windowsSourceText`): set here (`settingByline`), its client's on a
sandbox, or the source code's defaults. While the scope sets its own and a fallback would set some, **Use its client's
windows** / **Use the source code's windows** (`windowsFallbackLabel`) saves an unset (`WFP.unset`), judged and recorded
like any change. Save posts `/clientAdmin/client/workflows/params`
(`windowsSaveRequest`; no `mode`, since a save is never a draft -- `windowsEffectText` says so). A change that would,
now, freeze or hide forms engaged with the workflow is refused with the impact report (`ImpactKind.workflowFrozen` /
`workflowHidden`, worded by `impactFindingText`), whose **Save anyway** acknowledges it. Beneath Save, **Earlier
settings** expands the three a workflow's parameters keep, newest first, each in a line (`windowsSummaryText`) with who
set it and **Use these values**, which fills the editor -- a pre-fill, never a save: the person saves it as any change.
A setter the caller may not administer (an operator who set a client's windows) is not named: that line, and the
byline, say only when.

**Visibility** (issue #1384, `WorkflowVisibilityEditor.kt`, its pure half in `WorkflowVisibility.kt`, covered by
`WorkflowVisibilityTest`): who sees a workflow, a parameter beside its windows -- on a workflow of **any** kind, so every
row of Workflows offers **Visibility…** and a **Seen by** column (`visibilityText`; restricted ones in `.wf-restricted`).
The value is the kernel's `WfVisibility`: `everyone`, or `restricted` to the client's administrators and the people
holding one of its labels -- the user labels the Users page puts on people, offered from the catalog's `CWF.userLabels`
and free to type. **One parameter, mode and labels together** (`WFP.visibility`), so a sandbox restricting a workflow
never inherits its client's group, and the labels are kept when it is opened to everyone. It saves as the windows do
(`visibilitySaveRequest`, the same `/clientAdmin/client/workflows/params`), with the provenance line, the way back to a
client's choice, and the earlier choices (`historyOf`, which leaves out a setting that changed only the other
parameter -- the windows editor uses it too). Restricting a workflow out of engaged forms' owners' sight is refused with
the impact report (`ImpactKind.workflowUnseen`). **A new workflow is made with who sees it**: the slot editor's "Seen
by" starts on administrators only and is sent as `CWF.visibility`, written before the workflow exists; on a client with
a sandbox it is the sandbox's, and the client's own stays administrators only. The backend enforces it wherever a person
meets a workflow (`WorkflowPhases.liveFor`, `WorkflowAudience`): one a person does not see is, to them, as one deleted
or past its lifetime is to everybody -- its part in their forms hidden and kept, its locks on their data released
(`TraitLocks`) -- and a restricted creation workflow sends them to the trait picker, a survey to the raw editor. The `clients` file's `workflows.newWorkflowNote` and the home file's `publicHome.createClientNote` ship empty
and say how to open a workflow up where the `reference` flavor fills them in.

**Long text** is a layout mark, not a schema keyword: a `g-layout` field entry's `multiline: true` (`SL.multiline`,
refused on anything but plain text) makes `SchemaForm` draw antd's `TextArea` (`ControlKind.LongText`) for any form
whose layout says so.

**The time-zone picker** (issue #1257, `TimeZonePicker.kt`). The time zone is `ClientPresentationFields.Kind.timeZone`,
drawn as `TimeZonePicker`: an antd `Select` that builds its own options rather than letting antd filter, since before
anything is typed it shows a short list (`commonTimeZones`, about 40, one per region people pick, plus `UTC`, which
`Intl.supportedValuesOf` leaves out but the backend accepts), and typing searches all of the browser's ~420 zones by
place, name, id, or offset (`-5`, `GMT+5:30`, and `GMT`, `UTC` or `+0` for zero, which `Intl` writes as a bare "GMT" --
an offset matches exactly). Only the short list is labeled when the picker is drawn; every zone (~1,700 `Intl`
formats, ~80ms) the first time a search is typed. Search goes through antd 6's object form,
`showSearch = { searchValue, onSearch, filterOption: false }`, with the search text the picker's one piece of state.
Pinned at the top: the client's current zone, under exactly its stored spelling (so an untouched draft is no change,
and a zone off the short list still shows), and the browser's own. Labels are `Intl`'s ("Central Time (Chicago) ·
GMT−5", the long generic name, the id's place, the short offset for today), so the webapp ships no zone data. Two
things `Intl` does that the code relies on knowing: **Chrome lists and resolves legacy spellings** (`Asia/Calcutta`,
`Europe/Kiev`; even `Asia/Kolkata` resolves to `Asia/Calcutta`), so zones are matched by what `Intl` resolves them to
(`TimeZoneChoice.key`) and the short list's modern spelling is the one offered and saved; and **UTC's generic name is
just "GMT"**, so a name that is only an offset falls back to the long one or to the place. What the picker offers is
what the client check accepts, by the kernel's `isPlaceZoneName` (`DateUtil.kt`), and its `Intl` calls share
`DisplayZone.kt`'s `intlDateTimeFormat`, which carries the `js()` constructor-call workaround. The read-only row shows the
same label beside the stored id (`timeZoneText`). The logic is pure and covered under `jsNodeTest`
(`TimeZonePickerTest`); the self-serve create page (#1228) is meant to use the same picker, starting on the browser's
zone.

Denied honestly in two layers, as Users is: `HomeApi.fetchConfig().canManageUsers == false` shows a
not-available panel without calling the endpoint, and a refusal from the endpoint -- a `public` self-administrator,
who administers only their own users (#805) -- is shown as it came, through `LoadStateCard`'s `errorLead`. The
menu offers the page to a caller who administers a client -- "Clients" or "My client" by whether they see across
clients (issue #1082) -- so a `public` self-administrator, who administers none, is offered neither (issue #1091).
The page still refuses them honestly if they reach it by its address; the endpoint is the authority. The detail view (`c=<id>`, issue #906) and, later, editing a client and
designing its workflows (#903) open from this page.
