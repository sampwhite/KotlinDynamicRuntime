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
 * JSON, so it carries strings, numbers and booleans as themselves. A date travels as whatever the handler turned it
 * into (its text or its epoch), since the handler is also who reads it back.
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
        val parts = token.split(CUR.sep)
        if (parts.size != 3) {
            return Parsed.failed(CursorProblem.malformed, "The cursor is not one this listing produced.")
        }
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
            return Parsed.failed(CursorProblem.malformed, "The cursor is not one this listing produced.")
        }
        val key = json.jsonMapResult().valueOrNull()?.get(CUR.keyField) as? List<*>
            ?: return Parsed.failed(CursorProblem.malformed, "The cursor is not one this listing produced.")
        return Parsed.Ok(key)
    }

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
