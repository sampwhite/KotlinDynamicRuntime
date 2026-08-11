package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.clearedAt
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLTextAreaElement
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOrEmpty

/** Coroutine scope for firing suspend catalog calls from React effects. */
private val catalogScope = MainScope()

/**
 * The display engine's endpoint browser, in two "pages": the catalog (an [EndpointTable] of every registered
 * endpoint) and, once a row is selected, that endpoint's page — its identity, description, an interactive
 * input form (validate + run + rendered response), and a link that reveals the output schema. Navigation is a
 * simple view swap on the selection; a "Back to catalog" link clears it.
 */
val EndpointCatalog = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var selected by useState<EndpointInfo?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var editable by useState(true)
    var showOutput by useState(false)
    var failures by useState<List<SchFailure>?>(null)
    // Set when a field is edited after a validation pass, so the form can say that what is (or is no longer)
    // on screen predates the edit. Distinguishes "checked, then changed" from "never checked" -- a fresh form
    // has nothing to be stale about and should stay quiet.
    var revalidate by useState(false)
    // Bumped by a validation that found something, to send focus to the first failing field. A counter rather
    // than an effect on `failures`, because that also changes when an edit clears a failure -- and stealing
    // focus out of the field someone is typing in is exactly the wrong response to them fixing it.
    var focusRequest by useState(0)
    var coerced by useState<String?>(null)
    // The request-JSON panel doubles as an editor: `rawEdited` marks text changed but not yet loaded into the
    // form, and `rawError` holds why the last load attempt failed.
    var rawEdited by useState(false)
    var rawError by useState<String?>(null)
    var response by useState<Map<String, Any?>?>(null)
    var runError by useState<String?>(null)
    var running by useState(false)
    var error by useState<String?>(null)
    // True, once the initial URL-hash restore has run; until then the URL is not written back (so the
    // mount-time sync effect can't clobber a hash we are about to read).
    var restored by useState(false)

    // The request-JSON textarea, so a parse failure can put the caret on the offending character.
    val rawRef = useRef<HTMLTextAreaElement>(null)

    // The latest catalog, readable from the once-registered hashchange listener below (which would otherwise
    // close over the null catalog of the first render).
    val catalogRef = useRef<Catalog>(null)
    catalogRef.current = catalog

    useEffectOnce {
        catalogScope.launch {
            try {
                val fetched = SchemaCatalogApi.fetchCatalog()
                catalog = fetched
                error = null
                // Restore the selected endpoint (and any entered values) from the URL hash, so a refresh or a
                // shared link lands back on the same endpoint with the same input.
                readHash()?.let { hs ->
                    fetched.endpoints.firstOrNull { it.method == hs.method && it.path == hs.path }?.let { ep ->
                        selected = ep
                        values = hs.values
                        // Validate what was restored, so the panel and any failures are on screen immediately.
                        // Otherwise, a payload carried in the URL -- including a bad key someone is midway
                        // through fixing -- is invisible until a button is pressed.
                        if (hs.values.isNotEmpty()) {
                            val restoredResult = coerceAndValidate(
                                fetched.inputType(ep), hs.values, SchOpts(keepAdditionalProperties = true),
                            )
                            failures = restoredResult.failures
                            coerced = payloadText(restoredResult.value)
                        }
                    }
                }
            } catch (e: Throwable) {
                error = "Catalog fetch failed — is `./gradlew :launch:run` running? (${e.message})"
            } finally {
                restored = true
            }
        }
    }

    // Send focus to the first failure once the render carrying it has been committed. It has to be an effect:
    // the row is addressed by a DOM id, and that element does not exist until React has drawn the failures the
    // validation just produced.
    useEffect(focusRequest) {
        if (focusRequest > 0) {
            failures?.firstOrNull()?.let { focusField(it.path) }
        }
    }

    // Keep the URL hash in sync with the current selection and input values (replaceState: no history spam,
    // no hashchange loop). Gated on `restored` so it never overwrites the hash before it has been read.
    useEffect(selected, values, restored) {
        if (restored) {
            writeHash(selected, values)
        }
    }

    // React to hash changes made from OUTSIDE this component -- the app-bar menu ("Endpoint catalog" drops the
    // `m=`/`p=` params) and the back/forward buttons -- by re-deriving the selection from the hash. Our own
    // navigation uses replaceState, which does not fire hashchange, so this never fights the sync effect above.
    // The catalog is read through a ref because this listener is registered once and would otherwise capture
    // the (still null) catalog from the first render.
    useEffectOnce {
        onHashChange {
            val loaded = catalogRef.current
            if (loaded != null) {
                val target = readHash()
                selected = target?.let { hs ->
                    loaded.endpoints.firstOrNull { it.method == hs.method && it.path == hs.path }
                }
                values = target?.values ?: emptyMap()
                failures = null
                revalidate = false
                coerced = null
                rawEdited = false
                rawError = null
                response = null
                runError = null
            }
        }
    }

    val current = selected
    val cat = catalog

    if (current == null || cat == null) {
        // ---- Catalog page: the endpoint table ----
        div {
            className = ClassName("card wide")
            h1 { +"Endpoint catalog" }
            p {
                className = ClassName("subtitle")
                +"Every registered endpoint, discovered from the runtime's /schema/endpoints catalog. Select one to view and run it."
            }
            when {
                error != null -> p {
                    className = ClassName("error-text")
                    +error!!
                }
                cat == null -> p {
                    className = ClassName("subtitle")
                    +"Loading…"
                }
                else -> EndpointTable {
                    endpoints = cat.endpoints
                    onSelect = { ep ->
                        selected = ep
                        values = emptyMap()
                        editable = true
                        showOutput = false
                        failures = null
                        revalidate = false
                        coerced = null
                        rawEdited = false
                        rawError = null
                        response = null
                        runError = null
                    }
                }
            }
        }
    } else {
        // ---- Endpoint page ----
        val inputType = cat.inputType(current)

        // Validates [vals] with the kernel and refreshes the request-JSON panel from the result; returns the
        // coerced payload when there are no failures. Takes the values explicitly rather than reading state,
        // so applying an edit and validating it can happen in one pass -- a `values = x` set earlier in the
        // same handler is not visible until the next render.
        fun validateOn(vals: Map<String, Any?>): Map<String, Any?>? {
            // keepAdditionalProperties: an undeclared key is a failure either way, but the editor has to keep
            // showing it. Dropped, the panel would be rewritten without the key while the error still named it
            // -- a complaint about something no longer on screen and nothing to act on. It never reaches the
            // wire because a failure stops the "send".
            val result = coerceAndValidate(inputType, vals, SchOpts(keepAdditionalProperties = true))
            failures = result.failures
            revalidate = false
            // Asking to be validated and being told "somewhere below, something is wrong" is the case this
            // whole issue is about, so the answer goes to the first problem rather than making it be hunted.
            if (result.failures.isNotEmpty()) focusRequest += 1
            coerced = payloadText(result.value)
            rawEdited = false
            rawError = null
            return if (result.failures.isEmpty()) result.value.toJsonMapOrEmpty() else null
        }

        fun validate(): Map<String, Any?>? = validateOn(values)

        /**
         * Loads the edited JSON back into the form, returning the new values (or null when the text will not
         * parse, in which case the error is shown and nothing changes -- an edit is never silently discarded).
         */
        fun applyRaw(): Map<String, Any?>? {
            val parse = parseRawPayload(coerced ?: "")
            val parsed = parse.values
            if (parsed == null) {
                rawError = parse.error
                // Put the caret on the offending character. A textarea has no way to style one line -- it is a
                // single flat text node -- but selecting the spot gets the browser's own highlight, scrolls it
                // into view, and leaves the cursor where the fix goes. The text is unchanged on a failure, so
                // the element on screen is still the one these offsets refer to.
                parse.offset?.let { at ->
                    rawRef.current?.let { box ->
                        box.focus()
                        box.setSelectionRange(at, minOf(at + 1, box.value.length))
                    }
                }
                return null
            }
            // A picked file has no JSON form, so the panel shows a label for it. Carry the real File across
            // rather than letting its own label overwrite it and quietly empty the field.
            val merged = parsed.mapValues { (key, v) ->
                val existing = values[key]
                if (isBrowserFile(existing) && v == browserFileLabel(existing)) existing else v
            }
            values = merged
            rawEdited = false
            rawError = null
            return merged
        }

        /** The values to act on: pending JSON edits are loaded first, so a button never ignores what is on screen. */
        fun pendingValues(): Map<String, Any?>? = if (rawEdited) applyRaw() else values

        /**
         * Re-reads the form: canonicalizes the values through the schema and validates the result.
         *
         * The pruning is the point. `values` can hold keys no form field represents -- an undeclared property
         * arrives that way when a pasted payload carries one -- and those are invisible in the form yet
         * persist, into the URL hash and across a reload. Coercing with the default options drops them at every
         * level, which is what makes this direction honestly "the data filled out in the form fields".
         *
         * [validateOn]'s display pass deliberately does the opposite and keeps them, so the failure it reports
         * stays visible on the thing it is complaining about. Apply shows you the problem; Validate clears it.
         */
        fun validateFromForm() {
            val pruned = coerceAndValidate(inputType, values).value.toJsonMapOrEmpty()
            values = pruned
            validateOn(pruned)
        }

        div {
            className = ClassName("card wide")

            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { selected = null }
                    +"← Back to catalog"
                }
            }

            h1 { +"${current.method} ${current.path}" }
            current.description?.let {
                p {
                    className = ClassName("subtitle")
                    +it
                }
            }

            // Separate link to reveal the output schema (structure only).
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { showOutput = !showOutput }
                    +if (showOutput) "Hide output schema" else "View output schema"
                }
            }
            if (showOutput) {
                h2 { +"Output schema" }
                SchemaOutline { type = cat.outputType(current) }
            }

            h2 { +"Input parameters" }
            div {
                className = ClassName("row")
                Button {
                    size = "small"
                    onClick = { editable = !editable }
                    +if (editable) "Switch to read-only" else "Switch to edit"
                }
            }
            SchemaForm {
                type = inputType
                this.values = values
                this.editable = editable
                this.failures = failures
                onChange = { values = it }
                // Clearing on edit, rather than re-validating: validation stays something the user asks for
                // (Validate, or Run/Download implicitly), so a field being corrected must not keep showing the
                // complaint about what it used to hold. Descendants go with it -- `clearedAt` -- because a
                // structural edit re-indexes what is inside.
                //
                // Dropping the last failure returns to null, NOT to an empty list. Empty means "validated and
                // clean" and draws the ✓, which editing has not earned: the payload has changed since anything
                // was checked. Three states, and this is the one that keeps them honest.
                onFieldEdit = { path ->
                    failures?.let { current ->
                        val remaining = current.clearedAt(path)
                        failures = remaining.ifEmpty { null }
                        // Something had been checked and has now moved on: say so. Silence here would read as
                        // "nothing wrong", which is exactly the reading dropping the ✓ exists to prevent.
                        revalidate = true
                    }
                }
            }

            div {
                className = ClassName("row")
                if (editable) {
                    Button {
                        // Deliberately reads the FORM, not the panel: this is the form -> JSON direction, and
                        // "Apply to form" is the inverse. That also gives unapplied edits a way out -- there
                        // is otherwise no discard -- which is why the hint below spells both outcomes out.
                        onClick = { validateFromForm() }
                        +"Validate"
                    }
                }
                Button {
                    type = "primary"
                    loading = running
                    onClick = {
                        // Validate first; only send it when the coerced payload has no failures (they're shown).
                        val payload = pendingValues()?.let { validateOn(it) }
                        if (payload != null) {
                            if (cat.isFileDownload(current)) {
                                // The response is the file itself, so hand the URL to the browser and let it
                                // do what the server's Content-Disposition told it to. Fetching and parsing it
                                // here would corrupt the bytes and throw that header away.
                                startDownload(SchemaCatalogApi.downloadUrl(current, payload))
                            } else {
                                running = true
                                response = null
                                runError = null
                                catalogScope.launch {
                                    try {
                                        response = SchemaCatalogApi.invoke(
                                            current,
                                            payload,
                                            multipart = cat.hasFileInput(current),
                                        )
                                    } catch (e: Throwable) {
                                        runError = e.message
                                    } finally {
                                        running = false
                                    }
                                }
                            }
                        }
                    }
                    // A download is not a "run" -- nothing comes back to this page to show.
                    +if (cat.isFileDownload(current)) "Download" else "Run"
                }
            }

            // The third state made visible. Editing drops the failures that the edit invalidated, and with them
            // the ✓ -- but an empty screen says "nothing wrong" just as loudly as the banner did, so the one
            // state the form cannot vouch for is the one that has to announce itself. Shown alongside any
            // failures that survived, since those were computed before the edit as well.
            if (revalidate) {
                p {
                    className = ClassName("form-stale")
                    +("Edited since the last check — choose Validate (or Run) to check it again.")
                }
            }

            failures?.let { fs ->
                if (fs.isEmpty()) {
                    p {
                        className = ClassName("form-ok")
                        +"✓ Valid against the endpoint's input schema."
                    }
                } else {
                    h2 { +"Validation failures" }
                    p {
                        className = ClassName("subtitle")
                        +("Each is also shown against its field above, where one renders it. This list is the " +
                            "complete record — an undeclared property has no field to sit next to, and neither " +
                            "does a failure against the payload as a whole.")
                    }
                    // The path stays here even though the field above repeats the message: this listing is the
                    // one place that says *where*, and it is the same path an error from the server names.
                    //
                    // Each entry is a real button rather than a styled line, so it is reachable and operable
                    // by keyboard for free. A root failure has no field to go to, so it stays plain text.
                    fs.forEach { f ->
                        p {
                            className = ClassName("error-text")
                            // Both wordings when the schema supplies one: the framework's, which names the
                            // wire problem an API caller has to act on, then the schema's in quotes, which is
                            // what the person at the form was shown. Neither substitutes for the other here.
                            val schemaCopy = f.userMessage?.let { " (shown as: “$it”)" } ?: ""
                            val text = "${f.path.ifEmpty { "(root)" }}: ${f.message}${choicesSuffix(f)}$schemaCopy"
                            if (f.path.isEmpty()) {
                                +text
                            } else {
                                button {
                                    className = ClassName("failure-jump")
                                    // Explicit, so wrapping this panel in a form later cannot turn a
                                    // jump-to-field into a submit. Set as a plain attribute rather than
                                    // through the wrappers' ButtonType, whose entry spelling this need not
                                    // ride on.
                                    asDynamic()["type"] = "button"
                                    onClick = { focusField(f.path) }
                                    +text
                                }
                            }
                        }
                    }
                }
            }

            // Wrapped and keyed so the textarea keeps its DOM identity. The blocks above emit a *varying*
            // number of unkeyed siblings -- the failure list is one element when valid and many when not -- and
            // React matches unkeyed children by position, so a change in that count shifted this element's slot
            // and remounted it. A remounted textarea is a new node, which loses the inline height the browser
            // wrote when someone dragged the resize grip. A key pins the identity regardless of position.
            div {
                key = "request-json".unsafeCast<Key>()
                coerced?.let { text ->
                    h2 { +"Request JSON" }
                    p {
                        className = ClassName("subtitle")
                        +("The coerced payload that will be sent. It is editable — paste or splice one in, " +
                            "then choose Apply to form to load it into the fields above and check it.")
                    }
                    // A textarea rather than a rendered block: the caret, focus ring and resize grip say "type
                    // here" without a label having to. Edits accumulate freely -- nothing is parsed until
                    // asked, since splicing JSON by hand is rarely one keystroke's worth of change.
                    textarea {
                        ref = rawRef
                        className = ClassName(if (rawEdited) "code json-edit edited" else "code json-edit")
                        value = text
                        spellCheck = false
                        onChange = { e ->
                            coerced = e.target.value
                            rawEdited = true
                            rawError = null
                        }
                    }
                    div {
                        className = ClassName("row")
                        Button {
                            onClick = { applyRaw()?.let { validateOn(it) } }
                            disabled = !rawEdited
                            +"Apply to form"
                        }
                        if (rawEdited) {
                            span {
                                className = ClassName("type-hint")
                                +("Edited — Apply to form to use it, or Validate to discard it and re-read " +
                                    "the form.")
                            }
                        }
                    }
                    rawError?.let { message ->
                        p {
                            className = ClassName("error-text")
                            +message
                        }
                    }
                }
            }

            runError?.let {
                p {
                    className = ClassName("error-text")
                    +"Request failed: $it"
                }
            }

            response?.let { resp ->
                h2 { +"Response" }
                renderResponse(current.kind, cat.payloadType(current), resp)
                h2 { +"Raw response" }
                pre {
                    className = ClassName("code")
                    +resp.toJsonStr()
                }
            }

            // A second backlink at the bottom, so a long response page doesn't force a scroll back up.
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { selected = null }
                    +"← Back to catalog"
                }
            }
        }
    }
}

/**
 * Renders an endpoint's response payload through the read-only [SchemaForm], unwrapping the protocol envelope
 * by [kind]: a `general` result (`results`) and an `item` are single objects; a `list` (`items`) renders each
 * element. [payloadType] is the resolved element/object type; when it is null (an untyped payload), the payload
 * falls back to formatted JSON.
 */
private fun ChildrenBuilder.renderResponse(kind: String, payloadType: SchType?, response: Map<String, Any?>) {
    when (kind) {
        EndpointKind.list.name -> {
            val items = response[EP.items].toJsonListOrEmpty()
            if (items.isEmpty()) {
                p {
                    className = ClassName("type-hint")
                    +"(no items)"
                }
            }
            items.forEachIndexed { i, item ->
                div {
                    className = ClassName("nested")
                    p {
                        className = ClassName("type-hint")
                        +"[$i]"
                    }
                    renderPayload(payloadType, item.toJsonMapOrEmpty())
                }
            }
        }
        EndpointKind.item.name -> renderPayload(payloadType, response[EP.item].toJsonMapOrEmpty())
        else -> renderPayload(payloadType, response[EP.results].toJsonMapOrEmpty())
    }
}

/** Renders one payload object read-only via [SchemaForm], or as formatted JSON when its type is unknown. */
private fun ChildrenBuilder.renderPayload(type: SchType?, data: Map<String, Any?>) {
    if (type != null) {
        SchemaForm {
            this.type = type
            values = data
            editable = false
            onChange = {}
        }
    } else {
        pre {
            className = ClassName("code")
            +data.toJsonStr()
        }
    }
}

/**
 * The coerced payload rendered for the request-JSON panel. A picked file has no JSON form, so it shows as the
 * label of what was chosen; the payload itself keeps the real file, only this display substitutes.
 */
fun payloadText(value: Any?): String = value.toJsonMapOrEmpty()
    .mapValues { (_, v) -> if (isBrowserFile(v)) browserFileLabel(v) else v }
    .toJsonStr()

/**
 * The outcome of parsing edited request JSON: either the parsed [values], or an [error] saying what is wrong
 * and where. Exactly one is non-null.
 */
class RawParse(val values: Map<String, Any?>?, val error: String?, val offset: Int? = null)

/**
 * Parses edited request JSON back into a form's values (issue #191).
 *
 * Kept as a pure top-level function — plain string in, values or message out — so the parsing and the error
 * wording are covered without a browser, which is where the interesting cases are: a trailing comma, a
 * half-finished paste, a JSON array where an object belongs.
 *
 * Blank text is an empty payload rather than an error: clearing the box to start over should not be scolded.
 */
fun parseRawPayload(text: String): RawParse {
    if (text.isBlank()) {
        return RawParse(emptyMap(), null)
    }
    // A form's values are named fields, so an array or a scalar has nowhere to land. Caught up front because
    // the parser's own complaint for this ("Character '[' indicates a JSON array was present when a map was
    // expected") is accurate but reads as an internal diagnostic rather than an answer.
    if (!text.trim().startsWith("{")) {
        return RawParse(null, "The request has to be a JSON object — one starting with '{'.")
    }
    val parsed = try {
        text.jsonMap()
    } catch (e: KdrException) {
        // The parser records where it gave up; passing that through turns "invalid JSON" into something a
        // person can act on when the payload is fifty lines long.
        val line = e.extraData[KdrException.lineKey]
        val col = e.extraData[KdrException.lineColKey]
        val where = if (line != null) " (line $line, column ${col ?: "?"})" else ""
        // The parser's message ends by restating the position as a raw offset, which is the wrong unit for
        // someone looking at a text box -- line and column are already stated above. Dropping the sentence is
        // cosmetic: if that wording ever changes, the tail simply stays, it does not break.
        val detail = (e.message ?: "could not be parsed").substringBefore(" Error originates at offset")
        // The character offset comes back too: a message can say where the parse broke, but putting the caret
        // there is what actually saves someone hunting for it in a long payload.
        return RawParse(null, "Invalid JSON$where: $detail", e.extraData[KdrException.offsetKey] as? Int)
    }
        ?: return RawParse(null, "The request has to be a JSON object — one starting with '{'.")
    return RawParse(parsed, null)
}

// --- URL-hash routing -------------------------------------------------------------------------------------
// Within the catalog page, the selected endpoint (method + path) and the entered input values (as JSON) live
// in the URL hash (under `page=catalog`), so a refresh or a shared link restores the same endpoint page and
// the same input. Shared hash helpers live in HashRoute.kt.

/** The endpoint identity and input values decoded from the URL hash. */
private class HashState(val method: String, val path: String, val values: Map<String, Any?>)

/** Parses the current URL hash into a [HashState], or null when it names no endpoint. */
private fun readHash(): HashState? {
    val params = hashParams()
    val method = params["m"] ?: return null
    val path = params["p"] ?: return null
    val values = params["v"]?.let { runCatching { it.jsonMap() }.getOrNull() } ?: emptyMap()
    return HashState(method, path, values)
}

/** Writes the current selection + input values into the URL hash. Stays on `page=catalog`; adds the endpoint
 *  (and its values) when one is selected. */
private fun writeHash(endpoint: EndpointInfo?, values: Map<String, Any?>) {
    val params = mutableListOf("page" to "catalog")
    if (endpoint != null) {
        params.add("m" to endpoint.method)
        params.add("p" to endpoint.path)
        val shareable = values.withoutFiles()
        if (shareable.isNotEmpty()) {
            params.add("v" to shareable.toJsonStr(compact = true))
        }
    }
    replaceHash(params)
}

/**
 * The values with any picked file dropped. A file is the one input that cannot travel here: it has no JSON
 * form to serialize into, and a link that restored one would mean a URL could make someone's browser upload a
 * file off their disk. The other fields still round-trip, so a shared link puts you back on the endpoint with
 * everything filled in but the file to pick again.
 */
private fun Map<String, Any?>.withoutFiles(): Map<String, Any?> = filterValues { !isBrowserFile(it) }
