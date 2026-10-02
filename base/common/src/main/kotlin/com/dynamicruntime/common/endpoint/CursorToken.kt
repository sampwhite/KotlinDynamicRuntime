package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.ProblemCode
import com.dynamicruntime.common.util.base64Decode
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.crc32Hex
import com.dynamicruntime.common.util.jsonMapResult
import com.dynamicruntime.common.util.toJsonStr

/** Cursor paging's constants (issue #976): the token's format, and the wording of the two envelope fields. */
@Suppress("ConstPropertyName")
object CUR {
    /** The token format's version, its first part. A reader refuses a version it does not know. */
    const val version = "1"

    /** Separates the token's three parts. Not a base64url character, so it cannot occur inside one. */
    const val sep = "."

    /** The one key of the JSON object the key values travel in. */
    const val keyField = "k"

    /**
     * The longest token read. A real one is a few dozen characters -- a version, a hash, a sort key -- so anything
     * near this is not one, and is refused before it is decoded rather than after.
     */
    const val maxTokenLength = 2000

    /** What a cursor-paged listing's `after` input is, said once for validation and for the catalog. */
    const val afterDescription = "The `next` value of the previous page; absent for the first page."

    /** What a cursor-paged listing's `next` output is. */
    const val nextDescription = "Send as `after` to get the next page; absent when this page was the last."
}

/** What can be wrong with a cursor token (issue #976). */
@Suppress("EnumEntryName")
enum class CursorProblem : ProblemCode {
    /** Not a token at all: the wrong shape, or a key that does not decode. */
    malformed,

    /** A token of a format version this node does not read. */
    unsupportedVersion,

    /** A well-formed token that belongs to a different query -- another listing, or the same one asked differently. */
    otherQuery,
}

/**
 * The opaque `next` / `after` value of a cursor-paged listing (issue #976): `<version>.<queryHash>.<key>`.
 *
 * The **key** is the sort key of the last item of a page -- the values `cursorSlice` resumes after -- as base64url
 * JSON. So it comes back as JSON reads it, which is not always what went in: a string, a boolean and a null come back
 * as themselves, a whole number as a `Long` **whatever it was** (the `Double` `2.0` returns as `2L`), a fractional
 * one as a `Double`, and anything else -- a date, say -- as its text. Whoever reads a key back puts its types back:
 * that is what a [CursorKeyCodec]'s `fromValues` is for.
 *
 * The **query hash** ties a token to the query that produced it. A key means something only under one ordering of
 * one set: the same key replayed against a different listing, or the same listing grouped differently, would
 * resume at an arbitrary point and return a plausible page of the wrong walk. So a handler names what makes its
 * query the same query -- [encode]'s and [decode]'s `queryId` -- and a token presented to any other is refused,
 * rather than answered. Only a CRC of the id travels: it is a check against a caller's mistake, not a secret, and a
 * token grants nothing the caller could not ask for without one.
 *
 * Opaque to callers by contract, not by encryption: the format may change, which is what the version is for.
 */
object CursorToken {
    /** The token for a page ending on the item whose sort key is [key], in the query [queryId]. */
    fun encode(queryId: String, key: List<Any?>): String {
        val json = mapOf(CUR.keyField to key).toJsonStr(compact = true)
        return listOf(CUR.version, queryId.crc32Hex(), json.toByteArray(Charsets.UTF_8).base64Encode()).joinToString(CUR.sep)
    }

    /**
     * The key values [token] carries, when it is a token of the query [queryId]. Non-throwing: a caller that
     * reports takes the problem, and [keyOf] is the throwing form built on this.
     */
    fun decode(token: String, queryId: String): Parsed<List<Any?>> {
        if (token.length > CUR.maxTokenLength) return malformed()
        val parts = token.split(CUR.sep)
        if (parts.size != 3) return malformed()
        val (version, hash, encoded) = parts
        if (version != CUR.version) {
            return Parsed.failed(
                CursorProblem.unsupportedVersion,
                "The cursor is of a format this node does not read (version '$version'); start again without it.",
            )
        }
        if (hash != queryId.crc32Hex()) {
            return Parsed.failed(
                CursorProblem.otherQuery,
                "The cursor belongs to a different query; start again without it.",
            )
        }
        val json = try {
            String(encoded.base64Decode(), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return malformed()
        }
        val key = json.jsonMapResult().valueOrNull()?.get(CUR.keyField) as? List<*> ?: return malformed()
        return Parsed.Ok(key)
    }

    /** The one answer to text that is not a token of this listing, whichever way it fails to be one. */
    fun malformed(): Parsed.Failed =
        Parsed.failed(CursorProblem.malformed, "The cursor is not one this listing produced; start again without it.")

    /**
     * The key to resume after for a request's `after` value: null when there is none (the first page), the key
     * when [token] is this query's, and a **400** otherwise -- a cursor the caller sent that cannot be honored is
     * their input to fix, and answering with the first page instead would restart their walk without telling them.
     */
    fun keyOf(token: String?, queryId: String): List<Any?>? {
        if (token.isNullOrEmpty()) return null
        return decode(token, queryId).orThrow { KdrException.mkInput(it) }
    }
}

/**
 * How a listing's sort key travels in a cursor (issue #976): [toValues] turns a key into the JSON values the token
 * carries, and [fromValues] turns them back -- or answers null when they are not a key of this listing, which
 * [cursorPage] reports as a malformed cursor rather than reading as "no cursor". Restoring types belongs in
 * [fromValues], since JSON does not keep them all (see [CursorToken]).
 */
class CursorKeyCodec<K : Any>(val toValues: (K) -> List<Any?>, val fromValues: (List<Any?>) -> K?)

/** The codecs of the common key shapes. */
object CursorKeys {
    /** A single string key -- an id. */
    val string: CursorKeyCodec<String> = CursorKeyCodec({ listOf(it) }, { it.singleOrNull() as? String })
}

/**
 * One page of a cursor-paged listing, from its request (issue #976): the whole of what a `cursorPaged` handler does
 * about paging, so no endpoint writes the steps out and gets one subtly wrong.
 *
 * [sorted] is everything the query matches, in [cmp] order of [keyOf] -- a total order over a key that never changes
 * for an item (see `cursorSlice`). [queryId] is what makes this query the same query: the listing, and every input
 * that changes the set or its order. The request's `after` is read with it, so a cursor from another query is a 400,
 * as is one whose key [codec] does not recognize -- never the first page, which would restart a caller's walk
 * without telling them. The page's last item gives the `next`, under the same [queryId].
 *
 * [toItem] renders only the items of the page, so a listing that is cheap to order and costly to render -- ids in
 * hand, rows to load -- pays for one page.
 */
fun <T, K : Any> cursorPage(
    request: Map<String, Any?>,
    queryId: String,
    sorted: List<T>,
    keyOf: (T) -> K,
    cmp: Comparator<in K>,
    codec: CursorKeyCodec<K>,
    summary: Map<String, Any?>? = null,
    toItem: (T) -> Any?,
): ListPage {
    val values = CursorToken.keyOf(request[EP.after] as? String, queryId)
    val afterKey = values?.let { codec.fromValues(it) ?: CursorToken.malformed().orThrow { m -> KdrException.mkInput(m) } }
    val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
    val slice = cursorSlice(sorted, afterKey, limit, keyOf, cmp)
    val next = if (slice.hasMore) CursorToken.encode(queryId, codec.toValues(keyOf(slice.items.last()))) else null
    return ListPage.cursor(slice.items.map(toItem), slice.numAvailable, next, summary)
}
