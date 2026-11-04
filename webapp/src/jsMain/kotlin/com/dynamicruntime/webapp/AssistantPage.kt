package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.aria.AriaRole
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.details
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.summary
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLTextAreaElement
import kotlin.time.Duration.Companion.milliseconds

private val assistantScope = MainScope()

/** The page's panels' element ids, which the links at its top lead to. */
private const val panelSandbox = "assistant-sandbox"
private const val panelSwitchOn = "assistant-switch-on"
private const val panelConversation = "assistant-conversation"
private const val panelDrafts = "assistant-drafts"
private const val panelAllowance = "assistant-allowance"

/**
 * The workflow assistant (issue #1527): a client administrator says what they want, and the assistant drafts it in the
 * client's sandbox, for them to try there and publish from the Clients page. Panels: the sandbox, when the client has
 * none (the assistant drafts in it); the conversation; the drafts this conversation made, each to try or publish; and the
 * month's allowance. Nothing here publishes.
 *
 * It works from the client and from inside its sandbox, where "Try it in the sandbox" leaves the person: the backend
 * answers for the person either way (`AssistantTurns.personOf`). The open conversation rides the address
 * ([HP.conversation]); while a turn runs the page polls it every [assistantPollMs], reading only the messages after
 * the last it has, and stops when it is idle.
 */
val AssistantPage = FC<Props> {
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()
    var status by useState<AssistantStatus?>(null)
    var statusError by useState<DisplayError?>(null)
    // Bumped when something the status shows may have changed: a turn ended, or a send was refused for it. Counters
    // are bumped through their setters, since a bump from a coroutine must not count from the render that launched it.
    val (statusReads, setStatusReads) = useState(0)
    var conversations by useState<List<ConversationSummary>?>(null)
    var conversationId by useState(routeParams()[HP.conversation])
    var summary by useState<ConversationSummary?>(null)
    var messages by useState<List<StoredMessage>>(emptyList())
    var turns by useState<List<TurnView>>(emptyList())
    var readError by useState<DisplayError?>(null)
    // The turn just sent, until the conversation says it has ended: polled for even before it reads as running.
    var pendingTurnId by useState<String?>(null)
    val (pollTick, setPollTick) = useState(0)
    // Polls failed in a row: each waits longer before the next (pollDelayMs), and a success starts over.
    var pollFailures by useState(0)
    var text by useState("")
    // The id and the text of a message sent and not answered, so sending it again starts no second turn.
    var unanswered by useState<Pair<String, String>?>(null)
    var sending by useState(false)
    var stopping by useState(false)
    var sendError by useState<DisplayError?>(null)
    var stopError by useState<DisplayError?>(null)
    var sandboxBusy by useState(false)
    var sandboxError by useState<DisplayError?>(null)
    var switchBusy by useState(false)
    var switchError by useState<DisplayError?>(null)
    var tryError by useState<DisplayError?>(null)
    // Drops an answer for a conversation the page has moved on from: a read's token must still be current, and the
    // conversation it read still the one open.
    val readToken = useRef(0)
    val openId = useRef<String>(null)
    openId.current = conversationId
    val transcriptBox = useRef<dynamic>(null)
    // The message box, focused again when an example fills it: the example's button goes as the box fills.
    val inputBox = useRef<HTMLTextAreaElement>(null)
    // Taken by each status read and by a switch-on, so only the latest answer is shown: a read already under way when
    // the assistant is switched on must not put the switch-on panel back.
    val statusToken = useRef(0)

    useEffect(generation, statusReads) {
        val token = (statusToken.current ?: 0) + 1
        statusToken.current = token
        assistantScope.launch {
            // A failed re-read keeps the status the page has: the conversation stays, and the allowance says it is old.
            try {
                val read = AssistantApi.status()
                if (statusToken.current == token) {
                    status = read
                    statusError = null
                }
            } catch (e: Throwable) {
                if (statusToken.current == token) statusError = userFacingError(e)
            }
        }
        assistantScope.launch {
            when (val listed = apiResult { AssistantApi.conversations() }) {
                is ApiResult.Ok -> conversations = listed.value
                else -> console.error("$errorLogPrefix could not list the assistant's conversations: ${listed.failureOrNull()?.message}")
            }
        }
    }

    /**
     * Reads the open conversation [id]: whole when [whole], else only the messages after those the page has. [token] is
     * the read token as the read was asked for -- a poll's, before its wait -- so a conversation opened meanwhile drops it.
     */
    fun read(id: String, whole: Boolean, token: Int = readToken.current ?: 0) {
        val after = if (whole) 0L else messages.lastOrNull()?.seq ?: 0L
        val wasPolling = pollsConversation(summary?.running == true, pendingTurnId, turns)
        assistantScope.launch {
            fun current() = readToken.current == token && openId.current == id
            try {
                val loaded = AssistantApi.conversation(id, after)
                if (!current()) return@launch
                summary = loaded.summary
                messages = if (whole) loaded.messages else messages + loaded.messages.filter { m -> messages.none { it.seq == m.seq } }
                turns = loaded.turns
                readError = null
                pollFailures = 0
                val pending = pendingTurnId?.takeIf { p -> loaded.turns.none { it.turnId == p && it.status != ACNV.statusRunning } }
                pendingTurnId = pending
                if (pollsConversation(loaded.summary.running, pending, loaded.turns)) {
                    setPollTick { it + 1 }
                } else if (wasPolling || !whole) {
                    // A turn ended: what it spent, and the conversation's place in the list, are read again.
                    setStatusReads { it + 1 }
                }
            } catch (e: Throwable) {
                if (!current()) return@launch
                readError = userFacingError(e)
                // A failed poll is tried again, each time a little later: a turn the page was following keeps going on
                // the server, and its answer must not be lost to one dropped read.
                if (wasPolling) {
                    pollFailures += 1
                    setPollTick { it + 1 }
                }
            }
        }
    }

    // A conversation opened -- from the address, the list, or a first message -- is read whole.
    useEffect(conversationId) {
        readToken.current = (readToken.current ?: 0) + 1
        pollFailures = 0
        messages = emptyList()
        turns = emptyList()
        summary = null
        readError = null
        conversationId?.let { read(it, whole = true) }
    }

    // While a turn runs, the conversation is read again every assistantPollMs. The effect is a coroutine React cancels
    // when it re-runs or the page goes, so a wait never outlives the page or doubles up.
    useEffect(pollTick) {
        val id = conversationId
        if (pollTick == 0 || id == null) return@useEffect
        val token = readToken.current ?: 0
        delay(pollDelayMs(pollFailures).milliseconds)
        read(id, whole = false, token = token)
    }

    // The newest message in view as messages arrive, as a chat's is.
    useEffect(messages.size) {
        val box: dynamic = transcriptBox.current
        if (box != null) box.scrollTop = box.scrollHeight
    }

    fun open(id: String?) {
        pendingTurnId = null
        sendError = null
        stopError = null
        // A message never answered belongs to the conversation it was sent in; here it is a new one.
        unanswered = null
        replaceRoute(listOfNotNull(HP.page to ASST.page, id?.let { HP.conversation to it }))
        conversationId = id
    }

    fun send() {
        val message = text.trim()
        if (message.isEmpty()) return
        val id = messageIdFor(unanswered, message, ::newMessageId)
        unanswered = id to message
        sending = true
        sendError = null
        assistantScope.launch {
            try {
                val (conversation, turn) = AssistantApi.send(conversationId, id, message)
                unanswered = null
                text = ""
                pendingTurnId = turn
                if (conversation != conversationId) {
                    replaceRoute(listOf(HP.page to ASST.page, HP.conversation to conversation))
                    conversationId = conversation
                    // The list is read again now, not when the turn ends: it holds the new conversation from the start,
                    // and a new one begun meanwhile does not offer the examples again.
                    setStatusReads { it + 1 }
                } else {
                    setPollTick { it + 1 }
                }
            } catch (e: ApiError) {
                // Answered, if refused: a new message, or this one sent again, gets an id of its own.
                unanswered = null
                sendError = userFacingError(e)
                when (e.errorCode) {
                    ACNV.budgetSpentCode, ACNV.noSandboxCode, ACNV.notOnCode -> setStatusReads { it + 1 }
                    // The conversation changed under the page: read again, it says it cannot go on, or that a turn runs
                    // in it -- which the read then follows.
                    ACNV.readOnlyCode, ACNV.busyCode -> conversationId?.let { read(it, whole = false) }
                }
            } catch (e: Throwable) {
                sendError = userFacingError(e)
            } finally {
                sending = false
            }
        }
    }

    fun stop() {
        val id = conversationId ?: return
        stopping = true
        stopError = null
        assistantScope.launch {
            try {
                AssistantApi.cancel(id)
                setPollTick { it + 1 }
            } catch (e: Throwable) {
                stopError = userFacingError(e)
            } finally {
                stopping = false
            }
        }
    }

    fun turnOnSandbox(client: String) {
        sandboxBusy = true
        sandboxError = null
        assistantScope.launch {
            try {
                ClientsApi.setSandbox(client, true)
                // The shell too: the app bar now offers to open the sandbox.
                bump()
            } catch (e: Throwable) {
                sandboxError = userFacingError(e)
            } finally {
                sandboxBusy = false
            }
        }
    }

    // The person switches the assistant on for their own client, where it is offered (issue #1568).
    fun switchOn() {
        switchBusy = true
        switchError = null
        val token = (statusToken.current ?: 0) + 1
        statusToken.current = token
        assistantScope.launch {
            try {
                val switched = AssistantApi.switchOn()
                if (statusToken.current == token) status = switched
                setStatusReads { it + 1 }
            } catch (e: Throwable) {
                switchError = userFacingError(e)
            } finally {
                switchBusy = false
            }
        }
    }

    // Into the sandbox (unless already there), at the draft's workflow's forms -- or the forms, for traits.
    fun tryIt(draft: AssistantDraft, inSandbox: Boolean) {
        val to = draft.workflowIds.firstOrNull()?.let { listOf(HP.page to pageWorkflowForms, WAGG.workflowId to it) }
            ?: listOf(HP.page to HMENU.pageForms)
        if (inSandbox) {
            navigate(to)
            return
        }
        tryError = null
        assistantScope.launch {
            try {
                AuthApi.openSandbox()
                afterSessionChange(bump, to)
            } catch (e: Throwable) {
                tryError = userFacingError(e)
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"Workflow assistant" }
        p {
            className = ClassName("subtitle")
            +(
                "Say what workflow you want, and the assistant drafts it in your sandbox: try it there, then publish it from " +
                    "the Clients page. It never publishes anything itself."
            )
        }
        val current = status
        when (assistantViewOf(current, statusError != null)) {
            AssistantView.failed -> errorText("Couldn't load the assistant.", statusError!!)
            AssistantView.loading -> p { className = ClassName("subtitle"); +"Loading…" }
            AssistantView.switchOn -> panel(panelSwitchOn, "Switch on the assistant", kind = PanelKind.live) {
                val name = current!!.clientName
                p {
                    +(
                        "The workflow assistant is not on for $name yet, and you can switch it on. It works within a monthly " +
                            "allowance, which this page shows once it is on."
                    )
                }
                p { +current.dataNotice }
                Popconfirm {
                    title = "Switch on the workflow assistant for $name?"
                    description = "What you write to it, and what it reads for you, is sent to Anthropic."
                    okText = "Switch on"
                    cancelText = "Cancel"
                    onConfirm = { switchOn() }
                    Button {
                        type = "primary"
                        loading = switchBusy
                        +"Switch on the assistant"
                    }
                }
                panelNote(switchError, null, "Couldn't switch the assistant on.")
            }
            AssistantView.switchOnFromClient -> p {
                className = ClassName("subtitle")
                val name = current!!.clientName
                +"The workflow assistant is not on for $name yet. Leave the sandbox to switch it on from $name itself."
            }
            AssistantView.off -> p {
                className = ClassName("subtitle")
                +"The workflow assistant is not on for ${current!!.clientName}. A deployment operator switches it on for a client."
            }
            AssistantView.on -> {
                current!!
                val running = summary?.running == true || pendingTurnId != null
                val drafts = draftsOf(messages)
                panelLinks(
                    listOfNotNull(
                        (panelSandbox to "Sandbox").takeIf { !current.hasSandbox },
                        panelConversation to "Conversation",
                        panelDrafts to "Drafts",
                        panelAllowance to "Allowance",
                    ),
                )
                if (!current.hasSandbox) {
                    // Live while it can be turned on here; a client defined in code has it turned on there.
                    panel(panelSandbox, "Sandbox", kind = if (current.sandboxInSource) PanelKind.view else PanelKind.live) {
                        p {
                            +(
                                "The assistant drafts in ${current.clientName}'s sandbox, which ${current.clientName} has not got yet. " +
                                    "With a sandbox, ${current.clientName} itself runs only what is published, and the sandbox runs " +
                                    "everything drafted, with users and data of its own, so a draft can be tried before anyone " +
                                    "else sees it."
                            )
                        }
                        if (current.sandboxInSource) {
                            p {
                                className = ClassName("type-hint")
                                +"${current.clientName} is defined in code, so its sandbox is turned on there, in its definition."
                            }
                        } else {
                            Popconfirm {
                                title = "Turn on ${current.clientName}'s sandbox?"
                                description = "From now on ${current.clientName} runs only published configuration."
                                okText = "Turn on sandbox"
                                cancelText = "Cancel"
                                onConfirm = { turnOnSandbox(current.client) }
                                Button {
                                    type = "primary"
                                    loading = sandboxBusy
                                    +"Turn on sandbox"
                                }
                            }
                        }
                        panelNote(sandboxError, null, "Couldn't turn on the sandbox.")
                    }
                }
                panel(panelConversation, "Conversation", kind = PanelKind.live) {
                    // What its New form asks for is the code's for a client defined there (issue #1569).
                    if (current.sandboxInSource) {
                        p {
                            className = ClassName("type-hint")
                            +(
                                "${current.clientName} is defined in code, so what its New form asks for is changed in code. " +
                                    "The assistant can add a workflow that collects more once a form exists."
                            )
                        }
                    }
                    conversationPicker(conversations, conversationId, summary, running) { open(it) }
                    transcript(transcriptItems(messages), running, transcriptBox)
                    turnEndText(turns.lastOrNull()?.takeIf { !running })?.let { p { className = ClassName("type-hint"); +it } }
                    val readOnly = summary?.readOnly == true
                    val blocked = when {
                        readOnly -> "This conversation cannot go on. Start a new one."
                        current.allowance.spent -> "This month's allowance is used up."
                        !current.hasSandbox -> "Turn on the sandbox first: the assistant drafts there."
                        else -> null
                    }
                    textarea {
                        ref = inputBox
                        className = ClassName("assistant-input")
                        asDynamic()["aria-label"] = "Message to the assistant"
                        value = text
                        disabled = sending || blocked != null
                        placeholder = if (conversationId == null) {
                            "Describe the workflow you want: who fills in what, who reviews it, and when."
                        } else {
                            "Ask for a change, or say what to do next."
                        }
                        onChange = { e -> text = e.target.value }
                        onKeyDown = { e ->
                            if (e.key == "Enter" && (e.metaKey || e.ctrlKey)) {
                                e.preventDefault()
                                if (!running) send()
                            }
                        }
                    }
                    // Somewhere to start, for a person who has not talked to it yet (issue #1569): each fills the box.
                    if (showsExamples(conversationId, conversations, text, blocked)) {
                        div {
                            className = ClassName("assistant-examples")
                            role = AriaRole.group
                            ariaLabel = "Example messages"
                            span { className = ClassName("type-hint"); +"For example: " }
                            for (example in assistantExamples(definedInCode = current.sandboxInSource)) {
                                Button {
                                    size = "small"
                                    onClick = {
                                        text = example
                                        inputBox.current?.focus()
                                    }
                                    +example
                                }
                            }
                        }
                    }
                    div {
                        className = ClassName("row")
                        Button {
                            type = "primary"
                            loading = sending
                            disabled = running || blocked != null || text.isBlank()
                            onClick = { send() }
                            +"Send"
                        }
                        if (running) {
                            Button {
                                loading = stopping
                                onClick = { stop() }
                                +"Stop"
                            }
                            span { className = ClassName("type-hint"); +"The assistant is working…" }
                        }
                    }
                    blocked?.let { p { className = ClassName("type-hint"); +it } }
                    if (conversationId == null) {
                        Disclosure {
                            summaryText = "What is sent to Anthropic"
                            p { +current.dataNotice }
                        }
                    }
                    panelNote(sendError, null, "Couldn't send the message.")
                    panelNote(stopError, null, "Couldn't stop the assistant.")
                    val readLead = if (running) "Couldn't read the conversation; trying again." else "Couldn't read the conversation."
                    panelNote(readError, null, readLead)
                }
                panel(panelDrafts, "Drafts from this conversation") {
                    if (drafts.isEmpty()) {
                        p { className = ClassName("type-hint"); +"Nothing drafted yet." }
                    } else {
                        draftList(drafts, current, onTry = { tryIt(it, current.inSandbox) })
                        p {
                            className = ClassName("type-hint")
                            +"In the sandbox, Design View in the app bar shows which definitions are behind what you see."
                        }
                        panelNote(tryError, null, "Couldn't open the sandbox.")
                    }
                }
                panel(panelAllowance, "Allowance") {
                    div {
                        className = ClassName("allowance-bar")
                        div {
                            className = ClassName(if (current.allowance.spent) "allowance-used spent" else "allowance-used")
                            val width: dynamic = js("({})")
                            width.width = "${allowancePercent(current.allowance)}%"
                            style = width
                        }
                    }
                    p { +allowanceText(current.allowance) }
                    panelNote(statusError, null, "Couldn't read the allowance again; this is what it was.")
                }
            }
        }
    }
}

/** The person's conversations, to reopen one or start another. */
private fun ChildrenBuilder.conversationPicker(
    conversations: List<ConversationSummary>?,
    openId: String?,
    open: ConversationSummary?,
    running: Boolean,
    onOpen: (String?) -> Unit,
) {
    // One just begun is not in the list until the list is read again: named by its own summary meanwhile.
    val listed = conversations.orEmpty().map { it.conversationId to it.title }
    val choices = listed + listOfNotNull(
        openId?.takeIf { id -> listed.none { it.first == id } }?.let { it to (open?.title ?: "This conversation") },
    )
    div {
        className = ClassName("row")
        Select {
            value = openId ?: ""
            disabled = running
            asDynamic()["aria-label"] = "Conversation"
            options = (listOf("" to "New conversation") + choices).map { (v, l) ->
                val o: dynamic = js("({})")
                o.label = l
                o.value = v
                o
            }.toTypedArray()
            style = js("({ minWidth: 320, maxWidth: '100%' })")
            onChange = { v -> onOpen((v as String).ifEmpty { null }) }
        }
        if (openId != null) {
            Button {
                type = "link"
                disabled = running
                onClick = { onOpen(null) }
                +"Start a new conversation"
            }
        }
    }
}

/** The transcript: each thing said, each note, and each line of tool calls, which opens to the calls and their results. */
private fun ChildrenBuilder.transcript(items: List<TranscriptItem>, running: Boolean, box: react.RefObject<dynamic>) {
    if (items.isEmpty() && !running) return
    div {
        className = ClassName("assistant-transcript")
        ref = box.unsafeCast<react.Ref<web.html.HTMLDivElement>>()
        // Read out as answers arrive, without taking the reader's place.
        asDynamic()["aria-live"] = "polite"
        for (item in items) {
            when (item) {
                is TranscriptItem.Said -> div {
                    key = item.key.unsafeCast<Key>()
                    className = ClassName(if (item.fromPerson) "assistant-said from-person" else "assistant-said")
                    if (item.fromPerson) p { +item.text } else Markdown { source = item.text }
                }
                is TranscriptItem.Note -> p {
                    key = item.key.unsafeCast<Key>()
                    className = ClassName("assistant-note")
                    +item.text
                }
                is TranscriptItem.Tools -> details {
                    key = item.key.unsafeCast<Key>()
                    className = ClassName("assistant-tools")
                    summary { codeText(item.label) }
                    for (call in item.calls) {
                        div {
                            key = call.id.unsafeCast<Key>()
                            className = ClassName("assistant-call")
                            p {
                                code { +call.name }
                                +" "
                                span { className = ClassName("type-hint"); +call.input.toJsonStr(compact = true) }
                            }
                            pre {
                                className = ClassName(if (call.isError) "code assistant-result error" else "code assistant-result")
                                +(call.result ?: "Running…")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [text] with its backticked names drawn as code. */
private fun ChildrenBuilder.codeText(text: String) {
    codeSpans(text).forEachIndexed { i, (part, isCode) ->
        if (isCode) code { key = i.toString().unsafeCast<Key>(); +part } else span { key = i.toString().unsafeCast<Key>(); +part }
    }
}

/** Each draft, with the way to try it and the way to publish it. */
private fun ChildrenBuilder.draftList(drafts: List<AssistantDraft>, status: AssistantStatus, onTry: (AssistantDraft) -> Unit) {
    // Publishing is done on the Clients page, in the client's publishing panel -- the sandbox's, from inside it.
    val publishHref = routeHref(
        listOf(
            HP.page to HMENU.pageClients,
            HP.client to if (status.inSandbox) sandboxOf(status.client) else status.client,
            HP.section to panelPublishing,
        ),
    )
    ul {
        className = ClassName("assistant-drafts")
        for (draft in drafts) {
            li {
                key = draft.title.unsafeCast<Key>()
                span { codeText(draft.title) }
                if (draft.workflowIds.isNotEmpty()) {
                    span { className = ClassName("type-hint"); +" — workflows: ${draft.workflowIds.joinToString(", ")}" }
                }
                div {
                    className = ClassName("row")
                    Button {
                        onClick = { onTry(draft) }
                        +"Try it in the sandbox"
                    }
                    a {
                        href = publishHref
                        +"Check impact and publish"
                    }
                }
            }
        }
    }
}
