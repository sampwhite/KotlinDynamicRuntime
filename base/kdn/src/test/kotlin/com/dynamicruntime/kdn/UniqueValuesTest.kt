package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
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
import com.dynamicruntime.common.gedra.UCJ
import com.dynamicruntime.common.gedra.UniqueClaims
import com.dynamicruntime.common.gedra.VCL
import com.dynamicruntime.common.gedra.ValueClaim
import com.dynamicruntime.common.gedra.ValueClaims
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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

    /** Writes [client]'s configuration, its budget's title unique for each owner or not. */
    fun storeUnique(client: String, unique: Boolean) {
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
    }

    /** As [storeUnique], and makes it live. */
    fun setUnique(client: String, unique: Boolean) {
        storeUnique(client, unique)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun latestOf(client: String) =
        GedraConfigService.get(cxt).readLatest(asClient(client), GedraId.of(GedraConfigType.configDoc, client, "main"))!!

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
        mine.refused("q3-budget ") shouldContain
            "budget, data.title: Value 'q3-budget' is already used by another of your forms, as 'Q3 Budget'"
        // For the page that sent it: the field, the code, and the value, with no name for the field in its words (issue #1918).
        val extra = mine.expectError(EXC.badInput, GEP.formDocCreate, body("Q3 budget "))[EP.extraData].toJsonMapOrEmpty()
        extra[GDF.gedraId] shouldBe held
        extra[EP.failures].toJsonListOfMaps().single() shouldBe mapOf(
            EP.failurePath to "data.title", EP.failureCode to SchFailCode.valueTaken.name, GE.traitId to "budget",
            UNQ.value to "Q3 budget ",
            EP.failureMessage to "Value 'Q3 budget' is already used by another of your forms, as 'Q3 Budget'. Choose a different one.",
        )
        owner(client, "other").make("Q3 Budget")
        // Accents are letters, so these differ, however the accent is written; no value claims nothing.
        mine.make("Caf\u00e9")
        mine.make("Cafe")
        mine.refused("Cafe\u0301") shouldContain "already used"
        repeat(2) { mine.make(null) }
        repeat(2) { mine.make(" ") }
        // Text with no letter or digit has nothing to be compared by, so it is refused on every write.
        for (title in listOf("!!!", " - ", "\u2764\uFE0F")) mine.refused(title) shouldContain
            "budget, data.title: Value must have at least one letter or digit."
        mine.expectError(EXC.badInput, GEP.formDocCreate, body("!!!"))[EP.extraData].toJsonMapOrEmpty()[EP.failures]
            .toJsonListOfMaps().single().let { it[GE.traitId] to it[EP.failureCode] } shouldBe ("budget" to SchFailCode.noLetterOrDigit.name)
        mine.expectError(EXC.badInput, GEP.patch, patchBody(held, mapOf("title" to "?")))[EP.errorMessage].toString() shouldContain
            "at least one letter or digit"
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
        storeUnique(client, true)
        val finding = ConfigImpact.reportFor(cxt, client, listOf(latestOf(client)), anyTier = true).findings
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

    "a value with nothing to compare, stored before its field was unique, is reported, and kept until its form is edited" {
        val client = "unq1851l"
        setUnique(client, false)
        val mine = owner(client)
        val bare = mine.make("!!!")
        storeUnique(client, true)
        ConfigImpact.reportFor(cxt, client, listOf(latestOf(client)), anyTier = true).findings
            .single { it.kind == ImpactKind.dataInvalid }.rows shouldBe setOf(bare)
        GedraConfigReload.reloadClient(cxt, client)
        mine.expectError(EXC.badInput, GEP.patch, patchBody(bare, mapOf("note" to "n")))
        mine.rename(bare, "Named")
        mine.refused("named")
    }

    /** Deletes [client]'s claims, as though its forms were made by a node on which the field was not unique. */
    fun dropClaims(client: String) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "dTestUniqueClaims", cxt.getGlobalSchema().tables.getValue(VCL.gedraValueClaims).columns,
            "delete from t:${VCL.gedraValueClaims} where c:${PF.client} = :${PF.client}",
        )
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(PF.client to client)) }
    }

    "the nightly job claims what has no claim, a dry run only counting, and leaves the rest as it is" {
        val client = "unq1851g"
        setUnique(client, true)
        val mine = owner(client)
        val first = mine.make("Early")
        mine.make("Late")
        UniqueClaims.fill(cxt, client) shouldBe 0
        dropClaims(client)
        UniqueClaims.fill(cxt, client, dryRun = true) shouldBe 2
        // Counted, and not claimed.
        mine.make("early")
        dropClaims(client)
        fun run(name: String, dryRun: Boolean) = JobService.get(cxt)
            .launch(cxt, UCJ.jobType, name, mode = JobRunMode.pooledOnCaller, clients = listOf(client), dryRun = dryRun).outcome
        run("unq1851dry", true) shouldBe JobLaunchOutcome.completed
        UniqueClaims.fill(cxt, client, dryRun = true) shouldBe 3
        run("unq1851wet", false) shouldBe JobLaunchOutcome.completed
        UniqueClaims.fill(cxt, client, dryRun = true) shouldBe 0
        mine.refused("late")
        // The two that shared a value when the job ran: taken until both let it go.
        mine.rename(first, "Own")
        mine.refused("EARLY")
    }

    "a claim left over is dropped alone, and the form keeps the one of the value it holds" {
        val client = "unq1851m"
        setUnique(client, true)
        val mine = owner(client)
        val form = mine.make("Now")
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val stale = ValueClaim(
            client, UniqueClaims.kindOf("budget", "title"), mine.userId.toString(), UniqueClaims.keyOf("Before")!!, "Before", form,
            "budget", "title",
        )
        sqlCxt.sqlDb.withSession(cxt) { ValueClaims.claim(cxt, sqlCxt, listOf(stale)) { _, _ -> KdrException.mkInput("taken") } }
        mine.make("Before")
        mine.refused("now") shouldContain "already used"
    }

    "filling in one field's claims leaves another's as they were" {
        val client = "unq1851n"
        fun store(codeUnique: Boolean) {
            val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("BudgetEntry", "budget", setOf(GedraDataType.formDoc), "A budget.") {
                    property("title", "What it is called.") { unique = UNQ.owner }
                    property("code", "Its code.") { if (codeUnique) unique = UNQ.owner }
                }
            }
            GedraConfigService.get(cxt).writeConfig(asClient(client), config)
            GedraConfigReload.reloadClient(cxt, client)
        }
        store(false)
        val mine = owner(client)
        fun entry(title: String, code: String) =
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "budget", GE.data to mapOf("title" to title, "code" to code))))
        val form = mine.postItem(GEP.formDocCreate, entry("T1", "C1"))[GDF.gedraId].toOptStr()!!
        fun titleKey(): String {
            val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
            val stmt = SqlStmtUtil.prepareSql(
                sqlCxt, "qTestUniqueClaims", cxt.getGlobalSchema().tables.getValue(VCL.gedraValueClaims).columns,
                "select * from t:${VCL.gedraValueClaims} where c:${VCL.gedraId} = :${VCL.gedraId} and c:${VCL.fieldName} = 'title'",
            )
            @Suppress("CanBeVal") var key = ""
            sqlCxt.sqlDb.withSession(cxt) {
                key = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(VCL.gedraId to form)).single().let {
                    "${it[VCL.valueKey]} ${it[PF.createdAt]}"
                }
            }
            return key
        }
        val before = titleKey()
        store(true)
        titleKey() shouldBe before
        mine.expectError(EXC.badInput, GEP.formDocCreate, entry("T2", "c1"))
    }

    "a sandbox's claims are its own" {
        val client = "unq1851o"
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.production, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
            trait("BudgetEntry", "budget", setOf(GedraDataType.formDoc), "A budget.") {
                property("title", "What it is called.") { unique = UNQ.owner }
            }
        }
        GedraConfigService.get(cxt).run {
            writeConfig(asClient(client), config)
            publish(asClient(client), listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        }
        GedraConfigReload.reloadClient(cxt, client)
        owner(client).run { make("Same"); refused("same") }
        owner(sandboxOf(client)).run { make("Same"); refused("same") }
    }

    "a field hidden from the caller is not answered for" {
        val client = "unq1851p"
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("CaseEntry", "case", setOf(GedraDataType.formDoc), "A case.") {
                property("code", "Its code.") { unique = UNQ.owner; visibleWhen = CFACTS.hasAdminLevel }
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
        val mine = owner(client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        chief.postItem(
            GEP.formDocCreate,
            mapOf(
                GDF.entries to listOf(mapOf(GE.traitId to "case", GE.data to mapOf("code" to "K1"))),
                EI.user to mine.userId.toString(),
            ),
        )
        val ask = mapOf(UNQ.traitId to "case", UNQ.field to "code", UNQ.value to "k1")
        chief.getData(GEP.uniqueCheck, ask + (EI.user to mine.userId.toString()))[UNQ.taken] shouldBe true
        mine.getData(GEP.uniqueCheck, ask)[UNQ.taken] shouldBe false
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
        chief.expectError(EXC.badInput, GEP.formDocImport, import("Fresh", "HELD"))[EP.errorMessage].toString() shouldContain
            "trait 'budget' is invalid: data.title: Value 'HELD' is already used"
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
        // A type of its own that another trait's data holds, which the parser cannot see.
        chief.expectError(
            EXC.badInput, CFEP.bundleWrite,
            write {
                type("Place") { type = SCT.kObject; property("code", "Its code.") { unique = UNQ.owner } }
                trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "A site.") { property("place", "Where.") { ref("Place") } }
            },
        ).toString() shouldContain "not compared there"
    }
})
