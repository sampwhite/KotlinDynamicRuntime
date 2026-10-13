package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.filestore.FSENV
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * The local files a file ref may name (issue #1154): where the deployment says they are, the places that may not be
 * a root, and -- case by case -- everything a root and a path are held to before a byte is handed back.
 */
class LocalFileRootsTest : StringSpec({
    val tempDirs = mutableListOf<File>()
    var instances = 0

    fun tempDir(): File = Files.createTempDirectory("kdrLocalFiles").toFile().also { tempDirs.add(it) }

    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><circle cx="4" cy="4" r="3"/></svg>""".toByteArray()

    fun cxtWith(env: String, vararg settings: Pair<String, Any?>): KdrCxt {
        val config = KdrInstanceConfig("localFileRoots${instances++}", env, ENV.liveSource)
        settings.forEach { (key, value) -> config.put(key, value) }
        return KdrCxt("localFileRoots", config)
    }

    fun refusal(env: String, vararg settings: Pair<String, Any?>): String = withClue(settings.toList()) {
        shouldThrow<KdrException> { LocalFileRoots.resolve(cxtWith(env, *settings)) }.also {
            it.source shouldBe SRC.config
            it.message.orEmpty() shouldContain "Refusing to start"
        }.message.orEmpty()
    }

    /** A location holding one root, `brand`, with a picture in it; the roots over it as a unit test declares them. */
    class Rig(val location: File, val brand: File, val roots: LocalFileRoots)

    fun rig(): Rig {
        val location = tempDir()
        val brand = File(location, "brand").apply { mkdirs() }
        File(brand, "logo.png").writeBytes(png)
        return Rig(location, brand, LocalFileRoots.resolve(cxtWith(ENV.unit, LFENV.dir.name to location.absolutePath)))
    }

    "the location is 'localFiles' in the workspace unless one is named, and a unit test is given none" {
        // Resolved only: nothing here creates the default, which is in the real workspace.
        val workspaceDir = AppPaths.resolve(LocalFileRoots.defaultDirName)
        val wasThere = workspaceDir.exists()
        LocalFileRoots.resolve(cxtWith(ENV.local)).location shouldBe workspaceDir
        workspaceDir.exists() shouldBe wasThere

        // A relative one is taken from the workspace, an absolute one as written.
        LocalFileRoots.resolve(cxtWith(ENV.local, LFENV.dir.name to "data/images")).location shouldBe AppPaths.resolve("data/images")
        val absolute = tempDir()
        LocalFileRoots.resolve(cxtWith(ENV.local, LFENV.dir.name to absolute.absolutePath)).location shouldBe absolute

        // A unit test never reads the workspace: no default, and no relative path, which would resolve into it.
        val none = LocalFileRoots.resolve(cxtWith(ENV.unit))
        none.location.shouldBeNull()
        none.hasRoot("brand") shouldBe false
        none.read("brand", "logo.png").file.shouldBeNull()
        refusal(ENV.unit, LFENV.dir.name to "data/images") shouldContain "a unit test names an absolute one"
        refusal(ENV.unit, LFENV.roots.name to "brand=repo/images") shouldContain "a unit test names an absolute one"
    }

    "a root is a directory under the location, or one placed elsewhere -- which takes the place of a directory of its name" {
        val r = rig()
        r.roots.hasRoot("brand") shouldBe true
        r.roots.read("brand", "logo.png").file.shouldNotBeNull().bytes.toList() shouldBe png.toList()
        // Nothing creates one: a name with no directory is no root, and a file is not a directory.
        r.roots.hasRoot("shared") shouldBe false
        File(r.location, "afile").writeBytes(png)
        r.roots.hasRoot("afile") shouldBe false

        // Placed elsewhere, by name; and a name declared so is that place, whatever is under the location.
        val elsewhere = tempDir().also { File(it, "logo.png").writeBytes(svg) }
        val shared = tempDir().also { File(it, "pic.svg").writeBytes(svg) }
        val placed = LocalFileRoots.resolve(
            cxtWith(
                ENV.unit, LFENV.dir.name to r.location.absolutePath,
                LFENV.roots.name to " brand = ${elsewhere.absolutePath} , shared=${shared.absolutePath} ",
            ),
        )
        placed.read("brand", "logo.png").file.shouldNotBeNull().bytes.toList() shouldBe svg.toList()
        placed.read("shared", "pic.svg").file.shouldNotBeNull().kind.mimeType shouldBe "image/svg+xml"
        // With no location at all, a placed root is still one.
        LocalFileRoots.resolve(cxtWith(ENV.unit, LFENV.roots.name to "shared=${shared.absolutePath}")).hasRoot("shared") shouldBe true
        // A place that is not there is no fault of the declaration: there is simply no such root yet, and the
        // startup line is where that is said.
        val absent = File(tempDir(), "later")
        val later = LocalFileRoots.resolve(cxtWith(ENV.unit, LFENV.dir.name to absent.absolutePath, LFENV.roots.name to "soon=${absent.absolutePath}/soon"))
        later.hasRoot("soon") shouldBe false
        later.describe() shouldBe "'${absent.absolutePath}' is not there, so it holds no roots; the root 'soon' is at '${absent.absolutePath}/soon', which is not there"
        // What is there is named: the roots under the location, less a name placed elsewhere and a name that is none.
        File(r.location, "shared").mkdirs()
        File(r.location, "my brand").mkdirs()
        r.roots.describe() shouldBe "'${r.location.absolutePath}' holds the roots brand, shared"
        placed.describe() shouldBe
            "'${r.location.absolutePath}' holds no roots; the root 'brand' is at '${elsewhere.absolutePath}'; the root 'shared' is at '${shared.absolutePath}'"
        LocalFileRoots.resolve(cxtWith(ENV.unit)).describe() shouldBe "no location is named"
    }

    "a declaration that is malformed refuses the start" {
        val dir = tempDir().absolutePath
        refusal(ENV.local, LFENV.roots.name to "brand") shouldContain "not `name=location`"
        refusal(ENV.local, LFENV.roots.name to "=$dir") shouldContain "not `name=location`"
        refusal(ENV.local, LFENV.roots.name to "brand=") shouldContain "not `name=location`"
        refusal(ENV.local, LFENV.roots.name to "my brand=$dir") shouldContain "whose name is no name"
        refusal(ENV.local, LFENV.roots.name to "../brand=$dir") shouldContain "whose name is no name"
        refusal(ENV.local, LFENV.roots.name to "brand=$dir,brand=$dir") shouldContain "declares the root 'brand' twice"
        // A path from the workspace stays in it, and names nothing hidden.
        refusal(ENV.local, LFENV.dir.name to "../elsewhere") shouldContain "may not hold '..'"
        refusal(ENV.local, LFENV.dir.name to "images/../../elsewhere") shouldContain "may not hold '..'"
        refusal(ENV.local, LFENV.dir.name to ".git/images") shouldContain "a name beginning with a dot"
        refusal(ENV.local, LFENV.roots.name to "brand=repo/.hidden") shouldContain "a name beginning with a dot"
    }

    "a location or a root inside a place that holds the deployment's data, or containing one, refuses the start" {
        // Inside one of the workspace's own.
        for (reserved in listOf("private", "private/images", "h2Database", "logs/pictures", "sampleFiles/x", "fileStore/web-assets")) {
            refusal(ENV.local, LFENV.dir.name to reserved) shouldContain "which holds the deployment's own data"
            refusal(ENV.local, LFENV.roots.name to "brand=$reserved") shouldContain "is inside"
        }
        // Containing one: the workspace itself, what is above it, and the root of everything.
        val workspace = AppPaths.workspaceDir().absoluteFile
        for (wide in listOf(workspace.path, workspace.parentFile.path, "/")) {
            refusal(ENV.local, LFENV.dir.name to wide) shouldContain "contains"
            refusal(ENV.local, LFENV.roots.name to "all=$wide") shouldContain "contains"
        }
        // The file store's directory is wherever it was put, and is judged where it is.
        val base = tempDir()
        val store = File(base, "store").apply { mkdirs() }
        refusal(ENV.local, FSENV.dir.name to store.absolutePath, LFENV.dir.name to File(store, "pictures").absolutePath) shouldContain "is inside"
        refusal(ENV.local, FSENV.dir.name to store.absolutePath, LFENV.roots.name to "all=${base.absolutePath}") shouldContain "contains"
        // By its real path: a link into one is in it.
        val link = File(base, "innocent")
        Files.createSymbolicLink(link.toPath(), store.toPath())
        refusal(ENV.local, FSENV.dir.name to store.absolutePath, LFENV.roots.name to "pics=${link.absolutePath}") shouldContain "is inside"
        // Beside it is fine.
        val beside = File(base, "pictures").apply { mkdirs() }
        LocalFileRoots.resolve(cxtWith(ENV.local, FSENV.dir.name to store.absolutePath, LFENV.dir.name to beside.absolutePath)).location shouldBe beside
    }

    "a root that comes to point at such a place after the start is no root" {
        val base = tempDir()
        val store = File(base, "store").apply { mkdirs() }
        File(store, "secret.png").writeBytes(png)
        val location = File(base, "pictures").apply { mkdirs() }
        val roots = LocalFileRoots.resolve(cxtWith(ENV.unit, FSENV.dir.name to store.absolutePath, LFENV.dir.name to location.absolutePath))
        // A root may be a link: whoever runs the deployment made it.
        val real = tempDir().also { File(it, "logo.png").writeBytes(png) }
        val brand = File(location, "brand")
        Files.createSymbolicLink(brand.toPath(), real.toPath())
        roots.read("brand", "logo.png").file.shouldNotBeNull()
        // Repointed at the file store, it is judged where it now is, each time it is used.
        Files.delete(brand.toPath())
        Files.createSymbolicLink(brand.toPath(), store.toPath())
        roots.hasRoot("brand") shouldBe false
        roots.read("brand", "secret.png").let {
            it.file.shouldBeNull()
            it.reason.orEmpty() shouldContain "is inside"
        }
    }

    "a root's name is a plain name, wherever it is asked for" {
        val r = rig()
        File(r.location, "my brand").mkdirs()
        File(r.location, ".hidden").mkdirs()
        for (name in listOf("", ".", "..", "my brand", ".hidden", "brand/..", "brand/sub", "/etc", "bränd")) {
            r.roots.hasRoot(name) shouldBe false
            r.roots.read(name, "logo.png").file.shouldBeNull()
        }
    }

    "a path is held to a root: no way up, no absolute path, no backslash, no hidden name" {
        val r = rig()
        File(r.location, "secret.png").writeBytes(png)
        File(r.brand, "sub").mkdirs()
        File(r.brand, "sub/pic.png").writeBytes(png)
        File(r.brand, ".hidden").mkdirs()
        File(r.brand, ".hidden/pic.png").writeBytes(png)
        File(r.brand, ".pic.png").writeBytes(png)

        r.roots.read("brand", "sub/pic.png").file.shouldNotBeNull().name shouldBe "pic.png"
        val refused = listOf(
            "", "../secret.png", "sub/../../secret.png", "sub/../logo.png", "./logo.png", "sub/./pic.png",
            "/etc/passwd", r.brand.absolutePath + "/logo.png", "sub//pic.png", "sub/", "/logo.png",
            "sub\\pic.png", "..\\secret.png", ".hidden/pic.png", ".pic.png", "sub/.pic.png", "logo.png\u0000.txt", "lo go.png", "logo.png?x",
        )
        for (path in refused) {
            val read = r.roots.read("brand", path)
            read.file.shouldBeNull()
            read.reason.orEmpty() shouldContain "the path is not one a local file may have"
        }
    }

    "the file's real path is exactly the root's plus the path: a link below the root is refused, out of it or within it" {
        val r = rig()
        val outside = tempDir().also { File(it, "theirs.png").writeBytes(png) }
        // A link to a file outside the root -- a picture even, so nothing but the link refuses it.
        Files.createSymbolicLink(File(r.brand, "out.png").toPath(), File(outside, "theirs.png").toPath())
        // A link to a directory outside it.
        Files.createSymbolicLink(File(r.brand, "away").toPath(), outside.toPath())
        // A link that stays within the root.
        Files.createSymbolicLink(File(r.brand, "alias.png").toPath(), File(r.brand, "logo.png").toPath())
        // A link to nothing.
        Files.createSymbolicLink(File(r.brand, "dangling.png").toPath(), File(outside, "absent.png").toPath())

        for (path in listOf("out.png", "away/theirs.png", "alias.png")) {
            val read = r.roots.read("brand", path)
            read.file.shouldBeNull()
            read.reason.orEmpty() shouldContain "reached through a link"
        }
        r.roots.read("brand", "dangling.png").file.shouldBeNull()
        // A name that differs from the file's own only by case resolves on a filesystem that ignores case, and is
        // refused there; where case is kept, there is simply no such file. Either way, none.
        r.roots.read("brand", "Logo.png").file.shouldBeNull()
        r.roots.read("brand", "LOGO.PNG").file.shouldBeNull()
        r.roots.read("brand", "logo.png").file.shouldNotBeNull()
    }

    "only a regular file that is an image of bounded size is handed back, and the reason says nothing of what a file holds" {
        val r = rig()
        File(r.brand, "folder.png").mkdirs()
        File(r.brand, "big.png").writeBytes(png + ByteArray(FREF.maxSize))
        File(r.brand, "edge.png").writeBytes(png + ByteArray(FREF.maxSize - png.size))
        val secret = "password=hunter2-in-a-file"
        File(r.brand, "notes.png").writeText(secret)
        File(r.brand, "data.svg").writeText("<config><password>$secret</password></config>")
        File(r.brand, "broken.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"><$secret")
        File(r.brand, "script.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg"><script>alert('$secret')</script></svg>""")
        File(r.brand, "empty.png").writeBytes(ByteArray(0))
        File(r.brand, "pic.svg").writeBytes(svg)

        r.roots.read("brand", "folder.png").reason shouldBe "it is not a file"
        r.roots.read("brand", "big.png").reason.orEmpty() shouldContain "it is larger than"
        r.roots.read("brand", "edge.png").file.shouldNotBeNull().bytes.size shouldBe FREF.maxSize
        r.roots.read("brand", "absent.png").reason shouldBe "there is no such file"
        for (path in listOf("notes.png", "data.svg", "broken.svg", "script.svg", "empty.png")) {
            val read = r.roots.read("brand", path)
            read.file.shouldBeNull()
            // One thing said, whatever the file held: what a parser says of a file can repeat what is in it.
            read.reason shouldBe "it is not an image a file ref may be"
        }
        // Why is said in the log once for a file and a reason, not once a request: a missing picture on a busy page
        // is asked for by every view of it.
        val source = LocalFileSource(r.roots)
        val minute = 60_000_000_000L
        source.shouldSay("local:brand/absent.png", "there is no such file", 0) shouldBe true
        source.shouldSay("local:brand/absent.png", "there is no such file", minute) shouldBe false
        source.shouldSay("local:brand/other.png", "there is no such file", minute) shouldBe true
        source.shouldSay("local:brand/absent.png", "it is not a file", 2 * minute) shouldBe true
        source.shouldSay("local:brand/absent.png", "it is not a file", 3 * minute) shouldBe false
        source.shouldSay("local:brand/absent.png", "it is not a file", 2 * minute + LocalFileSource.repeatReasonAfterMs * 1_000_000) shouldBe true
        // The kind is the bytes' own, whatever the file is called.
        r.roots.read("brand", "pic.svg").file.shouldNotBeNull().kind.mimeType shouldBe "image/svg+xml"
        File(r.brand, "really-a-png.svg").writeBytes(png)
        r.roots.read("brand", "really-a-png.svg").file.shouldNotBeNull().kind.mimeType shouldBe "image/png"
    }
})
