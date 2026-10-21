package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useState
import web.cssom.ClassName

private val createOwnScope = MainScope()

/**
 * The body of `POST /user/self/createClient` (issue #1228): the client's [name], trimmed; its note and time zone only
 * when given -- the zone the creator's browser is in, as the card starts it (issue #1115). Pure, and covered under
 * `jsNodeTest`.
 */
fun createOwnClientRequest(name: String, description: String, timeZone: String): Map<String, Any?> = buildMap {
    put(CLD.name, name.trim())
    description.trim().ifEmpty { null }?.let { put(CLD.description, it) }
    timeZone.trim().ifEmpty { null }?.let { put(CLD.timeZone, it) }
}

/** What creating one's own client did (issue #1228), as the card reads it. */
class CreateOwnClientResult(
    val client: String,
    /** Whether the session moved to the creator's admin user there; false when it could not, for [createOwnClientFailureText]'s reason. */
    val switched: Boolean,
    /** Whether this node carries the client now. */
    val present: Boolean,
    /** Why the creator's user could not be made, when it could not. */
    val userFailure: String?,
    /** Why loading the client on this node failed, when it did. */
    val loadFailure: String?,
)

/** The result of a create from its `results`. Pure, and covered under `jsNodeTest`. */
fun parseCreateOwnClientResult(results: Map<String, Any?>): CreateOwnClientResult = CreateOwnClientResult(
    client = results[CLD.client].toOptStr().orEmpty(),
    switched = results[CLD.switched] == true,
    present = results[CLD.present] == true,
    userFailure = results[CLD.userFailure].toOptStr(),
    loadFailure = results[CLD.loadFailure].toOptStr(),
)

/**
 * What the card says when a create did not move the session (issue #1228): the client was made -- and is not to be
 * made again -- but the person's user there could not be, or this node does not carry the client. Null when it moved.
 * Pure, and covered under `jsNodeTest`.
 */
fun createOwnClientFailureText(result: CreateOwnClientResult): String? = when {
    result.switched -> null
    result.userFailure != null ->
        "Your client '${result.client}' was created, but your administrator user there could not be: ${result.userFailure}"
    !result.present ->
        "Your client '${result.client}' was created, but this site does not carry it" +
            (result.loadFailure?.let { ": $it" } ?: ".")
    else -> "Your client '${result.client}' was created, but the session could not move to it."
}

object CreateOwnClientApi {
    suspend fun create(name: String, description: String, timeZone: String): CreateOwnClientResult = parseCreateOwnClientResult(
        Http.sendApi("POST", AEP.createOwnClient, createOwnClientRequest(name, description, timeZone))[EP.results].toJsonMapOrEmpty(),
    )
}

external interface CreateOwnClientCardProps : Props {
    /** The self-serve client the caller's person made, or null when they have made none. */
    var made: String?
    /** That client's name, when the caller's users say it. */
    var madeName: String?
}

/**
 * A person's own client, from the home page (issue #1228), for a caller signed in to `public`. Until they have made
 * one: a name, an optional note and a time zone -- starting on the browser's -- with what creating it means, and a
 * create that moves the session to their new administrator user there. Once they have: a way to open it. A person
 * creates one, so the card asks the shell config which ([HomeConfig.selfServeClient]) rather than trying the create.
 * A refusal is shown in the endpoint's own words; a create that made the client but did not move the session says why.
 */
val CreateOwnClientCard = FC<CreateOwnClientCardProps> { props ->
    var name by useState("")
    var description by useState("")
    var timeZone by useState(browserTimeZone().orEmpty())
    var busy by useState(false)
    var error by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    val bump = useRefreshBump()

    fun act(work: suspend () -> Unit) {
        busy = true
        error = null
        createOwnScope.launch {
            try {
                work()
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    div {
        className = ClassName("card home-own-client")
        val made = props.made
        if (made != null) {
            h2 { +"Your client" }
            p { +"You have created your own client, ${props.madeName ?: made}. A person creates one." }
            Button {
                type = "primary"
                disabled = busy
                onClick = {
                    act {
                        AuthApi.switchClient(made)
                        afterSessionChange(bump)
                    }
                }
                +"Open ${props.madeName ?: made}"
            }
        } else {
            h2 { +"Create your own client" }
            p { +"A client of your own, to build forms and workflows in, with you as its administrator." }
            ul {
                li { +"Its id is made from the name you give it." }
                li { +"It is on the free plan." }
                li { +"You become its administrator, and the page moves there once it is made." }
                li { +"You create one: once made, this card opens it." }
            }
            div {
                className = ClassName("row")
                span { className = ClassName("field-label"); +"Name" }
                Input {
                    value = name
                    placeholder = "The name people see"
                    disabled = busy
                    maxLength = 80
                    onChange = { e -> name = e.target.value as String }
                }
            }
            div {
                className = ClassName("row")
                span { className = ClassName("field-label"); +"Note" }
                Input {
                    value = description
                    placeholder = "Optional: who, what or why"
                    disabled = busy
                    onChange = { e -> description = e.target.value as String }
                }
            }
            div {
                className = ClassName("row")
                span { className = ClassName("field-label"); +"Time zone" }
                TimeZonePicker {
                    value = timeZone
                    disabled = busy
                    onChange = { v -> timeZone = v }
                }
            }
            Button {
                type = "primary"
                loading = busy
                disabled = name.isBlank()
                onClick = {
                    act {
                        val result = CreateOwnClientApi.create(name, description, timeZone)
                        val failure = createOwnClientFailureText(result)
                        if (failure == null) {
                            afterSessionChange(bump)
                        } else {
                            // Made, so not to be made again: re-read the config, which now names it, beside the reason.
                            note = failure
                            bump()
                        }
                    }
                }
                +"Create the client"
            }
        }
        note?.let { p { className = ClassName("subtitle"); +it } }
        error?.let { errorText(if (made != null) "Couldn't open your client." else "Couldn't create the client.", it) }
    }
}
