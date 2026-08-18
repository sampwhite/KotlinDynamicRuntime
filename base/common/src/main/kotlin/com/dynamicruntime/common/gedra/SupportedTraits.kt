package com.dynamicruntime.common.gedra

/**
 * The traits a client may **use** -- in forms, and in the entries it stores (issue #356).
 *
 * Three named concepts rather than two overloaded ones, per `client-definition.md`:
 *
 * | | |
 * |---|---|
 * | `GedraConfigCollector.traitsFor(client)` | what a client can **see** -- its own and global's, so `$ref`s resolve |
 * | [supportedTraits] | what a client may **use** in forms and data entry |
 * | [ClientDef.includedTraits] | what a client **wrote down** to get there |
 *
 * **Visible and supported are not the same set**, and the difference is the point: all of the global schema
 * stays visible, because `$ref`s have to resolve, while a trait a client does not mention is not supported by
 * it. Support is an opt-in allowlist rather than "global minus what you excluded" -- a client that mentions
 * nothing supports nothing, however much it can see.
 *
 * The computed set is the declared list with its groups expanded, **plus everything the client customized**:
 * a trait it altered, extended or defined is supported without a second mention. That is why the declared
 * attribute is called `includedTraits` and not `supportedTraits` -- an attribute claiming to list what a
 * client supports, which does not list all of it, is the kind of near-truth somebody eventually relies on.
 */
fun supportedTraits(
    configs: GedraConfigCollector,
    client: String,
    def: ClientDef?,
    /** Qualified type names this client overlaid; a trait whose entry type is among them was customized. */
    overlaidTypes: Set<String>,
): List<GedraTrait> {
    val visible = configs.traitsFor(client)
    // No definition means nothing has said what this client supports, so it is treated as supporting what it
    // can see. Only reachable for a client whose definition was dropped in a degraded production boot -- and
    // there, behaving as it did before clients existed is the safer of the two answers.
    val declared = def ?: return visible
    val included = LinkedHashMap<String, GedraTrait>()
    for (trait in visible) {
        val byId = trait.traitId in declared.includedTraitIds
        // `#allGlobal` is functional: membership computed here from what is global, never written down, so it
        // cannot fall out of step the way a hand-applied tag would.
        val byGroup = CLD.allGlobal in declared.includedGroups && trait !in configs.traitsOwnedBy(client)
        // Customized without a second mention: altering a trait's entry type is as clear a statement of intent
        // as naming it, and requiring both would make the list a place to forget.
        val customized = trait.typeName in overlaidTypes
        if (byId || byGroup || customized) {
            included[trait.traitId] = trait
        }
    }
    // Its own traits are supported by having been declared at all -- a client does not include itself.
    for (trait in configs.traitsOwnedBy(client)) {
        included[trait.traitId] = trait
    }
    return included.values.toList()
}
