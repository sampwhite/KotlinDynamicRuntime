package com.dynamicruntime.common.logging

/**
 * The logging severity levels the code base emits against. Our own two-way (KMP) logging speaks entirely in
 * [LogLevel] -- on both backend and frontend -- so nothing above the logging layer depends on any particular
 * logging implementation.
 *
 * Ordered from most to least verbose, so `level.ordinal` drives the threshold check (a message is emitted when
 * its level is at or above the configured threshold). [off] is not message severity -- it exists for
 * configuration, to silence a logger entirely.
 *
 * Entry names are lowerCamelCase to match the code base's enum convention (see `ExpectedVal`, `SchFailCode`),
 * so they read the same at a call site as in config.
 */
@Suppress("EnumEntryName")
enum class LogLevel {
    trace,
    debug,
    info,
    warn,
    error,
    off,
}
