package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The rules a bucket name and a key are held to (issue #1129), which is what keeps a name from becoming a path of its own choosing. */
class FileNamesTest : StringSpec({
    "a bucket name is lowercase letters, digits and hyphens, so it is one S3 would take" {
        for (name in listOf("web-assets", "abc", "a1b", "files2", "a".repeat(40))) {
            withClue(name) { FNAME.bucketProblem(name).shouldBeNull() }
        }
        for (name in listOf("", "ab", "a".repeat(41), "Web-Assets", "web_assets", "web.assets", "-web", "web-", "web assets", "web/assets", "..")) {
            withClue(name) { FNAME.bucketProblem(name).shouldNotBeNull() }
        }
        val refused = shouldThrow<KdrException> { FNAME.checkBucket("Web_Assets") }
        refused.code shouldBe EXC.badInput
        refused.message shouldContain "Not a file-store bucket name: 'Web_Assets'"
        shouldThrow<KdrException> { FileBucketDef("Web_Assets", "A bucket.") }
    }

    "a key is safe segments between single slashes" {
        for (key in listOf("a", "clients/acme_1/web/favicon", "A.b-c_d/e.png", "x".repeat(128), "a.meta.jsonx", "meta.json")) {
            withClue(key) { FNAME.keyProblem(key).shouldBeNull() }
        }
        val refused = listOf(
            "", "/a", "a/", "a//b", ".", "..", "a/../b", "a/./b", "../a", "a b", "a\\b", "a:b", "a~tmp", "a\u0000b", "a\nb",
            "café", "x".repeat(129), ("a/".repeat(256) + "a"),
            // The metadata's own name, anywhere: the file beside a file is never a file of its own.
            "logo.meta.json", "logo.meta.json/x", "a/.meta.json",
        )
        for (key in refused) {
            withClue(key) { FNAME.keyProblem(key).shouldNotBeNull() }
        }
        val thrown = shouldThrow<KdrException> { FNAME.checkKey("../etc/passwd") }
        thrown.code shouldBe EXC.badInput
        thrown.message shouldContain "Not a file-store key: '../etc/passwd'"
        FNAME.checkKey("clients/acme/logo") shouldBe "clients/acme/logo"
    }

    "a listing's prefix is nothing, a key, or a key and a slash" {
        for (prefix in listOf("", "clients", "clients/", "clients/acme/web/")) {
            withClue(prefix) { FNAME.checkKeyPrefix(prefix) shouldBe prefix }
        }
        for (prefix in listOf("/", "clients//", "../", "a/../", "clients/ acme")) {
            withClue(prefix) { shouldThrow<KdrException> { FNAME.checkKeyPrefix(prefix) }.code shouldBe EXC.badInput }
        }
    }
})
