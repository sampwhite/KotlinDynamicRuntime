# Frontend guidance (webapp)

Auto-loaded by Claude Code sessions working under `webapp/`. This holds **shared frontend knowledge** — not
personal config (that belongs in your own non-versioned `CLAUDE.md` in the **workspace directory**, the parent
of this repo). Repo-wide agent guidance — including who owns the workspace's configuration files — is in the
versioned [`CLAUDE.md`](../CLAUDE.md) at the repository root.

## Static content: Markdown fragments (issue #59)

The backend serves per-component UI text as **Markdown fragment files** through the **static context root**
(`st` by default). These are content, not API, so they deliberately do **not** appear in the
`/schema/endpoints` catalog — you won't discover them there. This note is how you find out about them.

**Request:** `GET /st/<appId>/md/<fileId:buildId>` (e.g. `/st/myapp.acme.en/md/emailForms:9f3ac1`)

- `appId` — **you** construct it: the application you're serving, plus an optional client-variation suffix,
  plus an optional locale suffix. It stays **opaque and unread** by the backend, and deliberately so: whose
  content you are served must not be something the caller can choose. The backend decides that from who you
  are signed in as.
- `fileId` — names a fragment file the deployment declares. Its content is every layer that applies added up
  (issue #456): the file the owning component ships, any overlay over it, and any a client contributed. If no
  component in the deployment declares it, the request 404s.
- `buildId` — a content hash the backend provides, and since issue #456 it **selects** the content rather than
  being stripped. Practically nothing changes for you — keep fetching the exact `fileId:buildId` a UI-config
  handed you — but two things follow. A ref is **per caller**, so do not reuse one across sign-ins or share it
  between users. And a `buildId` this node does not recognize 404s (uncached) rather than falling back to
  current content, so re-fetch the UI-config to get a fresh ref rather than retrying the old URL.

**Response:** a free-form two-tier JSON map `{ namespace: { key: value } }` with an **immutable**
`Cache-Control` (cache it forever / front it with a CDN — the `buildId` busts it on change). Safe to put
behind a shared cache: a URL names one document, because the `buildId` hashes the merged content rather than
the underlying file, so two clients reading different copy never share a URL.

**Beyond standard Markdown** the renderer takes two Pandoc-style attributed constructs (issue #795): a bracketed
span with a role, `[text]{.role}`, and an image with placement, `![alt](src){.float-right width=240}`. A role
renders as a `md-`-prefixed class by default (`.md-value`, `.md-code` are styled in `app.css`); a surface that
cannot use a stylesheet -- a mail -- passes `MarkdownHooks(decorateSpan = …)` and realizes the role itself. An
attribute block holds only role names and numeric `width`/`height`; anything else makes it literal text. A
backslash escapes any ASCII punctuation (`\*`), and `escapeMarkdown()` applies that to a *value* being substituted
into copy, so an address like `_ops_@acme.test` reads as written (the mails do this to every param). The
same source renders to plain text with `renderMarkdownText()` (emphasis and roles dropped, a link as
`text (url)`, an image as its alt text), which is how a mail gets its text part from the one body.

**Using a fragment value:** each value is Markdown that may embed `${namespace.key}` placeholders. Resolve
them with the kernel's `String.evalTemplate(data)` — the fragment map *is* the data map, so `${email.subject}`
reads `map["email"]["subject"]` — then render the resulting Markdown.

A placeholder holds an **expression**, not just a path: literals, `+ - * / %`, `~` to join text, comparison,
`&& || !`, `cond ? a : b`, `a ?: b` for a default (`${user.name ?: "there"}`), and calls to a fixed set of
built-in functions (`${upper(user.name)}`, `${count(items)}`, `${formatDay(order.at)}`). It runs in the kernel, so a preview in
the browser resolves a template exactly as the backend will. A bare missing or null value still throws — say
what should happen with `?:` rather than relying on tolerance.

**Where a file is shown** is declared with it (issue #933): `fragmentFiles(HFRAG.home, shownOn = "the app bar and
home page, …")`, carried to the fragment check and the copy editor's keys listing as `shownOn`; a file declared
without one is *not shown by this application* and the editor says so. Declare it for every new file a page reads.

**Authoring a `.md` fragment file:** `# @namespace` opens a namespace (re-declare to switch); `# +key value`
is an inline value; `# +key` alone starts a next-line value (ends at two blank lines or the next `# ` line);
`/- ... -/` is a comment. Reference: `base/common/src/main/resources/md-fragments/sample.md`, and
`MarkdownFragmentUtil` + `ScriptUtil` in `base:kernel` (both transpile-clean, so you can parse/resolve on the
frontend too).

## UI-config endpoints: how a widget-group learns what to build (issue #70)

A React **widget-group** (the auth flow, the profile page, later the nav/home) fetches a normal API endpoint
— its **UI-config** — to learn how to construct itself. Every such endpoint returns the same envelope:

```
{ fragments: [ { fileId, buildId } ], features: { … }, settings: { … }, state: { … } }
```

- `fragments` — the Markdown fragment file(s) this group's copy comes from, **each already carrying its
  `buildId`**. This is how a component learns its `fileId:buildId` (the previously-open question): fetch each
  at `/st/<appId>/md/<fileId:buildId>`. `features`, `settings`, and `state` are group-specific.
- `features` are boolean policy flags; `settings` are non-flag tuning **values** (numbers, strings), kept
  apart so a config map isn't flags and magnitudes mixed (issue #146). Either may be empty/absent.
- These calls are cheap and meant to be **re-fetched on navigation/invalidation** — err on calling too often.
  It's fine for each widget in a group to fetch independently.

**Endpoint model:** one config endpoint **per widget-group**, not a swiss-army endpoint switching on a
"group" arg (a per-endpoint output schema is what the schema/validation layer and the runtime's
dynamic-endpoint story need). Slice namespaces by "who authors the copy"; be pragmatic in leaf namespaces,
disciplined in a hub (`nav`/`shell`) everything composes through.

Current UI-config endpoints:
- `GET /auth/ui/config` — anonymous; features `{registration, codeLogin, passwordLogin, googleLogin}`, state
  `{userInfo, googleClientId}` (anonymous `userInfo` when logged out). Fragment file `auth`. `googleLogin` is
  on only when the deployment set `KDR_GOOGLE_CLIENT_ID`, and `googleClientId` carries that (public) id —
  Google's script has to present it, so it is served here rather than hardcoded in the frontend. A configured
  id is **not** enough for the button to work: Google also checks the page's origin against the client id's
  *Authorized JavaScript origins*, and an unregistered one fails in the browser without the backend seeing
  anything. `http://localhost:7070` (same-origin, `/wa`) and `http://localhost:8080` (dev server) are separate
  origins and both need registering — as is any other port, and `127.0.0.1` is a different origin again from
  `localhost`. See the `KDR_GOOGLE_CLIENT_ID` declaration (`GOOG.googleClientIdEnvVar`), whose documentation
  covers this, or the operator `/operator/env/reference` view.

  **The symptom is not reliable, and the frontend cannot detect it** (issue #250). It may be a `403` plus
  `[GSI_LOGGER]: The given origin is not allowed for the given client ID`; it may be *"Access blocked — You
  can't sign in to this app…"* inside Google's own window after a click; and it may be a button that renders
  and never completes. Measured rather than assumed: on an unregistered origin Google's script still loads,
  `renderButton` still draws a button, and an `error_callback` passed to `initialize` **never fires** — so
  there is no error for `GoogleSignInButton` to catch, and its `onFail` covers only a script that cannot be
  *fetched*. What the component does instead is state the page's own origin beside the button where
  `showErrorDetail` is on, so a developer has the exact string to register.
- `GET /profile/ui/config` — **login-required** (`profile` section); features `{hasPassword, canSetPassword}`,
  state `{userInfo}`. Fragment file `profile`.
- The **switcher** (issue #749), login-required (`user` section): `GET /user/self/users` lists the `UserChoice`s
  the signed-in person may act as (registered, enabled users of their identity; `isCurrent` / `isDefault`
  marked), `POST /user/self/switch {userId}` reissues the session as one of them (the app then goes home and
  bumps the refresh generation, as a login does -- no page reload, issue #1015; see `afterSessionChange`, which
  every same-person session change uses), `POST /user/self/setDefault {userId}` chooses which user the
  address logs in as, and `POST /user/self/removePublic {userId}` permanently removes one of the person's own
  `public` users (issue #752), down to nothing -- a `public` user's owner registered themselves and can register
  again, so the last user may go too, retiring the identity. Removing the acting user moves the session to the
  person's default user, or ends it when none remains. The **profile page** offers it ("Your public account")
  for every `public` user in the switcher's list, and says when it is the whole registration
  (`removablePublicUsers`, `removalEndsEverything`). The shell config (`/home/ui/config`) carries the same list as `state.users`, so the app
  bar needs no fetch of its own: for a person with more than one user the **identity badge** says which one this
  is in brackets (`Demo Person [hub · Member B]`, only what tells it from the others -- `UserChoice.qualifierWithin`)
  and becomes the menu that switches; with one user it is the plain label it always was. The default persona
  is `member` (shown `Member`); `admin` is the other so far.
- The **sandbox** (issues #929, #931), login-required (`user` section): `POST /user/self/openSandbox {client?}`
  moves the session into a client's Shadow Sandbox as the person's own user there, created on first use; it is for
  an administrator of the parent (an `allClients` administrator may name any client), and in a sandbox `admin`
  takes effect only for an identity that is an administrator in the parent. The bar offers **Open sandbox** when the
  shell config's `canOpenSandbox` feature says so; in a sandbox (`state.sandboxOf` / `sandboxOfName`) a chip takes
  its place -- **Acme Sandbox**, on screen however far a page scrolls, as the bar is -- whose popover says what the
  sandbox runs and offers **Back to Acme**, a switch to the person's own user there (`SandboxShell.kt`:
  `showOpenSandbox`, `sandboxChipLabel`, `sandboxInfoText`, `sandboxWayBack`). The UI word is "sandbox"; "Shadow" is
  the feature's internal name and appears in no UI text.
- **Invitations** (issue #751), anonymous like the rest of the auth flow: an administrator's create for an
  address that is not their own -- new, or another person's -- provisions an unclaimed user and mails a link
  to `#page=invite&token=<token>` (built under `KDR_PUBLIC_URL`, else the request's scheme and host). The
  **invite page** (`InvitePage`) POSTs `/auth/invitation/preview {invitationToken}` to say what the account is
  and waits for **Accept**, which POSTs `/auth/invitation/accept`: the link is the proof, so accepting registers
  the user, verifies a new identity, signs the browser in, and goes home re-reading (`afterSessionChange`, issue
  #1015). Nothing happens on merely opening the
  link. `POST /clientAdmin/user/invite {userId}` re-sends a lapsed one (the editor offers it for an unclaimed
  user). The **register form** shows Client / Persona / Persona suffix to an `allClients` caller only; set, they ride
  `createInitial` and the backend refuses them from anyone else.
- **Claiming from the login page** (issue #751), since a mailed link cannot be required: the auth flow's third
  mode, `#page=claim` ("Claim an account created for you", linked from the login page). The person types the
  address, the client and the persona as the invitation spelled them (`admin`, `member B`; `PERSONA.splitTyped`),
  with no choice lists so an anonymous caller learns nothing about a client. `POST /auth/claim/sendVerify`
  **always answers as a success** and the mail says the rest: the code, or that nothing matched (acknowledging
  the client when the address has a user there); the code is computed over the whole key, defaults applied, so
  it cannot be replayed against another user. `POST /auth/claim/register` is a code login aimed at that user:
  it claims an unclaimed one and simply signs in as a claimed one -- the one way to log in as a particular
  non-default user by address alone.
- **The mails are fragment copy** (issue #773): every mail the auth flows send -- the code, the invitation, the
  claim page's answers -- is authored in the **backend** fragment file `mail.md` (never served; one namespace
  per mail, `subject` + `body`, a shared `common.footer` and `common.htmlStyle`) and goes out as one message
  with a text part (the kernel's `renderMarkdownText`) and an HTML part (`renderMarkdown`, issue #795). `MailCopy`
  renders it for the client of the user the mail is *about* -- an invitation's user, a claim's key -- so a
  client's config overlay of `mail` rewords its mails and signs them as itself, whoever sent them. Params are
  sanitized like an error message's; a URL param becomes an anchor in the HTML part on its own, and a value a
  reader copies out is marked in the copy with a role -- `[${address}]{.value}`, `[${code}]{.code}` -- which
  the HTML part realizes as a style-only anchor (so Gmail does not linkify an address or a code) and the text
  part shows bare. `forHtml` is bound by every render, for a body whose two parts want different words. The dev
  autofill (`fetchDevCode`) and the tests read the code out of the text part's "verification code is <code>."
  -- keep that phrase when rewording. `/fixture/simulatedEmails` returns both parts (`text`, `html`). The
  sample's acme and globex each overlay `common.footer` alone, so every mail about one of their users is
  signed by them (`SampleClients.kt`).

The backend helper `fragmentRefs(…)` + `SchTypeBuilder.uiFragmentsProperty()` (in `content/UiConfig.kt`) keep
the envelope consistent across groups.

## The admin console: editing someone's identity and authority (issue #225)

The Users page edits several **independent** things, and treating any two as one control is the mistake the
whole screen is arranged to prevent. Three of them are **authority**:

- **Access level** — a rung of `RoleLadder` (`user` < `operator` < `admin`), shown as a *single-choice*
  `Select`, because the levels are an ordering and holding two is not a thing one can be.
- **All clients** — an off-ladder **capability**, a checkbox. The level says *what* someone may do; this says
  *whose data* they may do it to. Different axes.
- **Organization** — an optional narrowing *within* a client. Blank means client-wide.

A fourth is **belonging**, and behaves unlike the other three:

- **Client** — which client the account is in (issue #352). Chosen when the user is **created** and never
  again: their content carries the client both in its `client` column and inside every `GedraId`, so moving
  someone would strand it. There is no set-client call for an editor to offer, which is why this is a `Select`
  while creating and a read-only field afterward — the one control on this page whose absence is the point.
  Offered only to a caller holding `allClients`, who is also the only one able to read `/admin/clients` to
  populate it; a scoped administrator's client is not a decision. The list gains a **Client** column under the
  same condition, because a column that says one thing says nothing.
- **Persona** (issue #750) — what kind of user this is, from the kernel's `PERSONA` registry (`Member`,
  `Admin`), chosen at creation and never again, so a `Select` while creating and read-only afterward. Personas
  grant roles by default and roles provide a default persona: until the administrator picks a persona it
  follows the access level (`personaForLevel`), picking one moves the level to its defaults (`levelForPersona`),
  and only a chosen persona is sent, so the backend applies the same rule to an unnamed one. The Status column
  says `unclaimed` for a user nobody has logged into yet (no registered date; the activated date is stamped at
  creation and says nothing about that).
  Creating a user at your **own** address makes an associated user of yours, registered at once and in the
  badge's switcher. The **Persona suffix** box is on every create form (issue #797; optional -- a first
  `Member B` needs it as much as a second one). A create that collides with an existing user of the same
  address, client and persona -- the backend's duplicate-key refusal, recognized by the envelope's logical
  `errorCode` (`AERR.userKeyTaken`), never by its wording -- switches the box's hint to say why it is now
  needed. **Create a user for me** (issue #797) opens the same form on the caller's own address, locked
  (`#page=users&u=me`): the user is registered on creation and no invitation is sent. Every successful save on
  the page bumps the refresh bus, so a user created, disabled or deleted for yourself shows in the badge's
  switcher at once. The list shows both in a **Persona** column (`Member B`), and a **Registered** column says
  when the person claimed the user.
  **The person behind the user** (issue #770): the editor opens with the identity's view from
  `GET /clientAdmin/user/identity?userId=` (`AdminIdentity`) -- the person's other users as a list, when there
  is more than one, each choosable to edit that one instead (the open one marked, what tells each apart as the
  badge says it, its status), then a read-only **Summary**: whether the address is proven, whether a password
  is set, which user an unnamed login lands on, and the open user's dates. The read runs through the same
  user-admin scope as the rest, so a client administrator sees only the person's users in their own client,
  and "Signs in as" is a dash rather than a hint when the login lands outside it (`identitySiblings`,
  `identitySummary`).
  **In `public`** (issue #805) every self-registered user is an administrator of themselves only: the Users page
  lists their own identity's users, "Create user" is not offered (`mayCreateForOthers`; the backend refuses any
  other address and any other client), and "Create a user for me" is how they make variants. Their data reads
  stay their own, and the `clientAdmin/config` endpoints refuse them.

Two are **identity**, and sit at the top of the editor beside the email for that reason — they say who the
account is, not what it may do:

- **Name** — the account's real-world name: a person's full name, or a business's. Non-unique display copy,
  never an identifier.
- **Business account** — `isEntity`. It says how to *read* the name, not which field to read: there is one
  name input whose label switches between "Full name" and "Business name", rather than a second field
  appearing. That mirrors `UserProfile.displayName`, which is `name ?: publicName` with no reference to the
  flag, so the console and the app cannot disagree about what is shown.

One more is neither authority nor identity:

- **Labels** (issue #786) — free-form words an administrator puts on a user (`reviewer`) for a workflow's
  `userHasLabel` function to test. They **grant nothing**, which is why they sit apart from the access level
  rather than beside it. A tags `Select` offering the *user's* client's suggestions (`labelSuggestions`, fetched
  the first time that client is edited and kept keyed by client, so a slow reply can only fill its own client's
  entry — never land on the next user's editor) while accepting any other label typed. On an existing user
  only: create has no labels field. Editable on your **own** row too, deliberately — a label is not privilege,
  and the approve endpoint (#787) enforces the second-person rule itself: a reviewer can never approve a form
  they own (in production, a form owned by any of their users). Saving compares
  after `normalizeUserLabels`, the kernel rule the backend stores by, so re-spacing a label is no change.

**Unticking "Business account" keeps the name.** It used to clear it, which was right while only a business
had one and is silent data loss now that a person does — reclassifying an account should not discard what it
is called. The list shows **Name** and a plain Person/Business **Type**.

**`username` is gone from this page** — no create field, no column — but it keeps its unique column, its index
and its role as a login id, and the search still matches it. A username is an identifier, not a name, and the
console displays none, so advertising it in the search placeholder would point at something you cannot see
here. Do not "tidy" that by dropping the match as well.

**Compose, never replace.** A role list is sent whole, so an edit that rebuilds it from one control silently
drops the others. `RoleLadder.rolesAtLevel(current, level)` moves someone between rungs while preserving
anything off the ladder, and `rolesWithCapability(roles, capability, granted)` moves a capability on or off.
`draftRoles` composes them **in that order** — that is what lets a level change keep a capability and a
capability change keep a level. Saving compares role *sets*, not levels, or a capability-only edit would look
like no change and never be sent.

**Controls that could only ever fail are not shown.** "All clients" appears only to a caller who holds it (the
backend refuses to grant reach the granter lacks), and the Organization field is editable only by someone not
confined to one (a confined administrator may assign only their own). The **Operator** rung of the level
`Select` follows the same rule since #464: it is deployment-wide (it requires `allClients`), so it is offered
only to a caller who holds `allClients` — via `offeredAccessLevels(operatorSelectable)`, covered under
`jsNodeTest` — except when the edited user already is an operator, so the value still shows and can be kept
(anti-escalation checks *adding*, not the result set). This is the advertise-versus-serve drift issue #211
exists to remove, relocated into a form: a control that can only produce a 400 is worse than no control.

Editing **yourself** disables the level, the capability and the enabled flag — another administrator has to
change those, so an account cannot demote or disable itself into a lock-out. Organization is deliberately *not*
disabled there: the field is only rendered for a caller who has none, and giving yourself one *narrows* you.
Note the one-way door that creates for a client-scoped administrator — once confined, `requireAssignableOrg`
will not let them clear it, so they need a peer (an `allClients` holder is exempt from the rule entirely). The
backend treats that as co-equal administration rather than a lock-out.

**But a control that produces a silent no-op is the same family**, and there the fix is a hint rather than
hiding, because the state is legal and worth keeping: `allClients` below the Administrator level is stored and
inert (the full-scope surface needs *both*, issue #237). `isAllClientsDormant(level, granted)` — pure, covered
under `jsNodeTest` — drives a note saying so, and saying that it is **kept**. Do not "fix" this by disabling
the checkbox: demoting an administrator should leave the capability dormant rather than make someone remember
to re-grant it, which is the same reasoning that makes `rolesAtLevel` preserve capabilities at all.

`AdminApi` calls the **`clientAdmin`** paths ([UADEP], renamed from `userAdmin` in #466), not the full-scope
`admin` ones. That surface serves both kinds of administrator correctly — a capability holder is simply
unconfined on it — so the console needs no branch on who is asking. `canManageUsers` from the home config
decides whether the page is offered at all; it shapes the UI and is not the enforcement point, which stays the
section gate.

Paths, field names and the ladder all come from `base/kernel`, so a backend rename breaks compilation here
rather than at runtime.

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
  **only while the user is still on the page the create was launched from** -- capture `hashParams()` before
  the `launch`, because a slow response must not pull someone out of wherever they went next. Keep the save
  button busy across that navigation (no `running = false`, no `finally` reset): the page unmounts a tick
  later, and a live button in between makes a second form. Create-for-user returns to the listing it was
  launched from, as it was (#762 review; see the next bullet). `formsArrivalNote` is the reading half, and
  names the reason (filter, sort, or neither). A new save surface calls the helpers; it does not build its own
  hash. #758 was the workflow create keeping an in-place "Form created" page after the picker had moved to
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
- **A workflow's listing is its own page** (`page=workflowForms`, issue #792): where a count on the Workflows
  page leads. `FormsPage` draws it in workflow mode -- the workflow as heading, a state switch, `← Workflows` --
  with the workflow and state riding the hash as search keys (`formsDrillKeys`) so paging, sorting and a client
  switch carry them, but kept by Clear and part of the page's history identity, never a chip. Every way home
  reads the listing off `from` (`formsListingOf`, honouring only the two forms listings), so a form opened
  there returns there; a `page=forms` hash naming a workflow is routed to the workflow's listing, so old links
  still open where they did.
- **Choosing a client drops the `user` scope** (`formsSearchForClient`): a user belongs to one client. A shared
  `client=X` link opened by a caller without `allClients` drops the selector from the search too
  (`formsInitialSearch`), since no control would let them clear it.
- **Publish the surface with its rows.** `loadForClient` stages the catalog, endpoints, search and first page
  in locals and publishes them together, so a failed switch leaves the previous surface whole rather than X's
  controls over the old rows.

## The Clients page (issues #903, #905)

`#page=clients` lists the clients an administrator oversees, from `GET /clientAdmin/clients/overview`
(`ClientsApi.listOverview`, rows parsed by the pure `parseClientOverview`): an `allClients` administrator sees
every client the node knows of -- present, not enabled here, dropped by a check, or known only from stored
configuration -- and a client-scoped one sees their own. It is the **scoped** surface on purpose: that section
admits both kinds of administrator and an `allClients` holder is unconfined there, so the page needs no branch on
who is asking beyond the line under its heading. Per row: the load status (`clientLoadText`), where the
definition came from (`clientOriginText`: source, stored, and how many stored configurations are loaded), live
forms, active users with the unclaimed ones told apart (`userCountText`), and workflows. Counts are client-wide
and count active users only -- disabled and deleted users are never in the cache the count reads. A present
client's Forms and Users counts link to the listings behind them (`clientFormsHref`, `clientUsersHref`): with the
client chosen or filtered for an `allClients` administrator, bare for a scoped one, whose listings are their own
client already (the Users page draws no client filter for them, so a `client=` in the hash would be one they
could not clear). The Workflows page takes no client, so that count is plain text until it does.

**One client** (`c=<id>`, issue #906) shows, on the same route with `← Clients` back: the listing's facts for it,
its definition from `/clientAdmin/client/definition` (`clientSummaryRows`, drawn with `readOnlyField`; the
operator-only `audience` and `usageType` (#820) are noted "(set by the platform)" for a scoped administrator, so
the later editor disables exactly those), the issues its checks forgave, and the stored configurations this node
holds for it -- the full-scope bundles listing naming the client for an `allClients` administrator, the scoped
one otherwise. Each configuration row (`StoredConfigTable`, issue #1001) says whether its latest revision is
**live** by the client's tier (`bundleLiveText`: "Live", "v3 draft; v2 live" for a published-only client --
every client with a sandbox is one -- or "Live, unpublished" for one on its latest revision; the summary carries
`publishedVersion`, the overview row `publishedOnly` and `staticHere`), and an actions column whose first action
is **Publish** (`bundleAction`): offered on a client without a sandbox and on a sandbox's page -- which lists its
parent's bundles, the configuration it runs, and publishes them there -- and replaced by a note elsewhere: a
client with a sandbox publishes from it, after previewing -- the word "sandbox" leads to the sandbox's page,
directly for an `allClients` administrator, and for a client-scoped one by opening the sandbox first (a fresh
session as their user there) and landing on that page -- and a client static in production takes nothing stored. A publish reloads the client (`ClientsApi.publishBundle`).
A publish is judged against the client's **stored data** (issue #935): one that would affect it is refused with an
impact report (the envelope's `errorCode` `IMP.refusedCode`, the report in its `extraData`, which `ApiError` carries
for this), and the table opens it in a dialog (`ImpactReportDialog`: `impactSummary`, a line per finding from
`impactFindingText`, at most five form ids per finding beside the full count) whose **Publish anyway** sends
`acknowledgeImpact` (`publishBundleRequest`). **Check impact** (`BundleAction.checkImpact`) fetches the same report
before any publish (`ClientsApi.bundleImpact`): beside Publish where publishing changes what the client runs, and on a
client with a sandbox beside the "publish from its sandbox" note too, with no publish button in the dialog there. Each
form id opens the raw trait editor in a new tab (`impactFormHref`) where the session can read it
(`impactFormsOpenable`); a session in the sandbox is a user of the sandbox and cannot read its parent's forms, so there
the ids stay plain and a note says to open them from the parent's own page. The `impact-demo` simulation leaves a harmful draft to look at -- run it from the
**Simulations** page, which signs you in as the client's administrator, or `kdr-probe --url <your server> impact-demo`. The definition and the configurations are fetched apart and keyed on the open id with a
monotonic token, so a client this node does not carry still shows what the listing knows above the retrieve's
404, and a slow answer for a client the user moved on from is dropped. The constants the frontend reads these by
(`CFEP`, `ACEP`, `CCT`, `GCI`) live in the kernel for that reason.

**Sandbox** (issue #932, `SandboxSection` in `ClientsPage.kt`): the detail says whether the client has a Shadow
Sandbox and offers **Add a sandbox** / **Remove the sandbox** (`sandboxControl`), posting
`/clientAdmin/client/sandbox {client, sandbox}` (`ClientsApi.setSandbox`), which sets the flag in the client's
stored definition, **publishes** it and reloads -- the published definition decides both the sandbox and the tier,
so a draft would change nothing -- and is refused for a source-defined client (its flag is set in source), for a
sandbox, and while that config has unpublished changes. A client with a sandbox runs only published configuration.
A sandbox's own detail says whose it is. In the listing each sandbox follows its parent
(`withSandboxesBesideParents`) with a "sandbox of <id>" note (`sandboxRowNote`); the overview rows carry
`sandboxOf` and `hasSandbox`. A scoped administrator's listing is their own client, so only an `allClients` one
sees sandbox rows.

**Copy & menu** (issue #917): what a client's own configuration changes about what its people see, from
`GET /clientAdmin/client/overrides` (`ClientsApi.overrides`, parsed by the pure `parseClientOverrides`; the wire
names are the kernel's `COV`). The detail's "Copy & menu" section draws two tables: the copy the client rewords
(`file: namespace.key`, the shipped value, the client's, "Set by" as `config (source|stored)` -- `setByText` --
or `config (template <id>)` for a value the client inherits from the template it extends, issue #945; a
stored change over the client's own source value shows "was: …", and an orphan -- a key no shipped file declares,
so the value is never read -- is flagged), and the interface items it changes (`menuChangeText`: added, hidden,
shown, renamed, reordered; `blockValueText`; the shipped label rides on the row as `baseLabel`, so a row that only
hides an item still names it; "Set by" names every config that set a field, a stored one first -- `blockSetByText`).
Every value is Markdown and renders with `MarkdownInline`, then clamped to one line by `.cell-clamp` with the whole
on hover -- clamped after rendering, never cut before it, since a cut through a link would show its syntax. The listing's **Customized** column says how much (`customizedText`, "3 copy,
2 menu" -- "menu" because every block a client can overlay today is one) and links to the detail; an `allClients`
administrator also gets **Copy & menu across clients** (`ov=1` on the same route, `overridesAcrossHref`): one
retrieve per listed client, grouped by the pure `overridesAcrossClients` into file-or-block → key → the clients
overriding it. A scoped administrator has one client and is never shown that view, whatever the hash says.

**Editing the copy** (issue #918, `CopyEditor` in `ClientsPage.kt`): each copy row has **Edit** and, for a stored
value only, **Reset** -- data cannot remove what source code or the shipped file says, so a source-set key is
overridden (its `sourceValue` shows as "was: …") and reset returns to it. **Add an override** fetches
`/clientAdmin/client/copy/keys` (`ClientsApi.copyKeys`; every shipped key with the client's value, backend files
included, since `mail` is never served) and offers file → namespace → key, minus what the client already sets
(`addableCopyKeys`), with the current value as the starting text; the File choice groups the files the
application shows first, each with where (`copyFileChoices`, `copyFileLabel`, from the keys' `shownOn`), and the
rest under "Not shown by this application" (issue #933) -- and a Copy row of such a file is marked "(not shown
here)", since its override is served but changes nothing anyone sees on this app. Save posts `/clientAdmin/client/copy/set`
(`copyEditRequest`; the editor's hint says which template syntax the file takes, by its `audience` --
`copySyntaxHint`, issue #1001), which writes the client's stored config (the one already overlaying the file, else `copy`,
created on first use), trial-checks it, publishes and reloads -- one call, live at once; for a client with a Shadow
Sandbox (issue #930) -- which is always published-only -- it instead saves a **draft** of the parent's configuration
and reloads, so the sandbox shows it and publishing is the explicit step, and the result's `mode` (`EDM.live` / `EDM.draft`) says which, which
`savedNote` turns into the note; called in the sandbox, it edits the parent's -- and the backend's
refusal of a faulted value (a raw `%{...}` in a frontend file, an unresolved pull in a backend one) is shown in
its words under the editor. A success bumps the refresh generation, so the page re-reads the client and the
shell re-reads its copy: a changed `home.brand` appears in the app bar without a reload. Wire names: `COV`
(addresses) and `CPY` (paths, `stored`, `buildId`, `issues`, `mode`), both in the kernel.

**Editing the menu** (issue #919, `MenuEditor` in `ClientsPage.kt`): the detail's "Menu" table lists every home-menu
item for the client from `/clientAdmin/client/menu/items` (`ClientsApi.menuItems`; shipped and effective label
and condition, before any one caller's cfacts -- an editor lists what can be changed), with **Rename**, **Hide**,
**Show** (a choice of the audiences the shipped menu already draws for, `menuAudiences`; the backend refuses any
other expression) and, where the client's stored config changed the item, **Reset**; a group (`menuGroups`: anything another item
sits under) gets no Hide, since the bar draws a child only under a parent it keeps, and the backend refuses it
too. "Set by" comes from the overrides report's rows, which know source from stored. `menuVisibilityText` reads
the condition as "everyone", "hidden" or the expression itself, noting the shipped state when the client changed
it. Each action posts `/clientAdmin/client/menu/set` or `/reset` (`menuEditRequest`), the same write-trial-publish-
reload path as a copy edit (a draft, likewise, for a client with a sandbox), landing in the config already changing the item, else one overlaying the menu, else
`copy`. Hiding or showing is presentation, not permission -- the section gate still decides -- and the hint under
the table says so. Blocks other than the home menu (the sample's nav) stay read-only under "Other interface
changes". Wire names: `MNU` in the kernel.

**Editing the definition** (issue #1026, `DefinitionEditor` in `ClientsPage.kt`): under the summary rows of a client
defined in **stored** configuration (`definitionEditable`: present, origin stored, not a sandbox, not static here --
the backend refuses each in its own words; the page only spares showing an editor that cannot save), **Edit
definition** opens the presentation fields -- name, description, domain prefix, custom domain, web resources,
user labels as one comma line (`labelsOfText`) -- seeded from the client's **stored** definition, the retrieve's
`storedDefinition` (`definitionDraftOf`; what the client runs differs from it by an unpublished draft, which the
editor must show and be able to take back, and by what a template fills in, which written back would become the
client's own). Save posts
`/clientAdmin/client/definition/set` (`ClientsApi.setDefinition`) with **only the fields that changed**
(`definitionEditRequest`; a cleared field goes as blank, which the backend clears, and Save is disabled while
nothing differs), the same write-trial-publish-reload path as a copy edit, a draft likewise for a client with a
sandbox (`savedNote` says which). The structural fields (environments, template, included traits, preload) and the
platform's (#820) are not inputs of the endpoint at all, so the editor never offers them: they stay in the read-only
rows, the platform's marked "(set by the platform)". A domain prefix or custom domain another client declares is
refused by the stored write itself (`GedraConfigService`), whatever write carries it. When the configuration
holding the definition (the retrieve's `storedDefinitionConfig`) has unpublished changes and the definition does not
ask for a sandbox (its saves would be drafts), the backend's draft rule would refuse every save, so the page says so
instead of offering the editor (`definitionEditOffer`; nothing is offered until the configurations have loaded, so
an open editor is never replaced by the note), linking down to the Stored configuration table, where the row
offered Publish is tinted (`configRowNeedsPublish`, `.op-row-attention`). After a save the editor keeps the stored
definition the result returned as its baseline until the page re-reads, so a quick reopen does not send the old
values back. A success bumps the refresh generation, so the heading, the
listing row and the shell follow the new name. The editable set is the kernel's `ClientPresentationFields`, which
the endpoint's input and this editor both read.

Denied honestly in two layers, as Users is: `HomeApi.fetchConfig().canManageUsers == false` shows a
not-available panel without calling the endpoint, and a refusal from the endpoint -- a `public` self-administrator,
who administers only their own users (#805) -- is shown as it came, through `LoadStateCard`'s `errorLead`. The
menu item is gated on the admin level like Users, so a `public` self-administrator is offered it and refused on the
page; the endpoint is the authority. The detail view (`c=<id>`, issue #906) and, later, editing a client and
designing its workflows (#903) open from this page.

## The Reports page (issue #1007)

`#page=reports` lists a client's named reports (`GET /clientAdmin/reports`) down the left and runs the open one
(`GET /clientAdmin/report/run`) as a table beside them; `reporting.md` at the repo root says what a report is. The
pure half -- parsers, the run's query, the table's columns, a cell's text, the paging line, the links -- is
`ReportsApi.kt` (covered by `ReportsPageTest`); `ReportsPage.kt` holds the components.

- **Where the page is rides the hash**: `c` (the client, for an `allClients` administrator only -- a client's own
  administrator sends no client and the endpoint answers for theirs), `rpt` (the open report) and `view`
  (`grouped` / `detail`). Report and client are ordinary links, so Back steps through them; the mode is written
  with `replaceHash` and **read back from the hash** (`reportSetupInForce`), never kept beside it, so the page
  shows what its address says. Rows are drawn only while they belong to the walk on screen (`reportWalkKey`). **Not `m`** for the mode: that is the catalog's, and any hash carrying it routes there.
- **The setup is "the report's own" until touched.** `ReportRunSetup`'s lists are null until the user changes one,
  and a null list is not sent, so the endpoint applies the report's defaults. A list the user **emptied** is sent
  as an empty list -- absent and empty mean different things to the endpoint (`reportRunQuery`).
- **Paging is the webapp's first cursor paging, and it goes forward only.** A page is fetched with the previous
  page's `next` as `after`; there is **Next →** and **← First** and no Previous, because a cursor says nothing
  about the page before it. The cursor is component state, keyed on the client, report and setup
  (`ReportRunSetup.signature`): anything that changes the query starts the walk again, which is also what the
  endpoint requires -- another query's cursor is a 400. The "Forms 26–32 of 32" line counts rows as the walk goes
  (`reportRangeText`), since a cursor carries no offset.
- **A detail row's "Open" link goes to the forms page**, which owns its hash and its ways home, so its back link
  reads "← My forms"; the way back to the report is the browser's Back. Do not add a `from=reports`: the forms
  page drops any `from` that is not a forms listing.
- **A grouped run's table** is what it grouped by, a **Forms** count, then each rolled-up column headed with its
  rollup, "Total (sum)" (`reportTableColumns`). A group with no value reads "(none)"; any other empty cell a dash.
- **Download CSV** (issue #1008) is the browser walking the cursor: `ReportsApi.runAll` → `walkReportRun` fetches
  the run as set up from its first page at `reportDownloadPageSize` (500), following `next` to the end, and
  `reportCsv` writes it -- there is no export endpoint. The walk asks `stillWanted` around every page, so changing
  the report or a control abandons it; **a failure part-way saves nothing**, since part of a run would pass for
  the whole, and so does leaving the page (a cleanup bumps the token, since the walk's scope outlives the
  component). In the file a detail run leads with the form id, a number is in full, a UTC timestamp is written
  `yyyy-MM-dd HH:mm:ss` (`csvTimestamp`; the ISO form is one spreadsheets leave as text), nothing is an empty
  field, and a cell beginning `= + - @` gets a leading apostrophe (`csvSafeText`, applied once per cell): a report's text is
  whatever somebody typed into a form, and a spreadsheet would run it as a formula. `walkReportRun` is `suspend`,
  and its tests return `GlobalScope.promise { … }`, which the Node runner awaits -- the way to test suspend logic
  here without a coroutine test library.
- **Data to look at**: the `report-demo` simulation creates acme and globex forms -- run it from the **Simulations**
  page, which then signs you in as an acme administrator in one click, or `kdr-probe --url <your server> report-demo`.
  `report-history-demo` does the same and adds five dated days of snapshots, landing on the History view.

**The History view** (issue #1037; `view=history`, the third button beside Per form and Grouped): a report's stored
snapshots (`GET /clientAdmin/report/history`, #1034) as a bar chart over days, with **Snapshot now**
(`POST /clientAdmin/report/snapshot`). No run is fetched behind it, and it has neither Group by nor Must have: a
snapshot is always the report's own setup. The pure half is `ReportHistoryApi.kt` (covered by `ReportHistoryTest`);
`ReportHistoryPanel.kt` draws it. It is the webapp's first chart, hand-drawn SVG (`react.dom.svg`, no chart
library), and these are the rules it was built to:

- **What can be charted** (`historyMetrics`): the count of forms, then each rolled-up column whose result is a
  number and that the report does not group by. A rolled-up date is a moment, not a height, so it is not offered.
- **One snapshot a day** (`latestPerDay`), and **only the current definition's**: each snapshot says whether it was
  taken under the report as bound now (`sameDefinition`), and one that was not is counted in a note, not charted.
- **A group's colour is its place, never its rank.** `historySeries` orders groups as first seen, oldest day first,
  so switching metric or one group overtaking another repaints nothing. Past eight groups, the eight with the most
  forms on the latest day are kept; the rest fold into a grey **Other** bar when the metric adds up (a count, a
  sum) and are left to a note when it does not (an average).
- **The palette is validated, not picked**: `--chart-1..8` in `app.css` are a fixed categorical order checked as a
  set against the card surface for colour-vision separation and contrast. Do not re-order them or add a ninth hue
  by eye. Text never wears a series colour; a swatch beside it does.
- **Laid out in real pixels** (`historyChartLayout`, at the panel's measured width via a `ResizeObserver`), not a
  `viewBox` scaled to fit, so axis text keeps its size in a narrow pane. Bars are at most 24 wide with 2 between
  neighbours, rounded at the data end and square at the baseline (`barPath`); ticks are clean numbers
  (`niceTickStep`); when the days are too many for a readable bar, only the latest that fit are drawn and a line
  says so.
- **Nothing is hover-only.** Each bar's hit target is its whole column of the plot, is focusable, and shows the
  same readout on focus as on hover; the table under the chart holds every day and every number the readout shows.
- **A Kotlin trap this view hit**: inside an antd `Button { }` builder, `loading = x` assigns a *local* named
  `loading` if the component has one (a `var loading by useState(...)`), not the button's property -- which sets
  state during render and crashes with React's "too many re-renders". Name such state something else, or write
  `this.loading`.

## Buttons and links on the form surfaces (issue #726)

The form view and edit pages draw two kinds of control, and which one a control gets is decided **by what it
does**, not by where it sits:

- A **button** is one of the page's *verbs* — something that changes the state of the page or the form. Start
  editing (`Edit`, `type = "primary"`), save (`Save changes`, primary), finish or cancel (`Done`, `Cancel`,
  default), delete (`Delete form` and its confirm, `danger`). "Start editing" is a verb whether it flips the
  survey view into edit mode or hops to the raw editor, so the two read-only views draw the same primary
  `Edit`.
- A **link-styled control** (`type = "link"`, or the shared `<a class="back-link">`) goes somewhere else
  without changing anything: an alternate view of the same form (`View raw`, `Raw edit`, the listing's
  `View Info`), the way back (`formsBackLink()` / `formsBackToListing()` — never a hand-rolled link-button),
  and the pager.

One deliberate exception: the forms **table's** per-row actions are all link-styled, `Delete` included, because
a row is dense and a full button per row would not fit. Everywhere else, a destructive action is a `danger`
button. The three surfaces share `formsEditorHeader` (back link, title, right-aligned actions), so a new action
lands in the same place on each; classify it by the rule above before picking its type.

**Link colour lives in `app.css`, for `<a>` links and link buttons alike, and it is measured.** `.back-link` and
`.ant-btn.ant-btn-link` both read `--accent-bright` at rest and `--accent-brighter` (one step up the same indigo
ramp) on hover, with an underline — scoped to live, non-destructive link buttons: a `danger` link keeps antd's red
and a disabled one keeps antd's dimmed colour with no hover, both of which a bare colour rule silently overrides.
It is a CSS rule and **not** an antd token on purpose: antd's dark algorithm
re-derives a `colorLink` *seed* to a dimmer shade (measured: `#818cf8` came out `#717ad6`, 3.8:1), so a token
cannot pin the palette's value. The rule exists because antd's stock dark link (`#1668dc`) measures **2.8:1**
against the slate card and its derived hover *darkens* to **1.5:1** — the algorithm is tuned for antd's own
near-black backgrounds, where a darker hover still reads; on this shell it vanishes. On the scheme's indigo the
rest state is 4.9:1 and hover 7.3:1, moving *away* from the background.

Two things about verifying it were learned the hard way. **The hover and active colours need `!important`.**
antd 6 applies its state colours through *nested* rules (`&:not(:disabled):not(.ant-btn-disabled):hover`) whose
effective specificity beats any reasonable app selector, and a stylesheet walk will not even find them unless it
recurses into nested rules — so a plain override *looks* right in the CSS and still loses on screen. And **a
hover colour can only be verified with a real pointer**: `:hover` cannot be synthesised from script, so reading
the rule's declared colour proves nothing. Put the pointer on the control (the browser pane's `hover` action, by
ref), wait out antd's `0.2s` transition, then read `getComputedStyle(el).color`. If a link ever looks dim,
measure that way against its surface before touching the palette, and keep hover brighter than rest.

## Choice widgets, and the free-entry one (issues #261, #418)

`SchemaForm` draws a choice field from the schema, and which control it draws is decided by two keywords:

| schema | control | means |
| --- | --- | --- |
| `g-options` | antd `Select` | pick one of these, and nothing else validates |
| `g-options` + `g-openOptions` | antd `AutoComplete` (`OpenChoiceField`) | pick one, **or type your own** |
| `g-options` on an array's items | `Select mode="multiple"` | pick several |
| both, on an array's items | `Select mode="tags"` | pick several, **or add your own** |

**A free-entry ("non-strict") list is antd's `AutoComplete`, and needs no new dependency.** antd renders it as
`<Select mode={SECRET_COMBOBOX_MODE_DO_NOT_USE} suffixIcon={null}>` — the same `@rc-component/select` engine
the closed dropdown already uses, so it inherits the theme tokens and the keyboard behavior for free. Reaching
for Downshift, react-select or a hand-built widget buys nothing that matters here and costs a second styling
system beside antd. A native `<input list>` + `<datalist>` is tempting for its zero cost and is not usable:
the popup is browser-drawn, so CSS cannot reach it and it renders as a light control inside our dark shell.

Four things about it were expensive to learn, and none is guessable from the docs:

- **The value shown after a selection is the option's `value`, never its `label`.** Intentional in antd since
  v4, and `optionLabelProp` is explicitly `Omit`ted from `AutoCompleteProps`, so it cannot be worked around
  inside the component. Treat it as a design rule instead: on an open list, keep labels close to values,
  because a free-entry value has to be something a person could plausibly have typed.
- **`filterOption` as a *function* is not a top-level prop in antd 6** — it moved under `showSearch`. A
  function passed at the top level is **ignored silently**, so a filtering rule can look broken when it was
  simply never called. Several rules were written before that was noticed. The boolean form still works.
- **Filtering is off (`filterOption = false`), on purpose.** A combobox that narrows its popup hides the rest
  of a short list exactly when someone is trying to find out what is on offer — and once a value is committed,
  the box holds it, so reopening would offer only that one option and nothing else. A long list will want
  narrowing back; that is when the `showSearch` note above matters.
- **Do not put widget state in a form field component.** A keystroke re-renders the form, so anything a field
  keeps in `useState` is at the mercy of what the parent does between renders. Two versions of this widget
  held a "has the user typed?" flag and neither survived. Prefer a rule computed from what the control already
  passes you.

**The array case behaves differently from the single one, and less well.** `tags` mode commits a typed value
on **blur** rather than on Enter, and while a non-matching value is being typed the popup shows only the
"create this" entry — the other suggestions disappear, which is the very thing `filterOption = false` fixes for
the single-choice field. It does not fix it here (tried; no observable difference, so the line was removed
rather than left in looking load-bearing). Standard tags-mode behavior, and acceptable, but if an open
multi-select ever becomes a surface people use a lot, this is what to improve.

**Testing note — measured, on the `#page=debug&tool=choice` debug page (`DebugChoicePage.kt`).** An
`AutoComplete` is driven by *typing*, because the typed text **is** its value; no popup is involved. A plain
`Select` resisted the browser tools for three independent reasons, none of them a bug in the widget:

1. In a **hidden** browser pane `requestAnimationFrame` never fires, so antd's popup freezes at the first
   frame of its open animation — **0×0** — and a closed one lingers fully drawn at the first frame of its
   close. A coordinate click on what a screenshot shows lands on the page behind, and antd closes the popup as
   an outside click. Screenshots show the *opposite* of the true state; measure the DOM instead.
2. With `virtual` on (the default) the `role="option"` items are a 0×0 accessibility mirror with **no click
   handler** (the visible items have the handler and no role), so a click by accessibility ref cannot select.
   `virtual = false` puts the roles on the real items.
3. The tools' `key` action sends `keyCode`/`which` = 0, and rc-select's list navigation switches on `which`,
   so ArrowDown/Enter from it are inert. `type` is fine.

**What drives one, with the popup still frozen:** click the combobox to open it (it opens on mousedown), then
from `javascript_tool` dispatch `KeyboardEvent`s to the inner `<input>` with `keyCode`/`which` set
(`Object.defineProperty`), keydown + keyup per key, yielding a microtask (`await null`) between keys so React
commits the move before Enter. Measured on single, `mode="multiple"`, `virtual=false` and `showSearch`, and
confirmed on the Users page's Access-level select with **no change to the widget**: it has no `id` prop, but its
inner `<input>` is the editor's first `.ant-select input` and is `document.activeElement` once opened (antd
generates an id for it anyway). Setting `theme.token.motion = false` on a `ConfigProvider` removes the
animation, and then a coordinate click on an option selects as well.

**Simpler still, and what a full flow was driven with:** open the Select the same way, then dispatch a `click`
to the visible `.ant-select-item-option` whose `title` is the wanted label, found inside the dropdown that
contains `<inputId>_list` — a pick *by label*, no arrow counting. The whole acme sequence has been driven this
way end to end from the browser tools, verified against `GET /gedra/acme/formDoc` (`item.entries`) after each
save: create a form on the creation page (two Selects, text inputs, submit), then on the Edit form delete the
questionnaire entry and add it back. Buttons are reached by accessibility ref or clicked by text from
`javascript_tool` (`+ Add`, `Add data`, `Save changes`). Two things about the Edit form that cost a wrong turn
each: a section's `✕` removes the **edit**, not the entry ("Save sends only the sections you leave in place"),
so deleting an entry is its **Action** Select → `deleteOrNoOp`; and a section added with `+ Add` shows its
Trait-id *and* Action Selects at once, and its `Data` object must be added (`Add data`) before the trait's
fields appear. So a choice widget no longer needs to be handed to a person to verify.

**That the UI *can* be driven is not a reason to prefer it.** Call the endpoint whenever the question is about
the backend or the data — seeding a form, checking that a save landed, exercising a validation rule: one
`fetch` is simpler, faster and less fragile than a page. Drive the UI only when the UI itself is what is being
validated — that a control renders the right choices, that an invalid choice shows the right message, that a
save navigates where it should. The run above used the API as the witness after every save for exactly this
reason.

## Design View: marking a form with the definitions behind it (issue #972)

An administrator's switch in the app bar (`Design`) turns the workflow pages into an annotated view: each trait
block and field is outlined on hover with its id, a click opens a side inspector (`DesignInspector.kt`) with the
definition behind it, where it was declared, and its authored JSON, and the fields the form leaves out are drawn
as hatched ghosts saying why. A field's copy -- label, description, hint -- can be edited there as **this workflow's
own** (issue #984, `WorkflowCopyEditor`): the edit is written into the workflow's stored definition as an alteration
of the field's type, shows on that workflow's pages only, and **Reset to shared** removes it. It is offered only
where the workflow is the client's own stored definition; elsewhere the inspector says why, from the block's
`editRefusal`. **A Design View save is made as the Clients page's editors make theirs** (`ClientStoredEdit`, issue
#1026): published and live at once for a client without a sandbox -- so it is refused while the config carries
somebody's unpublished changes (`unpublishedChanges`) -- and a draft of the parent's config, shown by the sandbox, for
one with a sandbox, whose own page points to the sandbox instead (`publishedOnly`). The two kinds of editor therefore
never leave each other a draft to refuse.

The **shared definition** -- what every workflow on the client draws -- is edited separately (issue #1029,
`SharedFieldSection`), behind a deliberate **Edit the shared definition** button and never as an option beside a
workflow's Save. It is offered for a definition the client declares in its own stored configuration, and says first
where it is used ("Used by 2 workflows: …") and which workflows keep their own copy of the field. It edits the
field's copy and its choices -- relabel one, or add one -- but never removes a choice or changes a value, since stored
forms may hold it. Its two headings say what an edit changes -- **Form copy** (what forms show) and **Choices** (what
the field accepts) -- under one save (issue #1039). A blank copy input is not empty copy: the form falls back to the
field's own title or name, description and range hint, and both copy editors show that fallback in place
(`copyFallback`, mirroring `SchemaForm`'s own fallbacks -- change the two together). A field the type's layout leaves
out of a list that owns the order (`reorder`, `authoritative`) cannot take shared copy, and the read says so per field
(`sharedCopyRefusals`), so the inputs are disabled with the reason rather than refused on Save. The facts come from the
definition read (`parseSharedFacts`); the save is `/clientAdmin/design/sharedField`. Where a definition comes from is said in one sentence (`provenanceText`, issue
#1013): where it is declared, and the client configuration altering it when one does -- the reader sees what every
client shares and what is this client's own, however it was assembled.

- **The backend explains; the page does not work it out.** The switch is a `sessionStorage` flag that rides every
  request as `X-Kdr-View: design` (see `applyRequestHeaders`); the backend honors it only for a client
  administrator, adding a `design` block to the workflow view -- an address and origin for every type the page
  draws. Never infer provenance in the webapp: a definition's origin is the backend's to say.
- **The page explains only its own decisions.** Which fields a form hides, and why, is decided in `SchemaForm` --
  so the webapp names those reasons (`FieldHidden`). They all come from one function, `formFieldPlan`
  (`fieldHiddenReason` per field), which both presentations follow: the ordinary form skips a field with a
  reason, Design View draws it as a ghost. **A new rule that hides a field goes into `fieldHiddenReason`**, never
  as a fresh `return@forEach` in the render loop -- a rule added there would hide the field from Design View too,
  with no ghost to say so.
- **Only the workflow form is marked.** It provides `DesignViewContext`; every other `SchemaForm` (the catalog,
  the raw editors) sees null and draws exactly as before.
- **Seeing it.** The `design-demo` simulation writes the `designdemo` client -- defined in data, so its definitions
  show as the client's own. Run it from the **Simulations** page (below), with a suffix for a fresh copy
  (`designdemouat`), and sign in as its designer in one click; or `kdr-probe --url http://localhost:7072 design-demo`.

## The Simulations page (issue #997)

`#page=simulations`, offered on a test instance only (the menu item is gated on `kdr:isTestInstance`; the route is
Home elsewhere): the simulations the node offers, from `/fixture/simulations` -- the catalog's shape, parsed with
`parseCatalog`, and not narrowed to the published API, since on a test instance simulations are for anyone testing it.
Each gets its input form from its schema (the same `SchemaForm` and `checkInput` the endpoint catalog uses) and a
**Run**; the run's report becomes **Sign in as …** buttons, which post `/fixture/becomeUser` and `afterSessionChange`
to the report's start page -- no reload. The pure half (`SimulationsApi.kt`: the report, the sign-in body, the start
page) is covered by `SimulationsTest`. What a simulation is, and how to add one, is in the `kdr-testing` skill.

## Errors: never a blank page (issue #223)

A throw during render used to unmount the whole React tree and leave an empty body — the least informative
failure the app can produce. Two nested error boundaries in `App.kt` now prevent that, and **for ordinary work
there is nothing to do**: anything rendered beneath a page is covered automatically, including a new page
added to the `when (page)` switch, and including portals (a boundary follows the *React* tree, not the DOM).

The two exist because they answer different questions:

- The **page** boundary wraps `div.app-content` and swaps in `ErrorFallback`, a panel *inside* the shell — so
  a crash costs the page, not the navigation, and you can click away. It is **keyed on the page**, because
  React never resets a boundary itself: without the key the fallback would outlive the very navigation it
  invites you to make.
- The **backstop** wraps the whole shell and swaps in `ShellErrorFallback`, which only offers a reload —
  there is no navigation left to offer. React runs the innermost matching boundary, so this changes nothing
  about how a page failure behaves.

**Three things to know when adding UI:**

- **New persistent chrome goes inside the backstop.** The app bar and the update banner are *inside* it — put
  anything similar there too, not as a sibling above it. This is the one placement decision the boundaries
  cannot make for you.
- **Boundaries catch render and lifecycle errors only.** A throw from an event handler, an effect, or a
  rejected promise never reaches one. `installGlobalErrorHandlers` reports those to the console but draws
  nothing, so a component doing async work should surface its own failures in its own UI — `EndpointCatalog`'s
  `runError` is the pattern.
- **Never swallow.** A component that catches its own exception and renders nothing hides it from the
  boundary *and* the console, and a silent catch is now the only remaining way to get a blank region.

Every report carries a `[kdr]` prefix (`errorLogPrefix`), so one search finds every frontend failure however
it arose — and a browser test can assert the console is free of them. Reporting happens *alongside* rendering
the fallback, never instead of it: a boundary that displayed prettily and stayed quiet would let such a test
pass on a broken page.

On-screen detail (message + component stack) is gated on the `showErrorDetail` app-config feature, which the
backend sets from `isTestInstance` — the same fence `_debug=explainAccess` uses, rather than a second notion
of "a dev build". Note the limit: a crash in the *shell* happens before the config fetch returns, so the
detail is withheld there even on a test instance and the console is where you read it.

### An HTTP caller must throw `ApiError` on a non-2xx, or a real 4xx reads as "unreachable"

`userFacingError` (`ApiError.kt`) is how a caught failure becomes what the user sees, and it branches on the
throwable being an **`ApiError`**: an `ApiError` with a 4xx status shows its message ("No user matching '2' is
within your access"); **anything else** — a bare `error(...)`, a `RuntimeException`, a raw `fetch` rejection —
has no status, so it is treated as a request that never reached the backend and shows *"The server could not be
reached. Please try again."* So a handler that replies with a perfectly good 400 message can surface as an
unreachable-server error purely because the caller threw the wrong type. This bit `SchemaCatalogApi.invoke`,
which had its own `fetch` path (for multipart uploads and downloads) and threw `error(map["message"])` on a
non-2xx (issue #545).

`Http` (the ordinary API layer) already does the right thing — on a non-2xx it parses the error envelope and
throws `ApiError(message, fromFragment, status, errorCode, traceId)`. **So route API calls through `Http` where
you can.** When a call genuinely needs its own `fetch` (a file upload, a download, a streamed body), replicate
that construction exactly: read the envelope, and `throw ApiError(...)` with the `status` — never a bare
`error()` — so the status survives to `userFacingError`. `applyRequestHeaders(headers)` returns the trace id to
put on it.

### A call whose failure is expected reads an `ApiResult`, never a `runCatching` (issue #967)

Where a page wants a value or a fallback -- a config that may be refused, a suggestion list that may not load --
wrap the call in `apiResult { … }` and read the `ApiResult`: `valueOrNull()`, `valueOr(default)`,
`failureOrNull()` to log, or a `when` over `Ok` / `Refused` (the `ApiError`, status and all) / `Failed` (an
`ApiFailure`: no answer at all). Only those two failures become values; a bug in handling the response and a
coroutine's cancellation propagate. `runCatching` caught both and turned them into the same default -- a null
dereference read as "the server could not be reached", and a cancelled fetch wrote its default into a page
already left. `Http.getApiResult` / `sendApiResult` return the result directly.

A **display** site that shows any failure keeps its `catch (e: Throwable) { … userFacingError(e) }`: it is a UI
boundary, and `userFacingError` rethrows a `CancellationException` for it. The browser's `fetch` is called in
one place, `fetchCompleted` (`Http.kt`), which is where a dropped connection becomes an `ApiFailure`; a call
that needs its own request (an upload) goes through it too.

## Iterating on the frontend without a rebuild each time

Rebuilding the bundle and restarting `:launch:run` is roughly a minute per change, which is a poor loop for
anything visual — a column width, a spacing tweak, a control that is the wrong size. The webpack **dev server**
turns that into about two seconds, and the flag that makes it work is easy to miss:

```bash
./gradlew :webapp:jsBrowserDevelopmentRun --continuous -Pwebapp.backendPort=7071 -Pwebapp.port=8081 -Pwebapp.open=false
```

- **`--continuous` is the load-bearing part.** Without it the dev server serves a snapshot: neither a Kotlin
  edit nor an `app.css` edit reaches the browser, because Gradle never re-runs the compile and resource-copy
  tasks that feed webpack. With it, both were measured at **~2 seconds** from save to served.
- **`-Pwebapp.backendPort`** points the API proxy at your own backend. It defaults to `7070`, the developer's
  IntelliJ instance — which is also the one port a second session must not bind, so without this the dev
  server is unusable from any checkout but the first.
- **`-Pwebapp.port`** moves the dev server itself off 8080, for the same reason.
- `-Pwebapp.open=false` stops it launching Chrome, which an agent session does not want.

Start your backend first (`KDR_DEFAULTS_FILE=none KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true ./gradlew :launch:run`), then browse the dev
server rather than the backend: **`http://localhost:8081/`**, not `:7071/wa`. The proxy forwards `/kda` and
`/st`, so the app is same-origin and logins work normally.

**For CSS specifically, probe in the browser before writing the rule.** Injecting a candidate `<style>` and
measuring with `getBoundingClientRect` answers "what would this look like" in one call, with no build at all —
and measuring beats eyeballing, since "an 816px input beside a 178px column" is a diagnosis where "looks
cramped" is a complaint. Write the rule to `app.css` once it is settled. Note the antd caveat below when the
rule targets one of its controls.

**Do not assume the developer's running app shows your change.** The human developer typically runs the
backend from IntelliJ against a **rebuilt production** webapp bundle, and does not run the dev server at all —
so the instance they have open reflects the last bundle *they* built, never your uncommitted frontend edits.
To see your own change you run your own: the dev server above, or your own `:launch:run` (which rebuilds and
embeds the bundle, ~a minute). Never ask the developer to look at something only your build contains, and
never read their window as confirmation of your edit.

## Signing a browser session in to verify a change (the `becomeUser` fixture)

Most of the app is behind a login — a login-gated surface (anything in the `gedra` section, the forms pages)
is not even *visible* to a signed-out caller, so `/schema/endpoints` omits it and a page fetched anonymously
comes back empty. Verifying such a change in a browser therefore starts with a session, and the fast way in is
a test fixture rather than the real email-code flow:

- **`POST /kda/fixture/becomeUser`** with `{email, level, client, capabilities, name, persona, personaSuffix}` creates-or-finds the user
  and logs you straight in — **no verification code**. It is a `forTestingOnly` endpoint, so it exists only on a
  test instance (`KDR_TEST_INSTANCE`, which the in-memory local server is). Call it from the browser page's own
  `fetch()` so the session cookie lands in the browser, then reload the app to fetch as the new identity (a
  same-hash navigate does not reload — call `location.reload()`).
- **Pass `client` to become a *specific* client's user** (`client: "acme"`). This is what a **per-client**
  surface needs — a client's schema variant, its usage columns and search fields — and without it you land in
  the default client (`hub` for a user given the `allClients` capability, else `public`; issue #799) and verify
  the wrong variant. An unknown client is refused rather than silently downgraded.
  Since the identity split (issue #747) `client` is part of *which* user you become: an address that already
  has a user in another client gets a **second** user in the one you name, not the existing one.
- **`level` is the privilege rung** (`user` / `admin` / …); `admin` is client-wide, so a gedra listing it runs
  goes through the in-memory **cache** read path, where an ordinary user (scoped to their own rows) goes through
  SQL — a cheap way to exercise the path a unit test cannot easily reach. Seed whatever the surface reads with
  further `fetch` POSTs in the same session, using the constants' real *values* (a trait id may be
  `acmeSiteAudit`, not `siteAudit`).
- **Pass `name` to give the created user a real-world name** (a person's full name, distinct from the username /
  `publicName`, which falls back to the email). Set it when verifying a feature that reads the owner's *name* —
  a `prefillFromOwner` on the `name` attribute, say — since a nameless fixture user has nothing there to show.
  Ignored when the user already exists, like `level` (issue #736).
- **Pass `persona` / `personaSuffix` to become one of several users under one address** (issue #747): an identity
  (the address) may have a user per client, persona and personaSuffix. Naming any of `client`, `persona` or
  `personaSuffix` picks *that* user of the address, creating it when there is none and **recovering** it when it
  was deleted recoverably (same user, all its content back, unregistered again); naming none logs in as the
  identity's default user: the chosen default, else the one most recently acted as (every login stamps it,
  issue #748), else its first outside `public` (issue #752), else its first -- among the **registered** users
  (issue #749; a fixture user is registered on creation). Given only an address that already has a real-client
  user, the fixture therefore signs in as that one: name `client: "public"` to get the placeholder. `personaSuffix` is the UAT batch discriminator (`A`, `B`, `1`, `2`, …).

This is one `fetch`, not a heavyweight login — reach for it rather than declaring a login-gated change
unverifiable. Other test fixtures exist for more specialized needs (for example reading a real login code back
from the in-memory mail sink to drive the actual auth flow); `becomeUser` is the one for "sign in as X and look".

## Reading a frontend crash: the readable bundle (issue #230)

What ships is webpack's **production** bundle, and a Kotlin exception reaches JS with `message` undefined and a
minified `name` — so the error boundary can only report `ji` at a byte offset, however good its plumbing is.

`./gradlew :launch:run -Pwebapp.dev=true` embeds the **development** build in its place. Same filename, same
resource directory, same URL, identical behavior — the boundaries, the config flags and the debug pages all
work exactly as before. What changes is that the same crash reports:

```
IllegalStateException: Deliberate fault from the debug page (issue #227).
    at DebugFault$lambda (KotlinDynamicRuntime-webapp.js:4548)
```

naming the Kotlin declaration rather than an offset. Combined with the debug fault routes above, that is the
difference between a diagnosis and a guess.

It is a **troubleshooting** build: ~24 MB instead of ~2 MB, and a slower first load. And it is one *or* the
other per build — Kotlin/JS runs both executable modes through a single compile-sync directory whose contents
differ per mode, so one invocation cannot honestly produce both. Separate invocations are fine.

## Debug pages (issue #227)

A small area that exists only where the deployment permits it (`allowDebugPages`, from the backend's
`isTestInstance`), for diagnosing the app rather than using it. Reached by URL, which is what lets a browser
test drive it with nothing but a link:

```
#page=debug                  index of what is available
#page=debug&tool=state       resolved app config + refresh generation
#page=debug&tool=fault       throws while rendering -> the page boundary catches
#page=debug&tool=choice      the antd choice widgets, instrumented with an event trace (see "Choice widgets")
#<any page>&fault=shell      throws in the app bar   -> the backstop catches
```

Where the flag is off the route resolves to Home — the page does not exist rather than being refused, so
nothing acknowledges that a way to break the app is there. `allowDebugPages` is deliberately a **separate**
flag from `showErrorDetail`: seeing internals and manufacturing a failure are different powers.

**Adding a tool** is a branch in `DebugPage` plus an entry in `DebugIndex`. Keep faults as dedicated
components rather than conditionals inside real pages — the one exception is the shell fault, which must live
in `AppBar` because proving the *backstop* catches requires the chrome itself to throw.

Two behaviors worth knowing before you touch it, both learned the hard way:

- **A fault must stay true while its parameter is present.** An earlier version consumed the request during
  render so a reload would not re-fault; React retries a failed render, the retry no longer faulted, and it
  recovered instead of showing the boundary. A fault that stops being true mid-render is not testable.
- **Escaping the shell fault is explicit.** The backstop is not keyed, so nothing resets it, and its reload
  would re-read the URL that caused the failure. `reloadWithoutFault` strips the parameter first, using
  `history.replaceState` and *then* reloading — `location.replace()` does not navigate for a hash-only change,
  so the reload after it re-reads the original address.

## Frontend tests (issue #161)

`webapp` has a `jsTest` source set (multiplatform `kotlin.test`, the same framework `base/kernel` uses) for
**pure-logic** tests — plain `Map`/`String` in, typed value out, no React, no `fetch`, no DOM. Run them with:

```bash
./gradlew :webapp:jsNodeTest    # runs under Node; no browser needed
```

The JS target declares `nodejs()` alongside `browser {}` purely so this task exists; `jsBrowserTest` is
disabled in `build.gradle.kts` so `check`/`build` never pull in a headless Chrome. There is **no** DOM/React
test harness yet — component rendering, HTTP, and full-app behavior are still browser-driven, and verifying
them means opening the app and looking. The case for changing that, and the decision it waits on, is recorded
at `deferred-work.md#when-a-frontend-change-breaks-a-page-its-author-did-not-open`.

**Keep the mapping testable.** A UI-config fetcher's `UiConfig` → typed-config transform lives as a pure
top-level function next to its `*Api` object (`appConfigFrom`, `homeConfigFrom`, `authConfigFrom`,
`profileConfigFrom`); the `suspend` fetcher is just fetch + delegate. Add new config mapping the same way — as
a pure function — so it can be covered without a server. `TraceId` and `Copy` (in `WidgetGroup.kt`) are
likewise pure and covered.
