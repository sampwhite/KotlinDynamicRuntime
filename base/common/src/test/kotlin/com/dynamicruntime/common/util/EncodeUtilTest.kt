package com.dynamicruntime.common.util

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith

class EncodeUtilTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    "base64Encode round-trips arbitrary bytes and is URL-safe" {
        val bytes = ByteArray(256) { it.toByte() } // every byte value
        val encoded = bytes.base64Encode()
        encoded shouldNotContain "+" // URL-safe alphabet uses '-' and '_'
        encoded shouldNotContain "/"
        encoded shouldNotContain "=" // unpadded, so fully URL-safe
        encoded.base64Decode() shouldBe bytes // still round-trips despite the missing padding
    }

    "base64EncodeStripped drops '=' padding and replaces '-' with 'z'" {
        // 0xFB's top 6 bits are 111110 = index 62 = '-' in the URL-safe alphabet, so this input's encoding
        // contains '-' (exercising the 'z' substitution); 4 bytes also forces '=' padding in plain base64.
        val bytes = ByteArray(4) { 0xFB.toByte() }
        bytes.base64Encode() shouldBe "-_v7-w" // URL-safe, unpadded, still has the '-'s
        val stripped = bytes.base64EncodeStripped()
        stripped shouldBe "z_v7zw"
        stripped shouldContain "z" // the '-' -> 'z' replacement actually happened
        stripped shouldNotContain "-"
        stripped shouldNotContain "="
        stripped shouldMatch Regex("[A-Za-z0-9_]+") // all single-token characters
    }

    "stdHash is deterministic and search-friendly" {
        "hello world".stdHash() shouldBe "hello world".stdHash()
        "hello world".stdHash() shouldNotBe "hello worlD".stdHash()
        "hello world".stdHash() shouldMatch Regex("[A-Za-z0-9_]+")
    }

    "mkUniqueShorterStr keeps short strings and shortens long ones with a hash core" {
        "short".mkUniqueShorterStr(40) shouldBe "short"
        val long = "a".repeat(200)
        val short = long.mkUniqueShorterStr(40)
        short.length shouldBe 40
        short shouldStartWith "aaaaaaaaaa" // head preserved
    }

    "mkUniqueId is a 17-digit timestamp plus a short random suffix, and sorts by time" {
        val id = cxt.mkUniqueId()
        id shouldMatch Regex("\\d{17}[A-Za-z0-9_]+")
        id.substring(0, 17).all { it.isDigit() }.shouldBeTrue()

        // An id made now sorts after one made from an earlier instant (string order == time order).
        val earlier = cxt.now().addDays(-1).formatCompactId() + mkRndString(4)
        (earlier < id).shouldBeTrue()
    }

    "mkRndString yields distinct values of the expected length" {
        val a = mkRndString(4)
        val b = mkRndString(4)
        a shouldNotBe b
        a.length shouldBe 6 // 4 bytes -> 6 unpadded base64 chars
    }

    "hashPassword verifies the right password and rejects the wrong one" {
        val hash = "correct horse battery staple".hashPassword()
        hash shouldStartWith "pbkdf2|"
        "correct horse battery staple".checkPassword(hash).shouldBeTrue()
        "Correct horse battery staple".checkPassword(hash).shouldBeFalse()
    }

    "hashPassword uses a fresh salt each time, so two hashes of one password differ" {
        val p = "s3cret"
        p.hashPassword() shouldNotBe p.hashPassword()
    }

    "checkPassword falls back to a direct compare for a non-hashed (bootstrap) stored value" {
        "openSesame".checkPassword("openSesame").shouldBeTrue()
        "openSesame".checkPassword("nope").shouldBeFalse()
    }

    "checkPassword rejects an unknown algorithm tag" {
        shouldThrow<KdrException> { "pw".checkPassword("scrypt|salt|hash") }
    }

    "encrypt/decrypt round-trips with the same key" {
        val key = mkEncryptionKey()
        val plain = "attack at dawn — €—ünïcode"
        val cipher = plain.encrypt(key)
        cipher shouldStartWith "AGN"
        cipher.decrypt(key) shouldBe plain
    }

    "decrypt fails with the wrong key or a tampered signature" {
        val key = mkEncryptionKey()
        val cipher = "message".encrypt(key)
        shouldThrow<KdrException> { cipher.decrypt(mkEncryptionKey()) } // wrong key -> GCM tag fails
        shouldThrow<KdrException> { "XXX${cipher.substring(3)}".decrypt(key) } // bad signature
    }
})
