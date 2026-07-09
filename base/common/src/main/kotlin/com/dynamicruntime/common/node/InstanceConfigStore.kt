package com.dynamicruntime.common.node

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.tableModule

/** Column-name keys for the InstanceConfig table. Each name matches its value. */
@Suppress("ConstPropertyName")
object IC {
    const val instanceName = "instanceName"
    const val configType = "configType"
    const val configName = "configName"
    const val configData = "configData"
}

/**
 * The `InstanceConfig` table — private, per-instance configuration stored as small rows keyed by
 * (instanceName, configName). Ported from dn's node-package instance-config table (the `Dn` prefix dropped).
 * It is the `node` topic's transactional lock table, so writes go through the idempotent topic transaction,
 * which also exercises the database stack end-to-end (issue #33's stated validation use).
 */
@Suppress("ConstPropertyName")
object InstanceConfigStore {
    const val topic = "node"
    const val tableName = "InstanceConfig"

    /** The table definition, contributed to the schema store by the `common` component. */
    fun tables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "node", topic = topic) {
        table(tableName, "Stores private instance configuration data.") {
            column(IC.instanceName, "Unique identifier of the application instance.")
            column(IC.configType, "The type of configuration held in this row.")
            column(IC.configName, "The name of the configuration data.")
            column(IC.configData, "The configuration data for this entry.") { type = SCT.kObject }
            primaryKey(IC.instanceName, IC.configName)
            withTransactions()
        }
    }

    /** Upserts a configuration entry for this instance via the topic transaction. */
    fun setConfig(cxt: KdrCxt, configType: String, configName: String, data: Map<String, Any?>) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, topic)
        val tranData = mapOf(
            IC.instanceName to cxt.instanceConfig.instanceName,
            IC.configName to configName,
        )
        SqlTopicTranProvider.executeTopicTran(sqlCxt, "setInstanceConfig", null, tranData) {
            sqlCxt.tranData[IC.configType] = configType
            sqlCxt.tranData[IC.configData] = data
        }
    }

    /** Reads a configuration entry for this instance, or null if it does not exist. */
    fun getConfig(cxt: KdrCxt, configName: String): Map<String, Any?>? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, topic)
        val table = cxt.getSchema().tables[tableName]
            ?: throw KdrException("InstanceConfig table is not registered in the schema store.")
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        val keys = mapOf(
            IC.instanceName to cxt.instanceConfig.instanceName,
            IC.configName to configName,
        )
        var result: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            result = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, keys)
        }
        return result
    }
}
