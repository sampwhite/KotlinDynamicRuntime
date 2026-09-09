package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.toOptStr

/**
 * The configuration **protection tier** (issue #617), read and written on the [GCT.gedraConfigControl] table.
 *
 * There are three tiers, and they are one ladder: a client consumes the latest revision (**free**), or only its
 * latest **published** revision (**published-only**), the runtime state toggled per client per environment; and
 * `ClientDef.staticConfig` is the source-code **static** tier -- published-only that cannot be toggled off, for
 * a client whose production configuration comes from source with unit tests against it. The word "protected" is
 * avoided on purpose: `AdminEndpoints` already uses `selfProtected` for self-role edits, and one word reading
 * two ways is what #611 asked to settle.
 *
 * What the runtime **consumes** is a single question -- latest, or latest-published -- so the tier collapses to
 * one boolean at the point it matters:
 *
 * ```
 * publishedOnly(client) = staticConfig(client) || the toggled state for (client, environment)
 * ```
 *
 * so `static` is exactly `published-only` a client cannot turn off. The loader (#614) and the reload (#616)
 * consult this; the config-editing reads (`readLatest`, `listConfigs`) do **not** -- an administrator editing a
 * client's config must still see the editable latest, whatever tier the client runs at.
 *
 * These are free functions taking the topic's [SqlCxt] and table, because the boot loader reaches the table
 * through its own bootstrap (before the schema store exists) while the endpoint and the reload reach it through
 * the service -- and both must resolve the tier the same way.
 */
object GedraConfigControl {
    /**
     * The clients that consume published-only in [env] by their **toggled state** alone (issue #617) -- one row
     * per client, read as a set. `staticConfig` is not here: it is a source-code fact, folded in by
     * [staticClients], so a static client with no toggle row is still treated as published-only.
     */
    fun publishedOnlyClients(cxt: KdrCxt, sqlCxt: SqlCxt, table: KdrTable, env: String): Set<String> {
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraConfigControlByEnv", table.columns,
            "select * from t:${GCT.gedraConfigControl} where c:${GC.environment} = :${GC.environment} " +
                "and c:${PF.enabled} = true and c:${GC.publishedOnly} = true",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(GC.environment to env))
        }
        return rows.mapNotNull { it[PF.client].toOptStr() }.toSet()
    }

    /** Whether [client] is toggled published-only in [env] by its stored state alone (ignores `staticConfig`). */
    fun isToggledPublishedOnly(cxt: KdrCxt, sqlCxt: SqlCxt, table: KdrTable, client: String, env: String): Boolean {
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraConfigControlOne", table.columns,
            "select * from t:${GCT.gedraConfigControl} where c:${PF.client} = :${PF.client} " +
                "and c:${GC.environment} = :${GC.environment} and c:${PF.enabled} = true",
        )
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client, GC.environment to env)).firstOrNull()
        }
        return row?.get(GC.publishedOnly) == true
    }

    /**
     * Sets [client]'s published-only state in [env] (issue #617), an upsert under the row's own lock -- the same
     * shape `InstanceConfigService.setConfig` uses for a small keyed row. The caller has already refused a
     * `staticConfig` client (its tier is not the toggle's to change); this only records the runtime state.
     */
    fun setPublishedOnly(cxt: KdrCxt, sqlCxt: SqlCxt, table: KdrTable, client: String, env: String, value: Boolean) {
        val keys = mapOf(PF.client to client, GC.environment to env)
        val selectStmt = SqlTopicUtil.mkNamedTableSelectStmt(
            sqlCxt, "qGedraConfigControlPk", table, listOf(PF.client, GC.environment),
        )
        sqlCxt.sqlDb.withSession(cxt) {
            // Read enabled-only: a disabled row (a future clear-the-tier path) reads as absent, so the insert
            // path takes it and `prepForStdExecute` re-enables it -- an update would leave it disabled and every
            // reader, which filters on enabled, would ignore the toggle.
            val existing = sqlCxt.sqlDb.queryOneEnabled(cxt, selectStmt, keys)
            if (existing == null) {
                val data = mutableMapOf<String, Any?>(PF.client to client, GC.environment to env, GC.publishedOnly to value)
                SqlTopicUtil.prepForStdExecute(cxt, table, data)
                sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, table), data)
            } else {
                val stmt = SqlTopicUtil.mkPartialUpdateStmt(
                    sqlCxt, table, "uGedraConfigControl",
                    "c:${GC.publishedOnly} = :${GC.publishedOnly}",
                    "c:${PF.client} = :${PF.client} and c:${GC.environment} = :${GC.environment}",
                )
                val bind = mutableMapOf<String, Any?>(PF.client to client, GC.environment to env, GC.publishedOnly to value)
                SqlTopicUtil.prepForStdUpdate(cxt, table, bind, existing)
                sqlCxt.sqlDb.executeStatement(cxt, stmt, bind)
            }
        }
    }

    /**
     * The clients a source-code definition marks `staticConfig` (issue #617). Read from [sourceConfigs] -- the
     * configs that are **not** data-loaded -- because static is the source tier: a client cannot make itself
     * static through the very data the tier exists to stop it changing. The default is not static, so a client
     * with no source definition (one defined purely in data) is never static.
     */
    fun staticClients(sourceConfigs: List<GedraConfig>): Set<String> =
        sourceConfigs.mapNotNull { it.client }.filter { it.staticConfig }.map { it.clientId }.toSet()
}
