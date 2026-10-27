package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.ApiKeyInfo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffect
import react.useState
import web.cssom.ClassName

private val apiKeysScope = MainScope()

external interface ApiKeysTableProps : Props {
    var keys: List<ApiKeyInfo>
    var limits: ApiKeyLimits?
    /** Whether the extend and revoke go through the administrator's paths. */
    var asAdmin: Boolean
    /** Whether each row names the user the key acts as, linked to the Users page: an administrator's listing. */
    var showOwner: Boolean
    /** Called after a key was extended or revoked, so the listing re-reads. */
    var onChanged: () -> Unit
}

/**
 * A listing of API keys (issue #1402): what each is for, its access, when it was made and by whom, when it expires and
 * was last used, and whether it still works -- with **Extend…** and **Revoke** on each that has not been revoked. The
 * extension's date is shown before it is saved, worked out by the same kernel rule the server applies.
 */
val ApiKeysTable = FC<ApiKeysTableProps> { props ->
    val now = useRelativeClock()
    var extendFor by useState<String?>(null)
    var amount by useState("3")
    var unit by useState(EXTU.months)
    var working by useState<String?>(null)
    var failure by useState<Pair<String, DisplayError>?>(null)
    val columns = if (props.showOwner) 8 else 7

    fun act(keyId: String, work: suspend () -> Unit) {
        working = keyId
        failure = null
        apiKeysScope.launch {
            try {
                work()
                props.onChanged()
            } catch (e: Throwable) {
                failure = keyId to userFacingError(e)
            } finally {
                working = null
            }
        }
    }

    table {
        className = ClassName("op-table api-keys")
        thead {
            tr {
                if (props.showOwner) th { +"Acts as" }
                th { +"Name" }
                th { +"Access" }
                th { +"Created" }
                th { +"Expires" }
                th { +"Last used" }
                th { +"Status" }
                th { +"" }
            }
        }
        tbody {
            for (info in props.keys) {
                val status = info.status(now)
                tr {
                    key = info.keyId.unsafeCast<Key>()
                    if (status == AKEY.revoked || status == AKEY.expired) className = ClassName("api-key-dead")
                    if (props.showOwner) {
                        td {
                            a {
                                href = routeHref(listOf(HP.page to HMENU.pageUsers, HP.user to info.userId.toString()))
                                +(info.ownerName ?: info.ownerAddress ?: "User ${info.userId}")
                            }
                            info.ownerName?.let { _ -> info.ownerAddress?.let { div { className = ClassName("subtitle"); +it } } }
                        }
                    }
                    td {
                        div { +info.name }
                        div { className = ClassName("subtitle"); code { +info.shown } }
                    }
                    td { +apiKeyAccessText(info.access) }
                    td {
                        info.createdAt?.let { RelativeTime { iso = it.toString(); this.now = now } }
                        apiKeyMadeByText(info)?.let { div { className = ClassName("subtitle"); +it } }
                    }
                    td { RelativeTime { iso = info.expiresAt.toString(); this.now = now } }
                    td {
                        val used = info.lastUsedAt
                        if (used == null) +"Never" else RelativeTime { iso = used.toString(); this.now = now }
                    }
                    td { +apiKeyStatusText(status) }
                    td {
                        if (status != AKEY.revoked) {
                            div {
                                className = ClassName("api-key-actions")
                                Button {
                                    this.type = "link"
                                    size = "small"
                                    disabled = working != null
                                    onClick = { extendFor = if (extendFor == info.keyId) null else info.keyId }
                                    +"Extend…"
                                }
                                Popconfirm {
                                    title = apiKeyRevokeQuestion(info)
                                    description = apiKeyRevokeConsequence
                                    okText = "Revoke"
                                    cancelText = "Cancel"
                                    placement = "left"
                                    okButtonProps = js("({ danger: true })")
                                    onConfirm = { act(info.keyId) { ApiKeysApi.revoke(props.asAdmin, info.keyId) } }
                                    Button {
                                        this.type = "link"
                                        size = "small"
                                        danger = true
                                        disabled = working != null
                                        +"Revoke"
                                    }
                                }
                            }
                        }
                    }
                }
                if (extendFor == info.keyId) {
                    tr {
                        key = "${info.keyId}-extend".unsafeCast<Key>()
                        td {
                            colSpan = columns
                            extendEditor(info, now, amount, unit, props.limits, working == info.keyId,
                                onAmount = { amount = it }, onUnit = { unit = it }, onCancel = { extendFor = null },
                                onSave = { n ->
                                    act(info.keyId) {
                                        ApiKeysApi.extend(props.asAdmin, info.keyId, n, unit)
                                        extendFor = null
                                    }
                                },
                            )
                        }
                    }
                }
                failure?.takeIf { it.first == info.keyId }?.let { (_, err) ->
                    tr {
                        key = "${info.keyId}-error".unsafeCast<Key>()
                        td { colSpan = columns; errorText("Couldn't change “${info.name}”.", err) }
                    }
                }
            }
        }
    }
}

/** The extension's fields, under its key's row: an amount, days or months, and the date it would give. */
private fun ChildrenBuilder.extendEditor(
    info: ApiKeyInfo,
    now: kotlin.time.Instant,
    amount: String,
    unit: String,
    limits: ApiKeyLimits?,
    saving: Boolean,
    onAmount: (String) -> Unit,
    onUnit: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val preview = apiKeyExtensionPreview(info, now, amount, unit, limits?.maxDays ?: AKEY.defaultMaxDays)
    div {
        className = ClassName("api-key-extend")
        span { +"Extend by " }
        Input {
            this.type = "number"
            value = amount
            disabled = saving
            asDynamic()["style"] = js("({ width: 90 })")
            asDynamic()["aria-label"] = "How many"
            onChange = { e -> onAmount(e.target.value as String) }
        }
        Select {
            value = unit
            disabled = saving
            options = listOf(EXTU.days, EXTU.months).map { u ->
                val o: dynamic = js("({})")
                o.label = u
                o.value = u
                o
            }.toTypedArray()
            style = js("({ minWidth: 110 })")
            onChange = { v -> onUnit(v as String) }
        }
        Button {
            this.type = "primary"
            size = "small"
            this.loading = saving
            disabled = preview.problem != null || preview.expiry == null
            onClick = { amount.trim().toIntOrNull()?.let(onSave) }
            +"Save"
        }
        Button {
            size = "small"
            disabled = saving
            onClick = onCancel
            +"Cancel"
        }
    }
    val expiry = preview.expiry
    when {
        preview.problem != null -> p { className = ClassName("error-text"); +preview.problem }
        expiry != null -> p { className = ClassName("subtitle"); +"It would then expire ${formatTimestamp(expiry.toString())}." }
    }
}

external interface ApiKeysSectionProps : Props {
    /** The user whose keys these are, for an administrator; null for the signed-in user's own. */
    var ownerId: Long?
}

/**
 * A user's API keys (issue #1402): on Profile the signed-in person's own, and in the Users editor -- with
 * [ApiKeysSectionProps.ownerId] --
 * a user's as their administrator manages them. The listing, a way to make one, and the dialog showing a new key the
 * one time it can be seen: it is held in this component's state alone, and gone when the dialog closes.
 */
val ApiKeysSection = FC<ApiKeysSectionProps> { props ->
    val generation = useRefreshGeneration()
    val forSelf = props.ownerId == null
    var page by useState<ApiKeyPage?>(null)
    var loadError by useState<DisplayError?>(null)
    var reread by useState(0)
    var keyName by useState("")
    var readOnly by useState(true)
    var lifetime by useState<Int?>(null)
    var creating by useState(false)
    var createError by useState<DisplayError?>(null)
    var made by useState<ApiKeyMade?>(null)

    useEffect(generation, props.ownerId, reread) {
        apiKeysScope.launch {
            try {
                page = ApiKeysApi.list(props.ownerId)
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("api-keys-section")
        h2 { +"API keys" }
        p {
            className = ClassName("subtitle")
            +apiKeysIntro(forSelf)
        }
        loadError?.let { errorText("Couldn't read the keys.", it) }
        val loaded = page
        when {
            loaded == null && loadError == null -> p { className = ClassName("subtitle"); +"Loading…" }
            loaded != null && loaded.keys.isEmpty() -> p { className = ClassName("subtitle"); +"No keys yet." }
            loaded != null -> ApiKeysTable {
                keys = loaded.keys
                limits = loaded.limits
                asAdmin = !forSelf
                showOwner = false
                onChanged = { reread += 1 }
            }
        }

        if (loaded != null) {
            val block = apiKeyCreateBlock(loaded.limits, forSelf)
            if (block != null) {
                p { className = ClassName("subtitle"); +block }
            } else {
                div {
                    className = ClassName("api-key-create")
                    textField("New key", keyName, disabled = creating, placeholder = "What it is for", maxLength = AKEY.maxNameLength) {
                        keyName = it
                    }
                    choiceRow("Access") {
                        Checkbox {
                            checked = readOnly
                            disabled = creating
                            onChange = { e -> readOnly = e.target.checked as Boolean }
                            +"Read-only (can look at things but not change them)"
                        }
                    }
                    val choices = apiKeyLifetimeChoices(loaded.limits)
                    val chosen = lifetime ?: loaded.limits?.days ?: AKEY.defaultDays
                    choiceRow("Lasts") {
                        Select {
                            value = chosen.toString()
                            disabled = creating
                            options = choices.map { d ->
                                val o: dynamic = js("({})")
                                o.label = apiKeyLifetimeText(d)
                                o.value = d.toString()
                                o
                            }.toTypedArray()
                            style = js("({ minWidth: 140 })")
                            onChange = { v -> lifetime = (v as String).toInt() }
                        }
                    }
                    Button {
                        this.type = "primary"
                        this.loading = creating
                        disabled = keyName.isBlank()
                        onClick = {
                            creating = true
                            createError = null
                            apiKeysScope.launch {
                                try {
                                    made = ApiKeysApi.create(props.ownerId, keyName, if (readOnly) AKEY.read else AKEY.full, chosen)
                                    keyName = ""
                                    reread += 1
                                } catch (e: Throwable) {
                                    createError = userFacingError(e)
                                } finally {
                                    creating = false
                                }
                            }
                        }
                        +"Create key"
                    }
                    createError?.let { errorText("Couldn't create the key.", it) }
                }
            }
        }

        ApiKeySecretDialog {
            this.made = made
            onClose = { made = null }
        }
    }
}

external interface ApiKeySecretDialogProps : Props {
    var made: ApiKeyMade?
    var onClose: () -> Unit
}

/** The new key, shown the one time it can be: to copy, and a command to try it with. */
val ApiKeySecretDialog = FC<ApiKeySecretDialogProps> { props ->
    val key = props.made
    Modal {
        open = key != null
        title = "Your new API key"
        footer = null
        onCancel = props.onClose
        if (key != null) {
            p { +"Copy it now. This is the only time it is shown: only a hash of it is kept, so nobody can show it to you again." }
            div {
                className = ClassName("api-key-secret")
                code { +key.secret }
            }
            CopyButton { text = key.secret; label = "Copy the key" }
            p {
                className = ClassName("subtitle")
                +"${apiKeyAccessSentence(key.info.access)}, and expires ${formatTimestamp(key.info.expiresAt.toString())}. Try it:"
            }
            val command = apiKeyTryCommand(ApiKeysApi.tryUrl(), key.secret)
            div {
                className = ClassName("api-key-secret")
                code { +command }
            }
            CopyButton { text = command; label = "Copy the command" }
            div {
                className = ClassName("api-key-dialog-actions")
                Button {
                    this.type = "primary"
                    onClick = props.onClose
                    +"I have copied it"
                }
            }
        }
    }
}
