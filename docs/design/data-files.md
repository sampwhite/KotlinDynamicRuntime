# Data files: files in a form's data

A **data file** is a file uploaded for a form's data: a photograph of a site, a scanned document, a video of an
inspection. A trait's field names it by its id, and the file itself is held by a record of its own. This note records
what that record is, how a file reaches it, how a form names it, and who may read it. **Status: the record, the
upload, serving, and the links between forms and files are built** (issues #1834 to #1838). The sweep and the form
control are not yet (#1839, #1840).

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

An upload comes **in chunks, in order**, so a lost connection costs a chunk rather than the file:

1. **Begin** (`POST /files/upload/begin {name, mimeType, declaredSize}`). The type must be one of the data kinds, and the
   size at most its kind's largest (video 2 GB, an image 20 MB, a PDF 50 MB), and the client's plan must have room
   (below). It makes the record, `uploading`, and the upload's row, and answers the chunk size: 8 MiB.
2. **Chunks** (`POST /files/upload/chunk?fileId=&offset=&chunkHash=`, the chunk the raw body, as
   `application/octet-stream`). Each is the next one, at the offset the upload has reached, the chunk size long unless it
   is the last, and hashes to the SHA-256 sent with it. One sent again, already taken, is answered as before, so a
   sender that lost the answer may send it again. Any other is a 409 carrying `nextOffset`.
3. **Status** (`GET /files/upload/status?fileId=`) says where to resume after a lost connection.
4. **Finish** (`POST /files/upload/finish {fileId}`), every byte taken. Its checks are in the next section.
5. **Abort** (`POST /files/upload/abort {fileId}`) ends it without a file.

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
- **A WebM** is checked by its EBML document type.
- **The plan still has room**: asked again, since two uploads begun at once can each have found room the other took.

A file that fails is removed, record and bytes, and the refusal says why -- an MP4 whose boxes are no video we can read
among them. One that passes is named by its hash -- the staged copy moved there, or dropped for a copy already stored --
and marked `ready`.

**A finish may be called again after a failure part-way**, a store's or the network's. That the store put the chunks
together is recorded in the upload's row the moment it is done, so a second call takes up from where the file is --
staged, or already under its hash -- and never asks the store to complete an upload it has completed.

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

## Who may do what

The `files` section is any signed-in user's, and how far a caller reaches is their read scope, as for a form:

- **Reading** a file -- its record, its content, and which forms use it (`GET /files/usedBy?fileId=`) -- is for
  whoever's scope admits its record, its owner or an administrator or editor of its client, and for whoever may read a
  live form that names it.
- **Removing** a file is for the first of those alone, and only while no live form names it.
- **An upload** is its owner's alone.

`usedBy` answers each field of a live form the caller may read that names the file, and how many live forms they may
not read use it too.

## The plan

A plan counts the megabytes of a client's files (`fileMegabytes`, 500 on the free plan): every live data file's size,
added up and rounded up -- one being uploaded at the size it said, unless its upload ran past its 24 hours. It is asked
when an upload begins and again when it finishes, from the gedra cache's index of the client's rows, so it costs no
query.

## What is not built

- **The sweep** of expired uploads, files nothing links, and bytes no record names (#1839).
- **The form control** that uploads in chunks and resumes (#1840).
- **Converting a video**, HEAD on a file, presigned uploads straight to S3, and the rest, each in
  [`deferred-work.md`](../development/deferred-work.md) under its trigger (#1841).
