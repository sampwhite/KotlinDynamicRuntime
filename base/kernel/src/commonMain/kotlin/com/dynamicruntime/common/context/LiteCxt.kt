package com.dynamicruntime.common.context

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A lightweight [KdrCxtBase] for the frontend and for two-way (kernel) code and tests -- it carries none of
 * the backend machinery (instance config, database, request, logging) that the full `KdrCxt` does. Anything
 * that only needs the shared context surface (notably building a schema) can run on this, on JVM or JS.
 *
 * [fixedNow] pins [now] for deterministic tests; left null, it reads the system clock.
 */
class LiteCxt(private val fixedNow: Instant? = null) : KdrCxtBase {
    override fun now(): Instant = fixedNow ?: Clock.System.now()
}
