package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.user.personaTerm

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
    // Unclaimed (issue #751): an invitation is out for a live user, and waits for an enable on a disabled one.
    if (!user.registered && !user.deleted) add(if (user.enabled) "Invitation pending" else "Unclaimed")
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
): List<String> = buildList {
    if (editor) add("Editor")
    if (allClients) add("All clients")
    when {
        personaSuffix.isNotBlank() -> add("persona suffix ${personaSuffix.trim()}")
        suffixNeeded -> add("a persona suffix is needed")
    }
    normalizeUserLabels(labels).takeIf { it.isNotEmpty() }?.let { add("labels: ${it.joinToString(", ")}") }
    if (!enabled) add(if (creating) "starts disabled" else "disabled")
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
 * compared as the backend stores it: the roles as a set, the organization and name trimmed, the labels normalized. A
 * sandbox user somebody [opened] (issue #1198) has no roles or organization of its own to set, so neither is a change.
 * Pure, covered under `jsNodeTest`.
 */
fun userDraftChanges(
    target: AdminUser,
    roles: List<String>,
    org: String,
    name: String,
    isEntity: Boolean,
    enabled: Boolean,
    labels: List<String>,
    opened: Boolean,
): List<UserChange> = buildList {
    if (!opened && roles.toSet() != target.roles.toSet()) add(UserChange.roles)
    if (!opened && org.trim().ifEmpty { null } != target.org) add(UserChange.org)
    if (name.trim().ifEmpty { null } != target.name || isEntity != target.isEntity) add(UserChange.accountName)
    if (enabled != target.enabled) add(UserChange.enabled)
    if (normalizeUserLabels(labels) != target.labels) add(UserChange.labels)
}

/**
 * What leads a save's failure: the save is one call per change, not one transaction, so a failure part way says what
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
 * Whether a create form holds anything a reader typed: the address (unless it is the caller's own, filled in for
 * them), a name, a persona suffix, or a label. Pure, covered under `jsNodeTest`.
 */
fun createDraftDirty(email: String, forSelf: Boolean, name: String, personaSuffix: String, labels: List<String>): Boolean =
    (!forSelf && email.isNotBlank()) || name.isNotBlank() || personaSuffix.isNotBlank() || normalizeUserLabels(labels).isNotEmpty()
