package com.dynamicruntime.kdn

import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import java.io.File

/*
 * The JSON blocks of a demo's documentation, held to the demo's bundles (issues #1137, #1799): what `ConfDemoDocsTest`
 * and `GrantsDemoDocsTest` share. A block is marked on the line before its fence with what it shows:
 * `<!-- kdr-doc: bundle=<name> -->` for a bundle's whole `slots`, `bundle=<name> pointer=<JSON Pointer>` for a part, or
 * `illustration` for a shape that is not the demo's.
 *
 * Theme: #DOCDR.
 */

/**
 * What is wrong with [pages]' JSON blocks against the demo's bundles as [slotsOf] reads them, one line each, with the keys
 * [ignoring] left out of both sides.
 */
internal fun checkDocBlocks(pages: List<File>, slotsOf: (String) -> Any?, ignoring: Set<String> = emptySet()): List<String> {
    val problems = mutableListOf<String>()
    for (page in pages) {
        for (block in jsonBlocks(page)) {
            val where = "${page.name}, line ${block.line}"
            val target = block.target ?: run {
                problems.add("$where: a JSON block with no `<!-- kdr-doc: ... -->` marker on the line before it.")
                null
            } ?: continue
            if (target.illustration) continue
            val slots = slotsOf(target.bundle) ?: run {
                problems.add("$where: the demo writes no bundle '${target.bundle}'.")
                null
            } ?: continue
            val expected = valueAt(slots, target.pointer) ?: run {
                problems.add("$where: '${target.pointer}' names nothing in bundle '${target.bundle}'.")
                null
            } ?: continue
            val shown = runCatching { without(normalized(block.text), ignoring) }.getOrNull()
            if (shown == null || shown != without(normalized(expected.toJsonStr(compact = true)), ignoring)) {
                val at = if (target.pointer.isEmpty()) "" else " at ${target.pointer}"
                problems.add(
                    "$where: the block for bundle '${target.bundle}'$at does not match what the demo writes. " +
                        "It should read:\n```json\n${expected.toJsonStr()}\n```",
                )
            }
        }
    }
    return problems
}

/** What a JSON block shows, from its marker: a bundle and a pointer within its slots, or an illustration. */
private class DocTarget(val bundle: String, val pointer: String, val illustration: Boolean)

private class JsonBlock(val line: Int, val target: DocTarget?, val text: String)

private val markerRegex = Regex("""^\s*<!--\s*kdr-doc:\s*(.*?)\s*-->\s*$""")

/** Each ```json block of [page], with the marker on the line before it. */
private fun jsonBlocks(page: File): List<JsonBlock> {
    val lines = page.readLines()
    val out = mutableListOf<JsonBlock>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() == "```json") {
            val marker = lines.getOrNull(i - 1)?.let { markerRegex.find(it)?.groupValues?.get(1) }
            val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == "```" } ?: lines.size
            out.add(JsonBlock(i + 1, marker?.let { targetOf(it) }, lines.subList(i + 1, end).joinToString("\n")))
            i = end + 1
        } else {
            i++
        }
    }
    return out
}

/** A marker's `bundle=... pointer=...`, or `illustration`. */
private fun targetOf(marker: String): DocTarget? {
    if (marker == "illustration") return DocTarget("", "", illustration = true)
    val fields = marker.split(Regex("\\s+")).mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { it[0] to it[1] }
    val bundle = fields["bundle"] ?: return null
    return DocTarget(bundle, fields["pointer"].orEmpty(), illustration = false)
}

/** The value at JSON Pointer [pointer] within [root] (RFC 6901), or null when it names nothing. */
private fun valueAt(root: Any?, pointer: String): Any? {
    if (pointer.isEmpty()) return root
    var at = root
    for (raw in pointer.removePrefix("/").split('/')) {
        val key = raw.replace("~1", "/").replace("~0", "~")
        at = when (at) {
            is Map<*, *> -> if (at.containsKey(key)) at[key] else return null
            is List<*> -> key.toIntOrNull()?.let { at.getOrNull(it) } ?: return null
            else -> return null
        }
    }
    return at
}

/** [value] with every key in [keys] left out, at any depth. */
private fun without(value: Any?, keys: Set<String>): Any? = if (keys.isEmpty()) value else when (value) {
    is Map<*, *> -> value.filterKeys { it !in keys }.mapValues { without(it.value, keys) }
    is List<*> -> value.map { without(it, keys) }
    else -> value
}

/** [json] parsed, so two texts of one value compare equal however they are laid out. */
private fun normalized(json: String): Any? = "{\"v\": $json}".jsonMap()?.get("v")
