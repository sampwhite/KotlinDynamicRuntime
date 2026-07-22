package com.dynamicruntime.common.context

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A lightweight [KdrCxtBase] for the frontend and for two-way (kernel) code and tests -- it carries none of
 * the backend machinery (instance config, database, request, logging) that the full `KdrCxt` does. Anything
 * that only needs the shared context surface (notably building a schema) can run on this, on JVM or JS.
 *
 * [fixedNow] pins the clock for deterministic tests; left null, it reads the system clock. It plays the role
 * the backend's instance clock does (issue #160): a lite context has no per-context delta, so [now] and
 * [instanceNow] coincide.
 */
class LiteCxt(private val fixedNow: Instant? = null) : KdrCxtBase {
    override fun instanceNow(): Instant = fixedNow ?: Clock.System.now()
    override fun now(): Instant = instanceNow()
}
