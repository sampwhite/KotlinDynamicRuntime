package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.util.isPlaceZoneName
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

/** Which of its three faces the card shows (issue #1228). */
@Suppress("EnumEntryName")
enum class OwnClientCardMode {
    /** The person has made no client: the create. */
    create,

    /** They have, and this site carries it: a way to open it. */
    open,

    /** They have, and this site no longer carries it: said, since a person creates one and there is no second. */
    unavailable,
}

/** The card's face for a person who [made] a client (null for none) this site carries unless [madeAbsent]. Pure. */
fun ownClientCardMode(made: String?, madeAbsent: Boolean): OwnClientCardMode = when {
    made == null -> OwnClientCardMode.create
    madeAbsent -> OwnClientCardMode.unavailable
    else -> OwnClientCardMode.open
}

/**
 * What the card does after a create (issue #1228): the page moves with the session ([moves]), or -- the client made
 * but the session not moved -- it shows [note] and re-reads the shell config, which now names the client, so the
 * create is not offered again. Pure, and covered under `jsNodeTest`.
 */
class CreateOwnClientNext(val moves: Boolean, val note: String?) {
    /** Whether the card re-reads the config in place, rather than the page moving. */
    val rereads: Boolean get() = !moves
}

fun createOwnClientNext(result: CreateOwnClientResult): CreateOwnClientNext =
    createOwnClientFailureText(result)?.let { CreateOwnClientNext(moves = false, note = it) } ?: CreateOwnClientNext(moves = true, note = null)

/** The zone the card starts on: the browser's, when it is a place's zone a client may name -- never an `Etc/` offset. */
fun ownClientStartZone(browserZone: String?): String = browserZone?.takeIf { isPlaceZoneName(it) }.orEmpty()

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
    /** Whether this site no longer carries [made]. */
    var madeAbsent: Boolean
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
    var timeZone by useState(ownClientStartZone(browserTimeZone()))
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
        val label = props.madeName ?: made
        when (ownClientCardMode(made, props.madeAbsent == true)) {
            OwnClientCardMode.open -> {
                h2 { +"Your client" }
                p { +"You have created your own client, $label. A person creates one." }
                Button {
                    type = "primary"
                    disabled = busy
                    onClick = {
                        act {
                            AuthApi.switchClient(made!!)
                            afterSessionChange(bump)
                        }
                    }
                    +"Open $label"
                }
            }
            OwnClientCardMode.unavailable -> {
                h2 { +"Your client" }
                p { +"You created your own client, $label, but this site no longer carries it. A person creates one, so there is no other to make." }
            }
            OwnClientCardMode.create -> {
                h2 { +"Create your own client" }
                p { +"A client of your own, to build forms and workflows in, with you as its administrator." }
                ul {
                    li { +"Its id is made from the name you give it." }
                    li { +"It is on the free plan." }
                    li { +"You become its administrator, and the page moves there once it is made." }
                    li { +"A person creates one; once yours is made, this card opens it." }
                }
                textField("Name", name, disabled = busy, placeholder = "The name people see", maxLength = 80) { name = it }
                textField("Note", description, disabled = busy, placeholder = "Optional: who, what or why") { description = it }
                choiceRow("Time zone") {
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
                            val next = createOwnClientNext(CreateOwnClientApi.create(name, description, timeZone))
                            note = next.note
                            if (next.moves) afterSessionChange(bump) else bump()
                        }
                    }
                    +"Create the client"
                }
            }
        }
        note?.let { p { className = ClassName("subtitle"); +it } }
        error?.let { errorText(if (made != null) "Couldn't open your client." else "Couldn't create the client.", it) }
    }
}
