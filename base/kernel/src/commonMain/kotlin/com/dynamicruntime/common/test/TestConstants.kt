package com.dynamicruntime.common.test

/**
 * Wire constants for the test-only endpoints (issue #125): shared in the KMP kernel so the endpoint that
 * defines them and the helpers/clients that call them (e.g. `TestUser` in base:common) use the same strings.
 * These endpoints exist only on a test instance (see `KdrInstanceConfig.isTestInstance`).
 */
@Suppress("ConstPropertyName")
object TEP {
    /** Create-or-find a user by email and immediately become them (a `forTestingOnly` POST). */
    const val becomeUser = "/test/becomeUser"

    const val email = "email"
    const val grantAdmin = "grantAdmin"
    const val failIfUserAlreadyExists = "failIfUserAlreadyExists"
}
