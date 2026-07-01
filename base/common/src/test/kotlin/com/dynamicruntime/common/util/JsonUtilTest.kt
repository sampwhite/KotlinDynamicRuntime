package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.StringSpec

class JsonUtilTest : StringSpec({

    // --- toJsonStr: scalars -------------------------------------------------

    "toJsonStr renders null as the literal null" {
        null.toJsonStr(compact = true) shouldBe "null"
    }

    "toJsonStr renders booleans without quotes" {
        listOf(true, false).toJsonStr(compact = true) shouldBe "[true,false]"
    }

    "toJsonStr renders integers via plain toString" {
        listOf(1, 2, 3).toJsonStr(compact = true) shouldBe "[1,2,3]"
    }

    "toJsonStr drops the trailing zero on whole doubles and keeps real fractions" {
        listOf(2.0, 1.5).toJsonStr(compact = true) shouldBe "[2,1.5]"
    }

    // --- toJsonStr: maps ----------------------------------------------------

    "toJsonStr on an empty map renders braces with no space" {
        linkedMapOf<String, Any?>().toJsonStr(compact = true) shouldBe "{}"
    }

    "toJsonStr on an empty list renders empty brackets" {
        emptyList<Any?>().toJsonStr(compact = true) shouldBe "[]"
    }

    "toJsonStr preserves insertion order for a LinkedHashMap" {
        linkedMapOf("b" to 1, "a" to 2).toJsonStr(compact = true) shouldBe """{"b":1,"a":2}"""
    }

    "toJsonStr sorts keys alphabetically for a non-sequenced map" {
        hashMapOf("b" to 1, "a" to 2).toJsonStr(compact = true) shouldBe """{"a":2,"b":1}"""
    }

    "toJsonStr sorts scalar values ahead of collection values when sorting" {
        hashMapOf("data" to arrayListOf(1), "id" to 5)
            .toJsonStr(compact = true) shouldBe """{"id":5,"data":[1]}"""
    }

    "toJsonStr nests maps and arrays" {
        linkedMapOf("x" to linkedMapOf("y" to listOf(1, 2)))
            .toJsonStr(compact = true) shouldBe """{"x":{"y":[1,2]}}"""
    }

    // --- toJsonStr: null handling depends on map ordering -------------------

    "toJsonStr keeps nulls in a LinkedHashMap even without preserveNulls" {
        linkedMapOf<String, Any?>("a" to null, "b" to 2)
            .toJsonStr(compact = true) shouldBe """{"a":null,"b":2}"""
    }

    "toJsonStr drops nulls from a sorted map and marks the suppression with a space" {
        hashMapOf<String, Any?>("a" to null).toJsonStr(compact = true) shouldBe "{ }"
    }

    "toJsonStr keeps nulls from a sorted map when preserveNulls is set" {
        hashMapOf<String, Any?>("a" to null)
            .toJsonStr(compact = true, preserveNulls = true) shouldBe """{"a":null}"""
    }

    // --- toJsonStr: pretty (non-compact) formatting -------------------------

    "toJsonStr in pretty mode indents map entries with two spaces per level" {
        linkedMapOf("a" to 1).toJsonStr() shouldBe "{\n  \"a\":1\n}"
    }

    // --- toJsonStr: string escaping -----------------------------------------

    "toJsonStr escapes control and quote characters in string values" {
        linkedMapOf("s" to "a\"b\nc").toJsonStr(compact = true) shouldBe "{\"s\":\"a\\\"b\\nc\"}"
    }

    // --- jsonMap / jsonArray / json: happy path -----------------------------

    "jsonMap parses a flat object with integers parsed as Long" {
        """{"a":1,"b":2}""".jsonMap() shouldBe mapOf("a" to 1L, "b" to 2L)
    }

    "jsonArray parses a flat array with integers parsed as Long" {
        """[1,2,3]""".jsonArray() shouldBe listOf(1L, 2L, 3L)
    }

    "json parses a top-level array" {
        """[1,2]""".json() shouldBe listOf(1L, 2L)
    }

    "jsonMap parses nested objects and arrays" {
        """{"a":{"b":[1,2]}}""".jsonMap() shouldBe
            mapOf("a" to mapOf("b" to listOf(1L, 2L)))
    }

    "jsonMap parses the full set of scalar types" {
        """{"t":true,"f":false,"n":null,"d":1.5,"s":"hi"}""".jsonMap() shouldBe
            mapOf("t" to true, "f" to false, "n" to null, "d" to 1.5, "s" to "hi")
    }

    "jsonMap parses negative integers and negative floating point" {
        """{"x":-3,"y":-2.5}""".jsonMap() shouldBe mapOf("x" to -3L, "y" to -2.5)
    }

    // --- parsing: escapes ---------------------------------------------------

    "jsonMap decodes standard backslash escapes" {
        "{\"s\":\"a\\nb\\tc\"}".jsonMap() shouldBe mapOf("s" to "a\nb\tc")
    }

    "jsonMap decodes unicode escape sequences" {
        "{\"s\":\"\\u0041\\u00e9\"}".jsonMap() shouldBe mapOf("s" to "Aé")
    }

    // --- parsing: empty / null forgiveness ----------------------------------

    "jsonMap on an empty string returns null" {
        "".jsonMap() shouldBe null
    }

    "jsonArray on a whitespace-only string returns null" {
        "   ".jsonArray() shouldBe null
    }

    "json on the literal null returns null" {
        "null".json() shouldBe null
    }

    // --- parsing: error reporting -------------------------------------------

    "jsonMap rejects duplicate keys by default" {
        shouldThrow<KdrException> {
            """{"a":1,"a":2}""".jsonMap()
        }
    }

    "jsonMap rejects a top-level array (type mismatch)" {
        shouldThrow<KdrException> {
            """[1,2]""".jsonMap()
        }
    }

    "jsonArray rejects a top-level object (type mismatch)" {
        shouldThrow<KdrException> {
            """{"a":1}""".jsonArray()
        }
    }

    "a parse failure carries positional data in extraData" {
        val ex = shouldThrow<KdrException> {
            """{"a":1""".jsonMap() // never closed
        }
        ex.extraData["line"] shouldBe 1
        ex.extraData["lineCol"] shouldBe 6
        (ex.extraData["offset"] as Int) shouldBe 5
    }

    // --- round trip ---------------------------------------------------------

    "format then parse round-trips a mixed structure" {
        val original = linkedMapOf(
            "name" to "test",
            "vals" to listOf(1L, 2L, 3L),
            "nested" to linkedMapOf("flag" to true, "note" to "a\nb"),
        )
        original.toJsonStr().json() shouldBe original
    }

    // --- multi-line error position ------------------------------------------

    "a parse error on a later line reports the column within that line" {
        val input = "{\n  \"a\": 1,\n  @\n}"
        val ex = shouldThrow<KdrException> { input.jsonMap() }
        ex.extraData["line"] shouldBe 3
        ex.extraData["lineCol"] shouldBe 3
        ex.extraData["offset"] shouldBe 14
    }

    // --- arrays of every flavor format as JSON arrays -----------------------

    "toJsonStr renders primitive and object arrays as JSON arrays" {
        intArrayOf(1, 2, 3).toJsonStr(compact = true) shouldBe "[1,2,3]"
        longArrayOf(1, 2).toJsonStr(compact = true) shouldBe "[1,2]"
        doubleArrayOf(1.0, 2.5).toJsonStr(compact = true) shouldBe "[1,2.5]"
        booleanArrayOf(true, false).toJsonStr(compact = true) shouldBe "[true,false]"
        arrayOf("a", "b").toJsonStr(compact = true) shouldBe """["a","b"]"""
    }

    "toJsonStr renders an array nested inside a map" {
        linkedMapOf("v" to intArrayOf(1, 2)).toJsonStr(compact = true) shouldBe """{"v":[1,2]}"""
    }

    // --- depth limits: both format and parse throw --------------------------

    "toJsonStr throws on structures nested beyond the depth limit" {
        var deep: Any? = "leaf"
        repeat(60) { deep = listOf(deep) }
        shouldThrow<KdrException> { deep.toJsonStr(compact = true) }
    }

    "parsing throws on structures nested beyond the depth limit" {
        val deep = "[".repeat(60) + "]".repeat(60)
        shouldThrow<KdrException> { deep.jsonArray() }
    }

    // --- trailing content ---------------------------------------------------

    "parsing rejects trailing non-whitespace content" {
        shouldThrow<KdrException> { """{"a":1} oops""".jsonMap() }
        shouldThrow<KdrException> { """[1,2]x""".jsonArray() }
    }

    "parsing allows trailing whitespace" {
        """{"a":1}   """.jsonMap() shouldBe mapOf("a" to 1L)
        "  [1]  ".jsonArray() shouldBe listOf(1L)
    }

    "forgiveTrailingContent lets a non-standard caller ignore trailing content" {
        val state = PState("""{"a":1} trailing""", ExpectedVal.map)
        state.forgiveTrailingContent = true
        val result = parseJson(state, 0)
        checkNoTrailingContent(state) // must not throw
        result shouldBe mapOf("a" to 1L)
    }
})
