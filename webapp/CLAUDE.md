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

**Using a fragment value:** each value is Markdown that may embed `${namespace.key}` placeholders. Resolve
them with the kernel's `String.evalTemplate(data)` — the fragment map *is* the data map, so `${email.subject}`
reads `map["email"]["subject"]` — then render the resulting Markdown.

A placeholder holds an **expression**, not just a path: literals, `+ - * / %`, `~` to join text, comparison,
`&& || !`, `cond ? a : b`, `a ?: b` for a default (`${user.name ?: "there"}`), and calls to a fixed set of
built-in functions (`${upper(user.name)}`, `${count(items)}`, `${formatDay(order.at)}`). It runs in the kernel, so a preview in
the browser resolves a template exactly as the backend will. A bare missing or null value still throws — say
what should happen with `?:` rather than relying on tolerance.

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

Two are **identity**, and sit at the top of the editor beside the email for that reason — they say who the
account is, not what it may do:

- **Name** — the account's real-world name: a person's full name, or a business's. Non-unique display copy,
  never an identifier.
- **Business account** — `isEntity`. It says how to *read* the name, not which field to read: there is one
  name input whose label switches between "Full name" and "Business name", rather than a second field
  appearing. That mirrors `UserProfile.displayName`, which is `name ?: publicName` with no reference to the
  flag, so the console and the app cannot disagree about what is shown.

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

**Testing note, and it is the good news:** an `AutoComplete` **can** be driven by the browser tools — typing
and clicking an option both work — because its control is a real `<input>`. A plain antd `Select` cannot be
(see `deferred-work.md`), so an open choice field is the one choice widget an agent can verify end to end.

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

Start your backend first (`KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true ./gradlew :launch:run`), then browse the dev
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

- **`POST /kda/fixture/becomeUser`** with `{email, level, client, capabilities}` creates-or-finds the user and
  logs you straight in — **no verification code**. It is a `forTestingOnly` endpoint, so it exists only on a
  test instance (`KDR_TEST_INSTANCE`, which the in-memory local server is). Call it from the browser page's own
  `fetch()` so the session cookie lands in the browser, then reload the app to fetch as the new identity (a
  same-hash navigate does not reload — call `location.reload()`).
- **Pass `client` to become a *specific* client's user** (`client: "acme"`). This is what a **per-client**
  surface needs — a client's schema variant, its usage columns and search fields — and without it you land in
  the default client and verify the wrong variant. An unknown client is refused rather than silently downgraded.
- **`level` is the privilege rung** (`user` / `admin` / …); `admin` is client-wide, so a gedra listing it runs
  goes through the in-memory **cache** read path, where an ordinary user (scoped to their own rows) goes through
  SQL — a cheap way to exercise the path a unit test cannot easily reach. Seed whatever the surface reads with
  further `fetch` POSTs in the same session, using the constants' real *values* (a trait id may be
  `acmeSiteAudit`, not `siteAudit`).

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
