package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.util.toOptStr
import java.sql.SQLException

/** Table and column names for value claims (issue #1849). Each name matches its value. */
@Suppress("ConstPropertyName")
object VCL {
    const val formValueClaims = "FormValueClaims"

    /** What the claim is of: `counter:<counterId>` for a counter's id; a unique field's kind later (issue #1851). */
    const val claimKind = "claimKind"

    /** What the claim is unique within, beside its client: a counter's scope, or -- for a unique field -- its owner. */
    const val scopeKey = "scopeKey"

    /** The claimed value as its key: a counter's period and count, or a hash of a normalized value. */
    const val valueKey = "valueKey"

    /** The value as the form holds it, for a message naming it. */
    const val claimedValue = "claimedValue"

    /** The form that holds the value. */
    const val gedraId = "gedraId"

    /** The trait whose entry holds it. */
    const val traitId = "traitId"

    /** The field of the entry's data that holds it. */
    const val fieldName = "fieldName"

    /** The SQLState class of an integrity violation -- a duplicate key among them -- in Postgres and H2 alike. */
    const val integrityViolationClass = "23"
}

/**
 * The value claims table (issue #1849): one row per value a form holds that no other form in the claim's scope may hold.
 * In the gedra data topic, so a claim is written in the transaction of the form that holds it, and its primary key is
 * what keeps two forms from holding one value, whatever two writes race. A counter's ids are the first kind; a field
 * unique for each owner is to share it (issue #1851), keyed by a hash of its normalized value.
 */
fun valueClaimTables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "formValueClaims", topic = gedraDataTopic) {
    table(VCL.formValueClaims, "Which form holds each value no other form in its scope may hold (issue #1849).") {
        column(VCL.claimKind, "What the claim is of, such as 'counter:receipt'.", required = true)
        column(VCL.scopeKey, "What the value is unique within, beside the client; '-' for the client alone.", required = true)
        column(VCL.valueKey, "The claimed value as its key.", required = true)
        column(VCL.claimedValue, "The value, as the form holds it.", required = true)
        column(VCL.gedraId, "The form that holds it.", required = true)
        column(VCL.traitId, "The trait whose entry holds it.", required = true)
        column(VCL.fieldName, "The field of the entry's data that holds it.", required = true)
        forClient()
        primaryKey(PF.client, VCL.claimKind, VCL.scopeKey, VCL.valueKey)
        index(VCL.gedraId)
    }
}

/** One value a write claims for its form (issue #1849): the key it is held under, and where the form holds it. */
data class ValueClaim(
    val client: String,
    val claimKind: String,
    val scopeKey: String,
    val valueKey: String,
    val value: String,
    val gedraId: String,
    val traitId: String,
    val field: String,
) {
    /** The order a write claims its values in, so two writes claiming the same values never wait on each other in a cycle. */
    val sortKey: String get() = listOf(claimKind, scopeKey, valueKey).joinToString("\u0000")

    fun keyMap(): Map<String, Any?> =
        mapOf(PF.client to client, VCL.claimKind to claimKind, VCL.scopeKey to scopeKey, VCL.valueKey to valueKey)
}

/** Claims values for a form, inside the form's write transaction (issue #1849). */
object ValueClaims {
    /**
     * Inserts [claims], in key order, on [sqlCxt]'s transaction. A value already held is refused with
     * [refusal]'s words, given the claim and the form holding it (null when a write racing this one holds it, not yet
     * committed): a 400 the caller can act on, never a database error.
     *
     * In key order, so that two writes claiming the same values -- the same tokens sent twice, in another order -- take
     * the keys in one order and cannot deadlock. Each key is asked for first, for a refusal that names its holder; the
     * primary key is what decides when two writes race, the second insert waiting for the first to commit and then
     * failing as a duplicate, which is refused here in the same words.
     */
    fun claim(cxt: KdrCxt, sqlCxt: SqlCxt, claims: List<ValueClaim>, refusal: (ValueClaim, String?) -> String) {
        if (claims.isEmpty()) return
        val table = table(cxt)
        for (claim in claims.sortedBy { it.sortKey }) {
            val held = sqlCxt.sqlDb.queryOneStatement(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), claim.keyMap())
            if (held != null) throw KdrException.mkInput(refusal(claim, held[VCL.gedraId].toOptStr()))
            val row = LinkedHashMap<String, Any?>(claim.keyMap())
            row[VCL.claimedValue] = claim.value
            row[VCL.gedraId] = claim.gedraId
            row[VCL.traitId] = claim.traitId
            row[VCL.fieldName] = claim.field
            SqlTopicUtil.prepForStdExecute(cxt, table, row)
            try {
                sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, table), row)
            } catch (e: KdrException) {
                if (isIntegrityViolation(e)) throw KdrException.mkInput(refusal(claim, null))
                throw e
            }
        }
    }

    /** Whether [e] arose from a duplicate key -- an integrity violation, by its SQLState -- anywhere in its causes. */
    private fun isIntegrityViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(10).any { (it as? SQLException)?.sqlState?.startsWith(VCL.integrityViolationClass) == true }

    private fun table(cxt: KdrCxt): KdrTable = cxt.getGlobalSchema().tables[VCL.formValueClaims]
        ?: throw KdrException("The ${VCL.formValueClaims} table is not registered in the schema store.")
}
