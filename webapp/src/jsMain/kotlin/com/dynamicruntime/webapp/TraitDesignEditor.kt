package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.TraitChoiceDesign
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Fragment
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useMemo
import react.useState
import web.cssom.ClassName

private val designScope = MainScope()

external interface TraitDesignEditorProps : Props {
    var clientId: String
    /** The trait as stored, which the draft starts from; null for a new trait. */
    var baseline: TraitDesign?
    var quotas: TraitQuotasView?
    /** Whether a save is a draft, as the page last read the client ([savesAsDraft]): said beside Save, and sent with it. */
    var savesDraft: Boolean
    /** Whether the impact report's forms can be opened from this session ([impactFormsOpenable]). */
    var formsOpenable: Boolean
    /** What a save calls once it has taken effect, with what it said: the editor closes, and the page re-reads. */
    var onSaved: (String) -> Unit
    var onCancel: () -> Unit
    /** What a refusal for no longer taking effect as said calls ([saveConflict]): the page re-reads. */
    var onChanged: () -> Unit
}

/**
 * The traits editor (issue #1118): a client's own trait designed from a fixed set of field kinds -- its name and
 * description, then each field's label, kind, whether the data requires it, and a choice field's choices -- beside a
 * **live preview**, the form [SchemaForm] draws from the draft's schema, parsed by the kernel as the backend will
 * write it ([previewDefs]).
 *
 * Keys are made by the backend from first labels and never change, so a saved field may change its label freely but
 * its kind only between text and long text ([kindsFor]). What is wrong with the draft is said under it, in the
 * kernel's words ([draftProblems]), and Save waits for it to be right; the plan's limits are said beside the fields
 * ([draftQuotaText]) and **Add field** stops at them. A save takes effect as the page's other editors' do -- live, or a
 * draft for a client with a sandbox -- and an edit that would strand data stored forms hold is refused with the impact
 * report, whose dialog's **Save anyway** acknowledges it.
 */
val TraitDesignEditor = FC<TraitDesignEditorProps> { props ->
    var draft by useState(props.baseline ?: newTraitDraft())
    var previewValues by useState(emptyMap<String, Any?>())
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    var impact by useState<ImpactReportView?>(null)
    val baseline = props.baseline
    val isNew = baseline == null

    val problems = draftProblems(draft, baseline)
    val count = draftAttributeCount(draft)
    val changed = draftChanged(draft, baseline)
    val preview = useMemo(draft.toInfo().toString()) {
        if (problems.any { it.contains("needs at least one field") }) null else {
            val defs = previewDefs(draft)
            try {
                parseSchemaTypes(defs)[previewTypeName]?.let { it to collectLayouts(defs) }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun save(acknowledge: Boolean) {
        busy = true
        saveError = null
        designScope.launch {
            try {
                val result = ClientsApi.saveTraitDesign(traitDesignRequest(props.clientId, draft, expectedMode(props.savesDraft), acknowledge))
                impact = null
                props.onSaved(savedNote("Saved trait ${result.design.traitId} to ${result.configName}.", result.mode))
            } catch (e: Throwable) {
                val report = impactOf(e)
                if (report != null) impact = report else saveError = saveFailure(e, props.onChanged)
            } finally {
                busy = false
            }
        }
    }

    div {
        className = ClassName("trait-designer")
        h4 { +(baseline?.let { "Edit ${it.label}" } ?: "New trait") }
        textField("Name", draft.label, disabled = busy) { v -> draft = draft.withHeading(v, draft.description.orEmpty()) }
        textField("Description", draft.description.orEmpty(), disabled = busy) { v -> draft = draft.withHeading(draft.label, v) }
        baseline?.traitId?.let { id ->
            p {
                className = ClassName("type-hint")
                +"Its id is "
                code { +id }
                +". A trait's id and its fields' keys never change; their labels may."
            }
        }
        draft.fields.forEachIndexed { i, field ->
            div {
                key = "field-$i".unsafeCast<Key>()
                className = ClassName("trait-field")
                div {
                    className = ClassName("row")
                    Input {
                        value = field.label
                        placeholder = "Label"
                        disabled = busy
                        style = js("({ maxWidth: 320 })")
                        onChange = { e -> draft = draft.withField(i, field.withLabel(e.target.value as String)) }
                    }
                    Select {
                        value = field.kind.name
                        options = choiceOptions(kindsFor(field, baseline).map { kindLabel(it) to it.name })
                        disabled = busy
                        style = js("({ minWidth: 160 })")
                        onChange = { v ->
                            TraitFieldKind.entries.firstOrNull { it.name == v }?.let {
                                draft = draft.withField(i, field.withKind(it))
                            }
                        }
                    }
                    Checkbox {
                        checked = field.required
                        disabled = busy
                        onChange = { e -> draft = draft.withField(i, field.withRequired(e.target.checked == true)) }
                        +"Required"
                    }
                    span {
                        className = ClassName("row-actions")
                        Button {
                            type = "link"
                            size = "small"
                            disabled = busy || i == 0
                            onClick = { draft = draft.withFieldMoved(i, -1) }
                            +"↑"
                        }
                        Button {
                            type = "link"
                            size = "small"
                            disabled = busy || i == draft.fields.lastIndex
                            onClick = { draft = draft.withFieldMoved(i, 1) }
                            +"↓"
                        }
                        Button {
                            type = "link"
                            size = "small"
                            danger = true
                            disabled = busy
                            onClick = { draft = draft.withoutField(i) }
                            +"Remove"
                        }
                    }
                }
                field.key?.let { k ->
                    div {
                        className = ClassName("type-hint")
                        +"Key: "
                        code { +k }
                    }
                }
                if (field.kind.hasChoices) {
                    div {
                        className = ClassName("trait-choices")
                        field.choices.forEachIndexed { j, choice ->
                            div {
                                key = "choice-$j".unsafeCast<Key>()
                                className = ClassName("row")
                                Input {
                                    value = choice.label
                                    placeholder = "Choice"
                                    disabled = busy
                                    style = js("({ maxWidth: 280 })")
                                    onChange = { e ->
                                        val label = e.target.value as String
                                        draft = draft.withField(i, field.withChoices(field.choices.mapIndexed { n, c -> if (n == j) TraitChoiceDesign(c.value, label) else c }))
                                    }
                                }
                                Button {
                                    type = "link"
                                    size = "small"
                                    disabled = busy
                                    onClick = { draft = draft.withField(i, field.withChoices(field.choices.filterIndexed { n, _ -> n != j })) }
                                    +"Remove"
                                }
                            }
                        }
                        Button {
                            type = "link"
                            size = "small"
                            disabled = busy
                            onClick = { draft = draft.withField(i, field.withChoices(field.choices + TraitChoiceDesign(null, ""))) }
                            +"+ Add choice"
                        }
                    }
                }
            }
        }
        val addRefusal = addFieldRefusal(props.quotas, count)
        p {
            Button {
                size = "small"
                disabled = busy || addRefusal != null
                addRefusal?.let { asDynamic()["title"] = it }
                onClick = { draft = draft.withNewField() }
                +"+ Add field"
            }
        }
        draftQuotaText(props.quotas, count, isNew)?.let {
            p {
                className = ClassName("type-hint")
                +it
            }
        }
        p {
            className = ClassName("type-hint")
            +("Required means every entry of the trait must hold the field, whoever writes it. To ask for a field on " +
                "one form only, mark it required on that form in Design View.")
        }
        if (problems.isNotEmpty()) {
            ul {
                className = ClassName("wf-reasons")
                problems.forEachIndexed { i, text ->
                    li {
                        key = i.toString().unsafeCast<Key>()
                        +text
                    }
                }
            }
        }
        h4 { +"Preview" }
        val shown = preview
        if (shown == null) {
            p {
                className = ClassName("subtitle")
                +"The preview appears once the trait has a field."
            }
        } else {
            div {
                className = ClassName("trait-preview")
                SchemaForm {
                    type = shown.first
                    values = previewValues
                    editable = true
                    friendly = true
                    fieldLayouts = shown.second
                    onChange = { v -> previewValues = v }
                }
            }
        }
        p {
            className = ClassName("subtitle")
            +saveEffectText(props.savesDraft)
        }
        p {
            Button {
                type = "primary"
                this.loading = busy
                disabled = !changed || problems.isNotEmpty()
                onClick = { save(acknowledge = false) }
                +"Save"
            }
            Button {
                type = "link"
                disabled = busy
                onClick = { props.onCancel() }
                +"Cancel"
            }
        }
        saveError?.let { errorText("Couldn't save the trait.", it) }
    }
    Modal {
        open = impact != null
        title = "Some stored forms hold data this change affects"
        onCancel = { impact = null }
        footer = Fragment.create {
            Button {
                onClick = { impact = null }
                +"Cancel"
            }
            Button {
                type = "primary"
                danger = true
                this.loading = busy
                onClick = { save(acknowledge = true) }
                +"Save anyway"
            }
        }
        impact?.let { impactReportBody(it, props.formsOpenable, doing = "Saving this trait") }
    }
}
