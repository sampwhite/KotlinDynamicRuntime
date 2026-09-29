package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The standard keywords issue #823 settled: `pattern`, `exclusiveMinimum` / `exclusiveMaximum` and `uniqueItems`
 * are enforced, and `enum`, `allOf`, `anyOf`, `not` and `dependentSchemas` are refused by name. In `commonTest`, so
 * each case runs on the JVM and under Kotlin/JS -- the backend's regex engine and the browser's -- and a pattern
 * that accepted a value on one and refused it on the other would fail here on one of the two.
 */
class SchStandardKeywordsTest {

    /** One type, `ns.T`, whose single property `v` has [body]. */
    private fun fieldType(body: Map<String, Any?>): SchType =
        parseSchemaTypes(
            mapOf("ns.T" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("v" to body))),
        ).getValue("ns.T")

    /** The failure codes validating `{v: value}` against a field with [body] produces. */
    private fun codes(body: Map<String, Any?>, value: Any?): List<SchFailCode> =
        validate(fieldType(body), mapOf("v" to value)).map { it.code }

    private fun patternField(pattern: String) = mapOf(SCH.type to SCT.string, SCH.pattern to pattern)

    private fun accepts(pattern: String, value: String): Boolean = codes(patternField(pattern), value).isEmpty()

    /** The message of the refusal parsing a field with [body], which must be refused. */
    private fun refusal(body: Map<String, Any?>): String =
        assertFailsWith<KdrException> { fieldType(body) }.message.orEmpty()

    // --- pattern ---

    @Test
    fun aPatternIsUnanchoredUnlessAnchored() {
        assertTrue(accepts("[0-9]", "a1b"))
        assertTrue(!accepts("^[0-9]+$", "a1b"))
        assertTrue(accepts("^[0-9]+$", "123"))
    }

    // Java's `$` also matches before a final line break; the translation makes it the end of the value on both.
    @Test
    fun dollarIsTheEndOfTheValueOnBothEngines() {
        assertTrue(accepts("^[a-z]+$", "abc"))
        assertTrue(!accepts("^[a-z]+$", "abc\n"))
    }

    // ECMA-262's `\s` is Unicode-wide (a no-break space counts); Java's is ASCII. Both now read it as ECMA does.
    @Test
    fun whitespaceClassesAreEcmaScriptsOnBothEngines() {
        assertTrue(accepts("^a\\sb$", "a\u00a0b"))
        assertTrue(!accepts("^\\S+$", "a\u00a0b"))
        assertTrue(accepts("^[\\s,]+$", " ,\u3000"))
    }

    // ECMA-262's `.` excludes only line terminators; Java's also excludes U+0085.
    @Test
    fun dotIsAnythingButALineTerminatorOnBothEngines() {
        assertTrue(accepts("^.$", "\u0085"))
        // (Inside a value: a value that is only whitespace reads as blank, and so as absent.)
        assertTrue(!accepts("^a.b$", "a\nb"))
        assertTrue(!accepts("^a.b$", "a\u2028b"))
    }

    @Test
    fun portableSyntaxBehavesAlike() {
        assertTrue(accepts("^\\@\\-$", "@-"))                   // escaped punctuation is a literal
        assertTrue(accepts("^a]}$", "a]}"))                    // so is a lone `]` or `}`
        assertTrue(accepts("^[\\&\\&]+$", "&&"))                // escaped `&` in a class is not an intersection
        assertTrue(accepts("^\\p{Lu}+$", "ÀB"))
        assertTrue(!accepts("^\\p{Lu}+$", "àb"))
        assertTrue(accepts("^(?<x>a)\\k<x>$", "aa"))
        assertTrue(accepts("^(a)\\1$", "aa"))
        assertTrue(accepts("^a{2,3}?b*?$", "aab"))
        assertTrue(accepts("^(?:ab|cd)(?=e)", "cde"))
        assertTrue(accepts("^\\x41\\u0042[^\\d\\W]$", "ABc"))
        assertTrue(accepts("^[\\]\\[a-c-]+$", "[]-b"))
    }

    @Test
    fun javaOnlyOrAmbiguousSyntaxIsRefusedByName() {
        val refused = listOf(
            "a++" to "possessive", "(?i)abc" to "inline flag", "(?>a)" to "atomic", "\\Aabc" to "\\A",
            "a{" to "quantifier", "[]a]" to "empty class", "[a[b]]" to "union", "[a&&b]" to "intersection",
            "\\p{Alpha}" to "general category", "a\\vb" to "\\v", "[\\S]" to "\\S", "\\0" to "\\0",
            "[a" to "never closed", "(a" to "not a valid pattern",
        )
        for ((pattern, mentions) in refused) {
            val message = refusal(patternField(pattern))
            assertTrue("Property 'v'" in message && mentions in message, "'$pattern' -> $message")
        }
    }

    // A pattern would check nothing on a non-string, and on a date it would miss the string a standard validator
    // checks; both are refused rather than ignored.
    @Test
    fun aPatternOutsideAPlainStringIsRefused() {
        assertTrue("plain string" in refusal(mapOf(SCH.type to SCT.integer, SCH.pattern to "^1$")))
        assertTrue("plain string" in refusal(mapOf(SCH.type to SCT.string, SCH.format to SFMT.date, SCH.pattern to "x")))
        assertTrue("text" in refusal(mapOf(SCH.type to SCT.string, SCH.pattern to 5L)))
    }

    @Test
    fun aMismatchIsItsOwnCodeAndRunsAfterEdgeWhitespace() {
        val type = fieldType(patternField("^[0-9]{5}$"))
        val failure = validate(type, mapOf("v" to "1234")).single()
        assertEquals(SchFailCode.patternMismatch, failure.code)
        assertEquals("This must match the pattern '^[0-9]{5}$'.", failure.message)
        // Endpoint input is trimmed before the pattern sees it.
        assertEquals(emptyList(), validate(type, mapOf("v" to " 12345 "), SchOpts(forInput = true)))
    }

    // --- exclusive bounds ---

    @Test
    fun anExclusiveBoundRefusesTheBoundItself() {
        val range = mapOf(SCH.type to SCT.number, SCH.exclusiveMinimum to 0L, SCH.exclusiveMaximum to 10L)
        assertEquals(listOf(SchFailCode.belowMinimum), codes(range, 0L))
        assertEquals(listOf(SchFailCode.aboveMaximum), codes(range, 10L))
        assertEquals(emptyList(), codes(range, 0.5))
        assertEquals(emptyList(), codes(range, 9.99))
        val message = validate(fieldType(range), mapOf("v" to 0L)).single().message
        assertEquals("This must be more than 0.", message)
    }

    @Test
    fun withBothKindsOnOneSideTheStricterWins() {
        val inclusiveStricter = mapOf(SCH.type to SCT.integer, SCH.minimum to 5L, SCH.exclusiveMinimum to 3L)
        assertEquals(emptyList(), codes(inclusiveStricter, 5L))
        assertEquals(listOf(SchFailCode.belowMinimum), codes(inclusiveStricter, 4L))
        val exclusiveStricter = mapOf(SCH.type to SCT.integer, SCH.minimum to 3L, SCH.exclusiveMinimum to 5L)
        assertEquals(listOf(SchFailCode.belowMinimum), codes(exclusiveStricter, 5L))
        assertEquals(emptyList(), codes(exclusiveStricter, 6L))
    }

    @Test
    fun draftFoursBooleanFormIsRefused() {
        val message = refusal(mapOf(SCH.type to SCT.integer, SCH.minimum to 0L, SCH.exclusiveMinimum to true))
        assertTrue("'${SCH.exclusiveMinimum}'" in message && "must be a number" in message, message)
    }

    // --- uniqueItems ---

    private fun uniqueList(items: Map<String, Any?>? = null) =
        buildMap {
            put(SCH.type, SCT.array)
            put(SCH.uniqueItems, true)
            items?.let { put(SCH.items, it) }
        }

    @Test
    fun uniqueItemsRefusesARepeatNamingBothPositions() {
        val failure = validate(fieldType(uniqueList()), mapOf("v" to listOf(1L, 2L, 1L))).single()
        assertEquals(SchFailCode.duplicateItem, failure.code)
        assertEquals("Item 3 repeats item 1; each item must be different.", failure.message)
        assertEquals(emptyList(), codes(uniqueList(), listOf(1L, 2L, 3L)))
    }

    @Test
    fun itemsAreComparedAsJsonValues() {
        assertEquals(listOf(SchFailCode.duplicateItem), codes(uniqueList(), listOf(1L, 1.0)))
        assertEquals(emptyList(), codes(uniqueList(), listOf("1", 1L)))
        val a = mapOf("x" to 1L, "y" to listOf("p"))
        val b = mapOf("y" to listOf("p"), "x" to 1L)
        assertEquals(listOf(SchFailCode.duplicateItem), codes(uniqueList(), listOf(a, b)))
        // Compared as validated: "5" coerces to the integer 5.
        assertEquals(listOf(SchFailCode.duplicateItem), codes(uniqueList(mapOf(SCH.type to SCT.integer)), listOf("5", 5L)))
    }

    @Test
    fun uniqueItemsMustBeABoolean() {
        assertTrue("'${SCH.uniqueItems}'" in refusal(mapOf(SCH.type to SCT.array, SCH.uniqueItems to "yes")))
    }

    // --- refused keywords ---

    @Test
    fun eachRefusedKeywordFailsTheParseByName() {
        val refused = listOf(SCH.enum, SCH.allOf, SCH.anyOf, SCH.not, SCH.dependentSchemas)
        for (keyword in refused) {
            val message = refusal(mapOf(SCH.type to SCT.string, keyword to listOf(mapOf(SCH.type to SCT.string))))
            assertTrue("Property 'v'" in message && "'$keyword'" in message, message)
            // On a type too, and beside a `$ref`, whose target would never see it.
            val onType = assertFailsWith<KdrException> {
                parseSchemaTypes(mapOf("ns.U" to mapOf(SCH.type to SCT.kObject, keyword to emptyList<Any?>())))
            }.message.orEmpty()
            assertTrue("Type 'ns.U'" in onType, onType)
            val besideRef = assertFailsWith<KdrException> {
                parseSchemaTypes(
                    mapOf(
                        "ns.S" to mapOf(SCH.type to SCT.string),
                        "ns.H" to mapOf(
                            SCH.type to SCT.kObject,
                            SCH.properties to mapOf("s" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.S", keyword to true)),
                        ),
                    ),
                )
            }.message.orEmpty()
            assertTrue("Property 's'" in besideRef, besideRef)
        }
    }

    // A denylist, not an allowlist: a keyword a document carries for its own purposes stays allowed.
    @Test
    fun otherUnreadKeywordsStayAllowed() {
        fieldType(mapOf(SCH.type to SCT.string, "x-note" to "anything", SCH.examples to listOf("a")))
    }
}
