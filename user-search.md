# The admin user search, and its server-driven-UI shape (issue #411)

The admin console's user search is a **brute-force scan of the in-memory `AuthUsers` cache**, not a SQL query.
That is the whole reason it can search and sort on things that are not database columns — the public name
(username-or-email), and, because a user's organization is not a column either, the entire read scope. This
note covers what it does, and the **server-driven-UI (SDUI) shape** the extra-credit slice gave it: one shared
description of the fields that both the backend and the console build from.

## What the search is

`GET /userAdmin/userSearch` (and the full-scope `/admin/userSearch`) filters and sorts **active** users:

- **email** — case-insensitive substring
- **name** — case-insensitive substring of the real-world name *or* the username (so pasting a login handle
  finds the account, the behavior the old single search box had)
- **client** — exact match; only meaningful to an `allClients` caller, since anyone else is already confined to
  their own client by scope
- **updatedAt** — a from/to instant range

sorted by any of those (default `updatedAt` descending), capped at a large default (`USF.defaultLimit` = 500),
reporting `numItems` / `numAvailable` / `hasMore`.

**Active users only, by design.** The cache holds enabled rows (its initial load is enabled-only; a row
disabled later enters as a tombstone). Finding a *disabled or deleted* account to re-enable stays on the SQL
listing (`UserService.listUsers`). This was a deliberate call: the cache is the point, and it reliably holds
exactly the active population.

**Scope is a per-row predicate.** `ReadScope.admitsUserRow` is the one admission both the by-id read
(`queryAdministrableUser`) and this listing use, so they cannot disagree. A client-scoped administrator's scan
is served from the cache's `client` index rather than the whole table (`SqlCacheSnapshot.allByIndex`) — the
caching skill's sanctioned way to scope a listing, since the index *is* the scope. An `allClients`
administrator legitimately scans everyone.

## The SDUI shape: one description, both sides

The fields a user can search and sort on are described **once**, in `base/kernel`, as
`userSearchFieldSpecs: List<UserSearchFieldSpec>` — plain data: a name, a label, a filter kind
(`substring` / `exact` / `dateRange`), whether it is sortable, whether it is client-scoped, and (for a range)
the two bound param names. Because `base/kernel` compiles into **both** the JVM backend and the Kotlin/JS front
end, that same value is visible on both sides at compile time.

- **The backend** (`userSearchFields`, in `base/common`) attaches the `AuthUserRow` accessors — how to read a
  field off a row, and how to compare it — keyed to the spec by name. The accessors live here, not in the
  kernel, because they take an `AuthUserRow`, a JVM type the front end never sees.
- **The console** iterates the spec to render its **filter panel** (a substring field → a text box, an exact
  field → a picker, a date-range field → two date-time pickers) and its **sortable result columns** (existence,
  order, header label, sortability all from the spec). `UserTable` and `Users.kt` name no individual field.
- **The wire and the shareable URL** carry the same keys — a field's own name for a text filter, the spec's
  range keys for a range, plus the sort — so `AdminApi.userSearchArgs` (the query) and the page's hash encoding
  are one spec-driven function, and a shared link reproduces the search.

The payoff: **adding a searchable/sortable attribute is a spec entry plus its backend accessors.** The filter
input, the sortable column, the wire serialization, and the shareable-URL round-trip all follow with no
further front-end edit. A rename or a mismatch is a **compile error on both sides**, not a runtime surprise —
the same property `ROLE` / `RoleLadder` / `USF` rely on. A test (`UserSearchTest`) pins that the kernel spec and
the backend registry agree (every spec field has an accessor; the filter kind matches how the accessor
filters), so the two halves of the description cannot drift.

### What is *not* automatic yet

Two per-field touches remain on the front end, both genuinely presentational and small:

- a **display accessor** (`UserTable.cellValue`) — how a column's value reads off an `AdminUser` (the
  front-end counterpart to the backend's row accessors), and
- a **column width**.

And the **exact** filter kind renders specifically as the client picker (its options are the caller's clients);
a second exact field would need that generalized.

## The next step: fully runtime SDUI

This is **compile-time** SDUI — the description is shared source, not a wire payload. The natural next step, if
a deployment ever needs to vary the search fields without shipping a new front-end bundle, is to **serve the
spec as JSON** (an endpoint, or part of a UI-config envelope — the `/schema/endpoints` catalog and `SchemaForm`
are the precedent) and have the console fetch and render from that payload at runtime.

Because `userSearchFieldSpecs` is already plain data, that is a matter of *serializing and rendering* it, not
redesigning it. The work it would add:

- serialize the specs (and enough per-field presentation — label, width — to render without compiled
  knowledge), and fetch them like any other UI-config;
- move the two remaining per-field front-end touches (the display accessor, the width) into the served
  description, and generalize the `exact` renderer to carry its own options; and
- decide the versioning/caching story for the served spec, as the fragment and catalog endpoints already do.

None of that is needed for a single first-party admin screen today, which is why the shared-spec form is where
this rests — it removes the duplication and proves the paradigm, and leaves the runtime version a
serialization step away rather than a rewrite.
