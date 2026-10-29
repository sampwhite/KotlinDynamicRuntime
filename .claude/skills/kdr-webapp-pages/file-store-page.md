## The file store page (issue #1297)

`/wa/operator/files`, **File store** under the Operator menu (app-only, like Simulations, since an edge keeps no files):
what the file store holds, drilled bucket by bucket and folder by folder, each file downloadable -- what Linode's
console shows of a bucket, for whichever store the deployment runs on. `OperatorFileStorePage.kt` draws it; the pure
half (`FileStoreApi.kt`: the answers read into views, the addresses, the words) is covered by `FileStoreBrowserTest`.
Wire names are the kernel's `FSB`, the paths `OPS.fileStore*`, each file's record the `FMETA` the web assets use.

- **The calls**: `GET /operator/fileStore/buckets` (each bucket, and in the summary what the store is), `GET
  …/list {bucket, prefix?, limit?}` (the folders and files directly under a folder), `GET …/download {bucket, key}`
  (the file, as an attachment under its key's last segment) and `GET …/download/meta`. All in the `operator`
  section, so a caller without the role sees the refusal on the page, as on the other operator pages.
- **Folders are read off the keys** -- no store keeps any -- and a file's record (`<key>.meta.json`) is folded into
  its row, never listed as a file. A file missing its record or its content is listed **flagged** (`state`:
  `noMeta` / `noContent` / `unreadableMeta`, worded by `fileStateText`) rather than hidden, and only a whole file
  has a Download: it is what `CheckFileStore` would report, seen where it is. A folder past the limit is cut, folders first, and says so.
- **The bucket and folder ride the address** (`HP.bucket`, `HP.folder`; the folder read as the endpoint names one,
  `fileStoreFolder`, so a typed address without the slash opens the folder), so Back steps out through the folders
  and a folder is linkable; the breadcrumb (`breadcrumbs`) leads back up. A download is `startDownload` on an `<a>`, so a
  refusal does not navigate away.
- **It is a raw view.** The endpoints read the provider itself, past the rule that a client's files are reached
  through `forOwner`: an operator sees every folder, `clients/` included. Read-only: nothing on the page writes or
  removes.
