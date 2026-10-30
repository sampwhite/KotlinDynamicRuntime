package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.user.AccountHistory
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.user.personaTerm
import kotlin.time.Instant

/*
 * The Profile page's layout (issues #1479, #1480), the pure half: its structure line, what its account panel says,
 * and what the caller may do. Built on the page structure of #1477 (`Panels.kt`); covered under `jsNodeTest`.
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
 * The account panel's copy (issue #1479): the `profile` file's [account] namespace and its keys -- a line's label, the
 * words a value is made of, and what the caller may do ([profileAbilities]).
 */
@Suppress("ConstPropertyName")
object PRFA {
    const val account = "account"

    const val signedInAs = "signedInAs"
    const val email = "email"
    const val client = "client"
    const val persona = "persona"
    const val access = "access"
    const val org = "org"
    const val accountType = "accountType"
    const val emailProven = "emailProven"
    const val registered = "registered"
    const val previousSignIn = "previousSignIn"
    const val timeZone = "timeZone"

    const val personal = "personal"
    const val business = "business"
    const val everyClient = "everyClient"
    const val siteZone = "siteZone"
    const val notYet = "notYet"

    const val canAllClients = "canAllClients"
    const val canAdministerClient = "canAdministerClient"
    const val canAdministerOrg = "canAdministerOrg"
    const val canOwnUsers = "canOwnUsers"
    const val canEditClient = "canEditClient"
    const val canEditOrg = "canEditOrg"
    const val canOperateSite = "canOperateSite"
    const val canReadFacts = "canReadFacts"
    const val canOpenSandbox = "canOpenSandbox"
    const val canOwnForms = "canOwnForms"
}

/** What each of the account panel's [PRFA] words says when the page's copy has not arrived. */
val profileAccountDefaults: Map<String, String> = mapOf(
    PRFA.signedInAs to "Signed in as",
    PRFA.email to "Email",
    PRFA.client to "Client",
    PRFA.persona to "Persona",
    PRFA.access to "Access",
    PRFA.org to "Organization",
    PRFA.accountType to "Account type",
    PRFA.emailProven to "Email proven",
    PRFA.registered to "Registered",
    PRFA.previousSignIn to "Previous sign-in",
    PRFA.timeZone to "Time zone",
    PRFA.personal to "Personal",
    PRFA.business to "Business",
    PRFA.everyClient to "across every client",
    PRFA.siteZone to "This site's default",
    PRFA.notYet to "Not yet",
    PRFA.canAllClients to "Administer every client: its users, its configuration, and its forms.",
    PRFA.canAdministerClient to "Administer your client: its users, its configuration, and every form in it.",
    PRFA.canAdministerOrg to "Administer your client's configuration, and the users and forms of your organization.",
    PRFA.canOwnUsers to "Manage the users of your own address, and nobody else's.",
    PRFA.canEditClient to "See the users and forms of your client, and edit its forms.",
    PRFA.canEditOrg to "See the users and forms of your organization, and edit its forms.",
    PRFA.canOperateSite to "See how this site is running: its settings, boot checks, caches, and file store.",
    PRFA.canReadFacts to "Look up what your client's configuration facts mean.",
    PRFA.canOpenSandbox to "Open your client's sandbox, to try a change before it is published.",
    PRFA.canOwnForms to "Work with your own forms.",
)

/** The account panel's words as shipped: [key]'s entry in [profileAccountDefaults], or the key itself. */
fun profileAccountDefault(key: String): String = profileAccountDefaults[key] ?: key

/**
 * The roles Profile describes the signed-in user by (issue #1479): their own, less the `admin` every registrant of
 * `public` is given there -- in `public` an administrator is no different from an ordinary user (`AdminScope.ownIdentity`)
 * -- unless they also hold `allClients`, whose administration is real wherever they are. Pure, covered under
 * `jsNodeTest`.
 */
fun profileShownRoles(profile: UserProfile): Set<String> =
    if (profile.client == CL.public && ROLE.allClients !in profile.roles) profile.roles - ROLE.admin else profile.roles

/**
 * What the caller may do (issue #1479), as [PRFA] keys, widest first: administer every client, else their own -- their
 * organization's users and forms when they have one ([org]), which is as far as a client administrator reads -- else
 * their own address's users (an administrator in `public`); be an editor, as far; the operator's surfaces, by the
 * caller's [roles]; and open the sandbox. A caller who administers no client and edits none works with their own forms.
 * Pure, covered under `jsNodeTest`.
 */
fun profileAbilities(reach: CallerReach, roles: Set<String>, org: String?): List<String> = buildList {
    val inOrg = !org.isNullOrBlank()
    when {
        reach.allClients -> add(PRFA.canAllClients)
        reach.administersClient -> add(if (inOrg) PRFA.canAdministerOrg else PRFA.canAdministerClient)
        reach.managesUsers -> add(PRFA.canOwnUsers)
    }
    if (reach.editsClient) add(if (inOrg) PRFA.canEditOrg else PRFA.canEditClient)
    // The deployment's own pages take the capability beside the rung; a client's operator reads what its facts mean.
    if (RoleLadder.satisfies(roles, ROLE.operator) && ROLE.allClients in roles) {
        add(PRFA.canOperateSite)
    } else if (RoleLadder.highestHeld(roles) == ROLE.operator) {
        add(PRFA.canReadFacts)
    }
    if (reach.opensSandbox) add(PRFA.canOpenSandbox)
    if (!reach.allClients && !reach.administersClient && !reach.editsClient) add(PRFA.canOwnForms)
}

/**
 * One line of the account panel: its label's key in the `profile` file's [PRFA.account] namespace, and its value.
 */
class AccountFact(val key: String, val value: String)

/**
 * What the account panel says of the signed-in user (issues #1479, #1480), in order: who they are signed in as -- when
 * that is not simply their address -- their address and when it was proven, their client, their persona and access,
 * organization, account type, when the user was registered and the sign-in before this one, and last the time zone.
 * Each from data the page or the shell already has: the [profile], the [loginId], the person's [users] (the current
 * one carries the persona's suffix and the client's name), the user's own [history] -- none from a node that serves
 * none, when its lines are left out rather than said to be unproven -- and the shell's [facts]. A moment is absolute
 * ([moment], in the display zone and labeled with it, as a user's own page shows one). A value's words come from
 * [words] (a key and its shipped words), so a deployment's copy can reword them. A line with nothing to say is left
 * out. Pure, covered under `jsNodeTest`.
 */
fun profileAccountFacts(
    profile: UserProfile,
    loginId: String,
    users: List<UserChoice>,
    facts: ShellFacts,
    history: AccountHistory? = null,
    moment: (Instant) -> String = { formatTimestamp(it.toString()) },
    words: (String, String) -> String = { _, shipped -> shipped },
): List<AccountFact> {
    fun word(key: String) = words(key, profileAccountDefault(key))
    val current = users.firstOrNull { it.isCurrent && it.userId == profile.userId }
    val persona = current?.persona ?: profile.persona
    val roles = profileShownRoles(profile)
    return buildList {
        profile.displayName?.ifBlank { null }?.takeIf { it != loginId }?.let { add(AccountFact(PRFA.signedInAs, it)) }
        loginId.ifBlank { null }?.let { add(AccountFact(PRFA.email, it)) }
        history?.let { h -> add(AccountFact(PRFA.emailProven, h.verifiedAt?.let(moment) ?: word(PRFA.notYet))) }
        add(AccountFact(PRFA.client, profileClientName(users, profile.client)))
        // The persona alone: the access is the next line, so the persona does not say it again.
        persona?.let { add(AccountFact(PRFA.persona, personaTerm(it, current?.personaSuffix.orEmpty()))) }
        add(AccountFact(PRFA.access, profileAccessText(roles, word(PRFA.everyClient))))
        profile.org?.ifBlank { null }?.let { add(AccountFact(PRFA.org, it)) }
        add(AccountFact(PRFA.accountType, word(if (profile.isEntity) PRFA.business else PRFA.personal)))
        history?.registeredAt?.let { add(AccountFact(PRFA.registered, moment(it))) }
        history?.previousLoggedInAt?.let { add(AccountFact(PRFA.previousSignIn, moment(it))) }
        add(AccountFact(PRFA.timeZone, profileTimeZoneText(facts.timeZone, facts.timeZoneLabel, word(PRFA.siteZone))))
    }
}

/**
 * The access [roles] give, in words (`Administrator`), then [everyClient] (`across every client`) where `allClients`
 * widens it: beside `admin` or `operator`, whose surfaces it widens. It confers no level of its own, so beside a lower
 * one it says nothing. Pure, covered under `jsNodeTest`.
 */
fun profileAccessText(roles: Collection<String>, everyClient: String = profileAccountDefault(PRFA.everyClient)): String {
    val level = PERSONA.accessLabel(PERSONA.accessOf(roles))
    val widened = ROLE.allClients in roles && RoleLadder.satisfies(roles.toSet(), ROLE.operator)
    return if (widened) "$level, $everyClient" else level
}

/**
 * The zone the caller's moments are shown in: [zone] with its [label] (`America/Chicago (CT)`), or, when the shell names
 * none, [siteZone] with the label every moment on the page carries ([deploymentZoneLabel], `GMT-8`). Pure, covered
 * under `jsNodeTest`.
 */
fun profileTimeZoneText(zone: String?, label: String?, siteZone: String = profileAccountDefault(PRFA.siteZone)): String =
    zone?.let { z -> label?.let { "$z ($it)" } ?: z } ?: "$siteZone ($deploymentZoneLabel)"

/**
 * The Profile page's structure line (issue #1479, rule 7 of #1477): the person's address ([loginId]), their [users] --
 * each by what tells it apart from the others, the one acted as marked -- and the access it has ([profileShownRoles]).
 * When the list does not hold the one acted as (it failed to load), the signed-in user alone, by its client and
 * persona. Pure, covered under `jsNodeTest`.
 */
fun profileStructure(loginId: String, users: List<UserChoice>, profile: UserProfile): StructureLine {
    val roles = profileShownRoles(profile)
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
