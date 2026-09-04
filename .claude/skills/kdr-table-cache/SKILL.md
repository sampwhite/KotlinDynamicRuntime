---
name: kdr-table-cache
description: Hold a whole database table in memory with KotlinDynamicRuntime's table-cache subsystem (common/sql/cache) — declaring a cache with unique and non-unique indexes, registering it, reading it cache-first with a SQL fallback, replaying its change stream with a cursor (whole-cache or scoped to one index key), and the cross-node coherence that keeps it current. Also covers which tables must NOT be cached. Use when adding a cache, reading through one, consuming its changes, or diagnosing a stale read.
---

# Caching a table in memory (common/sql/cache)

A table small enough to fit in memory can be held there and kept current *incrementally*. `AuthUsers` is the
cache in place today (`user/AuthUserCache.kt`), which is why every gated request no longer re-queries the
acting user's row.

## First: should this table be cached at all?

Three questions, in order. A "no" to any of them means do not cache it.

1. **Is it bounded and small?** A cache holds every row on every node. A load over `TCH.largeLoadWarning`
   (50k) logs a warning; that is the subsystem telling you the answer, not a threshold to tune.
2. **Are its reads *scoped*?** A `ReadScope`-filtered table can still be cached — `gedraData` is — but the
   scope decides *how*, and this is the part to get right. Two rules keep the cache from ever widening an
   answer:
   - **A by-id read may check scope per row**, against the one row it found, exactly as
     `UserService.queryAdministrableUser` does. `gedraData`'s `admitsRow` is that check, shared by the lookup
     and the listing so the two cannot come to disagree about what a scope admits.
   - **A listing must never compose a scope predicate in memory** — that would be a second implementation of
     what `SqlScopeUtil` exists to be the only copy of. It may only be served from an index that is *already*
     the scope, so the "filter" is an index-key lookup, not a predicate. `gedraData`'s listing is served from
     the `clientKind` index and **only when the scope names a client**; a scope the index cannot key on (an
     `allClients` admin over every client, an ordinary user whose scope is client-less) falls back to SQL.
   `AuthUsers` shows **both halves** (issue #411): its *identity* reads stay one row by a unique key,
   deliberately unscoped — a login has to find a user in whatever client they belong to — while its admin
   *search* is a scoped listing, served from a non-unique `client` index when the scope names a client and
   falling back to SQL otherwise, with `ReadScope.admitsUserRow` (shared with `queryAdministrableUser`, the
   counterpart to gedra's `admitsRow`) as the per-row check. So neither rule above is gedra-only.
3. **Do its writers round-trip `updatedAt`?** The reload walks `updatedAt` forward and **skips a row stamped
   at or before the version it already holds**. `SqlTopicUtil.prepDates` guarantees the advance only when the
   write carries the prior `updatedAt` through — which a read-modify-write does, and a write assembled from a
   fresh map does not.

The order that a cached listing returns must **equal the SQL `order by` exactly**, including the tiebreak that
makes it total — `gedraData` sorts `updatedAt desc, gedraId desc` (most recently written first, issue #562), the
id breaking a same-millisecond tie so
the cache and SQL never page the same rows differently. And the `limit` is applied **after** the scope filter,
not before (the SQL carries no `LIMIT` — it filters, orders, then `take(limit)`), so the cache must filter and
sort the whole scoped set before capping, or a caller gets fewer of their own rows than they should.

## Declaring a cache

`SqlCacheParams` says which table, how to turn a stored row into your payload, and what to index.

```kotlin
val params = SqlCacheParams(
    topic = authTopic,
    tableName = UT.authUsers,
    extract = { _, data -> data },
    indexes = listOf(
        SqlCacheIndex(AU.username, unique = true) { it[AU.username].toOptStr() },
        SqlCacheIndex(AU.primaryId, unique = true) { it[AU.primaryId].toOptStr() },
        // A non-unique index groups every row sharing a key -- here the client, so the admin search can pull
        // one client's rows (issue #411). `keyOf` returning null leaves a row out of just this index.
        SqlCacheIndex(PF.client, unique = false) { it[PF.client].toOptStr() },
    ),
)
```

- **`extract`** turns the stored row into the payload; returning null **skips** the row. Keep as much or as
  little as you want — the raw map is not retained, so what you drop is dropped.
- **`indexes`** are computed in memory from the payload. `unique = true` maps a key to one row; otherwise a
  key maps to every row sharing it. `keyOf` returning null leaves the row out of *that* index only.
- **The payload implements nothing.** The id comes from the table's declared primary key, and
  `enabled`/`updatedAt` are protocol columns every table has.

**Cache the raw row map when consumers need a mutable object.** `AuthUserCache` does: `AuthUserRow` is
mutable and callers edit one and write it back, so a shared instance would be everyone's edit. Extracting per
read *is* the defensive copy. It also keeps full fidelity — `AuthUserRow.extract` scrubs the password out of
the row it hands out, so a cache of extracted rows would lose `encodedPassword` and break password login.

## Registering it

In the owning service's `checkInit`, so the cache service's `checkReady` performs the first load at startup
rather than in a request:

```kotlin
override fun checkInit(cxt: KdrCxt) {
    userCache = SqlTableCacheService.registerCache(cxt, AuthUserCache.params())
}
```

`registerCache` returns null when no cache service is running; every read below then misses and falls back to
SQL, so absence costs queries and nothing else.

## Reading it

Refresh, then read the snapshot. There is deliberately no combined `get(cxt, id)` wrapper.

```kotlin
cache.checkRefresh(cxt)
val snapshot = cache.snapshot          // one read: several lookups then share one consistent view
snapshot.get(cache.idOf(userId))       // by primary key
snapshot.byIndex(AU.username, name)    // a unique index -> one row or null
snapshot.allByIndex("client", "acme")  // a non-unique index -> every row under that key
```

- `idOf(vararg)` renders primary-key values the way `SqlCacheRow.id` does. Pass them in declared key order.
- A **misspelled index name throws** rather than returning null, because "no such row" is the answer a lookup
  is least able to question.
- **Disabled rows are absent from every lookup.** The first load takes enabled rows only, and a row disabled
  later becomes a tombstone.

### Always fall back to SQL on a miss

This is what makes a cache *indistinguishable* from the queries it replaces rather than merely similar:

```kotlin
fun queryByUsername(cxt: KdrCxt, username: String): AuthUserRow? =
    cachedUser(cxt) { it.snapshot.byIndex(AU.username, username) } ?: queryOne(cxt, AU.username, username)
```

A disabled user is the routine miss — `queryOne` is documented to return disabled rows, and the cache does not
hold them, so the fallback is what preserves that.

## Consuming changes: cursors

A lookup wants current state; a consumer maintaining its own derived structure wants *changes*, exactly once.

```kotlin
val cursor = SqlCacheCursor(cache)                            // the whole cache
val scoped = SqlCacheCursor(cache, "client", "acme")          // one key of a non-unique index

for (row in cursor.nextChanges(cxt)) {
    if (row.enabled) derived[row.id] = row.value else derived.remove(row.id)
}
```

- Changes include **tombstones** — a removed row arrives with `enabled = false`, because to a cursor an
  absence is invisible.
- A **key-scoped** cursor also reports a **departure**: a row whose key changes arrives under its old key as a
  disabled copy. That is why this is not the same as filtering the whole stream yourself — a filter would just
  stop matching the row and the consumer would hold it forever.
- Giving an index name without a key (or the reverse) **throws**; the failure mode would be silently widening
  to the whole table.
- `nextChanges` advances the position as it hands the rows over, so a consumer that throws part-way will not
  see them again. A cursor is **not thread safe**; the cache behind it is.

## How it stays current

Mostly you do not have to think about this. It matters when diagnosing a stale read.

1. **This node wrote it** — immediate. `SqlDatabase.publishWrite` tells the registered `SqlWriteListener`s
   which tables a statement touched (from its `t:` markers), the cache service marks the cache, and the next
   read reloads. **Automatic: a new write path announces itself by existing.**
2. **Another node wrote it** — its request end wrote the date into the shared `KdrCacheState` row; this node
   reads that row on a node-global throttle (`TCH.stateReadThrottleMs`, 250ms). *This* is what sets cross-node
   promptness.
3. **Nobody announced it** — a migration or a DBA. The `minRecheckMs` floor (30s, `KDR_TABLE_CACHE_MIN_RECHECK_MS`)
   is the backstop, not the promise.

Reads never run inside an open transaction: a reload there would see uncommitted rows and strand them in a
snapshot every thread shares. The write's reload stays pending until the transaction ends.

Outside a request (a script, a background job), wrap work in `SqlTableCacheService.withMonitoring(cxt) { ... }`
so other nodes hear about it at once rather than at the floor.

## Diagnosing

`GET /operator/cache/state` reports this node's caches *against* the shared row — the row alone cannot say
whether this node has caught up, which is where a stale read comes from. Look for `isLoaded`, a missing
`queryFromDate` (never completed a load), a stuck `pendingReload`, and `sharedState` disagreeing with
`lastSeen`.

### Is it current *right now*?

The endpoint cannot tell you that, because the `operator` gate resolves the caller's roles through the user
cache and so refreshes every cache before the handler runs. In process — a test, a script, an
edit-and-check loop — ask the service instead:

```kotlin
val state = SqlTableCacheService.get(cxt).refreshState(cxt)  // throws if no cache service is running
state.need           // current | neverRefreshed | changed | reloadPending | aged | disabled
state.isRefreshed    // the next cached read sweeps nothing
state.needsRefresh   // it will sweep
state.pendingTables  // written on this node, not reloaded yet
```

- **Asking does not refresh.** It reads the memo `getAndRefresh` keeps in `cxt.locals` plus the caches'
  pending flags — no query, no snapshot moves — which is what makes it safe to ask mid-check.
- `refreshNeed(cxt)` is the same decision without the surrounding detail, and is what `getAndRefresh` itself
  calls — so a check and the read after it cannot disagree.
- `needsRefresh` is **not** `!isRefreshed`: with caching disabled neither holds, and a loop waiting on
  `!isRefreshed` would wait forever.
- To act on the answer, `service.checkRefresh(cxt)` sweeps unconditionally, memo or no memo.
- `changed` after your own write is the expected reading; `reloadPending` on its own means a reload could not
  run, which in practice means a transaction was open.

`KDR_TABLE_CACHE_DISABLED=true` turns every cache off; each lookup then takes the SQL query it was replacing.
That is the first thing to try when a cache is suspected, because it isolates the question.

## Gotchas

- **Never hand out the cached object** if the payload is mutable. Extract per read.
- **A row moving between index keys** is only visible to key-scoped cursors, via the departure tombstone.
- **Tombstones accumulate**, in `ordered` and in each index's stream. Bounded by how many rows are disabled or
  move keys, not by table size.
- **An unreadable row is skipped and logged**, not fatal — one hand-edited row must not fail every reload.
- **Comparing a cached date against `instanceNow()`** needs `Instant.truncateToMs()`: the cached value has
  been through a database timestamp and carries millisecond precision, while the clock does not.
