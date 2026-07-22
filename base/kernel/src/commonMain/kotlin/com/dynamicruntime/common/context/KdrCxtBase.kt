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
    /**
     * The current instant for **this context** (issue #160): the instance clock ([instanceNow]) plus any
     * per-context delta. Use it for **transitory** dates -- form-token/session/device-trust lifetimes,
     * rate-limit windows, and most generated timestamps.
     *
     * The backend may travel it for testing (advance or freeze); a lite context uses the system clock.
     */
    fun now(): Instant

    /**
     * The current instant for the **instance** (issue #160): the shared clock every context sees, before any
     * per-context delta. Use it for **persisted / protocol / queuing** dates -- `createdAt`, `updatedAt`,
     * `touchedAt` -- because a change-driving queue keyed on them needs a monotonic, instance-consistent value
     * that different in-flight requests cannot each shift out from under each other.
     */
    fun instanceNow(): Instant
}
