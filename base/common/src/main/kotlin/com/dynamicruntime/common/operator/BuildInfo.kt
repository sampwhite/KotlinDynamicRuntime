package com.dynamicruntime.common.operator

import com.dynamicruntime.common.util.jsonMapOrNull
import kotlin.time.Instant

/** The build-info resource (issue #1620): where the build writes it, and its field names, each matching its value. */
@Suppress("ConstPropertyName")
object BLD {
    /** The classpath resource `base/common`'s `embedBuildInfo` task writes. */
    const val resourcePath = "/build-info/kdr-build.json"

    const val commit = "commit"
    const val commitEpochSeconds = "commitEpochSeconds"
    const val branch = "branch"
    const val uncommittedChanges = "uncommittedChanges"

    /** The commit recorded by a build that had no git to ask. */
    const val unknown = "unknown"
}

/**
 * The commit this node's code was built from (issue #1620), as `base/common`'s `embedBuildInfo` Gradle task recorded it
 * in the `build-info/kdr-build.json` resource. Read once, on first use.
 *
 * Every field is null when the resource is missing, as it is for a run whose build skipped `processResources`. [commit]
 * is [BLD.unknown] when the build had no git to ask, or was made outside a checkout.
 */
object BuildInfo {
    /** The resource's contents, or an empty map when there is none or it does not parse. */
    val recorded: Map<String, Any?> by lazy {
        BuildInfo::class.java.getResourceAsStream(BLD.resourcePath)?.use { it.readBytes().decodeToString() }
            ?.jsonMapOrNull() ?: emptyMap()
    }

    /** The full commit hash, `unknown`, or null when nothing was recorded. */
    val commit: String? get() = recorded[BLD.commit] as? String

    /** When the commit was made, or null when it is not known. */
    val commitTime: Instant?
        get() = (recorded[BLD.commitEpochSeconds] as? Number)?.toLong()?.let { Instant.fromEpochSeconds(it) }

    /** The branch the build was made from, or null when nothing was recorded. */
    val branch: String? get() = recorded[BLD.branch] as? String

    /** Whether the checkout had uncommitted changes when it was built, or null when nothing was recorded. */
    val uncommittedChanges: Boolean? get() = recorded[BLD.uncommittedChanges] as? Boolean
}
