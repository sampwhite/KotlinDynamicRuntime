package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.user.personaTerm
import com.dynamicruntime.common.util.serverTimeZone
import kotlinx.datetime.offsetAt
import kotlin.time.Clock

/*
 * The Profile page's layout (issue #1479), the pure half: its structure line, what its account panel says, and what
 * the caller may do. Built on the page structure of #1477 (`Panels.kt`); covered under `jsNodeTest`.
 */

/** The ids of the Profile page's panels, what its links at the top lead to. */
@Suppress("ConstPropertyName")
object PRFP {
    const val account = "profile-account"
    const val users = "profile-users"
    const val name = "profile-name"
    const val signIn = "profile-sign-in"
    const val keys = "profile-keys"
}

/**
 * The keys of what the caller may do (issue #1479), each also its copy key in the `profile` file's `account` namespace,
 * where the words live ([profileAbilityDefaults] holds them when the copy has not arrived).
 */
@Suppress("ConstPropertyName")
object PRFA {
    const val allClients = "canAllClients"
    const val administersClient = "canAdministerClient"
    const val ownUsers = "canOwnUsers"
    const val editsClient = "canEditClient"
    const val opensSandbox = "canOpenSandbox"
    const val ownForms = "canOwnForms"
}

/** What each [PRFA] key says when the page's copy has not arrived. */
val profileAbilityDefaults: Map<String, String> = mapOf(
    PRFA.allClients to "Administer every client: its users, its configuration, and its forms.",
    PRFA.administersClient to "Administer your client: its users, its configuration, and every form in it.",
    PRFA.ownUsers to "Manage the users of your own address, and nobody else's.",
    PRFA.editsClient to "See every user and form of your client, and edit its forms.",
    PRFA.opensSandbox to "Open your client's sandbox, to try a change before it is published.",
    PRFA.ownForms to "Work with your own forms.",
)

/**
 * What the caller may do (issue #1479), as [PRFA] keys, widest first: administer every client, else their own, else
 * their own address's users (an administrator in `public`); be an editor; open the sandbox. A caller who administers
 * nothing and edits nothing works with their own forms. Pure, covered under `jsNodeTest`.
 */
fun profileAbilities(reach: CallerReach): List<String> = buildList {
    when {
        reach.allClients -> add(PRFA.allClients)
        reach.administersClient -> add(PRFA.administersClient)
        reach.managesUsers -> add(PRFA.ownUsers)
    }
    if (reach.editsClient) add(PRFA.editsClient)
    if (reach.opensSandbox) add(PRFA.opensSandbox)
    if (!reach.allClients && !reach.administersClient && !reach.managesUsers && !reach.editsClient) add(PRFA.ownForms)
}

/**
 * One line of the account panel: its copy key in the `profile` file's `account` namespace, the label it has when the
 * copy has not arrived, and its value.
 */
class AccountFact(val key: String, val label: String, val value: String)

/**
 * What the account panel says of the signed-in user (issue #1479), in order: who they are signed in as, their address,
 * their client and the sandbox they are in, their persona and access, organization, account type, and the time zone
 * their moments are shown in. Each from data the page or the shell already has: the [profile], the [loginId], the
 * person's [users] (the current one carries the persona's suffix and the client's name), and the shell's [facts]. A
 * line with nothing to say is left out. Pure, covered under `jsNodeTest`.
 */
fun profileAccountFacts(profile: UserProfile, loginId: String, users: List<UserChoice>, facts: ShellFacts): List<AccountFact> {
    val current = users.firstOrNull { it.isCurrent && it.userId == profile.userId }
    val persona = current?.persona ?: profile.persona
    return buildList {
        add(AccountFact("signedInAs", "Signed in as", profile.displayName ?: loginId))
        loginId.ifBlank { null }?.let { add(AccountFact("email", "Email", it)) }
        add(AccountFact("client", "Client", profileClientName(users, profile.client)))
        facts.sandboxOfName?.let { add(AccountFact("sandbox", "Sandbox", "$it's sandbox")) }
        persona?.let {
            val term = personaTerm(it, current?.personaSuffix.orEmpty())
            add(AccountFact("persona", "Persona", PERSONA.withActing(term, it, profile.roles)))
        }
        add(AccountFact("access", "Access", profileAccessText(profile.roles)))
        profile.org?.ifBlank { null }?.let { add(AccountFact("org", "Organization", it)) }
        add(AccountFact("accountType", "Account type", if (profile.isEntity) "Business" else "Personal"))
        add(AccountFact("timeZone", "Time zone", profileTimeZoneText(facts.timeZone, facts.timeZoneLabel)))
    }
}

/**
 * The access [roles] give, in words (`Administrator`), across every client when they hold `allClients`. Pure, covered
 * under `jsNodeTest`.
 */
fun profileAccessText(roles: Collection<String>): String {
    val level = PERSONA.accessLabel(PERSONA.accessOf(roles))
    return if (ROLE.allClients in roles) "$level, across every client" else level
}

/**
 * The zone the caller's moments are shown in: [zone] with its [label] (`America/Chicago (CT)`), or, when the shell
 * names none, the site's own fixed offset. Pure, covered under `jsNodeTest`.
 */
fun profileTimeZoneText(zone: String?, label: String?): String =
    zone?.let { z -> label?.let { "$z ($it)" } ?: z }
        ?: "This site's default (UTC${serverTimeZone.offsetAt(Clock.System.now())})"

/**
 * The Profile page's structure line (issue #1479, rule 7 of #1477): the person's address ([loginId]), their [users] --
 * each by what tells it apart from the others, the one acted as marked -- and the access it has. Before the users load,
 * or when the list does not hold the one acted as (a sandbox's), the signed-in user alone, by its client and persona.
 * Pure, covered under `jsNodeTest`.
 */
fun profileStructure(loginId: String, users: List<UserChoice>, profile: UserProfile): StructureLine {
    val roles = profile.roles
    val listed = users.takeIf { all -> all.any { it.isCurrent && it.userId == profile.userId } }
    val rows = listed?.map { u ->
        val label = u.qualifierWithin(listed, roles.takeIf { _ -> u.isCurrent })
            .ifEmpty { "${u.clientName?.ifBlank { null } ?: u.client} / ${personaTerm(u.persona, u.personaSuffix)}" }
        StructureRow(u.userId.toString(), label, u.label(), null, u.isCurrent)
    } ?: listOf(
        StructureRow(
            profile.userId.toString(),
            "${profile.client} / ${PERSONA.label(profile.persona ?: PERSONA.defaultFor(roles))}",
            profile.client,
            null,
            current = true,
        ),
    )
    return StructureLine(loginId.ifBlank { profile.displayName.orEmpty() }, rows, PERSONA.accessPhrase(PERSONA.accessOf(roles)))
}
