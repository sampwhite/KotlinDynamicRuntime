package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.DBGK
import com.dynamicruntime.common.app.DBGT
import com.dynamicruntime.common.util.splitComma
import com.dynamicruntime.common.util.toJsonListOfMaps

/**
 * One `_debug` word the node declares (issue #1334), as the app bar's debug box offers it: the word, what it does, and
 * which kind it is (`DBGK`).
 */
data class DebugTagChoice(val name: String, val description: String, val kind: String)

/** The app config's list of words, dropping an entry that names no word. */
fun debugTagChoicesFrom(raw: Any?): List<DebugTagChoice> = raw.toJsonListOfMaps().mapNotNull { entry ->
    val name = (entry[DBGT.name] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    DebugTagChoice(name, entry[DBGT.description] as? String ?: "", entry[DBGT.kind] as? String ?: DBGK.explain)
}

/** The words in the box's text, as the backend reads them: comma-separated, trimmed, blanks dropped. */
fun debugTagWords(text: String): List<String> = text.splitComma().filter { it.isNotEmpty() }

/**
 * The box's text with [word] appended after a comma, or the text's words unchanged when [word] is among them already.
 * The words are rejoined without spaces either way, as the backend would read them.
 */
fun withDebugTag(text: String, word: String): String {
    val words = debugTagWords(text)
    return (if (word in words) words else words + word).joinToString(",")
}

/** A word's kind, as the picker labels it: what the word does to the request. */
fun debugTagKindLabel(kind: String): String = when (kind) {
    DBGK.log -> "adds logging"
    DBGK.alter -> "changes the request"
    else -> "explains"
}
