## A client's own web assets (issue #1130)

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

## A client's file refs (issues #1153, #1154)

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
