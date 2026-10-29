package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.UserChoice
import react.ChildrenBuilder
import react.Key
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * The person behind a user (issue #770), as the admin read returns it: the identity's own facts, and those of
 * its users the caller administers, lowest id first. [signsInAsUserId] is present only when the user an
 * unnamed login lands on is one of [users].
 *
 * The identity's facts -- [verifiedAt], [hasPassword], [signsInAsUserId] -- describe the person's users in every
 * client, so the backend gives them only to a caller who sees every client, or about their own identity (issue
 * #1298). [hasPassword] is null when it gave none, which is how [identityFactsGiven] tells "not proven" from "not
 * told".
 */
class AdminIdentity(
    val verifiedAt: String?,
    val hasPassword: Boolean?,
    val signsInAsUserId: Long?,
    val users: List<AdminUser>,
) {
    /** Whether the backend told this caller the identity's facts. */
    val identityFactsGiven: Boolean get() = hasPassword != null
}

/** One row of the editor's list of a person's users: the user, what tells it apart, its status, and whether it is open. */
class IdentitySibling(val user: AdminUser, val label: String, val status: String, val selected: Boolean)

/** What tells [user] apart from the rest of [users] -- the badge's own rule (`UserChoice.qualifierWithin`). */
private fun qualifierOf(user: AdminUser, users: List<AdminUser>): String {
    val choices = users.map { UserChoice(it.userId, it.client, it.persona, it.personaSuffix, it.name) }
    // Each sibling with what it acts as when that differs from its persona (issue #1450): their roles are in hand.
    val roles = user.actingRoles ?: user.roles
    return choices.first { it.userId == user.userId }.qualifierWithin(choices, roles)
        .ifEmpty { personaCell(user.persona, user.personaSuffix, roles) }
}

/**
 * The editor's list of a person's users (issue #770): one row per user in [users], in the order given (lowest
 * id first, as the backend sends them), labeled by what tells it apart and marked [IdentitySibling.selected]
 * for the one open ([editingUserId]). Empty when there is only the one user: a list of one says nothing the
 * editor does not. Pure, covered under `jsNodeTest`.
 */
fun identitySiblings(users: List<AdminUser>, editingUserId: Long): List<IdentitySibling> =
    if (users.size < 2) {
        emptyList()
    } else {
        users.map { IdentitySibling(it, qualifierOf(it, users), statusWords(it).joinToString(", "), it.userId == editingUserId) }
    }

/** Which of the person's users an unnamed login lands on, as the summary says it. */
private fun signsInAsText(identity: AdminIdentity, editing: AdminUser): String = when (val id = identity.signsInAsUserId) {
    null -> "—"
    editing.userId -> "This user"
    else -> identity.users.firstOrNull { it.userId == id }?.let { qualifierOf(it, identity.users) } ?: "—"
}

/**
 * The editor's read-only summary (issue #770), as label/value pairs: the identity's facts -- whether the
 * address is proven, whether a password is set, which user an unnamed login lands on -- when the caller is told
 * them ([AdminIdentity.identityFactsGiven]; none while [identity] is null -- not loaded, or an editor's view), then
 * the open user's dates. Pure, covered under `jsNodeTest`.
 */
fun identitySummary(identity: AdminIdentity?, editing: AdminUser): List<Pair<String, String>> {
    fun date(iso: String?) = iso?.let { formatTimestamp(it) } ?: "—"
    val facts = if (identity == null || !identity.identityFactsGiven) emptyList() else listOf(
        "Address" to (identity.verifiedAt?.let { "Proven ${formatTimestamp(it)}" } ?: "Not yet proven"),
        "Password" to (if (identity.hasPassword == true) "Set" else "Not set"),
        "Signs in as" to signsInAsText(identity, editing),
    )
    return facts + listOf(
        "Registered" to date(editing.registeredAt),
        "Activated" to date(editing.activatedAt),
        "Last login" to date(editing.lastLoggedInAt),
        "Last edited" to date(editing.lastEditedAt),
        "Updated" to date(editing.updatedAt),
    )
}

/**
 * The editor's structure line (issue #1478, rule 7 of #1477): the person's address, then the person's users -- the
 * open one marked, each other one a link that opens it instead ([onPick]) -- then the access the open one has. Read
 * before any field, so the reader sees what the user is part of before what can be changed about it.
 */
fun ChildrenBuilder.userStructureLine(structure: UserStructure, disabled: Boolean, onPick: (AdminUser) -> Unit) {
    div {
        className = ClassName("user-structure")
        asDynamic()["aria-label"] = "How this user is put together"
        span {
            className = ClassName("user-structure-person")
            +structure.person
        }
        span {
            className = ClassName("user-structure-arrow")
            +"→"
        }
        for (row in structure.users) {
            if (row.selected) {
                span {
                    key = row.user.userId.toString().unsafeCast<Key>()
                    className = ClassName("user-mark user-structure-current")
                    title = row.status
                    +row.label
                }
            } else {
                button {
                    key = row.user.userId.toString().unsafeCast<Key>()
                    className = ClassName("wf-cell-link")
                    title = "${row.status} -- open this user"
                    this.disabled = disabled
                    onClick = { onPick(row.user) }
                    +row.label
                }
            }
        }
        span {
            className = ClassName("user-structure-arrow")
            +"→"
        }
        span { +structure.access }
    }
}
