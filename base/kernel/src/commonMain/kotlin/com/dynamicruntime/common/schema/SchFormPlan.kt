package com.dynamicruntime.common.schema

/**
 * The first slice of the frontend form's presentation logic pulled into the kernel (issue #777, toward the
 * base:kernel "headless simulated-frontend testing" goal): the ordered list of an object type's field names to
 * present, decided by the layout's [SchLayoutMode]. Pure over [SchType] + [SchLayout], so a JVM (backend) test
 * exercises exactly what the browser runs -- the same relationship the schema validator already has with the
 * frontend, one layer up. The React render loop (`SchemaForm`) calls this instead of iterating
 * `type.properties`, then applies its per-field gates over the result.
 *
 * **Order and candidate membership only.** The per-field gates a form still applies -- a `g-derived` value
 * hidden in a friendly form, a `g-visibleWhen` field the caller's cfacts fail, a conditionally forbidden field,
 * a caller-omitted field -- run *on top of* this list. So the presented set is this list **intersected** with
 * what the schema is willing to show, which is why even an [SchLayoutMode.authoritative] layout can only narrow
 * and order, never widen: the gates are downstream of it.
 *
 *  - no layout, or [SchLayoutMode.overlay]: schema declaration order, every declared property (the behavior
 *    before this issue -- the layout is a copy overlay and has no say over order or membership).
 *  - [SchLayoutMode.reorder]: the layout's listed fields first, in list order, then every other declared
 *    property in schema order. Nothing is dropped.
 *  - [SchLayoutMode.authoritative]: only the layout's listed fields, in list order.
 *
 * A layout field the type does not declare is dropped here defensively. The boot check ([layoutFieldProblems])
 * already refuses one, and a narrowed client's layout is pruned to what it kept ([SchLayout.prunedTo]), so in a
 * resolved schema the layout names only declared properties and this filter changes nothing -- it just keeps
 * the function honest on its own, without depending on the boot having run.
 */
fun orderedFieldNames(type: SchType, layout: SchLayout?): List<String> {
    val declared = type.properties.keys.toList()
    if (layout == null) return declared
    val listed = layout.fieldNames.filter { it in type.properties }
    return when (layout.mode) {
        SchLayoutMode.overlay -> declared
        SchLayoutMode.reorder -> {
            val listedSet = listed.toSet()
            listed + declared.filter { it !in listedSet }
        }
        SchLayoutMode.authoritative -> listed
    }
}
