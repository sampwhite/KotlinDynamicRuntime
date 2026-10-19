package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * The stores a node declares (issue #1282): `KDR_FILE_STORES` read into named stores beside the default one, and
 * every mistake in it refusing the start. Pure: nothing here builds a store.
 */
class FileStoreSpecsTest : StringSpec({
    var instances = 0
    val tempDirs = mutableListOf<File>()

    fun tempDir(): File = Files.createTempDirectory("kdrFileStoreSpecs").toFile().also { tempDirs.add(it) }

    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    /** A context over a fresh instance config holding [settings], which win over the environment. */
    fun cxtWith(env: String, vararg settings: Pair<String, Any?>): KdrCxt {
        val config = KdrInstanceConfig("fileStoreSpecs${instances++}", env, ENV.liveSource)
        settings.forEach { (key, value) -> config.put(key, value) }
        return KdrCxt("fileStoreSpecs", config)
    }

    fun declaring(stores: String, env: String = ENV.local, vararg more: Pair<String, Any?>) =
        cxtWith(env, FSENV.stores.name to stores, *more)

    fun refusal(stores: String, env: String = ENV.local, vararg more: Pair<String, Any?>): String {
        val refused = shouldThrow<KdrException> { FileStoreSpecs.all(declaring(stores, env, *more)) }
        refused.source shouldBe SRC.config
        refused.message.orEmpty() shouldContain "Refusing to start: "
        return refused.message.orEmpty()
    }

    "with none declared there is the default store alone" {
        val cxt = cxtWith(ENV.unit)
        FileStoreSpecs.all(cxt).map { it.name } shouldContainExactly listOf(FSNM.default)
        FileStoreSpecs.namesOf(cxt) shouldContainExactly listOf(FSNM.default)
        // A unit test's default store is memory, as the service's is.
        FileStoreSpecs.defaultOf(cxt).type shouldBe FST.memory
    }

    "stores are read by name, each one's attributes in any order and among the others'" {
        val dir = tempDir()
        val cxt = declaring(
            "aws.type=s3, disk.dir=${dir.absolutePath}, aws.region=us-east-1, disk.type=local, aws.prefix=acme-," +
                "aws.profile=kdr-aws, aws.endpoint=https://s3.example.test, ram.type=memory,",
        )
        val specs = FileStoreSpecs.all(cxt)
        specs.map { it.name } shouldContainExactly listOf(FSNM.default, "aws", "disk", "ram")
        FileStoreSpecs.namesOf(cxt) shouldContainExactly listOf(FSNM.default, "aws", "disk", "ram")
        specs[1].let {
            it.type shouldBe FST.s3
            it.region shouldBe "us-east-1"
            it.prefix shouldBe "acme-"
            it.profile shouldBe "kdr-aws"
            it.endpoint shouldBe "https://s3.example.test"
        }
        specs[2].let {
            it.type shouldBe FST.local
            it.dir shouldBe dir
        }
        specs[3].type shouldBe FST.memory
        // Every local store's directory is one to keep out of, the default's whether or not it is in use.
        FileStoreSpecs.localDirsOf(cxt) shouldContainExactly listOf(FileStoreService.localDirOf(cxt), dir)
    }

    "a local store's relative directory is the workspace's -- except in a unit test, which keeps no files there" {
        FileStoreSpecs.named(declaring("disk.type=local,disk.dir=data/disk")).single().dir shouldBe AppPaths.resolve("data/disk")
        refusal("disk.type=local,disk.dir=data/disk", ENV.unit) shouldContain "must be an absolute path"
    }

    "a declaration that says what it does not mean refuses the start, naming what is wrong" {
        val cases = listOf(
            "aws" to "holds 'aws', which is not `<store>.<attribute>=<value>`",
            "aws=s3" to "holds 'aws=s3', which is not",
            ".type=s3" to "which is not `<store>.<attribute>=<value>`",
            "aws.=s3" to "which is not `<store>.<attribute>=<value>`",
            "Aws.type=s3" to "a store 'Aws', whose name is no name",
            "1aws.type=s3" to "whose name is no name: it must begin with a lowercase letter",
            "default.type=memory" to "declares a store 'default'. That is the store KDR_FILE_STORE_TYPE",
            "aws.color=red" to "gives the store 'aws' an attribute 'color'; the attributes are type, dir, region",
            "aws.type=" to "gives the store 'aws' an empty type",
            "aws.type=memory,aws.type=memory" to "gives the store 'aws' its type twice",
            "aws.region=us-east-1" to "declares the store 'aws' with no type",
            "aws.type=gcs" to "the type 'gcs'; it must be one of memory, local, s3",
            "aws.type=memory,aws.region=us-east-1" to "gives the memory store 'aws' region, which a memory store does not take",
            "disk.type=local" to "declares the local store 'disk' with no dir",
            "aws.type=s3" to "declares the s3 store 'aws' with no region",
            "aws.type=s3,aws.region=us-east-1,aws.prefix=Acme_" to "gives the store 'aws' the prefix 'Acme_'",
        )
        for ((declared, said) in cases) {
            withClue(declared) { refusal(declared) shouldContain said }
        }
    }

    "two stores at one place are refused: each would hold the other's files" {
        val dir = tempDir()
        refusal("a.type=local,a.dir=$dir,b.type=local,b.dir=$dir") shouldContain "the file stores 'a' and 'b' are at one place"
        refusal("a.type=local,a.dir=$dir,b.type=local,b.dir=$dir/inner") shouldContain "the file stores 'a' and 'b' are at one place"
        refusal("a.type=local,a.dir=$dir/inner,b.type=local,b.dir=$dir") shouldContain "are at one place"
        // The default store's own directory too, where the default is a local store.
        val defaultDir = tempDir()
        refusal(
            "disk.type=local,disk.dir=$defaultDir/files", ENV.local,
            FSENV.type.name to FST.local, FSENV.dir.name to defaultDir.absolutePath,
        ) shouldContain "the file stores 'default' and 'disk' are at one place"
        // Siblings are apart.
        FileStoreSpecs.all(declaring("a.type=local,a.dir=$dir/one,b.type=local,b.dir=$dir/two")).map { it.name } shouldContainExactly
            listOf(FSNM.default, "a", "b")

        // S3: the same buckets are one place, whatever credentials each signs in with.
        refusal("a.type=s3,a.region=us-east-1,a.prefix=x-,b.type=s3,b.region=us-east-1,b.prefix=x-,b.profile=other") shouldContain
            "the file stores 'a', 'b' are the same buckets"
        refusal(
            "aws.type=s3,aws.region=us-east-1,aws.prefix=x-", ENV.local,
            FSENV.type.name to FST.s3, FSENV.s3Region.name to "us-east-1", FSENV.s3BucketPrefix.name to "x-",
        ) shouldContain "the file stores 'default', 'aws' are the same buckets"
        // Another prefix, region or endpoint is another place.
        FileStoreSpecs.all(declaring("a.type=s3,a.region=us-east-1,a.prefix=x-,b.type=s3,b.region=us-east-1,b.prefix=y-")).size shouldBe 3
        FileStoreSpecs.all(declaring("a.type=s3,a.region=us-east-1,b.type=s3,b.region=us-east-1,b.endpoint=https://s3.example.test")).size shouldBe 3
    }

    "a store's name is a lowercase word, as the kernel's rule says" {
        FSNM.nameProblem("aws-eu2") shouldBe null
        FSNM.nameProblem("") shouldBe "it is empty"
        FSNM.nameProblem("a".repeat(FSNM.maxNameLength + 1)) shouldBe "it is longer than ${FSNM.maxNameLength} characters"
        FSNM.nameProblem("-aws") shouldBe "it must begin with a lowercase letter"
        FSNM.nameProblem("aws_eu") shouldBe "it may hold only lowercase letters, digits and hyphens"
    }
})
