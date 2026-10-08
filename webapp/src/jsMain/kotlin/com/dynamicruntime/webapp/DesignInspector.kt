package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.offeredChoices
import com.dynamicruntime.common.schema.orderedFieldNames
import com.dynamicruntime.common.util.fmtD
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Fragment
import react.Key
import react.Props
import react.create
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
    suspend fun definition(slot: String, key: String, client: String?): Map<String, Any?> =
        Http.getApi(DSV.definition + queryString(definitionQuery(slot, key, client)))[EP.item].toJsonMapOrEmpty()

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
        client: String?,
    ): ApiResult<Map<String, Any?>> = Http.sendApiResult(
        "POST", DSV.layoutEntryEdit,
        buildMap {
            put(DSV.workflowId, workflowId)
            put(DSV.typeName, typeName)
            put(DSV.field, field)
            entry?.let { put(DSV.entry, it) }
            put(DSV.basedOn, basedOn)
            client?.let { put(DSV.client, it) }
        },
    )

    /**
     * Sets this client's **shared wording** at a fragment key a field's copy, a label or a heading pulls (issues #1010,
     * #1070) -- the Clients page's copy override (#918) -- or, with a null [value], removes the client's stored value.
     * Live, or a draft for a client with a sandbox; the result says which.
     */
    suspend fun setSharedWording(pull: PulledKey, value: String?, client: String?): ApiResult<CopyEditResult> = apiResult {
        val path = if (value == null) CPY.resetPath else CPY.setPath
        parseCopyEditResult(Http.sendApi("POST", path, sharedWordingRequest(pull, value, client))[EP.results].toJsonMapOrEmpty())
    }

    /**
     * Sets a label the workflow owns -- its own, a task's or a save's, as [slot] names -- to [label], clearing the page
     * title when [label] is blank (issue #1070), as an edit of the definition stamped [basedOn].
     */
    suspend fun setLabel(workflowId: String, slot: LabelSlot, label: String?, basedOn: String, client: String?): ApiResult<Map<String, Any?>> =
        Http.sendApiResult("POST", DSV.labelEdit, labelEditBody(workflowId, slot, label, basedOn, client))

    /**
     * Sets [typeName]'s heading to [label] -- the workflow's own when [workflowId] is given, else the shared one in the
     * definition the client declares -- or clears it when [label] is blank (issue #1070), as an edit of the definition
     * stamped [basedOn].
     */
    suspend fun setHeading(workflowId: String?, typeName: String, label: String?, basedOn: String, client: String?): ApiResult<Map<String, Any?>> =
        Http.sendApiResult(
            "POST", if (workflowId != null) DSV.headingEdit else DSV.sharedHeadingEdit,
            headingEditBody(workflowId, typeName, label, basedOn, client),
        )

    /**
     * Sets the fields the workflow's form shows for [typeName] to [fields], in order, or stops choosing them when
     * [fields] is null (issue #1071), as an edit of the definition stamped [basedOn].
     */
    suspend fun setShownFields(workflowId: String, typeName: String, fields: List<String>?, basedOn: String, client: String?): ApiResult<Map<String, Any?>> =
        Http.sendApiResult("POST", DSV.shownFieldsEdit, shownFieldsBody(workflowId, typeName, fields, basedOn, client))

    /**
     * Sets [field] of [typeName] -- in the definition the client declares -- to the layout [entry] and the choices
     * [options], each when given, for every workflow on the client (issue #1029), as an edit of the entry stamped
     * [basedOn]. A refusal comes back as the result's refusal: a stale stamp, or -- for a removed choice stored forms
     * hold -- the impact report ([impactOf]), which [acknowledgeImpact] goes past (issue #1040).
     */
    suspend fun setSharedField(
        typeName: String,
        field: String,
        entry: Map<String, Any?>?,
        options: List<Map<String, Any?>>?,
        basedOn: String,
        acknowledgeImpact: Boolean,
        client: String?,
    ): ApiResult<Map<String, Any?>> = Http.sendApiResult(
        "POST", DSV.sharedFieldEdit, sharedFieldBody(typeName, field, entry, options, basedOn, acknowledgeImpact, client),
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
    // Reads a save has made stale: re-read, with the old one kept on screen meanwhile, so a section the read draws --
    // and what it was just telling the user, a saved note -- does not vanish and come back (issue #1010).
    var stale by useState<Set<String>>(emptySet())
    val cacheKey = address?.let { "${it.slot}|${it.key}" }
    useEffect(cacheKey, rereads) {
        val a = address ?: return@useEffect
        if (cacheKey == null || (cacheKey in loaded && cacheKey !in stale)) return@useEffect
        designScope.launch {
            // A definition that will not load is said in the panel; the page stays usable.
            val result = apiResult { DesignApi.definition(a.slot, a.key, session.client) }
            loaded = loaded + (cacheKey to LoadedDefinition(result.valueOrNull(), result.failureOrNull()?.let { userFacingError(it) }))
            stale = stale - cacheKey
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
        // A save re-reads the panel's definition: its read carries what a label or heading is written as (issue #1070).
        val rereadDefinition = {
            cacheKey?.let { stale = stale + it }
            rereads += 1
        }
        when (selected) {
            null, DesignTarget.Workflow, is DesignTarget.Task, is DesignTarget.Save -> {
                // As the page now shows it -- read from the view, not from the selection, which was made before any
                // edit re-read the view.
                when (selected) {
                    is DesignTarget.Task -> taskSummary(DesignTarget.Task(currentTask(props.view, selected.task)), session)
                    is DesignTarget.Save -> {
                        val task = currentTask(props.view, selected.task)
                        saveSummary(DesignTarget.Save(task, task.saves.firstOrNull { it.id == selected.save.id } ?: selected.save))
                    }
                    else -> workflowSummary(props.view, selected == null, session)
                }
                // The label the workflow owns (issue #1070), once the definition read has answered: it says what the
                // label is written as, and whether it pulls shared wording.
                val slot = labelSlotOf(selected)
                if (slot != null && definition?.response != null) {
                    val shown = shownLabel(props.view, slot)
                    LabelSection {
                        key = "${selected?.id ?: "wf"}|${design.basedOn}".unsafeCast<Key>()
                        this.session = session
                        this.slot = slot
                        this.read = definition.response
                        this.shown = shown
                        onSaved = rereadDefinition
                    }
                    pulledLabelOf(definition.response, slot)?.let { pulled ->
                        SharedWordingSection {
                            key = "${selected?.id ?: "wf"}|wording".unsafeCast<Key>()
                            this.session = session
                            this.subject = "This label"
                            this.slots = listOf(WordingSlot(slot.title, pulled, shown))
                            this.refusal = sharedWordingRefusalOf(definition.response)
                            onSaved = rereadDefinition
                        }
                    }
                }
            }
            is DesignTarget.Trait -> {
                // The trait as the page now draws it, its heading included (see the field case below).
                val trait = props.view.tasks.flatMap { it.traits }.firstOrNull { it.traitId == selected.trait.traitId } ?: selected.trait
                traitSummary(DesignTarget.Trait(trait))
                // Which fields the form shows (issue #1071): the workflow's own choice, from the block.
                FieldsShownSection {
                    key = "${selected.id}|fields|${design.basedOn}".unsafeCast<Key>()
                    this.session = session
                    this.trait = trait
                }
                if (definition?.response != null && address != null) {
                    HeadingSection {
                        key = "${selected.id}|${design.basedOn}".unsafeCast<Key>()
                        this.session = session
                        this.trait = trait
                        this.read = definition.response
                        this.typeBody = subtreeAt(definition.response[DSV.entry].toJsonMapOrEmpty(), address.path)
                        onSaved = rereadDefinition
                    }
                }
            }
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
                        pulled = pulledCopyOf(definition.response, owner.typeName, selected.name)
                    }
                } else if (design.editRefusal != null) {
                    // Not editable here: what the workflow's own copy is, if it has one -- and why there is no control,
                    // so the missing one reads as a rule rather than a fault.
                    val edit = design.layoutEdit(owner.typeName, selected.name)
                    div {
                        className = ClassName("dv-edit")
                        overrideFacts(edit)
                        edit?.let { sharedCopyFacts(it.inherited, pulledCopyOf(definition?.response, owner.typeName, selected.name)) }
                        p {
                            className = ClassName("dv-note")
                            +design.editRefusal
                        }
                    }
                }
                // Copy pulled from fragment files (issue #1010): the client's shared wording at each key, editable whatever
                // the definition's origin, since a copy override is the client's own data.
                val pulled = pulledCopyOf(definition?.response, owner.typeName, selected.name)
                if (pulled.isNotEmpty()) {
                    val shown = props.view.fieldLayouts[owner.typeName]?.fieldFor(selected.name)
                    SharedWordingSection {
                        key = "${selected.id}|wording".unsafeCast<Key>()
                        this.session = session
                        this.subject = "This field's copy"
                        this.slots = editableCopyKeys.mapNotNull { k -> pulled[k]?.let { WordingSlot(humanizeFieldName(k), it, shownCopy(shown, k)) } }
                        this.refusal = sharedWordingRefusalOf(definition?.response)
                        onSaved = rereadDefinition
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
                        this.pulled = pulled
                        // The read is cached per definition, so a shared save -- which changes it -- drops it to re-read.
                        onSaved = rereadDefinition
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
            // Yes or no on editing the workflow's own copy is the block's decision (issue #1013) -- a field's, a label's,
            // a heading's (issue #1070).
            definitionSection(address, definition, selected, editable = design.canEdit)
        }
    }
}

/** A definition read's outcome: the response, or why there is none. */
private class LoadedDefinition(val response: Map<String, Any?>?, val error: DisplayError?)

/** Where [target] is declared: its type's address from the backend's block, extended to the field's place in it. */
private fun addressOf(target: DesignTarget, design: WfDesign): DesignAddress? = when (target) {
    DesignTarget.Workflow, is DesignTarget.Task, is DesignTarget.Save -> design.workflow
    is DesignTarget.Trait -> design.types[target.trait.typeName]
    is DesignTarget.Field -> {
        val owner = fieldOwner(target.root.typeName, target.root.type, target.path)
        design.types[owner.typeName]?.below(owner.schemaPath)
    }
}

private fun ChildrenBuilder.workflowSummary(view: WorkflowView, nothingSelected: Boolean, session: DesignSession) {
    h2 { +view.label.ifBlank { humanizeFieldName(view.workflowId) } }
    fact("Workflow", view.workflowId, mono = true)
    fact("Kind", view.entry)
    // Every task, whether or not the page draws its name (a one-task form shows none): each opens its own label.
    pickList("Tasks", view.tasks.map { t -> t.label.ifBlank { t.id } to { session.select(DesignTarget.Task(t)) } })
    if (nothingSelected) {
        p {
            className = ClassName("dv-note")
            +"Click any outlined part of the form — the title, a section or a field — to see what defines it."
        }
    }
}

private fun ChildrenBuilder.taskSummary(target: DesignTarget.Task, session: DesignSession) {
    val task = target.task
    h2 { +task.label.ifBlank { task.id } }
    fact("Task", task.id, mono = true)
    fact("Collects", task.traits.joinToString(", ") { it.traitId }.ifEmpty { "nothing" }, mono = true)
    // Its saves, which the page draws only while the form is being filled in: each opens its button's label.
    if (task.saves.isNotEmpty()) pickList("Saves", task.saves.map { sv -> sv.label.ifBlank { sv.id } to { session.select(DesignTarget.Save(task, sv)) } })
}

private fun ChildrenBuilder.saveSummary(target: DesignTarget.Save) {
    val save = target.save
    h2 { +save.label.ifBlank { save.id } }
    fact("Save", save.id, mono = true)
    fact("Of task", target.task.id, mono = true)
    fact("Kind", save.kind)
}

/** A short list of parts of the page, each a link that selects it -- for parts the page may not draw. */
private fun ChildrenBuilder.pickList(name: String, items: List<Pair<String, () -> Unit>>) {
    div {
        className = ClassName("dv-fact")
        span {
            className = ClassName("dv-fact-name")
            +name
        }
        span {
            className = ClassName("dv-fact-value")
            items.forEachIndexed { i, (label, pick) ->
                if (i > 0) +", "
                Button {
                    size = "small"
                    type = "link"
                    onClick = { pick() }
                    +label
                }
            }
        }
    }
}

/** A heading -- Markdown, perhaps several lines -- as one line of plain words: its first, without the `#` marks. */
private fun headingLine(markdown: String): String = markdown.lineSequence().firstOrNull()?.trimStart('#', ' ').orEmpty()

/** [task] as [view] now has it, or [task] itself when the view no longer carries it. */
private fun currentTask(view: WorkflowView, task: WfTaskView): WfTaskView = view.tasks.firstOrNull { it.id == task.id } ?: task

/** The label [slot] names as the page shows it -- resolved, its pulls filled in -- from the view. */
private fun shownLabel(view: WorkflowView, slot: LabelSlot): String {
    val task = slot.taskId?.let { id -> view.tasks.firstOrNull { it.id == id } } ?: return view.label
    return slot.saveId?.let { id -> task.saves.firstOrNull { it.id == id }?.label.orEmpty() } ?: task.label
}

private fun ChildrenBuilder.traitSummary(target: DesignTarget.Trait) {
    val trait = target.trait
    h2 { +(trait.fieldLayout?.label?.let(::headingLine) ?: trait.type.title ?: humanizeFieldName(trait.traitId)) }
    fact("Trait", trait.traitId, mono = true)
    fact("Data type", trait.typeName, mono = true)
    fact("In this task", if (trait.required) "required" else "optional")
    fact("Fields", trait.type.properties.size.toString())
}

/** Choices as the inspector lists them: each value, with its label when that says something more. */
private fun choicesText(options: List<SchOption>): String =
    options.joinToString(", ") { if (it.label == it.value) it.value else "${it.value} (${it.label})" }

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
    fact("Required by the data", if (target.required) "yes" else "no")
    // What this form asks for beyond the schema (issue #1022), from the layout entry the page draws it with.
    if (layout?.required == true) fact("Required on this form", "yes")
    if (vt.minBound != null || vt.maxBound != null) {
        fact("Range", listOfNotNull(vt.minBound?.let { "from ${it.fmtD()}" }, vt.maxBound?.let { "to ${it.fmtD()}" }).joinToString(" "))
    }
    vt.options?.let { options ->
        fact("Choices", choicesText(options))
    }
    if (layout?.choices != null) {
        fact("Choices on this form", choicesText(offeredChoices(vt, layout).orEmpty()))
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
private fun ChildrenBuilder.sharedCopyFacts(inherited: Map<String, Any?>?, pulled: Map<String, PulledSlot>) {
    // A pulled slot reads as its words, naming the shared wording, never as its template (issue #1010).
    val copy = editableCopyKeys.mapNotNull { key -> inherited?.get(key).toOptStr()?.let { key to inheritedCopyText(it, pulled[key]) } }
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
    /** The field's copy slots that pull a fragment key (issue #1010), so an inherited pull reads as its words. */
    var pulled: Map<String, PulledSlot>?
}

/**
 * The workflow's own copy for one field (issue #984): its label, description and hint, edited as a small form or as
 * the layout entry's JSON, saved as the workflow's override of the shared copy -- never the shared definition, which
 * every other workflow draws from too. Shows what the override replaces, says when the shared copy has changed since,
 * and offers **Reset to shared**. A save is the server's to refuse (a stale page, an entry the layout checks reject),
 * and the refusal is said here.
 *
 * Beside the copy, the form's own requirements (issue #1048, the keys of #1022): **Required on this form**, and for a
 * field with a closed list, **Choices on this form** -- which of the schema's choices this form offers, and its label
 * for each. They save into the same entry, so changing one keeps the copy, and Reset removes them with it.
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
    var required by useState(start[SL.required] == true)
    var choiceRows by useState(formChoiceRowsOf(target.prop.valueType, start))
    var jsonText by useState(start.toJsonStr())
    var saving by useState(false)
    var failure by useState<DisplayError?>(null)

    fun save(entry: Map<String, Any?>?) {
        saving = true
        failure = null
        designScope.launch {
            val result = DesignApi.setLayoutEntry(session.workflowId, owner.typeName, target.name, entry, session.design.basedOn, session.client)
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
                        // Blank, the page shows the shared copy -- or, with none, the field's own (issue #1039).
                        val sharedValue = shared?.get(key).toOptStr()?.let { inheritedCopyText(it, props.pulled?.get(key)) }
                        val fallback = copyFallback(key, target.name, target.prop)
                        Input {
                            value = values[key].orEmpty()
                            placeholder = sharedValue ?: fallback.text
                            onChange = { e -> values = values + (key to (e.target.value as String)) }
                        }
                        if (sharedValue != null) {
                            p {
                                className = ClassName("dv-shared")
                                +"Shared: $sharedValue"
                            }
                        } else if (values[key].isNullOrBlank()) {
                            p {
                                className = ClassName("dv-fallback")
                                +fallback.note
                            }
                        }
                    }
                }
                formRequirementControls(
                    target, required, choiceRows,
                    onRequired = { required = it },
                    onRows = { choiceRows = it },
                )
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
                            val problem = formChoicesProblem(choiceRows)
                            if (problem != null) {
                                failure = DisplayError.expected(problem)
                            } else {
                                save(formEntryFrom(start, target.name, values, required, choiceRows))
                            }
                        }
                    }
                    +"Save"
                }
                Button {
                    size = "small"
                    onClick = {
                        if (!asJson) jsonText = formEntryFrom(start, target.name, values, required, choiceRows).toJsonStr()
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

/**
 * The workflow copy editor's form requirements (issue #1048): a **Required on this form** checkbox -- shown checked and
 * locked when the data itself requires the field, and replaced by the reason when no form may require it -- and, for a
 * field with a closed list, one row per schema choice: offered or not, with the form's label for it, filled in from the
 * schema's to be changed. The editor keeps the choices' order; a list ordered by hand keeps its order.
 */
private fun ChildrenBuilder.formRequirementControls(
    target: DesignTarget.Field,
    required: Boolean,
    rows: List<FormChoiceRow>?,
    onRequired: (Boolean) -> Unit,
    onRows: (List<FormChoiceRow>) -> Unit,
) {
    p {
        className = ClassName("dv-caption")
        +"Form requirements \u2014 what this form asks for beyond the data"
    }
    val unavailable = formRequiredUnavailable(target.prop)
    if (unavailable != null) {
        p {
            className = ClassName("dv-note")
            +unavailable
        }
    } else {
        div {
            className = ClassName("dv-actions")
            Checkbox {
                // Already required by the data: nothing for the form to add, so shown but not changeable.
                checked = required || target.required
                disabled = target.required
                onChange = { e -> onRequired(e.target.checked == true) }
                +(if (target.required) "Required on this form (the data requires it)" else "Required on this form")
            }
        }
    }
    if (rows == null) return
    p {
        className = ClassName("dv-caption")
        +"Choices on this form"
    }
    rows.forEachIndexed { i, row ->
        div {
            className = ClassName("dv-edit-row")
            Checkbox {
                checked = row.offered
                onChange = { e -> onRows(rows.mapIndexed { j, r -> if (j == i) FormChoiceRow(r.value, r.schemaLabel, e.target.checked == true, r.label) else r }) }
                +row.value
            }
            Input {
                value = row.label
                placeholder = row.schemaLabel
                disabled = !row.offered
                onChange = { e -> onRows(rows.mapIndexed { j, r -> if (j == i) FormChoiceRow(r.value, r.schemaLabel, r.offered, e.target.value as String) else r }) }
            }
        }
    }
}

/** One copy slot that pulls shared wording (issue #1010): what it is called, its keys, and its words as shown. */
class WordingSlot(val name: String, val slot: PulledSlot, val shown: String?)

external interface SharedWordingSectionProps : Props {
    var session: DesignSession
    /** What the wording is, for the opening sentence: "This field's copy", "This label", "This heading". */
    var subject: String
    /** The copy slots that pull shared wording, from the definition read. */
    var slots: List<WordingSlot>
    /** Why the wording may not be changed from here (a client with a sandbox is changed from it), or null. */
    var refusal: String?
    var onSaved: () -> Unit
}

/**
 * **Shared wording** (issue #1010): each fragment key a copy slot pulls, edited at the key -- this client's copy
 * override (#918), the Clients page's path -- so it changes everywhere the key is used, in every workflow. The user
 * sees words, never the `%{@t(...)}` template; a slot that mixes a pull with other text is shown as composed, with
 * each key it pulls editable beneath. Offered whatever the definition's origin: an override is the client's own data
 * even for a definition declared globally or in source. A field's copy, a label and a heading (issue #1070) alike.
 */
private val SharedWordingSection = FC<SharedWordingSectionProps> { props ->
    div {
        className = ClassName("dv-edit")
        h3 { +"Shared wording" }
        p {
            className = ClassName("dv-note")
            +"${props.subject} comes from shared wording. Changing it changes this client's wording wherever it is used."
        }
        props.refusal?.let {
            p {
                className = ClassName("dv-note")
                +it
            }
        }
        for (wording in props.slots) {
            val pulled = wording.slot
            if (pulled.mixed) {
                div {
                    className = ClassName("dv-edit-row")
                    span {
                        className = ClassName("dv-fact-name")
                        +wording.name
                    }
                    span { +wording.shown.orEmpty() }
                    p {
                        className = ClassName("dv-fallback")
                        +"Combines shared wording with other text; the shared part is below."
                    }
                }
            }
            for (pull in pulled.pulls) {
                SharedWordingRow {
                    key = "${wording.name}|${pull.fileId}|${pull.name}".unsafeCast<Key>()
                    this.session = props.session
                    this.label = if (pulled.mixed) pull.name else wording.name
                    this.pull = pull
                    this.editable = props.refusal == null
                    this.onSaved = props.onSaved
                }
            }
        }
    }
}

/** [field]'s copy in [slot], as the page shows it. */
private fun shownCopy(field: SchLayoutField?, slot: String): String? = when (slot) {
    SL.label -> field?.label
    SL.description -> field?.description
    else -> field?.hint
}

external interface SharedWordingRowProps : Props {
    var session: DesignSession
    var label: String
    var pull: PulledKey
    /** False where the wording is changed elsewhere (the client's sandbox): the words and their source, no controls. */
    var editable: Boolean
    var onSaved: () -> Unit
}

/** One key's shared wording: its words, where they come from, and **Save shared wording** / reset (issue #1010). */
private val SharedWordingRow = FC<SharedWordingRowProps> { props ->
    val pull = props.pull
    var value by useState(pull.value.orEmpty())
    var saving by useState(false)
    var note by useState<String?>(null)
    var failure by useState<DisplayError?>(null)

    fun save(newValue: String?) {
        saving = true
        failure = null
        note = null
        designScope.launch {
            val result = DesignApi.setSharedWording(pull, newValue, props.session.client)
            saving = false
            val refused = result.failureOrNull()
            if (refused != null) {
                failure = userFacingError(refused)
            } else {
                note = savedNote(if (newValue == null) "Back to the earlier wording." else "Saved.", result.valueOrNull()?.mode ?: EDM.live)
                props.onSaved()
                props.session.afterEdit()
            }
        }
    }

    div {
        className = ClassName("dv-edit-row")
        span {
            className = ClassName("dv-fact-name")
            +props.label
        }
        Input {
            this.value = value
            placeholder = pull.baseValue
            disabled = !props.editable
            onChange = { e -> value = e.target.value as String }
        }
        p {
            className = ClassName("dv-shared")
            +pulledSourceLine(pull)
        }
    }
    if (props.editable) div {
        className = ClassName("dv-actions")
        Button {
            type = "primary"
            size = "small"
            loading = saving
            disabled = value.trim().isEmpty() || value == pull.value.orEmpty()
            onClick = { save(value) }
            +"Save shared wording"
        }
        pulledResetLabel(pull)?.let { resetLabel ->
            Button {
                size = "small"
                type = "link"
                disabled = saving
                onClick = { save(null) }
                +resetLabel
            }
        }
    }
    note?.let {
        p {
            className = ClassName("dv-note")
            +it
        }
    }
    failure?.let { errorText("Couldn't save the shared wording.", it) }
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
    /** The field's copy slots that pull a fragment key (issue #1010): shown, not offered, here. */
    var pulled: Map<String, PulledSlot>?
    var onSaved: () -> Unit
}

/**
 * The field's **shared** definition (issue #1029): where it is used, which workflows keep their own copy of it, and --
 * opened deliberately, never as an option beside a workflow's Save -- its copy and choices edited for every workflow.
 * A choice can be relabeled, added or removed; an existing value is not edited in place -- a change of value is a
 * removal and an addition. A save that removes a choice stored forms hold is refused with the impact report, shown in
 * a dialog with **Save anyway** (issue #1040).
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
    var impact by useState<ImpactReportView?>(null)
    val copyRefusal = facts.copyRefusals[target.name]
    val removing = removedChoiceValues(startRows, rows)

    fun save(acknowledge: Boolean) {
        saving = true
        failure = null
        designScope.launch {
            // Only what changed is sent: copy that was not touched sends no entry, so a choices-only save never writes
            // a layout entry the type's layout did not have.
            val result = DesignApi.setSharedField(
                props.typeName, target.name,
                copyEntryFrom(start, target.name, values).takeIf { copyChanged(start, values) },
                startRows?.let { sharedOptionsPayload(rows) }, facts.basedOn, acknowledgeImpact = acknowledge,
                client = props.session.client,
            )
            saving = false
            val refused = result.failureOrNull()
            val refusedOver = refused?.let { impactOf(it) }
            when {
                refusedOver != null -> impact = refusedOver
                refused != null -> {
                    impact = null
                    failure = userFacingError(refused)
                }
                else -> {
                    impact = null
                    editing = false
                    props.onSaved()
                    props.session.afterEdit()
                }
            }
        }
    }

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
            // Nothing to edit: copy it cannot take, and no choices (issue #1039).
            copyRefusal != null && startRows == null -> p {
                className = ClassName("dv-note")
                +copyRefusal
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
                // Grouped by what an edit changes (issue #1039): wording forms show, and what the field accepts.
                p {
                    className = ClassName("dv-caption")
                    +"Form copy \u2014 what forms show for this field"
                }
                copyRefusal?.let {
                    p {
                        className = ClassName("dv-note")
                        +it
                    }
                }
                for (key in editableCopyKeys) {
                    // A blank input is the field's own wording, not nothing: say what the form shows instead.
                    val fallback = copyFallback(key, target.name, target.prop)
                    // A slot pulled from a fragment file is shared wording, edited at its key in its own section
                    // (issue #1010); overwriting the pull with literal text here would silently drop it.
                    props.pulled?.get(key)?.let { slot ->
                        div {
                            className = ClassName("dv-edit-row")
                            span {
                                className = ClassName("dv-fact-name")
                                +humanizeFieldName(key)
                            }
                            span { +inheritedCopyText(start[key].toOptStr().orEmpty(), slot) }
                            p {
                                className = ClassName("dv-fallback")
                                +"Shared wording: change it under Shared wording."
                            }
                        }
                        continue
                    }
                    div {
                        className = ClassName("dv-edit-row")
                        span {
                            className = ClassName("dv-fact-name")
                            +humanizeFieldName(key)
                        }
                        Input {
                            value = values[key].orEmpty()
                            placeholder = fallback.text
                            disabled = copyRefusal != null
                            onChange = { e -> values = values + (key to (e.target.value as String)) }
                        }
                        if (values[key].isNullOrBlank()) {
                            p {
                                className = ClassName("dv-fallback")
                                +fallback.note
                            }
                        }
                    }
                }
                if (startRows != null) {
                    p {
                        className = ClassName("dv-caption")
                        +"Choices \u2014 what the field accepts"
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
                            div {
                                className = ClassName("dv-choice-label")
                                Input {
                                    value = row.label
                                    placeholder = "label"
                                    onChange = { e -> rows = rows.mapIndexed { j, r -> if (j == i) ChoiceRow(r.value, e.target.value as String, r.isNew) else r } }
                                }
                                Button {
                                    size = "small"
                                    type = "link"
                                    // A field with choices keeps at least one.
                                    disabled = rows.size <= 1
                                    asDynamic()["aria-label"] = "Remove ${row.value.ifBlank { "this choice" }}"
                                    onClick = { rows = rows.filterIndexed { j, _ -> j != i } }
                                    +"Remove"
                                }
                            }
                        }
                    }
                    removingNote(removing)?.let {
                        p {
                            className = ClassName("dv-note")
                            +it
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
                    p {
                        className = ClassName("dv-caption")
                        +"To change a value, remove the choice and add a new one."
                    }
                }
                div {
                    className = ClassName("dv-actions")
                    Button {
                        type = "primary"
                        size = "small"
                        loading = saving
                        onClick = { save(acknowledge = false) }
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
    // The stored forms a removal would leave invalid (issue #1040), with the way past it.
    Modal {
        open = impact != null
        title = "Some stored forms hold ${if (removing.size == 1) "this choice" else "these choices"}"
        onCancel = { impact = null }
        footer = Fragment.create {
            Button {
                onClick = { impact = null }
                +"Cancel"
            }
            Button {
                type = "primary"
                danger = true
                loading = saving
                onClick = { save(acknowledge = true) }
                +"Save anyway"
            }
        }
        impact?.let { impactReportBody(it, formsOpenable = true, doing = removingPhrase(removing)) }
    }
}

external interface LabelSectionProps : Props {
    var session: DesignSession
    var slot: LabelSlot
    /** The workflow's definition read: what the label is written as, and whether it pulls shared wording. */
    var read: Map<String, Any?>
    /** The label as the page shows it. */
    var shown: String
    var onSaved: () -> Unit
}

/**
 * A label the workflow owns (issue #1070) -- its own (the page title), a task's, or a save's button -- edited in the
 * workflow's definition in place: the workflow owns it, so there is no shared level beneath to reset to. A label that
 * pulls a fragment key is shared wording, edited at the key in its own section, and not overwritten here. Offered where
 * the workflow's own copy is (the block's `canEdit`); elsewhere the block's reason is said.
 */
private val LabelSection = FC<LabelSectionProps> { props ->
    val session = props.session
    val design = session.design
    val slot = props.slot
    val written = writtenLabel(props.read, slot)
    val pulled = pulledLabelOf(props.read, slot)
    val fallback = labelFallback(slot, session.isEdit)
    var editing by useState(false)
    var value by useState(written.orEmpty())
    var saving by useState(false)
    var failure by useState<DisplayError?>(null)

    fun save() {
        saving = true
        failure = null
        designScope.launch {
            val result = DesignApi.setLabel(session.workflowId, slot, value, design.basedOn, session.client)
            saving = false
            val refused = result.failureOrNull()
            if (refused != null) {
                failure = userFacingError(refused)
            } else {
                editing = false
                props.onSaved()
                session.afterEdit()
            }
        }
    }

    div {
        className = ClassName("dv-edit")
        h3 { +slot.title }
        if (slot.optional) {
            p {
                className = ClassName("dv-note")
                +"The page title is also this workflow's name in form listings and on lock notices."
            }
        }
        when {
            // Shared wording: its own section below edits it at the key; replacing it with literal text here would
            // silently drop the pull.
            pulled != null -> p {
                className = ClassName("dv-fallback")
                +"This label is shared wording: change it under Shared wording."
            }
            !design.canEdit -> {
                fact("Shown", props.shown.ifBlank { fallback.text ?: "none" })
                design.editRefusal?.let {
                    p {
                        className = ClassName("dv-note")
                        +it
                    }
                }
            }
            !editing -> {
                fact("Shown", props.shown.ifBlank { fallback.text ?: "none" })
                div {
                    className = ClassName("dv-actions")
                    Button {
                        size = "small"
                        onClick = {
                            value = written.orEmpty()
                            editing = true
                        }
                        +"Edit"
                    }
                }
            }
            else -> {
                div {
                    className = ClassName("dv-edit-row")
                    span {
                        className = ClassName("dv-fact-name")
                        +"Label"
                    }
                    Input {
                        this.value = value
                        placeholder = fallback.text
                        onChange = { e -> value = e.target.value as String }
                    }
                    if (value.isBlank()) {
                        p {
                            className = ClassName("dv-fallback")
                            +fallback.note
                        }
                    }
                }
                div {
                    className = ClassName("dv-actions")
                    Button {
                        type = "primary"
                        size = "small"
                        loading = saving
                        // A task's and a save's label are required: blank is no label at all.
                        disabled = !slot.optional && value.isBlank()
                        onClick = { save() }
                        +"Save"
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
        }
        failure?.let { errorText("Couldn't save the label.", it) }
    }
}

external interface HeadingSectionProps : Props {
    var session: DesignSession
    var trait: WfTraitView
    /** The definition read of the trait or type: its shared facts, and whether its heading pulls shared wording. */
    var read: Map<String, Any?>
    /** The type's body in that definition's authored entry, where its shared heading is written. */
    var typeBody: Any?
    var onSaved: () -> Unit
}

/**
 * A trait section's **heading** (issue #1070): the `label` of its data type's layout, at the two levels a field's copy
 * has -- the workflow's own, written as its alteration of the type, with **Reset to shared**; and, behind its own
 * button, the shared heading in the definition the client declares, for every workflow. A heading that pulls a fragment
 * key is shared wording, edited at the key.
 */
private val HeadingSection = FC<HeadingSectionProps> { props ->
    val session = props.session
    val design = session.design
    val trait = props.trait
    val typeName = trait.typeName
    val edit = design.headingEdits[typeName]
    val sharedWritten = headingIn(props.typeBody)
    val pulled = pulledHeadingOf(props.read, typeName)
    val facts = parseSharedFacts(props.read)
    val fallback = headingFallback(trait)
    // The shared heading as words: a pull reads as its key's words, naming the key.
    val sharedText = (edit?.inherited ?: sharedWritten)?.let { inheritedCopyText(it, pulled) }
    var editingOwn by useState(false)
    var editingShared by useState(false)
    var value by useState("")
    var saving by useState(false)
    var failure by useState<DisplayError?>(null)

    fun save(workflowId: String?, label: String?, basedOn: String) {
        saving = true
        failure = null
        designScope.launch {
            val result = DesignApi.setHeading(workflowId, typeName, label, basedOn, session.client)
            saving = false
            val refused = result.failureOrNull()
            if (refused != null) {
                failure = userFacingError(refused)
            } else {
                editingOwn = false
                editingShared = false
                props.onSaved()
                session.afterEdit()
            }
        }
    }

    fun ChildrenBuilder.headingInput(placeholderText: String?, note: String?) {
        div {
            className = ClassName("dv-edit-row")
            span {
                className = ClassName("dv-fact-name")
                +"Heading"
            }
            Input {
                this.value = value
                placeholder = placeholderText
                onChange = { e -> value = e.target.value as String }
            }
            note?.let {
                p {
                    className = ClassName(if (value.isBlank()) "dv-fallback" else "dv-shared")
                    +it
                }
            }
        }
    }

    div {
        className = ClassName("dv-edit")
        h3 { +"Heading for this workflow" }
        fact("Shown", trait.fieldLayout?.label?.let(::headingLine) ?: fallback.text.orEmpty())
        if (edit != null) {
            p {
                className = ClassName("dv-note")
                +"This workflow uses its own heading here; every other workflow shows the shared one."
            }
            if (edit.inheritedChanged) {
                p {
                    className = ClassName("dv-callout")
                    +"The shared heading has changed since this workflow overrode it."
                }
            }
        }
        when {
            !design.canEdit -> design.editRefusal?.let {
                p {
                    className = ClassName("dv-note")
                    +it
                }
            }
            !editingOwn -> div {
                className = ClassName("dv-actions")
                Button {
                    size = "small"
                    disabled = editingShared
                    onClick = {
                        value = edit?.label.orEmpty()
                        editingOwn = true
                    }
                    +(if (edit == null) "Override for this workflow" else "Edit")
                }
                if (edit != null) {
                    Button {
                        size = "small"
                        type = "link"
                        loading = saving
                        onClick = { save(session.workflowId, null, design.basedOn) }
                        +"Reset to shared"
                    }
                }
            }
            else -> {
                // Blank goes back to the shared heading -- or, with none, the section's own title (issue #1039's rule).
                headingInput(
                    sharedText ?: fallback.text,
                    if (value.isBlank()) sharedText?.let { "Blank: the shared heading, \"$it\"." } ?: fallback.note
                    else sharedText?.let { "Shared: $it" },
                )
                div {
                    className = ClassName("dv-actions")
                    Button {
                        type = "primary"
                        size = "small"
                        loading = saving
                        onClick = { save(session.workflowId, value, design.basedOn) }
                        +"Save"
                    }
                    Button {
                        size = "small"
                        type = "link"
                        onClick = { editingOwn = false }
                        +"Cancel"
                    }
                }
            }
        }
        // The shared heading (issue #1029's editor, for the type as a whole): opened deliberately.
        if (facts != null) {
            h3 { +"Shared heading" }
            p {
                className = ClassName("dv-note")
                +usedByText(facts.usedBy)
            }
            when {
                !facts.canEdit -> facts.refusal?.let {
                    p {
                        className = ClassName("dv-note")
                        +it
                    }
                }
                pulled != null -> p {
                    className = ClassName("dv-fallback")
                    +"The shared heading is shared wording: change it under Shared wording."
                }
                !editingShared -> div {
                    className = ClassName("dv-actions")
                    Button {
                        size = "small"
                        disabled = editingOwn
                        onClick = {
                            value = sharedWritten.orEmpty()
                            editingShared = true
                        }
                        +"Edit the shared heading"
                    }
                }
                else -> {
                    headingInput(fallback.text, if (value.isBlank()) fallback.note else null)
                    div {
                        className = ClassName("dv-actions")
                        Button {
                            type = "primary"
                            size = "small"
                            loading = saving
                            onClick = { save(null, value, facts.basedOn) }
                            +"Save for every workflow"
                        }
                        Button {
                            size = "small"
                            type = "link"
                            onClick = { editingShared = false }
                            +"Cancel"
                        }
                    }
                }
            }
        }
        failure?.let { errorText("Couldn't save the heading.", it) }
    }
    pulled?.let {
        SharedWordingSection {
            this.session = session
            this.subject = "This heading"
            this.slots = listOf(WordingSlot("Heading", it, trait.fieldLayout?.label))
            this.refusal = sharedWordingRefusalOf(props.read)
            onSaved = props.onSaved
        }
    }
}

external interface FieldsShownSectionProps : Props {
    var session: DesignSession
    /** The trait as the page now draws it. */
    var trait: WfTraitView
}

/**
 * Which of a trait's fields this workflow's form shows (issue #1071). Off by default -- the form shows what the shared
 * layout does. **Choose which fields this form shows** writes the fields shown now as the workflow's own list, so
 * nothing changes until one is unchecked; a checklist of every field the type declares, filterable for a long
 * questionnaire, then picks them -- checking adds a field at the end, unchecking takes it off -- saved together. A
 * field the data requires, or that this form requires, cannot be taken off, and says why. Turning the choice off goes
 * back to the shared layout's fields and keeps any copy. The page goes on drawing only the form as shown: the full set
 * lives here.
 */
private val FieldsShownSection = FC<FieldsShownSectionProps> { props ->
    val session = props.session
    val design = session.design
    val trait = props.trait
    val own = design.shownFields[trait.typeName]
    // What the form shows now -- the start of the list when the choice is turned on.
    val showing = orderedFieldNames(trait.type, trait.fieldLayout)
    var chosen by useState(own ?: showing)
    var filter by useState("")
    var saving by useState(false)
    var failure by useState<DisplayError?>(null)

    fun save(fields: List<String>?) {
        saving = true
        failure = null
        designScope.launch {
            val result = DesignApi.setShownFields(session.workflowId, trait.typeName, fields, design.basedOn, session.client)
            saving = false
            result.failureOrNull()?.let { failure = userFacingError(it) } ?: session.afterEdit()
        }
    }

    div {
        className = ClassName("dv-edit")
        h3 { +"Fields on this form" }
        fact("Shows", "${showing.size} of ${trait.type.properties.size} fields")
        if (!design.canEdit) {
            design.editRefusal?.let {
                p {
                    className = ClassName("dv-note")
                    +it
                }
            }
        } else {
            div {
                className = ClassName("dv-actions")
                Checkbox {
                    checked = own != null
                    disabled = saving
                    onChange = { e -> save(if (e.target.checked == true) showing else null) }
                    +"Choose which fields this form shows"
                }
            }
            if (own == null) {
                p {
                    className = ClassName("dv-fallback")
                    +"Off: the form shows what the shared layout does."
                }
            } else {
                p {
                    className = ClassName("dv-note")
                    +"This form's save writes only the fields it shows; the form's other answers are kept as they are."
                }
                val rows = shownFieldRows(trait.type, trait.fieldLayout, chosen)
                if (rows.size > 8) {
                    Input {
                        value = filter
                        placeholder = "Filter the fields"
                        onChange = { e -> filter = e.target.value as String }
                    }
                }
                for (row in rows.filter { shownFieldMatches(it, filter) }) {
                    div {
                        key = row.name.unsafeCast<Key>()
                        className = ClassName("dv-edit-row")
                        Checkbox {
                            checked = row.shown
                            disabled = row.locked != null
                            onChange = { e -> chosen = if (e.target.checked == true) chosen + row.name else chosen - row.name }
                            +row.label
                        }
                        span {
                            className = ClassName("dv-fact-value mono")
                            +row.name
                        }
                        row.locked?.let {
                            p {
                                className = ClassName("dv-fallback")
                                +it
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
                        disabled = chosen == own || chosen.isEmpty()
                        onClick = { save(chosen) }
                        +"Save"
                    }
                    Button {
                        size = "small"
                        type = "link"
                        disabled = chosen == own
                        onClick = { chosen = own }
                        +"Revert"
                    }
                }
            }
        }
        failure?.let { errorText("Couldn't change the fields this form shows.", it) }
    }
}

