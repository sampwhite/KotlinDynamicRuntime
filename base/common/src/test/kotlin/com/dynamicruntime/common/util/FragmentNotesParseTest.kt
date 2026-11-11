package com.dynamicruntime.common.util

import com.dynamicruntime.common.content.CPAGE
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Copy notes (issue #1799): `/-where ...-/` before a key or namespace, read apart from the values they leave alone. */
class FragmentNotesParseTest : StringSpec({

    fun codeOf(md: String): Any? =
        shouldThrow<KdrException> { md.parseFragmentNotes() }.extraData[KdrException.errorCodeKey]

    "a note is about the key or namespace after it, and a key's own note wins over its namespace's" {
        val md = """
            /- An ordinary comment, which is not a note. -/
            /-where none: the app's own words. -/
            # @home
            /- A developer's comment between a note and its key. -/
            /-where Home: the paragraphs
               under the title. -/
            # +intro
            First paragraph.

            Second.

            # +footer Unlocated, so its namespace's note holds.
        """.trimIndent()
        val notes = md.parseFragmentNotes()
        notes.noteFor("home", "intro") shouldBe "Home: the paragraphs under the title."
        notes.whereOf("home", "intro") shouldBe "Home: the paragraphs under the title."
        notes.noteFor("home", "footer") shouldBe "none: the app's own words."
        notes.whereOf("home", "footer").shouldBeNull()
        notes.noteFor("other", "key").shouldBeNull()
        // The values read as they would with no notes.
        md.parseMarkdownFragments() shouldBe mapOf(
            "home" to mapOf("intro" to "First paragraph.\n\nSecond.", "footer" to "Unlocated, so its namespace's note holds."),
        )
    }

    "a note must be about something, once, on a page the list has" {
        codeOf("# @home\n# +title Hi\n/-where Home: nothing follows. -/") shouldBe MarkdownError.danglingNote
        codeOf("# @home\n/-where Home: one. -/\n/-where Home: two. -/\n# +title Hi") shouldBe MarkdownError.duplicateNote
        codeOf("# @home\n/-where Lobby: a page nobody has. -/\n# +title Hi") shouldBe MarkdownError.badNote
        codeOf("# @home\n/-where Home -/\n# +title Hi") shouldBe MarkdownError.badNote
        codeOf("# @home\n/-where Home: -/\n# +title Hi") shouldBe MarkdownError.badNote
        codeOf("/-where Home: a namespace has no one place. -/\n# @home\n# +title Hi") shouldBe MarkdownError.locatedNamespace
        codeOf("# @home\n/-where Home: open") shouldBe MarkdownError.unterminatedComment
    }

    "every page of the list is one a note may name" {
        for (page in CPAGE.pages + CPAGE.none) {
            "# @n\n/-where $page: somewhere. -/\n# +k v".parseFragmentNotes().noteFor("n", "k") shouldBe "$page: somewhere."
        }
    }
})
