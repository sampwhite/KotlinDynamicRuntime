package com.dynamicruntime.webapp

import com.dynamicruntime.common.fileref.FREF
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
 * what the file is, where it is and the address it is served at -- with **Replace**, **Reword** and **Remove**,
 * and under the list the way to add one: an upload, or where the deployment has opened a root to clients (issue
 * #1164), a file it keeps for the client there. Read from `/clientAdmin/client/files` and `…/files/takes` on mount
 * and again on every refresh generation.
 *
 * **Unlike a web asset, a file ref is configuration**, so a change is live at once only for a client that runs its
 * latest: for one with a sandbox it is a draft the sandbox shows, and the section says which above the list
 * ([fileRefsIntro]) and again after each change. What is listed is then the sandbox's, each ref marked when the
 * client's own people are not served it yet. Every change is sent with how the page said it would take effect
 * ([expectedMode]), and the backend refuses one the page called a draft that would be live: the page words a removal
 * from what it last read of the client, and a sandbox can go in between. Refused so ([saveConflict]), the page is
 * read again, and says how a change now takes effect.
 *
 * Picking a file *is* the upload, as for a web asset; a removal is asked about first. Each answers the ref as it
 * now is, which is drawn at once, and bumps the refresh generation through [FileRefsSectionProps.onChanged].
 *
 * What it decides and what it says are `FileRefs.kt`'s, pure and tested; what is here is the state and the drawing.
 */
val FileRefsSection = FC<FileRefsSectionProps> { props ->
    var refs by useState<List<FileRefView>?>(null)
    var takes by useState<FileRefTakes?>(null)
    // Whether what a ref takes was asked for and could not be had: nothing is added from here then, and it is said.
    var takesFailed by useState(false)
    var loadError by useState<DisplayError?>(null)
    // What is running -- which ref and which action ([webAssetBusyKey]) -- and where the last failure was: a ref's
    // id, or [FACT.adding] for the form under the list.
    var busy by useState<String?>(null)
    var confirming by useState<String?>(null)
    var failedAt by useState<String?>(null)
    var failure by useState<DisplayError?>(null)
    var problem by useState<String?>(null)
    var note by useState<String?>(null)
    // The ref whose description is being reworded, and what has been typed for it.
    var describing by useState<String?>(null)
    var describeText by useState("")
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
            takesFailed = false
            loadError = null
            failedAt = null
            failure = null
            problem = null
            note = null
            confirming = null
            describing = null
            describeText = ""
            newId = ""
            newDescription = ""
            newRoot = null
            newPath = ""
        }
        fileRefsScope.launch {
            try {
                val loaded = ClientsApi.fileRefs(props.clientId)
                if (latest.current == token) {
                    refs = loaded
                    loadError = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) loadError = userFacingError(e)
            }
        }
        // By itself: what a ref takes is not needed to list the refs, and a node that cannot say it -- one a release
        // behind, mid-deploy -- still lists them. Without it nothing is added from here; the rows keep their actions.
        fileRefsScope.launch {
            try {
                val taken = ClientsApi.fileRefTakes(props.clientId)
                if (latest.current == token) {
                    takes = taken
                    takesFailed = false
                }
            } catch (_: Throwable) {
                if (latest.current == token) {
                    takes = null
                    takesFailed = true
                }
            }
        }
    }

    val draft = savesAsDraft(props.sandboxOf, props.hasSandbox)
    val expected = expectedMode(draft)

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
                    describing = null
                    if (added) {
                        newId = ""
                        newDescription = ""
                        newPath = ""
                    }
                }
                props.onChanged()
            } catch (e: Throwable) {
                val conflict = saveConflict(e)
                if (lastClient.current == forClient) {
                    failedAt = at
                    failure = conflict ?: userFacingError(e)
                }
                if (conflict != null) props.onChanged()
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

    val listed = refs
    val taking = takes
    val editable = !props.staticHere

    p {
        className = ClassName("subtitle")
        +fileRefsIntro(props.sandboxOf, props.hasSandbox, props.staticHere)
    }
    loadError?.let { errorText("Couldn't load this client's files.", it) }
    if (listed == null && loadError == null) {
        p {
            className = ClassName("subtitle")
            +"Loading…"
        }
    }
    // One element whether or not there is a note, so that what follows keeps its place -- and with it, where the
    // keyboard is -- when one appears or goes. Empty, it takes no room (`app.css`).
    p {
        className = ClassName("subtitle file-ref-note")
        note?.let { +it }
    }
    if (listed != null && listed.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +fileRefsEmptyText(draft)
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
                        alt = fileRefAltText(ref)
                        // A client may hold two hundred: each is fetched as it nears the screen, not all on opening the page.
                        asDynamic()["loading"] = "lazy"
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
                if (describing == ref.refId) {
                    div {
                        className = ClassName("file-ref-describe")
                        Input {
                            value = describeText
                            placeholder = "What it is for; leave it empty to clear"
                            maxLength = FREF.maxDescriptionLength
                            disabled = busy != null
                            style = js("({ minWidth: 240 })")
                            onChange = { e -> describeText = e.target.value as String }
                        }
                        Button {
                            type = "primary"
                            loading = busy == webAssetBusyKey(ref.refId, FACT.describe)
                            disabled = busy != null && busy != webAssetBusyKey(ref.refId, FACT.describe)
                            onClick = {
                                // What was typed, blank included: blank is how a description is cleared.
                                run(ref.refId, FACT.describe, added = false, ::fileRefDescribedNote) {
                                    ClientsApi.describeFileRef(props.clientId, ref.refId, describeText, expected)
                                }
                            }
                            +"Save"
                        }
                        Button {
                            disabled = busy != null
                            onClick = { describing = null }
                            +"Cancel"
                        }
                    }
                } else {
                    ref.description?.let { described -> div { +described } }
                }
                div { +fileRefHoldsText(ref) }
                div {
                    className = ClassName("subtitle")
                    +fileRefWhereText(ref)
                }
                // What to write in copy to show it (issue #1186), and the address that resolves to for its people.
                div {
                    className = ClassName("subtitle file-ref-address")
                    +"In copy: "
                    code { +fileRefImageMarkdown(ref) }
                }
                fileRefAddress(ref, Http::fileRefUrl)?.let { address ->
                    div {
                        className = ClassName("subtitle file-ref-address")
                        +fileRefAddressLead(ref)
                        code { +address }
                    }
                }
                fileRefShippedHint(ref)?.let { hint ->
                    div {
                        className = ClassName("subtitle")
                        +hint
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
                            onPicked = { file, size ->
                                val tooLarge = fileRefFileProblem(taking, size)
                                if (tooLarge != null) {
                                    refuse(ref.refId, tooLarge)
                                } else {
                                    // No description sent: the ref keeps the one it has.
                                    run(ref.refId, FACT.upload, added = false, ::fileRefUploadedNote) {
                                        ClientsApi.uploadFileRef(props.clientId, ref.refId, file, description = null, expected)
                                    }
                                }
                            }
                        }
                        if (fileRefRemovable(ref)) {
                            Button {
                                disabled = busy != null || describing == ref.refId
                                onClick = {
                                    describing = ref.refId
                                    describeText = ref.description.orEmpty()
                                }
                                +"Reword…"
                            }
                            Popconfirm {
                                title = fileRefRemoveQuestion(ref, props.clientId, draft)
                                description = fileRefRemoveConsequence(ref, draft)
                                okText = "Remove"
                                cancelText = "Cancel"
                                placement = "bottomLeft"
                                open = confirming == ref.refId
                                onOpenChange = { open -> confirming = if (open) ref.refId else null }
                                onConfirm = {
                                    confirming = null
                                    run(ref.refId, FACT.remove, added = false, ::fileRefRemovedNote) {
                                        ClientsApi.removeFileRef(props.clientId, ref.refId, expected)
                                    }
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
    val atLimit = listed?.let { fileRefAtLimitText(it, taking) }
    if (editable && listed != null && atLimit != null) {
        p {
            key = "at-limit".unsafeCast<Key>()
            className = ClassName("subtitle")
            +atLimit
        }
    }
    if (editable && listed != null && takesFailed) {
        p {
            key = "no-takes".unsafeCast<Key>()
            className = ClassName("subtitle")
            +"What a file may be could not be read just now, so none is added from here. Reload to try again."
        }
    }
    // Only once the backend has said what a file may be: the form offers what will be accepted, in its words.
    if (editable && listed != null && taking != null && atLimit == null) {
        div {
            // Keyed, so the form is the same form whatever is drawn above it: a row added by its own upload must
            // not take the keyboard out of it.
            key = "add".unsafeCast<Key>()
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
                    maxLength = FREF.maxRefIdLength
                    disabled = busy != null
                    // antd gives an Input `flex: 1` in a flex row, where a width alone is ignored: an id is
                    // short and says it does not grow, and what it is for takes the room that leaves.
                    style = js("({ width: 220, flex: 'none' })")
                    onChange = { e -> newId = (e.target.value as String).trim() }
                }
                Input {
                    value = newDescription
                    placeholder = "What it is for (optional)"
                    maxLength = FREF.maxDescriptionLength
                    disabled = busy != null
                    style = js("({ minWidth: 240 })")
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
                        val wrong = fileRefAddUploadProblem(newId, listed, taking, size)
                        if (wrong != null) {
                            refuse(FACT.adding, wrong)
                        } else {
                            val described = fileRefNewDescription(newDescription)
                            run(FACT.adding, FACT.upload, added = true, ::fileRefUploadedNote) {
                                ClientsApi.uploadFileRef(props.clientId, newId, file, described, expected)
                            }
                        }
                    }
                }
                span {
                    className = ClassName("subtitle")
                    +fileRefTakesText(taking)
                    fileRefCountText(listed, taking)?.let { +" $it" }
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
                        value = fileRefChosenRoot(newRoot, taking.roots)
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
                        maxLength = FREF.maxPathLength
                        disabled = busy != null
                        style = js("({ minWidth: 240 })")
                        onChange = { e -> newPath = (e.target.value as String).trim() }
                    }
                    Button {
                        loading = busy == webAssetBusyKey(FACT.adding, FACT.local)
                        disabled = busy != null && busy != webAssetBusyKey(FACT.adding, FACT.local)
                        onClick = {
                            val root = fileRefChosenRoot(newRoot, taking.roots)
                            val wrong = fileRefLocalProblem(newId, listed, root, newPath)
                            if (wrong != null || root == null) {
                                refuse(FACT.adding, wrong.orEmpty())
                            } else {
                                val described = fileRefNewDescription(newDescription)
                                run(FACT.adding, FACT.local, added = true, ::fileRefNamedNote) {
                                    ClientsApi.nameLocalFileRef(props.clientId, newId, root, newPath, described, expected)
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
