package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.TreeMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * S3 as a map, for running [S3FileStore] without a network: the buckets that exist, the objects in each, and a
 * record of every call made, so a test can say what was fetched as well as what came back.
 *
 * Multipart uploads are held to S3's rule that every part but the last is at least [minPartSize], which a test may
 * lower to work with small parts; S3's own is [FPART.minPartSize].
 */
class FakeS3Api(vararg existingBuckets: String, val minPartSize: Long = FPART.minPartSize) : S3Api {
    override val location: String = "fake S3"

    /** The buckets that exist; an object call against any other fails, as S3's would. */
    val buckets = existingBuckets.toMutableSet()

    /** Object bytes by `bucket/key`. */
    val objects = TreeMap<String, ByteArray>()

    /** The content type each object was stored with, by `bucket/key`. */
    val contentTypes = HashMap<String, String>()

    /** When each object was last written, by `bucket/key`: [clock] at the put, or what a test sets. */
    val modified = HashMap<String, Instant>()

    /** What a put records as the moment it wrote. */
    var clock: () -> Instant = { Clock.System.now() }

    /** Every call, in order: `get bucket/key`, `put bucket/key`, `head …`, `delete …`, `list bucket/prefix`. */
    val calls = mutableListOf<String>()

    /** When true every call fails as an unreachable S3 does. */
    var unreachable = false

    /** Says of a call, as [calls] records it, whether it is to fail: how a test stops a write part-way. */
    var failing: ((call: String) -> Boolean)? = null

    /** Run after a `get` has read its object and before it returns: where a test changes S3 mid-read. */
    var afterGet: ((bucket: String, key: String) -> Unit)? = null

    private fun reached(call: String, bucket: String) {
        calls.add(call)
        if (unreachable) throw KdrException("S3 could not be reached.", null, EXC.notAvailable, SRC.network, ACT.io)
        if (failing?.invoke(call) == true) throw KdrException("S3 could not $call.", null, EXC.notAvailable, SRC.network, ACT.io)
        if (bucket !in buckets) throw KdrException("No such bucket '$bucket'.", null, EXC.notAvailable, SRC.network, ACT.io)
    }

    override fun headBucket(bucket: String): Boolean {
        calls.add("headBucket $bucket")
        if (unreachable) throw KdrException("S3 could not be reached.", null, EXC.notAvailable, SRC.network, ACT.io)
        return bucket in buckets
    }

    override fun put(bucket: String, key: String, bytes: ByteArray, contentType: String) {
        reached("put $bucket/$key", bucket)
        objects["$bucket/$key"] = bytes.copyOf()
        contentTypes["$bucket/$key"] = contentType
        modified["$bucket/$key"] = clock()
    }

    override fun get(bucket: String, key: String): ByteArray? {
        reached("get $bucket/$key", bucket)
        val found = objects["$bucket/$key"]?.copyOf()
        afterGet?.invoke(bucket, key)
        return found
    }

    override fun head(bucket: String, key: String): Boolean {
        reached("head $bucket/$key", bucket)
        return "$bucket/$key" in objects
    }

    override fun delete(bucket: String, key: String) {
        reached("delete $bucket/$key", bucket)
        objects.remove("$bucket/$key")
    }

    /** How many keys each listing was asked for, in order. */
    val listSizes = mutableListOf<Int>()

    override fun listObjects(bucket: String, prefix: String): List<StoredObject> {
        reached("listObjects $bucket/$prefix", bucket)
        return objects.keys.filter { it.startsWith("$bucket/$prefix") }
            .map { StoredObject(it.removePrefix("$bucket/"), modified[it] ?: Instant.fromEpochMilliseconds(0)) }
    }

    override fun list(bucket: String, prefix: String, maxKeys: Int): List<String> {
        reached("list $bucket/$prefix", bucket)
        listSizes.add(maxKeys)
        return objects.keys.filter { it.startsWith("$bucket/$prefix") }.map { it.removePrefix("$bucket/") }.take(maxKeys)
    }

    /** An upload under way: its bucket and key, when it began, and its parts by number. */
    class Multipart(val bucket: String, val key: String, val startedAt: Instant) {
        val parts = TreeMap<Int, ByteArray>()

        /** When each part was stored, by number. */
        val partTimes = TreeMap<Int, Instant>()
    }

    /** The uploads under way, by upload id. */
    val multiparts = LinkedHashMap<String, Multipart>()

    private var nextUpload = 0

    override fun getRange(bucket: String, key: String, offset: Long, length: Long): InputStream? {
        reached("getRange $bucket/$key $offset+$length", bucket)
        require(length >= 1) { "An empty range is not one S3 can be asked for." }
        val bytes = objects["$bucket/$key"] ?: return null
        // S3 answers a range past the end with what there is of it, as here.
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return ByteArrayInputStream(bytes.copyOfRange(minOf(offset.toInt(), end), end))
    }

    override fun copy(bucket: String, from: String, to: String): Boolean {
        reached("copy $bucket/$from $to", bucket)
        val bytes = objects["$bucket/$from"] ?: return false
        objects["$bucket/$to"] = bytes.copyOf()
        contentTypes["$bucket/$from"]?.let { contentTypes["$bucket/$to"] = it }
        modified["$bucket/$to"] = clock()
        return true
    }

    override fun createMultipart(bucket: String, key: String, contentType: String): String {
        reached("createMultipart $bucket/$key", bucket)
        val id = "upload${++nextUpload}"
        multiparts[id] = Multipart(bucket, key, clock())
        contentTypes["$bucket/$key"] = contentType
        return id
    }

    override fun uploadPart(bucket: String, key: String, uploadId: String, number: Int, bytes: ByteArray): String? {
        reached("uploadPart $bucket/$key $number", bucket)
        val upload = multiparts[uploadId]?.takeIf { it.bucket == bucket && it.key == key } ?: return null
        upload.parts[number] = bytes.copyOf()
        upload.partTimes[number] = clock()
        return etagOf(bytes)
    }

    override fun completeMultipart(bucket: String, key: String, uploadId: String, parts: List<Pair<Int, String>>): Boolean {
        reached("completeMultipart $bucket/$key", bucket)
        val upload = multiparts[uploadId]?.takeIf { it.bucket == bucket && it.key == key } ?: return false
        val out = ByteArrayOutputStream()
        parts.forEachIndexed { i, (number, tag) ->
            val bytes = upload.parts[number]
            if (bytes == null || etagOf(bytes) != tag) {
                throw KdrException("S3 could not complete: InvalidPart $number.", null, EXC.notAvailable, SRC.network, ACT.io)
            }
            if (i < parts.size - 1 && bytes.size < minPartSize) {
                throw KdrException("S3 could not complete: EntityTooSmall, part $number.", null, EXC.notAvailable, SRC.network, ACT.io)
            }
            out.write(bytes)
        }
        objects["$bucket/$key"] = out.toByteArray()
        modified["$bucket/$key"] = clock()
        multiparts.remove(uploadId)
        return true
    }

    override fun abortMultipart(bucket: String, key: String, uploadId: String): Boolean {
        reached("abortMultipart $bucket/$key", bucket)
        return multiparts[uploadId]?.takeIf { it.bucket == bucket && it.key == key }?.let { multiparts.remove(uploadId) } != null
    }

    override fun listMultipart(bucket: String): List<StagedUpload> {
        reached("listMultipart $bucket", bucket)
        return multiparts.filterValues { it.bucket == bucket }.map { (id, u) -> StagedUpload(u.key, id, u.startedAt) }.sortedBy { it.key }
    }

    override fun listParts(bucket: String, key: String, uploadId: String): List<S3Part>? {
        reached("listParts $bucket/$key", bucket)
        val upload = multiparts[uploadId]?.takeIf { it.bucket == bucket && it.key == key } ?: return null
        return upload.parts.map { (n, bytes) -> S3Part(n, bytes.size.toLong(), etagOf(bytes), upload.partTimes.getValue(n)) }
    }

    /** What S3 tags a part with: here, its length and a checksum, which differ for different bytes. */
    private fun etagOf(bytes: ByteArray): String = "\"${bytes.size}-${bytes.contentHashCode()}\""
}
