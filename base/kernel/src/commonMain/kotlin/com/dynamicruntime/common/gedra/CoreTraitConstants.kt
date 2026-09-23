package com.dynamicruntime.common.gedra

/**
 * The core traits' own names, so nothing has to spell one twice (issue #300). In `base:kernel` since issue #789,
 * so the frontend's status chip reads a form's [cfacts] state by the same names the backend writes it under.
 */
@Suppress("ConstPropertyName")
object GT {
    /** The config bundle these traits are declared in: `gc.cd.global.coreTraits`. */
    const val coreTraits = "coreTraits"

    /**
     * The `name` trait, and the single field it carries. The two being the same word is not an accident worth
     * hiding: the trait is *about* the name, so its one field has nowhere better to be called.
     *
     * It is also the clearest argument for the entry envelope living one level up. Flat, this entry would be
     * `{"traitId": "name", "name": "..."}` — and the day the envelope wants a name of its own there is
     * nowhere to put it.
     */
    const val name = "name"

    /** The entry type the [name] trait generates: `globalconfig.NameEntry`. */
    const val nameEntry = "NameEntry"

    /**
     * How long a name may be. Long enough for anything somebody would type into a form's title, short enough
     * to index and to show in a listing without truncation being the normal case.
     */
    const val nameMaxLength = 128

    /**
     * The `cfacts` **state** trait (issue #599): the state→cfact bridge's convention. A gedra's stored state may
     * assert cfact names in this trait's [facts]; `GedraDataService.formCfacts` reads them and feeds them into
     * `CFactRegistry.assemble` as target facts, so a workflow-eligibility expression can gate on state. `derived`
     * -- a projection a batch may recompute -- and form-global (unkeyed): one set about the form, however many
     * produce it. Since issue #784 it is **merged from contributions** -- the survey's facts, and the framework
     * singletons (`needsReview`, `finished`) its engaged workflows emit -- folded into this one entry by the
     * recompute (`mergeCfactContributions`). Which workflow contributed what is kept on that workflow's own state.
     */
    const val cfacts = "cfacts"

    /** The entry type the [cfacts] state trait generates: `globalconfig.CFactsEntry`. */
    const val cfactsEntry = "CFactsEntry"

    /** Under a [cfacts] entry: the declared cfact names this gedra's state asserts. */
    const val facts = "facts"
}
