package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI

/**
 * The few S3 calls the file store makes (issue #1129), as an interface of our own. It is the whole of what
 * [S3FileStore] knows of S3, which does two things: the AWS SDK's types stay in this one file, and the
 * provider can be run -- every test of it is -- against a stand-in that keeps objects in a map, since the
 * unit tests never reach a network.
 *
 * A bucket here is an S3 bucket by its real name, and a key an object key. An object or a bucket that is not
 * there is an ordinary answer (`null`, `false`); anything else that goes wrong -- S3 unreachable, the
 * credentials refused -- is a [KdrException].
 */
interface S3Api {
    /** Where this reaches, in words: for the startup line and the boot report. */
    val location: String

    /** Whether [bucket] exists and these credentials may use it. */
    fun headBucket(bucket: String): Boolean

    fun put(bucket: String, key: String, bytes: ByteArray, contentType: String)

    /** The object's bytes, or null when there is no such object. */
    fun get(bucket: String, key: String): ByteArray?

    /** Whether there is an object at [key], without fetching it. */
    fun head(bucket: String, key: String): Boolean

    /** Removes the object at [key]; no error when there is none. */
    fun delete(bucket: String, key: String)

    /** The object keys beginning with [prefix], in key order: the first [maxKeys] of them. */
    fun list(bucket: String, prefix: String, maxKeys: Int = Int.MAX_VALUE): List<String>
}

/**
 * [S3Api] over the AWS SDK for Java v2. Credentials come from the SDK's own default chain -- the environment,
 * a profile, the machine's role -- so none is ever in this application's configuration or its secrets file.
 *
 * [endpoint] points it at something other than AWS that speaks S3 (a local stand-in, another vendor), which
 * is addressed by path rather than by a bucket's own hostname.
 *
 * The SDK is told which HTTP client to use, its JDK-backed one, rather than left to find one: the build
 * excludes its Netty and Apache clients (`base/common/build.gradle.kts`).
 */
class SdkS3Api(val region: String, val endpoint: String? = null) : S3Api, AutoCloseable {
    override val location: String = if (endpoint == null) "S3 in $region" else "S3 at $endpoint ($region)"

    private val client: S3Client = S3Client.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .region(Region.of(region))
        .apply {
            if (endpoint != null) {
                endpointOverride(URI.create(endpoint))
                forcePathStyle(true)
            }
        }
        .build()

    override fun headBucket(bucket: String): Boolean =
        call("check the bucket '$bucket'", absent = false, isAbsent = ::isNotFound) {
            client.headBucket { it.bucket(bucket) }
            true
        }

    override fun put(bucket: String, key: String, bytes: ByteArray, contentType: String) {
        call("store '$key' in the bucket '$bucket'") {
            client.putObject({ it.bucket(bucket).key(key).contentType(contentType) }, RequestBody.fromBytes(bytes))
        }
    }

    override fun get(bucket: String, key: String): ByteArray? =
        call<ByteArray?>("read '$key' in the bucket '$bucket'", absent = null) {
            client.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()
        }

    override fun head(bucket: String, key: String): Boolean =
        call("check '$key' in the bucket '$bucket'", absent = false) {
            client.headObject { it.bucket(bucket).key(key) }
            true
        }

    override fun delete(bucket: String, key: String) {
        call("delete '$key' in the bucket '$bucket'") { client.deleteObject { it.bucket(bucket).key(key) } }
    }

    override fun list(bucket: String, prefix: String, maxKeys: Int): List<String> =
        call("list the bucket '$bucket'") {
            // The paginator fetches a page when it is walked into, so taking the first few asks for no more.
            client.listObjectsV2Paginator { it.bucket(bucket).prefix(prefix) }.contents().asSequence()
                .take(maxKeys).map { it.key() }.toList()
        }

    override fun close() = client.close()

    /** Runs an S3 call, turning its failure into ours -- the one place an SDK exception is caught. */
    private fun <T> call(what: String, block: () -> T): T = try {
        block()
    } catch (e: SdkException) {
        throw failure(what, e)
    }

    /**
     * As [call], for a call where "not there" is an answer rather than a failure: it yields [absent]. What
     * counts as not there is [isAbsent] -- for an object, that the object is missing and not its whole bucket.
     */
    private fun <T> call(what: String, absent: T, isAbsent: (SdkException) -> Boolean = ::isMissingObject, block: () -> T): T = try {
        block()
    } catch (e: SdkException) {
        if (isAbsent(e)) absent else throw failure(what, e)
    }

    private fun isNotFound(e: SdkException): Boolean = e is S3Exception && e.statusCode() == notFoundStatus

    /**
     * Whether [e] says an object is not there. S3 answers "not found" for a missing bucket as well, and that is
     * a deployment that is set up wrong, not a file nobody stored: read as an absent file it would have every
     * file in the bucket quietly vanish. (A `head` carries no reason, so there the two cannot be told apart.)
     */
    private fun isMissingObject(e: SdkException): Boolean =
        isNotFound(e) && (e as S3Exception).awsErrorDetails()?.errorCode() != noSuchBucketCode

    /**
     * **Sensitive**: what S3 says of a refusal names the account, the role and the real bucket, which is for
     * whoever runs the deployment and not for whoever asked for a file. Where a deployment obfuscates, the
     * client is told only that something failed and the log has the rest.
     */
    private fun failure(what: String, e: SdkException): KdrException =
        KdrException("S3 could not $what: ${e.message}", e, EXC.notAvailable, SRC.network, ACT.io, sensitive = true)

    @Suppress("ConstPropertyName")
    companion object {
        const val notFoundStatus = 404

        /** S3's error code for a bucket that does not exist. */
        const val noSuchBucketCode = "NoSuchBucket"
    }
}
