package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JSON highlighter (issue #1227): the runs it classifies, the text it keeps, and what it refuses. */
class JsonHighlightTest {
    private val block = """
        {
          "name": "A <b>", "n": -1.5e3,
          "ok": true, "none": null,
          "list": ["x", 2]
        }
    """.trimIndent()

    @Test
    fun eachRunIsClassified() {
        val html = highlightJson(block)!!
        assertTrue(html.contains("<span class=\"${MdTok.key}\">&quot;name&quot;</span>"), html)
        assertTrue(html.contains("<span class=\"${MdTok.string}\">&quot;A &lt;b&gt;&quot;</span>"), html)
        assertTrue(html.contains("<span class=\"${MdTok.number}\">-1.5e3</span>"), html)
        assertTrue(html.contains("<span class=\"${MdTok.literal}\">true</span>"), html)
        assertTrue(html.contains("<span class=\"${MdTok.literal}\">null</span>"), html)
        assertTrue(html.contains("<span class=\"${MdTok.punct}\">{</span>"), html)
        // A string inside a list is a value, not a key.
        assertTrue(html.contains("<span class=\"${MdTok.string}\">&quot;x&quot;</span>"), html)
        assertFalse(html.contains("<span class=\"${MdTok.key}\">&quot;x&quot;</span>"), html)
    }

    @Test
    fun theTextAndItsLayoutAreKeptExactly() {
        val html = highlightJson(block)!!
        val stripped = html.replace(Regex("</?span[^>]*>"), "")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        assertEquals(block, stripped)
    }

    @Test
    fun aKeyIsAStringAColonFollows() {
        val html = highlightJson("{\"a\" : \"b\"}")!!
        assertTrue(html.contains("<span class=\"${MdTok.key}\">&quot;a&quot;</span>"), html)
        assertTrue(html.contains("<span class=\"${MdTok.string}\">&quot;b&quot;</span>"), html)
    }

    @Test
    fun whatIsNotJsonAtTheTokenLevelIsRefused() {
        assertNull(highlightJson("{\"open: 1}"), "an unterminated string")
        assertNull(highlightJson("{ // a comment\n}"), "a comment")
        assertNull(highlightJson("{\"a\": yes}"), "a bare word")
        assertNull(highlightJson("- 1"), "a sign on its own")
    }

    @Test
    fun theRendererHighlightsAJsonFenceAndLeavesOthersPlain() {
        val json = "```json\n{\"a\": 1}\n```"
        val html = json.renderMarkdown()
        assertTrue(html.startsWith("<pre><code class=\"language-json\"><span"), html)
        val kotlin = "```kotlin\nval a = 1\n```".renderMarkdown()
        assertEquals("<pre><code class=\"language-kotlin\">val a = 1</code></pre>\n", kotlin)
        // A fence labelled json whose text is not JSON renders as it did before.
        assertEquals("<pre><code class=\"language-json\">not json</code></pre>\n", "```json\nnot json\n```".renderMarkdown())
    }
}
