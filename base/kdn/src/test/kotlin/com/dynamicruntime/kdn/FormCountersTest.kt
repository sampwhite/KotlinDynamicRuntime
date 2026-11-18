package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.counter.CounterService
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.ScriptZone
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.zonedDayText
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Counters (issue #1849): a client's ids, a prefix and a zero-padded count, issued before a form is saved and stored
 * only with the token they were issued with. Over the shared instance, a client per scenario.
 */
class FormCountersTest : StringSpec({
    val cxt = TestInstances.default("counters1849")
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("counters", client).also { it.userId = 18490L }

    fun clientDef(client: String) = ClientDef(
        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), timeZone = "America/Chicago",
    )

    /** A store's client: a daily receipt counter on the `receipt` trait, and an order counter that never resets. */
    fun storeConfig(client: String, more: GedraConfigBuilder.() -> Unit = {}): GedraConfig =
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(clientDef(client))
            counter("receipt", $$"R${compactDay}-", digits = 4, reset = CTR.resetDaily, description = "Receipts.")
            counter("order", "ORD-", digits = 2)
            trait("ReceiptEntry", "receipt", setOf(GedraDataType.formDoc), "A receipt.") {
                property("number", "The receipt's id.", required = true) { counter = "receipt" }
                property("note", "A note.")
            }
            trait("OrderEntry", "order", setOf(GedraDataType.formDoc), "An order.") {
                property("orderNo", "The order's id.") { counter = "order" }
            }
            // The id as the forms list's column, searched for in part or whole.
            traitUsage("receipt", "Receipt", $$"${number}", substring = true)
            more()
        }

    fun createStore(client: String): TestUser {
        GedraConfigService.get(cxt).writeConfig(asClient(client), storeConfig(client))
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "clerk@$client.test", userClient = client)
    }

    fun TestUser.issue(counter: String): Pair<String, String> =
        postData(GEP.counterIssue, mapOf(CTR.counter to counter)).let { it[CTR.value].toOptStr()!! to it[CTR.token].toOptStr()!! }

    fun receipt(number: String, note: String? = null) =
        mapOf(GE.traitId to "receipt", GE.data to listOfNotNull("number" to number, note?.let { "note" to it }).toMap())

    fun create(entries: List<Map<String, Any?>>, tokens: List<String>) =
        mapOf(GDF.entries to entries, CTR.counterTokens to tokens)

    val chicago = ScriptZone.of(TimeZone.of("America/Chicago"))

    "a counter issues ids in order, the client's day in the prefix and the count padded" {
        val clerk = createStore("store1849a")
        val day = zonedDayText(cxt.instanceNow(), chicago).replace("-", "")
        clerk.issue("receipt").first shouldBe "R$day-0001"
        clerk.issue("receipt").first shouldBe "R$day-0002"
        clerk.issue("order").first shouldBe "ORD-01"
        // A counter the client does not have.
        clerk.expectError(EXC.notFound, GEP.counterIssue, mapOf(CTR.counter to "nosuch"))
    }

    "a form stores an id only with its token, and only once" {
        val client = "store1849b"
        val clerk = createStore(client)
        val (value, token) = clerk.issue("receipt")
        // No token: refused, and nothing stored.
        clerk.expectError(EXC.badInput, GEP.formDocCreate, create(listOf(receipt(value)), emptyList()))[EP.errorMessage]
            .toString() shouldContain "was not sent with the token it was issued with"
        // A value made up, with a real token beside it.
        clerk.expectError(EXC.badInput, GEP.formDocCreate, create(listOf(receipt("R20990101-9999")), listOf(token)))
        // A token changed after it was issued does not open.
        clerk.expectError(EXC.badInput, GEP.formDocCreate, create(listOf(receipt(value)), listOf(token.dropLast(2) + "AA")))
        // Another clerk's token.
        val other = TestUser.create(cxt, "other@$client.test", userClient = client)
        other.expectError(EXC.badInput, GEP.formDocCreate, create(listOf(receipt(value)), listOf(token)))[EP.errorMessage]
            .toString() shouldContain "issued to someone else"
        // With its token: stored.
        val form = clerk.postItem(GEP.formDocCreate, create(listOf(receipt(value)), listOf(token)))
        form[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()["number"] shouldBe value
        // The same token again: the id is already stored.
        clerk.expectError(EXC.badInput, GEP.formDocCreate, create(listOf(receipt(value)), listOf(token)))[EP.errorMessage]
            .toString() shouldContain "is already stored elsewhere"
    }

    "saves racing with one token store it once, and the others are refused as already stored, never a server error" {
        val clerk = createStore("store1849r")
        val (value, token) = clerk.issue("receipt")
        val body = create(listOf(receipt(value)), listOf(token))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val outcomes = try {
            // A success's answer has no status, a refusal's says it.
            (1..4).map { pool.submit<Int> { (clerk.client.sendJsonPostRequest(GEP.formDocCreate, body)[EP.status] as? Number)?.toInt() ?: 200 } }
                .map { it.get() }
        } finally {
            pool.shutdown()
        }
        outcomes.count { it == 200 } shouldBe 1
        outcomes.filter { it != 200 }.toSet() shouldBe setOf(EXC.badInput)
    }

    "a stored id does not change, and the rest of the entry still does" {
        val clerk = createStore("store1849c")
        val (value, token) = clerk.issue("receipt")
        val gedraId = clerk.postItem(GEP.formDocCreate, create(listOf(receipt(value)), listOf(token)))[GDF.gedraId].toOptStr()!!
        fun patch(data: Map<String, Any?>, tokens: List<String> = emptyList()) = mapOf(
            GPF.targets to mapOf(
                GedraDataType.formDoc.name to listOf(
                    mapOf(
                        GDF.gedraId to gedraId,
                        GPF.edits to listOf(mapOf(GED.action to GedraEditAction.addOrMerge.name, GE.traitId to "receipt", GE.data to data)),
                    ),
                ),
            ),
            CTR.counterTokens to tokens,
        )
        clerk.postItems(GEP.patch, patch(mapOf("note" to "paid in cash")))
        // Resending the id it holds is no change.
        clerk.postItems(GEP.patch, patch(mapOf("number" to value, "note" to "paid by card")))
        // Another id, even one issued to this clerk with its token, is refused.
        val (next, nextToken) = clerk.issue("receipt")
        clerk.expectError(EXC.badInput, GEP.patch, patch(mapOf("number" to next), listOf(nextToken)))[EP.errorMessage]
            .toString() shouldContain "does not change"
    }

    "a field left out is given no id, and a later write gives it one with its token" {
        val clerk = createStore("store1849d")
        val gedraId = clerk.postItem(
            GEP.formDocCreate, create(listOf(mapOf(GE.traitId to "order", GE.data to emptyMap<String, Any?>())), emptyList()),
        )[GDF.gedraId].toOptStr()!!
        val (value, token) = clerk.issue("order")
        val edit = mapOf(GED.action to GedraEditAction.addOrMerge.name, GE.traitId to "order", GE.data to mapOf("orderNo" to value))
        val target = mapOf(GDF.gedraId to gedraId, GPF.edits to listOf(edit))
        clerk.postItems(GEP.patch, mapOf(GPF.targets to mapOf(GedraDataType.formDoc.name to listOf(target)), CTR.counterTokens to listOf(token)))
    }

    "an id is a column of the forms list, and a search finds the form by it" {
        val clerk = createStore("store1849h")
        val stored = (1..3).map {
            val (value, token) = clerk.issue("receipt")
            clerk.postItem(GEP.formDocCreate, create(listOf(receipt(value)), listOf(token)))
            value
        }
        // The client's own copy of the listing, which has its search fields.
        val formDocs = clientPath(GEP.formDocs, "store1849h")
        clerk.getItems(formDocs, mapOf("receipt" to stored[1])).size shouldBe 1
        clerk.getItems(formDocs, mapOf("receiptContains" to stored[2].takeLast(4))).size shouldBe 1
    }

    "a daily counter starts again at 1 on the client's next day" {
        val client = "store1849e"
        createStore(client)
        val counters = CounterService.get(cxt)
        val asker = asClient(client)
        // 11:30 PM on the 16th in Chicago, then 12:30 AM on the 17th there.
        val late = Instant.parse("2026-11-17T05:30:00Z")
        counters.issue(asker, client, "receipt", at = late).value shouldBe "R20261116-0001"
        counters.issue(asker, client, "receipt", at = late).value shouldBe "R20261116-0002"
        counters.issue(asker, client, "receipt", at = late + 1.hours).value shouldBe "R20261117-0001"
        // The counter that never resets goes on.
        counters.issue(asker, client, "order", at = late).value shouldBe "ORD-01"
        counters.issue(asker, client, "order", at = late + 1.hours).value shouldBe "ORD-02"
    }

    "a counter that is no good, or a field naming one the client lacks, is refused at the write" {
        val client = "store1849f"
        createStore(client)
        val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
        fun write(more: GedraConfigBuilder.() -> Unit): Map<String, Any?> = mapOf(
            CFEP.name to "extra", CFEP.namespaceField to clientNamespace(client),
            CFEP.slots to gedraConfigToEntries(gedraConfig(cxt, "extra", clientNamespace(client), client, build = more)),
        )
        // A daily count whose prefix ignores the day would repeat its ids.
        admin.expectError(EXC.badInput, CFEP.bundleWrite, write { counter("ticket", "T-", reset = CTR.resetDaily) })
            .toString() shouldContain "does not change with the day"
        // Padded past what a count is padded to.
        admin.expectError(EXC.badInput, CFEP.bundleWrite, write { counter("wide", "W-", digits = 9) })
        // A field naming a counter the client does not have.
        admin.expectError(
            EXC.badInput, CFEP.bundleWrite,
            write {
                trait("TicketEntry", "ticket", setOf(GedraDataType.formDoc), "A ticket.") {
                    property("ticketNo", "The ticket's id.") { counter = "ticket" }
                }
            },
        ).toString() shouldContain "which the client does not have"
        // And a sound one is taken, and issues at once.
        admin.postData(CFEP.bundleWrite, write { counter("ticket", $$"T${compactDay}-", reset = CTR.resetDaily) })
        GedraConfigReload.reloadClient(cxt, client)
        CounterService.get(cxt).forClient(client).keys shouldBe setOf("receipt", "order", "ticket")
    }

    "a counter's entry round-trips through its stored slot" {
        val entries = gedraConfigToEntries(storeConfig("store1849g"))[CCT.counterDef].orEmpty()
        entries.map { it[CTR.counterId] } shouldBe listOf("receipt", "order")
        entries.first() shouldBe mapOf(
            CTR.counterId to "receipt", CTR.prefix to $$"R${compactDay}-", CTR.digits to 4, CTR.reset to CTR.resetDaily,
            CTR.description to "Receipts.",
        )
    }
})
