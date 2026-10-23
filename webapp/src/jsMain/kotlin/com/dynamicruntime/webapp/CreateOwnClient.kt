package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.util.isPlaceZoneName
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.joinAsPhrase
import com.dynamicruntime.common.webasset.WebAssetKinds
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.span

private val createOwnScope = MainScope()

/**
 * The body of `POST /user/self/createClient` (issue #1228): the client's [name], trimmed; its note and time zone only
 * when given -- the zone the creator's browser is in, as the card starts it (issue #1115). Pure, and covered under
 * `jsNodeTest`.
 */
fun createOwnClientRequest(
    name: String,
    description: String,
    timeZone: String,
    /** The new client's home copy (issue #1336), by the create's field names ([CLD.homeCopyFields]' keys). */
    home: Map<String, String> = emptyMap(),
    /** Whether the client starts with a creation workflow and a survey (issue #1346); sent only when it does not. */
    starterWorkflows: Boolean = true,
): Map<String, Any?> = buildMap {
    put(CLD.name, name.trim())
    if (!starterWorkflows) put(CLD.starterWorkflows, false)
    description.trim().ifEmpty { null }?.let { put(CLD.description, it) }
    timeZone.trim().ifEmpty { null }?.let { put(CLD.timeZone, it) }
    for (field in CLD.homeCopyFields.keys) home[field]?.trim()?.ifEmpty { null }?.let { put(field, it) }
}

/** An image picked for a web-asset slot (issue #1336): the browser's `File`, its name, content type and size. */
class PickedImage(val file: Any, val name: String, val mimeType: String, val size: Long)

/**
 * What is wrong with an image of [mimeType] and [size] for the web-asset [slot] (issue #1336), or null when the
 * server would take it -- asked before the create, so no client is made only to have its image refused. The kinds
 * and limits are the server's own ([WebAssetKinds]); the server still decides, by the bytes. Pure, and covered under
 * `jsNodeTest`.
 */
fun imageProblem(slot: String, mimeType: String, size: Long): String? {
    val def = WebAssetKinds.slotDef(slot) ?: return "'$slot' is not an image this client has."
    val what = def.label.lowercase()
    if (WebAssetKinds.declaredKind(mimeType) == null) {
        return "The $what must be ${joinAsPhrase(WebAssetKinds.kinds.map { it.label }, last = "or")}."
    }
    if (size <= 0L) return "The $what file is empty."
    if (size > def.maxSize) return "The $what is ${byteSizeText(size)}; it may be at most ${byteSizeText(def.maxSize.toLong())}."
    return null
}

/**
 * What a slot takes, for the picker beside it: "PNG, SVG, ICO, JPEG or WebP, up to 256 KB.", worded as the Clients
 * page's web assets word it ([imageTakesText]). Pure.
 */
fun imageLimitText(slot: String): String =
    imageTakesText(WebAssetKinds.kinds.map { it.label }, WebAssetKinds.slotDef(slot)?.maxSize?.toLong() ?: 0L)

/** One image the create could not upload (issue #1336): which, as a person calls it, and why. */
class ImageUploadFailure(val label: String, val reason: String)

/**
 * What the card says when the client was made and moved to, but an image did not upload (issue #1336): which, and
 * why, and that it can be added from My client; null when every one did, and the page moves home. Pure, and covered
 * under `jsNodeTest`.
 */
fun imageUploadsNote(failures: List<ImageUploadFailure>): String? {
    if (failures.isEmpty()) return null
    // A reason is the server's sentence, period and all; the note ends its own.
    val each = failures.joinToString("; ") { "the ${it.label.lowercase()} couldn't be uploaded: ${it.reason.trim().trimEnd('.')}" }
    return "Your client was created, but $each. Add ${if (failures.size == 1) "it" else "them"} from My client."
}

/**
 * What the card adds when the client was made but the session did not move to it (issue #1336): the images picked --
 * [labels], as a person calls them -- were not uploaded, since only the client's administrator may set them. Null
 * when none was picked. Pure, and covered under `jsNodeTest`.
 */
fun imagesNotUploadedNote(labels: List<String>): String? {
    if (labels.isEmpty()) return null
    val what = joinAsPhrase(labels.map { it.lowercase() })
    return "The $what you chose ${if (labels.size == 1) "wasn't" else "weren't"} uploaded."
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
    suspend fun create(
        name: String,
        description: String,
        timeZone: String,
        home: Map<String, String> = emptyMap(),
        starterWorkflows: Boolean = true,
    ): CreateOwnClientResult = parseCreateOwnClientResult(
        Http.sendApi("POST", AEP.createOwnClient, createOwnClientRequest(name, description, timeZone, home, starterWorkflows))[EP.results]
            .toJsonMapOrEmpty(),
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
    // Whether it starts with a creation workflow and a survey (issue #1346): on unless the person says otherwise.
    var starters by useState(true)
    var busy by useState(false)
    var error by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    // The new client's home page (issue #1336): its copy, by the create's field names, and the images picked per slot.
    var home by useState<Map<String, String>>(emptyMap())
    var images by useState<Map<String, PickedImage>>(emptyMap())
    // Set once the client is made and moved to but an image did not upload: the card stays to say so, then goes on.
    var continueReady by useState(false)
    val bump = useRefreshBump()
    val imageProblems = images.mapValues { (slot, img) -> imageProblem(slot, img.mimeType, img.size) }.filterValues { it != null }

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
                // Made and moved to, but an image did not upload: the client exists, so nothing here is to be created
                // again -- the note and the way on below are all the card says.
                if (continueReady) {
                    h2 { +"Your client" }
                } else {
                    h2 { +"Create your own client" }
                    p { +"A client of your own, to build forms and workflows in, with you as its administrator." }
                    ul {
                        li { +"Its id is made from the name you give it." }
                        li { +"It is on the free plan." }
                        li { +"You become its administrator, and the page moves there once it is made." }
                        li { +"A person creates one; once yours is made, this card opens it." }
                    }
                    textField("Name", name, disabled = busy, placeholder = "The name people see", maxLength = 80) { name = it }
                    // "Internal": the home page below has a note of its own, which the client's people do see.
                    textField("Internal note", description, disabled = busy, placeholder = "Optional: who, what or why; only administrators see it") { description = it }
                    choiceRow("Time zone") {
                        TimeZonePicker {
                            value = timeZone
                            disabled = busy
                            onChange = { v -> timeZone = v }
                        }
                    }
                    div {
                        Checkbox {
                            checked = starters
                            disabled = busy
                            onChange = { e -> starters = e.target.checked == true }
                            +"Start with a creation workflow and a survey"
                        }
                        div {
                            className = ClassName("type-hint")
                            +("Two workflows built around a form's name, so your client's people can make a form at once. " +
                                "They count toward the free plan's workflows, and can be renamed or removed from My client.")
                        }
                    }
                    // Its home page, set as it is made (issue #1336): the copy is published with the client, so it shows
                    // at once, and the images upload once the client exists. All of it optional, and all of it changeable.
                    h3 { +"Your client's home page (optional)" }
                    p { className = ClassName("subtitle"); +"All of this can be changed later, from My client." }
                    fun homeText(field: String) = home[field].orEmpty()
                    fun setHome(field: String, value: String) { home = home + (field to value) }
                    textField("Wordmark", homeText(CLD.homeBrand), disabled = busy, placeholder = "The name beside your logo", maxLength = CLD.homeBrandMaxLength) {
                        setHome(CLD.homeBrand, it)
                    }
                    textField("Title", homeText(CLD.homeTitle), disabled = busy, placeholder = "Welcome", maxLength = CLD.homeTitleMaxLength) { setHome(CLD.homeTitle, it) }
                    for ((field, label) in listOf(CLD.homeIntro to "Introduction", CLD.homeNote to "Note")) {
                        choiceRow(label) {
                            TextArea {
                                value = homeText(field)
                                disabled = busy
                                placeholder = if (field == CLD.homeIntro) "What your client is for. Markdown." else "Optional, beneath the introduction. Markdown."
                                maxLength = CLD.homeTextMaxLength
                                asDynamic()["autoSize"] = js("({ minRows: 2, maxRows: 8 })")
                                onChange = { e -> setHome(field, e.target.value as String) }
                            }
                        }
                    }
                    for (def in WebAssetKinds.slotDefs) {
                        choiceRow(def.label) {
                            val picked = images[def.slot]
                            FilePickButton {
                                // Qualified: the card's own `label` (the client's) would otherwise be what this names.
                                this.label = if (picked == null) "Choose\u2026" else "Change\u2026"
                                accept = WebAssetKinds.acceptedMimeTypes
                                this.busy = false
                                disabled = busy
                                onPicked = { file, size ->
                                    val f = file.asDynamic()
                                    images = images + (def.slot to PickedImage(file, f.name as String, (f.type as? String).orEmpty(), size))
                                }
                            }
                            span { className = ClassName("subtitle"); +" ${picked?.name ?: imageLimitText(def.slot)}" }
                            if (picked != null) {
                                Button {
                                    type = "link"
                                    disabled = busy
                                    onClick = { images = images - def.slot }
                                    +"Remove"
                                }
                            }
                            imageProblems[def.slot]?.let { p { className = ClassName("error-text"); +it } }
                        }
                    }
                    Button {
                        type = "primary"
                        loading = busy
                        disabled = name.isBlank() || imageProblems.isNotEmpty()
                        onClick = {
                            act {
                                val result = CreateOwnClientApi.create(name, description, timeZone, home, starters)
                                val next = createOwnClientNext(result)
                                fun labelOf(slot: String) = WebAssetKinds.slotDef(slot)?.label ?: slot
                                note = next.note
                                if (!next.moves) {
                                    // Not the client's administrator here, so the images picked cannot be set: say so.
                                    note = listOfNotNull(next.note, imagesNotUploadedNote(images.keys.map(::labelOf))).joinToString(" ")
                                    bump()
                                    return@act
                                }
                                // Moved: the session is the new client's administrator, who may set its images -- each
                                // slot its own upload, so they go together.
                                val client = result.client
                                val failures = coroutineScope {
                                    images.map { (slot, img) ->
                                        async {
                                            try {
                                                ClientsApi.uploadWebAsset(client, slot, img.file)
                                                null
                                            } catch (e: Throwable) {
                                                ImageUploadFailure(labelOf(slot), userFacingError(e).text)
                                            }
                                        }
                                    }.awaitAll().filterNotNull()
                                }
                                val uploadsNote = imageUploadsNote(failures)
                                if (uploadsNote == null) {
                                    afterSessionChange(bump)
                                } else {
                                    note = uploadsNote
                                    continueReady = true
                                }
                            }
                        }
                        +"Create the client"
                    }
                }
            }
        }
        note?.let { p { className = ClassName("subtitle"); +it } }
        if (continueReady) {
            Button {
                type = "primary"
                onClick = { afterSessionChange(bump) }
                +"Continue to your client"
            }
        }
        error?.let { errorText(if (made != null) "Couldn't open your client." else "Couldn't create the client.", it) }
    }
}
