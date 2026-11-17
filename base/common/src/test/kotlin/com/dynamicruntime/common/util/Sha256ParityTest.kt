package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The kernel's SHA-256 (issue #1834) gives the JDK's digest for any bytes split any way, so a file hashed chunk by
 * chunk across nodes gets the hash the file store records for the same bytes put whole.
 */
class Sha256ParityTest : StringSpec({
    fun jdk(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    "random lengths and random splits agree with the JDK" {
        val random = Random(1834)
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(0, 3000))
            var hash = Sha256()
            var at = 0
            while (at < bytes.size) {
                val take = random.nextInt(1, 300).coerceAtMost(bytes.size - at)
                // Saved and resumed between some chunks, as a session row does between requests.
                if (random.nextBoolean()) hash = Sha256.fromState(hash.toState())
                hash.update(bytes, at, take)
                at += take
            }
            hash.hexDigest() shouldBe jdk(bytes)
        }
    }
})
