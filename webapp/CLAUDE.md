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

**Still open:** how a React component learns its `fileId:buildId` — a component will get it from
backend-provided configuration, an area still to be built out.
