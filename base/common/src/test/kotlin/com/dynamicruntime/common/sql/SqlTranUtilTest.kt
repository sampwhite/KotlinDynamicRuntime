package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.job.jobTables
import com.dynamicruntime.common.job.jobTopic
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly

/**
 * The topic-transaction retry loop (`SqlTranUtil.doTran`), driven by a scripted provider so a race between
 * writers can be replayed exactly rather than hoped for.
 */
class SqlTranUtilTest : StringSpec({
    fun sqlCxt(): SqlCxt {
        val cxt = KdrCxt.mkSimpleCxt("sqlTranUtilTest")
        cxt.instanceConfig.put(KdrSchemaStore.key, KdrSchemaStore(tables = jobTables(cxt).associateBy { it.tableName }))
        val service = SqlTopicService()
        cxt.instanceConfig.put(SqlTopicService.serviceName, service)
        service.checkInit(cxt)
        return SqlTopicService.mkSqlCxt(cxt, jobTopic)
    }

    "an insert beaten by a concurrent writer goes back to taking the lock rather than inserting again" {
        // The race, as a writer sees it: no row to lock, so it inserts -- but another writer inserted the row first,
        // so the insert fails on the unique key; the row is now there, so the lock succeeds (issue #872).
        val calls = mutableListOf<String>()
        var locks = 0
        val provider = object : SqlTranExecProvider {
            override fun lock(): Boolean {
                calls.add("lock")
                return ++locks > 1
            }

            override fun insert() {
                calls.add("insert")
                throw KdrException("duplicate key value violates unique constraint", null, EXC.internalError, SRC.database, ACT.general)
            }

            override fun execute() {
                calls.add("execute")
            }
        }
        SqlTranUtil.doTran(sqlCxt(), "raceTest", provider)
        calls shouldContainExactly listOf("lock", "insert", "lock", "execute")
    }
})
