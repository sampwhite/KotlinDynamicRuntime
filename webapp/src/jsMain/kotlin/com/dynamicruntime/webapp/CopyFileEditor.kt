package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.details
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.summary
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val copyFileScope = MainScope()

external interface CopyFileEditorProps : Props {
    var clientId: String
    /** Whether [clientId] is the client the Clients page opens with none named (issue #1082), so that address is its page too. */
    var inPlace: Boolean
    /** The copy the client's configuration sets, from the overrides report: who set each key, and the orphans. */
    var overrides: List<CopyOverrideView>
    /** What a save calls once it has taken effect: the page re-reads, and so does the shell. */
    var onChanged: () -> Unit
}

/**
 * A client's copy, file by file, and its editor (issue #1062; it replaces #918's table and key picker). Choose a
 * file and every namespace and key of it is laid out with the value the client reads, rendered as Markdown -- a long
 * one in a panel that scrolls on its own -- or, per key, as written. Typing in **Find** searches every file's keys and
 * values instead, since an administrator usually knows the words on screen rather than the key; **Only changed** keeps
 * the keys the client's configuration sets -- every file's, while no file is chosen, which is how the view opens. A key
 * the client changes says who set it, with the shipped value and any source value it replaced a click away.
 *
 * **Edit** opens the key's text, with a **Preview**; **Reset** removes a stored value. Neither is saved at once: each
 * is a pending change, marked on its key with a **Revert**, and the bar under the view saves every pending change to
 * the file together (`/clientAdmin/client/copy/apply`) -- one trial, one publish, so they take effect together -- or
 * discards them. A save is of one file, so while one file has pending changes another's keys are not editable. A
 * refusal is shown under the bar in the backend's words, the keys it names marked, and the changes kept to correct.
 * While anything is pending, leaving the client's page asks first ([LeaveGuard]).
 */
val CopyFileEditor = FC<CopyFileEditorProps> { props ->
    val generation = useRefreshGeneration()
    var keys by useState<List<CopyKeyView>?>(null)
    var keysError by useState<DisplayError?>(null)
    var fileId by useState<String?>(null)
    var filter by useState("")
    var onlyChanged by useState(false)
    var pending by useState<Map<CopyAddress, PendingCopy>>(emptyMap())
    var editing by useState<Set<CopyAddress>>(emptySet())
    var previewing by useState<Set<CopyAddress>>(emptySet())
    var raw by useState<Set<CopyAddress>>(emptySet())
    var saving by useState(false)
    var saveError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    val latestKeys = useRef(0)

    // A new client under the same editor: nothing of the previous one's editing state carries over.
    useEffect(props.clientId) {
        fileId = null
        filter = ""
        onlyChanged = false
        pending = emptyMap()
        editing = emptySet()
        previewing = emptySet()
        raw = emptySet()
        saveError = null
        note = null
    }

    // The keys, read for the open client and again whenever the page re-reads (after a save, the values changed); a
    // read still in flight for a client the page has left is disowned.
    useEffect(props.clientId, generation) {
        val token = (latestKeys.current ?: 0) + 1
        latestKeys.current = token
        copyFileScope.launch {
            try {
                val loaded = ClientsApi.copyKeys(props.clientId)
                if (latestKeys.current == token) {
                    keys = loaded
                    keysError = null
                }
            } catch (e: Throwable) {
                if (latestKeys.current == token) keysError = userFacingError(e)
            }
        }
    }

    // The leave guard (issue #700): armed while changes are pending, so leaving the client's page -- in the app, or
    // by reload or a closed tab -- warns first. Moving within the page (the same client) is not a leave.
    val dirty = pending.isNotEmpty()
    useEffect(dirty, props.clientId, props.inPlace) {
        if (dirty) {
            val client = props.clientId
            val inPlace = props.inPlace
            LeaveGuard.arm({ h -> staysOnClientPage(h, client, inPlace) }) {
                LeaveGuard.confirmLeave("You have unsaved copy changes for this client. Leave the page and lose them?")
            }
        } else {
            LeaveGuard.disarm()
        }
    }

    when {
        keysError != null -> {
            errorText("Couldn't load the client's copy.", keysError!!)
            return@FC
        }
        keys == null -> {
            p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            return@FC
        }
    }

    val rows = copyKeyRows(keys!!, props.overrides)
    val openFile = copyViewOpenFile(rows, fileId)
    val query = CopyViewQuery(openFile, filter, onlyChanged)
    val groups = copyFileGroups(rows, query, pending)
    val acrossFiles = copyViewSpansFiles(query)
    val refused = saveError?.let { copyKeysNamedIn(it.text, pending.keys) }.orEmpty()

    fun edit(row: CopyKeyRow, draft: String) {
        pending = pendingAfterEdit(pending, row, draft)
        saveError = null
        note = null
    }

    fun revert(at: CopyAddress) {
        pending = pending - at
        editing = editing - at
        saveError = null
    }

    fun save() {
        val file = pendingCopyFile(pending) ?: return
        saving = true
        saveError = null
        copyFileScope.launch {
            try {
                val result = ClientsApi.applyCopy(props.clientId, pending, rows.map { it.address })
                note = copyAppliedNote(file, result)
                pending = emptyMap()
                editing = emptySet()
                previewing = emptySet()
                props.onChanged()
            } catch (e: Throwable) {
                saveError = userFacingError(e)
            } finally {
                saving = false
            }
        }
    }

    div {
        className = ClassName("row copy-view-controls")
        val files = copyViewFiles(rows)
        Select {
            value = openFile
            placeholder = "Choose a file"
            disabled = filter.isNotBlank()
            allowClear = true
            options = groupedOptions(
                "Shown by this application" to files.filter { it.shownOn != null }.map { copyFileLabel(it) to it.fileId },
                notShownNote to files.filter { it.shownOn == null }.map { it.fileId to it.fileId },
            )
            style = js("({ minWidth: 300 })")
            onChange = { v -> fileId = v as? String }
        }
        Input {
            value = filter
            placeholder = "Find a key or the words it says"
            allowClear = true
            style = js("({ maxWidth: 320 })")
            onChange = { e -> filter = (e.target.value as? String).orEmpty() }
        }
        Checkbox {
            checked = onlyChanged
            onChange = { e -> onlyChanged = e.target.checked == true }
            +"Only changed"
        }
    }
    when {
        filter.isNotBlank() -> p {
            className = ClassName("subtitle")
            +(if (openFile != null) "Searching every file; clear Find to go back to the chosen file." else "Searching every file.")
        }
        acrossFiles -> p {
            className = ClassName("subtitle")
            +"Every file's changed keys; choose a file to see all of its keys."
        }
    }
    if (groups.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +when {
                filter.isNotBlank() -> "No key or value matches “${filter.trim()}”."
                openFile == null && !onlyChanged -> "Choose a file to see its keys and what each says, or type in Find to search every file by its words."
                openFile == null -> "This client changes none of its copy."
                onlyChanged -> "This client changes nothing in this file."
                else -> "This file has no keys."
            }
        }
    }
    for (group in groups) {
        div {
            key = group.fileId.unsafeCast<Key>()
            className = ClassName("copy-file")
            // The file as a panel of its own, headed by what it is, so its keys read apart from the page around them.
            div {
                className = ClassName("copy-file-head")
                span {
                    className = ClassName("copy-file-name")
                    +group.fileId
                }
                span {
                    className = ClassName("copy-file-where")
                    +(group.shownOn ?: notShownNote)
                }
                span {
                    className = ClassName("copy-file-count")
                    +copyFileCountText(group, rows)
                }
            }
            if (group.shownOn == null && !acrossFiles) {
                p {
                    className = ClassName("subtitle copy-file-note")
                    +"A deployment's own frontend may read this file by URL, but no page here does, so a change shows nowhere in this app."
                }
            }
            for (ns in group.namespaces) {
                div {
                    key = ns.namespace.unsafeCast<Key>()
                    className = ClassName("copy-namespace")
                    h4 { +ns.namespace }
                    for (row in ns.rows) {
                        copyKeyItem(
                            row = row,
                            pending = pending,
                            isEditing = row.address in editing,
                            isPreviewing = row.address in previewing,
                            isRaw = row.address in raw,
                            isRefused = row.address in refused,
                            busy = saving,
                            onEdit = {
                                editing = editing + row.address
                                // The key's file becomes the chosen one, so clearing Find returns to the changes.
                                fileId = row.address.fileId
                            },
                            onClose = { editing = editing - row.address },
                            onDraft = { draft -> edit(row, draft) },
                            onReset = {
                                pending = pending + (row.address to PendingCopy(null))
                                fileId = row.address.fileId
                                saveError = null
                                note = null
                            },
                            onRevert = { revert(row.address) },
                            onPreview = { on -> previewing = if (on) previewing + row.address else previewing - row.address },
                            onRaw = { on -> raw = if (on) raw + row.address else raw - row.address },
                        )
                    }
                }
            }
        }
    }

    if (pending.isNotEmpty()) {
        div {
            className = ClassName("copy-save-bar")
            span { +"${pendingCopyText(pending)}." }
            Button {
                type = "primary"
                this.loading = saving
                onClick = { save() }
                +"Save"
            }
            Button {
                type = "link"
                disabled = saving
                onClick = {
                    pending = emptyMap()
                    editing = emptySet()
                    saveError = null
                }
                +"Discard"
            }
            p {
                className = ClassName("type-hint")
                +"Saving makes every change live for this client at once -- or, for a client with a sandbox, saves a draft the sandbox shows. If the checks fault any of them, none is saved."
            }
            saveError?.let { errorText("Couldn't save the copy.", it) }
        }
    } else {
        note?.let {
            p {
                className = ClassName("subtitle")
                +it
            }
        }
    }
}

/** One key of the file view: its address and status, its value, what it replaced, and its editor when open. */
private fun ChildrenBuilder.copyKeyItem(
    row: CopyKeyRow,
    pending: Map<CopyAddress, PendingCopy>,
    isEditing: Boolean,
    isPreviewing: Boolean,
    isRaw: Boolean,
    isRefused: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onDraft: (String) -> Unit,
    onReset: () -> Unit,
    onRevert: () -> Unit,
    onPreview: (Boolean) -> Unit,
    onRaw: (Boolean) -> Unit,
) {
    val change = pending[row.address]
    val editable = copyRowEditable(row, pending)
    div {
        key = row.address.nsKey.unsafeCast<Key>()
        className = ClassName(
            listOfNotNull("copy-key", "copy-key-pending".takeIf { change != null }, "copy-key-refused".takeIf { isRefused }).joinToString(" "),
        )
        div {
            className = ClassName("copy-key-head")
            code { +row.address.nsKey }
            copyRowStatus(row, pending)?.let {
                span {
                    className = ClassName("copy-key-status")
                    +it
                }
            }
            if (isRefused) {
                span {
                    className = ClassName("copy-key-status copy-key-status-refused")
                    +"named in the refusal below"
                }
            }
            span {
                className = ClassName("copy-key-actions")
                // An orphan has no shipped key to set, so a save of it would only be refused: reset is what works there.
                if (!row.orphan && !isEditing && change?.reset != true) {
                    span {
                        // Said on the wrapper: a disabled button takes no pointer events, so its own title would never show.
                        if (!editable) title = "Save or discard the changes to ${pendingCopyFile(pending)} first: a save is of one file."
                        Button {
                            type = "link"
                            size = "small"
                            disabled = busy || !editable
                            onClick = { onEdit() }
                            +"Edit"
                        }
                    }
                }
                if (copyRowOffersReset(row, pending)) {
                    Button {
                        type = "link"
                        size = "small"
                        disabled = busy || !editable
                        onClick = { onReset() }
                        +"Reset"
                    }
                }
                if (change != null) {
                    Button {
                        type = "link"
                        size = "small"
                        disabled = busy
                        onClick = { onRevert() }
                        +"Revert"
                    }
                }
                Button {
                    type = "link"
                    size = "small"
                    onClick = { onRaw(!isRaw) }
                    +(if (isRaw) "Rendered" else "As written")
                }
            }
        }
        if (isEditing) {
            textarea {
                // `code` draws the inset well; `copy-edit` keeps the page's own face, since the value is prose.
                className = ClassName("code json-edit copy-edit")
                value = change?.value ?: row.value
                disabled = busy
                spellCheck = true
                onChange = { e -> onDraft(e.target.value) }
            }
            p {
                className = ClassName("type-hint")
                +copySyntaxHint(row.audience)
            }
            div {
                className = ClassName("row")
                Checkbox {
                    checked = isPreviewing
                    onChange = { e -> onPreview(e.target.checked == true) }
                    +"Preview"
                }
                Button {
                    type = "link"
                    size = "small"
                    onClick = { onClose() }
                    +"Close"
                }
            }
            if (isPreviewing) copyValuePanel(change?.value ?: row.value, isRaw = false)
        } else {
            copyValuePanel(copyRowShownValue(row, pending), isRaw)
        }
        // What the client's change replaced: the shipped copy, and its own source value when a stored one overrides it.
        val o = row.override
        if (o != null && !o.orphan) {
            details {
                className = ClassName("copy-key-was")
                summary { +"What it replaces" }
                p {
                    className = ClassName("subtitle")
                    +"Shipped:"
                }
                copyValuePanel(o.baseValue, isRaw)
                o.sourceValue?.let {
                    p {
                        className = ClassName("subtitle")
                        +"Set in the client's source configuration (what a reset returns to):"
                    }
                    copyValuePanel(it, isRaw)
                }
            }
        }
    }
}

/**
 * A value in its panel: rendered as Markdown, or as written. A long one scrolls within the panel, so a whole file
 * does not take the page's height; placeholders and pulls show as written either way -- there is nothing here to fill
 * them from. An empty value says so, rather than drawing an empty box.
 */
private fun ChildrenBuilder.copyValuePanel(value: String?, isRaw: Boolean) {
    if (value.isNullOrEmpty()) {
        p {
            className = ClassName("subtitle copy-value-empty")
            +"(empty)"
        }
        return
    }
    if (isRaw) {
        pre {
            className = ClassName("copy-value copy-value-raw")
            +value
        }
    } else {
        div {
            className = ClassName("copy-value")
            Markdown { source = value }
        }
    }
}
