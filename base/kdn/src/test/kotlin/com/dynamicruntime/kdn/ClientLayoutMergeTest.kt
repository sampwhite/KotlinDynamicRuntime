package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's alteration of a type's field layout merges with the inherited layout **by field** (issue #985): it
 * states only the entries it changes, and everything else -- the other fields' entries, the form strings -- is the
 * global layout's. Written as stored configuration and reloaded live, the way a client edits its own copy.
 */
class ClientLayoutMergeTest : StringSpec({
    // Its own instance (issue #1075): LayoutMergeFixture, a fixture component no shared entry has.
    val cxt = Startup.mkTestBootCxt(
        "layoutMerge985", "layoutMerge985", mapOf(LayoutMergeFixture.loadFlag.name to "true"), listOf(LayoutMergeFixture()),
    )

    /** Writes a client of its own whose config alters the fixture's types as [build] says, and reloads it. */
    fun alter(client: String, build: GedraConfigBuilder.() -> Unit) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            build()
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun layoutFor(client: String, type: String): SchLayout? = SchemaService.get(cxt).storeFor(client).layouts[type]

    "a client rewording one field keeps the other entries and the form strings" {
        alter("reword985") {
            type(LayoutMergeFixture.card) { layout { field("b", label = "Bee") } }
        }
        val merged = layoutFor("reword985", LayoutMergeFixture.card).shouldNotBeNull()
        merged.fieldFor("a")?.label shouldBe "A"
        merged.fieldFor("b")?.label shouldBe "Bee"
        merged.strings[LAYSTR.formErrorHint] shouldBe "Fix it."
        // Global's own is untouched.
        layoutFor(GID.globalClient, LayoutMergeFixture.card)?.fieldFor("b")?.label shouldBe "B"
        // The alteration said nothing of the properties, so the type keeps all three.
        SchemaService.get(cxt).storeFor("reword985").types.getValue(LayoutMergeFixture.card).properties.keys shouldBe
            setOf("a", "b", "c")
    }

    "an entry for a field the inherited layout does not list is added where the layout only annotates" {
        alter("annotate985") {
            type(LayoutMergeFixture.card) { layout { field("c", label = "Cee") } }
        }
        val merged = layoutFor("annotate985", LayoutMergeFixture.card).shouldNotBeNull()
        merged.fields.map { it.field } shouldBe listOf("a", "b", "c")
    }

    "where the inherited layout's order matters, such an entry is refused -- outside production, at the reload" {
        val message = shouldThrow<KdrException> {
            alter("ordered985") {
                type(LayoutMergeFixture.ordered) { layout { field("c", label = "Cee") } }
            }
        }.fullMessage()
        message shouldContain "schemaFields[c]"
        message shouldContain "matches no entry"
    }

    "an alteration setting reorder restates the list: its order, and a field-only entry keeps the inherited copy" {
        alter("restate985") {
            type(LayoutMergeFixture.ordered) {
                layout(mode = SchLayoutMode.reorder) {
                    field("c", label = "Cee")
                    field("a")
                    field("b", label = "Bee")
                }
            }
        }
        val merged = layoutFor("restate985", LayoutMergeFixture.ordered).shouldNotBeNull()
        merged.fields.map { it.field } shouldBe listOf("c", "a", "b")
        merged.fieldFor("a")?.label shouldBe "A"
        merged.fieldFor("b")?.label shouldBe "Bee"
    }

    "an alteration may merge its properties instead of restating them, removing one with null" {
        alter("merge985") {
            type(LayoutMergeFixture.card) {
                data[SCH.merge] = mapOf(SCH.properties to MCH.merge)
                // Insertion-ordered, as parsed JSON always is: the JSON writer keeps a null only in such a map.
                data[SCH.properties] = linkedMapOf("b" to null)
            }
        }
        SchemaService.get(cxt).storeFor("merge985").types.getValue(LayoutMergeFixture.card).properties.keys shouldBe
            setOf("a", "c")
    }

    "a merge directive the type does not offer refuses the alteration -- outside production, at the reload" {
        val message = shouldThrow<KdrException> {
            alter("badMerge985") {
                type(LayoutMergeFixture.card) { data[SCH.merge] = mapOf(SCH.properties to "shuffle") }
            }
        }.fullMessage()
        message shouldContain "with a merge it cannot apply"
        message shouldContain "In 'g-merge', 'properties' merges as one of"
    }

    "g-merge on a client's own type does nothing, so it is refused -- outside production, at the reload" {
        val message = shouldThrow<KdrException> {
            alter("ownMerge985") {
                type("Own") {
                    type = SCT.kObject
                    data[SCH.merge] = mapOf(SCH.properties to MCH.merge)
                    property("x", "A value.")
                }
            }
        }.fullMessage()
        message shouldContain "would do nothing"
    }

    "a client that narrows the type and rewords a field keeps its wording; entries for dropped fields fall away" {
        alter("narrowReword985") {
            type(LayoutMergeFixture.card) {
                type = SCT.kObject
                property("a", "First.")
                property("c", "Third.")
                layout { field("a", label = "Ay") }
            }
        }
        val merged = layoutFor("narrowReword985", LayoutMergeFixture.card).shouldNotBeNull()
        merged.fields.map { it.field to it.label } shouldBe listOf("a" to "Ay")
    }

    "a null layout drops the inherited one" {
        alter("bare985") {
            type(LayoutMergeFixture.card) { data[SCH.layout] = null }
        }
        layoutFor("bare985", LayoutMergeFixture.card).shouldBeNull()
    }
})

/** Global types with field layouts for clients to alter: one that only annotates, one whose order matters. */
class LayoutMergeFixture : ComponentDefinition {
    override val providerName: String = "layoutMergeFixture"

    /** The fixture's own owner root (issue #950). */
    override val ownerRoot: String = baseNamespace

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "layoutMergeBase", baseNamespace, GID.globalClient) {
            type("Card") {
                type = SCT.kObject
                property("a", "First.")
                property("b", "Second.")
                property("c", "Third.")
                layout {
                    field("a", label = "A")
                    field("b", label = "B")
                    string(LAYSTR.formErrorHint, "Fix it.")
                }
            }
            type("Ordered") {
                type = SCT.kObject
                property("a", "First.")
                property("b", "Second.")
                property("c", "Third.")
                layout(mode = SchLayoutMode.reorder) {
                    field("b", label = "B")
                    field("a", label = "A")
                }
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_LAYOUT_MERGE_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads this fixture component regardless of environment.",
        )
        const val baseNamespace = "layoutmerge"
        const val card = "$baseNamespace.Card"
        const val ordered = "$baseNamespace.Ordered"
    }
}
