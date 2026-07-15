package com.dynamicruntime.common.content

/**
 * The JSON keys of the shared UI-config envelope (issue #70): `{ fragments, features, state }`, plus the
 * `fileId`/`buildId` of a fragment ref. In the KMP kernel so the frontend reads a config response by the same
 * constants the backend writes it with. The envelope's meaning and the per-group helpers live in
 * `base:common`'s `UiConfig.kt`.
 */
@Suppress("ConstPropertyName")
object UIC {
    const val fragments = "fragments"
    const val fileId = "fileId"
    const val buildId = "buildId"
    const val features = "features"
    const val state = "state"
}
