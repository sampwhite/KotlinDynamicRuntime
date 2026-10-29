## API keys (issue #1402)

A program calls the API as a user with an **API key**, `Authorization: Bearer kdr_<keyId>_<secret>`;
`docs/design/api-keys.md` at the repo root says what a key is and what it may do. The UI is three surfaces over one
component, `ApiKeysSection.kt`, with its pure half in `ApiKeysApi.kt` (covered by `ApiKeysTest`):

- **Profile** draws `ApiKeysSection {}`: the person's own keys, over `/user/self/apiKey*`, in a sandbox too (issue
  #1453), where a key acts with what the sandbox's rules give its user and stays in the sandbox.
- **The Users editor** draws it with `ownerId` for the open user, over `/clientAdmin/apiKey*`. It acts at once, unlike
  the editor's fields, which wait for OK. Not for a `public` self-administrator, whom the administrator's surface
  refuses. When the open user is the caller, `ownerId` is left off, so one's own keys are
  managed as on Profile.
- **The API keys page** (`/wa/api-keys`, `ApiKeysPage.kt`) lists every key the caller administers, soonest to expire
  first, filtered by status, each owner linked to the Users editor. A key is made from its user's page, not here: a key
  belongs to someone. Offered by the same fact as Reports (`administersClient`).

What the component holds to:

- **The new key is shown once**, in `ApiKeySecretDialog`, with a `CopyButton` and a `curl` command to try it
  (`apiKeyTryCommand`). It lives in component state only and is dropped when the dialog closes; never put it in the
  address, browser storage or a log. The server keeps only its hash, so nothing can show it again.
- **A change of access is confirmed first**: **Make read-only** or **Allow changes** (`apiKeyAccessChange`), each
  behind a `Popconfirm` saying what happens to whatever already uses the key.
- **An extension's date is shown before it is saved**, from the kernel's `ApiKeyRules` -- the rule the server applies,
  calendar months in UTC (`addMonths`) -- by `apiKeyExtensionPreview`, which also says when it would pass the
  deployment's furthest expiry (the listing's summary, `maxDays`).
- **What a create may ask for comes from the listing's summary** (`ApiKeyLimits`: the default life, the furthest
  expiry, the most live keys and, for one user's listing, how many they hold). At the limit the form gives way to a
  sentence (`apiKeyCreateBlock`).
- **Wire names are the kernel's** `AKEY`, and a key is the kernel's `ApiKeyInfo`, whose `status(now)` the table reads,
  so a key's status cannot be worked out two ways.
