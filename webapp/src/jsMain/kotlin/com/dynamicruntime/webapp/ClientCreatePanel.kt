package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.ClassName

private val clientCreateScope = MainScope()

external interface ClientCreatePanelProps : Props {
    /** The customer to file the client under, to start: the customer page's, for the platform. */
    var presetCustomer: String?

    /**
     * Called once a client is made, before the panel says so: the page re-reads what lists it. Returns whether the page
     * moves on itself (to the client's page, say), in which case the panel says nothing more.
     */
    var onCreated: (ClientCreateOutcome) -> Boolean

    /** Called to close the panel: its Cancel, or Close once a client is made. */
    var onClose: () -> Unit
}

/**
 * A New client form (issue #1820), the one form behind both creates: the platform's, for an administrator who sees
 * every client, and a customer's, for an administrator of the customer's root client. Which, and what it may be built
 * on, it asks of `/clientAdmin/client/createChoices` -- the lists the creates judge by -- so it offers what the create
 * will take. A draft panel: the id (a customer's under its fixed prefix), the name, the note, the customer (the
 * platform's), the template, whether a blank client starts with workflows, and under More options a sandbox and the
 * platform's usage, audience, and environments. A refusal is shown in the create's words; once the client is made it
 * says so, and offers a customer's creator the switch to their administrator user there.
 */
val ClientCreatePanel = FC<ClientCreatePanelProps> { props ->
    var choices by useState<ClientCreateChoices?>(null)
    var choicesError by useState<DisplayError?>(null)
    var draft by useState(ClientCreateDraft(customerId = props.presetCustomer.orEmpty()))
    var busy by useState(false)
    var failure by useState<DisplayError?>(null)
    var created by useState<ClientCreateOutcome?>(null)
    val bump = useRefreshBump()

    useEffect(Unit) {
        clientCreateScope.launch {
            try {
                choices = ClientCreateApi.choices()
            } catch (e: Throwable) {
                choicesError = userFacingError(e)
            }
        }
    }

    val loaded = choices
    val start = ClientCreateDraft(customerId = props.presetCustomer.orEmpty())
    panel("newClient", "New client", kind = PanelKind.draft, dirty = created == null && draft != start) {
        when {
            choicesError != null -> {
                errorText("Couldn't start a new client.", choicesError!!)
                p {
                    Button {
                        type = "link"
                        onClick = { props.onClose() }
                        +"Close"
                    }
                }
            }
            loaded == null -> p { className = ClassName("subtitle"); +"Loading…" }
            created != null -> {
                val outcome = created!!
                p { className = ClassName("form-ok"); +clientCreatedText(outcome) }
                div {
                    className = ClassName("save-bar")
                    if (clientCreatedSwitchable(outcome)) {
                        Button {
                            type = "primary"
                            loading = busy
                            onClick = {
                                busy = true
                                failure = null
                                clientCreateScope.launch {
                                    try {
                                        AuthApi.switchClient(outcome.client)
                                        afterSessionChange(bump)
                                    } catch (e: Throwable) {
                                        failure = userFacingError(e)
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                            +"Switch to ${outcome.client}"
                        }
                    }
                    Button {
                        type = "link"
                        disabled = busy
                        onClick = { props.onClose() }
                        +"Close"
                    }
                }
                failure?.let { errorText("Couldn't switch to the client.", it) }
            }
            else -> {
                val platform = loaded.asPlatform
                // An edit clears the last create's refusal, which was about what was sent then.
                fun set(change: ClientCreateDraft.() -> ClientCreateDraft) {
                    draft = draft.change()
                    failure = null
                }
                if (platform) {
                    textField("Client id", draft.clientId, disabled = busy, placeholder = "Letters, digits and _", maxLength = 80) {
                        set { copy(clientId = it) }
                    }
                } else {
                    choiceRow("Client id") {
                        Input {
                            value = draft.clientId
                            disabled = busy
                            prefix = "${loaded.customerId.orEmpty()}${CUST.separator}"
                            placeholder = "Letters, digits and _"
                            maxLength = 80
                            // With a prefix antd draws a wrapper at full width, which wraps under the label; this keeps it
                            // beside the label at the width a plain field takes there (measured).
                            style = js("({ flex: '1 1 0%', minWidth: 0 })")
                            onChange = { e -> set { copy(clientId = e.target.value as String) } }
                        }
                    }
                    div {
                        className = ClassName("type-hint")
                        +"A client of ${loaded.customerId}'s has an id beginning ${loaded.customerId}${CUST.separator}."
                    }
                }
                textField("Name", draft.name, disabled = busy, placeholder = "The name people see", maxLength = 80) {
                    set { copy(name = it) }
                }
                textField("Internal note", draft.description, disabled = busy, placeholder = "Optional: who, what or why; only administrators see it") {
                    set { copy(description = it) }
                }
                if (platform) {
                    choiceRow("Customer") {
                        Select {
                            value = draft.customerId
                            disabled = busy
                            asDynamic()["aria-label"] = "Customer"
                            options = choiceOptions(listOf("None" to "") + loaded.customers.map { (id, label) -> label to id })
                            style = choiceStyle()
                            onChange = { v -> set { copy(customerId = v as? String ?: "") } }
                        }
                        div {
                            className = ClassName("type-hint")
                            +"A customer's client has an id that is the customer's, or begins with it and ${CUST.separator}."
                        }
                    }
                }
                choiceRow("Start from") {
                    Select {
                        value = draft.template
                        disabled = busy
                        asDynamic()["aria-label"] = "Start from"
                        options = choiceOptions(listOf(blankClientLabel to "") + loaded.templates.map { (id, label) -> label to id })
                        style = choiceStyle()
                        onChange = { v -> set { copy(template = v as? String ?: "") } }
                    }
                    div {
                        className = ClassName("type-hint")
                        +(if (draft.template.isEmpty()) {
                            "A blank client holds only what you give it."
                        } else {
                            "The client is built on ${draft.template}'s definition in code (its forms, workflows, and copy), " +
                                "and takes none of what is configured on ${draft.template}."
                        })
                    }
                }
                if (draft.template.isEmpty()) div {
                    Checkbox {
                        checked = draft.starterWorkflows
                        disabled = busy
                        onChange = { e -> set { copy(starterWorkflows = e.target.checked == true) } }
                        +"Start with a creation workflow and a core details workflow"
                    }
                }
                MoreOptions {
                    recordKey = "newClient"
                    inForce = clientCreateOptionsInForce(platform, draft)
                    div {
                        Checkbox {
                            checked = draft.sandbox
                            disabled = busy
                            onChange = { e -> set { copy(sandbox = e.target.checked == true) } }
                            +"With a sandbox"
                        }
                        div {
                            className = ClassName("type-hint")
                            +"Use this when the client's administrators will try changes before people see them."
                        }
                    }
                    if (platform) {
                        choiceRow("Usage") {
                            Select {
                                value = draft.usageType
                                disabled = busy
                                asDynamic()["aria-label"] = "Usage"
                                options = choiceOptions(ClientUsageType.entries.map { it.name to it.name })
                                style = choiceStyle()
                                onChange = { v -> set { copy(usageType = v as? String ?: ClientUsageType.production.name) } }
                            }
                            div { className = ClassName("type-hint"); +"Use this when the client is for testing or development work." }
                        }
                        choiceRow("Audience") {
                            Select {
                                value = draftAudience(draft)
                                disabled = busy
                                asDynamic()["aria-label"] = "Audience"
                                options = choiceOptions(ClientAudience.entries.map { it.name to it.name })
                                style = choiceStyle()
                                onChange = { v -> set { copy(audience = v as? String) } }
                            }
                            div {
                                className = ClassName("type-hint")
                                +"Use this when the client is ours though filed under a customer, or a customer's though under none."
                            }
                        }
                        choiceRow("Environments") {
                            Select {
                                mode = "multiple"
                                value = draft.environments.toTypedArray()
                                disabled = busy
                                asDynamic()["aria-label"] = "Environments"
                                options = choiceOptions(ENV.names.map { it to it })
                                style = choiceStyle()
                                onChange = { v -> set { copy(environments = (v as Array<String>).toList()) } }
                            }
                            div { className = ClassName("type-hint"); +"Use this when the client should run in some environments only." }
                        }
                    }
                }
                val problems = clientCreateProblems(loaded, draft)
                if (problems.isNotEmpty() && draft != start) {
                    p { className = ClassName("type-hint"); +problems.joinToString(" ") }
                }
                SaveBar {
                    saveText = "Create client"
                    saving = busy
                    canSave = problems.isEmpty()
                    this.failure = failure
                    failureLead = "Couldn't create the client."
                    effectText = clientCreateEffectText(loaded)
                    onDiscard = { props.onClose() }
                    onSave = {
                        busy = true
                        failure = null
                        clientCreateScope.launch {
                            try {
                                val outcome = ClientCreateApi.create(loaded, draft)
                                if (!props.onCreated(outcome)) created = outcome
                            } catch (e: Throwable) {
                                failure = userFacingError(e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a New client form's More options holds that is not at its default (issue #1820), each said shortly: the
 * sandbox, and the platform's usage, audience, and environments. Pure, and covered under `jsNodeTest`.
 */
fun clientCreateOptionsInForce(platform: Boolean, draft: ClientCreateDraft): List<String> = buildList {
    if (draft.sandbox) add("Sandbox")
    if (!platform) return@buildList
    if (draft.usageType != ClientUsageType.production.name) add("Usage: ${draft.usageType}")
    draft.audience?.let { add("Audience: $it") }
    if (draft.environments.toSet() != ENV.names.toSet()) add("Environments: ${draft.environments.joinToString(", ").ifEmpty { "none" }}")
}

/** How a create takes effect, said beside its button (issue #1820). Pure. */
fun clientCreateEffectText(choices: ClientCreateChoices): String =
    if (choices.asPlatform) {
        "The client is created, published, and loaded at once."
    } else {
        "The client is created under ${choices.customerId} at once, with you as its administrator."
    }
