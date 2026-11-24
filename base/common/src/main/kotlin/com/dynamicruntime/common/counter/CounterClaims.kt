package com.dynamicruntime.common.counter

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraWriteContext
import com.dynamicruntime.common.gedra.GedraWriteHook
import com.dynamicruntime.common.gedra.LogGedra
import com.dynamicruntime.common.gedra.ValueClaim
import com.dynamicruntime.common.gedra.ValueClaims
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The tokens a write sends with the ids it stores (issue #1849): put on the request's context by the endpoint that
 * takes them ([bind]) and read by [CounterClaimHook] inside the write's transaction. On the context's `locals`, which a
 * sub context copies, so the transaction's context made from it has them.
 */
@Suppress("ConstPropertyName")
object CounterTokens {
    /** What a write's [CTR.counterTokens] field is, on every endpoint that takes it. */
    const val hint = "The tokens of the ids a counter issued that this write stores for the first time, each as it was " +
        "issued. A field a counter fills holds only an id issued to you, sent with its token -- or, on a create, is left " +
        "out, and the server gives it the counter's next id."

    /** Puts the [CTR.counterTokens] [raw] holds -- a list of sealed tokens -- on [cxt], for the write it is about to make. */
    fun bind(cxt: KdrCxt, raw: Any?) {
        add(cxt, raw.toJsonListOfStrings())
    }

    /**
     * Adds [tokens] to those on [cxt]: a create's, for the ids the server issued for the fields it left out (issue #1852),
     * beside any the request sent.
     */
    fun add(cxt: KdrCxt, tokens: List<String>) {
        val kept = tokens.filter { it.isNotBlank() }
        if (kept.isNotEmpty()) cxt.locals[CTR.counterTokens] = of(cxt) + kept
    }

    /** The sealed tokens bound to [cxt]; none when its write sent none. */
    fun of(cxt: KdrCxt): List<String> = cxt.locals[CTR.counterTokens].toJsonListOfStrings()
}

/**
 * Holds a counter's field to the ids it was issued (issue #1849), on every write of a gedra's data -- a create, a patch,
 * an import -- inside the write's transaction, so a refusal rolls the whole write back.
 *
 * For each field a counter fills, in each entry the write leaves (or removes):
 * - **Unchanged** from what was stored: nothing to judge. A recompute, which changes only computed values, never gets
 *   further.
 * - **Changed or removed**, once it held an id: refused. An id is fixed once it is stored.
 * - **Newly given an id**: the write must send that id's token, issued to the acting user, for the form's client and the
 *   field's counter. A claim ([ValueClaims]) then records which gedra holds it, and an id already claimed -- the same
 *   token sent twice, even by two writes racing -- is refused.
 *
 * An entry is matched across the write by its trait and, for a keyed trait, its key, as an edit addresses one.
 */
object CounterClaimHook : GedraWriteHook {
    override fun afterWrite(cxt: KdrCxt, write: GedraWriteContext) {
        val row = write.row
        val union = SchemaService.get(cxt).storeFor(row.client).types["${GCFG.globalNamespace}.${GU.unionName(row.kind)}"] ?: return
        // Each trait's data type and the fields of it a counter fills, worked out once a write: every write passes here.
        val dataTypes = HashMap<String, SchType?>()
        fun dataTypeOf(traitId: String): SchType? =
            dataTypes.getOrPut(traitId) { union.variants?.select(traitId)?.properties?.get(GE.data)?.valueType }
        val countedFields = HashMap<String, Map<String, String>>()
        fun fieldsOf(traitId: String): Map<String, String> =
            countedFields.getOrPut(traitId) { dataTypeOf(traitId)?.let(::counterFieldsOf).orEmpty() }
        // Only the entries of traits a counter fills, by their address.
        fun counted(entries: List<Map<String, Any?>>): Map<String, Pair<String, Map<String, Any?>>> = buildMap {
            for (entry in entries) {
                val traitId = entry[GE.traitId].toOptStr() ?: continue
                if (fieldsOf(traitId).isEmpty()) continue
                val type = dataTypeOf(traitId) ?: continue
                val data = entry[GE.data].toJsonMapOrEmpty()
                val address = (listOf(traitId) + type.primaryKey.map { data[it].toString() }).joinToString("\u0000")
                put(address, traitId to data)
            }
        }
        val before = counted(write.priorEntries)
        val after = counted(row.entries)
        if (before.isEmpty() && after.isEmpty()) return
        val tokens by lazy { CounterTokens.of(cxt).map { CounterToken.open(cxt, it) } }
        val claims = mutableListOf<ValueClaim>()
        for (address in before.keys + after.keys) {
            val traitId = (after[address] ?: before.getValue(address)).first
            for ((field, counterId) in fieldsOf(traitId)) {
                val old = before[address]?.second?.get(field).toOptStr()
                val new = after[address]?.second?.get(field).toOptStr()
                if (old == new) continue
                if (old != null) {
                    throw KdrException.mkInput(
                        "The id '$old' in '$field' of '$traitId' was given to this ${kindWord(row.kind)} when it was stored, and does not change.",
                    )
                }
                val token = issuedToken(cxt, row.client, traitId, field, counterId, new!!, tokens)
                claims.add(
                    ValueClaim(
                        row.client, CNT.claimKindPrefix + counterId, token.scopeKey, "${token.periodKey}/${token.count}",
                        new, row.gedraId.fullId, traitId, field,
                    ),
                )
            }
        }
        ValueClaims.claim(cxt, write.sqlCxt, claims) { claim, holder ->
            "The id '${claim.value}' in '${claim.field}' of '${claim.traitId}' is already stored" +
                (if (holder == claim.gedraId) " on this ${kindWord(row.kind)}." else " elsewhere.") +
                " Ask the counter '${claim.claimKind.removePrefix(CNT.claimKindPrefix)}' for another."
        }
        claims.forEach {
            LogGedra.info(cxt) { "Gedra '${it.gedraId}' stores '${it.value}' from a counter in '${it.field}' of '${it.traitId}'." }
        }
    }

    /** The token [value] was issued with, after holding it to the gedra's client and the acting user; refused otherwise. */
    private fun issuedToken(
        cxt: KdrCxt,
        client: String,
        traitId: String,
        field: String,
        counterId: String,
        value: String,
        tokens: List<CounterToken>,
    ): CounterToken {
        val token = tokens.firstOrNull { it.value == value && it.counterId == counterId }
            ?: throw KdrException.mkInput(
                "The id '$value' in '$field' of '$traitId' was not sent with the token it was issued with. Ask the counter " +
                    "'$counterId' for an id, and send its token in '${CTR.counterTokens}' with the write that stores it.",
            )
        if (token.client != client) {
            throw KdrException.mkInput("The id '$value' in '$field' of '$traitId' was issued for another client's forms.")
        }
        if (token.userId != cxt.userProfile.userId) {
            throw KdrException.mkInput("The id '$value' in '$field' of '$traitId' was issued to someone else.")
        }
        return token
    }
}

/** The shape of a write's [CTR.counterTokens] field: a list of sealed tokens, each a secret the request log redacts. */
fun SchTypeBuilder.counterTokensShape() {
    type = SCT.array
    items { type = SCT.string }
    sensitive = true
}

/** A gedra of [kind] as a refusal names it: what a person calls it, not its enum name. */
private fun kindWord(kind: GedraDataType): String = when (kind) {
    GedraDataType.formDoc -> "form"
    GedraDataType.dataFile -> "data file"
    GedraDataType.userData -> "user's data"
    GedraDataType.wfData -> "workflow's data"
}
