package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The client configuration reference (issue #1476): made from the slot shapes the write gate is held to, so it lists
 * every slot and every field, and its links within the page each land on a heading.
 */
class ConfigReferenceTest : StringSpec({
    val page = clientConfigurationMarkdown()
    val shapes = ConfigSlotShapes.served(KdrCxt.mkSimpleCxt("configReferenceTest"))

    "the page is made from code alone, so it is the same each time" {
        clientConfigurationMarkdown() shouldBe page
    }

    "every slot has a section, and every link within the page lands on a heading" {
        val headings = headingRegex.findAll(page).map { slug(it.groupValues[1]) }.toList()
        headings.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.shouldBeEmpty()
        headings shouldContainAll shapes.entryTypes.keys.map { slug(it) }
        val anchors = anchorRegex.findAll(page).map { it.groupValues[1] }.toSet()
        (anchors - headings.toSet()).shouldBeEmpty()
    }

    "it says what each action's data must carry" {
        GedraEditAction.entries.forEach { page shouldContain "| `${it.name}` |" }
        page shouldContain "`${CFEP.staleCode}`"
    }
})

private val headingRegex = Regex("""(?m)^#{1,6} (.+)$""")
private val anchorRegex = Regex("""\]\(#([^)]+)\)""")

/** A heading's anchor as the renderer and GitHub make it: lowercased, spaces to hyphens, other punctuation dropped. */
private fun slug(text: String): String = text.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }.replace(' ', '-')
