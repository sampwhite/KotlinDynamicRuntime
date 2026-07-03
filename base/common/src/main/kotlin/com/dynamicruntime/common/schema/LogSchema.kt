package com.dynamicruntime.common.schema

import com.dynamicruntime.common.logging.KdrLogger

/**
 * Topic logger for the schema subsystem -- parsing, validation, and the schema
 * store. It lives beside the code it serves (rather than in the central `logging`
 * package) because the `"schema"` topic is owned by this one subsystem. That is the
 * default placement rule for topic loggers; see
 * [com.dynamicruntime.common.logging.LogStartup] for the cross-cutting exception.
 */
object LogSchema : KdrLogger("schema")
