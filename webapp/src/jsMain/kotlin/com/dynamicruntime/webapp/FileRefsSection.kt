package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.UADEP
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val fileRefsScope = MainScope()

external interface FileRefsSectionProps : Props {
    var clientId: String
    /** When this client is a sandbox, the client it is the sandbox of: what is listed is that client's latest. */
    var sandboxOf: String?
    /** Whether the client has a sandbox, so a change made here is a draft. */
    var hasSandbox: Boolean
    /** Whether the client takes nothing stored on this node: its files are listed, and none is changed here. */
    var staticHere: Boolean
    /** What a change calls once it has taken effect: the page re-reads. */
    var onChanged: () -> Unit
}

/**
 * A client's file refs on its detail page (issue #1176): each named image its configuration holds -- a preview,
 * what the file is, where it is and the address it is served at -- with **Replace** and **Remove**, and under the
 * list the way to add one: an upload, or where the deployment has opened a root to clients (issue #1164), a file
 * it keeps for the client there. Read from `/clientAdmin/client/files` and `…/files/takes` on mount and again on
 * every refresh generation.
 *
 * **Unlike a web asset, a file ref is configuration**, so a change is live at once only for a client that runs its
 * latest: for one with a sandbox it is a draft the sandbox shows, and the section says which above the list
 * ([fileRefsIntro]) and again after each change. What is listed is then the sandbox's, each ref marked when the
 * client's own people are not served it yet.
 *
 * Picking a file *is* the upload, as for a web asset; a removal is asked about first. Each answers the ref as it
 * now is, which is drawn at once, and bumps the refresh generation through [FileRefsSectionProps.onChanged].
 */
val FileRefsSection = FC<FileRefsSectionProps> { props ->
    var refs by useState<List<FileRefView>?>(null)
    var takes by useState<FileRefTakes?>(null)
    var loadError by useState<DisplayError?>(null)
    // What is running -- which ref and which action ([webAssetBusyKey]) -- and where the last failure was: a ref's
    // id, or [FACT.adding] for the form under the list.
    var busy by useState<String?>(null)
    var confirming by useState<String?>(null)
    var failedAt by useState<String?>(null)
    var failure by useState<DisplayError?>(null)
    var problem by useState<String?>(null)
    var note by useState<String?>(null)
    // The form that adds one.
    var newId by useState("")
    var newDescription by useState("")
    var newRoot by useState<String?>(null)
    var newPath by useState("")
    val latest = useRef(0)
    val lastClient = useRef<String>(null)
    val generation = useRefreshGeneration()

    useEffect(props.clientId, generation) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        // A new client: nothing of the previous one's refs, failures or half-filled form carries over.
        if (lastClient.current != props.clientId) {
            lastClient.current = props.clientId
            refs = null
            takes = null
            loadError = null
            failedAt = null
            failure = null
            problem = null
            note = null
            confirming = null
            newId = ""
            newDescription = ""
            newRoot = null
            newPath = ""
        }
        fileRefsScope.launch {
            try {
                val loaded = ClientsApi.fileRefs(props.clientId)
                val taken = ClientsApi.fileRefTakes(props.clientId)
                if (latest.current == token) {
                    refs = loaded
                    takes = taken
                    loadError = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) loadError = userFacingError(e)
            }
        }
    }

    /** Runs [change] for the ref or form [at]; [done] words what it did, and [added] says the form's work is done. */
    fun run(at: String, action: String, added: Boolean, done: (FileRefChange) -> String, change: suspend () -> FileRefChange) {
        // The client the change is for. By the time it answers the page may be showing another's refs.
        val forClient = props.clientId
        busy = webAssetBusyKey(at, action)
        failedAt = null
        failure = null
        problem = null
        note = null
        fileRefsScope.launch {
            try {
                val changed = change()
                if (lastClient.current == forClient) {
                    // Drawn from the answer, before the re-read the bump below starts.
                    refs = withFileRefChange(refs, changed)
                    note = done(changed)
                    if (added) {
                        newId = ""
                        newDescription = ""
                        newPath = ""
                    }
                }
                props.onChanged()
            } catch (e: Throwable) {
                if (lastClient.current == forClient) {
                    failedAt = at
                    failure = userFacingError(e)
                }
            } finally {
                busy = null
            }
        }
    }

    /** Says [text] beside [at] without sending anything: what the page can tell is wrong by itself. */
    fun refuse(at: String, text: String) {
        failedAt = at
        failure = null
        problem = text
        note = null
    }

    fun upload(at: String, refId: String, file: Any, size: Long, description: String?, added: Boolean) {
        val tooLarge = fileRefFileProblem(takes, size)
        if (tooLarge != null) refuse(at, tooLarge)
        else run(at, FACT.upload, added, ::fileRefUploadedNote) { ClientsApi.uploadFileRef(props.clientId, refId, file, description) }
    }

    val draft = props.sandboxOf != null || props.hasSandbox
    val listed = refs
    val taking = takes
    val editable = !props.staticHere

    p {
        className = ClassName("subtitle")
        +fileRefsIntro(props.sandboxOf, props.hasSandbox)
        if (!editable) +" This client's configuration comes from source code on this node, so its files are not changed here."
    }
    loadError?.let { errorText("Couldn't load this client's files.", it) }
    if (listed == null && loadError == null) {
        p {
            className = ClassName("subtitle")
            +"Loading…"
        }
    }
    note?.let {
        p {
            className = ClassName("subtitle")
            +it
        }
    }
    if (listed != null && listed.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +"This client has no files."
        }
    }
    listed?.forEach { ref ->
        div {
            key = ref.refId.unsafeCast<Key>()
            className = ClassName("web-asset")
            div {
                className = ClassName("web-asset-preview")
                val preview = fileRefPreviewArgs(props.clientId, ref)?.let { Http.apiUrl(UADEP.clientFilesDownload, it) }
                if (preview == null) {
                    span {
                        className = ClassName("web-asset-none")
                        +"—"
                    }
                } else {
                    img {
                        src = preview
                        alt = ref.description ?: "The file ${ref.refId}"
                    }
                }
            }
            div {
                className = ClassName("web-asset-facts")
                div {
                    className = ClassName("web-asset-name")
                    +ref.refId
                    fileRefTags(ref).forEach { tag ->
                        span {
                            key = tag.unsafeCast<Key>()
                            className = ClassName("file-ref-tag")
                            +tag
                        }
                    }
                }
                ref.description?.let { described -> div { +described } }
                div { +fileRefHoldsText(ref) }
                div {
                    className = ClassName("subtitle")
                    +fileRefWhereText(ref)
                    ref.configName?.let { +" In the configuration '$it'." }
                }
                fileRefAddress(ref, Http::fileRefUrl)?.let { address ->
                    div {
                        className = ClassName("subtitle")
                        +fileRefAddressLead(ref)
                        code { +address }
                    }
                }
                if (editable) {
                    div {
                        className = ClassName("web-asset-actions")
                        FilePickButton {
                            label = "Replace…"
                            accept = taking?.mimeTypes.orEmpty()
                            primary = false
                            this.busy = busy == webAssetBusyKey(ref.refId, FACT.upload)
                            disabled = busy != null
                            // No description sent: the ref keeps the one it has.
                            onPicked = { file, size -> upload(ref.refId, ref.refId, file, size, description = null, added = false) }
                        }
                        if (fileRefRemovable(ref)) {
                            Popconfirm {
                                title = fileRefRemoveQuestion(ref, props.clientId, draft)
                                description = fileRefRemoveConsequence(draft)
                                okText = "Remove"
                                cancelText = "Cancel"
                                placement = "bottomLeft"
                                open = confirming == ref.refId
                                onOpenChange = { open -> confirming = if (open) ref.refId else null }
                                onConfirm = {
                                    confirming = null
                                    run(ref.refId, FACT.remove, added = false, ::fileRefRemovedNote) { ClientsApi.removeFileRef(props.clientId, ref.refId) }
                                }
                                okButtonProps = js("({ danger: true })")
                                disabled = busy != null
                                Button {
                                    danger = true
                                    loading = busy == webAssetBusyKey(ref.refId, FACT.remove)
                                    disabled = busy != null && busy != webAssetBusyKey(ref.refId, FACT.remove)
                                    +"Remove"
                                }
                            }
                        }
                    }
                }
                if (failedAt == ref.refId) {
                    problem?.let {
                        p {
                            className = ClassName("error-text")
                            +it
                        }
                    }
                    failure?.let { errorText("Couldn't change the file '${ref.refId}'.", it) }
                }
            }
        }
    }
    if (editable && listed != null && taking != null) {
        div {
            className = ClassName("file-ref-add")
            div {
                className = ClassName("web-asset-name")
                +"Add a file"
            }
            div {
                className = ClassName("file-ref-add-row")
                Input {
                    value = newId
                    placeholder = "Its id, such as heroImage"
                    disabled = busy != null
                    style = js("({ width: 220 })")
                    onChange = { e -> newId = (e.target.value as String).trim() }
                }
                Input {
                    value = newDescription
                    placeholder = "What it is for (optional)"
                    disabled = busy != null
                    style = js("({ width: 320 })")
                    onChange = { e -> newDescription = e.target.value as String }
                }
            }
            div {
                className = ClassName("file-ref-add-row")
                FilePickButton {
                    label = "Upload…"
                    accept = taking.mimeTypes
                    this.busy = busy == webAssetBusyKey(FACT.adding, FACT.upload)
                    disabled = busy != null
                    onPicked = { file, size ->
                        val wrong = fileRefNewIdProblem(newId, listed)
                        if (wrong != null) refuse(FACT.adding, wrong)
                        else upload(FACT.adding, newId, file, size, newDescription.trim().ifEmpty { null }, added = true)
                    }
                }
                span {
                    className = ClassName("subtitle")
                    +fileRefTakesText(taking)
                    fileRefCountText(listed, taking)?.let { +" This client has $it" }
                }
            }
            // Offered only where the deployment has opened a root to clients, which most have not.
            if (taking.roots.isNotEmpty()) {
                div {
                    className = ClassName("subtitle")
                    +"Or name a file the deployment keeps for this client, by its path in the client's own directory there:"
                }
                div {
                    className = ClassName("file-ref-add-row")
                    Select {
                        value = newRoot ?: taking.roots.singleOrNull()
                        placeholder = "Where"
                        disabled = busy != null
                        style = js("({ minWidth: 160 })")
                        options = taking.roots.map { root ->
                            val option: dynamic = js("({})")
                            option.label = root
                            option.value = root
                            option
                        }.toTypedArray()
                        onChange = { chosen -> newRoot = chosen as? String }
                    }
                    Input {
                        value = newPath
                        placeholder = "Its path, such as marks/hero.png"
                        disabled = busy != null
                        style = js("({ width: 320 })")
                        onChange = { e -> newPath = (e.target.value as String).trim() }
                    }
                    Button {
                        loading = busy == webAssetBusyKey(FACT.adding, FACT.local)
                        disabled = busy != null && busy != webAssetBusyKey(FACT.adding, FACT.local)
                        onClick = {
                            val root = newRoot ?: taking.roots.singleOrNull()
                            val wrong = fileRefNewIdProblem(newId, listed)
                                ?: "Choose where the file is.".takeIf { root == null }
                                ?: "Give the file's path.".takeIf { newPath.isEmpty() }
                            if (wrong != null || root == null) {
                                refuse(FACT.adding, wrong ?: "Choose where the file is.")
                            } else {
                                val described = newDescription.trim().ifEmpty { null }
                                run(FACT.adding, FACT.local, added = true, ::fileRefNamedNote) {
                                    ClientsApi.nameLocalFileRef(props.clientId, newId, root, newPath, described)
                                }
                            }
                        }
                        +"Use this file"
                    }
                }
            }
            if (failedAt == FACT.adding) {
                problem?.let {
                    p {
                        className = ClassName("error-text")
                        +it
                    }
                }
                failure?.let { errorText("Couldn't add the file.", it) }
            }
        }
    }
}
