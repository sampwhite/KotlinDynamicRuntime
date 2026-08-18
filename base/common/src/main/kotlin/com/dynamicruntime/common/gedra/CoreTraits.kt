package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt

/** The core traits' own names, so nothing has to spell one twice (issue #300). */
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
}

/**
 * The traits every deployment has, in the reserved `globalconfig` namespace (issue #300).
 *
 * Declared by `base/common`'s component rather than by a sample or a fixture, because these are part of what
 * the runtime *is* — and because anything a test needs to reach has to come from a component that always
 * loads.
 *
 * One trait so far. It is expected to grow, and the bundle is the right size for that: granularity is an
 * authoring choice rather than an architectural one, since what assembles definitions together is the client
 * in their ids and not the bundle they were written in.
 */
fun coreTraits(cxt: KdrCxt): GedraConfig = gedraConfig(cxt, GT.coreTraits, GCFG.globalNamespace) {
    /*
     * `name` is first because it turned out to matter more than anything expected of it.
     *
     * In the prior system it was tracked aggressively in the history tables, and third-party integrations
     * bound to it — which is what makes a name not merely a label but something other systems have opinions
     * about. Uniqueness guarantees within the scope of a user are wanted eventually and are not here: this is
     * the trait and its shape, and a constraint that spans stored rows needs somewhere to be enforced that
     * does not exist yet.
     *
     * Bound to form documents **and workflow data**: it means the same thing on both, which is exactly why
     * `appliesTo` is a set and this is one trait rather than a `name` beside a `wfDataName` meaning the same
     * thing. The workflow binding arrived with workflow data itself (`gedra-workflow.md`) — the "when there is
     * workflow data to bind it to" this comment used to promise.
     */
    trait(
        GT.nameEntry,
        GT.name,
        setOf(GedraDataType.formDoc, GedraDataType.wfData),
        "What somebody chose to call this document or workflow.",
    ) {
        property(GT.name, "What to call it.", required = true) { maxLength = GT.nameMaxLength }
    }
}
