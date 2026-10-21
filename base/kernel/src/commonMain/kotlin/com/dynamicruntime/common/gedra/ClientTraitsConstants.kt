package com.dynamicruntime.common.gedra

/**
 * The names of the traits editor's reads and writes (issue #1117): which global traits a client includes, chosen on
 * its page. The endpoints are in `base:common` (`ClientTraitsEndpoints`); the names are here, in the kernel, so the
 * web app reads the catalog and builds the save by the names the backend uses. A trait's own fields are named by
 * [CCT], as everywhere else a trait is described.
 */
@Suppress("ConstPropertyName")
object CIT {
    // --- type names ---
    const val catalogTypeName = "ClientTraitCatalog"
    const val traitTypeName = "ClientTraitChoice"
    const val setResultTypeName = "ClientTraitsSetResult"

    // --- the catalog's fields (each matches its value) ---
    /** The global traits a client may include, each with what describes it ([traitTypeName]). */
    const val traits = "traits"

    /**
     * The schema reference of a trait's data -- its fields -- into the catalog's `$defs`: the global declaration,
     * whatever the client has made of it.
     */
    const val dataRef = "dataRef"

    /**
     * On a trait: whether the client has altered or extended its type, which makes it supported without being
     * named (`supportedTraits`). Such a trait is not the picker's to take away.
     */
    const val customized = "customized"

    /**
     * Why the client may not include a group such as `#allGlobal`, or absent when it may: a `customer` client in
     * `production` must have its supported set fully determined by its own definition.
     */
    const val groupsRefused = "groupsRefused"
}
