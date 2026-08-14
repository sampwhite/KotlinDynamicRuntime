package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.schemaDefs

/** Names for the manufactured entry unions (issue #301). */
@Suppress("ConstPropertyName")
object GU {
    /** The union type for one kind: `formDoc` becomes `FormDocEntry`. */
    fun unionName(kind: GedraDataType): String = kind.name.replaceFirstChar { it.uppercase() } + "Entry"

    /** The union's default branch, where a trait this reader has never heard of goes. */
    fun unknownBranchName(kind: GedraDataType): String = unionName(kind) + "Unknown"
}

/**
 * Builds the entry union for one gedra kind, over the traits one client can see (issue #301).
 *
 * A **function of (client, kind)** rather than a one-off, and called once today with the global scope. Per
 * client types are a later step, and writing the assembly this way now is what keeps that from being a
 * rewrite: the only thing that changes is who calls it and with what.
 *
 * The union is manufactured rather than authored because its branches are not known until every component
 * has contributed. It is put into the collected `$defs` before they are compiled, so it is an ordinary type
 * by the time anything looks at it — nothing downstream can tell it was assembled.
 *
 * ### It always declares a default branch
 *
 * Trait definitions are authored at runtime by people who are not us, so **meeting a trait this reader has
 * never heard of is an ordinary event.** Client separation guarantees it: an administrative surface looking
 * across clients will meet traits belonging to a client whose config this deployment never loaded. Without a
 * default branch, one unrecognized entry takes down the whole payload it arrived in.
 *
 * The default branch is deliberately **open**. A closed catch-all rejects every unknown entry that carries
 * anything, which is all of them; and one that merely dropped the unknown fields would be worse, since the
 * entry would pass through emptied with nothing saying so.
 *
 * Whether a reader *uses* the default is a separate question, and a reader's own: see
 * `SchOpts.allowUnknownVariant`.
 */
fun entryUnionDefs(
    cxt: KdrCxtBase,
    namespace: String,
    kind: GedraDataType,
    traits: Collection<GedraTrait>,
): Map<String, Any?> {
    // Sorted by trait id, so the same set of traits produces the same document however the components that
    // contributed them happened to be ordered.
    val branches = traits.filter { kind in it.appliesTo }.sortedBy { it.traitId }.map { it.typeName }
    val unionName = GU.unionName(kind)
    val unknownName = GU.unknownBranchName(kind)
    return schemaDefs(cxt, namespace) {
        variantDefault(
            unknownName,
            GE.traitId,
            "An entry whose trait this reader does not know -- from a client whose definitions it never " +
                "loaded, or newer than this node.",
        ) {
            // Declared even though the branch is open, so the shape a reader can rely on is stated rather
            // than merely tolerated: an entry always has data, known trait or not.
            property(GE.data, "The entry's own data, in a shape this reader cannot describe.") {
                type = SCT.kObject
            }
        }
        variantType(
            unionName,
            "Any entry that may be carried by a ${kind.name} gedra, selected by its ${GE.traitId}.",
            on = GE.traitId,
            branches = branches,
            defaultBranch = unknownName,
        )
    }
}
