package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** SHA-256 in Kotlin gives the standard digest, and its state survives being saved between chunks (issue #1834). */
class Sha256Test {
    private fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    @Test
    fun theStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hexOf(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hexOf(ascii("abc")))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hexOf(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hexOf(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    private val sample = ByteArray(1000) { (it * 31 + 7).toByte() }

    @Test
    fun howTheBytesAreSplitChangesNothing() {
        val whole = Sha256.hexOf(sample)
        for (cut in listOf(1, 55, 56, 63, 64, 65, 128, 999)) {
            val split = Sha256().update(sample, 0, cut).update(sample, cut, sample.size - cut).hexDigest()
            assertEquals(whole, split, "cut at $cut")
        }
    }

    @Test
    fun aSavedStateResumesWhereItLeftOff() {
        val whole = Sha256.hexOf(sample)
        for (cut in listOf(0, 1, 64, 100, 999, 1000)) {
            val first = Sha256().update(sample, 0, cut)
            // Through JSON, as a session row keeps it: the length may come back as another kind of number.
            val state = first.toState().toJsonStr().jsonMap()!!
            val resumed = Sha256.fromState(state).update(sample, cut, sample.size - cut)
            assertEquals(whole, resumed.hexDigest(), "cut at $cut")
            assertEquals(sample.size.toLong(), resumed.bytesHashed)
        }
    }

    @Test
    fun aDigestLeavesTheHashToGoOn() {
        val h = Sha256().update(sample, 0, 500)
        h.hexDigest()
        assertEquals(Sha256.hexOf(sample), h.update(sample, 500, 500).hexDigest())
    }

    @Test
    fun aStateThatIsNotOneIsRefused() {
        val good = Sha256().update(sample, 0, 70).toState()
        for (bad in listOf(
            good + (SHA.words to "xyz"),
            good + (SHA.length to 71L),
            good + (SHA.tail to "0"),
            good - SHA.length,
        )) {
            assertFailsWith<KdrException> { Sha256.fromState(bad) }
        }
    }
}
