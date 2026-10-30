package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.user.personaTerm
import com.dynamicruntime.common.util.normalizeEmail

/*
 * The Users editor's layout (issue #1478), the pure half: what its header, its structure line, its More options, and
 * its save say about a user. Built on the page structure of #1477 (`Panels.kt`); covered under `jsNodeTest`.
 */

/**
 * The chips beside the editor's heading: whether the user is live, whether nobody has claimed it yet, and whether it
 * lives in a sandbox. Pure, covered under `jsNodeTest`.
 */
fun userStatusChips(user: AdminUser): List<String> = buildList {
    add(
        when {
            user.deleted -> "Deleted"
            user.enabled -> "Enabled"
            else -> "Disabled"
        },
    )
    // Unclaimed (issue #751), with no word on an invitation: one may not have gone (issue #1504), and nothing records it.
    if (!user.registered && !user.deleted) add("Unclaimed")
    if (isSandboxClient(user.client)) add("Sandbox")
}

/** Where a user sits among a person's users: its client and persona, `acme / Member B`. */
fun userPlace(user: AdminUser): String = "${user.client} / ${personaTerm(user.persona, user.personaSuffix)}"

/**
 * How a user is put together (issue #1478, rule 7 of #1477): the person -- the address -- then each of the person's
 * [users], the open one [IdentitySibling.selected], then the access the open one has.
 */
class UserStructure(val person: String, val users: List<IdentitySibling>, val access: String)

/**
 * The structure line for [user], given the person's [siblings] when the caller is told them (an administrator): each
 * labeled by what tells it apart from the others ([identitySiblings]). The user alone otherwise, or while they load,
 * labeled by its client and persona ([userPlace]). Pure, covered under `jsNodeTest`.
 */
fun userStructure(user: AdminUser, siblings: List<AdminUser>?): UserStructure {
    val rows = siblings?.let { identitySiblings(it, user.userId) }?.takeIf { all -> all.any { it.selected } }
        ?: listOf(IdentitySibling(user, userPlace(user), statusWords(user).joinToString(", "), selected = true))
    return UserStructure(user.primaryId, rows, PERSONA.accessPhrase(PERSONA.accessOf(user.actingRoles ?: user.roles)))
}

/**
 * What a row of the structure line says beside a user that is not simply live: deleted, disabled, or nobody's yet --
 * shown, not left to a hover, so a reader sees it before opening the user. Null for a user that is just live. Pure,
 * covered under `jsNodeTest`.
 */
fun userStructureFlag(user: AdminUser): String? = listOfNotNull(
    when {
        user.deleted -> "deleted"
        !user.enabled -> "disabled"
        else -> null
    },
    "unclaimed".takeIf { !user.registered && !user.deleted },
).joinToString(", ").ifEmpty { null }

/**
 * A user's authority as the editor controls it (issue #1478): the rung, the Editor box, and the All clients box. Kept
 * apart from the role list, which may hold more than the editor draws -- two rungs, or `editor` beside `admin` -- so a
 * user opened and left alone compares as unchanged rather than as a list the editor would rewrite.
 */
data class UserAuthority(val level: String, val editor: Boolean, val allClients: Boolean)

/**
 * The authority [roles] hold as the editor shows it. Editor counts only where its box is drawn -- offered in the client
 * ([editorOffered]) and below Administrator, which implies it. Pure, covered under `jsNodeTest`.
 */
fun userAuthority(roles: List<String>, editorOffered: Boolean): UserAuthority {
    val level = RoleLadder.highestHeld(roles) ?: ROLE.user
    return draftAuthority(level, ROLE.editor in roles, ROLE.allClients in roles, editorOffered)
}

/** The authority a draft describes: its [editor] box counts only where it is drawn, as [userAuthority] has it. */
fun draftAuthority(level: String, editor: Boolean, allClients: Boolean, editorOffered: Boolean): UserAuthority =
    UserAuthority(level, editor && editorOffered && level != ROLE.admin, allClients)

/**
 * The role list [authority] asks for, given what [current] holds: on the same rung, [current] as it is, so a second
 * rung or a role the editor does not draw is kept; on another, the rung moved ([RoleLadder.rolesAtLevel]). Then the
 * two boxes, each on or off. A hidden Editor box is off: a role nobody can see is not sent. Pure, covered under
 * `jsNodeTest`.
 */
fun rolesForAuthority(current: List<String>, authority: UserAuthority): List<String> {
    val sameRung = current.isNotEmpty() && (RoleLadder.highestHeld(current) ?: ROLE.user) == authority.level
    val rung = if (sameRung) current else RoleLadder.rolesAtLevel(current, authority.level)
    return rolesWithEditor(rolesWithCapability(rung, ROLE.allClients, authority.allClients), authority.editor)
}

/**
 * What a user's More options holds that is not at its default, each said shortly, for its summary and for whether it
 * opens itself (rule 5 of #1477). [editor] only when the Editor box is offered; [personaSuffix] and [suffixNeeded]
 * only on a create, where a collision asks for a suffix. Pure, covered under `jsNodeTest`.
 */
fun userMoreOptionsInForce(
    editor: Boolean,
    allClients: Boolean,
    labels: List<String>,
    enabled: Boolean,
    creating: Boolean,
    personaSuffix: String = "",
    suffixNeeded: Boolean = false,
    invitationHeld: Boolean = false,
): List<String> = buildList {
    if (editor) add("Editor")
    if (allClients) add("All clients")
    when {
        personaSuffix.isNotBlank() -> add("persona suffix ${personaSuffix.trim()}")
        suffixNeeded -> add("a persona suffix is needed")
    }
    normalizeUserLabels(labels).takeIf { it.isNotEmpty() }?.let { add("labels: ${it.joinToString(", ")}") }
    if (!enabled) add(if (creating) "starts disabled" else "disabled")
    if (invitationHeld) add("no invitation")
}

/**
 * Whether a create mails an invitation at all (issue #1504): not for a form opened for the caller's own address
 * ([forSelf]), nor when the address typed is theirs ([ownAddress], null until known), however it was spelled. Pure,
 * covered under `jsNodeTest`.
 */
fun invitationOfferedFor(forSelf: Boolean, email: String, ownAddress: String?): Boolean =
    !forSelf && email.normalizeEmail() != ownAddress

/** Whether a create holds its invitation back: one is offered, the user will be live, and the box is off. */
fun invitationHeld(offered: Boolean, enabled: Boolean, invite: Boolean): Boolean = offered && enabled && !invite

/** The note after a create (issue #1504), and whether it reports something the administrator must act on ([failed]). */
class CreatedNote(val text: String, val failed: Boolean = false)

/**
 * What the note after a create says of its invitation (issue #1504). [invitable] is whether one could go at all: not at
 * the caller's own address, nor for a disabled user, of which the note says nothing. [wanted] is the Send invitation
 * box, and [sent] the backend's answer, since a mail that fails does not fail the create. Pure, under `jsNodeTest`.
 */
fun createdNote(address: String, invitable: Boolean, wanted: Boolean, sent: Boolean): CreatedNote = when {
    !invitable -> CreatedNote("Created $address.")
    !wanted -> CreatedNote("Created $address. No invitation sent: open the user to send one.")
    sent -> CreatedNote("Created $address. Invitation sent.")
    else -> CreatedNote("Created $address, but its invitation could not be sent: open the user to send it.", failed = true)
}

/**
 * Whether this node carries [client], going by [told], the clients it was told it does. Only a caller who sees across
 * clients is told any, and the client a scoped administrator works in is carried, so an empty list says yes. A sandbox
 * is carried with its parent. Pure, under `jsNodeTest`.
 */
fun clientCarried(client: String, told: Set<String>): Boolean =
    told.isEmpty() || client in told || sandboxParentOf(client) in told

/** What the Users editor offers to mail a user (issue #1504). */
@Suppress("EnumEntryName")
enum class UserMailOffer { invitation, reminder, none }

/**
 * What to offer [user]: the invitation for an enabled user nobody has claimed, a sign-in reminder for a claimed one --
 * whose invitation link would sign in whoever held the mail -- except at the caller's own address ([own]), one opened in
 * a sandbox, and one in a client this node does not carry ([carried] false), who could not sign in. Nothing for a
 * disabled or deleted user. Pure, under `jsNodeTest`.
 */
fun userMailOffer(user: AdminUser, own: Boolean, carried: Boolean): UserMailOffer = when {
    !user.enabled || user.deleted -> UserMailOffer.none
    !user.registered -> UserMailOffer.invitation
    own || user.opened || !carried -> UserMailOffer.none
    else -> UserMailOffer.reminder
}

/** What a save of an existing user writes, in the order it writes them: each is one call. */
@Suppress("EnumEntryName")
enum class UserChange(val words: String) {
    roles("access"),
    org("organization"),
    accountName("name"),
    enabled("enabled status"),
    labels("labels"),
}

/**
 * What the draft changes on [target] -- the calls a save makes, in order, and what makes the draft dirty. Each is
 * compared as the backend stores it: the authority as the editor controls it ([userAuthority]), the organization and
 * name trimmed, the labels normalized. A sandbox user somebody [opened] (issue #1198) has no roles or organization of
 * its own to set, so neither is a change. Pure, covered under `jsNodeTest`.
 */
fun userDraftChanges(
    target: AdminUser,
    authority: UserAuthority,
    editorOffered: Boolean,
    org: String,
    name: String,
    isEntity: Boolean,
    enabled: Boolean,
    labels: List<String>,
    opened: Boolean,
): List<UserChange> = buildList {
    if (!opened && authority != userAuthority(target.roles, editorOffered)) add(UserChange.roles)
    if (!opened && org.trim().ifEmpty { null } != target.org) add(UserChange.org)
    if (name.trim().ifEmpty { null } != target.name || isEntity != target.isEntity) add(UserChange.accountName)
    if (enabled != target.enabled) add(UserChange.enabled)
    if (normalizeUserLabels(labels) != target.labels) add(UserChange.labels)
}

/**
 * What leads a save's failure: the save is one call per change, not one transaction, so a failure partway says what
 * was saved before it as well as what was not. Pure, covered under `jsNodeTest`.
 */
fun userSaveFailureLead(saved: List<UserChange>, failed: UserChange?): String {
    val notSaved = failed?.let { "Couldn't save the ${it.words}." } ?: "Couldn't save the user."
    return if (saved.isEmpty()) notSaved else "Saved the ${listPhrase(saved.map { it.words })}. $notSaved"
}

/** [items] as a phrase, with the serial comma: `a`, `a and b`, `a, b, and c`. */
fun listPhrase(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
}

/**
 * A create form's fields (issue #1478), compared with what it opened with to say whether anything was changed: every
 * field, so a client, persona, organization, rung, or box chosen and left is asked about as a typed address is.
 */
data class CreateDraft(
    val email: String,
    val name: String,
    val isEntity: Boolean,
    val level: String,
    val editor: Boolean,
    val allClients: Boolean,
    val org: String,
    val client: String,
    val persona: String,
    val personaSuffix: String,
    val labels: List<String>,
    val enabled: Boolean,
    val invite: Boolean = true,
) {
    /** The fields as a save would send them: trimmed, and the labels normalized. */
    fun normalized(): CreateDraft = copy(
        email = email.trim(), name = name.trim(), org = org.trim(), client = client.trim(),
        personaSuffix = personaSuffix.trim(), labels = normalizeUserLabels(labels),
    )
}

/** Whether a create form holds anything changed from what it opened with ([seeded]). Pure, under `jsNodeTest`. */
fun createDraftDirty(current: CreateDraft, seeded: CreateDraft): Boolean = current.normalized() != seeded.normalized()
