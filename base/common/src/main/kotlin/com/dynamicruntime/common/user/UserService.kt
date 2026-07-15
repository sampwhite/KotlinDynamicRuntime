package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toOptInstant
import kotlin.time.Instant

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

    /**
     * Resolves a login identifier that is *either* a username *or* a primary contact (email): looks up by
     * username first, then by primaryId. The two spaces are disjoint -- a valid username cannot contain '@'
     * and an email must -- so the fallback is unambiguous. This lets a username-less frontend log a returning
     * user in by email while the backend keeps full username support (issue #70).
     */
    fun queryByLoginId(cxt: KdrCxt, loginId: String): AuthUserRow? =
        queryByUsername(cxt, loginId) ?: queryByPrimaryId(cxt, loginId)

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

    // --- AuthUserDevices: familiar-device trust -----------------------------

    /**
     * Records the device a user logged in from, and -- when [markTrusted] -- marks it *familiar* (verified)
     * with a fresh [AUTHC.deviceTrustMillis] expiration. Only a verification-code login sets [markTrusted]
     * (see KdrRequest.trustDevice); a password login records presence but never grants trust. Upserts the
     * row keyed by ([userId], [deviceGuid]): an existing untrusted row is left untouched when there is no
     * trust to grant. The multi-IP/user-agent merge is still deferred; deviceData holds the latest only.
     */
    fun recordDevice(
        cxt: KdrCxt, userId: Long, deviceGuid: String, ipAddress: String?, userAgent: String?, markTrusted: Boolean,
    ) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUserDevicesTable(cxt)
        val key = mapOf(AU.userId to userId, AUD.deviceGuid to deviceGuid)
        sqlCxt.sqlDb.withSession(cxt) {
            val existing = sqlCxt.sqlDb.queryOneStatement(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), key)
            if (existing != null && !markTrusted) return@withSession // presence already recorded; nothing to add
            val expiration = if (markTrusted) {
                Instant.fromEpochMilliseconds(cxt.now().toEpochMilliseconds() + AUTHC.deviceTrustMillis)
            } else {
                null
            }
            val row = mutableMapOf(
                AU.userId to userId,
                AUD.deviceGuid to deviceGuid,
                AUD.deviceData to mapOf("ipAddress" to ipAddress, "userAgent" to userAgent),
                AUD.deviceVerified to markTrusted,
                AUD.verifyExpiration to expiration,
            )
            SqlTopicUtil.prepForStdExecute(cxt, table, row)
            val stmt = if (existing != null) SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
            else SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
            sqlCxt.sqlDb.executeStatement(cxt, stmt, row)
        }
    }

    /**
     * Whether [deviceGuid] is a *familiar* device for [userId]: a recorded row that is verified and whose
     * trust has not expired. This is the hard precondition for password login (issue #69) -- an unfamiliar
     * device cannot use a password at all and must fall back to a verification code.
     */
    fun isDeviceTrusted(cxt: KdrCxt, userId: Long, deviceGuid: String): Boolean {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUserDevicesTable(cxt)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(AU.userId to userId, AUD.deviceGuid to deviceGuid))
        }
        val r = row ?: return false
        if (r[AUD.deviceVerified] != true) return false
        val expiration = r[AUD.verifyExpiration].toOptInstant() ?: return false
        return cxt.now() <= expiration
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "UserService"

        fun get(cxt: KdrCxt): UserService? = cxt.instanceConfig.get(serviceName) as? UserService
    }
}
