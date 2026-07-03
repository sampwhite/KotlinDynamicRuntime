package com.dynamicruntime.common.logging

/**
 * Topic logger for application startup and other general/cross-cutting runtime
 * activity. It lives in the central `logging` package (rather than in a single
 * subsystem) precisely because startup logging is emitted from many disparate
 * places -- the launcher, and eventually instance registration, component
 * initialization, and shutdown.
 *
 * This is the placement rule for topics whose use spreads across packages; contrast
 * with [com.dynamicruntime.common.schema.LogSchema], which is owned by one subsystem
 * and lives beside it.
 */
object LogStartup : KdrLogger("startup")
