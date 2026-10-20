package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.operator.FSB
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * The folder view of a bucket (issue #1297), derived from object names alone: folders from the next segment, a
 * file's record folded into its row, temporaries left out, a file missing either half flagged, and a long folder cut
 * rather than refused.
 */
class FileStoreBrowseTest : StringSpec({
    val t1 = Instant.parse("2026-10-20T10:00:00Z")
    val t2 = Instant.parse("2026-10-20T11:00:00Z")
    fun obj(name: String, at: Instant = t1) = StoredObject(name, at)
    fun meta(key: String) = FileMeta(mimeType = "image/png", size = 3, hash = "ab".repeat(32), uploadedAt = t1, fileName = key.substringAfterLast('/'))

    /** A file's two objects. */
    fun file(key: String, at: Instant = t1) = listOf(obj(key, at), obj(FileMeta.metaKey(key), at))

    val objects = file("clients/acme/web/favicon") + file("clients/acme/refs/" + "cd".repeat(32)) +
        file("clients/globex/web/brandMark", t2) + file("notes/readme.txt") + file("top.txt")

    fun listing(prefix: String, limit: Int = FSB.defaultLimit, objs: List<StoredObject> = objects, tmp: (String) -> String? = { null }) =
        FileStoreBrowse.folderListing("web-assets", prefix, objs.filter { it.name.startsWith(prefix) }, limit, tmp) { key -> meta(key) }

    "the top of a bucket is its folders and its files, folders from the next segment, each file one row" {
        val top = listing("")
        top.bucket shouldBe "web-assets"
        top.folders shouldContainExactly listOf("clients/", "notes/")
        top.files.map { it.key } shouldContainExactly listOf("top.txt")
        top.files.single().let {
            it.name shouldBe "top.txt"
            it.state shouldBe FSB.stateOk
            it.meta.shouldNotBeNull().fileName shouldBe "top.txt"
            it.modifiedAt shouldBe t1
        }
        top.more shouldBe false
        // The record's object is folded into the file's row, never a file of its own.
        top.files.none { it.key.endsWith(FileMeta.metaSuffix) } shouldBe true
    }

    "a folder lists what is directly under it, and a deep one its files with their names" {
        listing("clients/").let {
            it.folders shouldContainExactly listOf("acme/", "globex/")
            it.files.shouldBeEmpty()
        }
        listing("clients/acme/").folders shouldContainExactly listOf("refs/", "web/")
        listing("clients/acme/web/").let {
            it.folders.shouldBeEmpty()
            it.files.map { f -> f.name } shouldContainExactly listOf("favicon")
            it.files.single().key shouldBe "clients/acme/web/favicon"
        }
        listing("clients/globex/web/").files.single().modifiedAt shouldBe t2
        // An empty folder is empty, not an error.
        listing("nowhere/").let {
            it.folders.shouldBeEmpty()
            it.files.shouldBeEmpty()
            it.more shouldBe false
        }
    }

    "a file missing either half is listed flagged, and a temporary object is left out" {
        val objs = listOf(obj("half.txt", t2)) + listOf(obj(FileMeta.metaKey("ghost.txt"))) + file("whole.txt") +
            listOf(obj("whole.txt~tmp"), obj("whole.txt.meta.json~tmp"))
        val tmp: (String) -> String? = { name -> name.takeIf { it.endsWith("~tmp") }?.removeSuffix("~tmp")?.removeSuffix(FileMeta.metaSuffix) }
        val top = listing("", objs = objs, tmp = tmp)
        top.files.map { it.name to it.state } shouldContainExactly listOf(
            "ghost.txt" to FSB.stateNoContent,
            "half.txt" to FSB.stateNoMeta,
            "whole.txt" to FSB.stateOk,
        )
        // Only a file with a record has one read, and a file with only its content has none to show.
        top.files.first { it.name == "half.txt" }.let {
            it.meta.shouldBeNull()
            it.modifiedAt shouldBe t2
        }
        top.files.first { it.name == "ghost.txt" }.meta.shouldNotBeNull()

        // A record that is there and cannot be read makes no file either, and the row says so.
        val broken = FileStoreBrowse.folderListing("web-assets", "", file("broken.txt") + file("fine.txt"), 10, { null }) { key ->
            if (key == "broken.txt") null else meta(key)
        }
        broken.files.map { it.name to it.state } shouldContainExactly listOf("broken.txt" to FSB.stateUnreadableMeta, "fine.txt" to FSB.stateOk)
        broken.files.first().meta.shouldBeNull()
    }

    "a long folder is cut, folders first, and says so -- and only the files shown have their record read" {
        val asked = mutableListOf<String>()
        fun cut(limit: Int) = FileStoreBrowse.folderListing("web-assets", "", objects, limit, { null }) { key -> asked.add(key); meta(key) }
        cut(1).let {
            it.folders shouldContainExactly listOf("clients/")
            it.files.shouldBeEmpty()
            it.more shouldBe true
        }
        cut(2).let {
            it.folders shouldContainExactly listOf("clients/", "notes/")
            it.files.shouldBeEmpty()
            it.more shouldBe true
        }
        asked.shouldBeEmpty()
        cut(3).let {
            it.files.map { f -> f.key } shouldContainExactly listOf("top.txt")
            it.more shouldBe false
        }
        asked shouldContainExactly listOf("top.txt")
    }

    "a folder is named by a prefix ending in a slash, and the top of a bucket by none" {
        FileStoreBrowse.folderPrefix(null) shouldBe ""
        FileStoreBrowse.folderPrefix("  ") shouldBe ""
        FileStoreBrowse.folderPrefix("clients/acme") shouldBe "clients/acme/"
        FileStoreBrowse.folderPrefix("clients/acme/") shouldBe "clients/acme/"
    }
})
