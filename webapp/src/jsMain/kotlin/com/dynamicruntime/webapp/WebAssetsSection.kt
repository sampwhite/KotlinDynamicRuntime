package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val webAssetsScope = MainScope()

external interface WebAssetsSectionProps : Props {
    var clientId: String
    /** When this client is a sandbox, the client it is the sandbox of: it shows that client's, and changes none. */
    var sandboxOf: String?
    /** What an upload or a removal calls once it is live: the page re-reads, and so does the shell. */
    var onChanged: () -> Unit
}

/**
 * A client's own web assets on its detail page (issue #1130): each slot -- the favicon, the brand mark -- with a
 * preview of the image it holds, what the file store says of it (its type, its size, who uploaded it and when),
 * and **Upload** and **Remove**. Read from `/clientAdmin/client/web` on mount and again on every refresh generation.
 *
 * **A change is live as it is made.** A web asset is not part of the client's stored configuration, so there is no
 * draft and no publish, and the section says so above the slots. Picking a file *is* the upload; a removal is asked
 * about first, in a question that names the client it acts on. Each answers the slot as it now is, which is drawn
 * at once. Each success bumps
 * the refresh generation through [WebAssetsSectionProps.onChanged], which is what makes the shell re-read its config
 * and draw the new mark and tab icon without a reload -- for an administrator looking at their own client.
 *
 * A **sandbox** shows its parent's images and may change none of them, since a change would be live for the
 * parent's people: the slots are drawn without their actions, under a note saying where to make one.
 */
val WebAssetsSection = FC<WebAssetsSectionProps> { props ->
    var slots by useState<List<WebAssetSlot>?>(null)
    var loadError by useState<DisplayError?>(null)
    // What is running, which slot's removal is being asked about, and the last failure, beside its slot (issue #1388).
    val rows = useRowActions()
    val status = rows.status
    val latest = useRef(0)
    val lastClient = useRef<String>(null)
    val generation = useRefreshGeneration()

    useEffect(props.clientId, generation) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        // A new client: nothing of the previous one's slots or failures carries over.
        if (lastClient.current != props.clientId) {
            lastClient.current = props.clientId
            slots = null
            // The previous client's failure is not this one's: left set, it would be shown over this one's load
            // and keep its "Loading…" from showing.
            loadError = null
            rows.clear()
        }
        webAssetsScope.launch {
            try {
                val loaded = ClientsApi.webAssets(props.clientId)
                if (latest.current == token) {
                    slots = loaded
                    loadError = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) loadError = userFacingError(e)
            }
        }
    }

    fun run(slot: String, action: String, change: suspend () -> WebAssetSlot?) {
        // The client the change is for. By the time it answers the page may be showing another's slots, and
        // neither its result nor its refusal belongs beside those.
        val forClient = props.clientId
        rows.start(slot, action)
        webAssetsScope.launch {
            try {
                val changed = change()
                if (lastClient.current == forClient) {
                    // Drawn from the answer, before the re-read the bump below starts: the slot is as it now is
                    // the moment its controls come back.
                    changed?.let { slots = withWebAssetSlot(slots, it) }
                }
                props.onChanged()
            } catch (e: Throwable) {
                if (lastClient.current == forClient) rows.fail(slot, e, props.onChanged)
            } finally {
                rows.finish()
            }
        }
    }

    val parent = props.sandboxOf
    p {
        className = ClassName("subtitle")
        if (parent == null) {
            +"The images this client's people see in place of the deployment's. A change takes effect at once for everyone in the client: these are not part of its stored configuration, so there is no draft and no publish."
        } else {
            +"A sandbox shows the web assets of $parent. A change to them takes effect for $parent's people at once, so it is made on that client's own page, not here."
        }
    }
    loadError?.let { errorText("Couldn't load this client's web assets.", it) }
    val listed = slots
    if (listed == null && loadError == null) {
        p {
            className = ClassName("subtitle")
            +"Loading…"
        }
    }
    listed?.forEach { slot ->
        div {
            key = slot.slot.unsafeCast<Key>()
            className = ClassName("web-asset")
            div {
                className = ClassName("web-asset-preview")
                val preview = webAssetPreviewUrl(props.clientId, slot)
                if (preview == null) {
                    span {
                        className = ClassName("web-asset-none")
                        +"—"
                    }
                } else {
                    img {
                        src = preview
                        alt = "This client's ${slot.label.lowercase()}"
                    }
                }
            }
            div {
                className = ClassName("web-asset-facts")
                div {
                    className = ClassName("web-asset-name")
                    +slot.label
                }
                div { +webAssetHoldsText(slot) }
                webAssetUploadedText(slot)?.let { uploaded ->
                    div {
                        className = ClassName("subtitle")
                        +uploaded
                    }
                }
                if (parent == null) {
                    div {
                        className = ClassName("subtitle")
                        +webAssetTakesText(slot)
                    }
                    div {
                        className = ClassName("web-asset-actions")
                        WebAssetUpload {
                            this.slot = slot
                            this.busy = status.isBusy(slot.slot, WACT.upload)
                            this.disabled = status.running
                            onPicked = { file, size ->
                                val tooLarge = webAssetFileProblem(slot, size)
                                if (tooLarge != null) {
                                    rows.refuse(slot.slot, tooLarge)
                                } else {
                                    run(slot.slot, WACT.upload) { ClientsApi.uploadWebAsset(props.clientId, slot.slot, file) }
                                }
                            }
                        }
                        if (slot.file != null) {
                            // Gone for the client's people as it is confirmed, so it is asked about, and the
                            // question names where it acts (issue #1049) -- as every delete's does.
                            Popconfirm {
                                title = webAssetRemoveQuestion(slot, props.clientId)
                                description = webAssetRemoveConsequence(slot)
                                okText = "Remove"
                                cancelText = "Cancel"
                                placement = "bottomLeft"
                                open = status.confirming == slot.slot
                                onOpenChange = { open -> rows.confirm(if (open) slot.slot else null) }
                                onConfirm = {
                                    rows.confirm(null)
                                    run(slot.slot, WACT.remove) { ClientsApi.removeWebAsset(props.clientId, slot.slot) }
                                }
                                okButtonProps = js("({ danger: true })")
                                disabled = status.running
                                Button {
                                    danger = true
                                    loading = status.isBusy(slot.slot, WACT.remove)
                                    disabled = status.othersBusy(slot.slot, WACT.remove)
                                    +"Remove"
                                }
                            }
                        }
                    }
                }
                status.problemAt(slot.slot)?.let {
                    p {
                        className = ClassName("error-text")
                        +it
                    }
                }
                status.failureAt(slot.slot)?.let { errorText("Couldn't change the ${slot.label.lowercase()}.", it) }
            }
        }
    }
}

external interface WebAssetUploadProps : Props {
    var slot: WebAssetSlot
    /** Whether this slot's own upload or removal is running. */
    var busy: Boolean
    /** Whether any slot's is, so nothing else is started beside it. */
    var disabled: Boolean
    /** A file was chosen: the browser's `File`, and its size in bytes. */
    var onPicked: (file: Any, size: Long) -> Unit
}

/** The upload control of one slot: a [FilePickButton] offering the content types the slot takes. */
private val WebAssetUpload = FC<WebAssetUploadProps> { props ->
    FilePickButton {
        label = if (props.slot.file == null) "Upload…" else "Replace…"
        accept = props.slot.mimeTypes
        busy = props.busy
        disabled = props.disabled
        onPicked = props.onPicked
    }
}
