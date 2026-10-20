package com.dynamicruntime.common.simulation

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.AD
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.addDays
import kotlin.time.Instant

/**
 * The listing demo (issue #1316): a client holding hundreds of forms and users, so the forms listing and the Users
 * page can be reviewed against lists long enough to matter -- past the 500-row cap by default, so "narrow the search"
 * is part of what is looked at. Every value is a function of a form's or user's index, so one review's screenshot
 * compares with the next, and a rerun **tops up** to the counts rather than adding them again.
 */
@Suppress("ConstPropertyName")
object ListingDemo {
    const val client = "listingdemo"

    /** The simulation that provisions it: `/fixture/simulate/listing-demo`. */
    const val simulationName = "listing-demo"

    /** The configuration holding the client's definition and its traits. */
    const val main = "main"

    const val defaultForms = 650
    const val defaultUsers = 550

    /** The most of either a run may ask for: enough to go well past any cap, short of a run that takes minutes. */
    const val maxCount = 5000

    /** How many owners hold the heavy share of the forms (see [listingDemoOwnerIndex]). */
    const val heavyOwners = 5

    const val category = "category"
    const val amount = "amount"
    const val due = "due"

    val categories = listOf("facilities", "safety", "finance", "it", "grounds", "fleet")
    val subjects = listOf("Bridge", "Roof", "Pump", "Fence", "Boiler", "Elevator", "Parking", "Lighting")
    val kinds = listOf("inspection", "repair", "survey", "quote", "audit")
    val firstNames = listOf(
        "Avery", "Blake", "Casey", "Devon", "Emery", "Finley", "Gray", "Harper", "Indy", "Jordan",
        "Kai", "Logan", "Morgan", "Noel", "Oakley", "Parker", "Quinn", "Riley", "Sage", "Taylor",
    )
    val lastNames = listOf(
        "Abbott", "Barnes", "Chen", "Diaz", "Ellis", "Fischer", "Garcia", "Hughes", "Ito", "Jensen", "Kowalski",
        "Lopez", "Mensah", "Nakamura", "Okafor", "Patel", "Quinlan", "Rossi", "Silva", "Tanaka", "Underwood",
        "Vargas", "Weber", "Xu", "Young",
    )

    /** The first due date; the rest spread over two years from it. */
    val firstDue: Instant = Instant.parse("2025-01-01T12:00:00Z")
}

/** The demo client's definition and traits, in one configuration. */
fun listingDemoMain(cxt: KdrCxtBase, client: String = ListingDemo.client): GedraConfig =
    gedraConfig(cxt, ListingDemo.main, clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = "Listing demo", usageType = ClientUsageType.demo,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.dev),
                includedTraits = listOf(GT.name),
            ),
        )
        trait("CategoryEntry", ListingDemo.category, setOf(GedraDataType.formDoc), "What area the request is for.") {
            property(ListingDemo.category, "The area.") {
                title = "Category"
                for (c in ListingDemo.categories) option(c)
            }
        }
        trait("AmountEntry", ListingDemo.amount, setOf(GedraDataType.formDoc), "What the request is estimated to cost.") {
            property(ListingDemo.amount, "The estimate, in dollars.") {
                title = "Amount"
                type = SCT.number
            }
        }
        trait("DueEntry", ListingDemo.due, setOf(GedraDataType.formDoc), "When the request is due.") {
            property(ListingDemo.due, "The due date.") {
                title = "Due"
                dateTime()
            }
        }
        // The listing's columns, and so its search fields (issues #537, #538): one per trait, a kind each.
        traitUsage(GT.name, "Request", $$"${name}", substring = true)
        traitUsage(ListingDemo.category, "Category", $$"${category}")
        traitUsage(ListingDemo.amount, "Amount", $$"${amount}", UsageKind.number)
        traitUsage(ListingDemo.due, "Due", $$"${due}", UsageKind.date)
    }

/** The entries of the demo's [n]th form: a searchable title, a category, an amount, and a due date, each varied by [n]. */
fun listingDemoEntries(n: Int): List<Map<String, Any?>> {
    val subject = ListingDemo.subjects[n % ListingDemo.subjects.size]
    val kind = ListingDemo.kinds[(n / ListingDemo.subjects.size) % ListingDemo.kinds.size]
    fun entry(traitId: String, field: String, value: Any?) = mapOf(GE.traitId to traitId, GE.data to mapOf(field to value))
    return listOf(
        entry(GT.name, GT.nameField, "$subject $kind ${(n + 1).toString().padStart(4, '0')}"),
        entry(ListingDemo.category, ListingDemo.category, ListingDemo.categories[(n * 5) % ListingDemo.categories.size]),
        entry(ListingDemo.amount, ListingDemo.amount, ((n * 7919L) % 100_000L) / 100.0),
        entry(ListingDemo.due, ListingDemo.due, ListingDemo.firstDue.addDays((n * 11) % 730).toString()),
    )
}

/**
 * Which of [userCount] users owns the [n]th form: four in ten go to the first [ListingDemo.heavyOwners], the rest
 * spread over the next three in five of the users, and the remaining users own none -- so a heavy owner's own
 * listing is long, most owners hold a few, and some hold nothing.
 */
fun listingDemoOwnerIndex(n: Int, userCount: Int): Int {
    if (userCount <= ListingDemo.heavyOwners) return n % userCount
    // Numbered by how many heavy forms came before this one, so the share goes round all the heavy owners in turn.
    if (n % 10 < 4) return ((n / 10) * 4 + n % 10) % ListingDemo.heavyOwners
    val spread = ((userCount - ListingDemo.heavyOwners) * 3 / 5).coerceAtLeast(1)
    return ListingDemo.heavyOwners + (n * 37) % spread
}

/** One of the demo's users, as created: its address, its name, its persona suffix, and whether it is claimed. */
class ListingDemoUser(val email: String, val name: String, val personaSuffix: String, val registered: Boolean)

/**
 * The demo's [i]th user in [client]: a name that spreads the alphabet, a persona suffix on some, and claimed by the
 * person -- except one in six, left for the person to claim.
 */
fun listingDemoUser(i: Int, client: String): ListingDemoUser = ListingDemoUser(
    email = "user${(i + 1).toString().padStart(4, '0')}@$client.example",
    name = "${ListingDemo.firstNames[i % ListingDemo.firstNames.size]} ${ListingDemo.lastNames[(i * 7) % ListingDemo.lastNames.size]}",
    personaSuffix = when {
        i % 11 == 3 -> "A"
        i % 17 == 5 -> "B"
        else -> ""
    },
    registered = i % 6 != 5,
)

/**
 * Provisions the listing demo (issue #1316): the client `listingdemo<suffix>` with its configuration published, then
 * its users and forms topped up to [userCount] and [formCount] -- a user already there is kept as it is, and only the
 * forms missing from the count are created -- and an administrator to sign in as.
 */
fun provisionListingDemo(
    cxt: KdrCxt,
    suffix: String? = null,
    formCount: Int = ListingDemo.defaultForms,
    userCount: Int = ListingDemo.defaultUsers,
): SimulationReport {
    for ((what, n) in listOf("formCount" to formCount, "userCount" to userCount)) {
        if (n !in 1..ListingDemo.maxCount) {
            throw KdrException.mkInput("$what is from 1 to ${ListingDemo.maxCount}; $n is not.")
        }
    }
    val client = ListingDemo.client + Simulations.checkedSuffix(suffix)
    Simulations.provisionConfig(cxt, listingDemoMain(cxt, client))

    val users = (0 until userCount).map { i -> provisionListingDemoUser(cxt, client, listingDemoUser(i, client)) }

    val bound = cxt.mkSubContext("simulation", client)
    val held = GedraDataService.get(bound).listGedras(bound, GedraDataType.formDoc, ReadScope.ofClient(client), limit = 0).numAvailable
    for (n in held until formCount) Simulations.createForm(cxt, users[listingDemoOwnerIndex(n, userCount)], listingDemoEntries(n))

    val admin = Simulations.provisionUser(cxt, "chief@$client.example", client, ROLE.admin, name = "Casey Chief")
    val heavy = users.first()
    return SimulationReport(
        clients = listOf(client),
        users = listOf(
            Simulations.reported(admin, ROLE.admin, "the administrator, who sees every form and every user"),
            Simulations.reported(heavy, ROLE.user, "an owner holding a large share of the forms"),
        ),
        startPage = "page=${HMENU.pageForms}",
        summary = "Client '$client' holds ${maxOf(held, formCount)} forms and $userCount users (besides its administrator). " +
            "Sign in as the administrator for the forms listing and the Users page.",
    )
}

/**
 * The demo's user [u] in [client]: the one already at its address, or a new one -- registered or left for the
 * person to claim, as [u] says.
 */
private fun provisionListingDemoUser(cxt: KdrCxt, client: String, u: ListingDemoUser): AuthUserRow {
    val service = UserService.get(cxt)
    service.checkInit(cxt)
    service.queryIdentityByAddress(cxt, u.email)?.let { identity ->
        service.usersOfIdentity(cxt, identity.identityId).firstOrNull { it.client == client }?.let { return it }
    }
    val userId = service.provisionUser(
        cxt, u.email, client, listOf(ROLE.user), createdAt = cxt.now(), persona = PERSONA.member,
        personaSuffix = u.personaSuffix, verifiedAt = if (u.registered) cxt.now() else null, registered = u.registered,
    ) { authUserData -> AuthUserRow.normalizeName(u.name)?.let { authUserData[AD.name] = it } }
    return service.queryByUserId(cxt, userId)
        ?: throw KdrException("Could not load the just-created user '${u.email}'.")
}
