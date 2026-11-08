package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.overlay.DiffOutcome
import com.dynamicruntime.common.overlay.KeyedElement
import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.overlay.MergeOutcome
import com.dynamicruntime.common.overlay.MergeRule
import com.dynamicruntime.common.overlay.MergeSpec
import com.dynamicruntime.common.overlay.OnNew
import com.dynamicruntime.common.overlay.diffOverlay
import com.dynamicruntime.common.overlay.mergeOverlay
import com.dynamicruntime.common.overlay.restatedLists
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.layoutMergeSpec
import com.dynamicruntime.common.schema.schemaTypeMergeSpec
import com.dynamicruntime.common.schema.withoutStrandedLayoutEntries
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/*
 * Overlays of a client's code (issue #1657, step 3 of the overlay design).
 *
 * A stored entry marked `"overrides": "codeOverlay"` ([CCT.overridesCodeOverlayValue]) holds only what it changes of the
 * code layer's entry of the same key: the client's template's copies, its own code, a sandbox's code. Every read merges
 * it over the code's entry as the code is now ([mergeCodeOverlay]), so a later change to the code reaches every part of
 * the entry the client did not change. The merged entry then stands over the code's, whole, as a step 2 override
 * (`"overrides": "code"`) does, so nothing past the read knows the difference.
 *
 * Only the kinds with merge rules take one ([codeOverlaySlots]): workflows ([codeWorkflowMergeSpec]) and schema types
 * ([codeTypeMergeSpec]). A client's definition takes a narrow one with no merge, which says only whether the client has
 * a sandbox ([clientDefOverlayKeys], issue #1677). The rules are **open**: a client's own code makes no promise to
 * anyone else, so an overlay of it may change any part, unlike a workflow made from a global template, which keeps its
 * template's structure (`templateOverlayProblems`). What no rule can state is the edge: a task removed, inserted, or
 * moved has no place in an overlay -- there are no tombstones, by the rule that a client disables what it does not want
 * rather than deleting it (#1635) -- and an editor offers to detach the entry from the code instead, storing it whole.
 *
 * Theme: #CFGFG.
 */

/** The slots whose entries may be stored as an overlay of the code's (issue #1657): workflows and schema types. */
val codeOverlaySlots: Set<String> = setOf(CCT.workflowDef, CCT.schemaDef)

/**
 * The keys an overlay of a client's definition in code may hold (issue #1677): the client it changes, its mark, and its
 * `sandbox` flag -- what the Clients page's sandbox switch stores for a client defined in code, and nothing else for
 * now. It is flat, as a stored client definition is, and is not merged by a spec: the flag turns the sandbox on when the
 * code leaves it off, and never off when the code sets it.
 */
val clientDefOverlayKeys: Set<String> = setOf(CLD.clientId, CCT.overrides, CLD.sandbox)

/** What a refusal to state a change of a code entry's keyed list adds: the way out. */
private const val detachHint =
    "An overlay of the code keeps the code's entries here, in the code's order; detach the entry from the code to change them."

/** How a task of an overlay of a code workflow merges (issue #1657): its traits restated, its saves by id. */
val codeWorkflowTaskMergeSpec: MergeSpec = MergeSpec(
    rules = mapOf(
        WFD.traits to MergeRule.RestateKeyed(WFD.traitId),
        WFD.saves to MergeRule.Keyed(WFD.id, KeyedElement.merge, OnNew.refuse, refusalHint = detachHint),
    ),
    directiveKey = WFD.merge,
    choices = mapOf(
        WFD.traits to mapOf(
            MCH.restate to MergeRule.RestateKeyed(WFD.traitId),
            MCH.merge to MergeRule.Keyed(WFD.traitId, KeyedElement.merge, OnNew.append),
        ),
    ),
)

/**
 * How an overlay of a code workflow merges over it (issue #1657). Its tasks are targeted by id, each a resource of its own
 * ([codeWorkflowTaskMergeSpec]), and keep the code's order: an overlay can neither add one nor take one away. Its
 * eligibility tests, singleton rules, and locks are keyed lists whose order means nothing, so an overlay may add to them.
 * Its type alterations merge as a schema type's alteration does. Its id and kind are the code's ([MergeRule.Fixed]),
 * since what it stands over is told by them. Anything else replaces, so an overlay that mentions a list with no keys (a
 * task's functions) restates it ([restatedCodeLists]).
 */
val codeWorkflowMergeSpec: MergeSpec = MergeSpec(
    rules = mapOf(
        WFD.workflowId to MergeRule.Fixed,
        WFD.entry to MergeRule.Fixed,
        WFD.tasks to MergeRule.Keyed(
            WFD.id,
            KeyedElement.merge,
            OnNew.refuse,
            refusalHint = detachHint,
            elementSpec = { _, _ -> codeWorkflowTaskMergeSpec },
        ),
        WFD.eligibility to MergeRule.Keyed(WFD.id, KeyedElement.merge, OnNew.append),
        WFD.singletons to MergeRule.Keyed(WFD.cfact, KeyedElement.merge, OnNew.append),
        WFD.locks to MergeRule.Keyed(WFD.traitId, KeyedElement.merge, OnNew.append),
        WFD.types to MergeRule.Resources { _, _ -> schemaTypeMergeSpec },
        WFD.typeBasis to MergeRule.Resources { _, _ -> MergeSpec(emptyMap()) },
    ),
)

/**
 * How an overlay of a code schema type merges over it (issue #1657). Unlike a client's alteration of a type, which states
 * the whole set of properties it offers (`schemaTypeMergeSpec`), an overlay of the client's own code merges them: it names
 * only the properties it changes, each named body replacing the code's whole and `null` removing it, so a property the
 * code adds later reaches the client. The field layout merges by field, as an alteration's does. Anything else replaces.
 */
val codeTypeMergeSpec: MergeSpec = MergeSpec(
    rules = mapOf(
        SCH.properties to MergeRule.Merge,
        SCH.layout to MergeRule.Resource { base, overlay -> layoutMergeSpec(base, overlay) },
    ),
)

/** The spec an overlay of [slot] merges by, or null for a slot whose entries are only ever overridden whole. */
fun codeOverlaySpec(slot: String): MergeSpec? = when (slot) {
    CCT.workflowDef -> codeWorkflowMergeSpec
    CCT.schemaDef -> codeTypeMergeSpec
    else -> null
}

/**
 * The definition [overlay], an overlay of [slot], makes of [code], the code entry's definition (issue #1657): merged by
 * [codeOverlaySpec], with the parts that merge nothing removed -- a key the overlay sets to `null`, and a layout entry for
 * a field the merged type no longer has. What the merge refused is [MergeOutcome.problems], each located in the entry;
 * the value holds the rest of the overlay, so a load can apply what still fits and report what does not.
 */
fun mergeCodeOverlay(slot: String, code: Map<String, Any?>, overlay: Map<String, Any?>): MergeOutcome {
    val spec = codeOverlaySpec(slot) ?: throw IllegalArgumentException("Slot '$slot' takes no overlay of the code.")
    val outcome = mergeOverlay(spec, code, overlay)
    val value = when (slot) {
        // An inherited layout entry for a property the overlay removed goes with it, whether or not the overlay changes
        // the layout: judged as though it stated a layout of none but its own entries.
        CCT.schemaDef -> withoutStrandedLayoutEntries(
            outcome.value.filterValues { it != null },
            overlay + (SCH.layout to (overlay[SCH.layout] ?: emptyMap<String, Any?>())),
        )
        else -> withoutNulls(outcome.value)
    }
    return MergeOutcome(value, outcome.problems)
}

/**
 * The overlay of [slot] that makes [code], the code entry's definition, into [edited] (issue #1657): only what differs,
 * by the rules [mergeCodeOverlay] reads it with, and for a workflow its id first, so the stored definition says which it
 * is. What no rule can state -- a task removed, added, or moved, a workflow's kind changed -- is [DiffOutcome.problems],
 * which an editor answers by offering to detach the entry.
 */
fun diffCodeOverlay(slot: String, code: Map<String, Any?>, edited: Map<String, Any?>): DiffOutcome {
    val spec = codeOverlaySpec(slot) ?: throw IllegalArgumentException("Slot '$slot' takes no overlay of the code.")
    val diff = diffOverlay(spec, code, edited)
    if (slot != CCT.workflowDef) return diff
    val id = edited[WFD.workflowId] ?: code[WFD.workflowId]
    return DiffOutcome(linkedMapOf<String, Any?>(WFD.workflowId to id) + (diff.overlay - WFD.workflowId), diff.problems)
}

/**
 * Where [overlay], an overlay of [slot], restates a list with no keys (issue #1657), as paths: such a list is the
 * overlay's whole once it mentions it, so a change the code later makes to it no longer reaches the client. Said so that
 * keying the list, or leaving it alone, is a choice somebody sees.
 */
fun restatedCodeLists(slot: String, overlay: Map<String, Any?>): List<String> =
    codeOverlaySpec(slot)?.let { restatedLists(it, overlay) }.orEmpty()

/** Whether this stored slot entry is an overlay of the code's (issue #1657): marked `"overrides": "codeOverlay"`. */
val Map<String, Any?>.isCodeOverlay: Boolean get() = this[CCT.overrides] == CCT.overridesCodeOverlayValue

/** The field of a [slot]'s entry holding the definition an overlay changes: a workflow's `definition`, a type's `schema`. */
fun codeOverlayBodyField(slot: String): String = if (slot == CCT.schemaDef) CCT.schema else CCT.definition

/**
 * [overlay], a stored entry of [slot] holding an overlay of [code], the code's entry, as the whole entry it makes
 * (issue #1657): what an editor shows and has a person edit. Its mark stays, so a save of it is kept as an overlay.
 */
fun codeOverlayShown(slot: String, code: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
    val field = codeOverlayBodyField(slot)
    val merged = mergeCodeOverlay(slot, code[field].toJsonMapOrEmpty(), overlay[field].toJsonMapOrEmpty())
    return overlay + (field to merged.value)
}

/**
 * The parts of [overlay], a stored entry of [slot] holding an overlay of [code], the code's entry, that the code no longer
 * has a place for (issue #1657), each led by where it is: what a load leaves out and reports, and what a save of the
 * entry as it is shown, merged, would drop. Empty when the overlay fits the code.
 */
fun codeOverlayStale(slot: String, code: Map<String, Any?>, overlay: Map<String, Any?>): List<String> {
    val field = codeOverlayBodyField(slot)
    return overlayProblemTexts(mergeCodeOverlay(slot, code[field].toJsonMapOrEmpty(), overlay[field].toJsonMapOrEmpty()).problems)
}

/**
 * [edited], a whole entry of [slot] marked as an overlay of [code], the code's entry, as the overlay a save stores (issue
 * #1657): its definition only what differs from the code's. What no overlay can state is in [DiffOutcome.problems].
 */
fun codeOverlayStored(slot: String, code: Map<String, Any?>, edited: Map<String, Any?>): DiffOutcome {
    val field = codeOverlayBodyField(slot)
    val diff = diffCodeOverlay(slot, code[field].toJsonMapOrEmpty(), edited[field].toJsonMapOrEmpty())
    return DiffOutcome(edited + (field to diff.overlay), diff.problems)
}

/**
 * An entry of a **stored** config held as an overlay of its code (issue #1657): the stored entry as written, in [slot],
 * keyed by [key] (a workflow's id, a type's qualified name). [applied] when it was merged over the code's entry and the
 * result stands in the config, as any definition does; otherwise the code's runs, and [problems] says why. A problem on
 * an applied overlay is a part of it the code no longer has a place for, left out while the rest applies.
 *
 * The config keeps every one as written and a write stores it as written, never the merged definition: the merge is
 * redone over the code on every read, which is the point.
 */
class CodeOverlay(
    val slot: String,
    val key: String,
    val entry: Map<String, Any?>,
    val applied: Boolean,
    val problems: List<String>,
)

/** [problems] as one line each, led by where in the entry each one is. */
fun overlayProblemTexts(problems: List<Problem>): List<String> =
    problems.map { p -> p.location?.path?.takeIf { it.isNotEmpty() }?.let { "$it: ${p.message}" } ?: p.message }

/** [raw] with every null-valued entry gone, at any depth: what an overlay's removals leave behind in a merge. */
private fun withoutNulls(raw: Map<String, Any?>): Map<String, Any?> =
    raw.filterValues { it != null }.mapValues { (_, v) -> withoutNullsIn(v) }

private fun withoutNullsIn(value: Any?): Any? = when (value) {
    is Map<*, *> -> withoutNulls(value.toJsonMapOrEmpty())
    is List<*> -> value.map { withoutNullsIn(it) }
    else -> value
}
