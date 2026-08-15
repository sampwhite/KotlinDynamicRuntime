package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.schema.schemaDefs

/**
 * Builds the **edit** union for one gedra kind — what a patch may ask of one entry (issue #337).
 *
 * A sibling of `entryUnionDefs`, not a projection of it. Both are manufactured from the same
 * `Collection<GedraTrait>`, so one source produces two renderings and neither is derived from the other. That
 * is why a trait carries its own data schema: the alternative was reading it back out of an entry type, which
 * would make this a derivation of a derivation, two hops from the thing that was actually authored.
 *
 * It takes a trait collection rather than reaching for the global one, for the same reason the entry union
 * does: per-client views are a later step, and they are then *calling this with a different set* rather than a
 * rewrite.
 *
 * ### Why an edit is a union at all
 *
 * So that `data` can be the trait's own shape. A discriminator selects a branch by a property **inside** the
 * object it validates, and in an edit the selector (`traitId`) is a sibling of `data` — one level up from it.
 * Pointing `data` at the *entry* union therefore cannot work, whatever it is spelled like. Making the edit
 * itself the union puts `traitId` where a discriminator needs it, and the form draws a real sub-form for
 * `data` instead of a JSON textarea.
 *
 * ### The default branch, again
 *
 * Present for the same reason, the entry union has one, and one more. Trait definitions are authored by people
 * who are not us, so meeting an unknown `traitId` is ordinary; and the general patch endpoint deliberately
 * knows only the global traits, so a client's own trait is *expected* to arrive here and carry its data as
 * plain JSON. Refusing it would make the general endpoint unusable for exactly the caller's client separation
 * exists to serve.
 */
fun entryEditUnionDefs(
    cxt: KdrCxtBase,
    namespace: String,
    kind: GedraDataType,
    traits: Collection<GedraTrait>,
): Map<String, Any?> {
    // Sorted by trait id, so the same set of traits produces the same document, however, the components that
    // contributed them happened to be ordered.
    val applicable = traits.filter { kind in it.appliesTo }.sortedBy { it.traitId }
    val unionName = GU.editUnionName(kind)
    val unknownName = GU.unknownEditBranchName(kind)
    return schemaDefs(cxt, namespace) {
        for (trait in applicable) {
            variantBranch(
                GU.editBranchName(trait.typeName),
                GE.traitId,
                trait.traitId,
                "An edit to a '${trait.traitId}' entry.",
            ) {
                editEnvelopeFields()
                editDataProperty(trait.dataSchema)
            }
        }
        variantDefault(
            unknownName,
            GE.traitId,
            "An edit to an entry whose trait this node does not know -- a client's own definition, or one " +
                "newer than this node. Its data is carried as supplied.",
        ) {
            editEnvelopeFields()
            editDataProperty(null)
        }
        variantType(
            unionName,
            "An edit to one entry of a ${kind.name} gedra, selected by its ${GE.traitId}.",
            on = GE.traitId,
            branches = applicable.map { GU.editBranchName(it.typeName) },
            defaultBranch = unknownName,
        )
    }
}
