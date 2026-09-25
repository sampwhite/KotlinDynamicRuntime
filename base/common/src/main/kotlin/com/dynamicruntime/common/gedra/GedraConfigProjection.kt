package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder

/**
 * One JSON projection for a [GedraTrait] and a [ClientTraitUsage], shared by the config serializer
 * (`GedraConfigSerialize`) and the client-definition endpoints (`ClientEndpoints`) so each shape is written in
 * one place (issue #702). The map and the schema for it live side by side here, the way
 * [ClientDef.toInfo]/[ClientDef.defineInfoType] co-locate their pair -- so a field added to the map and to its
 * schema is one edit, and the two cannot drift.
 *
 * They are **extension functions in `base/common`**, not methods on the kernel types, on purpose: the field-name
 * constants ([CCT]) live in `base/common` while the types live in `base/kernel`, so the projection cannot sit on
 * the type without moving those constants across the module boundary. As extensions the call site still reads
 * `trait.toMetadataMap()`, and the constants stay where the config subsystem already keeps them.
 *
 * Scope: this is the **metadata** projection the endpoints and the serializer share, not the whole stored form.
 * The serializer adds a trait's config-only extras on top -- the description (read back from the generated entry
 * type) and the `dataSchema` body -- because those need the [GedraConfig] around the trait, which a projection
 * of the trait alone does not have. And the *computed* display value ([computeDisplayValues], keyed by [UF]) is
 * a different projection again -- a value off a row, not this rule -- and stays separate.
 */

/**
 * A trait's metadata as a wire map (issue #702): its id, the generated entry type, the gedra kinds it applies
 * to, and its primary key. [omitEmptyPrimaryKey] drops an empty `primaryKey` rather than writing `[]` -- the
 * serializer sets it, to keep stored config as lean as it was; the endpoint leaves it, because its schema
 * declares `primaryKey` required and present-but-empty is the honest answer there. The serializer layers its
 * `description`/`dataSchema`/`stateClass` on top of this; the endpoint uses it as-is.
 */
fun GedraTrait.toMetadataMap(omitEmptyPrimaryKey: Boolean = false): Map<String, Any?> = buildMap {
    put(CCT.traitId, traitId)
    put(CCT.typeName, typeName)
    put(CCT.appliesTo, appliesTo.map { it.name })
    if (!omitEmptyPrimaryKey || primaryKey.isNotEmpty()) {
        put(CCT.primaryKey, primaryKey)
    }
}

/** The schema of [toMetadataMap] -- called inside a `type(...) { }` block by both surfaces that emit a trait. */
fun SchTypeBuilder.traitMetadataFields() {
    property(
        CCT.traitId, "The trait's id, unique within its client's view: its own and the global traits.",
        required = true,
    )
    property(CCT.typeName, "The fully qualified name of the entry type this trait generated.", required = true)
    property(CCT.appliesTo, "The gedra kinds an entry of this trait may be carried on.", required = true) {
        type = SCT.array
        items { options(GedraDataType.entries) }
    }
    property(CCT.primaryKey, "The data fields that tell several entries apart; empty when single-instance.", required = true) {
        type = SCT.array
        items { type = SCT.string }
    }
}

/**
 * A trait-usage rule as a wire map (issue #702): the listing column it drives and how its value searches.
 * [includeDisplay] carries the `display` expression -- the serializer keeps it (it is the rule's substance), the
 * endpoint drops it (an internal expression an admin view has no use for, and its schema omits it).
 */
fun ClientTraitUsage.toRuleMap(includeDisplay: Boolean = true): Map<String, Any?> = buildMap {
    put(CCT.traitId, traitId)
    put(CCT.label, label)
    if (includeDisplay) {
        put(CCT.display, display)
    }
    put(CCT.kind, kind.name)
    put(CCT.substring, substring)
}

/** The schema of [toRuleMap] -- called inside a `type(...) { }` block; [includeDisplay] mirrors the map's. */
fun SchTypeBuilder.usageRuleFields(includeDisplay: Boolean = true) {
    property(CCT.traitId, "The trait whose value the column shows.", required = true)
    property(CCT.label, "The column header.", required = true)
    if (includeDisplay) {
        property(CCT.display, "The string-script expression evaluated against the trait's data for the value.", required = true)
    }
    property(CCT.kind, "How the value is read and compared.", required = true) { options(UsageKind.entries) }
    property(CCT.substring, "Whether a string column also offers a contains search.", required = true) { type = SCT.boolean }
}
