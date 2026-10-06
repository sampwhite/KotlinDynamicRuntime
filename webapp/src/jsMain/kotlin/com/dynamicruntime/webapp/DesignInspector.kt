package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.util.fmtD
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
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
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useState
import web.cssom.ClassName

private val designScope = MainScope()

/** The Design View definition read (issue #972): one definition's authored entry and where it was declared. */
object DesignApi {
    suspend fun definition(slot: String, key: String): Map<String, Any?> =
        Http.getApi(DSV.definition + queryString(mapOf(DSV.slot to slot, DSV.key to key)))[EP.item].toJsonMapOrEmpty()

    /**
     * Sets the workflow's own layout entry for [field] of [typeName] to [entry], or clears it when [entry] is null
     * (issue #984), as an edit of the definition stamped [basedOn]. The server's refusal -- a stale stamp (409), an
     * entry the layout checks refuse -- comes back as the result's refusal.
     */
    suspend fun setLayoutEntry(
        workflowId: String,
        typeName: String,
        field: String,
        entry: Map<String, Any?>?,
        basedOn: String,
    ): ApiResult<Map<String, Any?>> = Http.sendApiResult(
        "POST", DSV.layoutEntryEdit,
        buildMap {
            put(DSV.workflowId, workflowId)
            put(DSV.typeName, typeName)
            put(DSV.field, field)
            entry?.let { put(DSV.entry, it) }
            put(DSV.basedOn, basedOn)
        },
    )

    /**
     * Sets [field] of [typeName] -- in the definition the client declares -- to the layout [entry] and the choices
     * [options], each when given, for every workflow on the client (issue #1029), as an edit of the entry stamped
     * [basedOn]. A refusal (a stale stamp, a removed choice) comes back as the result's refusal.
     */
    suspend fun setSharedField(
        typeName: String,
        field: String,
        entry: Map<String, Any?>?,
        options: List<Map<String, Any?>>?,
        basedOn: String,
    ): ApiResult<Map<String, Any?>> = Http.sendApiResult(
        "POST", DSV.sharedFieldEdit,
        buildMap {
            put(DSV.typeName, typeName)
            put(DSV.field, field)
            entry?.let { put(DSV.entry, it) }
            options?.let { put(DSV.options, it) }
            put(DSV.sharedBasedOn, basedOn)
        },
    )
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
    /** Whether the workflow overrides this part's copy (issue #984): its badge carries a mark. */
    altered: Boolean = false,
    content: ChildrenBuilder.() -> Unit,
) {
    div {
        className = ClassName(
            listOfNotNull(
                "dv-target",
                "dv-altered".takeIf { altered },
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
    // Bumped when a save changes a definition the panel has read (the shared editor, issue #1029): its cached read is
    // dropped, and the key alone would not change, so this is what makes the read run again.
    var rereads by useState(0)
    val cacheKey = address?.let { "${it.slot}|${it.key}" }
    useEffect(cacheKey, rereads) {
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
            is DesignTarget.Field -> {
                // The field's copy as the page now shows it -- read from the view, not from the selection, which was
                // made before any edit re-read the view.
                val owner = fieldOwner(selected.root.typeName, selected.root.type, selected.path)
                fieldSummary(selected, props.view.fieldLayouts[owner.typeName]?.fieldFor(selected.name) ?: selected.layout)
                // Once the definition read has answered: the form starts from the type's authored entry, and starting
                // before it arrived would save an entry missing what the form never saw.
                if (design.canEdit && definition != null) {
                    WorkflowCopyEditor {
                        // Keyed on the field and the definition it edits, so a save -- which re-reads the view and
                        // moves the stamp -- starts the form afresh from what is now stored.
                        key = "${selected.id}|${design.basedOn}".unsafeCast<Key>()
                        this.session = session
                        this.target = selected
                        authored = (address?.let { authoredLayoutEntry(it, definition, selected) })
                    }
                } else if (design.editRefusal != null) {
                    // Not editable here: what the workflow's own copy is, if it has one -- and why there is no control,
                    // so the missing one reads as a rule rather than a fault.
                    val edit = design.layoutEdit(owner.typeName, selected.name)
                    div {
                        className = ClassName("dv-edit")
                        overrideFacts(edit)
                        edit?.let { sharedCopyFacts(it.inherited) }
                        p {
                            className = ClassName("dv-note")
                            +design.editRefusal
                        }
                    }
                }
                // The shared definition (issue #1029): where it is used, and -- deliberately, behind its own button --
                // editing it for every workflow. Once the definition read has answered, for the same reason as above.
                val facts = parseSharedFacts(definition?.response)
                if (facts != null && address != null) {
                    SharedFieldSection {
                        key = "${selected.id}|${facts.basedOn}".unsafeCast<Key>()
                        this.session = session
                        this.target = selected
                        this.facts = facts
                        this.typeName = owner.typeName
                        this.fieldSchema = subtreeAt(definition?.response?.get(DSV.entry).toJsonMapOrEmpty(), address.path)
                        this.authored = authoredLayoutEntry(address, definition, selected)
                        // The read is cached per definition, so a shared save -- which changes it -- drops it to re-read.
                        onSaved = {
                            cacheKey?.let { loaded = loaded - it }
                            rereads += 1
                        }
                    }
                }
            }
        }
        if (address == null) {
            p {
                className = ClassName("dv-note")
                +"This part of the page has no definition the backend could name."
            }
        } else {
            // Yes or no on editing is the block's decision, and only a field has an edit to offer (issue #1013).
            definitionSection(address, definition, selected, editable = design.canEdit.takeIf { selected is DesignTarget.Field })
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

private fun ChildrenBuilder.fieldSummary(target: DesignTarget.Field, layout: SchLayoutField?) {
    val prop = target.prop
    val vt = prop.valueType
    h2 { +(layout?.label ?: prop.title ?: humanizeFieldName(target.name)) }
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
    layout?.let { layout ->
        h3 { +"Copy on the form" }
        layout.label?.let { fact("Label", it) }
        layout.description?.let { fact("Description", it) }
        layout.hint?.let { fact("Hint", it) }
    }
}

/** Where the selection's definition lives, and its authored JSON -- the part at the address, then the whole entry. */
private fun ChildrenBuilder.definitionSection(
    address: DesignAddress,
    loaded: LoadedDefinition?,
    selected: DesignTarget?,
    /** Whether the selection can be edited here, for the pill; null when it has no edit to offer. */
    editable: Boolean?,
) {
    h3 { +"Definition" }
    p {
        className = ClassName("dv-address")
        +addressLine(address)
    }
    div {
        className = ClassName("dv-origin")
        editable?.let { yes ->
            span {
                className = ClassName(if (yes) "dv-pill dv-pill-own" else "dv-pill")
                +(if (yes) "Editable here" else "Read-only here")
            }
        }
        span { +provenanceText(address) }
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
            // The client's own alteration of a shared definition (issue #1013): its authored body, as written.
            (response[DSV.alteredBy] as? Map<*, *>)?.toJsonMapOrEmpty()?.let { altered ->
                details {
                    summary { +("This client's alteration" + altered[DSV.config].toOptStr()?.let { " ($it)" }.orEmpty()) }
                    jsonBlock(null, altered[DSV.entry])
                }
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

/** The type's own authored layout entry for [field]'s field, from the loaded definition, or null. */
private fun authoredLayoutEntry(address: DesignAddress, loaded: LoadedDefinition?, field: DesignTarget.Field): Map<String, Any?>? {
    val entry = loaded?.response?.get(DSV.entry).toJsonMapOrEmpty()
    val owner = address.path?.substringBeforeLast(".properties.", "")?.ifEmpty { null }
    return layoutEntryIn(subtreeAt(entry, owner ?: typeBodyPath(address)), field.name)
}

/**
 * The heading of a field's "Copy for this workflow" section, and -- when the workflow overrides the field's copy
 * ([edit]) -- that it does and whether the shared copy has changed since. Said the same way whether or not the copy
 * can be edited here.
 */
private fun ChildrenBuilder.overrideFacts(edit: LayoutEdit?) {
    h3 { +"Copy for this workflow" }
    if (edit == null) return
    p {
        className = ClassName("dv-note")
        +"This workflow uses its own copy here; every other workflow shows the shared copy."
    }
    if (edit.inheritedChanged) {
        p {
            className = ClassName("dv-callout")
            +"The shared copy has changed since this workflow overrode it."
        }
    }
}

/**
 * The shared copy a workflow's override replaces, for a reader who cannot edit it here -- the editor shows the same
 * beside each of its inputs. [inherited] is null when the shared layout has no entry for the field.
 */
private fun ChildrenBuilder.sharedCopyFacts(inherited: Map<String, Any?>?) {
    val copy = editableCopyKeys.mapNotNull { key -> inherited?.get(key).toOptStr()?.let { key to it } }
    if (copy.isEmpty()) {
        fact("Shared copy", "none")
        return
    }
    for ((key, value) in copy) fact("Shared ${humanizeFieldName(key).lowercase()}", value)
}

external interface WorkflowCopyEditorProps : Props {
    var session: DesignSession
    var target: DesignTarget.Field
    /** The type's own layout entry for the field, when the definition read has one -- where an edit starts. */
    var authored: Map<String, Any?>?
}

/**
 * The workflow's own copy for one field (issue #984): its label, description and hint, edited as a small form or as
 * the layout entry's JSON, saved as the workflow's override of the shared copy -- never the shared definition, which
 * every other workflow draws from too. Shows what the override replaces, says when the shared copy has changed since,
 * and offers **Reset to shared**. A save is the server's to refuse (a stale page, an entry the layout checks reject),
 * and the refusal is said here.
 */
private val WorkflowCopyEditor = FC<WorkflowCopyEditorProps> { props ->
    val session = props.session
    val target = props.target
    val owner = fieldOwner(target.root.typeName, target.root.type, target.path)
    val edit = session.design.layoutEdit(owner.typeName, target.name)
    val shared = edit?.inherited ?: props.authored
    val start = edit?.entry ?: props.authored ?: emptyMap()
    var editing by useState(false)
    var asJson by useState(false)
    var values by useState(editableCopyKeys.associateWith { start[it].toOptStr().orEmpty() })
    var jsonText by useState(start.toJsonStr())
    var saving by useState(false)
    var failure by useState<DisplayError?>(null)

    fun save(entry: Map<String, Any?>?) {
        saving = true
        failure = null
        designScope.launch {
            val result = DesignApi.setLayoutEntry(session.workflowId, owner.typeName, target.name, entry, session.design.basedOn)
            saving = false
            val refused = result.failureOrNull()
            if (refused != null) {
                failure = userFacingError(refused)
            } else {
                editing = false
                session.afterEdit()
            }
        }
    }

    div {
        className = ClassName("dv-edit")
        overrideFacts(edit)
        if (!editing) {
            div {
                className = ClassName("dv-actions")
                Button {
                    size = "small"
                    onClick = { editing = true }
                    +(if (edit == null) "Override for this workflow" else "Edit")
                }
                if (edit != null) {
                    Button {
                        size = "small"
                        type = "link"
                        loading = saving
                        onClick = { save(null) }
                        +"Reset to shared"
                    }
                }
            }
        } else {
            if (asJson) {
                textarea {
                    className = ClassName("code json-edit dv-json-edit")
                    value = jsonText
                    rows = 8
                    onChange = { e -> jsonText = e.target.value }
                }
            } else {
                for (key in editableCopyKeys) {
                    div {
                        className = ClassName("dv-edit-row")
                        span {
                            className = ClassName("dv-fact-name")
                            +humanizeFieldName(key)
                        }
                        Input {
                            value = values[key].orEmpty()
                            onChange = { e -> values = values + (key to (e.target.value as String)) }
                        }
                        shared?.get(key).toOptStr()?.let {
                            p {
                                className = ClassName("dv-shared")
                                +"Shared: $it"
                            }
                        }
                    }
                }
            }
            div {
                className = ClassName("dv-actions")
                Button {
                    type = "primary"
                    size = "small"
                    loading = saving
                    onClick = {
                        if (asJson) {
                            val parsed = parseJsonField(jsonText)
                            val entry = parsed.value as? Map<*, *>
                            if (parsed.error != null || entry == null) {
                                failure = DisplayError.expected(parsed.error ?: "The entry has to be a JSON object.")
                            } else {
                                save(entry.toJsonMapOrEmpty())
                            }
                        } else {
                            save(copyEntryFrom(start, target.name, values))
                        }
                    }
                    +"Save"
                }
                Button {
                    size = "small"
                    onClick = {
                        if (!asJson) jsonText = copyEntryFrom(start, target.name, values).toJsonStr()
                        asJson = !asJson
                    }
                    +(if (asJson) "Edit as a form" else "Edit as JSON")
                }
                Button {
                    size = "small"
                    type = "link"
                    onClick = {
                        editing = false
                        failure = null
                    }
                    +"Cancel"
                }
            }
        }
        failure?.let { errorText("Couldn't save the copy.", it) }
    }
}

external interface SharedFieldSectionProps : Props {
    var session: DesignSession
    var target: DesignTarget.Field
    var facts: SharedFacts
    /** The type that declares the field -- what the edit names. */
    var typeName: String
    /** The field's schema in the definition's authored entry, where its choices are. */
    var fieldSchema: Any?
    /** The type's own layout entry for the field, when it has one -- where a copy edit starts. */
    var authored: Map<String, Any?>?
    var onSaved: () -> Unit
}

/**
 * The field's **shared** definition (issue #1029): where it is used, which workflows keep their own copy of it, and --
 * opened deliberately, never as an option beside a workflow's Save -- its copy and choices edited for every workflow.
 * A choice can be relabeled or added; an existing value stays as it is, since stored forms may hold it.
 */
private val SharedFieldSection = FC<SharedFieldSectionProps> { props ->
    val facts = props.facts
    val target = props.target
    val start = props.authored ?: emptyMap()
    val startRows = choiceRowsOf(props.fieldSchema)
    var editing by useState(false)
    var values by useState(editableCopyKeys.associateWith { start[it].toOptStr().orEmpty() })
    var rows by useState(startRows.orEmpty())
    var saving by useState(false)
    var failure by useState<DisplayError?>(null)

    div {
        className = ClassName("dv-edit")
        h3 { +"Shared definition" }
        p {
            className = ClassName("dv-note")
            +usedByText(facts.usedBy)
        }
        variantNote(facts, target.name)?.let {
            p {
                className = ClassName("dv-note")
                +it
            }
        }
        when {
            !facts.canEdit -> facts.refusal?.let {
                p {
                    className = ClassName("dv-note")
                    +it
                }
            }
            !editing -> div {
                className = ClassName("dv-actions")
                Button {
                    size = "small"
                    onClick = { editing = true }
                    +"Edit the shared definition"
                }
            }
            else -> {
                for (key in editableCopyKeys) {
                    div {
                        className = ClassName("dv-edit-row")
                        span {
                            className = ClassName("dv-fact-name")
                            +humanizeFieldName(key)
                        }
                        Input {
                            value = values[key].orEmpty()
                            onChange = { e -> values = values + (key to (e.target.value as String)) }
                        }
                    }
                }
                if (startRows != null) {
                    p {
                        className = ClassName("dv-caption")
                        +"Choices"
                    }
                    rows.forEachIndexed { i, row ->
                        div {
                            className = ClassName("dv-edit-row")
                            if (row.isNew) {
                                Input {
                                    value = row.value
                                    placeholder = "value"
                                    onChange = { e -> rows = rows.mapIndexed { j, r -> if (j == i) ChoiceRow(e.target.value as String, r.label, true) else r } }
                                }
                            } else {
                                span {
                                    className = ClassName("dv-fact-name")
                                    +row.value
                                }
                            }
                            Input {
                                value = row.label
                                placeholder = "label"
                                onChange = { e -> rows = rows.mapIndexed { j, r -> if (j == i) ChoiceRow(r.value, e.target.value as String, r.isNew) else r } }
                            }
                        }
                    }
                    div {
                        className = ClassName("dv-actions")
                        Button {
                            size = "small"
                            type = "link"
                            onClick = { rows = rows + ChoiceRow("", "", isNew = true) }
                            +"Add a choice"
                        }
                    }
                }
                div {
                    className = ClassName("dv-actions")
                    Button {
                        type = "primary"
                        size = "small"
                        loading = saving
                        onClick = {
                            saving = true
                            failure = null
                            designScope.launch {
                                // Only what changed is sent: copy that was not touched sends no entry, so a choices-only
                                // save never writes a layout entry the type's layout did not have.
                                val result = DesignApi.setSharedField(
                                    props.typeName, target.name,
                                    copyEntryFrom(start, target.name, values).takeIf { copyChanged(start, values) },
                                    startRows?.let { sharedOptionsPayload(rows) }, facts.basedOn,
                                )
                                saving = false
                                val refused = result.failureOrNull()
                                if (refused != null) {
                                    failure = userFacingError(refused)
                                } else {
                                    editing = false
                                    props.onSaved()
                                    props.session.afterEdit()
                                }
                            }
                        }
                        +"Save for every workflow"
                    }
                    Button {
                        size = "small"
                        type = "link"
                        onClick = { editing = false }
                        +"Cancel"
                    }
                }
            }
        }
        failure?.let { errorText("Couldn't save the shared definition.", it) }
    }
}
