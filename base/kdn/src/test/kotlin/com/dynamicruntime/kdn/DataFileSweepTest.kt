package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSW
import com.dynamicruntime.common.datafile.DataFileService
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.filestore.FNAME
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.job.JobCounts
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.test.ClockOp
import com.dynamicruntime.common.test.TCLK
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The nightly sweep of data files (issue #1839): uploads past their time, files no form links, bytes nothing names, and
 * staged uploads nothing holds -- each asked again before it goes, and a dry run removing nothing. Its own instance,
 * since it travels the instance clock, which is instance-wide; each case a client of its own.
 */
class DataFileSweepTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("filesSweep", "dataFileSweepTest")
    val clock = TestHttpClient(cxt.instanceConfig).alwaysWithTestAccess()
    val hour = 3_600_000L

    /** Defines [client] with one trait whose photo is a file. */
    fun defineClient(client: String) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 18390L }
        GedraConfigService.get(cxt).writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("VisitEntry", "visit", setOf(GedraDataType.formDoc), "A site visit.") {
                    property("photo", "A photograph of the site.") { dataFile(FKIND.png) }
                }
            },
        )
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun png(seed: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(300) { (it * seed).toByte() }

    fun envelope(handler: com.dynamicruntime.common.http.request.RequestHandler): Map<String, Any?> =
        handler.rptResponseData?.jsonMap().shouldNotBeNull()

    fun upload(user: TestUser, bytes: ByteArray): String {
        val args = mapOf(DFILE.name to "a.png", DFILE.mimeType to "image/png")
        val sent = envelope(user.client.sendRawBodyRequest(DFILE.uploadPath, args, bytes))
        sent[EP.status] shouldBe null
        return sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
    }

    fun begin(user: TestUser): String =
        user.postItem(DFILE.beginPath, mapOf(DFILE.mimeType to "image/png", DFILE.declaredSize to 5000))[DFILE.fileId].toOptStr()
            .shouldNotBeNull()

    fun visit(photo: String?) = mapOf(GE.traitId to "visit", GE.data to listOfNotNull(photo?.let { "photo" to it }).toMap())

    fun createForm(user: TestUser, client: String, photo: String): String {
        val created = envelope(
            user.client.sendEditRequest(
                clientPath(GEP.formDocCreate, client), null, mapOf(GDF.entries to listOf(visit(photo))), HttpMethod.POST,
            ),
        )
        created[EP.status] shouldBe null
        return created[EP.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()
    }

    fun setPhoto(user: TestUser, client: String, formId: String, photo: String?) {
        val edit = visit(photo) + (GED.action to GedraEditAction.addOrReplace.name)
        val patched = envelope(
            user.client.sendEditRequest(
                clientPath(GEP.patch, client), null,
                mapOf(GPF.targets to mapOf(GedraDataType.formDoc.name to listOf(mapOf(GDF.gedraId to formId, GPF.edits to listOf(edit))))),
                HttpMethod.POST,
            ),
        )
        patched[EP.status] shouldBe null
    }

    fun exists(user: TestUser, fileId: String): Boolean =
        envelope(user.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to fileId)))[EP.status] == null

    fun sweep(client: String, dryRun: Boolean = false): JobCounts =
        JobService.get(cxt).launch(
            cxt, DFSW.jobType, "sweep-${cxt.mkUniqueId()}", JobLaunchKind.endpoint, JobRunMode.sync,
            clients = listOf(client), dryRun = dryRun,
        ).counts.shouldNotBeNull()

    fun advanceHours(hours: Long) {
        clock.sendJsonPostRequest(TCLK.path, mapOf(TCLK.op to ClockOp.advance.name, TCLK.deltaMs to hours * hour))
    }

    /** The user of [client] named for it, signed in: again after a jump of the clock past a session's life ends theirs. */
    fun signIn(client: String): TestUser = TestUser.create(cxt, "pat@$client.test", userClient = client)

    fun resetClock() {
        clock.sendJsonPostRequest(TCLK.path, mapOf(TCLK.op to ClockOp.reset.name))
    }

    val store = FileStoreService.get(cxt).store

    "an upload past its time goes, record and staged parts; one still in its time stays" {
        val client = "sweep1839u"
        defineClient(client)
        var pat = signIn(client)
        val stale = begin(pat)
        try {
            advanceHours(DFILE.uploadHours + 1L)
            pat = signIn(client)
            val fresh = begin(pat)
            sweep(client).completed shouldBe 1L
            exists(pat, stale) shouldBe false
            exists(pat, fresh) shouldBe true
            store.stagedUploads(cxt, DFILE.bucket).filter { it.key.endsWith(stale.substringAfterLast('.')) }.shouldBeEmpty()
        } finally {
            resetClock()
        }
    }

    "a file no live form links goes after the grace, then its bytes; a linked one stays, a deleted form's does not" {
        val client = "sweep1839o"
        defineClient(client)
        var pat = signIn(client)
        val kept = upload(pat, png(3))
        val replaced = upload(pat, png(5))
        val never = upload(pat, png(7))
        val deletedForms = upload(pat, png(9))
        val form = createForm(pat, client, replaced)
        setPhoto(pat, client, form, kept)
        // A form deleted: nothing restores one, so its file is no longer used.
        val gone = createForm(pat, client, deletedForms)
        envelope(pat.client.sendDeleteRequest(clientPath(GEP.formDoc, client), mapOf(GDF.gedraId to gone)))[EP.status] shouldBe null
        val folder = "${FNAME.clientKeyPrefix(client)}files/"
        try {
            // Within the grace, nothing goes.
            sweep(client).completed shouldBe 0L
            advanceHours(DSW_GRACE + 1L)
            pat = signIn(client)
            sweep(client).completed shouldBe 3L
            exists(pat, replaced) shouldBe false
            exists(pat, never) shouldBe false
            exists(pat, deletedForms) shouldBe false
            exists(pat, kept) shouldBe true
            // The removed files' bytes go the next night, nothing naming them.
            sweep(client).completed shouldBe 3L
            val stored = store.objects(cxt, DFILE.bucket, folder).map { it.name }
            for (seed in listOf(5, 7, 9)) stored shouldNotContain "$folder${png(seed).sha256Hex()}"
            stored shouldContain "$folder${png(3).sha256Hex()}"
        } finally {
            resetClock()
        }
    }

    "a file's grace runs from when a form last stopped naming it, not from its upload" {
        val client = "sweep1839g"
        defineClient(client)
        var pat = signIn(client)
        val old = upload(pat, png(19))
        val form = createForm(pat, client, old)
        try {
            // A month on, the photo is replaced -- by mistake, say.
            advanceHours(30 * 24L)
            pat = signIn(client)
            setPhoto(pat, client, form, upload(pat, png(21)))
            sweep(client).completed shouldBe 0L
            exists(pat, old) shouldBe true
            // Its whole grace later, with no undo, it goes.
            advanceHours(DSW_GRACE + 1L)
            pat = signIn(client)
            sweep(client).completed shouldBe 1L
            exists(pat, old) shouldBe false
        } finally {
            resetClock()
        }
    }

    "a sandbox's bytes, kept in its parent's folder, are named by its records and stay" {
        val parent = "sweep1839b"
        val setup = cxt.mkSubContext("setup", parent).also { it.userId = 18391L }
        GedraConfigService.get(cxt).writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(parent), parent) {
                defineClient(
                    ClientDef(
                        clientId = parent, name = parent, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                    ),
                )
                trait("VisitEntry", "visit", setOf(GedraDataType.formDoc), "A site visit.") {
                    property("photo", "A photograph of the site.") { dataFile(FKIND.png) }
                }
            },
        )
        GedraConfigReload.reloadClient(cxt, parent)
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        chief.postData(AEP.openSandbox, emptyMap())
        val inSandbox = upload(chief, png(23))
        val sandbox = inSandbox.split('.')[2]
        createForm(chief, sandbox, inSandbox)
        val bytes = "${FNAME.clientKeyPrefix(parent)}files/${png(23).sha256Hex()}"
        try {
            advanceHours(DSW_GRACE + 1L)
            // The parent's sweep walks its folder, where the sandbox's bytes are.
            sweep(parent)
            store.objects(cxt, DFILE.bucket, bytes).map { it.name } shouldContain bytes
        } finally {
            resetClock()
        }
    }

    "a file put together under staging/ for an upload no longer under way goes after an upload's hours" {
        val client = "sweep1839l"
        defineClient(client)
        val key = "${FNAME.clientKeyPrefix(client)}staging/leftover"
        FileStoreService.get(cxt).forOwner(cxt, client).put(cxt, DFILE.bucket, key, ContentData(png(25), "image/png"))
        try {
            sweep(client).completed shouldBe 0L
            advanceHours(DFILE.uploadHours + 1L)
            sweep(client).completed shouldBe 1L
            store.objects(cxt, DFILE.bucket, key).shouldBeEmpty()
        } finally {
            resetClock()
        }
    }

    "a file linked after it was listed is asked again, and kept" {
        val client = "sweep1839r"
        defineClient(client)
        var pat = signIn(client)
        val file = upload(pat, png(11))
        try {
            advanceHours(DSW_GRACE + 1L)
            pat = signIn(client)
            val bound = cxt.mkSubContext("sweep", client)
            val cutoff = cxt.instanceNow() - kotlin.time.Duration.parse("${DSW_GRACE}h")
            DataFileService.unlinkedFiles(bound, client, cutoff) shouldBe listOf(file)
            // Between the listing and the removal, a form links it.
            createForm(pat, client, file)
            DataFileService.sweepUnlinked(bound, file, cutoff, dryRun = false) shouldBe false
            exists(pat, file) shouldBe true
        } finally {
            resetClock()
        }
    }

    "bytes a new upload of the same file shares are kept, though the record that first named them is gone" {
        val client = "sweep1839s"
        defineClient(client)
        var pat = signIn(client)
        val first = upload(pat, png(13))
        try {
            advanceHours(DSW_GRACE + 1L)
            pat = signIn(client)
            // The same bytes again, the second record named by the hash the first one was.
            val second = upload(pat, png(13))
            createForm(pat, client, second)
            sweep(client)
            exists(pat, first) shouldBe false
            sweep(client)
            store.objects(cxt, DFILE.bucket, "${FNAME.clientKeyPrefix(client)}files/").map { it.name } shouldContain
                "${FNAME.clientKeyPrefix(client)}files/${png(13).sha256Hex()}"
            exists(pat, second) shouldBe true
        } finally {
            resetClock()
        }
    }

    "a staged upload no upload holds is aborted once it is past an upload's hours" {
        val client = "sweep1839t"
        defineClient(client)
        val key = "${FNAME.clientKeyPrefix(client)}staging/stray"
        val token = FileStoreService.get(cxt).forOwner(cxt, client).beginStaged(cxt, DFILE.bucket, key, "image/png")
        try {
            sweep(client).completed shouldBe 0L
            advanceHours(DFILE.uploadHours + 1L)
            sweep(client).completed shouldBe 1L
            store.stagedUploads(cxt, DFILE.bucket).firstOrNull { it.token == token }.shouldBeNull()
        } finally {
            resetClock()
        }
    }

    "a dry run counts what it would remove and removes nothing" {
        val client = "sweep1839d"
        defineClient(client)
        var pat = signIn(client)
        val stale = begin(pat)
        val orphan = upload(pat, png(17))
        try {
            advanceHours(DSW_GRACE + 1L)
            pat = signIn(client)
            sweep(client, dryRun = true).completed shouldBe 2L
            exists(pat, stale) shouldBe true
            exists(pat, orphan) shouldBe true
            sweep(client).completed shouldBe 2L
            exists(pat, orphan) shouldBe false
            envelope(pat.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to stale)))[EP.status] shouldBe EXC.notFound.toLong()
        } finally {
            resetClock()
        }
    }
})

/** The grace a test instance runs with: the default, since it sets no `KDR_FILE_ORPHAN_GRACE_HOURS`. */
private const val DSW_GRACE = DFSW.defaultGraceHours
