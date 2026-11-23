package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.home.changeGroups
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchGKeywords
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * What the assistant is taught of every `g-` keyword and every configuration slot (issue #1854). Each is named in its
 * system prompt or in a row of the Making changes guide (a row, which says how to make a change, and not a mention in
 * the guide's prose), written by one of its tools, or not taught, with why. A keyword or slot added without a decision
 * fails here, since a capability the assistant is never pointed at is one it does not use, though the references it can
 * read list it: a counter was such a one (issue #1849).
 *
 * Theme: #SLTKW.
 */
class AssistantTaughtTest : StringSpec({
    /** How the assistant reaches what it is not told by name: the [tool] that writes it, or none, and [why]. */
    class Reach(val tool: String?, val why: String)

    fun by(tool: String, why: String) = Reach(tool, why)
    fun notTaught(why: String) = Reach(null, why)

    val checking = notTaught("How a value is checked: a client's trait keeps the default, and the schema dialect has it.")
    val fileField = notTaught("A file field is not a kind the trait designer offers yet: written by hand, from the reference.")
    val keywords: Map<String, Reach> = mapOf(
        SCH.allowCoerce to checking,
        SCH.emptyIsAbsent to checking,
        SCH.visibleOnly to checking,
        SCH.outerWhitespace to checking,
        SCH.fileKinds to fileField,
        SCH.fileMaxSize to fileField,
        SCH.fileRows to fileField,
        SCH.fileMaxRows to fileField,
        SCH.derived to notTaught("The platform's: a value something other than a person fills in."),
        SCH.onWrite to notTaught("The platform's: functions run on a write, which no client's trait declares."),
        SCH.schemaDocument to notTaught("The platform's: a value that is itself a schema."),
        SCH.options to by(ATOOL.designTrait, "A choice field's choices."),
        SCH.openOptions to notTaught("An open list is not a kind the trait designer offers: written by hand, from the reference."),
        SCH.primaryKey to by(ATOOL.patchConfig, "The keys of a slot's entries, which an edit addresses."),
        SCH.optionalContents to notTaught("The platform's: an object field whose value may be a fragment."),
        SCH.optionsSource to notTaught("The platform's: choices a registered source supplies, which no client declares."),
        SCH.visibleWhen to notTaught("A field shown by a fact: no tool offers it, and the schema dialect has it."),
        SCH.sensitive to notTaught("The platform's: a secret, such as a password, which no client's form collects."),
        SCH.errors to notTaught("A field's own failure messages: no tool offers them, and the schema dialect has them."),
        SCH.presentation to notTaught("How a read-only page draws the platform's values; a client's trait does not set it."),
        SCH.layout to by(ATOOL.setShownFields, "A form's layout, which the Design View tools write."),
        SCH.merge to by(ATOOL.narrowTrait, "How a client's narrowing of a platform trait merges with it."),
        SCH.extends to by(ATOOL.narrowTrait, "The platform trait a client's narrowing extends."),
        SCH.appliesTo to by(ATOOL.designTrait, "The kinds of form a trait applies to."),
    )

    val slots: Map<String, Reach> = mapOf(
        CCT.clientDef to by(ATOOL.setClientDefinition, "The client's definition."),
        CCT.customerDef to notTaught("The platform's: only the hub client declares customers, and no client changes them."),
        CCT.traitDef to by(ATOOL.designTrait, "A trait of the client's own."),
        CCT.usageDef to by(ATOOL.saveFormsListColumn, "A column of the forms list."),
        CCT.reportDef to by(ATOOL.createReport, "A report."),
        CCT.schemaDef to by(ATOOL.patchConfig, "A type several traits share, which no editor makes."),
        CCT.fragmentDef to by(ATOOL.setCopy, "The client's wording."),
        CCT.uiBlockDef to by(ATOOL.setMenuItem, "The menu and Home's cards."),
        CCT.cfactDef to by(ATOOL.patchConfig, "A fact the client's expressions name, which no editor declares."),
        CCT.fileRefDef to notTaught("A file ref names bytes a person uploads: no endpoint takes them as data."),
        CCT.listingDef to by(ATOOL.setFormsListLayout, "How the forms list is drawn as a whole."),
    )

    // The prompt, and the guide's rows: what a change is, for each way to make one.
    val taught = AssistantPrompt.systemFor("acme", "The client 'acme' is configured.").single()[MSG.text].toString() +
        changeGroups.flatMap { it.ways }.joinToString("\n", prefix = "\n") { it.change }

    // Named as itself, not as the start of a longer name: `g-options` is not named by `g-optionsSource`.
    fun isNamed(name: String) = Regex(Regex.escape(name) + "(?![A-Za-z])").containsMatchIn(taught)

    fun judge(kind: String, name: String, reach: Reach?, where: String) {
        withClue("$kind $name: name it in the assistant's prompt (AssistantPrompt) or a Making changes row, or add it to $where") {
            (isNamed(name) || reach != null) shouldBe true
        }
        reach?.tool?.let { tool ->
            withClue("$kind $name (${reach.why}): the assistant has no tool $tool") { AssistantTools.byName.keys shouldContain tool }
        }
    }

    "every g- keyword is taught, written by a tool, or not taught with why" {
        for (keyword in SchGKeywords.keywords) judge("Keyword", keyword, keywords[keyword], "this test's keywords")
        for (keyword in keywords.keys) withClue("Keyword $keyword is not one") { SchGKeywords.keywords shouldContain keyword }
    }

    "every configuration slot is taught, written by a tool, or not taught with why" {
        for (slot in CCT.slots) judge("Slot", slot, slots[slot], "this test's slots")
        for (slot in slots.keys) withClue("Slot $slot is not one") { CCT.slots shouldContain slot }
    }
})
