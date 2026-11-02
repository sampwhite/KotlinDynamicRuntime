package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.TraitLocks
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.layout
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A trait's name in a server message -- a lock's refusal -- by the rule every page names a trait with (issue #1520): its
 * data type's layout heading, its backend pull resolved, then its title, then its id humanized. Over a client of its own.
 */
class TraitNameTest : StringSpec({
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
            layout(label = "## Site Visit Data\nWhere you went, and why.") {}
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

    "the heading's first line, then the title, then the id humanized" {
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
})
