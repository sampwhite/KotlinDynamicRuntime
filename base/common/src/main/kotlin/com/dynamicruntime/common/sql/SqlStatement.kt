package com.dynamicruntime.common.sql

/**
 * Wraps the data needed to generate and reuse a parameterized query. Rewritten from the prior-art
 * `DnSqlStatement`, with the shard dimension dropped (issue #33): the [sessionKey] now combines only [topic]
 * and [name].
 *
 * @see SqlBoundStatement for the actual prepared statement bound to a live connection.
 */
class SqlStatement(
    /** Topic the statement belongs to. */
    val topic: String,
    /** Unique name within the topic. */
    val name: String,
    /** The original SQL before entity (`t:`/`c:`/`:`) substitutions. */
    @Suppress("unused") val originalSql: String,
    /** The translated SQL to prepare (entities substituted, parameters turned into `?`). */
    val sql: String,
    /** Definitions of the columns referenced by the statement, keyed by field name. */
    val columns: Map<String, KdrColumn>,
    /** The bind parameter field names, in the order they appear in [sql]. */
    val bindFields: List<String>,
    /**
     * The logical table names the statement mentions, captured from its `t:` entity markers by
     * [SqlStmtUtil.prepareSql] -- so *any* statement knows what it touches, whether it was assembled by
     * [SqlTopicUtil]'s builders or written out by hand.
     *
     * This is what lets [SqlDatabase.publishWrite] tell a [SqlWriteListener] what a write touched, without
     * any caller having to remember to. Nearly always one name; a join or a multi-table statement yields
     * several.
     */
    val tableNames: List<String> = emptyList(),
) {
    /** Session key combining topic and name; used to find and reuse [SqlBoundStatement]s. */
    val sessionKey: String = "$name@$topic"

    /** Whether the prepared statement should request generated keys (for auto-increment inserts). */
    var returnGeneratedKeys: Boolean = false
}
