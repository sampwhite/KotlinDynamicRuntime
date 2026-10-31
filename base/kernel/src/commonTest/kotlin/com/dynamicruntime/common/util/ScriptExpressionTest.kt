package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A bare script expression evaluated to a value (issue #1501), where a template renders text: the grammar a template's
 * `${...}` block takes, the paths it reads for a definition check, and whether it pulls fragment copy. In `commonTest`,
 * so the backend and the browser compute alike.
 */
class ScriptExpressionTest {
    @Test
    fun evaluatesToTypedValues() {
        assertEquals(50.0, ScriptExpression.parse("perItem * count").evaluate(mapOf("perItem" to 12.5, "count" to 4L)))
        assertEquals(true, ScriptExpression.parse("a > 2 && b").evaluate(mapOf("a" to 3L, "b" to true)))
        assertEquals("ab", ScriptExpression.parse("x ~ y").evaluate(mapOf("x" to "a", "y" to "b")))
        assertNull(ScriptExpression.parse("x ?: null").evaluate(emptyMap()))
    }

    @Test
    fun aBareMissingValueThrowsWithItsCode() {
        val e = assertFailsWith<KdrException> { ScriptExpression.parse("a * b").evaluate(mapOf("a" to 2L)) }
        assertEquals(ScriptError.missingKey, e.extraData[KdrException.errorCodeKey])
    }

    @Test
    fun parseFaultsAreRefused() {
        assertEquals(
            ScriptError.emptyExpression,
            assertFailsWith<KdrException> { ScriptExpression.parse("  ") }.extraData[KdrException.errorCodeKey],
        )
        assertEquals(
            ScriptError.syntaxError,
            assertFailsWith<KdrException> { ScriptExpression.parse("a *") }.extraData[KdrException.errorCodeKey],
        )
    }

    @Test
    fun reportsThePathsItReadsAndWhetherItPullsCopy() {
        val e = ScriptExpression.parse("a.b * (c ?: 1)")
        assertEquals(setOf("a.b"), e.paths.required)
        assertEquals(setOf("c"), e.paths.optional)
        assertFalse(e.pullsFragments)
        assertTrue(ScriptExpression.parse("""@t("x.y")""").pullsFragments)
    }
}
