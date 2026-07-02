---
name: kdr-core-utils
description: Use KotlinDynamicRuntime's own from-scratch JSON parser/formatter (JsonUtil), Instant-based date utilities (DateUtil), and value coercion helpers (ConvertUtil/StrUtil) instead of reaching for a third-party library. Use when parsing/formatting JSON, handling dates/timestamps, or coercing values in this codebase.
---

# Core utilities (JSON, dates, conversion)

All in `base/common`, package `com.dynamicruntime.common.util`. The house style
is to use these, not a library (see the code guide). They are pure-Kotlin and
KMP-friendly (except `DateUtil`, which is JVM-portable via `kotlinx-datetime`).

## JSON — `JsonUtil.kt`

A hand-written parser/formatter with precise error reporting.

```kotlin
val s   = anyValue.toJsonStr(compact = false, preserveNulls = false) // format
val map = """{"a":1}""".jsonMap()   // MutableMap<String,Any?>? (null if blank input)
val arr = "[1,2,3]".jsonArray()     // MutableList<Any?>?
val any = someJson.json()           // Any? (object or array)
```

- Numbers parse to **`Long`** (integers) or **`Double`** (decimals). Objects/arrays
  are `LinkedHashMap`/`ArrayList` (insertion order preserved).
- Parse errors throw **`KdrException`** whose `extraData` carries `offset` / `line` /
  `lineCol`. Trailing non-whitespace after the root value is rejected (opt out with
  `PState.forgiveTrailingContent`). Nesting is capped at 50 (both parse and format).
- Formatting: a `LinkedHashMap` keeps insertion order; other maps sort keys (scalars
  before containers). `Instant` values serialize as ISO strings (via `fmt()`).

## Dates — `DateUtil.kt`

The one date type is the stdlib **`kotlin.time.Instant`** (never `java.util.Date`).

```kotlin
val t: Instant = "2021-06-01T08:00:00.250Z".parseDate()  // also accepts date-only + missing Z
t.formatDate()        // "2021-06-01T08:00:00.250Z" (UTC, millis)
t.formatDayPart()     // "2021-06-01" in serverTimeZone
t.formatCookieDate()  // "Tue, 01 Jun 2021 08:00:00 GMT"
t.toStartOfDay(); t.addDays(1); t.addHours(-6); t.truncateToMs()
```

- `parseDate` throws `KdrException` on bad input; a 10-char `yyyy-MM-dd` is start-of-day
  in `serverTimeZone` (a hardcoded `UTC-08:00`).
- Clock reads carry sub-ms precision; the wire format is millisecond. `now()`/
  `creationDate` on `KdrCxt` keep full precision — use a "close enough" comparison
  (or `truncateToMs()`) if a format→parse round-trip needs exact equality in a test.

## Value conversion — `ConvertUtil.kt` / `StrUtil.kt`

```kotlin
fun <T> Any.toT(): T            // the ONE place unchecked casts live; use instead of `as`
Any.toJsonMap(): Map<String,Any?>
Any?.toOptStr(): String?        // toString only if CharSequence, else null
Any?.fmt(): String              // human-friendly: doubles/floats trimmed, Instant -> ISO, null -> "null"
Double.fmtD(); Float.fmtF()     // KMP-safe, thread-safe fixed-precision (NOT DecimalFormat)
String.toOptBool(): Boolean?    // loose: first non-ws char y/t/1 -> true, n/f/0 -> false, else/blank -> null
String.splitComma(): List<String>   // trims; blank -> emptyList
StringBuilder.appendLiteral(text) / String.encodeLiteral()  // JSON string escaping
```

Never write `as` / `@Suppress("UNCHECKED_CAST")` — route through `toT()`/`toJsonMap()`.

## Source

`util/JsonUtil.kt`, `util/DateUtil.kt`, `util/ConvertUtil.kt`, `util/StrUtil.kt`,
`util/CollectionUtil.kt` (`deepClone`); tests alongside each in `test/.../util/`.
