package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.absoluteValue
import kotlin.time.Instant

/**
 * Exercises every column [StoreType] through a real create/insert/query cycle on H2 2.4.240: an
 * auto-increment counter primary key, a boolean, a float, a date (decode), and both list encodings
 * (comma-separated for scalar elements, JSON for string elements). This is the type-path coverage flagged
 * when the H2 version was bumped.
 */
class SqlTypeRoundTripTest : StringSpec({

    "all column store types round-trip, including auto-increment, lists, booleans, floats, and dates" {
        val cxt = KdrCxt.mkSimpleCxt("types")
        val db = SqlDatabase.mkInMemoryH2("test_types_roundtrip")
        val topic = "types"

        val table = tableModule(cxt, namespace = "typesNs", topic = topic) {
            table("TypeSample", "A table exercising every store type.") {
                column("id", "Auto-increment counter id.", autoIncrement = true) { type = SCT.integer }
                column("flag", "A boolean.") { type = SCT.boolean }
                column("ratio", "A floating-point value.") { type = SCT.number }
                column("eventAt", "A timestamp.") { dateTime() }
                column("tags", "A list of strings (JSON-encoded, may contain commas).") {
                    type = SCT.array
                    items { type = SCT.string }
                }
                column("nums", "A list of integers (comma-separated).") {
                    type = SCT.array
                    items { type = SCT.integer }
                }
                primaryKey("id")
            }
        }.single()

        val sqlCxt = SqlCxt(cxt, db, topic)
        val now = cxt.now()

        db.withSession(cxt) {
            SqlTableUtil.checkCreateTable(sqlCxt, table) shouldBe true

            val insertStmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
            val row = mapOf(
                "flag" to true,
                "ratio" to 3.5,
                "eventAt" to now,
                "tags" to listOf("a", "b,c"), // an element with a comma forces JSON encoding
                "nums" to listOf(1L, 2L, 3L),
            )
            val counter = LongArray(1)
            db.executeStatementGetCounterBack(cxt, insertStmt, row, counter) shouldBe 1
            val generatedId = counter[0]
            (generatedId >= 1L) shouldBe true

            val selectStmt = SqlTopicUtil.mkNamedTableSelectStmt(sqlCxt, "qById", table, listOf("id"))
            val got = db.queryOneStatement(cxt, selectStmt, mapOf("id" to generatedId)).shouldNotBeNull()

            got["id"] shouldBe generatedId
            got["flag"] shouldBe true
            got["ratio"] shouldBe 3.5
            got["tags"] shouldBe listOf("a", "b,c")
            got["nums"] shouldBe listOf(1L, 2L, 3L)

            val eventAt = got["eventAt"].shouldBeInstanceOf<Instant>()
            val deltaMs = (eventAt.toEpochMilliseconds() - now.toEpochMilliseconds()).absoluteValue
            (deltaMs < 2000L) shouldBe true
        }
    }
})
