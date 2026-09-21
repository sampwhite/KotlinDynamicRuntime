package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val inviteScope = MainScope()

/**
 * What the invitation page says the account is: the client and the persona (with the personaSuffix, for a batch
 * user) -- `hub as Admin`, `acme as Member B`. Pure, covered under `jsNodeTest`.
 */
fun invitationWhat(info: InvitationInfo): String {
    val persona = PERSONA.label(info.persona) + (if (info.personaSuffix.isEmpty()) "" else " ${info.personaSuffix}")
    return "${info.client} as $persona"
}

/**
 * The invitation page (issue #751): reached from a mailed link (`#page=invite&token=...`). It previews what the
 * link is for and waits for the person to **accept** -- nothing happens on merely opening it, so a mail
 * scanner that follows the link accepts nothing on their behalf. Accepting registers the invited user, verifies
 * a new identity, and signs this browser in as that user; then a **full reload**, since the session has changed
 * hands (the `becomeUser` pattern). Copy from the auth fragment's `invite` namespace.
 */
val InvitePage = FC<Props> {
    var copy by useState(Copy.empty)
    var info by useState<InvitationInfo?>(null)
    var error by useState<DisplayError?>(null)
    var busy by useState(false)
    var accepted by useState(false)
    val token = hashParams()[HMENU.inviteTokenParam]?.trim()?.ifEmpty { null }

    fun t(key: String, dflt: String): String = copy.t("invite", key, dflt)

    useEffectOnce {
        inviteScope.launch {
            try {
                val c = AuthApi.fetchConfig()
                copy = fetchCopyWithRetry(c.fragment) { runCatching { AuthApi.fetchConfig().fragment }.getOrNull() }
                if (token != null) info = AuthApi.previewInvitation(token)
            } catch (e: Throwable) {
                error = userFacingError(e)
            }
        }
    }

    fun accept() {
        val tk = token ?: return
        busy = true
        error = null
        inviteScope.launch {
            try {
                AuthApi.acceptInvitation(tk)
                accepted = true
                navigateHash(emptyList())
                reloadWebApp()
            } catch (e: Throwable) {
                error = userFacingError(e)
                busy = false
            }
        }
    }

    div {
        className = ClassName("card")
        h1 { +t("title", "You have been invited") }
        error?.let { errorText(it) }
        when {
            token == null -> p {
                className = ClassName("subtitle")
                +t("invalid", "This invitation link is not valid, or has expired.")
            }
            accepted -> p {
                className = ClassName("subtitle")
                +t("accepted", "You are signed in. The account is now yours.")
            }
            info != null -> {
                val invite = info!!
                p {
                    className = ClassName("subtitle")
                    MarkdownInline {
                        source = t(
                            "summary",
                            $$"An account has been created for **${invite.email}** in **${invite.client}** as **${invite.persona}**.",
                        ).evalTemplate(
                            mapOf(
                                "invite" to mapOf(
                                    "email" to invite.email, "client" to invite.client,
                                    "persona" to PERSONA.label(invite.persona) + (if (invite.personaSuffix.isEmpty()) "" else " ${invite.personaSuffix}"),
                                ),
                            ),
                        )
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +t("explain", "Accepting proves you can read mail at that address, makes the account yours, and signs you in.")
                }
                div {
                    className = ClassName("row")
                    Button {
                        type = "primary"
                        loading = busy
                        disabled = busy
                        onClick = { accept() }
                        +t("accept", "Accept and sign in")
                    }
                }
            }
        }
    }
}
