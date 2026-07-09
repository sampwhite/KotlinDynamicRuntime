package com.dynamicruntime.common.sql

/**
 * Database-dialect options that vary by the kind of database in use. The defaults describe H2 (case-folding
 * identifiers, `auto_increment` counters); the PostgreSQL builder flips [hasSerialType] and
 * [storesLowerCaseIdentifiersInSchema] on.
 */
class SqlDbOptions {
    /** Whether the database preserves the case of identifiers (H2/Postgres do not by default). */
    var identifiersCaseSensitive: Boolean = false

    /** Whether to use a Postgres-style `timestamp with time zone` column for dates. */
    var useTimezoneWithTz: Boolean = false

    /** Whether the database has a `serial`/`bigserial` auto-increment type (Postgres) vs `auto_increment` (H2). */
    var hasSerialType: Boolean = false

    /** Whether the database stores unquoted identifiers in lower case (Postgres) vs. upper case (H2). */
    var storesLowerCaseIdentifiersInSchema: Boolean = false
}
