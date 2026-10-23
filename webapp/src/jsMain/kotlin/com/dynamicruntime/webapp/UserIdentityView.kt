package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.UserChoice
import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
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
    return choices.first { it.userId == user.userId }.qualifierWithin(choices)
        .ifEmpty { personaCell(user.persona, user.personaSuffix) }
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

/**
 * The editor's read-only summary (issue #770), as label/value pairs: the identity's facts -- whether the
 * address is proven, whether a password is set, which user an unnamed login lands on -- when the caller is told
 * them ([AdminIdentity.identityFactsGiven]), then the open user's dates. Pure, covered under `jsNodeTest`.
 */
fun identitySummary(identity: AdminIdentity, editing: AdminUser): List<Pair<String, String>> {
    val signsInAs = when (val id = identity.signsInAsUserId) {
        null -> "—"
        editing.userId -> "This user"
        else -> identity.users.firstOrNull { it.userId == id }?.let { qualifierOf(it, identity.users) } ?: "—"
    }
    fun date(iso: String?) = iso?.let { formatTimestamp(it) } ?: "—"
    val facts = if (!identity.identityFactsGiven) emptyList() else listOf(
        "Address" to (identity.verifiedAt?.let { "Proven ${formatTimestamp(it)}" } ?: "Not yet proven"),
        "Password" to (if (identity.hasPassword == true) "Set" else "Not set"),
        "Signs in as" to signsInAs,
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
 * The person behind the user open in the editor (issue #770): the list of the person's users, when there is
 * more than one, each choosable to edit that one instead ([onPick]), then the read-only summary.
 */
fun ChildrenBuilder.identityPanel(identity: AdminIdentity, editing: AdminUser, disabled: Boolean, onPick: (AdminUser) -> Unit) {
    val siblings = identitySiblings(identity.users, editing.userId)
    if (siblings.isNotEmpty()) {
        h2 { +"This person's users" }
        for (sibling in siblings) {
            div {
                className = ClassName("row")
                if (sibling.selected) {
                    span {
                        className = ClassName("field-label")
                        +"▶ ${sibling.label}"
                    }
                } else {
                    Button {
                        type = "link"
                        this.disabled = disabled
                        onClick = { onPick(sibling.user) }
                        +sibling.label
                    }
                }
                span {
                    className = ClassName("type-hint")
                    +sibling.status
                }
            }
        }
    }
    h2 { +"Summary" }
    for ((label, value) in identitySummary(identity, editing)) {
        readOnlyField(label, value)
    }
}
