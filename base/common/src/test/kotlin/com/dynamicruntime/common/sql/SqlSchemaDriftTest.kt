package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Boot-time schema-drift detection (issue #216): a column the database has, the code no longer declares, and
 * that is `NOT NULL` with no default makes every generated insert fail. `createTable` only ever *adds* what is
 * missing, so before this it added the new name, left the old one behind, and started cleanly.
 *
 * Driven against a real H2 database rather than by hand-built metadata, because the claim is about what JDBC
 * reports for a table that actually exists -- nullability and defaults included, which is precisely the part a
 * fake would get to decide for itself.
 */
class SqlSchemaDriftTest : StringSpec({

    /** A table declaring [columns] beyond the key, with [required] naming which of them are NOT NULL. */
    fun tableOf(cxt: KdrCxt, columns: List<String>, required: Set<String> = emptySet()): KdrTable =
        tableModule(cxt, "app") {
            table("Drifter", "A table used to provoke drift") {
                column("itemKey", "Key of the item.")
                for (c in columns) column(c, "Column $c.", required = c in required)
                primaryKey("itemKey")
            }
        }.single()

    /**
     * Creates `Drifter` from [before], then reconciles [after] against it -- the shape of a rename, where the
     * declaration stops naming a column the database still has.
     */
    fun reconcile(dbName: String, before: KdrTable, allowDrift: Boolean = false, after: (KdrCxt) -> KdrTable) {
        val instanceConfig = KdrInstanceConfig.codeTest()
        if (allowDrift) instanceConfig.put(DbEnv.allowSchemaDrift.name, "true")
        val cxt = KdrCxt.mkSimpleCxt("test", instanceConfig)
        val db = SqlDatabase.mkInMemoryH2(dbName)
        val sqlCxt = SqlCxt(cxt, db, "app")
        db.withSession(cxt) {
            SqlTableUtil.createTable(sqlCxt, before)
            // A second SqlDatabase over the same in-memory database, so the "already created" cache does not
            // short-circuit the second reconciliation the way a redeploy never would.
            val db2 = SqlDatabase.mkInMemoryH2(dbName)
            val sqlCxt2 = SqlCxt(cxt, db2, "app")
            db2.withSession(cxt) { SqlTableUtil.createTable(sqlCxt2, after(cxt)) }
        }
    }

    "a stranded NOT NULL column with no default refuses the boot and names the cause" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val before = tableOf(cxt, listOf("account"), required = setOf("account"))

        val e = shouldThrow<KdrException> {
            reconcile("test_drift_blocking", before) { c -> tableOf(c, listOf("client")) }
        }
        // The message has to carry the explanation, not just the symptom -- the whole cost of the original bug
        // was the distance between "Could not execute query iAuthUserDevices" and the word "account".
        e.message shouldContain "account"
        e.message shouldContain "NOT NULL with no default"
        e.message shouldContain "not declared by the code"
        e.message shouldContain DbEnv.allowSchemaDrift.name // the way past it, stated where it is needed
    }

    "an undeclared column that is nullable is left alone" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        // Not required, so H2 creates it nullable: harmless to the framework, and possibly a DBA's own.
        // Failing on it would make this check something operators route around.
        val before = tableOf(cxt, listOf("account"))
        reconcile("test_drift_nullable", before) { c -> tableOf(c, listOf("client")) }
    }

    "the escape hatch boots a drifted deployment instead of refusing it" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val before = tableOf(cxt, listOf("account"), required = setOf("account"))
        // No throw: an operator mid-migration can start the node. Writes still fail -- the variable downgrades
        // the refusal, it does not fix anything.
        reconcile("test_drift_allowed", before, allowDrift = true) { c -> tableOf(c, listOf("client")) }
    }

    // --- the classification rules, where every branch is cheap to state ------

    "the blocking rule fires only on undeclared, NOT NULL, and undefaulted" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val declared = tableOf(cxt, listOf("client"))
        val aliases = SqlDatabase.mkInMemoryH2("test_drift_rules").also {
            it.addDefaultAliases("app", declared.columns)
        }.getAliases("app")

        fun blockingFor(vararg cols: DbColumnInfo): List<String> =
            SqlSchemaDrift.strandedBlockingColumns(declared, cols.associateBy { it.name }, aliases)

        blockingFor(DbColumnInfo("account", nullable = false, hasDefault = false)) shouldBe listOf("account")
        blockingFor(DbColumnInfo("account", nullable = true, hasDefault = false)) shouldBe emptyList()
        blockingFor(DbColumnInfo("account", nullable = false, hasDefault = true)) shouldBe emptyList()
        // Declared columns are never stranded, whatever the database says about them.
        blockingFor(DbColumnInfo("client", nullable = false, hasDefault = false)) shouldBe emptyList()
    }

    /**
     * The warning case: a column the code says is required that the database still allows to be null. It is
     * how an `ALTER TABLE ... ADD COLUMN` necessarily leaves things, so it is expected transiently and only
     * means something if it persists -- hence a warning, never fatal.
     */
    "a required column the database still allows to be null is reported but not fatal" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        val declared = tableOf(cxt, listOf("client"), required = setOf("client"))
        val aliases = SqlDatabase.mkInMemoryH2("test_drift_backfill").also {
            it.addDefaultAliases("app", declared.columns)
        }.getAliases("app")

        SqlSchemaDrift.unbackfilledColumns(
            declared, mapOf("client" to DbColumnInfo("client", nullable = true, hasDefault = false)), aliases,
        ) shouldBe listOf("client")

        SqlSchemaDrift.unbackfilledColumns(
            declared, mapOf("client" to DbColumnInfo("client", nullable = false, hasDefault = false)), aliases,
        ) shouldBe emptyList()

        // End to end it must not stop a boot: the column is added nullable and the next reconcile survives.
        reconcile("test_drift_addcol", tableOf(cxt, emptyList())) { c ->
            tableOf(c, listOf("client"), required = setOf("client"))
        }
    }
})
