package com.dynamicruntime.common.node

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.mkEncryptionKey
import com.dynamicruntime.common.util.toJsonMap

/** Column-name keys for the InstanceConfig table. Each name matches its value. */
@Suppress("ConstPropertyName")
object IC {
    const val instanceName = "instanceName"
    const val configType = "configType"
    const val configName = "configName"
    const val configData = "configData"
}

/**
 * Owns the node's private, per-instance configuration: the `InstanceConfig` table (topic `node`), general
 * get/set access to it ([getConfig] / [setConfig]), and the startup bootstrap of the shared encryption key.
 *
 * Ported from CRDR's `DnNodeService` (the node-package service). In CRDR that service grew into the general
 * home for instance configuration, so kd2 keeps that role here; [NodeService] (CRDR's `DnCoreNodeService`)
 * deliberately holds only the loaded encryption key, which is all it ever needed from instance config.
 *
 * The database work runs in [onCreate] (not [checkInit]) so the encryption key and config access are ready
 * for other services' `checkInit`: the whole `onCreate` pass completes before any `checkInit` runs. So this
 * is where the runtime first touches the database -- it connects to the `node` topic's database, creates the
 * `InstanceConfig` table, and loads or creates the persistent encryption key, handing it to [NodeService].
 * The table is the `node` topic's transactional lock table, so writes go through the idempotent topic
 * transaction.
 */
class InstanceConfigService : ServiceInitializer {
    override val serviceName: String = InstanceConfigService.serviceName

    @KdrPrivate
    var nodeService: NodeService? = null

    override fun onCreate(cxt: KdrCxt) {
        val node = NodeService.get(cxt) ?: throw KdrException("NodeService is not available for InstanceConfigService.")
        nodeService = node
        // Do the "database" work in onCreate (not "checkInit"), so the encryption key and config access are ready
        // for other services' "checkInit". SqlTopicService is a *startup* service (see CommonComponent), so it is
        // fully initialized -- its database configuration resolved -- before this regular service's onCreate.
        val authKey = node.instanceAuthConfigKey
        // Reading (and, on a fresh instance, creating) the encryption key is the runtime's first contact with
        // the database: it connects to the node topic's database and creates the InstanceConfig table.
        val existing = getConfig(cxt, authKey)
        val encryptionKey = if (existing != null) {
            existing[IC.configData]?.toJsonMap()?.get(encryptionKeyField) as? String
                ?: throw KdrException("Stored auth config '$authKey' is missing its encryption key.")
        } else {
            val created = mkEncryptionKey()
            LogStartup.info(cxt, "Storing a new shared encryption key for instance '${cxt.instanceConfig.instanceName}'.")
            setConfig(cxt, authConfigType, authKey, mapOf(encryptionKeyField to created))
            created
        }
        node.registerEncryptionKey(authKey, encryptionKey)
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

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "InstanceConfigService"
        const val topic = "node"
        const val tableName = "InstanceConfig"

        /** Config type of the row that stores the instance's encryption/auth key. */
        const val authConfigType = "authConfig"

        /** Key, within an auth-config row's data map, that holds the encryption key. */
        const val encryptionKeyField = "encryptionKey"

        fun get(cxt: KdrCxt): InstanceConfigService? = cxt.instanceConfig.get(serviceName) as? InstanceConfigService

        /** The InstanceConfig table definition, contributed to the schema store by the `common` component. */
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
    }
}
