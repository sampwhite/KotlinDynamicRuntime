package com.dynamicruntime.common.logging

/**
 * The logging severity levels the code base emits against. This is *our own* enum
 * rather than the underlying logging library's level type: call sites and
 * configuration speak in [LogLevel], and the (single, private) translation to the
 * backing library lives in [toLog4j]. That indirection isolates the rest of the
 * code base from the logging implementation, so swapping or wrapping the backend
 * later touches only the `logging` package.
 *
 * Ordered from most to least verbose. [off] is not message severity -- it exists
 * for configuration, to silence a logger entirely.
 *
 * Entry names are lowerCamelCase to match the code base's enum convention (see
 * `ExpectedVal`, `SchFailCode`), so they read the same at a call site as in config.
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
