package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.clearedAt
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import kotlin.math.roundToInt
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
import com.dynamicruntime.common.util.fmtD
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toOptLong

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
    var showRaw by useState(false)
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
    var runError by useState<DisplayError?>(null)
    var running by useState(false)
    var error by useState<DisplayError?>(null)
    // True, once the initial URL-hash restore has run; until then the URL is not written back (so the
    // mount-time sync effect can't clobber a hash we are about to read).
    var restored by useState(false)

    // The client selector (issue #394). A caller holding `allClients` can point the catalog at one client, so
    // the listing and every form built from it are that client's -- a field then offers only what that
    // client's schema allows, which is where the per-client narrowing first becomes visible without a test.
    // Null is the caller's own surface, which is what everyone else always sees. `clientChoices` is empty for
    // anyone who cannot pick, so the control simply is not drawn.
    var selectedClient by useState<String?>(null)
    var clientChoices by useState<List<ClientChoice>>(emptyList())

    // Catalog slicing (issue #489), client-side over the already-fetched list. Offered only when the response
    // says filtering is available (an env-authed caller); a caller without env auth is served only the
    // published endpoints and sees no controls, since there is nothing to toggle.
    var publicOnly by useState(false)
    var selectedTags by useState<Set<String>>(emptySet())

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
                            val restored = checkInput(fetched.inputType(ep), hs.values)
                            failures = restored.failures
                            coerced = payloadText(restored.coerced)
                        }
                    }
                }
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                restored = true
            }
        }
    }

    // Offer the client selector only to a caller holding `allClients` -- reading the client list is itself a
    // cross-client question only they can ask (the same rule the Users page follows; see webapp/CLAUDE.md). A
    // failure leaves it hidden rather than erroring: the client is not why anyone came to the catalog.
    useEffectOnce {
        catalogScope.launch {
            val cfg = runCatching { HomeApi.fetchConfig() }.getOrNull()
            if (cfg?.user?.roles?.contains(ROLE.allClients) == true) {
                clientChoices = runCatching { AdminApi.listClients() }.getOrDefault(emptyList())
            }
        }
    }

    // Re-fetch the catalog for the chosen client -- its endpoints and its `$defs` -- and return to the listing,
    // since a different client's surface may not carry the endpoint that was open. Skipped until the first load
    // has run (`restored`), so it does not double-fetch on mount, where `selectedClient` is already null.
    useEffect(selectedClient) {
        if (restored) {
            catalogScope.launch {
                runCatching { SchemaCatalogApi.fetchCatalog(client = selectedClient) }.getOrNull()?.let { fetched ->
                    catalog = fetched
                    selected = null
                    values = emptyMap()
                    error = null
                }
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

    // Keep the URL hash in sync with the current selection and input values. Whether that reaches history as a
    // new entry, a rewrite of the current one, or nothing at all is [hashWrite]'s decision -- a keystroke is
    // not a navigation, but opening an endpoint is, and before #324 both were replaceState and Back left the
    // page. Gated on `restored` so it never overwrites the hash before it has been read.
    useEffect(selected, values, restored, catalog) {
        if (restored) {
            writeHash(selected, values, reachable(hashParams(), catalog))
        }
    }

    // React to hash changes made from OUTSIDE this component -- the app-bar menu ("Endpoint catalog" drops the
    // `m=`/`p=` params) and the back/forward buttons -- by re-deriving the selection from the hash. Neither
    // replaceState nor pushState fires hashchange, so our own writes never re-enter here; and because the
    // selection below is derived *from* the hash, the sync effect above then finds nothing to write.
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
            // Drawn only for a caller who can pick (clientChoices is empty otherwise). Clearing it (allowClear)
            // returns to the caller's own surface -- the null the placeholder describes.
            if (clientChoices.isNotEmpty()) {
                div {
                    className = ClassName("row")
                    span {
                        className = ClassName("field-label")
                        +"Client"
                    }
                    Select {
                        value = selectedClient
                        options = clientOptions(clientChoices)
                        placeholder = "Your own surface"
                        allowClear = true
                        style = js("({ minWidth: 220 })")
                        onChange = { v -> selectedClient = v as? String }
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +"Show a client's endpoints, each form built from that client's schema. Clear to return to your own surface."
                }
            }
            // The publicApi + tag filters (issue #489), drawn only for a caller who may slice the catalog.
            // Without env auth the backend already limited the list to the published set, so there is nothing
            // to filter and the controls would only mislead.
            if (cat != null && cat.filtersAvailable) {
                val tagOptions = availableTags(cat.endpoints)
                div {
                    className = ClassName("row")
                    Checkbox {
                        checked = publicOnly
                        onChange = { e -> publicOnly = e.target.checked }
                        +"Public API only"
                    }
                    if (tagOptions.isNotEmpty()) {
                        span {
                            className = ClassName("field-label")
                            +"Tags"
                        }
                        Select {
                            asDynamic()["mode"] = "multiple"
                            value = selectedTags.toTypedArray()
                            options = tagOptions.map { tag ->
                                val o: dynamic = js("({})")
                                o.label = tag
                                o.value = tag
                                o
                            }.toTypedArray()
                            placeholder = "Any tag"
                            allowClear = true
                            style = js("({ minWidth: 260 })")
                            // Multi-select is OR: an endpoint shows if it carries any selected tag.
                            onChange = { v ->
                                selectedTags = (v as? Array<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
                            }
                        }
                    }
                }
            }
            when {
                error != null -> errorText("Couldn't load the catalog.", error!!)
                cat == null -> p {
                    className = ClassName("subtitle")
                    +"Loading…"
                }
                else -> EndpointTable {
                    endpoints = filterEndpoints(cat.endpoints, publicOnly, selectedTags)
                    onSelect = { ep ->
                        selected = ep
                        values = emptyMap()
                        editable = true
                        showOutput = false
                        showRaw = false
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
            // The coerce-and-validate rule (keepAdditionalProperties, forInput) lives in the shared [checkInput],
            // so this page and the new-form page hold input to the identical contract; see its doc for why each
            // option is set. This adds only the panel's own concerns: the request-JSON text, and focus.
            val check = checkInput(inputType, vals)
            failures = check.failures
            revalidate = false
            // Asking to be validated and being told "somewhere below, something is wrong" is the case this
            // whole issue is about, so the answer goes to the first problem rather than making it be hunted.
            if (!check.isValid) focusRequest += 1
            coerced = payloadText(check.coerced)
            rawEdited = false
            rawError = null
            return check.payload
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
            val pruned = coerceAndValidate(inputType, values, SchOpts(forInput = true)).value.toJsonMapOrEmpty()
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

            // Separate links to reveal the output schema (structure only) and the raw schema of both sides.
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { showOutput = !showOutput }
                    +if (showOutput) "Hide output schema" else "View output schema"
                }
                Button {
                    type = "link"
                    onClick = { showRaw = !showRaw }
                    +if (showRaw) "Hide raw schema" else "View raw schema"
                }
            }
            if (showOutput) {
                h2 { +"Output schema" }
                SchemaOutline { type = cat.outputType(current) }
            }
            // The raw documents (issue #262). The field-and-value views above cannot show everything a schema
            // says -- a discriminator's `defaultMapping`, `additionalProperties`, which type a `$ref` actually
            // resolves to -- and every construct added to the layer has so far needed a presentation invented
            // for it. This one needs none, and so answers for constructs that do not exist yet.
            if (showRaw) {
                h2 { +"Raw input schema" }
                rawSchemaBlock(cat.rawDocument(current.inputSchema))
                h2 { +"Raw output schema" }
                rawSchemaBlock(cat.rawDocument(current.outputSchema))
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
                // The caller's delivered cfacts (issue #564): a g-visibleWhen field this caller may not use is
                // hidden while the invoke form is editable. Read-only, the field still shows -- this surface
                // documents the wire. The backend enforces the condition regardless.
                cfacts = cat.cfacts
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
                                        runError = userFacingError(e)
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
                                    // jump-to-field into a "submit". Set as a plain attribute rather than
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

            runError?.let { errorText("The request failed.", it) }

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
 *
 * The envelope's own fields are shown by [envelopeSummary] above the payload rather than through the form:
 * they describe the *response*, and the form is driven by the payload's `SchType`, which has nothing to say
 * about them (issue #321).
 */
private fun ChildrenBuilder.renderResponse(kind: String, payloadType: SchType?, response: Map<String, Any?>) {
    envelopeSummary(kind, response)?.let { summary ->
        p {
            className = ClassName("type-hint")
            +summary
        }
    }
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

/**
 * A duration in milliseconds at a precision a reader can use: sub-millisecond calls keep two digits so they do
 * not collapse to zero, and anything slow enough to care about is shown whole.
 *
 * Pure, and covered under `jsNodeTest`.
 */
fun roundMs(ms: Double): String = when {
    ms >= 10.0 -> ms.roundToInt().toString()
    ms >= 1.0 -> ((ms * 10).roundToInt() / 10.0).fmtD()
    else -> ((ms * 100).roundToInt() / 100.0).fmtD()
}

/**
 * The one-line envelope summary shown above a response payload: how many items a list returned, whether more
 * are available, and how long the call took. Null when there is nothing worth a line.
 *
 * What is *left out* is the point. `contentHash` is for a machine to compare across fetches (issues #113/#114)
 * and says nothing to a reader; `requestUri` echoes the request just submitted on this very screen; and
 * `webAppHash` is deployment-global, so it is identical on every response ever rendered here. All three remain
 * in the raw panel below, which is complete by definition -- this line is for what a person reads and acts on.
 *
 * `hasMore` and `numAvailable` are declared by `listEndpoint` only for endpoints that opt in, and are not
 * populated by execution yet, so each is read strictly when present rather than assumed.
 *
 * Pure, and covered under `jsNodeTest` -- which is the reason it is separate from the rendering.
 */
fun envelopeSummary(kind: String, response: Map<String, Any?>): String? {
    val parts = mutableListOf<String>()
    if (kind == EndpointKind.list.name) {
        // Fall back to counting what arrived: a list response always has items, even if the count went missing.
        val numItems = response[EP.numItems].toOptLong() ?: response[EP.items].toJsonListOrEmpty().size.toLong()
        parts.add(if (numItems == 1L) "1 item" else "$numItems items")
        if (response[EP.hasMore] == true) parts.add("more available")
        response[EP.numAvailable].toOptLong()?.let { parts.add("$it in total") }
    }
    // `duration` is a Double of milliseconds, so it is rounded for reading rather than truncated: a call taking
    // 0.42ms would otherwise render as "0ms", which reads as a broken timer rather than a fast endpoint.
    (response[EP.duration] as? Number)?.toDouble()?.let { parts.add("${roundMs(it)}ms") }
    return if (parts.isEmpty()) null else parts.joinToString(" \u00b7 ")
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
 * Parses edited JSON back into a map (issue #191).
 *
 * Serves both surfaces that take JSON as text: the request panel, whose text is a whole payload, and a
 * free-form map field, whose text is one property's value (issue #251). They want the identical parse and
 * the identical wording, and [what] names the thing in the one sentence where the noun differs.
 *
 * Kept as a pure top-level function — plain string in, values or message out — so the parsing and the error
 * wording are covered without a browser, which is where the interesting cases are: a trailing comma, a
 * half-finished paste, a JSON array where an object belongs.
 *
 * Blank text is an empty payload rather than an error: clearing the box to start over should not be scolded.
 */
fun parseRawPayload(text: String, what: String = "request"): RawParse {
    if (text.isBlank()) {
        return RawParse(emptyMap(), null)
    }
    // A form's values are named fields, so an array or a scalar has nowhere to land. Caught up front because
    // the parser's own complaint for this ("Character '[' indicates a JSON array was present when a map was
    // expected") is accurate but reads as an internal diagnostic rather than an answer.
    if (!text.trim().startsWith("{")) {
        return RawParse(null, "The $what has to be a JSON object — one starting with '{'.")
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
        ?: return RawParse(null, "The $what has to be a JSON object — one starting with '{'.")
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
    val method = params[HP.method] ?: return null
    val path = params[HP.path] ?: return null
    val values = params[HP.values]?.let { runCatching { it.jsonMap() }.getOrNull() } ?: emptyMap()
    return HashState(method, path, values)
}

/**
 * Writes the current selection + input values into the URL hash. Stays on `page=catalog`; adds the endpoint
 * (and its values) when one is selected. [reachable] says whether the hash as it stands names somewhere this
 * page can actually show — see [hashWrite], which decides how the write reaches history.
 */
private fun writeHash(endpoint: EndpointInfo?, values: Map<String, Any?>, reachable: Boolean) {
    val params = mutableListOf(HP.page to HMENU.pageCatalog)
    if (endpoint != null) {
        params.add(HP.method to endpoint.method)
        params.add(HP.path to endpoint.path)
        val shareable = values.withoutFiles()
        if (shareable.isNotEmpty()) {
            params.add(HP.values to shareable.toJsonStr(compact = true))
        }
    }
    applyHashWrite(params, endpointIdentity, reachable)
}

/** What identifies a catalog destination: which endpoint is open. The entered values refine it, never move it. */
private val endpointIdentity = setOf(HP.method, HP.path)

/**
 * Whether the hash [params] name a destination this page can show: the list, or an endpoint the catalog
 * actually has. A [catalog] not yet fetched can vouch for nothing, so it answers false — and with no catalog
 * there is nothing to navigate between either.
 */
private fun reachable(params: Map<String, String>, catalog: Catalog?): Boolean {
    val method = params[HP.method] ?: return true
    val path = params[HP.path] ?: return true
    return catalog?.endpoints?.any { it.method == method && it.path == path } == true
}

/**
 * The values with any picked file dropped. A file is the one input that cannot travel here: it has no JSON
 * form to serialize into, and a link that restored one would mean a URL could make someone's browser upload a
 * file off their disk. The other fields still round-trip, so a shared link puts you back on the endpoint with
 * everything filled in but the file to pick again.
 */
private fun Map<String, Any?>.withoutFiles(): Map<String, Any?> = filterValues { !isBrowserFile(it) }

/** The distinct tags across [endpoints], sorted -- the options the catalog tag filter offers (issue #489). */
fun availableTags(endpoints: List<EndpointInfo>): List<String> =
    endpoints.flatMap { it.tags }.distinct().sorted()

/**
 * [endpoints] narrowed by the catalog filters (issue #489): [publicOnly] keeps only published endpoints, and
 * [selectedTags] keeps an endpoint carrying **any** of them (OR) -- an empty tag set does not filter. Pure, and
 * covered under `jsNodeTest`; the env-auth restriction is enforced server-side, so this only ever hides
 * endpoints already in hand.
 */
fun filterEndpoints(
    endpoints: List<EndpointInfo>,
    publicOnly: Boolean,
    selectedTags: Set<String>,
): List<EndpointInfo> =
    endpoints.filter { ep ->
        (!publicOnly || ep.publicApi) && (selectedTags.isEmpty() || ep.tags.any { it in selectedTags })
    }

/**
 * One raw schema document, pretty-printed into the same inset well the read-only views already use.
 *
 * A `pre` rather than a textarea: this is a rendering to read and copy, not something to edit. The request
 * panel is a textarea precisely because it *is* editable, and the difference should be visible without having
 * to click.
 */
private fun ChildrenBuilder.rawSchemaBlock(document: Map<String, Any?>) {
    pre {
        className = ClassName("code json-value")
        +document.toJsonStr()
    }
}
