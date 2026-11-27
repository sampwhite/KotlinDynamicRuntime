package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT

/**
 * The traits every deployment has, in the reserved `kdr.core` namespace (issue #300).
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
     * about. A client may keep the names of one owner's forms apart, by marking the field `g-unique` in its own
     * alteration of the type (issue #1851), which a new client starts with; the platform's type does not, so a
     * client that wants names to repeat may let them.
     *
     * Bound to form documents **and workflow data**: it means the same thing on both, which is exactly why
     * `appliesTo` is a set and this is one trait rather than a `name` beside a `wfDataName` meaning the same
     * thing. The workflow binding arrived with workflow data itself — the "when there is workflow data to bind
     * it to" this comment used to promise. (The workflow design has since moved: see issue #532.)
     */
    trait(
        GT.nameEntry,
        GT.name,
        setOf(GedraDataType.formDoc, GedraDataType.wfData),
        "What somebody chose to call this document or workflow.",
    ) {
        // Its name wherever a trait is named to people (`traitNameOf`, issue #1438): with no title, it was its id
        // humanized, "Kdr:name".
        title = "Name"
        property(GT.nameField, "What to call it.", required = true) { maxLength = GT.nameMaxLength }
    }

    // Which of its client's ftypes a form is (issue #1902). In no group: to a client that defines no ftypes it
    // would be a choice with nothing to choose. The choices are the client's types, sourced for each caller; which a form
    // may be set to is held on every write by `FTypeHook`, since a sourced list takes no part in validation.
    trait(
        GT.ftypeEntry,
        GT.ftype,
        setOf(GedraDataType.formDoc),
        "Which kind of form this is, of the form types its client defines.",
        joinsGroups = false,
    ) {
        title = "Form type"
        property(GT.ftypeField, "The form's type.", required = true) {
            title = "Type"
            optionsSource(FTYPE.optionsSource)
        }
    }

    // The default forms-list presentation (issue #537): a "Name" column pulled from the `name` trait, applied
    // to any client that has not declared usage rules of its own. This is what preserves the name column every
    // deployment showed before presentation became client-configurable -- a client with its own rules
    // overrides it wholesale (see `GedraConfigCollector.usagesFor`). Searchable by exact name and, since a name
    // is the field a person most often half-remembers, by substring too (issue #538).
    traitUsage(GT.name, "Name", $$"${name}", substring = true)

    // The state→cfact bridge's convention (issue #599): a gedra's state may assert cfact names here, which
    // `GedraDataService.formCfacts` unions into `assemble`'s target facts. Global and form-singleton, since a
    // form's cfacts are one set about the form, not per-workflow; `derived`, since a batch may recompute them.
    stateTrait(
        GT.cfactsEntry,
        GT.cfacts,
        setOf(GedraDataType.formDoc, GedraDataType.wfData),
        StateTraitClass.derived,
        "The declared cfact names this gedra's stored state asserts, for eligibility expressions to gate on.",
    ) {
        property(GT.facts, "Declared cfact names asserted about this gedra.") {
            type = SCT.array
            items { type = SCT.string }
        }
    }
}
