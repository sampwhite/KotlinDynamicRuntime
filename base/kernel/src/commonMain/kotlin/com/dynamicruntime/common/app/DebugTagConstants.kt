package com.dynamicruntime.common.app

/**
 * The fields of one `_debug` word as the app config lists it under [APP.debugTags] (issue #1334). In the kernel so
 * the app bar reads exactly what the backend writes.
 */
@Suppress("ConstPropertyName")
object DBGT {
    /** The word, as it is sent in `_debug`. */
    const val name = "name"

    /** What it does, in a sentence. */
    const val description = "description"

    /** Which of the [DBGK] kinds it is. */
    const val kind = "kind"
}

/**
 * What a `_debug` word does (issue #1334), so a person choosing one can tell a word that only reports from one that
 * changes what the request does.
 */
@Suppress("ConstPropertyName")
object DBGK {
    /** Adds to the response: an explanation under `_meta`, or the request's log lines. */
    const val explain = "explain"

    /** Adds log lines the request would not otherwise write. */
    const val log = "log"

    /** Changes what the request does -- reading past a cache, say -- so its answer is not the ordinary one. */
    const val alter = "alter"

    val all: List<String> = listOf(explain, log, alter)
}
