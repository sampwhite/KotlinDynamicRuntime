package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.iframe
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

external interface MailPreviewPanelProps : Props {
    var clientId: String
    var mail: String
}

private val mailPreviewScope = MainScope()

/**
 * One mail as the client's copy renders it (issue #1799), under its namespace in the copy editor's `mail` file: who it
 * is from (issue #1804), its subject, then its HTML part as a mail client would draw it, or its text part. The server
 * renders it by the code that sends mail, with a made-up address and code, so what shows here is what is sent. Read
 * again whenever the page re-reads, which a save of the copy makes it do; a read still in flight for another client or
 * mail is disowned.
 *
 * The HTML part is drawn in an empty-sandboxed frame: it runs nothing, and its inline styles reach nothing of the page.
 *
 * Theme: #CUSTX.
 */
val MailPreviewPanel = FC<MailPreviewPanelProps> { props ->
    val generation = useRefreshGeneration()
    var preview by useState<MailPreviewView?>(null)
    var failure by useState<DisplayError?>(null)
    var asText by useState(false)
    val latest = useRef(0)

    useEffect(props.clientId, props.mail, generation) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        mailPreviewScope.launch {
            try {
                val loaded = ClientsApi.mailPreview(props.clientId, props.mail)
                if (latest.current == token) {
                    preview = loaded
                    failure = null
                }
            } catch (e: Throwable) {
                if (latest.current == token) failure = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("mail-preview")
        val shown = preview
        when {
            failure != null -> errorText("Couldn't preview the mail.", failure!!)
            shown == null -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            else -> {
                // Who it is from (issue #1804), as an inbox shows it.
                p {
                    className = ClassName("mail-preview-from")
                    +"From: ${shown.from}"
                }
                div {
                    className = ClassName("mail-preview-head")
                    span {
                        className = ClassName("mail-preview-subject")
                        +shown.subject
                    }
                    Button {
                        type = "link"
                        size = "small"
                        onClick = { asText = !asText }
                        +(if (asText) "As HTML" else "As text")
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +"${mailPreviewModeText(shown.mode)} The address and the code are made up."
                }
                if (asText) {
                    pre {
                        className = ClassName("mail-preview-text")
                        +shown.text
                    }
                } else {
                    iframe {
                        className = ClassName("mail-preview-frame")
                        title = "The mail's HTML part"
                        sandbox = ""
                        srcDoc = shown.html
                    }
                }
            }
        }
    }
}
