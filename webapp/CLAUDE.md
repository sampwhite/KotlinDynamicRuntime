# Frontend guidance (webapp)

Auto-loaded by Claude Code sessions working under `webapp/`. This holds **shared frontend knowledge** — not
personal config (that belongs in your own, non-versioned, root `CLAUDE.md`).

## Static content: Markdown fragments (issue #59)

The backend serves per-component UI text as **Markdown fragment files** through the **static context root**
(`st` by default). These are content, not API, so they deliberately do **not** appear in the
`/schema/endpoints` catalog — you won't discover them there. This note is how you find out about them.

**Request:** `GET /st/<appId>/md/<fileId:buildId>` (e.g. `/st/myapp.acme.en/md/emailForms:9f3ac1`)

- `appId` — **you** construct it: the application you're serving, plus an optional client-variation suffix,
  plus an optional locale suffix. Opaque to the backend for now (it ignores it); a future backend may return
  different content per `appId`.
- `fileId` — names the file `md-fragments/<fileId>.md` in the owning backend component's resources. If that
  component isn't in the deployment, the file is absent and the request 404s.
- `buildId` — a cache-busting suffix (a content hash the backend provides). The endpoint **strips and
  ignores** it; its only purpose is to change the URL when the file changes.

**Response:** a free-form two-tier JSON map `{ namespace: { key: value } }` with an **immutable**
`Cache-Control` (cache it forever / front it with a CDN — the `buildId` busts it on change).

**Using a fragment value:** each value is Markdown that may embed `${namespace.key}` placeholders. Resolve
them with the kernel's `String.evalTemplate(data)` — the fragment map *is* the data map, so `${email.subject}`
reads `map["email"]["subject"]` — then render the resulting Markdown.

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
  *Authorized JavaScript origins*, and an unregistered one fails entirely in the browser (a `403` plus
  `[GSI_LOGGER]: The given origin is not allowed for the given client ID`) without the backend seeing
  anything. `http://localhost:7070` (same-origin, `/wa`) and `http://localhost:8080` (dev server) are separate
  origins and both need registering; see `environment-variables.md`.
- `GET /profile/ui/config` — **login-required** (`profile` section); features `{hasPassword, canSetPassword}`,
  state `{userInfo}`. Fragment file `profile`.

The backend helper `fragmentRefs(…)` + `SchTypeBuilder.uiFragmentsProperty()` (in `content/UiConfig.kt`) keep
the envelope consistent across groups.

## The admin console: editing someone's authority (issue #225)

The Users page edits three **independent** things, and treating them as one control is the mistake the whole
screen is arranged to prevent:

- **Access level** — a rung of `RoleLadder` (`user` < `operator` < `admin`), shown as a *single-choice*
  `Select`, because the levels are an ordering and holding two is not a thing one can be.
- **All clients** — an off-ladder **capability**, a checkbox. The level says *what* someone may do; this says
  *whose data* they may do it to. Different axes.
- **Organization** — an optional narrowing *within* a client. Blank means client-wide.

**Compose, never replace.** A role list is sent whole, so an edit that rebuilds it from one control silently
drops the others. `RoleLadder.rolesAtLevel(current, level)` moves someone between rungs while preserving
anything off the ladder, and `rolesWithCapability(roles, capability, granted)` moves a capability on or off.
`draftRoles` composes them **in that order** — that is what lets a level change keep a capability and a
capability change keep a level. Saving compares role *sets*, not levels, or a capability-only edit would look
like no change and never be sent.

**Controls that could only ever fail are not shown.** "All clients" appears only to a caller who holds it (the
backend refuses to grant reach the granter lacks), and the Organization field is editable only by someone not
confined to one (a confined administrator may assign only their own). This is the advertise-versus-serve drift
issue #211 exists to remove, relocated into a form: a control that can only produce a 400 is worse than no
control.

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

`AdminApi` calls the **`userAdmin`** paths ([UADEP]), not the full-scope `admin` ones. That surface serves both
kinds of administrator correctly — a capability holder is simply unconfined on it — so the console needs no
branch on who is asking. `canManageUsers` from the home config decides whether the page is offered at all; it
shapes the UI and is not the enforcement point, which stays the section gate.

Paths, field names and the ladder all come from `base/kernel`, so a backend rename breaks compilation here
rather than at runtime.

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
test harness yet — component rendering, HTTP, and full-app behavior are still browser-driven (a larger,
deferred effort).

**Keep the mapping testable.** A UI-config fetcher's `UiConfig` → typed-config transform lives as a pure
top-level function next to its `*Api` object (`appConfigFrom`, `homeConfigFrom`, `authConfigFrom`,
`profileConfigFrom`); the `suspend` fetcher is just fetch + delegate. Add new config mapping the same way — as
a pure function — so it can be covered without a server. `TraceId` and `Copy` (in `WidgetGroup.kt`) are
likewise pure and covered.
