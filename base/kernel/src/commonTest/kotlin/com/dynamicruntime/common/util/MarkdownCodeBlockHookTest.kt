package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** The code-block hook (issue #1233): what it is given, what it may replace, and the default without one. */
class MarkdownCodeBlockHookTest {
    private val fence = "```kotlin\nval a = 1\n```"

    @Test
    fun theHookSeesTheLanguageAndTheRenderedBlockAndReplacesIt() {
        val seen = mutableListOf<Pair<String, String>>()
        val hooks = MarkdownHooks(decorateCodeBlock = { language, pre -> seen.add(language to pre); "<div class=\"wrap\">$pre</div>" })
        val html = fence.renderMarkdown(hooks)
        assertEquals(listOf("kotlin" to "<pre><code class=\"language-kotlin\">val a = 1</code></pre>"), seen)
        assertEquals("<div class=\"wrap\"><pre><code class=\"language-kotlin\">val a = 1</code></pre></div>\n", html)
    }

    @Test
    fun aNullFromTheHookAndNoHookBothLeaveTheBlockAsItIs() {
        val plain = "<pre><code class=\"language-kotlin\">val a = 1</code></pre>\n"
        assertEquals(plain, fence.renderMarkdown())
        assertEquals(plain, fence.renderMarkdown(MarkdownHooks(decorateCodeBlock = { _, _ -> null })))
    }
}
