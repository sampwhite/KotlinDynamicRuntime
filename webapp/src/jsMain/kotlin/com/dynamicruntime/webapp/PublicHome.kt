package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useState
import web.cssom.ClassName

private val publicHomeScope = MainScope()

/**
 * Whether Home is the public home page (issue #1324): for a caller **signed in** to `public`. A visitor who has not
 * signed in acts in `public` too, and keeps the ordinary Home with its sign-in. Pure, and covered under `jsNodeTest`.
 */
fun isPublicHome(user: UserProfile): Boolean = user.isLoggedIn && user.client == CL.public

/** The roles of [demo] the caller has joined as, each to open. Pure. */
fun joinedRoles(demo: JoinableView): List<JoinRoleView> = demo.roles.filter { it.userId != null }

/** The roles of [demo] whose user an administrator has disabled since the caller joined: said, never offered again. Pure. */
fun removedRoles(demo: JoinableView): List<JoinRoleView> = demo.roles.filter { it.removed }

/** Whether [demo] has a role the caller may still join -- one not held, and not taken away by an administrator. Pure. */
fun hasRoleToJoin(demo: JoinableView): Boolean = demo.roles.any { it.userId == null && !it.removed }

external interface JoinableDemosProps : Props {
    var config: HomeConfig

    /**
     * The refresh generation [config] was read at, which the demos' read keys on: after a switch from `public` into a
     * demo, the generation moves before the demo's config arrives, and a read keyed on it would ask for the joinable
     * demos as the demo's user, which is refused ([useCardRead] says the same of Home's cards).
     */
    var configAt: Int
}

/**
 * The demo clients a visitor signed in to `public` may join (issues #1248, #1324), the `joinableDemos` page widget
 * (issue #1794): a card per demo client, its roles, and for each role joined an **Open as** switch; **Join…** leads to
 * the Join page, which says what a shared space means before anyone joins. The page draws it only where the deployment
 * offers joining ([HomeConfig.joinOffered]); its heading and introduction are its fence's copy, above it.
 */
val JoinableDemos = FC<JoinableDemosProps> { props ->
    val config = props.config
    var demos by useState<List<JoinableView>?>(null)
    var demosError by useState<DisplayError?>(null)
    var busy by useState(false)
    // A failed switch, and the demo's client id it was asked from, so it is said on that demo's card.
    var switchError by useState<Pair<String, DisplayError>?>(null)
    val bump = useRefreshBump()

    useEffect(props.configAt, config.joinOffered) {
        if (!config.joinOffered) return@useEffect
        publicHomeScope.launch {
            try {
                demos = JoinApi.joinable()
                demosError = null
            } catch (e: Throwable) {
                demosError = userFacingError(e)
            }
        }
    }

    fun switchTo(from: String, work: suspend () -> Unit) {
        busy = true
        switchError = null
        publicHomeScope.launch {
            try {
                work()
                afterSessionChange(bump)
            } catch (e: Throwable) {
                switchError = from to userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    demosError?.let { errorText("Couldn't read the demo clients.", it) }
    val loaded = demos
    if (loaded == null && demosError == null) p { className = ClassName("subtitle"); +"Loading…" }
    for (demo in loaded.orEmpty()) {
        div {
            key = demo.client.unsafeCast<Key>()
            className = ClassName("card public-demo")
            h3 { +demo.name }
            demo.description?.let { p { className = ClassName("subtitle"); +it } }
            ul { for (role in demo.roles) li { roleLine(role) } }
            // A role an administrator took away is said here, as the Join page says it, not left as a card with nothing
            // to do.
            for (role in removedRoles(demo)) {
                p {
                    className = ClassName("subtitle")
                    +"One of ${demo.name}'s administrators has disabled your ${role.label} user."
                }
            }
            div {
                className = ClassName("public-demo-actions")
                for (role in joinedRoles(demo)) {
                    Button {
                        disabled = busy
                        asDynamic()["aria-label"] = "Open ${demo.name} as ${role.label}"
                        onClick = { switchTo(demo.client) { AuthApi.switchUser(role.userId!!) } }
                        +"Open as ${role.label}"
                    }
                }
                if (hasRoleToJoin(demo)) {
                    val anotherRole = joinedRoles(demo).isNotEmpty()
                    a {
                        href = joinHref(demo.client)
                        asDynamic()["aria-label"] = if (anotherRole) "Join ${demo.name} as another role" else "Join ${demo.name}"
                        +(if (anotherRole) "Join as another role…" else "Join…")
                    }
                }
            }
            switchError?.takeIf { it.first == demo.client }?.let { errorText("Couldn't open ${demo.name}.", it.second) }
        }
    }
}
