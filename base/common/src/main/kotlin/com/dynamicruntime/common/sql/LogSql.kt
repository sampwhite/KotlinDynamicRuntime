package com.dynamicruntime.common.sql

import com.dynamicruntime.common.logging.KdrLogger

/**
 * Topic logger for the SQL/database subsystem — connection pooling, statement execution, and table
 * creation. Lives beside the code it serves because the `"sql"` topic is owned by this one subsystem (the
 * default placement rule for topic loggers). Rewritten from the prior-art `LogSql`/`AppLogger`.
 */
object LogSql : KdrLogger("sql")
