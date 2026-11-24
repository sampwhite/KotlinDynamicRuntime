package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.counter.CounterService
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
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
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.computeValue
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.ScriptZone
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.zonedDayText
import io.kotest.assertions.throwables.shouldThrow
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
            // A ticket's id, given when the form is saved rather than as it opens (issue #1852).
            counter("ticket", "TK-", digits = 3, issue = CTR.issueOnSave)
            trait("TicketEntry", "ticket", setOf(GedraDataType.formDoc), "A ticket.") {
                property("ticketNo", "The ticket's id.", required = true) { counter = "ticket" }
            }
            // A sale's creation workflow: the receipt, and a ticket beside it.
            workflow("sell", WfEntry.creation) {
                task("ring", "Ring up") {
                    trait("receipt")
                    trait("ticket", required = false)
                    save("create", "Create")
                }
            }
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
                .map {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    it.get()
                }
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

    "a create leaving a counter's field out is given the next id, on each create path, and answers with it" {
        val client = "store1852a"
        val clerk = createStore(client)
        val day = zonedDayText(cxt.instanceNow(), chicago).replace("-", "")
        fun idIn(form: Map<String, Any?>, traitId: String, field: String) =
            form[GDF.entries].toJsonListOfMaps().single { it[GE.traitId] == traitId }[GE.data].toJsonMapOrEmpty()[field]
        fun empty(traitId: String) = mapOf(GE.traitId to traitId, GE.data to emptyMap<String, Any?>())

        // The plain create: a required id and an optional one, both left out.
        val made = clerk.postItem(GEP.formDocCreate, create(listOf(empty("receipt"), empty("order")), emptyList()))
        idIn(made, "receipt", "number") shouldBe "R$day-0001"
        idIn(made, "order", "orderNo") shouldBe "ORD-01"
        // Beside an id sent with its token, which is stored as sent.
        val (sent, token) = clerk.issue("receipt")
        val both = clerk.postItem(GEP.formDocCreate, create(listOf(receipt(sent), empty("order")), listOf(token)))
        idIn(both, "receipt", "number") shouldBe sent
        idIn(both, "order", "orderNo") shouldBe "ORD-02"

        // A creation workflow's save, the ticket's counter giving its id at save as every counter does here.
        val saved = clerk.postData(
            clientPath(GEP.workflowSave, client),
            mapOf(
                GDF.workflowId to "sell", GDF.taskId to "ring", GDF.saveId to "create",
                GDF.entries to listOf(mapOf(GE.traitId to "receipt", GE.data to mapOf("note" to "cash")), empty("ticket")),
            ),
        )
        saved["saved"] shouldBe true
        val savedForm = saved[WSF.item].toJsonMapOrEmpty()
        idIn(savedForm, "receipt", "number") shouldBe "R$day-0003"
        idIn(savedForm, "ticket", "ticketNo") shouldBe "TK-001"

        // An all-clients administrator's create for the clerk, in the clerk's client.
        val operator = TestUser.create(
            cxt, "operator@$client.test", level = ROLE.admin, capabilities = listOf(ROLE.allClients), userClient = client,
        )
        val forClerk = operator.postItem(
            GEP.adminFormDocForUser, mapOf(EI.user to clerk.userId.toString(), GDF.entries to listOf(empty("receipt"))),
        )
        idIn(forClerk, "receipt", "number") shouldBe "R$day-0004"
    }

    "a create refused by its check uses up no id" {
        val client = "store1852b"
        val clerk = createStore(client)
        val day = zonedDayText(cxt.instanceNow(), chicago).replace("-", "")
        val operator = TestUser.create(
            cxt, "operator@$client.test", level = ROLE.admin, capabilities = listOf(ROLE.allClients), userClient = client,
        )
        // Loose entries, so the stored shape's check is what refuses the field the receipt does not declare.
        operator.expectError(
            EXC.badInput, GEP.adminFormDocForUser,
            mapOf(
                EI.user to clerk.userId.toString(),
                GDF.entries to listOf(mapOf(GE.traitId to "receipt", GE.data to mapOf("bogus" to 1))),
            ),
        )[EP.errorMessage].toString() shouldContain "bogus"
        // The count did not move.
        clerk.issue("receipt").first shouldBe "R$day-0001"
    }

    "a create past the client's plan uses up no id" {
        val client = "${CUST.free}_count1852"
        val full = TestUser.createFullAdmin(cxt, "operator@count1852.test")
        full.postData(
            ADEP.clientCreate,
            mapOf(
                CLD.clientId to client, CLD.name to client, CLD.usageType to ClientUsageType.dev.name,
                CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
                CLD.starterWorkflows to false,
            ),
        )
        val config = gedraConfig(cxt, "numbers", clientNamespace(client), client) {
            counter("order", "ORD-", digits = 2)
            trait("OrderEntry", "order", setOf(GedraDataType.formDoc), "An order.") {
                property("orderNo", "The order's id.") { counter = "order" }
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
        val forms = GedraDataService.get(cxt)
        val owner = asClient(client)
        repeat(PLAN.freeQuotas.forms!!) { forms.createGedra(owner, GedraDataType.formDoc, emptyList()) }
        shouldThrow<KdrException> {
            forms.createGedra(owner, GedraDataType.formDoc, listOf(mapOf(GE.traitId to "order", GE.data to emptyMap<String, Any?>())))
        }.message.orEmpty() shouldContain "as many as its plan allows"
        CounterService.get(cxt).issue(owner, client, "order").value shouldBe "ORD-01"
    }

    "a field its form is not to hold, or whose counter issues no ids, is given none" {
        val client = "store1852d"
        val refunds: GedraConfigBuilder.() -> Unit = {
            counter("refund", "RF-", digits = 2)
            counterFromMap(mapOf(CTR.counterId to "retired", CTR.prefix to "X-", CTR.enabled to false))
            trait("RefundEntry", "refund", setOf(GedraDataType.formDoc), "A refund.") {
                property("kind", "Sale or refund.")
                property("refundNo", "The refund's id.") { counter = "refund" }
                property("oldNo", "An id no longer given.") { counter = "retired" }
                property("auditNo", "The auditors' id.") {
                    counter = "refund"
                    visibleWhen = CFACTS.hasAdminLevel
                }
                presentWhen("refundNo", on = "kind", value = "refund")
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), storeConfig(client, refunds))
        GedraConfigReload.reloadClient(cxt, client)
        val clerk = TestUser.create(cxt, "clerk@$client.test", userClient = client)
        fun made(kind: String) = clerk.postItem(
            GEP.formDocCreate, create(listOf(mapOf(GE.traitId to "refund", GE.data to mapOf("kind" to kind))), emptyList()),
        )[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()
        // The rule forbids the refund's id on a sale, the gate hides the auditors', and a disabled counter gives none.
        made("sale") shouldBe mapOf("kind" to "sale")
        // Required by the same rule on a refund, and so not asked of the caller: the first id, none used up above.
        made("refund") shouldBe mapOf("kind" to "refund", "refundNo" to "RF-01")
    }

    "entries keyed by their counter's field are each given their key, and a value computed on write sees the id" {
        val client = "store1852e"
        val seats: GedraConfigBuilder.() -> Unit = {
            counter("seat", "S-", digits = 2)
            trait("SeatEntry", "seat", setOf(GedraDataType.formDoc), "A seat.") {
                primaryKey("seatNo")
                property("seatNo", "The seat's id.", required = true) { counter = "seat" }
                property("label", "Computed.") { derived = true }
                onWrite(computeValue("label", "'Seat ' ~ seatNo"))
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), storeConfig(client, seats))
        GedraConfigReload.reloadClient(cxt, client)
        val clerk = TestUser.create(cxt, "clerk@$client.test", userClient = client)
        val seat = mapOf(GE.traitId to "seat", GE.data to emptyMap<String, Any?>())
        val form = clerk.postItem(GEP.formDocCreate, create(listOf(seat, seat), emptyList()))
        form[GDF.entries].toJsonListOfMaps().map { it[GE.data].toJsonMapOrEmpty() } shouldBe listOf(
            mapOf("seatNo" to "S-01", "label" to "Seat S-01"), mapOf("seatNo" to "S-02", "label" to "Seat S-02"),
        )
    }

    "a layout that requires a counter's field is met by a create, and not by a form already stored without it" {
        val client = "store1852f"
        // Its own configuration: the store's has a creation workflow already, and a client has one.
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(clientDef(client))
            counter("order", "ORD-", digits = 2)
            trait("OrderEntry", "order", setOf(GedraDataType.formDoc), "An order.") {
                property("orderNo", "The order's id.") { counter = "order" }
            }
            val requires = mapOf(SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "orderNo", SL.required to true))))
            val orderData = "${clientNamespace(client)}.${traitDataTypeName("OrderEntry")}"
            workflow("take", WfEntry.creation) {
                alterType(orderData, requires)
                task("take", "Take") {
                    trait("order")
                    save("create", "Create")
                }
            }
            workflow("check", WfEntry.coreDetails) {
                alterType(orderData, requires)
                task("check", "Check") {
                    trait("order")
                    save("save", "Save", WfSaveKind.edit)
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
        val clerk = TestUser.create(cxt, "clerk@$client.test", userClient = client)
        val order = mapOf(GE.traitId to "order", GE.data to emptyMap<String, Any?>())
        fun save(workflow: String, save: String, gedraId: String? = null) = buildMap {
            put(GDF.workflowId, workflow)
            put(GDF.taskId, workflow)
            put(GDF.saveId, save)
            put(GDF.entries, listOf(order))
            gedraId?.let { put(GDF.gedraId, it) }
        }
        val path = clientPath(GEP.workflowSave, client)
        val made = clerk.postData(path, save("take", "create"))[WSF.item].toJsonMapOrEmpty()
        made[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()["orderNo"] shouldBe "ORD-01"
        // A form made without the trait: an edit adding it must bring the id.
        val bare = clerk.postItem(GEP.formDocCreate, create(emptyList(), emptyList()))[GDF.gedraId].toOptStr()!!
        clerk.expectError(EXC.badInput, path, save("check", "save", bare))[EP.errorMessage].toString() shouldContain "orderNo"
    }

    "an edit adding a counter's field still sends an issued id with its token" {
        val clerk = createStore("store1849d")
        // A form holding only an order, its id given at the create.
        val gedraId = clerk.postItem(
            GEP.formDocCreate, create(listOf(mapOf(GE.traitId to "order", GE.data to emptyMap<String, Any?>())), emptyList()),
        )[GDF.gedraId].toOptStr()!!
        fun patch(data: Map<String, Any?>, tokens: List<String>) = mapOf(
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
        // The receipt added with its id left out: the server gives none on an edit, and the id is required.
        clerk.expectError(EXC.badInput, GEP.patch, patch(mapOf("note" to "later"), emptyList()))[EP.errorMessage]
            .toString() shouldContain "number"
        val (value, token) = clerk.issue("receipt")
        clerk.postItems(GEP.patch, patch(mapOf("number" to value), listOf(token)))
    }

    "a counter says when it issues, the load refuses a value it does not know, and pages are told" {
        val client = "store1852c"
        createStore(client)
        CounterService.get(cxt).forClient(client).getValue("ticket").issuesOnSave shouldBe true
        CounterService.get(cxt).forClient(client).getValue("receipt").issuesOnSave shouldBe false
        // The entry keeps it, and leaves out the default.
        val entries = gedraConfigToEntries(storeConfig(client))[CCT.counterDef].orEmpty().associateBy { it[CTR.counterId] }
        entries.getValue("ticket")[CTR.issue] shouldBe CTR.issueOnSave
        entries.getValue("receipt").containsKey(CTR.issue) shouldBe false

        val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
        admin.expectError(
            EXC.badInput, CFEP.bundleWrite,
            mapOf(
                CFEP.name to "extra", CFEP.namespaceField to clientNamespace(client),
                CFEP.slots to gedraConfigToEntries(
                    gedraConfig(cxt, "extra", clientNamespace(client), client) { counter("later", "L-", issue = "eventually") },
                ),
            ),
        ).toString() shouldContain "'eventually' is not a valid option"

        // The workflow view and the catalog say how each counter their types name gives its ids.
        val clerk = TestUser.create(cxt, "clerk2@$client.test", userClient = client)
        val expected = mapOf(
            "receipt" to mapOf(CTR.issue to CTR.issueOnOpen),
            "ticket" to mapOf(CTR.issue to CTR.issueOnSave),
        )
        clerk.getData(clientPath(GEP.workflowView, client), mapOf(GDF.workflowId to "sell"))[CTR.counters] shouldBe expected
        val catalog = clerk.getData("/schema/endpoint", mapOf(EI.method to "POST", EI.path to clientPath(GEP.formDocCreate, client)))
        catalog[CTR.counters].toJsonMapOrEmpty().keys shouldBe setOf("receipt", "order", "ticket")
        catalog[CTR.counters].toJsonMapOrEmpty()["ticket"] shouldBe mapOf(CTR.issue to CTR.issueOnSave)
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
        admin.expectError(EXC.badInput, CFEP.bundleWrite, write { counter("stamp", "S-", reset = CTR.resetDaily) })
            .toString() shouldContain "does not change with the day"
        // Padded past what a count is padded to.
        admin.expectError(EXC.badInput, CFEP.bundleWrite, write { counter("wide", "W-", digits = 9) })
        // A field naming a counter the client does not have.
        admin.expectError(
            EXC.badInput, CFEP.bundleWrite,
            write {
                trait("StampEntry", "stamp", setOf(GedraDataType.formDoc), "A stamp.") {
                    property("stampNo", "The stamp's id.") { counter = "stamp" }
                }
            },
        ).toString() shouldContain "which the client does not have"
        // And a sound one is taken, and issues at once.
        admin.postData(CFEP.bundleWrite, write { counter("stamp", $$"S${compactDay}-", reset = CTR.resetDaily) })
        GedraConfigReload.reloadClient(cxt, client)
        CounterService.get(cxt).forClient(client).keys shouldBe setOf("receipt", "order", "ticket", "stamp")
    }

    "a counter's entry round-trips through its stored slot" {
        val entries = gedraConfigToEntries(storeConfig("store1849g"))[CCT.counterDef].orEmpty()
        entries.map { it[CTR.counterId] } shouldBe listOf("receipt", "order", "ticket")
        entries.first() shouldBe mapOf(
            CTR.counterId to "receipt", CTR.prefix to $$"R${compactDay}-", CTR.digits to 4, CTR.reset to CTR.resetDaily,
            CTR.description to "Receipts.",
        )
    }
})
