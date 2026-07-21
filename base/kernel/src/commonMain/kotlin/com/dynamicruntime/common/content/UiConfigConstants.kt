package com.dynamicruntime.common.content

/**
 * The JSON keys of the shared UI-config envelope (issue #70): `{ fragments, features, settings, state }`, plus
 * the `fileId`/`buildId` of a fragment ref. In the KMP kernel so the frontend reads a config response by the
 * same constants the backend writes it with. The envelope's meaning and the per-group helpers live in
 * `base:common`'s `UiConfig.kt`.
 */
@Suppress("ConstPropertyName")
object UIC {
    const val fragments = "fragments"
    const val fileId = "fileId"
    const val buildId = "buildId"

    /** Boolean policy flags: which affordances/behaviors are on for this caller/deployment. */
    const val features = "features"

    /**
     * Non-flag tuning *values* (numbers, strings) the frontend reads, kept apart from the boolean [features] so
     * a config map is not a mix of flags and magnitudes. The app config's idle-bump interval (issue #146) is
     * the first tenant; a group's own numeric knobs (a default page size, a poll interval) belong here too.
     */
    const val settings = "settings"

    const val state = "state"
}
