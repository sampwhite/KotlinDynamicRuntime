package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC

/**
 * Turns a [ReadScope] into SQL (issue #225): the one place that knows how "which rows may this caller see"
 * becomes a `where` clause, so a new scoped query gets the rules by construction rather than by remembering
 * them.
 *
 * The alternative -- each query composing its own conditions -- is how a scoped read quietly stops being
 * scoped: the omission is invisible at the call site, and the query keeps working. Here a caller that fails to
 * account for a constrained dimension gets an exception instead of a wider answer.
 */
object SqlScopeUtil {
    /**
     * The SQL conditions that confine a query on [table] to [scope], binding each value into [data]. Returns
     * an empty list for an unrestricted scope, so a caller needs no branch on whether it is confined.
     *
     * Which column answers which dimension is fixed: [PF.client], [PF.org], [PF.userId] -- the protocol
     * columns the ownership table features add. A table that declares one of those names itself (as
     * `AuthUsers` does for `userId`) is answered by it, since the name *is* the contract.
     *
     * **A constrained dimension the table cannot express is an error, not a pass.** Silently dropping the
     * condition is the failure that matters here: the query succeeds, returns rows outside the scope, and
     * nothing says so. A caller that genuinely handles a dimension another way names it in
     * [filteredAfterQuery] -- which `UserService.listUsers` does for the organization, because a user's lives
     * in a JSON blob and cannot be a predicate. That parameter is the only way past the check, and spelling it
     * out at the call site is the point: it reads as an admission rather than an oversight.
     *
     * The organization condition is the lenient one (`ReadScope.admitsOrg`): a row with no organization
     * belongs to the client as a whole and stays visible, so it matches `org is null or org = ?`. Strict
     * equality would make every row a client wrote before adopting organizations vanish the moment somebody
     * was given one.
     */
    fun scopeConditions(
        scope: ReadScope,
        table: KdrTable,
        data: MutableMap<String, Any?>,
        filteredAfterQuery: Set<String> = emptySet(),
    ): List<String> {
        // The bind parameter for each dimension is named for its **column**, not a distinct `scope*` name. The
        // binder types a parameter from the column it matches by name (SqlStmtUtil.getColumn), and a name that
        // matches nothing falls back to a string column -- which silently binds the bigint `userId` as text, so
        // Postgres refuses `userId = <varchar>` (H2 coerces and hides it). Naming the param for the column is
        // how every other query here types correctly, and an ownership column is never also in a SET, so it
        // cannot collide with another bind of the same name.
        val conditions = mutableListOf<String>()
        if (scope.client != null && table.demandsScopeColumn(PF.client, filteredAfterQuery)) {
            conditions.add("c:${PF.client} = :${PF.client}")
            data[PF.client] = scope.client
        }
        if (scope.org != null && table.demandsScopeColumn(PF.org, filteredAfterQuery)) {
            conditions.add("(c:${PF.org} is null or c:${PF.org} = :${PF.org})")
            data[PF.org] = scope.org
        }
        if (scope.userId != null && table.demandsScopeColumn(PF.userId, filteredAfterQuery)) {
            conditions.add("c:${PF.userId} = :${PF.userId}")
            data[PF.userId] = scope.userId
        }
        return conditions
    }

    /**
     * A ready-made `where` clause for [scope] -- [scopeConditions] joined, with the leading keyword, or the
     * empty string when nothing confines the query. For a query with conditions of its own, compose
     * [scopeConditions] into that list instead.
     */
    fun scopeWhereClause(
        scope: ReadScope,
        table: KdrTable,
        data: MutableMap<String, Any?>,
        filteredAfterQuery: Set<String> = emptySet(),
    ): String {
        val conditions = scopeConditions(scope, table, data, filteredAfterQuery)
        return if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")
    }

    /**
     * Whether [column] should become a predicate: true when the table carries it, false when the caller
     * declared it handles the dimension itself, and otherwise a throw.
     */
    private fun KdrTable.demandsScopeColumn(column: String, filteredAfterQuery: Set<String>): Boolean {
        if (columnsByName.containsKey(column)) return true
        if (column in filteredAfterQuery) return false
        throw KdrException(
            "Table $tableName has no '$column' column, so a read scope constraining it cannot be applied. " +
                "Either give the table the matching ownership feature, or name '$column' in " +
                "filteredAfterQuery to declare that the caller filters that dimension itself.",
            null, EXC.internalError, SRC.database, ACT.general,
        )
    }
}
