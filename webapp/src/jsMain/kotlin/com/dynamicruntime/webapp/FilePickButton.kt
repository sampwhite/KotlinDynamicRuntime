package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.input
import react.useRef
import web.cssom.ClassName
import web.html.InputType

external interface FilePickButtonProps : Props {
    /** What the button says: "Upload…", "Replace…". */
    var label: String
    /** The content types the browser's picker offers. */
    var accept: List<String>
    /** Whether this control's own upload is running. */
    var busy: Boolean
    /** Whether something else is, so nothing is started beside it. */
    var disabled: Boolean
    /** Drawn as the primary action of its row; a plain button otherwise. */
    var primary: Boolean?
    /** A file was chosen: the browser's `File`, and its size in bytes. */
    var onPicked: (file: Any, size: Long) -> Unit
}

/**
 * A button that picks a file: behind it the browser's own picker, limited to [FilePickButtonProps.accept]. A plain
 * `<input type="file">` rather than antd's Upload, which wants to own the request -- as the schema form's file
 * control decided. The input is cleared after each pick, so choosing the same file again -- after a refusal, say
 * -- is still a change the browser reports. Picking a file *is* the action: there is no second button to send it.
 */
val FilePickButton = FC<FilePickButtonProps> { props ->
    val picker = useRef<dynamic>(null)
    input {
        ref = picker.unsafeCast<react.Ref<web.html.HTMLInputElement>>()
        className = ClassName("web-asset-picker")
        type = "file".unsafeCast<InputType>()
        accept = props.accept.joinToString(",")
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
        if (props.primary != false) type = "primary"
        loading = props.busy
        disabled = props.disabled && !props.busy
        onClick = { picker.current?.click() }
        +props.label
    }
}
