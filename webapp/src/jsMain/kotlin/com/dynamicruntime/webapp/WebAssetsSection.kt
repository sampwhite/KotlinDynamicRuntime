package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.InputType

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
 * draft and no publish, and the section says so above the slots. Picking a file *is* the upload. Each success bumps
 * the refresh generation through [WebAssetsSectionProps.onChanged], which is what makes the shell re-read its config
 * and draw the new mark and tab icon without a reload -- for an administrator looking at their own client.
 *
 * A **sandbox** shows its parent's images and may change none of them, since a change would be live for the
 * parent's people: the slots are drawn without their actions, under a note saying where to make one.
 */
val WebAssetsSection = FC<WebAssetsSectionProps> { props ->
    var slots by useState<List<WebAssetSlot>?>(null)
    var loadError by useState<DisplayError?>(null)
    // The slot an upload or a removal is running for, and the slot the last failure was for, with what went wrong:
    // a refusal is shown beside the slot it was about.
    var busy by useState<String?>(null)
    var failedSlot by useState<String?>(null)
    var failure by useState<DisplayError?>(null)
    var problem by useState<String?>(null)
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
            failedSlot = null
            failure = null
            problem = null
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

    fun run(slot: String, action: suspend () -> Unit) {
        busy = slot
        failedSlot = null
        failure = null
        problem = null
        webAssetsScope.launch {
            try {
                action()
                props.onChanged()
            } catch (e: Throwable) {
                failedSlot = slot
                failure = userFacingError(e)
            } finally {
                busy = null
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
                            this.busy = busy == slot.slot
                            this.disabled = busy != null
                            onPicked = { file, size ->
                                val tooLarge = webAssetFileProblem(slot, size)
                                if (tooLarge != null) {
                                    failedSlot = slot.slot
                                    failure = null
                                    problem = tooLarge
                                } else {
                                    run(slot.slot) { ClientsApi.uploadWebAsset(props.clientId, slot.slot, file) }
                                }
                            }
                        }
                        if (slot.file != null) {
                            Button {
                                disabled = busy != null
                                onClick = { run(slot.slot) { ClientsApi.removeWebAsset(props.clientId, slot.slot) } }
                                +"Remove"
                            }
                        }
                    }
                }
                if (failedSlot == slot.slot) {
                    problem?.let {
                        p {
                            className = ClassName("error-text")
                            +it
                        }
                    }
                    failure?.let { errorText("Couldn't change the ${slot.label.lowercase()}.", it) }
                }
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

/**
 * The upload control of one slot: a button, and behind it the browser's own file picker, limited to the content
 * types the slot takes. A plain `<input type="file">` rather than antd's Upload, which wants to own the request --
 * as the schema form's file control decided before this one. The input is cleared after each pick, so choosing
 * the same file again -- after a refusal, say -- is still a change the browser reports.
 */
private val WebAssetUpload = FC<WebAssetUploadProps> { props ->
    val picker = useRef<dynamic>(null)
    input {
        ref = picker.unsafeCast<react.Ref<web.html.HTMLInputElement>>()
        className = ClassName("web-asset-picker")
        type = "file".unsafeCast<InputType>()
        accept = props.slot.mimeTypes.joinToString(",")
        onChange = { e ->
            val target = e.target.asDynamic()
            val files = target.files
            if (files != null && (files.length as Int) > 0) {
                val file = files[0]
                props.onPicked(file.unsafeCast<Any>(), (file.size as Number).toLong())
            }
            target.value = ""
        }
    }
    Button {
        type = "primary"
        loading = props.busy
        disabled = props.disabled && !props.busy
        onClick = { picker.current?.click() }
        +(if (props.slot.file == null) "Upload…" else "Replace…")
    }
}
