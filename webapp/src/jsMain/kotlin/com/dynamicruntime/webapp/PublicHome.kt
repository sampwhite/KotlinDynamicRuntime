package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.user.UserChoice
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
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

/** The person's users other than the one the session acts as: what "Your other accounts" lists. Pure. */
fun otherAccounts(users: List<UserChoice>): List<UserChoice> = users.filterNot { it.isCurrent }

/** The roles of [demo] the caller has joined as, each to open. Pure. */
fun joinedRoles(demo: JoinableView): List<JoinRoleView> = demo.roles.filter { it.userId != null }

/** Whether [demo] has a role the caller may still join -- one not held, and not taken away by an administrator. Pure. */
fun hasRoleToJoin(demo: JoinableView): Boolean = demo.roles.any { it.userId == null && !it.removed }

external interface PublicHomeProps : Props {
    var config: HomeConfig
    var copy: Copy
}

/**
 * The public home page (issue #1324), under Home's hero and intro, for a person signed in to `public`: what they can
 * do next on a teaching site, in three parts.
 *
 * 1. **Try a demo**, where the deployment offers joining ([HomeConfig.joinOffered], issue #1248): a card per demo
 *    client, its roles, and for each role joined an **Open as** switch; **Join…** leads to the Join page, which says
 *    what a shared space means before anyone joins. Absent, not empty, where joining is not offered.
 * 2. **Your other accounts**: the person's users in other clients, each a switch, as the account menu offers them.
 * 3. **Your own client**: the self-serve create (issue #1228), or a way to open the one made.
 */
val PublicHome = FC<PublicHomeProps> { props ->
    val config = props.config
    val copy = props.copy
    var demos by useState<List<JoinableView>?>(null)
    var demosError by useState<DisplayError?>(null)
    var busy by useState(false)
    var switchError by useState<DisplayError?>(null)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    useEffect(generation, config.joinOffered) {
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

    fun switchTo(work: suspend () -> Unit) {
        busy = true
        switchError = null
        publicHomeScope.launch {
            try {
                work()
                afterSessionChange(bump)
            } catch (e: Throwable) {
                switchError = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    div {
        className = ClassName("public-home")

        if (config.joinOffered) {
            div {
                className = ClassName("public-home-section")
                copy.opt("publicHome", "demosTitle")?.let { h2 { +it } }
                copy.opt("publicHome", "demosIntro")?.let { Markdown { source = it } }
                demosError?.let { errorText("Couldn't read the demo clients.", it) }
                val loaded = demos
                if (loaded == null && demosError == null) p { className = ClassName("subtitle"); +"Loading…" }
                for (demo in loaded.orEmpty()) {
                    div {
                        className = ClassName("card public-demo")
                        h3 { +demo.name }
                        demo.description?.let { p { className = ClassName("subtitle"); +it } }
                        ul {
                            for (role in demo.roles) {
                                li {
                                    +role.label
                                    span { className = ClassName("subtitle"); +" — ${role.summary}" }
                                }
                            }
                        }
                        div {
                            className = ClassName("public-demo-actions")
                            for (role in joinedRoles(demo)) {
                                Button {
                                    disabled = busy
                                    onClick = { switchTo { AuthApi.switchUser(role.userId!!) } }
                                    +"Open as ${role.label}"
                                }
                            }
                            if (hasRoleToJoin(demo)) {
                                a {
                                    href = joinHref(demo.client)
                                    +(if (joinedRoles(demo).isEmpty()) "Join…" else "Join as another role…")
                                }
                            }
                        }
                    }
                }
            }
        }

        val others = otherAccounts(config.users)
        if (others.isNotEmpty()) {
            div {
                className = ClassName("public-home-section")
                copy.opt("publicHome", "accountsTitle")?.let { h2 { +it } }
                copy.opt("publicHome", "accountsIntro")?.let { Markdown { source = it } }
                ul {
                    className = ClassName("public-accounts")
                    for (user in others) {
                        li {
                            span { +user.qualifierWithin(config.users).ifEmpty { user.label() } }
                            Button {
                                size = "small"
                                disabled = busy
                                onClick = { switchTo { AuthApi.switchUser(user.userId) } }
                                +"Switch"
                            }
                        }
                    }
                }
            }
        }
        switchError?.let { errorText("Couldn't switch.", it) }

        CreateOwnClientCard {
            made = config.selfServeClient
            madeAbsent = config.selfServeClientAbsent
            madeName = config.users.firstOrNull { it.client == config.selfServeClient }?.clientName
        }
    }
}
