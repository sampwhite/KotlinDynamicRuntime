package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import java.util.TreeMap

/**
 * S3 as a map, for running [S3FileStore] without a network: the buckets that exist, the objects in each, and a
 * record of every call made, so a test can say what was fetched as well as what came back.
 */
class FakeS3Api(vararg existingBuckets: String) : S3Api {
    override val location: String = "fake S3"

    /** The buckets that exist; an object call against any other fails, as S3's would. */
    val buckets = existingBuckets.toMutableSet()

    /** Object bytes by `bucket/key`. */
    val objects = TreeMap<String, ByteArray>()

    /** The content type each object was stored with, by `bucket/key`. */
    val contentTypes = HashMap<String, String>()

    /** Every call, in order: `get bucket/key`, `put bucket/key`, `head …`, `delete …`, `list bucket/prefix`. */
    val calls = mutableListOf<String>()

    /** When true every call fails as an unreachable S3 does. */
    var unreachable = false

    /** Run after a `get` has read its object and before it returns: where a test changes S3 mid-read. */
    var afterGet: ((bucket: String, key: String) -> Unit)? = null

    private fun reached(call: String, bucket: String) {
        calls.add(call)
        if (unreachable) throw KdrException("S3 could not be reached.", null, EXC.notAvailable, SRC.network, ACT.io)
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

    override fun list(bucket: String, prefix: String): List<String> {
        reached("list $bucket/$prefix", bucket)
        return objects.keys.filter { it.startsWith("$bucket/$prefix") }.map { it.removePrefix("$bucket/") }
    }
}
