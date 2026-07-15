# Frontend guidance (webapp)

Auto-loaded by Claude Code sessions working under `webapp/`. This holds **shared frontend knowledge** — not
personal config (that belongs in your own, non-versioned, root `CLAUDE.md`).

## Static content: Markdown fragments (issue #59)

The backend serves per-component UI text as **Markdown fragment files** through the **static context root**
(`st` by default). These are content, not API, so they deliberately do **not** appear in the
`/schema/endpoints` catalog — you won't discover them there. This note is how you find out about them.

**Request:** `GET /st/<appId>/md/<fileId:buildId>` (e.g. `/st/myapp.acme.en/md/emailForms:9f3ac1`)

- `appId` — **you** construct it: the application you're serving, plus an optional account-variation suffix,
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
{ fragments: [ { fileId, buildId } ], features: { … }, state: { … } }
```

- `fragments` — the Markdown fragment file(s) this group's copy comes from, **each already carrying its
  `buildId`**. This is how a component learns its `fileId:buildId` (the previously-open question): fetch each
  at `/st/<appId>/md/<fileId:buildId>`. `features` and `state` are group-specific.
- These calls are cheap and meant to be **re-fetched on navigation/invalidation** — err on calling too often.
  It's fine for each widget in a group to fetch independently.

**Endpoint model:** one config endpoint **per widget-group**, not a swiss-army endpoint switching on a
"group" arg (a per-endpoint output schema is what the schema/validation layer and the runtime's
dynamic-endpoint story need). Slice namespaces by "who authors the copy"; be pragmatic in leaf namespaces,
disciplined in a hub (`nav`/`shell`) everything composes through.

Current UI-config endpoints:
- `GET /auth/ui/config` — anonymous; features `{registration, codeLogin, passwordLogin}`, state `{userInfo}`
  (anonymous `userInfo` when logged out). Fragment file `auth`.
- `GET /profile/ui/config` — **login-required** (`profile` section); features `{hasPassword, canSetPassword}`,
  state `{userInfo}`. Fragment file `profile`.

The backend helper `fragmentRefs(…)` + `SchTypeBuilder.uiFragmentsProperty()` (in `content/UiConfig.kt`) keep
the envelope consistent across groups.
