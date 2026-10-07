package com.dynamicruntime.common.schema

import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.overlay.MergeRule
import com.dynamicruntime.common.overlay.MergeSpec
import com.dynamicruntime.common.overlay.mergeOverlay
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/*
 * Extensions (issue #990): a named type declared as **another type plus a delta** -- `"g-extends": "kdr.B"` at the top
 * of a `$defs` entry -- instead of written out in full, or composed by a `$ref` property. It is resolved before the
 * document is parsed, by the declared merger of #985, so what is parsed, served and exported is one resolved type:
 * the directive never reaches `SchType` or a reader of the schema.
 *
 * An extension has a **name of its own**, so nothing that refers to the base sees it, and it may widen: it is checked
 * as an ordinary new type once resolved, never against the narrowing rules an alteration keeps. That is the
 * difference from an alteration (a type declared under the base's own name), which keeps the name and may only narrow.
 */

/**
 * How an extension's body merges onto its base (issue #990): the alteration's spec ([schemaTypeMergeSpec]) with the
 * opposite default for [SCH.properties] -- an extension usually adds, so it **merges** by default: a named property
 * is added or replaces the base's whole, `{}` keeps it, `null` removes it, and an unnamed one is the base's, including
 * any the base gains later. `"g-merge": { "properties": "restate" }` makes the extension state its whole set instead.
 * The layout merges by field, as an alteration's does; every other key replaces.
 */
val extensionMergeSpec: MergeSpec = MergeSpec(
    rules = mapOf(
        SCH.properties to MergeRule.Merge,
        SCH.layout to MergeRule.Resource { base, overlay -> layoutMergeSpec(base, overlay) },
    ),
    directiveKey = SCH.merge,
    choices = mapOf(SCH.properties to mapOf(MCH.restate to MergeRule.Restate, MCH.merge to MergeRule.Merge)),
)

/** A document with its extensions resolved ([resolveExtensions]): the [defs], and the extensions [refused], by name. */
class ExtensionsResolved(val defs: Map<String, Any?>, val refused: Map<String, Problem>)

/**
 * [defs] with every top-level type that carries [SCH.extends] resolved onto its base (issue #990), and the ones that
 * could not be -- each left out of the result, with why. The base is read from [defs] as it stands, so on a client's
 * composed document an extension extends the base **as the client has it**, after its alterations. Returns [defs]
 * itself (by identity) when nothing in it extends anything.
 *
 * Refused: a base that is not named as text, or that [defs] does not hold; a base that is itself an extension (no
 * chains, so no cycles); a base that is not an object type; and a merge the spec does not offer. Whether a refusal
 * fails the boot or drops the extension and reports it is the caller's to decide, as for any other definition.
 */
fun resolveExtensions(defs: Map<String, Any?>): ExtensionsResolved {
    if (defs.values.none { it is Map<*, *> && it.containsKey(SCH.extends) }) return ExtensionsResolved(defs, emptyMap())
    val out = LinkedHashMap(defs)
    val refused = LinkedHashMap<String, Problem>()
    for ((name, raw) in defs) {
        if (raw !is Map<*, *> || !raw.containsKey(SCH.extends)) continue
        val body = raw.toJsonMapOrEmpty()
        val problem = extensionProblem(name, body, defs)
        if (problem != null) {
            out.remove(name)
            refused[name] = problem
            continue
        }
        val baseName = body[SCH.extends] as String
        val outcome = mergeOverlay(extensionMergeSpec, defs[baseName].toJsonMapOrEmpty(), body - SCH.extends, name)
        if (outcome.problems.isNotEmpty()) {
            out.remove(name)
            refused[name] = extendsProblem(
                "Type '$name' extends '$baseName' with a merge it cannot apply: " + outcome.problems.joinToString(" ") { it.message },
            )
            continue
        }
        out[name] = withoutStrandedLayoutEntries(outcome.value, body)
    }
    return ExtensionsResolved(out, refused)
}

/** Why [name]'s extension [body] cannot be resolved against [defs], or null when it can. */
private fun extensionProblem(name: String, body: Map<String, Any?>, defs: Map<String, Any?>): Problem? {
    val baseName = body[SCH.extends] as? String
        ?: return extendsProblem("Type '$name' sets '${SCH.extends}' to ${body[SCH.extends]}; it names the base type, as text.")
    val base = defs[baseName] as? Map<*, *>
        ?: return extendsProblem("Type '$name' extends '$baseName', which is not a type here.")
    if (base.containsKey(SCH.extends)) {
        return extendsProblem(
            "Type '$name' extends '$baseName', which itself extends '${base[SCH.extends]}'. An extension's base is a " +
                "type declared in full; extend '${base[SCH.extends]}' directly.",
        )
    }
    if (base[SCH.type].toOptStr() != SCT.kObject || base.containsKey(SCH.oneOf) || base.containsKey(SCH.dRef)) {
        return extendsProblem("Type '$name' extends '$baseName', which is not an object type; only an object type can be extended.")
    }
    return null
}

private fun extendsProblem(message: String): Problem = Problem(SchemaError.badExtends, message)
