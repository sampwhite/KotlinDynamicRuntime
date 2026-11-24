package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ConfigImpact
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.TraitNarrowing
import com.dynamicruntime.common.gedra.UNQ
import com.dynamicruntime.common.gedra.UniqueClaims
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.Executors

/**
 * Unique field values per owner (issue #1851): a value no two of one owner's forms may share, compared after
 * normalizing, on every write; turning it on over forms that share one already; and asking before saving. Over the
 * shared instance, a client per scenario.
 */
class UniqueValuesTest : StringSpec({
    val cxt = TestInstances.default("unique1851")
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("unique", client).also { it.userId = 18510L }

    /** Writes [client]'s configuration, its budget's title unique for each owner or not, and makes it live. */
    fun setUnique(client: String, unique: Boolean) {
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("BudgetEntry", "budget", setOf(GedraDataType.formDoc), "A budget.") {
                property("title", "What it is called.") { if (unique) this.unique = UNQ.owner }
                property("note", "A note.")
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun owner(client: String, name: String = "owner"): TestUser = TestUser.create(cxt, "$name@$client.test", userClient = client)
    fun budget(title: String?) = mapOf(GE.traitId to "budget", GE.data to buildMap { title?.let { put("title", it) } })
    fun body(title: String?) = mapOf(GDF.entries to listOf(budget(title)))
    fun TestUser.make(title: String?): String = postItem(GEP.formDocCreate, body(title))[GDF.gedraId].toOptStr()!!
    fun TestUser.refused(title: String) = expectError(EXC.badInput, GEP.formDocCreate, body(title))[EP.errorMessage].toString()
    fun patchBody(gedraId: String, data: Map<String, Any?>) = mapOf(
        GPF.targets to mapOf(
            GedraDataType.formDoc.name to listOf(
                mapOf(
                    GDF.gedraId to gedraId,
                    GPF.edits to listOf(mapOf(GED.action to GedraEditAction.addOrMerge.name, GE.traitId to "budget", GE.data to data)),
                ),
            ),
        ),
    )
    fun TestUser.rename(gedraId: String, title: String?) = postItems(GEP.patch, patchBody(gedraId, mapOf("title" to title)))
    fun TestUser.delete(gedraId: String) = deleteData(GEP.formDoc, mapOf(GDF.gedraId to gedraId))

    "one owner's forms cannot share a value, whatever its case or punctuation; another owner's can" {
        val client = "unq1851a"
        setUnique(client, true)
        val mine = owner(client)
        val held = mine.make("Q3 Budget")
        mine.refused("q3-budget ") shouldContain "Title 'q3-budget' is already used by another of your forms, as 'Q3 Budget'"
        mine.expectError(EXC.badInput, GEP.formDocCreate, body("Q3 budget"))[EP.extraData].toString() shouldContain held
        owner(client, "other").make("Q3 Budget")
        // Accents are letters, so these differ; a value with nothing to compare claims nothing, and neither does none.
        mine.make("Café")
        mine.make("Cafe")
        repeat(2) { mine.make("!!!") }
        repeat(2) { mine.make(null) }
    }

    "a form made for someone is checked against theirs" {
        val client = "unq1851b"
        setUnique(client, true)
        val theirs = owner(client)
        theirs.make("Plan")
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        chief.make("Plan")
        val forThem = body("plan") + (EI.user to theirs.userId.toString())
        chief.expectError(EXC.badInput, GEP.formDocCreate, forThem)[EP.errorMessage].toString() shouldContain "another of their forms"
    }

    "changing a value releases the old one, a respelling is no change, and a delete releases it" {
        val client = "unq1851c"
        setUnique(client, true)
        val mine = owner(client)
        val first = mine.make("Alpha")
        val second = mine.make("Beta")
        mine.expectError(EXC.badInput, GEP.patch, patchBody(second, mapOf("title" to "ALPHA")))[EP.errorMessage]
            .toString() shouldContain "already used"
        mine.rename(first, "ALPHA!")
        mine.refused("alpha") shouldContain "already used"
        // An edit of another field leaves it held.
        mine.postItems(GEP.patch, patchBody(first, mapOf("note" to "kept")))
        mine.refused("alpha")
        mine.rename(first, "Gamma")
        val third = mine.make("alpha")
        mine.delete(third)
        mine.make("Alpha")
        // Cleared, it is free too.
        mine.rename(second, "")
        mine.make("beta")
    }

    "writes racing for one value store it once, the others refused, never a server error" {
        val client = "unq1851d"
        setUnique(client, true)
        val mine = owner(client)
        val pool = Executors.newFixedThreadPool(4)
        val outcomes = try {
            fun post(): Int = (mine.client.sendJsonPostRequest(GEP.formDocCreate, body("Race"))[EP.status] as? Number)?.toInt() ?: 200
            (1..4).map { pool.submit<Int> { post() } }
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

    "turned on over forms that share a value, they are kept and reported, and the value is taken until all let it go" {
        val client = "unq1851e"
        setUnique(client, false)
        val mine = owner(client)
        val first = mine.make("Shared")
        val second = mine.make("shared")
        mine.make("Alone")
        // Another owner's is no collision.
        owner(client, "other").make("Alone")

        // The impact report of the change names the two.
        val latest = GedraConfigService.get(cxt).run {
            writeConfig(
                asClient(client),
                gedraConfig(cxt, "main", clientNamespace(client), client) {
                    defineClient(
                        ClientDef(
                            clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                            enabledEnvironments = setOf(ENV.unit, ENV.local),
                        ),
                    )
                    trait("BudgetEntry", "budget", setOf(GedraDataType.formDoc), "A budget.") {
                        property("title", "What it is called.") { unique = UNQ.owner }
                        property("note", "A note.")
                    }
                },
            )
            readLatest(asClient(client), GedraId.of(GedraConfigType.configDoc, client, "main"))!!
        }
        val finding = ConfigImpact.reportFor(cxt, client, listOf(latest), anyTier = true).findings
            .single { it.kind == ImpactKind.valuesShared }
        finding.traitId shouldBe "budget"
        finding.rows shouldBe setOf(first, second)

        GedraConfigReload.reloadClient(cxt, client)
        mine.refused("SHARED") shouldContain "already used"
        mine.refused("alone")
        // Either may be edited, and the value stays taken while the other holds it.
        mine.postItems(GEP.patch, patchBody(second, mapOf("note" to "still fine")))
        mine.rename(first, "Own")
        mine.refused("shared")
        mine.expectError(EXC.badInput, GEP.patch, patchBody(first, mapOf("title" to "Shared")))
        mine.rename(second, "Other")
        mine.make("Shared")
    }

    "turned off and on again, what changed meanwhile is what is held" {
        val client = "unq1851f"
        setUnique(client, true)
        val mine = owner(client)
        val form = mine.make("One")
        val gone = mine.make("Two")
        setUnique(client, false)
        mine.rename(form, "Three")
        mine.delete(gone)
        mine.make("Four")
        setUnique(client, true)
        // The claims left over name values no form holds now, and those of forms changed or made meanwhile are filled in.
        mine.make("One")
        mine.make("Two")
        mine.refused("three")
        mine.refused("four")
    }

    "the fill finds nothing to do twice, and a dry run writes nothing" {
        val client = "unq1851g"
        setUnique(client, false)
        val mine = owner(client)
        mine.make("Early")
        setUnique(client, true)
        UniqueClaims.fill(cxt, client) shouldBe 0
        UniqueClaims.fill(cxt, client, dryRun = true) shouldBe 0
    }

    "an import's value already held is an invalid entry: the import refused, or the entry forgiven and counted" {
        val client = "unq1851h"
        setUnique(client, true)
        val theirs = owner(client)
        theirs.make("Held")
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        fun import(vararg titles: String, forgive: Boolean = false) = mapOf(
            EI.user to theirs.userId.toString(), GIF.data to mapOf(EP.items to titles.map { body(it) }),
            GIF.forgiveInvalidEntries to forgive,
        )
        chief.expectError(EXC.badInput, GEP.formDocImport, import("Fresh", "HELD"))[EP.errorMessage].toString() shouldContain "already used"
        // Two documents of one import collide as well.
        chief.expectError(EXC.badInput, GEP.formDocImport, import("Twin", "twin"))
        val done = chief.postData(GEP.formDocImport, import("Fresh", "HELD", "fresh", forgive = true))
        done[GIF.imported].toJsonListOfMaps().size shouldBe 1
        theirs.refused("fresh")
    }

    "a form asks whether a value is taken, for a new form, its own, or one made for someone" {
        val client = "unq1851i"
        setUnique(client, true)
        val mine = owner(client)
        val held = mine.make("Q3 Budget")
        fun TestUser.ask(value: String, vararg more: Pair<String, Any?>, field: String = "title") =
            getData(GEP.uniqueCheck, mapOf(UNQ.traitId to "budget", UNQ.field to field, UNQ.value to value) + more)
        val answer = mine.ask("q3 budget")
        answer[UNQ.taken] shouldBe true
        answer[UNQ.heldAs] shouldBe "Q3 Budget"
        answer[GDF.gedraId] shouldBe held
        mine.ask("q3 budget", GDF.gedraId to held)[UNQ.taken] shouldBe false
        mine.ask("Q4")[UNQ.taken] shouldBe false
        mine.ask("Q3 Budget", field = "note")[UNQ.taken] shouldBe false
        owner(client, "other").ask("Q3 Budget")[UNQ.taken] shouldBe false
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        chief.ask("Q3 Budget", EI.user to mine.userId.toString())[UNQ.taken] shouldBe true
        // Another owner's form, or another owner, is not theirs to ask about.
        owner(client, "nosy").expectError(
            EXC.notFound, GEP.uniqueCheck,
            args = mapOf(UNQ.traitId to "budget", UNQ.field to "title", UNQ.value to "x", GDF.gedraId to held),
        )
    }

    "a new client starts with its form names unique, and may drop it" {
        val client = "unq1851j"
        val operator = TestUser.createFullAdmin(cxt, "operator@unique1851.test")
        operator.postData(
            ADEP.clientCreate,
            mapOf(
                CLD.clientId to client, CLD.name to client, CLD.usageType to ClientUsageType.dev.name,
                CLD.audience to ClientAudience.internal.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            ),
        )
        val mine = owner(client)
        fun named(name: String) = mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name))))
        mine.postItem(GEP.formDocCreate, named("My form"))
        mine.expectError(EXC.badInput, GEP.formDocCreate, named("my form"))[EP.errorMessage].toString() shouldContain "already used"
        // The traits editor reads it as a narrowing of its own, renames the trait beside it, and drops it.
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        fun narrow(narrowing: TraitNarrowing) = chief.postData(
            UADEP.clientTraitNarrow, mapOf(CIT.narrowing to narrowing.toInfo(), IMP.acknowledgeImpact to true),
        )
        narrow(TraitNarrowing(GT.name, name = "Title", unique = setOf(GT.nameField)))
        mine.expectError(EXC.badInput, GEP.formDocCreate, named("MY FORM"))
        narrow(TraitNarrowing(GT.name, name = "Title"))
        mine.postItem(GEP.formDocCreate, named("my form"))
    }

    "a write naming it where it is not checked is refused" {
        val client = "unq1851k"
        setUnique(client, true)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        fun write(more: GedraConfigBuilder.() -> Unit) = mapOf(
            CFEP.name to "extra", CFEP.namespaceField to clientNamespace(client),
            CFEP.slots to gedraConfigToEntries(gedraConfig(cxt, "extra", clientNamespace(client), client, build = more)),
        )
        chief.expectError(
            EXC.badInput, CFEP.bundleWrite,
            write {
                trait("RowEntry", "rows", setOf(GedraDataType.formDoc), "Keyed.") {
                    primaryKey("year")
                    property("year", "The year.", required = true)
                    property("title", "Its title.") { unique = UNQ.owner }
                }
            },
        ).toString() shouldContain "single entry"
    }
})
