package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useState
import web.cssom.ClassName

private val joinScope = MainScope()

/**
 * Joining a demo client (issue #1248), at `#page=join&c=<client>`: what the client is, the roles it may be joined as,
 * and -- before any of them -- what joining a shared space means, which the visitor acknowledges before a join is
 * offered. Joining moves the session to the new user and the page home in that client; a role already joined offers
 * to open it instead. The endpoint is the authority: where joining is not offered, or for a caller not in `public`, its
 * refusal is what the page shows.
 */
val JoinPage = FC<Props> {
    val client = hashParams()[HP.client]
    var joinable by useState<List<JoinableView>?>(null)
    var error by useState<DisplayError?>(null)
    var acknowledged by useState(false)
    var busy by useState(false)
    var joinError by useState<DisplayError?>(null)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    // An acknowledgment is of one client's disclosure: another client's page asks again.
    useEffect(client) {
        acknowledged = false
        joinError = null
    }

    useEffect(generation) {
        error = null
        joinScope.launch {
            try {
                joinable = JoinApi.joinable()
            } catch (e: Throwable) {
                error = userFacingError(e)
            }
        }
    }

    fun act(work: suspend () -> Unit) {
        busy = true
        joinError = null
        joinScope.launch {
            try {
                work()
                afterSessionChange(bump)
            } catch (e: Throwable) {
                joinError = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    div {
        className = ClassName("card")
        val view = joinable?.firstOrNull { it.client == client }
        when {
            error != null -> {
                h1 { +"Join a demo" }
                errorText("Couldn't read the demo clients.", error!!)
            }
            joinable == null -> p { className = ClassName("subtitle"); +"Loading…" }
            view == null -> {
                h1 { +"Join a demo" }
                p { +(if (client == null) "No demo client was named." else "'$client' is not a demo client you can join here.") }
                val others = joinable.orEmpty()
                if (others.isNotEmpty()) {
                    p { +"These are:" }
                    ul { for (other in others) li { a { href = joinHref(other.client); +other.name } } }
                }
            }
            else -> {
                h1 { +"Join ${view.name}" }
                view.description?.let { p { className = ClassName("subtitle"); +it } }
                div {
                    className = ClassName("join-disclosure")
                    asDynamic()["role"] = "note"
                    h2 { +"Before you join" }
                    for (paragraph in joinDisclosure(view)) p { +paragraph }
                    label {
                        className = ClassName("join-acknowledge")
                        Checkbox {
                            checked = acknowledged
                            disabled = busy
                            onChange = { e -> acknowledged = e.target.checked == true }
                        }
                        +" $joinAcknowledgment"
                    }
                }
                h2 { +"Join as" }
                for (role in view.roles) {
                    div {
                        className = ClassName("join-role")
                        p {
                            +role.label
                            span { className = ClassName("subtitle"); +" — ${role.summary}" }
                        }
                        val joinedAs = role.userId
                        if (role.removed) {
                            p {
                                className = ClassName("subtitle")
                                +"One of ${view.name}'s administrators has disabled your ${role.label} user, so it can't be joined again."
                            }
                        } else if (joinedAs != null) {
                            p { className = ClassName("subtitle"); +"You have joined as ${role.label}." }
                            Button {
                                disabled = busy
                                onClick = { act { AuthApi.switchUser(joinedAs) } }
                                +"Open as ${role.label}"
                            }
                        } else {
                            Button {
                                type = "primary"
                                disabled = busy || !acknowledged
                                onClick = { act { JoinApi.join(view.client, role.name) } }
                                +"Join as ${role.label}"
                            }
                        }
                    }
                }
                joinError?.let { errorText("Couldn't join.", it) }
            }
        }
    }
}
