package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.TraitNarrowing
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
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
import react.useMemo
import react.useState
import web.cssom.ClassName

private val narrowScope = MainScope()

external interface TraitNarrowEditorProps : Props {
    var clientId: String
    /** The global trait being narrowed. */
    var trait: TraitChoiceView
    /** Its data type, as the platform declares it. */
    var base: TraitBaseView
    /** Whether the client includes the trait now: a narrowing has no effect until it does. */
    var included: Boolean
    /** Whether a save is a draft, as the page last read the client ([savesAsDraft]): said beside Save, and sent with it. */
    var savesDraft: Boolean
    /** Whether the impact report's forms can be opened from this session ([impactFormsOpenable]). */
    var formsOpenable: Boolean
    /** What a save calls once it has taken effect, with what it said: the editor closes, and the page re-reads. */
    var onSaved: (String) -> Unit
    var onCancel: () -> Unit
    /** What a refusal for no longer taking effect as said calls ([saveConflict]): the page re-reads. */
    var onChanged: () -> Unit
    /** The page's "What is this?" for the preview (issue #1564): that it is a form to try, and saves nothing. */
    var previewExplanation: String
}

/**
 * Adapting a global trait for a client (issues #1119, #1534): its **Name** -- the layout `label`, which every page names
 * the trait by -- and **Introduction** -- the layout `description`, shown above its fields on workflow forms -- then a
 * row per field, with **Shown** -- hiding a field from the client's forms, in the
 * layout, while its data still accepts it -- **Required**, in the data, and for a field with choices the ones the
 * client keeps. Only moves that narrow are offered: a field the data requires cannot be
 * hidden, nor one the trait requires made optional ([hideLockedReason], [requireLockedReason]). A **live preview** draws
 * the trait as the client would take it ([narrowPreviewDefs]), the kernel's alteration over the platform's type.
 *
 * A save takes effect as the page's other editors' do -- live, or a draft for a client with a sandbox, which then says
 * what publishing it would do to the live client's forms -- and a live one that would strand data stored forms hold
 * (a field made required, a choice taken away) is refused with the impact report, whose **Save anyway** acknowledges
 * it. **As the trait is declared** clears the narrowing, so the client takes the trait as the platform declares it.
 *
 * Theme: #NARRW.
 */
val TraitNarrowEditor = FC<TraitNarrowEditorProps> { props ->
    val baseline = props.trait.narrowing ?: TraitNarrowing(props.trait.traitId)
    var draft by useState(baseline)
    var previewValues by useState(emptyMap<String, Any?>())
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    var impact by useState<ImpactReportView?>(null)
    // What a save refused over its impact was sending, so Save anyway resends that and not the draft.
    var pending by useState<TraitNarrowing?>(null)
    val rows = narrowFieldRows(props.base)
    val platformName = platformTraitName(props.trait.traitId, props.base)
    val platformIntro = platformTraitIntro(props.base)
    // What the two text boxes hold, as typed: the draft keeps them trimmed, which shown back would drop a space typed at
    // the end before the next word arrived.
    var nameText by useState(baseline.name ?: platformName)
    var introText by useState(baseline.introduction ?: platformIntro)
    val problems = narrowProblems(draft, props.base)
    val changed = narrowingChanged(draft, baseline)
    val preview = useMemo(draft.toInfo().toString()) { traitPreviewOf(narrowPreviewDefs(props.base, draft), props.base.typeName) }

    fun save(narrowing: TraitNarrowing, acknowledge: Boolean) {
        pending = narrowing
        busy = true
        saveError = null
        narrowScope.launch {
            try {
                val result = ClientsApi.narrowTrait(traitNarrowRequest(props.clientId, narrowing, expectedMode(props.savesDraft), acknowledge))
                impact = null
                val said =
                    savedNote("Saved how this client takes ${props.trait.traitId}, in ${result.configName}.", result.mode, result.forms)
                props.onSaved(listOfNotNull(said, draftImpactText(result.draftImpact)).joinToString(" "))
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
        h4 {
            +"Adapt "
            code { +props.trait.traitId }
        }
        p {
            className = ClassName("subtitle")
            +("How this client takes the trait. Its name, its introduction, and hiding a field change only its forms; " +
                "requiring a field or keeping fewer choices changes what its data accepts.")
        }
        choiceRow("Name") {
            Input {
                value = nameText
                placeholder = platformName
                disabled = busy
                style = js("({ maxWidth: 320 })")
                onChange = { e ->
                    // The state read back in this handler is still this render's, so the typed text is passed on directly.
                    val typed = e.target.value as String
                    nameText = typed
                    draft = draft.withName(typed, platformName)
                }
            }
        }
        p {
            className = ClassName("type-hint")
            +(if (draft.name == null) "As the platform names it. " else "The platform calls it “$platformName”. ")
            +"Use this when this client's people know the trait by another name: forms, listings, and messages show it."
        }
        if (props.trait.namePulled && draft.name != null) {
            p {
                className = ClassName("type-hint")
                +("The platform's name comes from shared copy, which may be translated or changed for every client; a name " +
                    "given here replaces it with these words.")
            }
        }
        choiceRow("Introduction") {
            TextArea {
                value = introText
                placeholder = "No introduction"
                disabled = busy
                asDynamic()["rows"] = fittedRows(introText, 2, 8)
                // Narrower than its column, so it may be dragged wider as well as taller (issue #1550).
                asDynamic()["className"] = "resize-both"
                style = js("({ width: 'min(560px, 100%)' })")
                onChange = { e ->
                    val typed = e.target.value as String
                    introText = typed
                    draft = draft.withIntroduction(typed, platformIntro)
                }
            }
        }
        p {
            className = ClassName("type-hint")
            +(if (draft.introduction == null) "As the platform introduces it. " else "This client's own. ")
            +("Use this when its people need a word before they fill the trait in: shown above its fields on workflow " +
                "forms, in Markdown. Empty shows none.")
        }
        if (props.trait.introPulled && draft.introduction != null) {
            p {
                className = ClassName("type-hint")
                +"The platform's introduction comes from shared copy; one given here replaces it."
            }
        }
        if (!props.included) {
            p {
                className = ClassName("type-hint")
                +"This client does not include the trait, so a narrowing takes effect once it does."
            }
        }
        div {
            className = ClassName("op-table-scroll")
            table {
                className = ClassName("op-table")
                thead {
                    tr {
                        th { +"Field" }
                        th { +"Shown" }
                        th { +"Required" }
                        th { +"Choices kept" }
                    }
                }
                tbody {
                    rows.forEach { row ->
                        val hideLock = hideLockedReason(row, draft)
                        val requireLock = requireLockedReason(row, draft)
                        tr {
                            key = row.field.unsafeCast<Key>()
                            td {
                                +row.label
                                div {
                                    className = ClassName("type-hint")
                                    code { +row.field }
                                }
                            }
                            td {
                                span {
                                    hideLock?.let { title = it }
                                    Checkbox {
                                        checked = row.field !in draft.hidden
                                        disabled = busy || hideLock != null
                                        onChange = { e -> draft = draft.withHidden(row.field, e.target.checked != true) }
                                    }
                                }
                            }
                            td {
                                span {
                                    requireLock?.let { title = it }
                                    Checkbox {
                                        checked = row.baseRequired || row.field in draft.required
                                        disabled = busy || requireLock != null
                                        onChange = { e -> draft = draft.withRequired(row.field, e.target.checked == true) }
                                    }
                                }
                            }
                            td {
                                val offered = row.choices
                                if (offered == null) {
                                    +"—"
                                } else {
                                    Select {
                                        mode = "multiple"
                                        value = (draft.choices[row.field] ?: offered.map { it.first }).toTypedArray()
                                        options = choiceOptions(offered.map { (v, label) -> label to v })
                                        disabled = busy
                                        style = js("({ minWidth: 220 })")
                                        onChange = { v ->
                                            val kept = (v as Array<*>).mapNotNull { it?.toString() }
                                            draft = draft.withChoices(row.field, kept, offered.map { it.first })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
        // The form draws the trait's fields, not its name, so the heading says what the trait will be called.
        WhatIsThis {
            heading = "Preview: ${draft.name ?: platformName}"
            headingLevel = 4
            text = props.previewExplanation
        }
        (draft.introduction ?: platformIntro).takeIf { it.isNotBlank() }?.let { intro ->
            Markdown { source = intro; inlineUi = true }
        }
        preview.problem?.let {
            p {
                className = ClassName("type-hint")
                +"The preview cannot be drawn: $it"
            }
        }
        preview.type?.let { type ->
            div {
                className = ClassName("trait-preview")
                SchemaForm {
                    this.type = type
                    values = previewValues
                    editable = true
                    friendly = true
                    fieldLayouts = preview.layouts
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
                onClick = { save(draft, acknowledge = false) }
                +"Save"
            }
            if (props.trait.narrowingConfig != null) {
                Button {
                    disabled = busy
                    asDynamic()["title"] =
                        "Take the trait as the platform declares it: its name, with nothing hidden, required, or restricted here."
                    onClick = { save(TraitNarrowing(props.trait.traitId), acknowledge = false) }
                    +"As the trait is declared"
                }
            }
            Button {
                type = "link"
                disabled = busy
                onClick = { props.onCancel() }
                +"Cancel"
            }
        }
        saveError?.let { errorText("Couldn't save the narrowing.", it) }
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
                onClick = { save(pending ?: draft, acknowledge = true) }
                +"Save anyway"
            }
        }
        impact?.let { impactReportBody(it, props.formsOpenable, doing = "Narrowing this trait") }
    }
}
