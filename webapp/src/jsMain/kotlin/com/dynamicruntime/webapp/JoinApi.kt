package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.JOIN
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * A role a demo client may be joined as (issue #1248): the caller's user in it when they have joined as it, and
 * [removed] when they did and one of the client's administrators has since disabled that user.
 */
class JoinRoleView(
    val name: String, val label: String, val persona: String, val summary: String, val userId: Long?, val removed: Boolean = false,
) {
    val isAdmin: Boolean get() = persona == PERSONA.admin
}

/** A demo client the caller may join (issue #1248), with its roles. */
class JoinableView(val client: String, val name: String, val description: String?, val roles: List<JoinRoleView>)

/** The joinable clients from `GET /user/self/joinable`'s envelope. Pure, and covered under `jsNodeTest`. */
fun parseJoinable(envelope: Map<String, Any?>): List<JoinableView> = envelope[EP.items].toJsonListOrEmpty().mapNotNull { raw ->
    val m = raw.toJsonMapOrEmpty()
    val client = m[JOIN.client].toOptStr() ?: return@mapNotNull null
    JoinableView(
        client = client,
        name = m[JOIN.clientName].toOptStr() ?: client,
        description = m[JOIN.description].toOptStr(),
        roles = m[JOIN.roles].toJsonListOrEmpty().mapNotNull { r ->
            val role = r.toJsonMapOrEmpty()
            val name = role[JOIN.name].toOptStr() ?: return@mapNotNull null
            JoinRoleView(
                name = name,
                label = role[JOIN.label].toOptStr() ?: name,
                persona = role[JOIN.persona].toOptStr().orEmpty(),
                summary = role[JOIN.summary].toOptStr().orEmpty(),
                userId = role[JOIN.userId].toOptLong(),
                removed = role[JOIN.removed] == true,
            )
        },
    )
}

/** The Join page for [client] (issue #1248). */
fun joinHref(client: String): String = routeHref(listOf(HP.page to HMENU.pageJoin, HP.client to client))

/**
 * What the Join page says before anyone joins [client] (issue #1248), a paragraph each: that it is one space shared
 * with every visitor who joins it, what of a person other visitors see, and that it all goes when the site's data is
 * cleared. Said plainly, and acknowledged before a join is sent, because the people seeing it are strangers. Pure, and
 * covered under `jsNodeTest`.
 */
fun joinDisclosure(client: JoinableView): List<String> = buildList {
    add(
        "${client.name} is a shared demo space. Everyone who joins it joins the same ${client.name}, and sees what the " +
            "others add there — and they see what you add.",
    )
    add("Don't put anything here that you wouldn't want other visitors to read.")
    // Every demo offers an administrator's role, whose holder sees the client's users; said whichever role is chosen.
    add(
        "Anyone who joins as an administrator — another visitor — can see the users of ${client.name}, including your " +
            "name and email address, and can change or disable them." +
            if (client.roles.any { it.isAdmin }) " As one, you can do the same to theirs." else "",
    )
    add("Everything in ${client.name}, the users who joined included, is deleted whenever this site's data is cleared.")
    add("Joining adds a user of your own email in ${client.name}. Your other accounts stay yours, in the account menu.")
}

/** The acknowledgment the Join page asks for before it sends a join (issue #1248). */
const val joinAcknowledgment = "I understand that this is a shared space, and that other visitors see what I do here."

/** The standing note a shared demo client's pages carry (issue #1248), behind the bar's "Shared demo" chip. */
fun sharedDemoNoteText(clientName: String): String =
    "$clientName is a shared demo space: other visitors who join it see what you add here, and anyone who joins as an " +
        "administrator can see its users. Everything is deleted whenever this site's data is cleared."

object JoinApi {
    suspend fun joinable(): List<JoinableView> = parseJoinable(Http.getApi(JOIN.joinablePath))

    /** Joins [client] as [role], acknowledged; the session is then that user. Answers the user's id. */
    suspend fun join(client: String, role: String): Long =
        Http.sendApi("POST", JOIN.joinPath, mapOf(JOIN.client to client, JOIN.role to role, JOIN.acknowledged to true))[EP.results]
            .toJsonMapOrEmpty()[JOIN.userId].toOptLong() ?: 0L
}
