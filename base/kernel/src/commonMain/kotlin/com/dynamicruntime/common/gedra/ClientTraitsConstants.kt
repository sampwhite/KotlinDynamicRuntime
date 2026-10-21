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

    // --- the client's own traits (issue #1118) ---
    const val ownTraitTypeName = "ClientOwnTrait"
    const val designTypeName = "ClientTraitDesign"
    const val designResultTypeName = "ClientTraitDesignResult"
    const val quotasTypeName = "ClientTraitQuotas"

    /** On the catalog: the client's own traits, each with its design or why the editor cannot edit it. */
    const val ownTraits = "ownTraits"

    /** On an own trait, and a design's result: the stored configuration that holds the trait. */
    const val configName = "configName"

    /** On an own trait: the trait as the editor designs it ([TraitDesign.toInfo]); absent when it cannot. */
    const val design = "design"

    /** On an own trait: why the editor cannot edit it -- a field not of its kinds; absent when it can. */
    const val notEditable = "notEditable"

    /**
     * On the catalog: the plan's limits the editor shows and holds a design to (issue #1109) -- how many schemas the
     * client declares and may, and how many attributes one may have. Absent for a client on no plan.
     */
    const val quotas = "quotas"
    const val plan = "plan"
    const val schemasUsed = "schemasUsed"
    const val schemasLimit = "schemasLimit"
    const val attributesLimit = "attributesLimit"

    // --- a design ([TraitDesign]), each matching its value ---
    const val traitId = "traitId"
    const val label = "label"
    const val description = "description"
    const val fields = "fields"
    const val key = "key"
    const val kind = "kind"
    const val required = "required"
    const val choices = "choices"
    const val value = "value"

    /**
     * The prefix of the stored configuration the editor makes for a new trait: one configuration per trait, named for
     * it (`trait_<traitId>`), so each is published, judged and, later, carried on its own.
     */
    const val configPrefix = "trait_"

    // --- extending and narrowing a global trait (issue #1119) ---
    const val narrowingTypeName = "ClientTraitNarrowing"
    const val narrowResultTypeName = "ClientTraitNarrowResult"

    /** On a design: the global trait it extends; absent for a trait of its own. */
    const val extendsTrait = "extendsTrait"

    /** On a narrowing ([TraitNarrowing]): the fields the client's forms leave out. */
    const val hidden = "hidden"

    /** On a global trait in the catalog: the client's narrowing of it ([TraitNarrowing.toInfo]); absent when it cannot be edited. */
    const val narrowing = "narrowing"

    /** On a global trait in the catalog: the stored configuration holding the client's narrowing of it, when one does. */
    const val narrowingConfig = "narrowingConfig"

    /** On a global trait in the catalog: why the editor cannot edit the client's alteration of it; absent when it can. */
    const val narrowingNotEditable = "narrowingNotEditable"

    /** On the catalog: the global layouts of the types in its `$defs`, by type name, so a preview is labeled. */
    const val layouts = "layouts"

    /**
     * On a draft save's result: the impact report of publishing it (issue #935), run against the live client --
     * information only, since publishing asks again. Absent when the save is live, or publishing would affect nothing.
     */
    const val draftImpact = "draftImpact"

    /**
     * The prefix of the stored configuration the editor makes for a narrowing of a global trait: one per trait, named
     * for it (`narrow_<traitId>`, a rooted id's colon an underscore).
     */
    const val narrowPrefix = "narrow_"
}
