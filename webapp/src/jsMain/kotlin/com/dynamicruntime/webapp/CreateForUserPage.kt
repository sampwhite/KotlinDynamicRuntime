package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.clearedAt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** Route id for the create-a-form-for-a-user page (issue #672 Slice 3). Frontend-only, reached from the forms
 *  listing's "Create for a user" action for an `allClients` admin; no top-nav entry. */
const val pageCreateForUser = "createForUser"

private val createForUserScope = MainScope()

/** Debounce before a user-search fetch, so a fast typist makes one call, not many. */
private const val userSearchDebounceMs = 200
private fun setCfuTimer(block: () -> Unit, delayMs: Int): Int = js("setTimeout(block, delayMs)") as Int
private fun clearCfuTimer(id: Int) {
    js("clearTimeout(id)")
}

/** The antd options for the user picker: each user's value is their email (the ref the backend resolves), its
 *  label the name-username-email pick label the scope bar uses, so the two pickers read alike. */
private fun userMatchOptions(users: List<AdminUser>): Array<dynamic> = users.map { u ->
    val o: dynamic = js("({})")
    o.value = u.primaryId
    o.label = userPickLabel(u.name, u.username, u.primaryId)
    o
}.toTypedArray()

/**
 * Create a form document **for another user** (issue #672 Slice 3), for an `allClients` admin. Two steps on one
 * page: pick the user (a type-ahead over the users the caller administers), then fill in the form **in that
 * user's client's rules** -- its schema, fetched by the user's client, through the same [SchemaForm] the ordinary
 * create page uses. Submitting posts to the admin on-behalf endpoint, which owns the form by the chosen user in
 * their client while the admin stays the actor. A success returns to the forms listing scoped to that client,
 * flashing the new row.
 *
 * The ordinary [NewFormPage] is untouched: that one always creates in the caller's own client (#672), and this
 * one is the separate surface for creating on someone else's behalf.
 */
@Suppress("DuplicatedCode")
val CreateForUserPage = FC<Props> {
    // Step one: choosing the user.
    var query by useState("")
    var matches by useState<List<AdminUser>>(emptyList())
    var picked by useState<AdminUser?>(null)
    val searchTimer = useRef<Int>(null)

    // Step two: the form, in the picked user's client's rules.
    var catalog by useState<Catalog?>(null)
    var endpoint by useState<EndpointInfo?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    var revalidate by useState(false)
    var focusRequest by useState(0)
    var loadingSchema by useState(false)
    var loadError by useState<DisplayError?>(null)
    var running by useState(false)
    var runError by useState<DisplayError?>(null)

    // Debounce a user search on the typed term; a short term is not worth a fetch.
    useEffect(query) {
        searchTimer.current?.let { clearCfuTimer(it) }
        searchTimer.current = null
        val term = query.trim()
        if (term.length < 2) {
            matches = emptyList()
        } else {
            searchTimer.current = setCfuTimer({
                createForUserScope.launch {
                    matches = runCatching {
                        AdminApi.searchUsers(UserSearchQuery(anyText = term), limit = maxUserSuggestions).users
                    }.getOrDefault(emptyList())
                }
            }, userSearchDebounceMs)
        }
    }

    // Load the chosen user's client's create schema when a user is picked (issue #714 mechanism: resolve the
    // endpoint on that client's surface). Re-runs if the picked user changes.
    useEffect(picked?.userId) {
        val user = picked ?: return@useEffect
        catalog = null
        endpoint = null
        values = emptyMap()
        failures = null
        revalidate = false
        runError = null
        loadError = null
        loadingSchema = true
        createForUserScope.launch {
            try {
                val fetched = fetchFormEndpoint(HttpMethod.POST.name, GEP.formDocCreate, user.client)
                catalog = fetched
                endpoint = findFormCreateEndpoint(fetched.endpoints)
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            } finally {
                loadingSchema = false
            }
        }
    }

    useFocusOnFailure(focusRequest, failures)

    div {
        className = ClassName("card wide")
        h1 { +"Create a form for a user" }
        // Back to the listing the page was opened from, carrying its filter and sort (the shared helper).
        formsBackToListing()

        // Step one: the user picker. Kept visible after a pick so the admin can change who the form is for.
        div {
            className = ClassName("row forms-scope")
            span {
                className = ClassName("forms-scope-label")
                +"For user"
            }
            AutoComplete {
                placeholder = "a name, email, or id"
                value = query
                options = userMatchOptions(matches)
                allowClear = true
                // The backend already narrowed the fetched matches to the term; show them all.
                filterOption = false
                // Enter must not commit a merely-matching suggestion; a user is chosen by clicking or arrowing.
                defaultActiveFirstOption = false
                style = js("({ width: 360 })")
                onChange = { v -> query = (v as? String) ?: "" }
                onSelect = { v ->
                    val email = (v as? String) ?: ""
                    val chosen = matches.firstOrNull { it.primaryId == email }
                    if (chosen != null) {
                        picked = chosen
                        query = userPickLabel(chosen.name, chosen.username, chosen.primaryId)
                    }
                }
            }
        }

        val user = picked
        val cat = catalog
        val ep = endpoint
        when {
            user == null -> p {
                className = ClassName("subtitle")
                +"Choose the user this form is for. The form is built with — and owned in — that user's client."
            }
            loadingSchema -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            loadError != null -> errorText("Couldn't load the form for this user's client.", loadError!!)
            cat == null || ep == null -> p {
                className = ClassName("subtitle")
                +("${user.client} defines no form to create — a client declares the traits its " +
                    "forms are built from, and this one declares none yet.")
            }
            else -> {
                val inputType = cat.inputType(ep)
                p {
                    className = ClassName("subtitle")
                    +("Creating a form for ${userPickLabel(user.name, user.username, user.primaryId)} in " +
                        "${user.client}. Add a section for each trait it should carry, then create it.")
                }

                SchemaForm {
                    type = inputType
                    this.values = values
                    editable = true
                    friendly = true
                    cfacts = cat.cfacts
                    layouts = cat.layouts
                    omit = listOf(GDF.allowAdditionalTraits)
                    this.failures = failures
                    onChange = { values = it }
                    onFieldEdit = { path ->
                        failures?.let { current ->
                            failures = current.clearedAt(path).ifEmpty { null }
                            revalidate = true
                        }
                    }
                }

                div {
                    className = ClassName("row")
                    Button {
                        type = "primary"
                        loading = running
                        onClick = {
                            val check = checkInput(inputType, values)
                            failures = check.failures
                            revalidate = false
                            val payload = check.payload
                            if (payload == null) {
                                focusRequest += 1
                            } else {
                                running = true
                                runError = null
                                createForUserScope.launch {
                                    try {
                                        val newId = AdminApi.createFormForUser(user.primaryId, payload)
                                        // Return to the listing scoped to the user's client (issue #714), flashing
                                        // the new row (issue #663). No running=false: this navigation unmounts the page.
                                        val flag = newId?.let { listOf(HP.highlight to it) } ?: emptyList()
                                        navigateHash(listOf(HP.page to HMENU.pageForms, EI.client to user.client) + flag)
                                    } catch (e: Throwable) {
                                        runError = userFacingError(e)
                                        running = false
                                    }
                                }
                            }
                        }
                        +"Create form"
                    }
                }

                if (revalidate) {
                    p {
                        className = ClassName("form-stale")
                        +"Edited since the last check — choose Create form to check it again."
                    }
                }

                failures?.let { fs ->
                    formFailureSummary(
                        fs, appConfig().envAuthDebug, formTraitLayouts(inputType, cat.layouts),
                        defaultSummary = "Please fix these before creating the form",
                        defaultHint = "Fix highlighted errors in entered data before creating the form.",
                    )
                }

                runError?.let { errorText("Couldn't create the form.", it) }
            }
        }
    }
}
