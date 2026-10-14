package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/*
 * Becoming someone on a test instance (issue #1183): the deployment admin in one click, any user through the
 * `becomeUser` fixture's own form, and the identities this browser became before -- under Account in the menu, on the
 * Home page as a card, and on the `#page=becomeUser` page. Offered only to a caller with test access, for whom the
 * fixture exists (issue #1149).
 */

private val becomeScope = MainScope()

/** Where this browser keeps the identities it became, in `localStorage`. */
private const val recentIdentitiesKey = "kdrRecentIdentities"
private const val recentIdentitiesWhat = "recent identities"

/** The identities this browser remembers, newest first. */
fun storedIdentities(): List<RecentIdentity> = decodeIdentities(localStorageGet(recentIdentitiesKey, recentIdentitiesWhat))

/**
 * Becomes the user [body] describes, remembers them, and goes home re-reading (`afterSessionChange`), with no reload --
 * what the one-click item, the card, the form and a recent identity all do. A refusal is handed to [onError].
 */
fun becomeAndGo(body: Map<String, Any?>, bump: () -> Unit, onError: (DisplayError) -> Unit = {}) {
    becomeScope.launch {
        when (val result = BecomeUserApi.become(body)) {
            is ApiResult.Ok -> {
                val info = result.value[EP.results].toJsonMapOrEmpty()
                val identity = recentIdentityOf(body, info, appConfig().dataId, Clock.System.now().toString())
                localStorageSet(recentIdentitiesKey, encodeIdentities(rememberIdentity(storedIdentities(), identity)), recentIdentitiesWhat)
                afterSessionChange(bump)
            }
            else -> result.failureOrNull()?.let { onError(userFacingError(it)) }
        }
    }
}

/** Becomes the deployment admin: `admin` plus `allClients` (issue #1183). */
fun becomeDeploymentAdmin(bump: () -> Unit, onError: (DisplayError) -> Unit = {}) = becomeAndGo(deploymentAdminBody(), bump, onError)

/**
 * The card on the Home page (issue #1183), for a caller with test access: become the deployment admin, open the form
 * for anyone else, or become again one of the identities this browser remembers.
 */
val TestAccessCard = FC<Props> {
    val bump = useRefreshBump()
    var error by useState<DisplayError?>(null)
    val (current, _) = identitiesFor(storedIdentities(), appConfig().dataId)
    div {
        className = ClassName("test-access-card")
        h2 { +"This is a test instance" }
        div {
            className = ClassName("row")
            Button {
                type = "primary"
                onClick = { error = null; becomeDeploymentAdmin(bump) { error = it } }
                +"Become deployment admin"
            }
            a {
                href = "#page=${HMENU.pageBecomeUser}"
                className = ClassName("back-link")
                +"Become a user…"
            }
        }
        recentIdentityButtons(current, bump) { error = it }
        error?.let { errorText("Couldn't become that user.", it) }
    }
}

/**
 * The `#page=becomeUser` page (issue #1183): the deployment admin in one click, the fixture's form -- drawn from the
 * endpoint's own schema, as the Simulations page draws each simulation's -- and the identities this browser
 * remembers, the ones from data since reset greyed with no button, since their users are gone.
 */
val BecomeUserPage = FC<Props> {
    val bump = useRefreshBump()
    var catalog by useState<Catalog?>(null)
    var loadError by useState<DisplayError?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    var error by useState<DisplayError?>(null)
    var running by useState(false)
    val (recent, setRecent) = useState(storedIdentities())

    useEffectOnce {
        becomeScope.launch {
            try {
                catalog = BecomeUserApi.fetchForm()
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            }
        }
    }

    val (current, stale) = identitiesFor(recent, appConfig().dataId)
    div {
        className = ClassName("card wide")
        h1 { +"Become a user" }
        p {
            className = ClassName("subtitle")
            +"Sign in as anyone on this test instance, created if they do not exist -- no verification code."
        }
        div {
            className = ClassName("row")
            Button {
                type = "primary"
                onClick = { error = null; becomeDeploymentAdmin(bump) { error = it } }
                +"Become deployment admin"
            }
            p {
                className = ClassName("type-hint")
                +"An administrator of every client (admin and allClients)."
            }
        }
        if (current.isNotEmpty() || stale.isNotEmpty()) {
            h2 { +"Recent" }
            recentIdentityButtons(current, bump) { error = it }
            for (identity in stale) {
                p {
                    key = identity.identityKey.unsafeCast<Key>()
                    className = ClassName("type-hint sim-recent-stale")
                    +"${identityLabel(identity)} (from data since reset)"
                }
            }
            Button {
                type = "link"
                onClick = {
                    localStorageRemove(recentIdentitiesKey, recentIdentitiesWhat)
                    setRecent(emptyList())
                }
                +"Clear"
            }
        }
        h2 { +"Anyone" }
        val cat = catalog
        val endpoint = cat?.endpoints?.firstOrNull()
        when {
            loadError != null -> errorText("Couldn't load the form.", loadError!!)
            cat == null -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            endpoint == null -> p {
                className = ClassName("subtitle")
                +"This instance does not offer the fixture."
            }
            else -> {
                val inputType = cat.inputType(endpoint)
                SchemaForm {
                    type = inputType
                    this.values = values
                    editable = true
                    cfacts = cat.cfacts
                    this.failures = failures
                    onChange = { values = it }
                }
                Button {
                    type = "primary"
                    this.loading = running
                    onClick = {
                        val check = checkInput(inputType, values)
                        failures = check.failures.ifEmpty { null }
                        check.payload?.let { payload ->
                            running = true
                            error = null
                            becomeAndGo(payload, bump) {
                                error = it
                                running = false
                            }
                        }
                    }
                    +"Become"
                }
            }
        }
        error?.let { errorText("Couldn't become that user.", it) }
    }
}

/** A button per remembered identity [identities], each becoming that user again. */
private fun ChildrenBuilder.recentIdentityButtons(identities: List<RecentIdentity>, bump: () -> Unit, onError: (DisplayError) -> Unit) {
    if (identities.isEmpty()) return
    div {
        className = ClassName("become-recent")
        for (identity in identities) {
            Button {
                key = identity.identityKey.unsafeCast<Key>()
                onClick = { becomeAndGo(becomeAgainBody(identity), bump, onError) }
                +identityLabel(identity)
            }
        }
    }
}
