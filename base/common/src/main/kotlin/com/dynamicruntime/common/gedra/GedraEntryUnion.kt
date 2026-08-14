package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.schemaDefs

/** Names for the types one gedra kind gives rise to (issues #301, #310). */
object GU {
    /**
     * The type of a gedra of one kind: `formDoc` becomes `FormDoc`.
     *
     * The enum name capitalized, so the settled vocabulary reaches the schema unchanged and the pair reads as
     * the pair it is -- a `FormDoc` carries `FormDocEntry`s.
     */
    fun gedraName(kind: GedraDataType): String = kind.name.replaceFirstChar { it.uppercase() }

    /** The union type of the entries one kind may carry: `formDoc` becomes `FormDocEntry`. */
    fun unionName(kind: GedraDataType): String = gedraName(kind) + "Entry"

    /** The union's default branch, where a trait this reader has never heard of, goes. */
    fun unknownBranchName(kind: GedraDataType): String = unionName(kind) + "Unknown"

    /**
     * The union of *edits* to the entries one kind may carry: `formDoc` becomes `FormDocEntryEdit`.
     *
     * Derived from [unionName] by one rule rather than given a naming scheme of its own, so the pair reads as
     * a pair: `FormDocEntry` is what is stored, `FormDocEntryEdit` is what asks to change it.
     */
    fun editUnionName(kind: GedraDataType): String = unionName(kind) + "Edit"

    /** The edit union's default branch, for a trait this node does not know. */
    fun unknownEditBranchName(kind: GedraDataType): String = editUnionName(kind) + "Unknown"

    /**
     * One trait's edit branch, named from the entry type it edits: `globalconfig.NameEntry` becomes
     * `globalconfig.NameEntryEdit`.
     *
     * Qualified already, so a branch lands in the namespace of the config that declared the trait rather than
     * in the union's — exactly as the entry types do. A client's trait keeps its own namespace here too.
     */
    fun editBranchName(entryTypeName: String): String = entryTypeName + "Edit"
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
    // Sorted by trait id, so the same set of traits produces the same document, however, the components that
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
