package com.dynamicruntime.common.logging

/**
 * The immutable, already-evaluated snapshot of one log event (issue #79). Everything a sink needs is captured
 * here **at log time**, on the calling thread -- so an async sink can format/write it later without reading
 * back into a mutable context. Shared by every sink (stdout, console, a future OpenSearch sink).
 */
class LogRecord(
    val level: LogLevel,
    /** The flat topic the emitting logger is bound to (e.g. `"schema"`). */
    val topic: String,
    /** Event time in epoch milliseconds, taken from the context clock at log time. */
    val timeMs: Long,
    /** The rendered message (lazy overloads have already been invoked). */
    val message: String,
    /** Platform context captured at log time (see [LogContext]); null when logged outside any context. */
    val context: LogContext?,
    /** An associated throwable, or null. `Throwable` is multiplatform, so it rides to any sink. */
    val cause: Throwable?,
    /** Structured key/values for machine sinks (e.g., a future OpenSearch bulk document); ignored by text sinks. */
    val data: Map<String, Any?>?,
)

/**
 * The bits of the emitting context worth logging, snapshotted to plain strings at log time, so nothing mutable
 * (or platform-specific) is read later. The backend fills both; the frontend typically leaves [thread] null.
 */
class LogContext(
    /** A short label identifying the context/actor, e.g. `[instance:loggingId(authId)]`; null if none. */
    val label: String?,
    /** The emitting thread's name (backend only; null on single-threaded JS). */
    val thread: String?,
)
