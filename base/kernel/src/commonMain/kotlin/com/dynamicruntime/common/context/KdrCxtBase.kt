package com.dynamicruntime.common.context

import kotlin.time.Instant

/**
 * The KMP-safe, shared slice of a request/processing context (issue #78). The backend's full KdrCxt (in
 * `base:common`, with instance config, database, request, logging, ...) implements this, and a lightweight
 * [LiteCxt] implements it for the frontend and for two-way (kernel) code -- so code that only needs the shared
 * essentials (notably the schema builders) can live in the kernel and run on both sides.
 *
 * Keep this interface deliberately thin and strictly transpile-safe: nothing backend-specific belongs here.
 * Code that needs backend-only capabilities takes this type and narrows with `if (cxt is KdrCxt)`.
 */
interface KdrCxtBase {
    /** The current instant (the backend may offset it for testing; a lite context uses the system clock). */
    fun now(): Instant
}
