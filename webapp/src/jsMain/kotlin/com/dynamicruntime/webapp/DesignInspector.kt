package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.util.fmtD
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.details
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.summary
import react.useEffect
import react.useState
import web.cssom.ClassName

private val designScope = MainScope()

/** The Design View definition read (issue #972): one definition's authored entry and where it was declared. */
object DesignApi {
    suspend fun definition(slot: String, key: String): Map<String, Any?> =
        Http.getApi(DSV.definition + queryString(mapOf(DSV.slot to slot, DSV.key to key)))[EP.item].toJsonMapOrEmpty()
}

/**
 * The frame Design View draws around one selectable part of a page (issue #972): an outline that shows on hover (or
 * always, with "show all ids" on), a badge naming the part -- a real button, so the part can be reached and selected
 * from the keyboard -- and, for a ghost, the reason the page left it out. A click anywhere inside selects the
 * innermost part, so a field inside a trait selects the field rather than the trait.
 */
fun ChildrenBuilder.designTargetFrame(
    session: DesignSession,
    target: DesignTarget,
    label: String,
    ghostReason: String? = null,
    content: ChildrenBuilder.() -> Unit,
) {
    div {
        className = ClassName(
            listOfNotNull(
                "dv-target",
                "dv-ghost".takeIf { ghostReason != null },
                "dv-selected".takeIf { session.isSelected(target) },
                "dv-pinned".takeIf { session.showAllIds },
            ).joinToString(" "),
        )
        onClick = { e ->
            e.stopPropagation()
            session.select(target)
        }
        button {
            className = ClassName("dv-badge")
            title = "Inspect $label"
            onClick = { e ->
                e.stopPropagation()
                session.select(target)
            }
            +label
        }
        ghostReason?.let {
            p {
                className = ClassName("dv-ghost-reason")
                +it
            }
        }
        content()
    }
}

external interface DesignInspectorProps : Props {
    var session: DesignSession
    var view: WorkflowView
    var onShowAllIds: (Boolean) -> Unit
    var onShowHidden: (Boolean) -> Unit
    var onClear: () -> Unit
}

/**
 * Design View's side panel (issue #972): what the selected part of the page is, which definition declares it and
 * where that definition lives, what the page made of it, and the authored JSON it came from. Read-only.
 *
 * Fixed to the right of the window while Design View is on, with the page pushed aside rather than covered (the
 * page's own class does that), so the form being looked at keeps its full layout. With nothing selected it
 * describes the workflow and says how to start.
 */
val DesignInspector = FC<DesignInspectorProps> { props ->
    val session = props.session
    val design = session.design
    val selected = session.selected
    val address: DesignAddress? = selected?.let { addressOf(it, design) } ?: design.workflow
    // The authored entry, by slot and key: one read per definition, kept while the panel is open, so moving between
    // the fields of one trait does not refetch it.
    var loaded by useState<Map<String, LoadedDefinition>>(emptyMap())
    val cacheKey = address?.let { "${it.slot}|${it.key}" }
    useEffect(cacheKey) {
        val a = address ?: return@useEffect
        if (cacheKey == null || cacheKey in loaded) return@useEffect
        designScope.launch {
            // A definition that will not load is said in the panel; the page stays usable.
            val result = apiResult { DesignApi.definition(a.slot, a.key) }
            loaded = loaded + (cacheKey to LoadedDefinition(result.valueOrNull(), result.failureOrNull()?.let { userFacingError(it) }))
        }
    }
    val definition = cacheKey?.let { loaded[it] }

    aside {
        className = ClassName("dv-inspector")
        asDynamic()["aria-label"] = "Design view inspector"
        div {
            className = ClassName("dv-inspector-head")
            span {
                className = ClassName("dv-title")
                +"Design view"
            }
            if (selected != null) {
                button {
                    className = ClassName("dv-clear")
                    title = "Back to the workflow"
                    onClick = { props.onClear() }
                    +"×"
                }
            }
        }
        div {
            className = ClassName("dv-switches")
            Checkbox {
                checked = session.showAllIds
                onChange = { e -> props.onShowAllIds(e.target.checked == true) }
                +"Show all ids"
            }
            Checkbox {
                checked = session.showHidden
                onChange = { e -> props.onShowHidden(e.target.checked == true) }
                +"Show hidden fields"
            }
        }
        when (selected) {
            null, DesignTarget.Workflow -> workflowSummary(props.view, selected == null)
            is DesignTarget.Trait -> traitSummary(selected)
            is DesignTarget.Field -> fieldSummary(selected)
        }
        if (address == null) {
            p {
                className = ClassName("dv-note")
                +"This part of the page has no definition the backend could name."
            }
        } else {
            definitionSection(address, definition, selected)
        }
    }
}

/** A definition read's outcome: the response, or why there is none. */
private class LoadedDefinition(val response: Map<String, Any?>?, val error: DisplayError?)

/** Where [target] is declared: its type's address from the backend's block, extended to the field's place in it. */
private fun addressOf(target: DesignTarget, design: WfDesign): DesignAddress? = when (target) {
    DesignTarget.Workflow -> design.workflow
    is DesignTarget.Trait -> design.types[target.trait.typeName]
    is DesignTarget.Field -> {
        val owner = fieldOwner(target.root.typeName, target.root.type, target.path)
        design.types[owner.typeName]?.below(owner.schemaPath)
    }
}

private fun ChildrenBuilder.workflowSummary(view: WorkflowView, nothingSelected: Boolean) {
    h2 { +view.label.ifBlank { humanizeFieldName(view.workflowId) } }
    fact("Workflow", view.workflowId, mono = true)
    fact("Kind", view.entry)
    fact("Tasks", view.tasks.joinToString(", ") { it.id }, mono = true)
    if (nothingSelected) {
        p {
            className = ClassName("dv-note")
            +"Click any outlined part of the form — a section or a field — to see what defines it."
        }
    }
}

private fun ChildrenBuilder.traitSummary(target: DesignTarget.Trait) {
    val trait = target.trait
    h2 { +(trait.fieldLayout?.label?.lineSequence()?.firstOrNull()?.trimStart('#', ' ') ?: trait.type.title ?: humanizeFieldName(trait.traitId)) }
    fact("Trait", trait.traitId, mono = true)
    fact("Data type", trait.typeName, mono = true)
    fact("In this task", if (trait.required) "required" else "optional")
    fact("Fields", trait.type.properties.size.toString())
}

private fun ChildrenBuilder.fieldSummary(target: DesignTarget.Field) {
    val prop = target.prop
    val vt = prop.valueType
    h2 { +(target.layout?.label ?: prop.title ?: humanizeFieldName(target.name)) }
    target.hidden?.let {
        p {
            className = ClassName("dv-callout")
            +"Not shown on this form. ${it.reason}"
        }
    }
    fact("Field", target.path, mono = true)
    fact("Type", typeWord(vt))
    fact("Required", if (target.required) "yes" else "no")
    if (vt.minBound != null || vt.maxBound != null) {
        fact("Range", listOfNotNull(vt.minBound?.let { "from ${it.fmtD()}" }, vt.maxBound?.let { "to ${it.fmtD()}" }).joinToString(" "))
    }
    vt.options?.let { options ->
        fact("Choices", options.joinToString(", ") { if (it.label == it.value) it.value else "${it.value} (${it.label})" })
    }
    prop.visibleWhen?.let { fact("Shown when", it, mono = true) }
    if (vt.derived) fact("Computed", "yes — nobody enters it")
    target.layout?.let { layout ->
        h3 { +"Copy on the form" }
        layout.label?.let { fact("Label", it) }
        layout.description?.let { fact("Description", it) }
        layout.hint?.let { fact("Hint", it) }
    }
}

/** Where the selection's definition lives, and its authored JSON -- the part at the address, then the whole entry. */
private fun ChildrenBuilder.definitionSection(address: DesignAddress, loaded: LoadedDefinition?, selected: DesignTarget?) {
    h3 { +"Definition" }
    p {
        className = ClassName("dv-address")
        +addressLine(address)
    }
    div {
        className = ClassName("dv-origin")
        span {
            className = ClassName(if (address.editable) "dv-pill dv-pill-own" else "dv-pill")
            +(if (address.editable) "Editable here" else "Read-only here")
        }
        span { +originText(address) }
    }
    val response = loaded?.response
    when {
        loaded == null -> p {
            className = ClassName("dv-note")
            +"Loading the definition…"
        }
        response == null -> loaded.error?.let { errorText("Couldn't load the definition.", it) }
        else -> {
            response[DSV.version]?.let { v ->
                fact("Revision", "v$v" + if (response[DSV.published] == true) ", published" else ", not yet published")
            }
            val entry = response[DSV.entry].toJsonMapOrEmpty()
            val focused = subtreeAt(entry, address.path)
            // A field's layout copy sits beside its schema, in its type's `g-layout`, so it is shown with it.
            val layoutEntry = (selected as? DesignTarget.Field)?.let { field ->
                val owner = address.path?.substringBeforeLast(".properties.", "")?.ifEmpty { null }
                layoutEntryIn(subtreeAt(entry, owner ?: typeBodyPath(address)), field.name)
            }
            if (focused != null && address.path != null) {
                jsonBlock("At this address", focused)
                layoutEntry?.let { jsonBlock("Its layout entry", it) }
            }
            details {
                if (address.path == null) asDynamic()["open"] = true
                summary { +"The whole ${slotWord(address.slot)} entry" }
                jsonBlock(null, entry)
            }
        }
    }
}

/** Where a type's body sits in its entry: under `dataSchema` in a trait's, under `schema` in a type's. */
private fun typeBodyPath(address: DesignAddress): String? = when (address.slot) {
    CCT.traitDef -> CCT.dataSchema
    CCT.schemaDef -> CCT.schema
    else -> null
}

private fun ChildrenBuilder.jsonBlock(caption: String?, value: Any?) {
    caption?.let {
        p {
            className = ClassName("dv-caption")
            +it
        }
    }
    pre {
        className = ClassName("dv-json")
        +value.toJsonStr()
    }
}

private fun ChildrenBuilder.fact(name: String, value: String, mono: Boolean = false) {
    div {
        className = ClassName("dv-fact")
        span {
            className = ClassName("dv-fact-name")
            +name
        }
        span {
            className = ClassName(if (mono) "dv-fact-value mono" else "dv-fact-value")
            +value
        }
    }
}
