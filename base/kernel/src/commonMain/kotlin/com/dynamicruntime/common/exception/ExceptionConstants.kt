package com.dynamicruntime.common.exception

// Constants for the exception package. Per the code guide, they live in
// upper-cased acronym objects and are always referenced qualified (e.g.
// `EXC.badInput`, `SRC.database`, `ACT.conversion`), never wildcard-imported.
// The lowerCamelCase `const val` names knowingly violate Kotlin style, so each
// object suppresses the const-naming inspection at the object level.

/**
 * HTTP-style status codes a [KdrException] can carry. We tend to view exceptions
 * through the lens of the HTTP code they would produce. Generally a 500 or a 504
 * is worth retrying (possibly against another cluster node).
 */
@Suppress("ConstPropertyName")
object EXC {
    const val ok = 200
    const val okCreated = 201
    const val badInput = 400
    const val authNeeded = 401
    const val notAuthorized = 403
    const val notFound = 404
    const val conflict = 409

    /** The default error code. */
    const val internalError = 500

    // Treated as a version of 400 that is not the caller's fault.
    const val notSupported = 501
    const val notResponding = 502

    /** Returned by a node that is shutting down. */
    const val notAvailable = 504
}

/** Source codes: what type of resource the issue arose from. */
@Suppress("ConstPropertyName")
object SRC {
    const val network = "network"
    const val file = "file"
    const val database = "database"
    const val config = "config"
    const val system = "system"
}

/** Activity codes: what kind of activity was underway when the issue arose. */
@Suppress("ConstPropertyName")
object ACT {
    /** No particular activity, the most common case. */
    const val general = "general"
    const val interrupted = "interrupted"
    const val conversion = "conversion"
    const val auth = "auth"

    /** Code threw a deliberate exception based on internal logic. */
    const val code = "code"
    const val connection = "connection"
    const val io = "io"
}
