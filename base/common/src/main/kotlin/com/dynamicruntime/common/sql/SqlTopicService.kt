package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.ServiceInitializer
import java.util.concurrent.ConcurrentHashMap

/**
 * The operational database service: it maps topics to their runtime [SqlTopic] objects and databases,
 * driving table creation, while the table *definitions* live in the read-only schema store
 * ([com.dynamicruntime.common.context.KdrSchemaStore.tables], populated through the collector/`SchemaService`
 * pipeline, the same way endpoints are). A topic's tables are those in the store whose
 * [KdrTable.topic] matches.
 *
 * Rewritten from the prior-art `SqlTopicService`, with the shard dimension removed. Config-driven database
 * selection (file H2 / Postgres, secrets) arrives in a later pass; for now every database is in-memory H2.
 */
class SqlTopicService : ServiceInitializer {
    override val serviceName: String = SqlTopicService.serviceName

    val topics: MutableMap<String, SqlTopic> = ConcurrentHashMap()
    val databases: MutableMap<String, SqlDatabase> = ConcurrentHashMap()

    var isInMemory: Boolean = true

    override fun checkInit(cxt: KdrCxt) {
        isInMemory = (cxt.instanceConfig.get(ACFG.inMemoryOnly) as? Boolean) ?: DbEnv.resolveInMemoryOnly(cxt)
    }

    /**
     * Returns the runtime topic for [topicName], creating it (and its tables) on first use. Returns null if
     * no table in the schema store declares that topic.
     */
    fun getOrCreateTopic(cxt: KdrCxt, topicName: String): SqlTopic? {
        topics[topicName]?.let { return it }
        synchronized(topics) {
            topics[topicName]?.let { return it }
            val tables = cxt.getSchema().tables.values.filter { it.topic == topicName }
            if (tables.isEmpty()) {
                return null
            }
            val db = getOrCreateDatabase(cxt)
            val sqlTopic = SqlTopic(topicName, db, tables)
            sqlTopic.init(SqlCxt(cxt, sqlTopic))
            topics[topicName] = sqlTopic
            return sqlTopic
        }
    }

    /**
     * Resolves the (single, shared) database for this instance, building it from the resolved configuration
     * on first use. Configuration comes from an explicit `db` config, else the `KDR_DB_*` environment, else
     * [isInMemory] (which, when true, forces in-memory H2 — see [SqlDbBuilder.resolveDbConfig]).
     */
    fun getOrCreateDatabase(cxt: KdrCxt): SqlDatabase {
        val config = SqlDbBuilder.resolveDbConfig(cxt, isInMemory)
        val dbName = SqlDbBuilder.dbNameOf(config)
        return databases.computeIfAbsent(dbName) { SqlDbBuilder.createDatabase(cxt, it, config) }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "SqlTopicService"

        /** Retrieves the topic service from the instance config, or null if absent. */
        fun get(cxt: KdrCxt): SqlTopicService? = cxt.instanceConfig.get(serviceName) as? SqlTopicService

        /** Builds a [SqlCxt] bound to [topic], resolving (and initializing) the topic through the service. */
        fun mkSqlCxt(cxt: KdrCxt, topic: String): SqlCxt {
            val service = get(cxt)
                ?: throw KdrException("Could not create SQL context for topic $topic: no SqlTopicService.")
            val sqlTopic = service.getOrCreateTopic(cxt, topic)
                ?: throw KdrException("Could not create SQL context: no tables registered for topic $topic.")
            return SqlCxt(cxt, sqlTopic)
        }

        /**
         * The topic-service endpoints: a list-tables endpoint that dumps the table catalog from the schema
         * store, mirroring `SchemaService`'s `/schema/endpoints`. Defined with the service that owns it.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "sql") {
            // The TableInfo type is owned by KdrTable, alongside its serialization (toJsonMap).
            KdrTable.defineInfoType(this)
            listEndpoint(
                "/db/tables",
                "Lists the database tables registered for this instance.",
                outputRef = KdrTable.infoTypeName,
            ) { c, _ -> listTables(c) }
        }

        /** Handler for `/db/tables`: dump every registered table's attributes, sorted by name. */
        fun listTables(cxt: KdrCxt): List<Map<String, Any?>> =
            cxt.getSchema().tables.values
                .sortedBy { it.tableName }
                .map { it.toJsonMap() } // KdrTable owns its own serialization
    }
}
