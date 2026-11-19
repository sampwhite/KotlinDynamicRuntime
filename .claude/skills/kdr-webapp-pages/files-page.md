## The Files page (issue #1863)

`/wa/files` (`FilesPage.kt`, its pure half in `FilesListing.kt`, covered by `FilesListingTest`) lists the caller's data
files over `GET /files/list`, the files uploaded for forms' fields (`docs/design/data-files.md`). Not the operator's
file store browser (`file-store-page.md`), which shows a bucket's objects and knows nothing of forms.

- **Named for whose files it lists**, as the forms listing is: **My files** for a caller who reads their own,
  **Files** for one who reads every file of the client -- an administrator of it or an editor -- by the same fact
  (`seesClientForms`), so the two menu entries (`HMENU.files`, `HMENU.clientFiles`) never show together. The listing is
  held to the client the caller acts in, however far their reach.
- **A growing listing** (`ShowMore.kt`): the first `listingBatch`, then more; a change of filter starts over. Filters are
  kind, status, and whether a form uses the file, each with an "any" choice (`filesAny`, since a Select's value cannot be
  null).
- **Each row**: the name as a link to the content (a new tab), kind and size, status, when the upload began (relative),
  who uploaded it only when some row is not the caller's (`filesShowOwner`), and **Used by**, the count of live forms
  linking it -- read or not by the caller, since any keeps it. A count opens the forms under the row (`FileUsesList`,
  over `/files/usedBy`), each a link to the form's view, with the number the caller may not open. An image or a video
  offers **Preview**, which draws `DataFileValue` under the row. One row's forms and one row's preview open at a time.
- **Remove only where it can succeed** (`fileRemovable`): a file no form uses, behind `confirmedRemove`, whose
  question names where it acts (`deleteTargetText`); for an upload under way it reads "Abort this upload?". A file a
  form uses says why it stays instead (`fileKeptText`): it is taken out of the form, which unlinks it.
- **It is a listing, not an editor**: a card with its filters and table, like the API keys page, not the panels of a
  page that edits one record.
