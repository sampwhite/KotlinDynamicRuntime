# Frontend guidance (webapp)

Auto-loaded by Claude Code sessions working under `webapp/`. This holds **shared frontend knowledge** — not
personal config (that belongs in your own non-versioned `CLAUDE.md` in the **workspace directory**, the parent
of this repo). Repo-wide agent guidance — including who owns the workspace's configuration files — is in the
versioned [`CLAUDE.md`](../CLAUDE.md) at the repository root.

This file holds what applies across the front end. What one page decides -- the Clients page, the admin console, the
forms listing, Reports, Design View, a client's web assets and file refs, the public home page, and the rest -- is in
the **`kdr-webapp-pages`** skill, one file per page under `.claude/skills/kdr-webapp-pages/`: read the page's file
before changing that page.

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
renders as a `md-`-prefixed class by default (`.md-value`, `.md-code` are styled in `app.css`, and so are an
image's `.md-float-left` and `.md-float-right`, issue #1382: floated beside the text that follows, at the copy's width,
contained in their `.markdown` block, and not floated below 480 px -- and `.md-clear`, from an empty `[]{.clear}`,
which ends a float where the copy puts it, so what follows goes below the image); a surface that
cannot use a stylesheet -- a mail -- passes `MarkdownHooks(decorateSpan = …)` and realizes the role itself. An
attribute block holds only role names and numeric `width`/`height`; anything else makes it literal text. A
backslash escapes any ASCII punctuation (`\*`), and `escapeMarkdown()` applies that to a *value* being substituted
into copy, so an address like `_ops_@acme.test` reads as written (the mails do this to every param). The
same source renders to plain text with `renderMarkdownText()` (emphasis and roles dropped, a link as
`text (url)`, an image as its alt text), which is how a mail gets its text part from the one body.

**An image may name a client's file ref** (issue #1186): `![Our logo](ref:logo)` (`MDR.refScheme`). Copy says
*which* image and never which bytes, so a replaced image needs no edit to the copy. The renderer asks
`MarkdownHooks.refAddress` where the ref is drawn from and draws the image as its **alt text** when that says
nothing -- no such ref, its file missing, or no hook at all, which is a mail's case (a mail is read where none of
the client's files can be fetched). The `Markdown` and `MarkdownInline` components pass the caller's client's refs
from the shell config (`state.fileRefs`, each id to its `logo:<hash>` name; `ShellFacts.copyRefAddress`), so every
copy surface draws refs with no prop of its own; a surface drawing *another* client's copy -- the copy editor --
sets `refAddress` itself. A `[link](ref:logo)` is not a ref: refs are images, and it renders inert. Copy naming a
ref the client does not have is refused at the write (the file-ref check, `FileRefService.build`), and so is
removing a ref that copy still names.

**An image may name one a component ships** (issue #1339): `![A diagram](img:sample:siteVisitFlow)`
(`MDR.imageScheme`), the name rooted under the component's owner. A component declares its images
(`ComponentDefinition.images`, read from `component-images/` on its classpath); they are checked at boot and served at
`/st/<appId>/img/<name:hash>` (`IMG.staticPath`, `ComponentImageService`), the same for every caller. The shell config
carries them as `state.images` (each name to its `<name>:<hash>`, `componentImageNamesFrom`), and every copy surface
draws them through `MarkdownHooks.imageAddress` (`ShellFacts.copyImageAddress`) -- the copy editor too, which sets only
its own `refAddress`. **`img:` and `ref:` are separate namespaces**: a client changes what its copy shows by changing
the copy, never by a ref standing in for a component image. An image no component ships is its alt text, as a mail
draws every image, and copy naming one is a problem of the copy: the fragment check's for copy every client shares,
the file-ref check's for a client's own.

**Using a fragment value:** each value is Markdown that may embed `${namespace.key}` placeholders. Resolve
them with the kernel's `String.evalTemplate(data)` — the fragment map *is* the data map, so `${email.subject}`
reads `map["email"]["subject"]` — then render the resulting Markdown.

A placeholder holds an **expression**, not just a path: literals, `+ - * / %`, `~` to join text, comparison,
`&& || !`, `cond ? a : b`, `a ?: b` for a default (`${user.name ?: "there"}`), and calls to a fixed set of
built-in functions (`${upper(user.name)}`, `${count(items)}`, `${formatDay(order.at)}`, `${currency(order.total, "USD")}`). It runs in the kernel, so a preview in
the browser resolves a template exactly as the backend will. A bare missing or null value still throws — say
what should happen with `?:` rather than relying on tolerance.

**Where a file is shown** is declared with it (issue #933): `fragmentFiles(HFRAG.home, shownOn = "the app bar and
home page, …")`, carried to the fragment check and the copy editor's keys listing as `shownOn`; a file declared
without one is *not shown by this application* and the editor says so. Declare it for every new file a page reads.

**A page with no UI-config of its own reads a file the shell's names** (issue #1337). The Clients page, Reports and
Design View have no widget-group, so the home config's `fragments` list names their files after its own
(`PFRAG.files`: `clients`, `reports`, `design`), and such a page reads its copy with `usePageCopy(PFRAG.clients)`
(`PageCopy.kt`; empty until it arrives, and if it cannot be read). Those files hold, for now, the pages' **"What is
this?" explanations**: each at the `whatIsThis` key (`HFRAG.explanation`) of the namespace of the copy it explains,
read with `copy.explanation(ns)` and drawn by `WhatIsThis` (`headingLevel` and `headingId` for a section heading that
is not a plain `h2`). The `home` file holds the shell's and the forms pages' (`sandbox`, `forms`, `workflowForms`).
An empty key draws no link, so a client's overlay can remove one as well as reword it. A page's other words still
live in the webapp, and belong in its file as they are moved.

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
- `GET /profile/ui/config` — **login-required** (`profile` section); features `{hasPassword, canSetPassword,
  codeReadBack}`, state `{userInfo, loginId, account}`. `account` is the user's own history (issue #1480), the kernel's
  `AccountHistory` (`ACCT` names its fields): when the user was registered, the sign-in before this one (a switch
  between users, into or out of a sandbox, is not a sign-in and moves neither stamp), and when the address was proven
  -- this user's row and its identity only. A session's only, as the page's own: an API key is refused. Fragment
  file `profile`.
- The **switcher** (issue #749), login-required (`user` section): `GET /user/self/users` lists the `UserChoice`s
  the signed-in person may act as (registered, enabled users of their identity in clients this node carries, each
  with its `clientName`, issue #1110; `isCurrent` / `isDefault` marked), `POST /user/self/switch {userId}` reissues
  the session as one of them (`POST /user/self/switchClient {client}` as the person's default user in a client,
  issue #1110) (the app then goes home and
  bumps the refresh generation, as a login does -- no page reload, issue #1015; see `afterSessionChange`, which
  every same-person session change uses), `POST /user/self/setDefault {userId}` chooses which user the
  address logs in as, and `POST /user/self/removePublic {userId}` permanently removes one of the person's own
  `public` users (issue #752), down to nothing -- a `public` user's owner registered themselves and can register
  again, so the last user may go too, retiring the identity. Removing the acting user moves the session to the
  person's default user, or ends it when none remains. The **profile page** (issue #1330) is built to the page
  structure (issue #1479): under its title the structure line (the address, the person's users, the access of the one
  acted as, `profileStructure`), then the panels Your account (read-only: who, client, persona and access,
  organization, account type, when the address was proven, the user's registration and previous sign-in -- absolute,
  each labeled with its zone, as a user's own page shows them (issue #1480); not its activation, which reads out of
  order there, nor a password row, which Sign-in already gives -- the time zone, and "What you can do here" from
  `ShellFacts.reach`, the roles, and the organization -- `profileAccountFacts` and `profileAbilities` in
  `ProfileLayout.kt`, covered by `ProfileLayoutTest`), Your users (live), Your name (a draft), Sign-in (live, then a
  draft once a code is sent; Remove password through `DangerConfirm`), and API keys (live), each drawing its own
  result; Log out stays below them. It describes a `public` user's access without the `admin` every registrant there
  is given (`profileShownRoles`), since in `public` an administrator is no different from an ordinary user, and it
  takes the shell's facts -- what the caller can do, the time zone -- only once they are this user's
  (`ShellFacts.describes`), not before the bar has been told or for a moment after a switch; a moment shown before
  then still names its zone. For a person with more than one user or a `public` one, it shows them all in a table
  ("Your users": Persona, Client, and Name only when the users are named differently): the active one tinted and
  marked **Active now**, and the one a sign-in lands on marked **Next sign-in** from the list's `signsInAs` -- the
  default rule worked out (`defaultUserOf`), which differs from `isDefault` (the choice alone) when sign-in passes a
  chosen default over.
  Each row offers **Switch to this user**, which stays on the profile page (`afterSessionChange` with the profile's
  address; the leave from inside a sandbox, read from `ShellFacts.inSandbox`), **Use for next sign-in** (`setDefault`)
  where a sign-in does not already land, and **Remove** on a `public` row, two-step, saying when it is the whole
  registration (`removablePublicUsers`, `removalEndsEverything`). The table's rules are pure (`profileUserRows`,
  `showsUsersTable`, `showsNameColumn`, `usersHelpData`, covered by `ProfileUsersTest`). Its status line is the
  `profile` file's `users.help`, which pulls `users.helpExtra` -- empty as shipped, and the reference flavor's overlay
  says the bar switches too. The concept behind it is `users.whatIsThis`, opened by the heading's **What is this?**
  (`WhatIsThis`: a disclosure that opens its explanation inline, Markdown from the copy it explains, drawing no link
  when that copy is empty). The link's own words ("What is this?", "Hide") are the app's, not a page's: the shell's
  `home` file's `whatIsThis` namespace, told to every page through `ShellFacts` once the bar has its copy, so a page's
  file holds only its explanation.
  The shell config (`/home/ui/config`) carries the same list as `state.users`, so the app
  bar needs no fetch of its own: for a person with more than one user the **identity badge** says which one this
  is in brackets (`Demo Person [hub · Member B]`, only what tells it from the others -- `UserChoice.qualifierWithin`)
  and becomes the menu that switches; with one user it is the plain label it always was. The default persona
  is `member` (shown `Member`); `editor` and `admin` are the others. Given the session's roles, the current user's
  persona says the access it has when that differs from the persona's (`Member (Administrator access)`, issue
  #1450) -- in the badge, its tooltip, its own menu row, and the Profile page's active row and help, never on
  another user's, since the list carries nobody's roles.
- The **sandbox** (issues #929, #931), login-required (`user` section): `POST /user/self/openSandbox {client?}`
  moves the session into a client's Shadow Sandbox as the person's own user there, created on first use; it is for
  an administrator of the parent (an `allClients` administrator may name any client), and in a sandbox `admin`
  takes effect only for an identity that is an administrator in the parent. A user somebody opened the sandbox as
  is handed the roles of the user **outside** they opened it from (and, when it is the parent's own, its
  organization), asked at every request, so a role taken away
  outside is gone from the sandbox session too -- and none at all once that user no longer stands for the parent. A
  sandbox's own user is handed its row's roles, without `allClients` -- and, on one holding `admin`, without a level
  the person's user outside no longer holds (issue #1199). Every switch made **from inside** a sandbox
  goes through `POST /auth/self/leaveSandbox {userId}` instead (issue #1197; `switchesFromSandbox`,
  `AuthApi.leaveSandbox`), which is not gated by role: a sandbox user nobody stands behind any more acts with none.
  The shell config says when a sandbox session acts with no roles (`state.sandboxStranded`), and the chip's popover
  then explains that access has ended rather than describing the sandbox -- offering **Sign out** when no way back
  and no other user is listed, which is the case for a sandbox user that was itself disabled (the switcher list is
  empty for a session whose own user no longer acts for it). The bar offers **Open sandbox** when the
  shell config's `canOpenSandbox` feature says so; in a sandbox (`state.sandboxOf` / `sandboxOfName`) a chip takes
  its place -- **Acme Sandbox**, on screen however far a page scrolls, as the bar is -- whose popover says what the
  sandbox runs and offers **Back to Acme**, a switch to the person's own user there (`SandboxShell.kt`:
  `showOpenSandbox`, `sandboxChipLabel`, `sandboxInfoText`, `sandboxWayBack`). The UI word is "sandbox"; "Shadow" is
  the feature's internal name and appears in no UI text. In a sandbox the whole page says so too (issue #1049): the
  shell marks the page root (`data-sandbox`, `markPageSandbox`), and `app.css` turns the page behind the cards a mid
  teal (`--sandbox-page`; the page itself only -- the inset wells that share `--surface-page` stay slate) and draws a
  band along the top edge -- outside a sandbox there is neither. And since a cue that shows only
  in a sandbox protects nobody who wrongly believes they are in one, every delete confirmation names where it acts,
  from the form's or user's own client (`deleteTargetText`: "acme (live data)" or "acme's sandbox").
- **Invitations** (issue #751), anonymous like the rest of the auth flow: an administrator's create for an
  address that is not their own -- new, or another person's -- provisions an unclaimed user and, unless it is created
  disabled or with `sendInvitation` false (issue #1504), mails a link to `/wa/invite#token=<token>` (built under
  `KDR_PUBLIC_URL`, else the request's scheme and host); the answer's `invitationSent` says whether it went. The
  **invite page** (`InvitePage`) POSTs `/auth/invitation/preview {invitationToken}` to say what the account is
  and waits for **Accept**, which POSTs `/auth/invitation/accept`: the link is the proof, so accepting registers
  the user, verifies a new identity, signs the browser in, and goes home re-reading (`afterSessionChange`, issue
  #1015). Nothing happens on merely opening the
  link. `POST /clientAdmin/user/invite {userId}` sends one, or a fresh one for a lapsed link (the editor offers it for
  an unclaimed user). A claimed user is refused one, since the link signs its holder in: `POST
  /clientAdmin/user/remind {userId}` instead mails the ordinary sign-in page (issue #1504), and only where
  `KDR_PUBLIC_URL` is set or on a test instance, so the link a person is told to use their password at is never built
  from a request's headers. Either send is limited per address. The **register form** shows Client / Persona / Persona suffix to an `allClients` caller only; set, they ride
  `createInitial` and the backend refuses them from anyone else.
- **Claiming from the login page** (issue #751), since a mailed link cannot be required: the auth flow's third
  mode, `/wa/claim` ("Claim an account created for you", linked from the login page). The person types the
  address, the client and the persona as the invitation spelled them (`admin`, `member B`; `PERSONA.splitTyped`),
  with no choice lists so an anonymous caller learns nothing about a client. `POST /auth/claim/sendVerify`
  **always answers as a success** and the mail says the rest: the code, or that nothing matched (acknowledging
  the client when the address has a user there); the code is computed over the whole key, defaults applied, so
  it cannot be replayed against another user. `POST /auth/claim/register` is a code login aimed at that user:
  it claims an unclaimed one and simply signs in as a claimed one -- the one way to log in as a particular
  non-default user by address alone.
- **The mails are fragment copy** (issue #773): every mail the auth flows send -- the code, the invitation, the
  sign-in reminder, the claim page's answers -- is authored in the **backend** fragment file `mail.md` (never served; one namespace
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

## Growing listings (issue #1315)

The forms listing, the Users page, and a report's run show **one growing list**, not pages (`ShowMore.kt`): the
first `listingBatch` (100) rows, then **Show 100 more** and, once everything fits under `listingCap` (500), **Show all
(N)**. At the cap the count line says "Showing 500 of 812 — narrow the search to see the rest." -- search and sort are
how a row is found in a long list, so there is no deep paging, no infinite scroll, and no page-size picker.

- **Growing re-queries from the top with the larger limit**; it never splices rows in by offset. One query is one
  snapshot of the sort, so a row created or deleted between clicks is never doubled or skipped, and a refresh or a
  delete reloads exactly what is shown.
- **The count shown rides the address** as `shown` (`HP.shown`, written only past the first batch, read back through
  `shownFromRoute`, which holds it to the batch and the cap). On the forms listing it is a non-navigation key, so
  `formsListingReturn` carries it home from a form and the listing asks for the same rows again; the endpoint
  whitelist (`formsSearchKeys`) keeps it off the wire.
- **Changing the search, the sort, or the client starts over at the first batch.** A delete keeps the count.
- **History restores; an explicit navigation starts over.** Back and Forward land on an address that names the rows, and
  the listing shows them. Choosing the listing from the menu is an explicit navigation: from another page it mounts
  fresh, and on the page itself -- where the bare link may change the address at all -- the menu's revisit count
  (`useRevisitSinceMount`, on the refresh context) tells the page to start over: no search or sort, the first batch,
  re-read. A future signal that content changed (a latest-modified date on the config calls) could refine this.
- **Growing keeps the rows on screen while it loads**, so the reader keeps their place and the new rows arrive
  beneath it. The forms page's ordinary reload swaps the table for "Loading…", which collapsed the page and threw a
  reader at row 100 back to the top; a grow sets `growing` instead of `listLoading`. Users and the report table
  already kept their rows across a reload.
- **Scroll is not restored** on return yet: the rows come back, the position does not (see `deferred-work.md`).

## Buttons and links on the form surfaces (issue #726)

The form view and edit pages draw two kinds of control, and which one a control gets is decided **by what it
does**, not by where it sits:

- A **button** is one of the page's *verbs* — something that changes the state of the page or the form. Start
  editing (`Edit`, `type = "primary"`), save (`Save changes`, primary), finish or cancel (`Done`, `Cancel`,
  default), delete (`Delete form` and its confirm, `danger`). "Start editing" is a verb whether it flips the
  survey view into edit mode or hops to the raw editor, so the two read-only views draw the same primary
  `Edit`.
- A **link-styled control** (`type = "link"`, or the shared `<a class="back-link">`) goes somewhere else
  without changing anything (off these surfaces, the Users page's test-only Become sits among a row's links, and is
  a button so styled, issue #1511): an alternate view of the same form (`View raw`, `Raw edit`, the listing's
  `View Info`), the way back (`formsBackLink()` / `formsBackToListing()` — never a hand-rolled link-button),
  and the pager.

One deliberate exception: the forms **table's** per-row actions are all link-styled, `Delete` included, because
a row is dense and a full button per row would not fit. Everywhere else, a destructive action is a `danger`
button. The three surfaces share `formsEditorHeader` (back link, title, right-aligned actions), so a new action
lands in the same place on each; classify it by the rule above before picking its type.

**Link color lives in `app.css`, for `<a>` links and link buttons alike, and it is measured.** `.back-link` and
`.ant-btn.ant-btn-link` both read `--accent-bright` at rest and `--accent-brighter` (one step up the same indigo
ramp) on hover, with an underline — scoped to live, non-destructive link buttons: a `danger` link keeps antd's red
and a disabled one keeps antd's dimmed color with no hover, both of which a bare color rule silently overrides.
It is a CSS rule and **not** an antd token on purpose: antd's dark algorithm
re-derives a `colorLink` *seed* to a dimmer shade (measured: `#818cf8` came out `#717ad6`, 3.8:1), so a token
cannot pin the palette's value. The rule exists because antd's stock dark link (`#1668dc`) measures **2.8:1**
against the slate card and its derived hover *darkens* to **1.5:1** — the algorithm is tuned for antd's own
near-black backgrounds, where a darker hover still reads; on this shell it vanishes. On the scheme's indigo the
rest state is 4.9:1 and hover 7.3:1, moving *away* from the background.

Two things about verifying it were learned the hard way. **The hover and active colors need `!important`.**
antd 6 applies its state colors through *nested* rules (`&:not(:disabled):not(.ant-btn-disabled):hover`) whose
effective specificity beats any reasonable app selector, and a stylesheet walk will not even find them unless it
recurses into nested rules — so a plain override *looks* right in the CSS and still loses on screen. And **a
hover color can only be verified with a real pointer**: `:hover` cannot be synthesized from script, so reading
the rule's declared color proves nothing. Put the pointer on the control (the browser pane's `hover` action, by
ref), wait out antd's `0.2s` transition, then read `getComputedStyle(el).color`. If a link ever looks dim,
measure that way against its surface before touching the palette, and keep hover brighter than rest.

**A default (secondary) button is themed by antd component tokens** (issue #1399), set in `App.kt`'s
`ConfigProvider` as `components.Button`. The dark algorithm gave it antd's own container color, a near-black
`#141414` under a `#424242` hairline. That is darker than the slate card and panels it sits on, so it read as an inset
slot rather than something to press. It now has:

| state | fill | border | text |
| --- | --- | --- | --- |
| rest | `#334155` (`--border`), a step up from the card | `#475569` | `#e2e8f0` |
| hover | `#475569` | `#64748b` | `#f8fafc` |

Hover moves away from the background, as a link's does.

- **Tokens work here, unlike for links:** the algorithm re-derives a *seed* token such as `colorLink`, but takes a
  component token as given. Measured on screen, including a real-pointer hover.
- **Danger buttons keep antd's dark fill on purpose.** Their red text measures about 4.2:1 on it, and would drop to
  about 2.4:1 on the slate one.
- **Disabled buttons keep antd's colors too.**
- **Primary and link buttons are untouched.**

## Page structure: panels, drafts, and live actions (issue #1477)

A page that edits something reads the same way everywhere, so a reader always knows which button commits which
controls. The building blocks are in `Panels.kt`. The client page (issue #1397) supplied the panels and the links to
them; its editors, its Retirement confirm, and the bundle editor's sticky bar (`.bundle-editor-savebar`) predate the
save and confirm rules below and have not moved onto them yet. The Users editor (#1478) and Profile (#1479) are built to
all of them.

1. **A page is a stack of panels.** Each is a shaded box (`panel(id, heading, explanation, kind)`) with a heading and,
   optionally, "What is this?". Every control belongs to exactly one panel, except the page's way out — a back link
   at its top, or Profile's Log out at its foot — which belongs to the page, as does Become in the Users editor's
   header, which only a caller with test access is offered (issue #1511).
2. **A panel is a draft or live, never both** (`PanelKind`; `view` for a read-only panel, or a section holding editors
   of its own, as the client page's do).
   - **Draft**: edits wait for a save. One footer per draft, at the bottom of what it covers (`SaveBar`). It holds
     a primary verb naming the thing ("Save user", "Save name", "Save copy"), a link "Cancel" or "Discard", and a
     line in the style of `saveEffectText`. Save is disabled until something changed: `SaveBar` enables it only
     when told `canSave = true`, so a footer that forgets shows a Save that cannot be pressed. The panel's heading
     says "Unsaved changes" while the draft is dirty (`panel(..., dirty = ...)`). A draft too long for its footer to
     stay in sight uses a sticky `SaveBar` instead, as the copy editor does for a copy file.
   - **Live**: each button acts at once and is labeled with its verb ("Send invitation", "Create key"); the panel
     says so under its heading ("Changes here take effect immediately.").
   - **A panel may change kind with its state, never be both at once.** Profile's Sign-in is live (ask for a code,
     remove the password) until a code is sent, then a draft (the code and the new password) until it is saved or
     canceled.
   - **One primary button per panel.** "OK" is not a label.
   - The draft's Cancel and Discard are links, as a back link is: they drop the draft — leaving a form that makes
     something new, or taking an existing record's fields back to what is stored and staying on it, as the Users
     editor does. The form view and edit pages keep their own rule (Buttons and links, issue #726), where Done and
     Cancel are buttons in the header.
3. **Results show where the action was taken.** Each panel draws its own failure and note (`panelNote`), never one
   line shared by the page.
4. **A longer page, or tabs.** The default is one scrolling page of panels, with links to them at the top
   (`panelLinks`). Tabs only for sections that are separate tasks, visited one at a time, on a page past about three
   screens — no page meets that yet, so there are no `Tabs` bindings. **A draft never spans tabs**: Save must not
   commit what is out of sight.
5. **Simple first, the rest on request — per panel, not a global mode.** An "advanced mode" switch hides state: a
   reader in simple mode cannot see that a user holds All clients. A draft panel shows its common fields and puts the
   rest under `MoreOptions`, which opens itself when anything inside is set and names what is in its summary ("More
   options · Editor, labels: reviewer"); both come from its one `inForce` list, so they cannot disagree. It takes the
   record it belongs to (`recordKey`), so another record starts it afresh rather than as the reader left the last
   one. Each option inside has a one-line hint beginning "Use this when".
6. **Deleting the thing the page is about appears only when it can succeed** — an existing record, never one being
   created, never your own row — in a last `PanelKind.danger` panel, behind `DangerConfirm` (the danger button, then
   the question naming where it acts, `deleteTargetText` (issue #1049), the confirm, and the way back). It is armed
   for one record only (`recordKey`), so a page that swaps the record in place never shows a confirm left waiting by
   the last one. Removing a row from a list on the page is not this: it stays the row's own `confirmedRemove`
   (`RowActionStatus.kt`), or a `Popconfirm` beside the row. Either way, every danger button is confirmed.
7. **Show how things fit together before the knobs.** A page about a user opens with a read-only line of its
   structure — the person's address, their users (each by what tells it apart from the others), this user's access —
   before any field.

Disclosures are `Disclosure`, its contents drawn only once opened: closed to start unless `initiallyOpen`, and opened
by `openWhen`, which is how `MoreOptions` opens itself.

## Times in tables (issue #1141)

A table column shows a time **relative to now** (`RelativeTime.kt`): `just now` within a minute either way, then two
units -- `12m ago`, `1h 23m ago`, `1d 2h ago`, `in 3h` for a future one -- and from 48 hours on the day alone,
`2026-10-10` (`formatRelativeTime`). The exact `formatTimestamp` value is the cell's tooltip. A table reads its rows
against one `useRelativeClock()`, which re-renders it every 30 seconds -- so minutes are the finest unit -- and an
antd table files each row's tooltip under `timeTipKey(dataIndex)` for `timeColumnRender`; a plain table draws
`RelativeTime`. A forms-listing display column is drawn the same way (issue #1323): each cell is its value read through
the column's presentation (`presentedValue`, the kernel's `presentColumnValue`) against the same clock and
`DisplayZone.scriptEnv(now)`, with the value behind a reworded cell as its tooltip (`presentedCellTip`). The columns
and their presentations come once, in the listing's `summary.columns` (`ListingColumn`), not on each row; a `date`
column with none reads relative to now, and the form's heading (`formHeading`) reads through its column too. What
stays **absolute**: a form's or user's own page (it has the room), a filter chip's date bound (a
fixed instant; a relative one would move under the filter), a report's cells and the report history's rows (the
dates there are data compared across rows and snapshots, and the history's day column already names the day).

**Every moment is shown in the client's time zone** (issue #1115, `DisplayZone.kt`): `formatTimestamp` gives
`Oct 17, 2026, 5:00 PM CT` through the browser's `Intl`, labeled with the zone, and the relative time's day is the
day there. The zone is the shell config's `state.timeZone` -- the client's, else the deployment's display default
(`KDR_DISPLAY_TIME_ZONE`) -- which the app bar sets into `DisplayZone` on every read; with neither, moments are shown
in the deployment's day start (`deploymentZone`, labeled `GMT-8`). A day-only value is never
shifted. Each function takes the zone as an argument too, which is how its tests pass one. The CSV download keeps
UTC (`csvTimestamp`): a file cannot say a zone per cell.

**And entered in it** (issue #1235, `ZonedEntry.kt`): a `date-time` field in a schema form and the Users page's date
range filters read and write in the same zone (`entryZone()`, which falls back to the deployment's for a zone `Intl`
does not know, as the display does), naming it beside the picker ("India Standard Time (Kolkata) · GMT+5:30",
`entryZoneNote`). antd's picker knows only the browser's zone and makes its own plain `dayjs()` values (typed text, an
empty field), so the conversion is on its **wall clock**, not by handing it zone-aware values: a stored moment goes in
as the date and time it reads in the client's zone (`zonedPickerValue`), and what is picked comes back read there
(`zonedPickerIso`, `instantOfWallClock`). Where daylight saving makes a wall clock ambiguous the answer is
`java.time`'s, so browser and server agree: a skipped spring-forward time moves forward by the gap, a repeated
fall-back time is the earlier moment. No dayjs `utc`/`timezone` plugin, and no zone database: the kernel's
kotlinx-datetime cannot convert zones in the browser, whose bundle carries only `@js-joda/core`. The panel's **Now**
is hidden (`showNow = false`): antd's now is the browser's wall clock, which read in the client's zone is not now
there. One edge is documented rather than handled: a time in the *browser's* own skipped hour, when its zone and the
client's change clocks on different days. A day-only picker is untouched.

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

**Testing note — measured, on the `/wa/debug/choice` debug page (`DebugChoicePage.kt`).** An
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
- **And give it a key, as every child of the backstop has.** React matches unkeyed children by their place, so a
  banner that comes and goes (the update banner, the public test deployment's band) would shift the bar and the
  page and remount them, losing the page's state. The invitation page lost its token that way on a public test
  deployment: its first mount had taken the token from the address, and the remount found none.
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
backend sets from the caller's test access (a test instance, with env auth effective — issue #1149) or ENV DEBUG
— the same fence `_debug=explainAccess` uses, rather than a second notion of "a dev build". Note the limit: a
crash in the *shell* happens before the config fetch returns, so the detail is withheld there even on a test
instance and the console is where you read it.

### An HTTP caller must throw `ApiError` on a non-2xx, or a real 4xx loses its status

`userFacingError` (`ApiError.kt`) is how a caught failure becomes what the user sees, and it branches on the
throwable's type: an `ApiError` with a 4xx status shows its message ("No user matching '2' is within your
access"); an `ApiFailure` — no usable answer, which `fetchCompleted` makes of a dropped connection — shows *"The
server could not be reached. Please try again."*; and **anything else** — a bare `error(...)`, a
`RuntimeException`, a kernel parse refusing what was delivered — is a fault in the page, shown with its own message
(or *"Something went wrong on this page"* where the deployment obfuscates). So a handler that replies with a
perfectly good 400 message can surface as a page fault, its status lost, purely because the caller threw the wrong
type. This bit `SchemaCatalogApi.invoke`,
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

**In the UI** (issue #1183), for a caller with test access: **Become deployment admin** (one click: `admin` plus
`allClients`, as `TEP.deploymentAdminEmail`) and **Become a user…** (`/wa/test/become-user`, the fixture's form drawn from
its own schema) under Account in the menu, and the same as a card at the top of Home. Each identity one becomes is kept in
`localStorage` against the app config's `dataId` and offered again in one click, grayed once the data is reset. Each
has an ✕ that forgets it from this browser's list (issue #1503); the user stays, and becoming them again adds them back
(`BecomeUserApi.kt`, `BecomeUserPage.kt`, covered by `BecomeUserTest`). The Users page offers the same to a caller with
test access (issue #1511): `BecomeLink`, on each row and in the user's header (`becomeOffered`). The fetch below is
still the way in from script.

Most of the app is behind a login — a login-gated surface (anything in the `gedra` section, the forms pages)
is not even *visible* to a signed-out caller, so `/schema/endpoints` omits it and a page fetched anonymously
comes back empty. Verifying such a change in a browser therefore starts with a session, and the fast way in is
a test fixture rather than the real email-code flow:

- **`POST /kda/fixture/becomeUser`** with `{email, level, client, capabilities, name, persona, personaSuffix}` creates-or-finds the user
  and logs you straight in — **no verification code**. It is a `forTestingOnly` endpoint, so it exists only on a
  test instance (`KDR_TEST_INSTANCE`, which the in-memory local server is), and only for a caller with env auth
  there (issue #1149) -- which a direct local request is assumed to have, unless the session suppressed it with the
  app bar's env control. Call it from the browser page's own
  `fetch()` so the session cookie lands in the browser, then reload the app to fetch as the new identity (a
  navigate to the address already shown does not reload — call `location.reload()`).
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

A small area that exists only where the deployment permits it (`allowDebugPages`, from the caller's test access --
a test instance, with env auth effective, issue #1149 -- or ENV DEBUG), for diagnosing the app rather than using it.
Reached by URL, which is what lets a browser test drive it with nothing but a link:

```
/wa/debug                    index of what is available
/wa/debug/state              resolved app config + refresh generation
/wa/debug/fault              throws while rendering -> the page boundary catches
/wa/debug/choice             the antd choice widgets, instrumented with an event trace (see "Choice widgets")
/wa/<any page>?fault=shell   throws in the app bar   -> the backstop catches
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
  `history.replaceState` and *then* reloading, so the reload reads the address without it rather than the one
  that failed.

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
at `docs/development/deferred-work.md#when-a-frontend-change-breaks-a-page-its-author-did-not-open`.

**Keep the mapping testable.** A UI-config fetcher's `UiConfig` → typed-config transform lives as a pure
top-level function next to its `*Api` object (`appConfigFrom`, `homeConfigFrom`, `authConfigFrom`,
`profileConfigFrom`); the `suspend` fetcher is just fetch + delegate. Add new config mapping the same way — as
a pure function — so it can be covered without a server. `TraceId` and `Copy` (in `WidgetGroup.kt`) are
likewise pure and covered.
