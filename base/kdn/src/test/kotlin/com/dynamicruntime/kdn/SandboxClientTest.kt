package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.SRJ
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * A sandbox loads and reloads beside its parent (issue #928, the Shadow Sandbox #925): a parent whose definition
 * asks for one gets a live client `<parent>:sandbox` running the parent's **latest** configuration -- whatever tier
 * the parent consumes at -- with data of its own. One booted instance; a parent client per case.
 */
class SandboxClientTest : StringSpec({
    val cxt = TestInstances.default("sandboxClient")

    fun asClient(client: String): KdrCxt = cxt.mkSubContext("sandbox", client).also { it.userId = 9300L }
    fun svc(): GedraConfigService = GedraConfigService.get(cxt)
    fun traits(client: String): List<String> = SchemaService.get(cxt).gedraTraitsFor(client).map { it.traitId }
    fun classId(client: String) = GedraId.of(GedraConfigType.configDoc, client, "main")

    /** Writes [client]'s one config, defining it -- with a sandbox unless [sandbox] is false -- and the named traits. */
    fun write(client: String, vararg traits: String, sandbox: Boolean = true) {
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Client $client", usageType = ClientUsageType.production,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    domainPrefix = "${client}pfx", sandbox = sandbox,
                ),
            )
            for (t in traits) {
                trait("${t}Entry", t, setOf(GedraDataType.formDoc), "The $t trait.") {
                    property("text", "A value.", required = true)
                }
            }
        }
        svc().writeConfig(asClient(client), config)
    }

    fun reload(client: String) = GedraConfigReload.reloadClient(cxt, client)

    "a published-only parent runs its published revision while its sandbox runs the latest" {
        val parent = "sbxpub"
        val sandbox = sandboxOf(parent)
        write(parent, "pubA")
        svc().publish(asClient(parent), classId(parent))
        svc().setPublishedOnly(asClient(parent), parent, true)
        write(parent, "pubA", "pubB")        // v2, unpublished
        val result = reload(parent)

        traits(parent) shouldContain "pubA"
        traits(parent) shouldNotContain "pubB"
        traits(sandbox) shouldContain "pubB"
        ClientService.get(cxt).isPresent(sandbox) shouldBe true
        // The reload says it rebuilt the sandbox too, so its caller announces both.
        result.sandbox.shouldNotBeNull().client shouldBe sandbox
        result.all.map { it.client } shouldContainExactly listOf(parent, sandbox)

        // Publishing v2 brings the parent up to what the sandbox already showed.
        svc().publish(asClient(parent), classId(parent))
        reload(parent)
        traits(parent) shouldContain "pubB"
        traits(sandbox) shouldContain "pubB"
    }

    "a parent on the latest tier and its sandbox run the same revision" {
        val parent = "sbxlatest"
        write(parent, "latA")
        reload(parent)
        write(parent, "latA", "latB")
        reload(parent)
        traits(parent) shouldContain "latB"
        traits(sandboxOf(parent)) shouldContain "latB"
    }

    // The sandbox's types are the parent's own, under the parent's namespace: it previews what the parent will run,
    // so nothing is renamed, and a colon never reaches a type name.
    "the sandbox shows the parent's types under the parent's namespace" {
        val parent = "sbxns"
        write(parent, "nsA")
        reload(parent)
        val store = SchemaService.get(cxt).storeFor(sandboxOf(parent))
        store.types.keys shouldContain "${clientNamespace(parent)}.nsAEntry"
        SchemaService.get(cxt).gedraTraitsFor(sandboxOf(parent)).single { it.traitId == "nsA" }.typeName shouldBe
            "${clientNamespace(parent)}.nsAEntry"
    }

    "the sandbox's definition is derived from the parent's, never authored" {
        val parent = "sbxdef"
        write(parent, "defA")
        reload(parent)
        val def = ClientService.get(cxt).known(sandboxOf(parent)).shouldNotBeNull()
        def.name shouldBe "Client $parent (sandbox)"
        def.usageType shouldBe ClientUsageType.dev
        def.audience shouldBe ClientAudience.internal
        def.enabledEnvironments shouldBe setOf(ENV.unit, ENV.local)
        // The parent's routing is the parent's alone, and a sandbox has no sandbox.
        def.domainPrefix.shouldBeNull()
        def.sandbox shouldBe false
    }

    // Everything a client owns carries its id, so data written in the sandbox is the sandbox's -- the same boundary
    // that separates any two clients. Written through the sandbox's own endpoint copy, whose path names it
    // (`/gedra/<parent>:sandbox/...`), which is the colon-bearing path #927 audited, exercised end to end.
    "a form doc written in the sandbox is the sandbox's, and neither client sees the other's" {
        val parent = "sbxdata"
        val sandbox = sandboxOf(parent)
        write(parent, "dataA")
        reload(parent)
        fun entry(text: String) = mapOf(GDF.entries to listOf(mapOf(GE.traitId to "dataA", GE.data to mapOf("text" to text))))

        val inSandbox = TestUser.create(cxt, "u@sandbox.$parent.test", userClient = sandbox)
        val inParent = TestUser.create(cxt, "u@$parent.test", userClient = parent)
        val sandboxDoc = inSandbox.postItem(clientPath(GEP.formDocCreate, sandbox), entry("trial run"))
        val parentDoc = inParent.postItem(GEP.formDocCreate, entry("the real thing"))

        sandboxDoc[GDF.gedraId].toOptStr().shouldNotBeNull() shouldStartWith "gd.fd.$sandbox."
        parentDoc[GDF.gedraId].toOptStr().shouldNotBeNull() shouldStartWith "gd.fd.$parent."
        fun idsSeenBy(user: TestUser) = user.getItems(GEP.formDocs, mapOf(EP.limit to 200)).map { it[GDF.gedraId] }
        idsSeenBy(inSandbox) shouldContainExactly listOf(sandboxDoc[GDF.gedraId])
        idsSeenBy(inParent) shouldContainExactly listOf(parentDoc[GDF.gedraId])
    }

    "clearing the flag withdraws the sandbox at the next reload" {
        val parent = "sbxclear"
        val sandbox = sandboxOf(parent)
        write(parent, "clrA")
        reload(parent)
        ClientService.get(cxt).isPresent(sandbox) shouldBe true

        write(parent, "clrA", sandbox = false)
        val result = reload(parent)
        // The reload ran for the sandbox it had, which is how it came to withdraw it.
        result.sandbox.shouldNotBeNull().loaded shouldBe 0
        ClientService.get(cxt).known(sandbox).shouldBeNull()
        GedraConfigLoadService.get(cxt).loadedFor(sandbox) shouldBe emptyList()
        SchemaService.get(cxt).storeFor(sandbox) shouldBe SchemaService.get(cxt).schemaStore
        // A client that never had a sandbox reloads alone.
        reload(parent).sandbox.shouldBeNull()
    }

    // A sandbox reloaded on its own -- as a peer catching up on the sandbox's marker does -- is rebuilt from its
    // parent as the parent stands.
    "a sandbox reloaded alone is rebuilt from its parent" {
        val parent = "sbxalone"
        val sandbox = sandboxOf(parent)
        write(parent, "alA")
        reload(parent)
        write(parent, "alA", "alB")
        val result = reload(sandbox)
        result.sandbox.shouldBeNull()
        traits(sandbox) shouldContain "alB"
    }

    // The nightly recompute takes its clients from the ones this node carries, and a sandbox is one of them.
    "the state recompute job runs for a sandbox as for any client" {
        val parent = "sbxjob"
        write(parent, "jobA")
        reload(parent)
        val launched = JobService.get(cxt).launch(
            cxt, SRJ.jobType, "sandboxRun", mode = JobRunMode.pooledOnCaller, clients = listOf(sandboxOf(parent)),
        )
        launched.outcome shouldBe JobLaunchOutcome.completed
    }
})
