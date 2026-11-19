# Data files: files in a form's data

A **data file** is a file uploaded for a form's data: a photograph of a site, a scanned document, a video of an
inspection. A trait's field names it by its id, and the file itself is held by a record of its own. This note records
what that record is, how a file reaches it, how a form names it, who may read it, and what clears what uploads leave
behind. **Status: built** -- the record, the upload, serving, the links between forms and files, the form control and
the Files page, and the nightly sweep (issues #1834 to #1840, #1863).

They are not a client's **named images** ([`config-file-refs.md`](config-file-refs.md)), which are part of its
configuration. In code each keeps its own word: "file ref" for a named image, "data file" (`GedraDataType.dataFile`)
for this.

<!-- Theme: #DFILE -->

## Why a file is a record of its own

A trait could hold its file outright, and it would be simpler: no record, no link, gone with the form. A file is a
record of its own because:

- **A file is uploaded before the form is saved.** A video takes minutes to send. The record exists from the moment
  the upload begins, so the form names something real.
- **Several forms may name one file.** A record has a life of its own, and the [links](#a-form-naming-a-file) between
  forms and files say which forms use it and refuse its removal while any does.
- **What becomes of a file belongs to the file.** Its status, and later the renditions a conversion makes of a video,
  are written to its record, never into a form's data.

## The record

A data file is a `GedraData` row of kind `dataFile` (`gd.df.<client>.<base>`), so it is owned, scoped, cached, and
soft-deleted as every gedra is. It holds no entries: its metadata is under `data.file` (`DFILE`):

| Field | What it is |
| --- | --- |
| `name` | The name it was picked under. |
| `kind` | Its kind (`FileKinds`): png, jpeg, webp, gif, mp4, webm, or pdf, told by its bytes. |
| `mimeType` | The type it is served as: its kind's. |
| `size` | Its size in bytes; while uploading, the size it said it would be. |
| `hash` | The SHA-256 of its bytes, once its upload has finished. |
| `status` | `uploading` or `ready`. `pending` and `failed` are kept for a conversion, not built. |
| `renditions` | Empty: what a conversion would add. |
| `uploadedAt` | When its upload began. |

Nothing that writes entries -- a create, a patch, an import -- takes a data file (`GedraDataType.carriesEntries`).

**The bytes are named by their hash**, in the `data-files` bucket under the client's folder:
`clients/<client>/files/<sha256>` (a sandbox's under its parent's, as its named images are). Two uploads of one file are
two records sharing one copy of its bytes, and a key that names its content is never rewritten, which is what lets it
be read in ranges with no check of its hash ([the file store's ranged read](#reading-a-file)).

## Uploading a file

**A file of 8 MiB or less is uploaded in one request** (#1840): `POST /files/upload?name=&mimeType=`, the file the raw
body, as `application/octet-stream`. It answers the file's record, `ready`, and is refused as an upload in chunks is: by
the checks a begin makes, a `Content-Length` past 8 MiB before the body is read, and the checks of the finish. It is a
begin, one chunk, and its finish, run in one request, so it adds no path of its own through the store, and a file it
refuses, or that any failure stops, is aborted, leaving nothing behind: nobody holds its id to resume it. **It is not
safe to send again**: one whose answer was lost may have made the file, and a second would make another. So the webapp
sends it again only after a 429, which the dispatcher answers before reading the body; for anything else it says so,
and an extra file a person's own retry makes is unlinked, for the sweep (#1839). An image or a document is usually this
size, and a resume would gain it nothing.

A larger file -- a video, above all -- comes **in chunks, in order**, so a lost connection costs a chunk rather than the
file:

1. **Begin** (`POST /files/upload/begin {name, mimeType, declaredSize}`). The type must be one of the data kinds, and the
   size at most its kind's largest (video 2 GB, an image 20 MB, a PDF 50 MB), and the client's plan must have room
   (below). It makes the record, `uploading`, and the upload's row, and answers the chunk size: 8 MiB.
2. **Chunks** (`POST /files/upload/chunk?fileId=&offset=&chunkHash=`, the chunk the raw body, as
   `application/octet-stream`). Each is the next one, at the offset the upload has reached, the chunk size long unless it
   is the last, and hashes to the SHA-256 sent with it. One sent again, already taken, is answered as before, so a
   sender that lost the answer may send it again. Any other is a 409 carrying `nextOffset`. **The chunk that completes
   the file finishes the upload** in the same request (#1840): its checks are in the next section, and its answer
   carries the file's record under `file`.
3. **Status** (`GET /files/upload/status?fileId=`) says where to resume after a lost connection.
4. **Abort** (`POST /files/upload/abort {fileId}`) ends it without a file: its parts dropped, and the file a finish
   that failed part-way put together from them, which nothing else names.

Until #1840 the finish was a call of its own, `POST /files/upload/finish`. It never did anything but follow the last
chunk, so a caller made a call that told the server only what it already knew.

**The running hash is saved between chunks** in the upload's row (`GedraFileUploads`, under the file's own lock), as the
kernel's resumable `Sha256` saves it: consecutive chunks may reach different nodes, and the store is never made to read
a 2 GB file back to hash it.

**A chunk is written to the store before the lock is taken**, so no lock is held across a store's network call. Under
the lock the offset is asked again, and only then does the upload advance. A chunk written for an offset another
request reached first is either replaced in the store by the one that advanced, or refused when the upload completes as
not the part recorded: never a file made of parts nobody checked.

**Memory is bounded at the dispatcher.** A chunk is read whole before its handler runs, under one of the node's
`KDR_RAW_BODY_PERMITS` (#1836), so the separate chunk permits this issue first planned are the same thing, and not built
twice.

An upload may run for 24 hours from its beginning. One past it is no longer taken, holds no room in its client's plan,
and the sweep (#1839) removes what it left. The upload's row is deleted once its upload finishes or is aborted.

## What finishing checks

The chunks are put together, and the file is checked before it is ready:

- **Its bytes are the kind it was sent as** (`FileKindSniff`): a file sent as a PNG whose bytes are a JPEG is refused.
- **An MP4's video is H.264** (`Mp4Video`). The finish walks the file's boxes to the `moov` index, a header at a time
  and then the index whole, and reads each video track's codec from its sample description. H.264 (`avc1`, `avc3`)
  plays in every browser. HEVC (`hvc1`, `hev1`), which phones record by default, plays in some, and is refused with a
  message asking for H.264: stored, it would show nothing for half its viewers. Converting a video is not built.
- **A QuickTime movie is an MP4** (#1840): what a Mac or an iPhone exports, named `.mov` or `.mp4`, its `ftyp` box
  naming only `qt  `. Its boxes are the ones MP4's came from, so it is walked the same way -- its index often after
  its media -- held to H.264 the same way (a ProRes master is refused for its codec), and stored and served as
  `video/mp4`. Until #1840 its brand refused it outright, which refused the H.264 movies people most often have. One
  from a Mac (H.264 and AAC, its index after its media) was played that way in Chromium; Safari plays QuickTime
  natively, and Firefox reads such a file as MP4, though no one has watched one there yet. A codec a person knows by
  name is refused by it: a ProRes master is told "ProRes ('apcn')", not only its code. **Its audio is not checked**, as an MP4's is not: AAC, what is exported, plays
  everywhere, while uncompressed PCM, rare outside an editor's masters, may play without sound.
- **A WebM** is checked by its EBML document type.
- **The plan still has room**: asked again, since two uploads begun at once can each have found room the other took.

A file that fails is removed, record and bytes, and the refusal says why -- an MP4 whose boxes are no video we can read
among them. One that passes is named by its hash -- the staged copy moved there, or dropped for a copy already stored --
and marked `ready`.

**A finish may run again after a failure part-way**, a store's or the network's: the sender sends the last chunk again,
which is answered as taken and finishes. (The status then reports every byte taken and the file still `uploading`.)
That the store put the chunks together is recorded in the upload's row the moment it is done, so the second run takes up
from where the file is -- staged, or already under its hash -- and never asks the store to complete an upload it has
completed. A last chunk sent again after the file is finished is answered with its record; any other chunk of a finished
file is a 409, since it is not one the file took.

**One request finishes an upload at a time.** A finish claims the upload under its lock, recording when in its row
(`finishingAt`), before it asks the store for anything. A large file's finish takes a while -- the parts put together
and, on S3, copied to the file's hash -- and a sender whose connection dropped meanwhile sends the last chunk again.
That request finds the claim and is answered 429, to be sent again shortly, rather than asking the store to complete
parts it no longer has (a 404 that would read as the upload's end). The claim carries a token, so only its holder
renews it -- between the finish's steps, so a long finish keeps it -- or gives it up, at once when the finish fails. One
whose node stopped lapses five minutes after its last renewal (`DFILE.finishLeaseMs`), and the next last chunk takes it
over. The webapp, answered 429 for a last chunk, keeps sending it every few seconds, up to three times the lease,
rather than reporting a stopped upload; the field says it is checking the file meanwhile.

## Reading a file

`GET /files/info?fileId=` answers its record. `GET /files/content?fileId=` answers its bytes a range at a time (#1836):
an image or a video shown in place, anything else a download under its name, with `Range`, `If-Range`, and
`If-None-Match` honored and its hash as its strong `ETag`. A file still uploading has no content yet (a 409).
`DELETE /files/delete?fileId=` removes it, aborting an upload under way, and is refused (409) while a live form names
it, naming the forms the caller may read and counting the rest. The record is soft-deleted. The bytes stay, since
another record may share them, until the sweep takes those no record names.

## A form naming a file

A trait's field takes a file when its type is a data file (`dataFile(FKIND.png, maxSize = ...)` in the schema builder,
format `gedra-file`): a string, the file's id, with the kinds it takes and, optionally, a size below theirs. The form's
data holds only the id.

**Each save links the form to what its fields name** (`DataFileLinkHook`, a write hook, in the save's own
transaction): one row of `GedraFileLinks` per field naming a file -- the file, the form, the entry, and where in the
entry's data the field is. A create, a patch, an import, and a recompute that stores computed values each keep them
current. A file a field names that it did not name before the write must be:

- a live data file of the form's own client, no longer uploading;
- of a kind the field takes, and no larger than its limit;
- one the caller may use: in their read scope -- their own, or any of the client's for an administrator or an editor --
  or already named by this form, so saving a form someone else put a file on needs no right to the file. The system's
  own writes use whatever the form names.
- at a field whose path, map keys and all, is at most 500 characters, since the path is part of the link's key.

Anything else refuses the save (400), saying which field and why. The system's own write -- a recompute storing
computed values -- is not failed for it: that field is left unlinked, and the log says so.

**Only what a write put there is judged.** A value the write only carried along is left as it was, linked or not. One
stored before its field became a file field was never checked, so it is never linked, never opens the file to anyone,
and never refuses an unrelated edit of the form.

**A client whose traits hold no file field pays nothing**: no links are read or written for its forms. The trade is that
a client taking away the last of its file fields keeps its forms' links as they were, holding those files from removal,
until a file field returns.

**Locks.** A save holds the form's lock and takes the lock of each file it newly names, in sorted order. A removal takes
only the file's. So the two cannot wait on each other, and whichever takes the file's lock second sees what the first
committed: a save after a removal finds the file gone, and a removal after a save finds the link.

**A removed form keeps its links**, since it may be brought back, but only a live form's count: for a removal's refusal,
for reading through, and for `usedBy`.

**A read of a form** (`GET /gedra/formDoc`) carries `files` beside its data when it names any: by id, each file's
name, kind, size, and status -- of a file it links, so a value its save never checked tells a reader nothing. What a
form says of a file is read from the file's record, so it is never stale.

**An import drops a form's file fields** and keeps the rest of the entry, counting each such entry under the discard
category `dataFile`: the files are another client's or another deployment's, and do not travel with an export. An entry
that does not validate without them -- a file field its trait requires -- is left out of the form, under the same count,
rather than refusing the import.

**To try it**, the sample component (loaded in developer environments) declares a global trait, `sample:siteMedia`,
with a photograph (PNG, JPEG, or WebP, up to 5 MB), a video (MP4 or WebM), and a report (PDF). The sample client
`globex` takes every global trait, so its users' forms carry it.

## Who may do what

The `files` section is any signed-in user's, and how far a caller reaches is their read scope, as for a form:

- **Reading** a file -- its record, its content, and which forms use it (`GET /files/usedBy?fileId=`) -- is for
  whoever's scope admits its record, its owner or an administrator or editor of its client, and for whoever may read a
  live form that names it.
- **Removing** a file is for the first of those alone, and only while no live form names it.
- **An upload** is its owner's alone.

`usedBy` answers each field of a live form the caller may read that names the file, and how many live forms they may
not read use it too.

## Listing files

`GET /files/list` (#1863) lists the live data files the caller may read **within the client they act in**, most
recently written first, as a growing listing: their own for an ordinary user, the client's for an editor or an
administrator. A caller who reaches every client lists one at a time, the one they act in, rather than every file of
the deployment. Each row is the file's record, its owner's name, and **how many live forms link it**, read or not by
the caller, since any of them keeps the file from removal. The counts come from one query of the client's links, by an
index on the links' `client`, and the forms from the cache; the files are the cache's index of the client's rows, as the
plan's count is. It filters by kind, by status, and by whether any form uses the file (`linked`). A row still uploading
carries when its upload stops being taken (`expiresAt`), so a page can tell an abandoned one from one under way.
`usedBy` names each use as the form's pages do -- its trait's title, its field's label -- and when the form was created.

The webapp's **Files** page (**My files** for a caller who reads only their own, as the forms listing is named), offered
only where some trait of the client's forms has a file field (the `kdr:clientHoldsFiles` fact), draws it: each file's
name as a link, its kind and size, status, when its upload began, who uploaded it when that is not always the caller,
and how many forms use it -- which opens them, from `usedBy`, each a link to its form. A file no form uses has
**Remove**, behind a confirmation naming where it acts; for an upload under way it aborts it. One a form uses says to
take it out of the form instead.

## The plan

A plan counts the megabytes of a client's files (`fileMegabytes`, 500 on the free plan): every live data file's size,
added up and rounded up -- one being uploaded at the size it said, unless its upload ran past its 24 hours. It is asked
when an upload begins and again when it finishes, from the gedra cache's index of the client's rows, so it costs no
query.

## The sweep

A nightly job (`dataFileSweep`, 04:00 UTC, `DataFileSweepJob.kt`, #1839) clears what uploads leave behind, every client
in turn, one task per thing it removes. Each task **asks again** before it removes anything, since the list is made
before the tasks run, and a dry run removes nothing and notes each in the trace.

- **Uploads past their time** go at once: their staged parts, anything put together from them, their row, and their
  record, which holds no bytes. One whose finish holds a fresh claim is left to finish.
- **Files no live form links** go once they have been unused for longer than `KDR_FILE_ORPHAN_GRACE_HOURS` (a week by
  default): `ready`, and linked by no live form -- replaced in its field, uploaded for a form never saved, or held only
  by forms since deleted, which nothing restores. **Unused since** is the latest of its upload, the last time a form
  stopped naming it (`unlinkedAt` on its record, written by the link hook under the file's lock), and the deletion of
  the last deleted form that names it, so a file replaced or a form deleted by mistake keeps a whole grace for the undo,
  however old the file. The removal is a soft delete, and its guard checks again under the file's lock, so a form that
  links the file at the last moment keeps it.
- **Bytes nothing names** go once they are older than the same grace: an object under a client's `files/` that no live
  record names -- of the client or its sandbox, whose bytes are its parent's -- and no upload whose parts are put
  together and moving there (the upload's row records the hash the moment the store assembles the file,
  `GDU.contentHash`). A file put together under `staging/` for an upload no longer under way goes after an upload's 24
  hours. **Two records of one file share its bytes**, so a new upload of bytes the store already holds keeps them, however
  old the object is.
- **Staged uploads nothing holds** -- S3's multipart uploads, or a store's own -- go after an upload's 24 hours: begun
  for an upload whose row never came, or went without them. The store is asked for them once a run, not once a client.

**Each task asks again of its own thing only**: a file's record and links, an object's own date and whether its hash is
named, a staged upload's token among the upload rows. A run's cost is the client's files and folder once, then a few
reads a removal.

What is left a narrow window: bytes the sweep found unnamed, which a new upload of exactly that file shares in the
moment between the sweep asking again and removing them. The grace makes the bytes old; nothing makes that moment
wide.

## What is not built

Each is in [`deferred-work.md`](../development/deferred-work.md) under the trigger that would make it worth doing:

- **Converting a video** to H.264, in a worker job on nodes with the CPU for it.
- **Presigned uploads and downloads** straight to S3, and **chunks in parallel**.
- **Copying a file over 5 GB** in parts, once a file kind may be that large.
- **Several ranges** of a file in one answer.
- **HEAD** on a file.
- **Carrying data files with the forms that name them** to another deployment.
- **Posters and thumbnails.**
- **Scanning uploads for malware.**
- **Resuming an upload across a reload**, **editing a map of files**, a **kept count** of the forms that use each
  file, and keeping a **restorable form's** files.
