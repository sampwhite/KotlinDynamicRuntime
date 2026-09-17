package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.clearedAt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** Route id for the create-a-form-for-a-user page (issue #672 Slice 3). Frontend-only, reached from the forms
 *  listing's "Create for a user" action for an `allClients` admin; no top-nav entry. */
const val pageCreateForUser = "createForUser"

private val createForUserScope = MainScope()

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
    // Step one: choosing the user. The type-ahead itself is the shared [FormUserPicker]; this page keeps only
    // the chosen user, which drives the second step's schema load.
    var picked by useState<AdminUser?>(null)
    // The user id whose schema fetch is current, so a slower earlier fetch's result is dropped rather than
    // overwriting a newer pick's (issue #715 review).
    val latestPick = useRef<Long>(null)

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

    // Load the chosen user's client's create schema when a user is picked (issue #714 mechanism: resolve the
    // endpoint on that client's surface). Re-runs when the picked user changes -- including to null (the box was
    // cleared or edited), which resets the form so a stale one is never left under a changed pick.
    useEffect(picked?.userId) {
        val user = picked
        catalog = null
        endpoint = null
        values = emptyMap()
        failures = null
        revalidate = false
        runError = null
        loadError = null
        if (user == null) {
            loadingSchema = false
            return@useEffect
        }
        latestPick.current = user.userId
        loadingSchema = true
        createForUserScope.launch {
            try {
                val fetched = fetchFormEndpoint(HttpMethod.POST.name, GEP.formDocCreate, user.client)
                // Drop a result a newer pick has superseded, so a slow fetch cannot leave one client's form under
                // another user's name (issue #715 review). The current pick owns `loadingSchema`.
                if (latestPick.current == user.userId) {
                    catalog = fetched
                    endpoint = findFormCreateEndpoint(fetched.endpoints)
                    loadError = null
                    loadingSchema = false
                }
            } catch (e: Throwable) {
                if (latestPick.current == user.userId) {
                    loadError = userFacingError(e)
                    loadingSchema = false
                }
            }
        }
    }

    useFocusOnFailure(focusRequest, failures)

    div {
        className = ClassName("card wide")
        h1 { +"Create a form for a user" }
        // Back to the listing the page was opened from, carrying its filter and sort (the shared helper).
        formsBackToListing()

        // Step one: the user picker (the shared [FormUserPicker], issue #727). Kept visible after a pick so the
        // admin can change who the form is for; a pick drives the schema load above, and clearing it (onPick
        // null) resets the form.
        FormUserPicker { onPick = { picked = it } }

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
                                        // the new row (issue #663) through the one shared return (issue #758). No
                                        // running=false: this navigation unmounts the page.
                                        navigateHash(formsListingReturn(mapOf(EI.client to user.client), newId, created = true))
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
