package com.dynamicruntime.common.schema

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Resolves the **backend pass** over a delivered `{ typeName -> g-layout block }` map before it ships (issue
 * #605). A layout's `label` / `description` / `hint` may pull shared copy with a backend `%{@t("…")}`, and this
 * is where that pull happens: each copy string runs through [MarkdownFragmentService.layoutBackendPass] against
 * the block's `fragmentFileId`, so a `%{@t(...)}` becomes the caller's finished copy and only `${...}` (the
 * frontend's field-data substitution) is left on the wire. Backend fragment files are private and never served,
 * so this keeps a pulled string's *source* on the server and ships only the result -- the reason the backend
 * pass exists (the same one task labels use).
 *
 * Run per request at each friendly surface's delivery (the endpoint catalog and the workflow view), because the
 * resolution is per caller (a client's fragment overlays). A string with no `%` is left untouched -- nothing to
 * resolve. A pull that cannot resolve (notably a **computed** key, which the boot check cannot see) degrades to
 * the copy **as written** with a `[schema]` warning, rather than faulting the whole catalog or view response;
 * the boot check ([layoutTemplateProblems] plus the fragment-pull validation in `SchemaService.checkLayouts`)
 * catches every *literal* miss before a request is ever served.
 */
fun resolveDeliveredLayouts(cxt: KdrCxt, layouts: Map<String, Any?>): Map<String, Any?> {
    if (layouts.isEmpty()) {
        return layouts
    }
    val svc = MarkdownFragmentService.get(cxt)
    return layouts.mapValues { (typeName, block) ->
        val body = block.toJsonMapOrEmpty()
        val fileId = body[SL.fragmentFileId].toOptStr()
        val fields = body[SL.schemaFields].toJsonListOfMaps().map { field ->
            val resolved = LinkedHashMap<String, Any?>(field)
            for (copyKey in listOf(SL.label, SL.description, SL.hint)) {
                val text = field[copyKey] as? String ?: continue
                // Only a string carrying a backend block needs the pass; plain copy and a frontend-only `${…}`
                // are left exactly as they are.
                if (MarkdownFragmentService.backendPassPrefix !in text) {
                    continue
                }
                resolved[copyKey] = try {
                    svc.layoutBackendPass(cxt, text, fileId)
                } catch (e: Throwable) {
                    LogSchema.warn(cxt) {
                        "Layout '$typeName' field '${field[SL.field].toOptStr()}' $copyKey has an unresolvable " +
                            "backend pull; delivering it as written. ${e.message}"
                    }
                    text
                }
            }
            resolved
        }
        body + (SL.schemaFields to fields)
    }
}
