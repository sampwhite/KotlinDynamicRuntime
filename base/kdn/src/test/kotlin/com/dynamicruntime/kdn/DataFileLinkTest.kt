package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GDT
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraDataTopic
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The links between forms and the data files their fields name (issue #1838): written when a form is saved, checked
 * against the field and the caller, refusing a file's removal while a live form names it, opening a file to whoever
 * may read such a form, and dropped on import.
 */
class DataFileLinkTest : StringSpec({
    val cxt = TestInstances.default("links1838")
    val client = "links1838"

    /** Defines [clientId] with its traits: `visit`'s photo always a file, `extra`'s ref a file only when [refIsFile]. */
    fun defineClient(clientId: String, refIsFile: Boolean = false) {
        val setup = cxt.mkSubContext("setup", clientId).also { it.userId = 18380L }
        GedraConfigService.get(cxt).writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(clientId), clientId) {
                defineClient(
                    ClientDef(
                        clientId = clientId, name = clientId, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("VisitEntry", "visit", setOf(GedraDataType.formDoc), "A site visit.") {
                    property("note", "What was seen.")
                    property("photo", "A photograph of the site.") { dataFile(FKIND.png, maxSize = 1000) }
                    property("clip", "A video of the site.") { dataFile(FKIND.mp4) }
                    property("rooms", "A photograph of each room, by its name.") { mapOfValues { dataFile(FKIND.png) } }
                }
                trait("BadgeEntry", "badge", setOf(GedraDataType.formDoc), "A badge, which must have its photograph.") {
                    property("photo", "The badge's photograph.", required = true) { dataFile(FKIND.png) }
                }
                trait("ExtraEntry", "extra", setOf(GedraDataType.formDoc), "A reference.") {
                    if (refIsFile) property("ref", "A file.") { dataFile(FKIND.png) } else property("ref", "Some text.")
                }
            },
        )
        GedraConfigReload.reloadClient(cxt, clientId)
    }
    defineClient(client)

    val owner = TestUser.create(cxt, "pat@links1838.test", userClient = client)
    val sam = TestUser.create(cxt, "sam@links1838.test", userClient = client)
    val lee = TestUser.create(cxt, "lee@links1838.test", userClient = client)
    val admin = TestUser.create(cxt, "chief@links1838.test", level = ROLE.admin, userClient = client)

    fun png(size: Int, seed: Int = 0): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(size - 8) { (it + seed).toByte() }

    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200)

    fun envelope(handler: com.dynamicruntime.common.http.request.RequestHandler): Map<String, Any?> =
        handler.rptResponseData?.jsonMap().shouldNotBeNull()

    /** Uploads [bytes] for [user] in one request, answering the file's id; only begun, still uploading, unless [finish]. */
    fun upload(user: TestUser, name: String, mime: String, bytes: ByteArray, finish: Boolean = true): String {
        if (!finish) {
            val begun = user.postItem(DFILE.beginPath, mapOf(DFILE.name to name, DFILE.mimeType to mime, DFILE.declaredSize to bytes.size))
            return begun[DFILE.fileId].toOptStr().shouldNotBeNull()
        }
        val sent = envelope(user.client.sendRawBodyRequest(DFILE.uploadPath, mapOf(DFILE.name to name, DFILE.mimeType to mime), bytes))
        sent[EP.status] shouldBe null
        return sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
    }

    fun visit(photo: String? = null, note: String = "a visit"): Map<String, Any?> =
        mapOf(GE.traitId to "visit", GE.data to listOfNotNull("note" to note, photo?.let { "photo" to it }).toMap())

    fun create(user: TestUser, photo: String?): Map<String, Any?> = envelope(
        user.client.sendEditRequest(clientPath(GEP.formDocCreate, client), null, mapOf(GDF.entries to listOf(visit(photo))), HttpMethod.POST),
    )

    fun formIdOf(created: Map<String, Any?>): String =
        created[EP.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()

    /** Replaces [formId]'s visit, as [user], with one naming [photo] (or none). */
    fun setPhoto(user: TestUser, formId: String, photo: String?): Map<String, Any?> = envelope(
        user.client.sendEditRequest(
            clientPath(GEP.patch, client), null,
            mapOf(
                GPF.targets to mapOf(
                    GedraDataType.formDoc.name to listOf(
                        mapOf(GDF.gedraId to formId, GPF.edits to listOf(visit(photo) + (GED.action to GedraEditAction.addOrReplace.name))),
                    ),
                ),
            ),
            HttpMethod.POST,
        ),
    )

    fun read(user: TestUser, formId: String): Map<String, Any?> =
        user.getItem(clientPath(GEP.formDoc, client), mapOf(GDF.gedraId to formId))

    fun info(user: TestUser, fileId: String): Map<String, Any?> = envelope(user.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to fileId)))

    fun delete(user: TestUser, fileId: String): Map<String, Any?> =
        envelope(user.client.sendDeleteRequest(DFILE.deletePath, mapOf(DFILE.fileId to fileId)))

    "a form's field names a file, its read carries the file's record, and the file says which forms use it" {
        val photo = upload(owner, "site.png", "image/png", png(300))
        val formId = formIdOf(create(owner, photo))

        val files = read(owner, formId)[GDF.files].toJsonMapOrEmpty()
        val record = files[photo].toJsonMapOrEmpty()
        record[DFILE.kind] shouldBe FKIND.png
        record[DFILE.name] shouldBe "site.png"
        record[DFILE.size] shouldBe 300L

        val used = owner.getItem(DFILE.usedByPath, mapOf(DFILE.fileId to photo))
        used[DFILE.usedBy].toJsonListOfMaps().map { it[DFILE.formId] to it[DFILE.fieldPath] } shouldBe listOf(formId to "photo")
        used[DFILE.hiddenForms] shouldBe 0L
    }

    "a field refuses a file of another kind, one too large, another user's, one still uploading, and one removed" {
        val wrongKind = upload(owner, "site.jpg", "image/jpeg", jpeg)
        create(owner, wrongKind)[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "takes only PNG;"

        val tooLarge = upload(owner, "big.png", "image/png", png(1500))
        create(owner, tooLarge)[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "at most 1000 bytes"

        val samsPhoto = upload(sam, "sams.png", "image/png", png(300, seed = 1))
        create(owner, samsPhoto)[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "no file you may use"

        val unfinished = upload(owner, "half.png", "image/png", png(300, seed = 2), finish = false)
        create(owner, unfinished)[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "still being uploaded"

        val removed = upload(owner, "gone.png", "image/png", png(300, seed = 3))
        delete(owner, removed)[EP.status] shouldBe null
        val refused = create(owner, removed)
        refused[EP.status] shouldBe EXC.badInput.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "no file you may use"

        // Not an id at all is refused the same way.
        create(owner, "not-a-file")[EP.status] shouldBe EXC.badInput.toLong()
    }

    "a file a live form names is not removed until the form stops naming it" {
        val photo = upload(owner, "kept.png", "image/png", png(300, seed = 4))
        val formId = formIdOf(create(owner, photo))

        val refused = delete(owner, photo)
        refused[EP.status] shouldBe EXC.conflict.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain formId

        setPhoto(owner, formId, null)[EP.status] shouldBe null
        owner.getItem(DFILE.usedByPath, mapOf(DFILE.fileId to photo))[DFILE.usedBy].toJsonListOfMaps().size shouldBe 0
        delete(owner, photo)[EP.status] shouldBe null
    }

    "a removed form holds no file in use, though it keeps its link" {
        val photo = upload(owner, "old.png", "image/png", png(300, seed = 5))
        val formId = formIdOf(create(owner, photo))
        owner.deleteData(clientPath(GEP.formDoc, client), mapOf(GDF.gedraId to formId))
        owner.getItem(DFILE.usedByPath, mapOf(DFILE.fileId to photo))[DFILE.usedBy].toJsonListOfMaps().size shouldBe 0
        delete(owner, photo)[EP.status] shouldBe null
    }

    "a file is read through a form that names it, by whoever may read the form, and by nobody else" {
        val photo = upload(owner, "shared.png", "image/png", png(300, seed = 6))
        info(sam, photo)[EP.status] shouldBe EXC.notFound.toLong()

        // Sam's form; an administrator, whose scope holds Pat's file, puts it there.
        val formId = formIdOf(create(sam, null))
        setPhoto(sam, formId, photo)[EP.status] shouldBe EXC.badInput.toLong()
        setPhoto(admin, formId, photo)[EP.status] shouldBe null

        info(sam, photo)[EP.item].toJsonMapOrEmpty()[DFILE.name] shouldBe "shared.png"
        sam.client.sendGetRequest(DFILE.contentPath, mapOf(DFILE.fileId to photo)).rptStatusCode shouldBe 200
        info(lee, photo)[EP.status] shouldBe EXC.notFound.toLong()
        // Sam may read the file but not remove it.
        delete(sam, photo)[EP.status] shouldBe EXC.notFound.toLong()
        // Pat's own view counts Sam's form as one Pat cannot read.
        owner.getItem(DFILE.usedByPath, mapOf(DFILE.fileId to photo))[DFILE.hiddenForms] shouldBe 1L

        // Sam's own save, keeping the file the form already names, needs no right of Sam's to it.
        setPhoto(sam, formId, photo)[EP.status] shouldBe null
        setPhoto(sam, formId, null)[EP.status] shouldBe null
        info(sam, photo)[EP.status] shouldBe EXC.notFound.toLong()
    }

    "an import drops the file fields and keeps the rest of the entry, leaving out one that needs its file" {
        val photo = upload(owner, "export.png", "image/png", png(300, seed = 7))
        val badge = mapOf(GE.traitId to "badge", GE.data to mapOf("photo" to photo))
        val answer = owner.postData(
            clientPath(GEP.formDocImport, client),
            mapOf(GIF.data to mapOf(GDF.entries to listOf(visit(photo, note = "carried"), badge))),
        )
        answer[GIF.discarded].toJsonListOfMaps().map { it[GIF.category] to it[GE.traitId] }.toSet() shouldBe
            setOf(GIF.dataFile to "visit", GIF.dataFile to "badge")
        val imported = answer[GIF.imported].toJsonListOfMaps().single()
        imported[GIF.excludedTraits] shouldBe listOf("badge")
        val formId = imported[GDF.gedraId].toOptStr().shouldNotBeNull()
        read(owner, formId)[GDF.entries].toJsonListOfMaps().single()[GE.data] shouldBe mapOf("note" to "carried")
    }

    "a save naming a file that does not exist makes no lock for it" {
        val missing = "gd.df.$client.${cxt.mkUniqueId()}"
        create(owner, missing)[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "no file you may use"
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = cxt.getGlobalSchema().tables.getValue(GDT.gedraDataTran)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qTestGedraDataTran", table.columns, "select * from t:${GDT.gedraDataTran} where c:${GD.gedraId} = :${GD.gedraId}",
        )
        var found: Map<String, Any?>? = mapOf()
        sqlCxt.sqlDb.withSession(cxt) { found = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(GD.gedraId to missing)) }
        found shouldBe null
    }

    "a file field's path is held to a length" {
        val photo = upload(owner, "room.png", "image/png", png(300, seed = 9))
        val room = mapOf(GE.traitId to "visit", GE.data to mapOf("rooms" to mapOf("x".repeat(DFILE.maxFieldPathLength) to photo)))
        val refused = envelope(
            owner.client.sendEditRequest(clientPath(GEP.formDocCreate, client), null, mapOf(GDF.entries to listOf(room)), HttpMethod.POST),
        )
        refused[EP.status] shouldBe EXC.badInput.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "at most ${DFILE.maxFieldPathLength}"
    }

    "a value its field did not take as a file when written is left unlinked, and refuses no later edit" {
        val later = "links1838b"
        defineClient(later)
        val pat = TestUser.create(cxt, "pat@links1838b.test", userClient = later)
        val photo = upload(pat, "later.png", "image/png", png(300, seed = 10))
        val created = envelope(
            pat.client.sendEditRequest(
                clientPath(GEP.formDocCreate, later), null,
                mapOf(GDF.entries to listOf(mapOf(GE.traitId to "extra", GE.data to mapOf("ref" to photo)), visit())),
                HttpMethod.POST,
            ),
        )
        val formId = formIdOf(created)
        // The field becomes a file field: the value stored before it was never checked, so it is never linked.
        defineClient(later, refIsFile = true)
        val patched = envelope(
            pat.client.sendEditRequest(
                clientPath(GEP.patch, later), null,
                mapOf(
                    GPF.targets to mapOf(
                        GedraDataType.formDoc.name to listOf(
                            mapOf(GDF.gedraId to formId, GPF.edits to listOf(visit(note = "edited") + (GED.action to GedraEditAction.addOrReplace.name))),
                        ),
                    ),
                ),
                HttpMethod.POST,
            ),
        )
        patched[EP.status] shouldBe null
        pat.getItem(clientPath(GEP.formDoc, later), mapOf(GDF.gedraId to formId))[GDF.files] shouldBe null
        pat.getItem(DFILE.usedByPath, mapOf(DFILE.fileId to photo))[DFILE.usedBy].toJsonListOfMaps().size shouldBe 0
    }

    "saves racing a removal never deadlock, and a removed file is named by no form" {
        val photo = upload(owner, "raced.png", "image/png", png(300, seed = 8))
        val writers = (1..4).map { TestUser.create(cxt, "writer$it@links1838.test", level = ROLE.admin, userClient = client) }
        val forms = writers.map { formIdOf(create(it, null)) }
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val saves = writers.zip(forms).map { (writer, formId) -> pool.submit<Map<String, Any?>> { setPhoto(writer, formId, photo) } }
        val removal = pool.submit<Map<String, Any?>> { delete(owner, photo) }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS) shouldBe true

        val removed = removal.get()[EP.status] == null
        val saved = saves.count { it.get()[EP.status] == null }
        val naming = writers.zip(forms).count { (writer, it) -> read(writer, it)[GDF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()["photo"] == photo }
        naming shouldBe saved
        // Removed, no save after it took; refused, at least one save came first.
        if (removed) saved shouldBe 0 else (saved > 0) shouldBe true
        if (!removed) removal.get()[EP.status].toOptLong() shouldBe EXC.conflict.toLong()
    }
})
