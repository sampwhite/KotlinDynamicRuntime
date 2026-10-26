package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientPresentationFields
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.home.PFRAG
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Clock

/*
 * The client page's layout (issue #1397), its pure half: what the header says and links to, how the definition's
 * read-only rows are grouped, and what the collapsed Menu panel says of itself. `ClientsPage.kt` draws it; covered by
 * `ClientsPageTest`.
 */

/**
 * A headed group of the definition's read-only rows (issue #1397): [heading] over its [rows], with the "What is this?"
 * of the `clients` file's namespace [explanationNs]. The first three are the definition editor's own sections
 * ([ClientPresentationFields.Section]), so the rows and the editor group a field alike; the fourth is where the client
 * runs, which the editor does not change.
 */
class SummaryGroup(val heading: String, val explanationNs: String, val rows: List<Pair<String, String>>)

/** The heading of the group of where a client runs (issue #1397): the one group the definition editor has no section for. */
const val runsGroupHeading = "Where it runs"

/**
 * The read-only summary of a client (issues #906, #1397) as [SummaryGroup]s, empty groups left out. From the overview
 * [row] when the listing has one (origin, plan) and the definition [def] when it could be read; either may be missing
 * -- a client this node does not carry has no definition to retrieve, and a deep link arrives before the listing -- so
 * [clientId] is the address's, which is always there. The attributes the caller does not edit here say who sets them
 * ([whoSetsNote], issue #1116). A list that is empty reads as a dash.
 *
 * What the page shows elsewhere is not repeated here: the load is a chip in the header, the forms and users counts are
 * its links ([clientHeaderLinks]), and the workflows are a section of their own. The traits are too, where that
 * section is drawn ([traitsShownBelow]); where it is not -- a client declared in source code -- they are listed here.
 * Pure, and covered under `jsNodeTest`.
 */
fun clientSummaryGroups(
    clientId: String,
    row: ClientOverview?,
    def: ClientDefinitionView?,
    canSeeAllClients: Boolean,
    traitsShownBelow: Boolean = false,
): List<SummaryGroup> {
    val info = def?.info.orEmpty()
    fun text(key: String): String = info[key].toOptStr()?.ifBlank { null } ?: "—"
    fun list(items: List<String>): String = if (items.isEmpty()) "—" else items.joinToString(", ")
    // Said whose it is when this caller does not edit it here.
    fun marked(key: String, value: String): String =
        whoSetsNote(key, canSeeAllClients)?.takeIf { value != "—" }?.let { "$value $it" } ?: value
    val name = row?.name?.ifBlank { null } ?: info[CLD.name].toOptStr().orEmpty()
    val about = buildList {
        add("Client id" to clientId)
        add("Name" to name.ifBlank { "—" })
        add("Description" to text(CLD.description))
    }
    val behavior = buildList {
        if (def == null) return@buildList
        add("Time zone" to timeZoneText(info[CLD.timeZone].toOptStr(), Clock.System.now()))
        add("User labels" to list(info[CLD.userLabels].toJsonListOfStrings()))
        // The value in force (issue #1308): the client's, else the platform's, said as such.
        add(humanizeFieldName(CLD.sessionHours) to inForceText(CLD.sessionHours, info, def.platformDefaults))
        val refresh = inForceText(CLD.refreshIntervalSeconds, info, def.platformDefaults)
        add(humanizeFieldName(CLD.refreshIntervalSeconds) to if (info[CLD.refreshIntervalSeconds] != null) marked(CLD.refreshIntervalSeconds, refresh) else refresh)
        add("Listing columns" to list(def.usageLabels))
        if (!traitsShownBelow) add("Traits" to list(def.traitIds))
    }
    val platform = buildList {
        if (def == null) return@buildList
        // The authority the platform sets (#820, #1107): a client's own administrator sees it, marked.
        for (key in listOf(CLD.audience, CLD.usageType, CLD.customerId)) add(humanizeFieldName(key) to marked(key, text(key)))
        add("Extends" to marked(CLD.extendsFromClientId, text(CLD.extendsFromClientId)))
        add("Domain" to marked(CLD.domainPrefix, info[CLD.customDomain].toOptStr() ?: info[CLD.domainPrefix].toOptStr()?.let { "$it (prefix)" } ?: "—"))
        add("Web resources" to marked(CLD.webResourcesId, text(CLD.webResourcesId)))
    }
    val runs = buildList {
        row?.let { add("Definition" to clientOriginText(it.origin, it.storedConfigs)) }
        if (def != null) {
            add("Enabled in" to marked(CLD.enabledEnvironments, list(info[CLD.enabledEnvironments].toJsonListOfStrings())))
            add("Static config" to marked(CLD.staticConfig, if (info[CLD.staticConfig] == true) "Yes" else "No"))
            add("Preload" to marked(CLD.preload, if (info[CLD.preload] == true) "Yes" else "No"))
        }
        row?.planUsage?.let { usage -> add("Plan" to planUsageText(usage)) }
    }
    return listOf(
        SummaryGroup(ClientPresentationFields.Section.about.heading, PFRAG.aboutNs, about),
        SummaryGroup(ClientPresentationFields.Section.behavior.heading, PFRAG.behaviorNs, behavior),
        SummaryGroup(ClientPresentationFields.Section.platform.heading, PFRAG.platformNs, platform),
        SummaryGroup(runsGroupHeading, PFRAG.runsNs, runs),
    ).filter { it.rows.isNotEmpty() }
}

/** Every row of [clientSummaryGroups], in order: the summary as one list, for what reads a row by its label. Pure. */
fun clientSummaryRows(clientId: String, row: ClientOverview?, def: ClientDefinitionView?, canSeeAllClients: Boolean, traitsShownBelow: Boolean = false): List<Pair<String, String>> =
    clientSummaryGroups(clientId, row, def, canSeeAllClients, traitsShownBelow).flatMap { it.rows }

/** A link in the client page's header (issue #1397): its words, and where it goes -- null for a count with nowhere to go. */
class HeaderLink(val text: String, val href: String?)

/**
 * The line of links under a client's name (issue #1397): its forms and users, counted, each opening the listing
 * behind it ([clientSummaryHref], as the Clients listing's counts do), then its **Reports**. For a client this node
 * carries, which is where those pages work; one it does not carry has its counts only, as text, and no Reports. Pure,
 * and covered under `jsNodeTest`.
 */
fun clientHeaderLinks(row: ClientOverview?, acrossClients: Boolean, clientId: String): List<HeaderLink> {
    if (row == null) return emptyList()
    val present = row.status == ClientStatus.present.name
    return buildList {
        add(HeaderLink(countWord(row.forms, "form"), clientSummaryHref(summaryFormsRow, row, acrossClients)))
        val users = countWord(row.users, "user") + if (row.unclaimedUsers == 0) "" else " (${row.unclaimedUsers} unclaimed)"
        add(HeaderLink(users, clientSummaryHref(summaryUsersRow, row, acrossClients)))
        // Named by client only for an administrator who looks across them; a scoped one's reports are their own.
        if (present) add(HeaderLink("Reports", reportsHref(clientId.takeIf { acrossClients })))
    }
}

/** "1 form", "3 forms". */
private fun countWord(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * The chips beside a client's name (issue #1397): its load ([clientLoadText]), and what it is to a sandbox -- whose
 * sandbox it is, or that it has one. Pure, and covered under `jsNodeTest`.
 */
fun clientStatusChips(row: ClientOverview?, issueCount: Int): List<String> {
    if (row == null) return emptyList()
    return buildList {
        add(clientLoadText(row.status, issueCount))
        when {
            row.sandboxOf != null -> add("Sandbox of ${row.sandboxOf}")
            row.hasSandbox -> add("Has a sandbox")
        }
    }
}

/**
 * The line under the chips that says how a save on the page takes effect (issue #1397), before anything is edited:
 * the answer every editor below says beside its own save ([saveEffectText], [pageSavesDraft]). Null for a client
 * that takes nothing stored here, whose page saves nothing. Pure, and covered under `jsNodeTest`.
 */
fun clientSaveEffectLine(row: ClientOverview?, savesDraft: Boolean): String? = when {
    row?.staticHere == true -> null
    row?.sandboxOf != null -> "Changes made here are drafts of ${row.sandboxOf}'s configuration, which this sandbox shows until they are published."
    savesDraft -> "Changes made here are drafts, shown in the client's sandbox until they are published."
    else -> "Changes made here are live for the client's people at once."
}

/**
 * The banner under the header while configuration waits to be published (issue #1397): how many of the client's
 * stored configurations have a latest revision that could be published, or checked, from the table below
 * ([selectableBundles]); null when none has. Pure, and covered under `jsNodeTest`.
 */
fun unpublishedBannerText(row: ClientOverview?, configs: List<ConfigSummaryView>?): String? {
    val n = configs?.let { selectableBundles(row, it).size } ?: 0
    return when (n) {
        0 -> null
        1 -> "1 configuration has unpublished changes."
        else -> "$n configurations have unpublished changes."
    }
}

/**
 * The menu items the client's own people may be offered (issue #1397), which the Menu panel lists unless asked for
 * every item: all but those shipped for the deployment's own people ([MenuItemView.deploymentOnly], the backend's to
 * say) -- its operators, a test instance, a debug session -- unless the client changed one, which always shows, as
 * does a group any listed item sits under. Pure, and covered under `jsNodeTest`.
 */
fun menuItemsForPeople(items: List<MenuItemView>): List<MenuItemView> {
    fun forPeople(item: MenuItemView): Boolean = !item.deploymentOnly || item.stored
    val parents = items.filter(::forPeople).mapNotNull { it.parentId }.toSet()
    return items.filter { forPeople(it) || it.itemId in parents }
}

/**
 * What the collapsed Menu panel says of itself (issue #1397): how many home-menu items the client's configuration
 * changes, from the overrides report's rows ([changedItems]). Pure, and covered under `jsNodeTest`.
 */
fun menuPanelSummary(changedItems: Int): String = when (changedItems) {
    0 -> "Shipped as is"
    1 -> "1 item changed by this client"
    else -> "$changedItems items changed by this client"
}
