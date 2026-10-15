package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.MarkdownHooks
import com.dynamicruntime.common.util.renderMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The copy button on a code block (issue #1233): which blocks get one, and what the wrapping is. */
class MarkdownCopyTest {
    private val pre = "<pre><code class=\"language-json\">{}</code></pre>"

    @Test
    fun aJsonBlockIsWrappedWithTheButtonBeforeIt() {
        val html = copyButtonWrap("json", pre)!!
        assertTrue(html.startsWith("<div class=\"$codeBlockClass\"><button type=\"button\" class=\"$copyButtonClass\""), html)
        assertTrue(html.endsWith("</button>$pre</div>"), html)
        assertTrue(html.contains("aria-label=\"Copy to clipboard\""), html)
    }

    @Test
    fun otherLanguagesAndAnUnlabelledFenceAreLeftAlone() {
        assertNull(copyButtonWrap("kotlin", pre))
        assertNull(copyButtonWrap("", pre))
    }

    @Test
    fun theRendererAppliesItThroughTheHook() {
        val html = "```json\n[]\n```".renderMarkdown(MarkdownHooks(decorateCodeBlock = ::copyButtonWrap))
        assertTrue(html.startsWith("<div class=\"$codeBlockClass\"><button"), html)
        assertTrue(html.endsWith("</code></pre></div>\n"), html)
        assertEquals("<pre><code class=\"language-kotlin\">x</code></pre>\n", "```kotlin\nx\n```".renderMarkdown(MarkdownHooks(decorateCodeBlock = ::copyButtonWrap)))
    }
}
