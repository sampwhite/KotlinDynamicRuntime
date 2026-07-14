package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The user/auth service (issue #67): owns the [AuthFormHandler] and the SQL access to the `AuthUsers` and
 * `AuthUserDevices` tables. Ported from dn's `UserService` + `AuthQueryHolder`, using kd2's topic/table SQL
 * layer. Registered by the `common` component; found via [get].
 */
class UserService : ServiceInitializer {
    override val serviceName: String = UserService.serviceName

    lateinit var authFormHandler: AuthFormHandler

    override fun checkInit(cxt: KdrCxt) {
        if (::authFormHandler.isInitialized) return
        val node = NodeService.get(cxt) ?: throw KdrException("NodeService required for UserService.")
        val mail = MailService.get(cxt) ?: throw KdrException("MailService required for UserService.")
        authFormHandler = AuthFormHandler(this, node, mail)
    }

    // --- AuthUsers queries --------------------------------------------------

    private fun authUsersTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.authUsers]
        ?: throw KdrException("AuthUsers table is not registered in the schema store.")

    private fun authUserDevicesTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.authUserDevices]
        ?: throw KdrException("AuthUserDevices table is not registered in the schema store.")

    fun queryByPrimaryId(cxt: KdrCxt, primaryId: String): AuthUserRow? = queryOne(cxt, AU.primaryId, primaryId)

    fun queryByUsername(cxt: KdrCxt, username: String): AuthUserRow? = queryOne(cxt, AU.username, username)

    fun queryByUserId(cxt: KdrCxt, userId: Long): AuthUserRow? = queryOne(cxt, AU.userId, userId)

    /** Selects a single `AuthUsers` row by an indexed [field], or null. Returns the row even if disabled. */
    private fun queryOne(cxt: KdrCxt, field: String, value: Any?): AuthUserRow? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val stmt = SqlTopicUtil.mkNamedTableSelectStmt(sqlCxt, "qAuthUsersBy_$field", table, listOf(field))
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(field to value))
        }
        return row?.let { AuthUserRow.extract(it) }
    }

    /** Inserts a new `AuthUsers` row (protocol columns stamped), returning the generated `userId`. */
    fun insertUser(cxt: KdrCxt, data: Map<String, Any?>): Long {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val row = data.toMutableMap()
        SqlTopicUtil.prepForStdExecute(cxt, table, row) // stamps enabled=true, account, audit columns
        val counter = LongArray(1)
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatementGetCounterBack(cxt, stmt, row, counter)
        }
        return counter[0]
    }

    /** Writes [row] back to its `AuthUsers` record (by `userId`), re-stamping protocol columns. */
    fun updateUser(cxt: KdrCxt, row: AuthUserRow) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val stmt = SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
        val data = row.toMap().toMutableMap()
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(cxt, stmt, data)
        }
    }

    // --- AuthUserDevices recording ------------------------------------------

    /**
     * Records a device the user logged in from, if not already recorded (minimal for Piece 1 -- the familiar
     * device step-up gate and the multi-IP/agent merge come with passwords in a follow-up). Inserts a row
     * keyed by ([userId], [deviceGuid]) capturing the [ipAddress] and [userAgent]. Absent/duplicate is
     * tolerated: a device already recorded is left alone.
     */
    fun recordDevice(cxt: KdrCxt, userId: Long, deviceGuid: String, ipAddress: String?, userAgent: String?) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUserDevicesTable(cxt)
        val selectStmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        val insertStmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val key = mapOf(AU.userId to userId, AUD.deviceGuid to deviceGuid)
        sqlCxt.sqlDb.withSession(cxt) {
            if (sqlCxt.sqlDb.queryOneStatement(cxt, selectStmt, key) != null) return@withSession
            val row = mutableMapOf<String, Any?>(
                AU.userId to userId,
                AUD.deviceGuid to deviceGuid,
                AUD.deviceData to mapOf("ipAddress" to ipAddress, "userAgent" to userAgent),
                AUD.deviceVerified to false,
            )
            SqlTopicUtil.prepForStdExecute(cxt, table, row)
            sqlCxt.sqlDb.executeStatement(cxt, insertStmt, row)
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "UserService"

        fun get(cxt: KdrCxt): UserService? = cxt.instanceConfig.get(serviceName) as? UserService
    }
}
