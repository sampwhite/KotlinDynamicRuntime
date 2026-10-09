package com.dynamicruntime.common.node

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.TOPIC
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.mkEncryptionKey
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.RandomUtil
import com.dynamicruntime.common.util.base64Encode

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
 * for other services' `checkInit`: the whole `onCreate` pass completes before any `checkInit` runs. It loads
 * or creates the persistent encryption key and hands it to [NodeService]. The table is the `node` topic's
 * transactional lock table, so writes go through the idempotent topic transaction.
 *
 * This service used to be where the runtime first touched the database, and where the `InstanceConfig` table
 * got created as a side effect. Since issue #162 the startup-tier [SqlTopicService] creates every topic's
 * tables in its `checkReady`, so by the time this runs the table already exists and the connection is already
 * open -- table creation is system work done at startup, never a side effect of the first thing to need a
 * table.
 */
class InstanceConfigService : ServiceInitializer {
    override val serviceName: String = InstanceConfigService.serviceName

    @KdrPrivate
    var nodeService: NodeService? = null

    /**
     * The id of the data this node serves (issue #1099): random, written to the `InstanceConfig` table the first time
     * a database is initialized and read back on every boot after. So it changes exactly when the data does -- every
     * boot of an in-memory database, which starts empty, and never across restarts on a persistent one (an H2 file
     * store, Postgres), whose data survives them. Stored database-wide ([databaseWide]), so every instance and node
     * using the database reads the same row. Not a secret: a test
     * instance serves it in the app config, so a browser can tell whether what it remembers of the data still holds.
     */
    var dataId: String = ""
        private set

    override fun onCreate(cxt: KdrCxt) {
        val node = NodeService.get(cxt)
        nodeService = node
        // Do the "database" work in onCreate (not "checkInit"), so the encryption key and config access are ready
        // for other services' "checkInit". SqlTopicService is a *startup* service (see CommonComponent), so it is
        // fully initialized -- its database configuration resolved -- before this regular service's onCreate.
        val authKey = node.instanceAuthConfigKey
        // Reads (and, on a fresh instance, creates) the encryption key. The InstanceConfig table it reads was
        // already created by SqlTopicService's startup-tier checkReady (issue #162). Created under the row's lock, so
        // nodes starting together on a fresh database agree on one key rather than each keeping its own -- which
        // would leave each unable to read what the others encrypt.
        val encryptionKey = getOrCreateConfig(cxt, authConfigType, authKey, encryptionKeyField) {
            LogStartup.info(cxt, "Storing a new shared encryption key for instance '${cxt.instanceConfig.instanceName}'.")
            mkEncryptionKey()
        }
        node.registerEncryptionKey(authKey, encryptionKey)
        // Database-wide, not under this instance's name: the data it identifies is the database's, whatever names
        // the instances reading it go by. Created under the row's lock, as the key is, so every node reads one id.
        dataId = getOrCreateConfig(cxt, dataIdConfigType, dataIdConfigName, dataIdField, databaseWide) {
            RandomUtil.bytes(dataIdBytes).base64Encode()
        }
    }

    /**
     * The value at [field] in the [configName] row, created by [create] if the row does not hold one yet -- once,
     * whoever asks first. A stored value is read without a lock; a missing one is written in a topic transaction on
     * the row itself, which reads the row again under its lock and keeps a value another node stored in the
     * meantime. So nodes starting together on a fresh database agree on one value, where reading first and writing
     * after would leave each with its own, the last write winning in the database. A row holding data but not
     * [field] is a damaged row, and refused rather than overwritten.
     */
    fun getOrCreateConfig(
        cxt: KdrCxt,
        configType: String,
        configName: String,
        field: String,
        instanceName: String = cxt.instanceConfig.instanceName,
        create: () -> String,
    ): String {
        fun valueIn(data: Any?): String? {
            val map = data?.toJsonMap() ?: return null
            return map[field] as? String
                ?: throw KdrException("Stored config '$configName' holds data but no '$field'.")
        }
        getConfig(cxt, configName, instanceName)?.let { row -> valueIn(row[IC.configData])?.let { return it } }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, topic)
        val tranData = mapOf(IC.instanceName to instanceName, IC.configName to configName)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, "createInstanceConfig", null, tranData, tranTableName = tableName) {
            // The row as it stands under the lock: a value another node stored since the read above is kept.
            if (valueIn(sqlCxt.tranData[IC.configData]) != null) {
                sqlCxt.tranAlreadyDone = true
            } else {
                sqlCxt.tranData[IC.configType] = configType
                sqlCxt.tranData[IC.configData] = mapOf(field to create())
            }
        }
        return valueIn(sqlCxt.tranData[IC.configData])
            ?: throw KdrException("Stored config '$configName' has no '$field' after it was created.")
    }

    /** Upserts a configuration entry for this instance via the topic transaction. */
    fun setConfig(cxt: KdrCxt, configType: String, configName: String, data: Map<String, Any?>) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, topic)
        val tranData = mapOf(
            IC.instanceName to cxt.instanceConfig.instanceName,
            IC.configName to configName,
        )
        // Names its lock table: TOPIC.instance also carries the cache-state table, and this transaction has no
        // business serializing against cache announcements (issue #435).
        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, "setInstanceConfig", null, tranData, tranTableName = tableName,
        ) {
            sqlCxt.tranData[IC.configType] = configType
            sqlCxt.tranData[IC.configData] = data
        }
    }

    /**
     * Reads a configuration entry for this instance -- or, with [instanceName], another instance's key, such as
     * [databaseWide] -- or null if it does not exist. A row whose [PF.enabled] flag is not set (issue #48) is treated
     * as absent, so a disabled config entry reads back as null.
     */
    fun getConfig(cxt: KdrCxt, configName: String, instanceName: String = cxt.instanceConfig.instanceName): Map<String, Any?>? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, topic)
        val table = cxt.getGlobalSchema().tables[tableName]
            ?: throw KdrException("InstanceConfig table is not registered in the schema store.")
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        val keys = mapOf(
            IC.instanceName to instanceName,
            IC.configName to configName,
        )
        var result: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            result = sqlCxt.sqlDb.queryOneEnabled(cxt, stmt, keys)
        }
        return result
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "InstanceConfigService"
        const val topic = TOPIC.instance
        const val tableName = "InstanceConfig"

        /** Config type of the row that stores the instance's encryption/auth key. */
        const val authConfigType = "authConfig"

        /** Key, within an auth-config row's data map, that holds the encryption key. */
        const val encryptionKeyField = "encryptionKey"

        /** The row holding the [dataId] (issue #1099): its config type and name, and the field within its data. */
        const val dataIdConfigType = "dataIdentity"
        const val dataIdConfigName = "dataId"
        const val dataIdField = "dataId"

        /**
         * The instance-name key of a row that belongs to the database rather than to one instance (issue #1099): the
         * [dataId]'s. Not a name any instance takes.
         */
        const val databaseWide = "*database*"

        /** Random bytes in a [dataId]: enough that two databases never share one, short enough to compare at a glance. */
        private const val dataIdBytes = 9

        /** The service, or null on a node that does not run it. */
        fun getOrNull(cxt: KdrCxt): InstanceConfigService? = cxt.instanceConfig.get(serviceName) as? InstanceConfigService

        fun get(cxt: KdrCxt): InstanceConfigService = cxt.instanceConfig.get(serviceName) as? InstanceConfigService
            ?: throw KdrException("The $serviceName is not available on this node.")

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
