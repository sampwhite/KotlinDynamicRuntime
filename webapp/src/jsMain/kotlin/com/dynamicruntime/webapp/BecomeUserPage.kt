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
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/*
 * Becoming someone on a test instance (issue #1183): the deployment admin in one click, any user through the
 * `becomeUser` fixture's own form, and the identities this browser became before -- under Account in the menu, on the
 * Home page as a card, and on the `/wa/test/become-user` page. Offered only to a caller with test access, for whom the
 * fixture exists (issue #1149).
 */

private val becomeScope = MainScope()

/** Where this browser keeps the identities it became, in `localStorage`. */
private const val recentIdentitiesKey = "kdrRecentIdentities"
private const val recentIdentitiesWhat = "recent identities"

/** The identities this browser remembers, newest first. */
fun storedIdentities(): List<RecentIdentity> = decodeIdentities(localStorageGet(recentIdentitiesKey, recentIdentitiesWhat))

/**
 * Drops the identity under [key] from this browser's list (issue #1503) and answers what is left: forgetting it, not
 * deleting the user, who can be become again.
 */
fun forgetStoredIdentity(key: String): List<RecentIdentity> {
    val left = forgetIdentity(storedIdentities(), key)
    if (left.isEmpty()) {
        localStorageRemove(recentIdentitiesKey, recentIdentitiesWhat)
    } else {
        localStorageSet(recentIdentitiesKey, encodeIdentities(left), recentIdentitiesWhat)
    }
    return left
}

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

external interface BecomeLinkProps : Props {
    /** The user to become. */
    var user: AdminUser

    /**
     * Runs what it is given once the page agrees to the move, for a page holding unsaved edits. Asked before becoming,
     * since the session changes first and the move home after it. Null goes straight on.
     */
    var beforeBecome: ((() -> Unit) -> Unit)?
}

/**
 * Becomes [BecomeLinkProps.user] from the Users page (issue #1511): styled as the row's other links, though a button,
 * since it changes the session. An unclaimed user asks first ([becomeClaims]). The user joins the recent identities,
 * and the caller lands on Home, as every way of becoming does.
 */
val BecomeLink = FC<BecomeLinkProps> { props ->
    val bump = useRefreshBump()
    var failure by useState<DisplayError?>(null)

    fun become() {
        failure = null
        val go = { becomeAndGo(becomeUserBody(props.user), bump) { failure = it } }
        props.beforeBecome?.invoke(go) ?: go()
    }

    fun ChildrenBuilder.link(press: (() -> Unit)?) {
        button {
            className = ClassName("wf-cell-link")
            title = "Sign in as this user."
            press?.let { act -> onClick = { act() } }
            +"Become"
        }
    }

    if (becomeClaims(props.user)) {
        Popconfirm {
            title = "Become this user?"
            description = "This claims the user, and its invitation will stop working."
            okText = "Become"
            cancelText = "Cancel"
            placement = "left"
            onConfirm = { become() }
            link(null)
        }
    } else {
        link { become() }
    }
    failure?.let { errorText("Couldn't become this user.", it) }
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
    // Read from storage on every draw, so a user just become shows; this only asks for a draw after an entry is forgotten.
    var forgotten by useState(0)
    val (current, _) = identitiesFor(storedIdentities(), appConfig().dataId)

    fun forget(identityKey: String) {
        error = null
        forgetStoredIdentity(identityKey)
        forgotten++
    }

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
                href = routeHref(listOf(HP.page to HMENU.pageBecomeUser))
                className = ClassName("back-link")
                +"Become a user…"
            }
        }
        recentIdentityButtons(current, bump, ::forget) { error = it }
        error?.let { errorText("Couldn't become that user.", it) }
    }
}

/**
 * The `/wa/test/become-user` page (issue #1183): the deployment admin in one click, the fixture's form -- drawn
 * from the endpoint's own schema, as the Simulations page draws each simulation's -- and the identities this
 * browser remembers, the ones from data since reset grayed with no way to become them, since their users are gone.
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

    fun forget(identityKey: String) {
        error = null
        setRecent(forgetStoredIdentity(identityKey))
    }

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
            recentIdentityButtons(current, bump, ::forget) { error = it }
            for (identity in stale) {
                p {
                    key = identity.identityKey.unsafeCast<Key>()
                    className = ClassName("type-hint sim-recent-stale")
                    +"${identityLabel(identity)} (from data since reset)"
                    forgetIdentityButton(identity, ::forget)
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

/** A button per remembered identity [identities], each becoming that user again, with an × forgetting it ([onForget]). */
private fun ChildrenBuilder.recentIdentityButtons(
    identities: List<RecentIdentity>,
    bump: () -> Unit,
    onForget: (String) -> Unit,
    onError: (DisplayError) -> Unit,
) {
    if (identities.isEmpty()) return
    div {
        className = ClassName("become-recent")
        for (identity in identities) {
            span {
                key = identity.identityKey.unsafeCast<Key>()
                className = ClassName("become-recent-entry")
                Button {
                    // The whole label, for one the entry's width cuts short.
                    asDynamic()["title"] = identityLabel(identity)
                    onClick = { becomeAndGo(becomeAgainBody(identity), bump, onError) }
                    +identityLabel(identity)
                }
                forgetIdentityButton(identity, onForget)
            }
        }
    }
}

/** The control beside a remembered [identity] (issue #1503), telling [onForget] its key; as a schema form's remove is. */
private fun ChildrenBuilder.forgetIdentityButton(identity: RecentIdentity, onForget: (String) -> Unit) {
    Button {
        type = "link"
        size = "small"
        val what = "Forget ${identityLabel(identity)}"
        asDynamic()["title"] = what
        asDynamic()["aria-label"] = what
        onClick = { onForget(identity.identityKey) }
        +"✕"
    }
}
