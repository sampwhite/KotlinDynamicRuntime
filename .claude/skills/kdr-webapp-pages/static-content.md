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

### A client's own web assets (issue #1130)

A client's administrator may upload a **favicon** and a **brand mark** of its own, which stand in for the
deployment's for that client's people -- and a client defined in source code may name a **web resources package** a
component ships (issue #1301, `ClientDef.webResourcesId`), whose images it shows in a slot nothing is uploaded to; an
upload stands in front of the package's, and removing it shows the package's again. To the frontend the two are one
slot: the shell config names whichever is shown, and the address serves it. They are static content too, under the
same root and absent from the catalog for the same reason:

**Request:** `GET /st/<appId>/web/<slot:hash>` (e.g. `/st/kdr.en/web/favicon:9f3a…`) -- built by
`Http.webAssetUrl(name)` from the kernel's `WAS.staticPath`, never spelled by hand.

- You get the `slot:hash` **name** from the shell config, `state.webAssets` (`HomeConfig.webAssets`): a slot
  (`WAS.favicon`, `WAS.brandMark`) to its name, **only for the slots that show an image** -- uploaded, or the package's. An absent slot is the
  deployment's own image, exactly as before -- so a client with nothing set changes nothing.
- The image served is the **caller's** client's, whatever the address says: the hash never selects whose. It
  decides only the caching -- the name the config handed you is cached for good, any other gets the current
  image under `no-store`, and an empty slot is a `no-store` 404. So after an upload, re-read the config for the
  new name rather than keeping the old address.
- The shell does this already, and you should not do it again: `markUrlFor(config)` is the mark the app bar and
  the home hero draw (the client's, else `brandMarkUrl`), and the bar calls `applyClientFavicon` on every config
  load, which swaps the page's `<link rel="icon">` set for the client's and puts the deployment's back when the
  client has none -- so an upload shows without a reload. Both marks are `object-fit: contain`: a client's need
  not be square.
- **Known limits.** An anonymous visitor is in the default client, so a client's branding reaches its
  *signed-in* people until a client can be resolved from the domain (the limit copy overlays have). And the
  first paint is the deployment's: the shell's `<head>` is written before anybody is known. **The swap is a
  Chromium and Firefox behaviour**: Safari reads a page's icon links once, at load, and draws no SVG favicon at
  all, so there a client's tab icon appears on the next full load and only when it is a raster image. The
  `apple-touch-icon` (an iOS home-screen icon) is left as the deployment's: there is no slot for one.

### A client's file refs (issues #1153, #1154)

Beside its two web assets a client has **file refs**: named images held in its configuration -- uploaded by its
administrator into the file store, or (issue #1154) shipped with it as a local file on the server. They are served
the same way, under their own marker:

**Request:** `GET /st/<appId>/ref/<refId:hash>` -- the kernel's `FREF.staticPath(appId, FREF.versionedName(refId, hash))`.

- The same rules as a web asset: the **caller's** client's ref whatever the address says, kept for good when the
  hash is the file's own, the current file under `no-store` for any other, a `no-store` 404 for no such ref.
- **Unlike a web asset, a ref is configuration**: a client with a sandbox drafts one there, and its own people are
  served it once it is published. So the same `refId` can be two different files for the client and for its sandbox,
  each under its own hash -- which is what the hash in the address is for.
- **The Clients page lists and changes them** (issue #1176): the **Files** section of a client's page
  (`FileRefsSection.kt`; what it decides and what it says are pure in `FileRefs.kt`, covered by `FileRefsTest`),
  under Web assets, which it is drawn like. It is described with the page's other sections, in
  `clients-page.md`. Its calls: `GET /clientAdmin/client/files` (each ref with the record of its file -- the file
  store's, or the disk's for a local one -- whether it is `live` for the client's own people or a draft, whether a
  `stored` configuration holds it, and whether its bytes are `missing`); `GET …/files/takes` (the size, kinds and
  content types an upload is held to, how many refs a client is served, and the roots open to clients);
  `POST …/files/upload` (multipart `refId`, `file`, `description`; `Http.uploadApi`), `POST …/files/local`,
  `POST …/files/describe` and `POST …/files/remove`; and `GET …/files/download {client, refId, hash}` for a
  preview -- the listing's `hash` in the address, so a replaced file is a new one, as the web assets' preview does.
  Beside it, `GET …/files/download/meta {client, refId}` answers what the file is without the file (issue #1131);
  the section has no use for it -- the listing already carries each record -- but a caller after one file does.
  Wire names are `FREF`, `CCT` and the file's `FMETA`, all in the kernel.
  - **A change answers its `mode`** (`EDM.live` / `EDM.draft`), as a copy edit does, and the ref as it now is --
    which the section draws at once. After a *removal* a ref still being there, and not `stored`, is the one the
    client was shipped with under that id, which the removed upload stood in front of.
  - **Every change says how the page expects it to take effect** (`mode`, from `expectedMode`), as every editor's
    save on the page does (see "How a save takes effect" under "The Clients page"). A change the page called a
    draft that would be live is a 409, and not made -- so a removal confirmed as "from the draft" cannot take a
    file from the client's people because the sandbox went in between. Send it with any new change you add here.
  - **A ref a draft removed is still listed** (issue #1188): the listing appends, after the sandbox's refs, those
    the client's own people are served that the latest configuration no longer holds, each `removedInDraft`, and a
    removal made as a draft answers that row rather than none. The section marks it "Removed in draft", previews it
    from what is served (the download's and meta read's `served: true`, since the sandbox has no such ref), and
    offers only **Put back**: `POST …/files/restore`, which copies the published entry back into the draft as it is
    -- nothing uploaded or named again. Published, the row goes.
  - **Finding one among many** (issue #1190): a Find box over id and description, a toggle per mark the list
    carries, a stated order -- what needs attention (missing, removed in a draft, a draft) first, then by id -- and
    the first 20 with "Show all", all pure in `FileRefs.kt` (`fileRefPage`) over the listing already loaded. The
    order is the page's, not the registry's, so a row does not move on a re-read. An address has a `CopyButton`
    (`Clipboard.kt`, the app's one clipboard write, shared with the code blocks' copy) that says what it did in words.
  - **What a row offers is the backend's answer** (issue #1192): each row's `removable` is asked as a removal asks
    it -- whether a stored configuration of the client holds an entry for the id, taken as the load takes it (by
    configuration id) -- so a row offers Remove and Reword exactly when they would succeed, whatever state the
    configuration is in. `fileRefRemovable` falls back to `stored` only for a backend that does not say. Each row
    also carries `setAt` / `setBy`, its stored entry's own stamp -- who set this ref -- where the file's record says
    who first stored its bytes, under whatever id; the page says both.
  - **Only a `stored` ref has Remove and Reword.** One the client was shipped with can be uploaded over and
    cannot be removed or reworded (`…/files/remove` and `…/files/describe` answer 400: nothing stored holds it).
  - **A file is picked with `FilePickButton`** (`FilePickButton.kt`), which the web assets' upload uses too:
    picking the file is the upload, and there is no second button.
  - **Each row says what to write in copy to show it** (issue #1186): `![<its words>](ref:<id>)`
    (`fileRefImageMarkdown`), and beside it the address that resolves to (`Http.fileRefUrl(name)`). That address
    is the **client's people's**: the static root answers each caller with their own client's ref, so the section
    says whose it is, and of a draft's that it is the sandbox's until published.
- **A listed ref says its `source`** (`FREF.sourceStore` / `FREF.sourceLocal`), and a local one its `root` and
  `path`. **`hash` and `name` are absent for a local ref whose file is missing** -- there are no bytes to hash --
  so do not build an address from a ref that is `missing`. A local ref has no upload behind it: its `file` record
  is what the disk says (`uploadedAt` is when the file last changed, and there is no `uploadedByName`). A ref the
  client was shipped with cannot be removed (`…/files/remove` answers 400: nothing stored holds it), only
  uploaded over; removing that upload shows the shipped one again.
- **A client's administrator can also name a file the deployment keeps for the client** (issue #1164), where the
  deployment has opened a root to clients. The Files section offers it under the upload, and only then: the
  open roots are `…/files/takes`' `roots` (and `GET …/files/roots {client?}`, as `[{root}]`), empty on most
  deployments. `POST …/files/local {client?, refId, root, path, description?}` (JSON, no file part) names the file,
  answering what an upload answers. The `path` is under the client's own directory in the root, and nothing
  lists what is there, so it is a typed path. A 403 means the root is not open to this caller; a 400 that the
  file "is not there to be served as an image" is deliberately one sentence for every reason (missing, not an
  image, too large) -- do not try to tell them apart. Such a ref is listed with `source` `FREF.sourceClientLocal`,
  beside `sourceStore` and `sourceLocal`. Wire names are `CCT.root`/`CCT.path` and
  `UADEP.clientFilesLocal`/`clientFilesRoots`.
- **A `clientLocal` ref a client inherits from a template falls back to the template's file** (issue #1180) where
  the client has none of its own -- the template's is its default, as a template's copy is -- and the listing says
  so with `FREF.fromTemplate` (the template's id), which `fileRefWhereText` words. Only an absent file falls back: a
  file the client has that cannot be served is its own problem, and the ref is `missing`. A ref the client holds
  itself (one named through `…/files/local`) reads the client's directory and nothing else.
