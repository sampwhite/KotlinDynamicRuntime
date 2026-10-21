package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.refName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Fragment
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName

private val traitsScope = MainScope()

external interface ClientTraitsSectionProps : Props {
    var clientId: String
    /** The traits the client's **stored** definition includes, which the picker starts from and a save changes. */
    var stored: List<String>
    /** Whether a save is a draft, as the page last read the client ([savesAsDraft]): said beside Save, and sent with it. */
    var savesDraft: Boolean
    /** Whether the impact report's forms can be opened from this session ([impactFormsOpenable]). */
    var formsOpenable: Boolean
    /** What a save calls once it has taken effect: the page re-reads. */
    var onChanged: () -> Unit
}

/**
 * The traits a client includes, on its page (issue #1117): every global trait as the platform declares it -- its id,
 * what it is for, what it applies to, and its fields on request, read-only ([SchemaOutline]) -- each with a box, and
 * above them one switch for every global trait, now and to come. Read from `/clientAdmin/client/traits` on mount and
 * on every refresh generation.
 *
 * A trait the client customizes is supported without being included, and one under every global trait is included
 * already, so their boxes are drawn ticked and locked, saying why ([traitLockedReason]). The switch is locked off for
 * a client that may not take it -- a customer's production client -- with the backend's reason. Turning it off keeps
 * the traits ticked one by one.
 *
 * **Save** sends the whole list ([includedTraitsAfter]), the picker's changes made in place, and takes effect as the
 * page's other editors' saves do: live, or a draft for a client with a sandbox, which it says beside Save
 * ([saveEffectText]) and sends ([expectedMode]). A live save that would take away a trait the client's forms hold is
 * refused with the impact report, shown in a dialog whose **Save anyway** acknowledges it.
 *
 * Under the picker, the client's **own** traits (issue #1118): each with **Edit**, or why it cannot be edited here,
 * then **New trait**, which open the [TraitDesignEditor].
 */
val ClientTraitsSection = FC<ClientTraitsSectionProps> { props ->
    val generation = useRefreshGeneration()
    var catalog by useState<TraitCatalogView?>(null)
    var loadError by useState<DisplayError?>(null)
    // The traits a save just stored, until the page's re-read brings the same: the picker starts from them, so the
    // old list is not sent back as a change.
    var saved by useState<List<String>?>(null)
    val baseline = saved ?: props.stored
    var draft by useState(traitsDraftOf(baseline))
    var opened by useState(emptySet<String>())
    var busy by useState(false)
    var saveError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    var impact by useState<ImpactReportView?>(null)
    // The own trait the editor is open on (issue #1118): its id, "" for a new trait, null when closed.
    var designing by useState<String?>(null)
    var designNote by useState<String?>(null)
    // Which client a save or a read was started for: an answer for a client the page has moved on from lands nowhere.
    val latest = useRef(0)

    useEffect(props.clientId) {
        latest.current = (latest.current ?: 0) + 1
        catalog = null
        opened = emptySet()
        designing = null
        designNote = null
        busy = false
        saveError = null
        note = null
        impact = null
    }
    // What the client stores now -- read again, or another client's: the picker starts over from it.
    useEffect(props.clientId, props.stored.joinToString("\n")) {
        saved = null
        draft = traitsDraftOf(props.stored)
    }
    useEffect(props.clientId, generation) {
        val token = latest.current
        traitsScope.launch {
            try {
                val read = ClientsApi.traitCatalog(props.clientId)
                if (latest.current == token) {
                    catalog = read
                    loadError = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) loadError = userFacingError(e)
            }
        }
    }
    val types = useMemo(catalog) { catalog?.let { parseSchemaTypes(it.defs) } ?: emptyMap() }

    p {
        className = ClassName("subtitle")
        +traitsIntro
    }
    val shown = catalog
    if (shown == null) {
        loadError?.let { errorText("Couldn't load the global traits.", it) }
            ?: p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        return@FC
    }
    loadError?.let { errorText("Couldn't refresh the global traits; showing what was loaded.", it) }
    val after = includedTraitsAfter(baseline, draft, shown.traits)
    val changed = after != baseline

    fun save(acknowledge: Boolean) {
        val token = latest.current
        busy = true
        saveError = null
        note = null
        traitsScope.launch {
            try {
                val result = ClientsApi.setIncludedTraits(traitsEditRequest(props.clientId, after, expectedMode(props.savesDraft), acknowledge))
                if (latest.current == token) {
                    note = savedNote("Saved the client's traits to ${result.configName}.", result.mode)
                    saved = result.included
                    impact = null
                }
                props.onChanged()
            } catch (e: Throwable) {
                if (latest.current == token) {
                    val report = impactOf(e)
                    if (report != null) impact = report else saveError = saveFailure(e, props.onChanged)
                }
            } finally {
                if (latest.current == token) busy = false
            }
        }
    }

    val groupLock = allGlobalLockedReason(shown, draft)
    p {
        Checkbox {
            checked = draft.allGlobal
            disabled = busy || groupLock != null
            onChange = { e -> draft = draft.copy(allGlobal = e.target.checked == true) }
            +"Every global trait, including ones added later"
        }
    }
    groupLock?.let {
        p {
            className = ClassName("type-hint")
            +it
        }
    }
    if (shown.traits.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +"This deployment declares no global trait."
        }
    } else {
        div {
            className = ClassName("op-table-scroll")
            table {
                className = ClassName("op-table")
                thead {
                    tr {
                        th { +"Included" }
                        th { +"Trait" }
                        th { +"What it is for" }
                        th { +"Applies to" }
                        th { +"" }
                    }
                }
                tbody {
                    shown.traits.forEach { trait ->
                        traitRows(trait, draft, busy, trait.traitId in opened, types,
                            onTick = { on -> draft = draft.copy(chosen = if (on) draft.chosen + trait.traitId else draft.chosen - trait.traitId) },
                            onToggleFields = { opened = if (trait.traitId in opened) opened - trait.traitId else opened + trait.traitId },
                        )
                    }
                }
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
            disabled = !changed
            onClick = { save(acknowledge = false) }
            +"Save"
        }
        Button {
            type = "link"
            disabled = busy || !changed
            onClick = {
                draft = traitsDraftOf(baseline)
                saveError = null
            }
            +"Undo changes"
        }
    }
    saveError?.let { errorText("Couldn't save the client's traits.", it) }
    note?.let {
        p {
            className = ClassName("subtitle")
            +it
        }
    }
    ownTraits(shown, designing, busy, onOpen = { designing = it; designNote = null })
    designing?.let { open ->
        TraitDesignEditor {
            // Keyed by what it opened on, so a draft for one trait never carries into another.
            key = "design-$open".unsafeCast<Key>()
            this.clientId = props.clientId
            this.baseline = shown.own.firstOrNull { it.traitId == open }?.design
            quotas = shown.quotas
            this.savesDraft = props.savesDraft
            formsOpenable = props.formsOpenable
            onSaved = { said ->
                designing = null
                designNote = said
                props.onChanged()
            }
            onCancel = { designing = null }
            onChanged = props.onChanged
        }
    }
    designNote?.let {
        p {
            className = ClassName("subtitle")
            +it
        }
    }
    Modal {
        open = impact != null
        title = "Some stored forms hold these traits"
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
        impact?.let { impactReportBody(it, props.formsOpenable, doing = "Saving these traits") }
    }
}

/** One trait's row -- its box, id, description and kinds, and the way to its fields -- and its fields when opened. */
private fun ChildrenBuilder.traitRows(
    trait: TraitChoiceView,
    draft: TraitsDraft,
    busy: Boolean,
    open: Boolean,
    types: Map<String, SchType>,
    onTick: (Boolean) -> Unit,
    onToggleFields: () -> Unit,
) {
    val locked = traitLockedReason(trait, draft)
    tr {
        key = trait.traitId.unsafeCast<Key>()
        td {
            span {
                locked?.let { title = it }
                Checkbox {
                    checked = traitShownIncluded(trait, draft)
                    disabled = busy || locked != null
                    onChange = { e -> onTick(e.target.checked == true) }
                }
            }
        }
        td {
            code { +trait.traitId }
            if (trait.customized) {
                div {
                    className = ClassName("type-hint")
                    +"Customized here"
                }
            }
        }
        td { +(trait.description ?: "—") }
        td { +traitAppliesToText(trait.appliesTo) }
        td {
            if (fieldsTypeOf(trait, types) != null) {
                Button {
                    type = "link"
                    size = "small"
                    onClick = { onToggleFields() }
                    +(if (open) "Hide fields" else "Fields")
                }
            }
        }
    }
    if (open) {
        val fields = fieldsTypeOf(trait, types) ?: return
        tr {
            key = "${trait.traitId}-fields".unsafeCast<Key>()
            td {
                asDynamic()["colSpan"] = 5
                SchemaOutline { type = fields }
            }
        }
    }
}

/** The type declaring [trait]'s fields, from the catalog's parsed schema; null when it carries none. */
private fun fieldsTypeOf(trait: TraitChoiceView, types: Map<String, SchType>): SchType? =
    trait.dataRef?.let(::refName)?.let { types[it] }

/**
 * The client's own traits (issue #1118): each with its name, how many fields it has and where it is stored, and
 * **Edit** -- or why the editor cannot edit it -- then **New trait**, unless the plan allows no more schemas.
 */
private fun ChildrenBuilder.ownTraits(catalog: TraitCatalogView, designing: String?, busy: Boolean, onOpen: (String) -> Unit) {
    h3 { +"Its own traits" }
    p {
        className = ClassName("subtitle")
        +("Traits this client defines for itself, supported without being included. A new one is stored in a " +
            "configuration of its own.")
    }
    if (catalog.own.isNotEmpty()) {
        div {
            className = ClassName("op-table-scroll")
            table {
                className = ClassName("op-table")
                thead {
                    tr {
                        th { +"Trait" }
                        th { +"Name" }
                        th { className = ClassName("op-num"); +"Fields" }
                        th { +"Stored in" }
                        th { +"" }
                    }
                }
                tbody {
                    catalog.own.forEach { own ->
                        tr {
                            key = own.traitId.unsafeCast<Key>()
                            td { code { +own.traitId } }
                            td { +(own.design?.label ?: "—") }
                            td { className = ClassName("op-num"); +(own.design?.fields?.size?.toString() ?: "—") }
                            td { +(own.configName ?: "source code") }
                            td {
                                if (own.design != null) {
                                    Button {
                                        type = "link"
                                        size = "small"
                                        disabled = busy || designing != null
                                        onClick = { onOpen(own.traitId) }
                                        +"Edit"
                                    }
                                } else {
                                    span {
                                        className = ClassName("type-hint")
                                        +(own.notEditable ?: "")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val refusal = newTraitRefusal(catalog.quotas)
    if (designing == null) {
        p {
            Button {
                size = "small"
                disabled = busy || refusal != null
                onClick = { onOpen("") }
                +"New trait"
            }
        }
        refusal?.let {
            p {
                className = ClassName("type-hint")
                +it
            }
        }
    }
}
