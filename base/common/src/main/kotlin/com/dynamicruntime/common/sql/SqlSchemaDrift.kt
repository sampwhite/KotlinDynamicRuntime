package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.util.toOptBool

/**
 * One live database column, as JDBC metadata describes it (issue #216). [SqlTableUtil] reads these while
 * reconciling a table; [SqlSchemaDrift] reads the same rows to answer the question the reconciliation never
 * asks -- what is in the database that the code does *not* declare.
 *
 * [nullable] and [hasDefault] are three-valued in JDBC only in the nullability case, and an unknown there is
 * treated as "nullable": every check below fires on a *definite* NOT NULL, so an ambiguous answer produces
 * silence rather than a false alarm on a driver that does not say.
 */
class DbColumnInfo(val name: String, val nullable: Boolean, val hasDefault: Boolean)

/**
 * Detects schema drift between a [KdrTable] declaration and the live database, at the moment [SqlTableUtil]
 * already has the column metadata in hand (issue #216).
 *
 * The reconciliation in `createTable` only ever adds what is missing. It never looks the other way, so a
 * **renamed column strands its predecessor**: the new name is added, nullable, and the old one stays behind,
 * `NOT NULL` and unpopulated. Startup is clean, reads keep working -- and every generated insert fails, since
 * the framework does not know the column is there to fill it. The `account` -> `client` rename put a live
 * deployment in exactly that state, and the failure surfaced a day later as
 * `Could not execute query iAuthUserDevices`, which names the statement and not one word of the cause.
 *
 * **Detect, never repair.** The framework cannot know that `client` was once `account` -- they are unrelated
 * strings -- and a guess would move production data. Renames stay a written migration; this only tells you.
 */
object SqlSchemaDrift {
    /**
     * Checks [existing] (the live columns, keyed by database column name) against [tableDef] and reports what
     * it finds: the blocking case throws, the unbackfilled case warns.
     *
     * Call it with the metadata read **before** any `ALTER TABLE ... ADD COLUMN` this boot performs. That is
     * not an optimization -- it is what keeps [unbackfilledColumns] quiet about a column this very boot just
     * added, which is nullable for a good reason (the table has rows) and expected to be. Only a column that
     * was already there and is still nullable has outlived its backfill.
     */
    fun check(
        sqlCxt: SqlCxt,
        tableDef: KdrTable,
        dbTableName: String,
        existing: Map<String, DbColumnInfo>,
        aliases: SqlColumnAliases,
    ) {
        val cxt = sqlCxt.cxt
        val blocking = strandedBlockingColumns(tableDef, existing, aliases)
        val unbackfilled = unbackfilledColumns(tableDef, existing, aliases)

        if (unbackfilled.isNotEmpty()) {
            // A warning and never fatal: the deployment works, and this is the state a correct rollout passes
            // through. It matters because the drift does not sit still -- see the class comment on `client`.
            LogSql.warn(cxt) {
                "Table $dbTableName has ${unbackfilled.size} column(s) the code declares required that the " +
                    "database still allows to be null: ${unbackfilled.joinToString(", ")}. That is expected " +
                    "just after a deploy that added them (ALTER TABLE ADD COLUMN cannot say NOT NULL on a " +
                    "table with rows) and means a backfill never happened if it persists. Backfill the " +
                    "column and apply the NOT NULL by hand."
            }
        }
        if (blocking.isEmpty()) {
            return
        }

        val detail = blocking.joinToString("; ") { col ->
            "$dbTableName.$col is NOT NULL with no default but is not declared by the code"
        }
        val message = "Schema drift on $dbTableName would make every insert fail: $detail. " +
            "Nothing the framework generates populates such a column, so writes to this table are already " +
            "broken -- most likely a rename whose migration was never finished, leaving the old column " +
            "behind. Drop it, or give it a default, or declare it. Set ${DbEnv.allowSchemaDrift}=true to " +
            "boot anyway (writes will still fail) while a migration is in progress."

        if (isDriftAllowed(cxt)) {
            // Logged at error rather than warn: this is not a caveat, it is a broken deployment that somebody
            // has asked to start regardless.
            LogSql.error(cxt, message)
            return
        }
        throw KdrException(message, null, EXC.internalError, SRC.database, ACT.general)
    }

    /**
     * Columns the database has, the declaration does not, that are `NOT NULL` with no default -- the ones
     * that make every generated insert fail.
     *
     * An undeclared column that is nullable or defaulted is deliberately **not** reported. It costs the
     * framework nothing and may well be a DBA's own; failing on it would turn this check into something
     * operators route around, and a check that gets disabled detects nothing.
     */
    fun strandedBlockingColumns(
        tableDef: KdrTable,
        existing: Map<String, DbColumnInfo>,
        aliases: SqlColumnAliases,
    ): List<String> {
        val declared = tableDef.columns.map { aliases.getColumnName(it.name) }.toSet()
        return existing.values
            .filter { it.name !in declared && !it.nullable && !it.hasDefault }
            .map { it.name }
            .sorted()
    }

    /**
     * Columns the declaration marks required that the live database still allows to be null -- the fingerprint
     * of an `ALTER TABLE ... ADD COLUMN`, which cannot say `NOT NULL` on a table that already has rows.
     *
     * Transient by nature and so only ever a warning. What makes it worth reporting at all is the second-order
     * damage: while `client` was nullable and unbackfilled, `AuthUserRow.extract` read NULL and defaulted it
     * to `""`, and every row touched after that was written back holding an empty string. The drift converted
     * rows from *obviously* unmigrated into *plausibly* migrated, so an `IS NULL` backfill would skip them and
     * a following `SET NOT NULL` would still pass.
     */
    fun unbackfilledColumns(
        tableDef: KdrTable,
        existing: Map<String, DbColumnInfo>,
        aliases: SqlColumnAliases,
    ): List<String> = tableDef.columns
        .filter { it.required }
        .mapNotNull { col -> existing[aliases.getColumnName(col.name)]?.takeIf { it.nullable }?.name }
        .sorted()

    /** Whether an operator has asked to boot despite blocking drift (see [DbEnv.allowSchemaDrift]). */
    fun isDriftAllowed(cxt: KdrCxt): Boolean = cxt.getEnvVar(DbEnv.allowSchemaDrift)?.toOptBool() ?: false
}
