package com.dynamicruntime.common.util

import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The generic short name of the IANA zone [zoneId], as a moment is labeled with it (issue #1321): "CT" for
 * `America/Chicago`, the same all year, where "CST" and "CDT" each hold for half of it. From Java's CLDR names, so the
 * backend is the one source of the wording and a mail and a table cell label alike; the kernel has no names, and
 * browsers word some zones differently.
 *
 * Null for a zone Java names only by an offset ("GMT-03:00" for `America/Sao_Paulo`), and for one whose name reads as
 * an offset although it moves: Java calls `Europe/London` "GMT" all year, which a July moment, an hour ahead of GMT,
 * would contradict. A moment in such a zone is labeled by its offset at that moment instead (`offsetLabel`) -- UTC's
 * included, so it reads "GMT", as the webapp has always shown it. Null too for a name that is not a zone. Kept once
 * worked out: a listing asks per row.
 */
fun genericZoneLabel(zoneId: String): String? = zoneLabels.getOrPut(zoneId) { Label(labelOf(zoneId)) }.text

/** A zone's worked-out label, null included, since a `ConcurrentHashMap` holds no null values. */
private class Label(val text: String?)

private val zoneLabels = ConcurrentHashMap<String, Label>()

private fun labelOf(zoneId: String): String? {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
    val name = zone.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.US)
    return name.takeUnless { it.startsWith("GMT") || it.startsWith("UTC") || it == zoneId }
}
