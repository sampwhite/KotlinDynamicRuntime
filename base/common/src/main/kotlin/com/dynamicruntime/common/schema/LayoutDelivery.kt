package com.dynamicruntime.common.schema

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.analyzeTemplate
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
 * resolve. A pull that cannot resolve degrades to the copy **as written** with a `[<schema>]` warning, rather than
 * faulting the whole catalog or view response. That fallback is the safety net for **every** miss today: the
 * boot checks so far catch a *malformed* block (`SchemaService.checkLayouts`) and the frontend-pass problems
 * (`layoutTemplateProblems`), but whether a well-formed pull's file and key actually **resolve** is a
 * cross-service check deferred to #620, so a literal miss (like a computed one) surfaces here at delivery rather
 * than at boot for now.
 */
fun resolveDeliveredLayouts(cxt: KdrCxt, layouts: Map<String, Any?>): Map<String, Any?> {
    if (layouts.isEmpty()) {
        return layouts
    }
    val svc = MarkdownFragmentService.get(cxt)
    return layouts.mapValues { (typeName, block) ->
        val body = block.toJsonMapOrEmpty()
        val fileId = body[SL.fragmentFileId].toOptStr()
        fun pass(what: String, text: String): String = try {
            svc.layoutBackendPass(cxt, text, fileId)
        } catch (e: Throwable) {
            LogSchema.warn(cxt) {
                "Layout '$typeName' $what has an unresolvable backend pull; delivering it as written. ${e.message}"
            }
            text
        }
        val heading = (body[SL.label] as? String)?.let { if (MarkdownFragmentService.backendPassPrefix in it) pass("heading", it) else it }
        val fields = body[SL.schemaFields].toJsonListOfMaps().map { field ->
            val resolved = LinkedHashMap<String, Any?>(field)
            for (copyKey in listOf(SL.label, SL.description, SL.hint)) {
                val text = field[copyKey] as? String ?: continue
                // Only a string carrying a backend block needs the pass; plain copy and a frontend-only `${…}`
                // are left exactly as they are.
                if (MarkdownFragmentService.backendPassPrefix !in text) {
                    continue
                }
                resolved[copyKey] = pass("field '${field[SL.field].toOptStr()}' $copyKey", text)
            }
            resolved
        }
        buildMap {
            putAll(body)
            heading?.let { put(SL.label, it) }
            put(SL.schemaFields, fields)
        }
    }
}


/**
 * Whether a layout's backend `%{@t(fileId.ns.key)}` pull resolves at boot (issue #620): the target file is
 * declared, it is a **backend** file, and the `namespace.key` is present in it. Mirrors the three checks
 * `MarkdownFragmentService.checkFragments` runs on a fragment file's own pulls.
 */
class LayoutPullHit(val fileFound: Boolean, val backend: Boolean, val keyPresent: Boolean)

/**
 * The problems with a layout's backend `%{@t(...)}` pulls whose target does not resolve (issue #620) -- the
 * cross-service half of the layout boot check, the part `SchemaService.checkLayouts` cannot do because it runs
 * in the startup phase, before the fragment registry exists. Kept a **pure function of a resolver lambda** so it
 * carries no dependency on `MarkdownFragmentService`: a regular-phase caller supplies [resolve] against the
 * fragment registry (see `LayoutCheckService`).
 *
 * For each copy string -- the block heading and every field's `label` / `description` / `hint` -- each
 * **literal, un-guarded** `%{@t(...)}` is resolved: a two-part `namespace.key` against the block's
 * `fragmentFileId`, a three-part `fileId.namespace.key` against its own file (the composition the delivery's
 * `layoutBackendPass` does). A guarded (`?:`) or computed key is skipped -- the delivery fallback handles those,
 * exactly as the fragment layer leaves them. [where] names the type (and client, for a variant).
 */
fun layoutPullProblems(
    where: String,
    layout: SchLayout,
    resolve: (fileId: String, nsKey: String) -> LayoutPullHit,
): List<String> {
    val problems = mutableListOf<String>()
    fun check(what: String, text: String?) {
        if (text == null || MarkdownFragmentService.backendPassPrefix !in text) {
            return
        }
        for (ref in text.analyzeTemplate(MarkdownFragmentService.backendPassPrefix).refs) {
            if (ref.tolerant) {
                continue
            }
            // A two-part key resolves against the block's default file; a three-part key names its own.
            val full = if (layout.fragmentFileId != null && ref.key.count { it == '.' } == 1) {
                "${layout.fragmentFileId}.${ref.key}"
            } else {
                ref.key
            }
            val dot = full.indexOf('.')
            if (dot <= 0 || dot >= full.length - 1) {
                problems.add("$where: the '${SCH.layout}' $what pull '%{@t(\"${ref.key}\")}' is not a fileId.namespace.key reference.")
                continue
            }
            val fileId = full.substring(0, dot)
            val nsKey = full.substring(dot + 1)
            val hit = resolve(fileId, nsKey)
            when {
                !hit.fileFound ->
                    problems.add("$where: the '${SCH.layout}' $what pulls %{@t(\"${ref.key}\")}, but no fragment file '$fileId' is declared here.")
                !hit.backend ->
                    problems.add("$where: the '${SCH.layout}' $what pulls from '$fileId', a frontend file; a layout pull must name a backend file.")
                !hit.keyPresent ->
                    problems.add("$where: the '${SCH.layout}' $what pulls %{@t(\"${ref.key}\")}, but '$fileId' has no fragment '$nsKey'.")
            }
        }
    }
    check("heading", layout.label)
    for (field in layout.fields) {
        check("${field.field}'s label", field.label)
        check("${field.field}'s description", field.description)
        check("${field.field}'s hint", field.hint)
    }
    return problems
}
