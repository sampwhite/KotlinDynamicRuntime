package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The content side of the organization work (issue #225): a table opts into an organization column with
 * [TableBuilder.forOrg], a standard write stamps it from the acting caller's own, and [SqlScopeUtil] turns a
 * [ReadScope] into the predicates that confine a read to it.
 *
 * Driven against a real (in-memory H2) database rather than by inspecting generated SQL, because the claim
 * being made is about which rows come back -- particularly the lenient null, where a wrong predicate would
 * still be perfectly valid SQL returning the wrong answer.
 */
class SqlOrgScopeTest : StringSpec({

    /** A context acting as a user of [client], in [org] (null for "the client's, not any organization's"). */
    fun cxtFor(client: String, org: String?): KdrCxt =
        KdrCxt.mkSimpleCxt("test").also { it.bindToUserProfile(UserProfile(authId = "u", client = client, org = org)) }

    fun notesTable(cxt: KdrCxt): KdrTable = tableModule(cxt, "app") {
        table("Note", "A note, owned by a client and optionally by an organization within it") {
            column("noteKey", "Key of the note.")
            primaryKey("noteKey")
            forOrg()
        }
    }.single()

    "forOrg adds a nullable org column and implies the client feature" {
        val table = notesTable(KdrCxt.mkSimpleCxt("test"))
        table.features shouldBe setOf(TableFeature.org, TableFeature.client)
        table.columnsByName.keys shouldContainAll setOf(PF.org, PF.client)
        // The client is required and the organization is not -- the whole reason a client that uses no
        // organizations can write to the table at all.
        (PF.client in table.required) shouldBe true
        (PF.org in table.required) shouldBe false
    }

    "a standard write stamps the acting caller's organization, and leaves it null when they have none" {
        val orgCxt = cxtFor("acme", "eng")
        val table = notesTable(orgCxt)

        val fromOrg = mutableMapOf<String, Any?>("noteKey" to "a")
        SqlTopicUtil.prepForStdExecute(orgCxt, table, fromOrg)
        fromOrg[PF.org] shouldBe "eng"
        fromOrg[PF.client] shouldBe "acme"

        // No organization is a legitimate final value, not a missing one: nothing is stamped, so the column
        // stays null and the row belongs to the client as a whole.
        val fromClient = mutableMapOf<String, Any?>("noteKey" to "b")
        SqlTopicUtil.prepForStdExecute(cxtFor("acme", null), table, fromClient)
        fromClient.containsKey(PF.org) shouldBe false

        // An organization already on the row survives a re-stamp, as the other ownership columns do.
        val explicit = mutableMapOf<String, Any?>("noteKey" to "c", PF.org to "sales")
        SqlTopicUtil.prepForStdExecute(orgCxt, table, explicit)
        explicit[PF.org] shouldBe "sales"
    }

    "a scoped read returns its own organization's rows plus the client's org-less ones" {
        val cxt = cxtFor("acme", "eng")
        val db = SqlDatabase.mkInMemoryH2("test_org_scope")
        val topic = "app"
        val table = notesTable(cxt)
        val sqlCxt = SqlCxt(cxt, db, topic)

        db.withSession(cxt) {
            SqlTableUtil.checkCreateTable(sqlCxt, table) shouldBe true
            val insert = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)

            // Written by callers in three different positions, so every row's org column is stamped by the
            // ordinary write path rather than set by hand.
            fun write(key: String, client: String, org: String?) {
                val row = mutableMapOf<String, Any?>("noteKey" to key)
                SqlTopicUtil.prepForStdExecute(cxtFor(client, org), table, row)
                db.executeStatement(cxt, insert, row) shouldBe 1
            }
            write("eng1", "acme", "eng")
            write("sales1", "acme", "sales")
            write("shared", "acme", null)      // the client's, not any organization's
            write("other", "globex", "eng")    // same org *name*, different client

            fun keysFor(scope: ReadScope): List<String> {
                val data = mutableMapOf<String, Any?>()
                val where = SqlScopeUtil.scopeWhereClause(scope, table, data)
                val stmt = SqlStmtUtil.prepareSql(
                    sqlCxt, "qNote${scope.shapeKey}", table.columns,
                    "select * from t:Note$where order by c:noteKey",
                )
                return db.queryStatement(cxt, stmt, data).map { it["noteKey"] as String }
            }

            // The lenient rule: the caller's own organization, plus the rows that belong to no organization.
            // "sales1" is excluded, and so is the other client's row despite carrying the same org name.
            keysFor(ReadScope.ofOrg("acme", "eng")) shouldContainExactly listOf("eng1", "shared")

            // One width out: the whole client, organizations and all.
            keysFor(ReadScope.ofClient("acme")) shouldContainExactly listOf("eng1", "sales1", "shared")

            // And unrestricted sees every client's.
            keysFor(ReadScope.unrestricted) shouldContainExactly listOf("eng1", "other", "sales1", "shared")
        }
    }

    "a constrained dimension the table cannot express is an error rather than a wider answer" {
        val cxt = KdrCxt.mkSimpleCxt("test")
        // Client-scoped but with no organization column: the table opted into forClient() alone.
        val table = tableModule(cxt, "app") {
            table("Plain", "A client-owned row with no organization") {
                column("plainKey", "Key.")
                primaryKey("plainKey")
                forClient()
            }
        }.single()

        val data = mutableMapOf<String, Any?>()
        // A client-only scope is fine -- that column is there.
        SqlScopeUtil.scopeConditions(ReadScope.ofClient("acme"), table, data).size shouldBe 1

        // An organization scope is not: dropping the condition would return the whole client's rows to a
        // caller confined to one organization, and the query would look perfectly healthy.
        val e = shouldThrow<KdrException> {
            SqlScopeUtil.scopeConditions(ReadScope.ofOrg("acme", "eng"), table, mutableMapOf())
        }
        e.message shouldContain PF.org

        // Unless the caller says it filters that dimension itself, which is what UserService.listUsers does.
        val declared = mutableMapOf<String, Any?>()
        SqlScopeUtil.scopeConditions(
            ReadScope.ofOrg("acme", "eng"), table, declared, filteredAfterQuery = setOf(PF.org),
        ).size shouldBe 1
        declared.containsKey(SCP.scopeOrg) shouldBe false
    }

    "a sub context bound to a different client drops the organization" {
        val cxt = cxtFor("acme", "eng")
        cxt.org shouldBe "eng"

        // Same client: the organization is still meaningful, so it travels.
        cxt.mkSubContext("sub").org shouldBe "eng"

        // Different client: an org name means nothing outside its own client, and carrying "eng" across would
        // confine globex's rows by a name that is not theirs.
        cxt.mkSubContext("sub", "globex").org shouldBe null
    }
})
