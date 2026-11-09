package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.workflow.TraitLocks
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.shouldBe

/**
 * How a trait is named to a person (issue #1739 gathered these): in a server message, by the rule every page uses (issue
 * #1520), and in the forms listing, as each row's own client names it (issue #1543). Each part below is one of the specs
 * that used to be a file of its own, kept whole.
 */
class TraitNamesTest : StringSpec({
    include(traitNameTests)
    include(listingTraitNamesTests)
})

/**
 * A trait's name in a server message -- a lock's refusal -- by the rule every page names a trait with (issue #1520): its
 * data type's layout heading, its backend pull resolved, then its title, then its id humanized. Over a client of its own.
 */
private val traitNameTests = stringSpec {
    val cxt = TestInstances.default("traitName1520")
    val client = "traitname1520"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("SiteVisitEntry", "siteVisit", setOf(GedraDataType.formDoc), "A visit.") {
            title = "Visit (title)"
            property("where", "Where it was.")
            layout(label = "## Site Visit Data", description = "Where you went, and why.") {}
        }
        trait("ClaimEntry", "claim", setOf(GedraDataType.formDoc), "A claim.") {
            title = "Expense claim"
            property("amount", "How much.")
        }
        trait("PlainEntry", "plainThing", setOf(GedraDataType.formDoc), "Plain.") { property("x", "X.") }
        // A heading pulled from a backend fragment file is named by the copy it pulls.
        trait("PulledEntry", "pulled", setOf(GedraDataType.formDoc), "Pulled.") {
            property("y", "Y.")
            layout(fragmentFileId = "mail", label = """%{@t("common.footer")}""") {}
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), config)
    GedraConfigReload.reloadClient(cxt, client)
    val asClient = cxt.mkSubContext("names", client)

    "the layout label, then the title, then the id humanized" {
        TraitLocks.traitName(asClient, client, "siteVisit") shouldBe "Site Visit Data"
        TraitLocks.traitName(asClient, client, "claim") shouldBe "Expense claim"
        TraitLocks.traitName(asClient, client, "plainThing") shouldBe "Plain thing"
        // A trait the client does not carry is named by its id.
        TraitLocks.traitName(asClient, client, "notHere") shouldBe "Not here"
    }

    "a pulled heading is named by the copy it pulls" {
        TraitLocks.traitName(asClient, client, "pulled") shouldBe
            "This message was sent automatically. If you were not expecting it, you can ignore it."
    }
}

/**
 * The forms listing names each row's traits as the **row's** client names them (issue #1543), in the row's
 * `traitNames`. A cross-client administrator's listing with no client chosen holds rows from every client, while the
 * page's catalog is the administrator's own client's: it knows neither another client's own traits nor that client's
 * name for a global one. Over two clients of their own: `renamer` names the platform's name trait **Talk title** (a
 * layout-only alteration, as Adapt writes) and has a trait of its own with a heading; `plain` takes both as they are.
 */
private val listingTraitNamesTests = stringSpec {
    val cxt = TestInstances.default("listingTraitNames1543")
    val renamer = "renamer1543"
    val plain = "plain1543"

    fun store(client: String, build: GedraConfigBuilder.() -> Unit) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    includedTraits = listOf(GT.name),
                ),
            )
            build()
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    store(renamer) {
        type("${GCFG.globalNamespace}.${traitDataTypeName(GT.nameEntry)}") {
            layout(label = "Talk title", description = "What the program calls the talk.") {}
        }
        trait("SiteVisitEntry", "siteVisit", setOf(GedraDataType.formDoc), "A visit.") {
            property("where", "Where it was.")
            layout(label = "## Site Visit Data", description = "Where you went, and why.") {}
        }
        // A heading pulled from a backend fragment file, resolved for the row's client.
        trait("PulledEntry", "pulled", setOf(GedraDataType.formDoc), "Pulled.") {
            property("y", "Y.")
            layout(fragmentFileId = "mail", label = """%{@t("common.footer")}""") {}
        }
    }
    store(plain) {}

    fun entry(traitId: String, data: Map<String, Any?>) = mapOf(GE.traitId to traitId, GE.data to data)
    val renamerDoc = TestUser.create(cxt, "u@$renamer.test", userClient = renamer).postItem(
        GEP.formDocCreate,
        mapOf(
            GDF.entries to listOf(
                entry(GT.name, mapOf(GT.nameField to "Kotlin everywhere")),
                entry("siteVisit", mapOf("where" to "Lyon")),
                entry("pulled", mapOf("y" to "1")),
            ),
        ),
    )
    val plainUser = TestUser.create(cxt, "u@$plain.test", userClient = plain)
    val plainDoc = plainUser.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry(GT.name, mapOf(GT.nameField to "Plain")))))
    val renamerNames = mapOf(
        GT.name to "Talk title",
        "siteVisit" to "Site Visit Data",
        "pulled" to "This message was sent automatically. If you were not expecting it, you can ignore it.",
    )

    fun namesOf(rows: List<Map<String, Any?>>, doc: Map<String, Any?>) =
        rows.single { it[GDF.gedraId] == doc[GDF.gedraId] }[GDF.traitNames].toJsonMapOrEmpty()

    "with no client chosen, an operator's listing names each row's traits as the row's own client does" {
        // In plain, so the page's catalog would be plain's.
        val operator = TestUser.createFullAdmin(cxt, "chief@$plain.test", userClient = plain)
        // Narrowed by user, which keeps the listing across clients (no client chosen) and its page this spec's own.
        fun listed(email: String) = operator.getItems(GEP.formDocs, mapOf(EI.user to email))
        namesOf(listed("u@$renamer.test"), renamerDoc) shouldBe renamerNames
        namesOf(listed("u@$plain.test"), plainDoc) shouldBe mapOf(GT.name to "Name")
        // And as a client is chosen, and on a single read: the same names.
        namesOf(operator.getItems(GEP.formDocs, mapOf(EI.client to renamer)), renamerDoc) shouldBe renamerNames
        operator.getItem(GEP.formDoc, mapOf(GDF.gedraId to renamerDoc[GDF.gedraId]))[GDF.traitNames] shouldBe renamerNames
    }

    "a client's own listing names its traits as its catalog does" {
        namesOf(plainUser.getItems(GEP.formDocs), plainDoc) shouldBe mapOf(GT.name to "Name")
    }
}
