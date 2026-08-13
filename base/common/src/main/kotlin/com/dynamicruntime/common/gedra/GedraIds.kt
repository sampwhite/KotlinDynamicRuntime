package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.intern.InternCache
import com.dynamicruntime.common.util.mkUniqueId

/**
 * Minting gedra ids (issue #287).
 *
 * Backend-only, and only because randomness is: `GedraId` itself, its parse and its text form live in the
 * kernel so a browser can read an id, but nothing in a browser creates a gedra.
 *
 * Every one of these builds the text form from parts it already holds and hands them to the id whole, so no
 * path here assembles a string only to parse it straight back.
 */

/**
 * A fresh id for a gedra about to be created: [context]'s letter followed by the project's standard
 * time-sortable unique id.
 *
 * Ids therefore sort by storage, then kind, then client, then origin, then time — which is useful clustering
 * for a primary key and a pleasant order to read a listing in.
 */
fun KdrCxt.mkGedraId(
    kind: GedraKind,
    client: String,
    context: GedraIdContext,
    suffix: String? = null,
): GedraId = GedraId.of(kind, client, context.letter + mkUniqueId(), suffix)

/**
 * The id of a user's own gedra, which is fixed by the user rather than chosen: the same user in the same
 * client always has the same id, with no lookup and nothing to store.
 */
fun mkUserGedraId(client: String, userId: Long): GedraId =
    GedraId.of(GedraDataType.userData, client, userIdPart(userId))

/**
 * The id of an object a user may have exactly one of — the shape behind "one workflow per user". [key] is the
 * object's own Java-variable-friendly name, and it needs no client prefix even where two clients use the same
 * name, because the id already carries the client.
 *
 * The construction is not parsed back out. It only has to be invariant given the same user and key, which is
 * what makes "does this user already have one?" answerable by building the id and looking it up.
 */
fun mkUserScopedGedraId(kind: GedraKind, client: String, userId: Long, key: String): GedraId =
    GedraId.of(kind, client, "${userIdPart(userId)}_$key")

/**
 * The id of a child created from a parent — a row of an imported spreadsheet, say — sharing the parent's base
 * id and adding [index] as the suffix.
 *
 * That shared base id is the point: a set of gedras that came in together is visible as such at a glance, and
 * the index often points back at the row that produced one. The child takes its own [kind] (an import is
 * usually a file reference producing form documents) but is always in the parent's client, which is not a
 * parameter precisely so that it cannot be given a different one.
 */
fun mkChildGedraId(kind: GedraKind, parent: GedraId, index: Int): GedraId =
    GedraId.of(kind, parent.client, parent.baseId, index.toString())

/**
 * The interned id for [fullId] — the shared instance, or null when no gedra has that id.
 *
 * For a cache holding every extant id, null is the existence answer, so this is a lookup and an existence
 * check in one hash of the string the caller already sent. Nothing is parsed on the way: the cache is keyed
 * by the text form, so a valid id for a gedra that exists costs one map lookup and no validation at all.
 *
 * A null answer does not distinguish "malformed" from "no such gedra" — `GedraId.parse` does that, and a
 * caller wanting to tell a 400 from a 404 parses only after this misses.
 */
fun InternCache<GedraId>.gedraId(fullId: String): GedraId? = get(fullId)

/**
 * [GedraId.parse], but yielding the interned instance whenever one exists.
 *
 * The cache is consulted first and never added to, so this stays honest about existence — an id for a gedra
 * that was never created parses fine and simply is not interned. Use it where a caller's string is about to
 * become an id that may or may not name something real.
 */
fun InternCache<GedraId>.readGedraId(fullId: String): GedraId = get(fullId) ?: GedraId.parse(fullId)

/** How a user's numeric id is spelled inside a base id. */
private fun userIdPart(userId: Long) = "u$userId"
