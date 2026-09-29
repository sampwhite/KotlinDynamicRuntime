package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.mergeFragmentLayers
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.mergeUiBlock
import com.dynamicruntime.common.uiblock.uiBlock
import com.dynamicruntime.common.uiblock.uiBlockOverlay
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * What a client's own configuration overrides (issue #916), computed over hand-built layers: which keys and items
 * are the client's, what each replaces, and which config -- source or stored -- set it. The component's overlay is
 * here to show it counts as what everybody gets, never as the client's change.
 */
class ClientOverridesTest : StringSpec({

    fun layer(
        isOverlay: Boolean = true,
        client: String? = null,
        configName: String? = null,
        stored: Boolean = false,
        content: Map<String, Map<String, String>>,
    ) = FragmentSource("home", isOverlay, client, "test", configName = configName, stored = stored) { content }

    val base = layer(isOverlay = false, content = mapOf("home" to mapOf("brand" to "KDR", "title" to "Welcome", "intro" to "Hi")))
    val component = layer(content = mapOf("home" to mapOf("title" to "Component welcome")))
    val acmeSource = layer(
        client = "acme", configName = "main",
        content = mapOf("home" to mapOf("brand" to "ACME KDR", "title" to "Acme welcome", "renamed" to "Stale")),
    )
    val acmeStored = layer(client = "acme", configName = "edits", stored = true, content = mapOf("home" to mapOf("brand" to "Acme Co")))
    val fragments = listOf(base, component, acmeStored, acmeSource)

    fun copyRows(client: String) =
        copyOverrides(fragments, client) { fileId, forClient -> mergeFragmentLayers(fileId, fragments, forClient) }
            .associateBy { "${it.namespace}.${it.key}" }

    "each key the client sets, with what everybody else reads and the config that won" {
        val rows = copyRows("acme")
        rows.keys shouldBe setOf("home.brand", "home.title", "home.renamed")

        // Stored beats source, and the source value is kept for a later "revert".
        val brand = rows.getValue("home.brand")
        brand.baseValue shouldBe "KDR"
        brand.value shouldBe "Acme Co"
        brand.configName shouldBe "edits"
        brand.stored shouldBe true
        brand.sourceValue shouldBe "ACME KDR"
        brand.orphan shouldBe false

        // The component's overlay is part of what everybody else reads.
        val title = rows.getValue("home.title")
        title.baseValue shouldBe "Component welcome"
        title.value shouldBe "Acme welcome"
        title.stored shouldBe false
        title.sourceValue shouldBe null

        // A key no base declares is flagged: it replaces nothing anybody reads.
        rows.getValue("home.renamed").orphan shouldBe true
        rows.getValue("home.renamed").baseValue shouldBe null
    }

    "another client overrides nothing, and the count matches the rows" {
        copyRows("globex").keys.shouldBeEmpty()
        countCopyOverrides(fragments, "acme") shouldBe 3
        countCopyOverrides(fragments, "globex") shouldBe 0
    }

    // --- UiBlocks -----------------------------------------------------------------------------------------

    val menu = uiBlock("menu", origin = "core", arrayKeys = mapOf("items" to "id")) {
        set("title", "Menu")
        items("items") {
            item { set("id", "home"); set("label", "Home") }
            item { set("id", "facts"); set("label", "Facts") }
        }
    }
    val acmeMenu = uiBlockOverlay("menu", origin = "acme main", client = "acme") {
        set("title", "Acme menu")
        items("items") {
            item { set("id", "home"); set("label", "Acme home") }
            item { set("id", "facts"); set(UIB.cfactExpression, CFACT.neverName) }
            item { set("id", "audits"); set("label", "Audits"); set(UIB.displayOrder, 150) }
        }
    }.let { UiBlockSource(it.blockId, true, it.client, it.origin, it.content, configName = "main") }
    val acmeMenuStored = uiBlockOverlay("menu", origin = "acme edits", client = "acme") {
        items("items") { item { set("id", "home"); set("label", "Start") } }
    }.let { UiBlockSource(it.blockId, true, it.client, it.origin, it.content, configName = "edits", stored = true) }
    val blocks = listOf(menu, acmeMenuStored, acmeMenu)

    fun blockRows(client: String) =
        blockOverrides(blocks, client) { blockId, forClient -> mergeUiBlock(blockId, blocks, forClient) }
            .associateBy { it.itemId ?: "(${it.path})" }

    "each item and object the client changes: renamed, hidden, added, and a field outside the list" {
        val rows = blockRows("acme")
        rows.keys shouldBe setOf("()", "home", "facts", "audits")

        val home = rows.getValue("home")
        home.path shouldBe "items"
        home.added shouldBe false
        home.hidden shouldBe false
        val label = home.fields.single()
        label.field shouldBe "label"
        label.baseValue shouldBe "Home"
        label.value shouldBe "Start"
        label.configName shouldBe "edits"
        label.stored shouldBe true

        rows.getValue("facts").hidden shouldBe true
        rows.getValue("facts").fields.single().baseValue shouldBe null

        val audits = rows.getValue("audits")
        audits.added shouldBe true
        audits.fields.associate { it.field to it.value } shouldBe mapOf("label" to "Audits", UIB.displayOrder to "150")

        val title = rows.getValue("()").fields.single()
        title.field shouldBe "title"
        title.baseValue shouldBe "Menu"
        title.value shouldBe "Acme menu"
    }

    "the block count matches the rows, and another client has none" {
        countBlockOverrides(blocks, "acme") shouldBe 4
        countBlockOverrides(blocks, "globex") shouldBe 0
        blockRows("globex").keys.shouldBeEmpty()
    }
})
