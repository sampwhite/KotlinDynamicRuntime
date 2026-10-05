package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.parseSchemaTypes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.kotest.matchers.string.shouldContain

/**
 * A workflow's alterations of types (issue #984): how a Design View edit rewrites a definition, that the definition
 * carries its alterations through its JSON form, that a workflow may alter only a type's field layout, and which
 * schema store a workflow's pages are drawn from.
 */
class WorkflowTypeAlterationsTest : StringSpec({
    val typeName = "client.acme.Request"
    val base = mapOf(WFD.workflowId to "request", WFD.entry to "creation")
    val inherited = mapOf(SL.field to "title", SL.label to "Title")

    fun entries(def: Map<String, Any?>): Any? =
        ((def[WFD.types] as Map<*, *>)[typeName] as Map<*, *>)[SCH.layout].let { (it as Map<*, *>)[SL.schemaFields] }

    "setting an entry adds it under the type's layout, and records the inherited entry as its basis" {
        val out = withLayoutEntry(base, typeName, "title", mapOf(SL.label to "Name it"), inherited)
        entries(out) shouldBe listOf(mapOf(SL.label to "Name it", SL.field to "title"))
        out[WFD.typeBasis] shouldBe mapOf(typeName to mapOf("title" to inherited))
        out[WFD.workflowId] shouldBe "request"
    }

    "setting it again replaces it; another field sits beside it" {
        val once = withLayoutEntry(base, typeName, "title", mapOf(SL.label to "Name it"), inherited)
        val twice = withLayoutEntry(once, typeName, "title", mapOf(SL.label to "Call it"), inherited)
        val both = withLayoutEntry(twice, typeName, "venue", mapOf(SL.label to "Where"), null)
        entries(both) shouldBe listOf(
            mapOf(SL.label to "Call it", SL.field to "title"),
            mapOf(SL.label to "Where", SL.field to "venue"),
        )
        // No inherited entry is recorded as an empty basis: there was none, and one appearing later is a change.
        (both[WFD.typeBasis] as Map<*, *>)[typeName] shouldBe mapOf("title" to inherited, "venue" to emptyMap<String, Any?>())
    }

    "removing the last entry leaves no trace of the alteration or its basis" {
        val set = withLayoutEntry(base, typeName, "title", mapOf(SL.label to "Name it"), inherited)
        withLayoutEntry(set, typeName, "title", null, inherited) shouldBe base
    }

    "a definition carries its alterations and their basis through its JSON form" {
        val raw = WfDefBuilder("request", WfEntry.creation).apply {
            task("ask", "Ask") {
                trait("note")
                save("make", "Make")
            }
            alterType(typeName, mapOf(SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "title", SL.label to "Name it")))))
        }.build()
        val def = parseWfDef(LiteCxt(), withLayoutEntry(raw, typeName, "venue", mapOf(SL.label to "Where"), null))
        def.typeAlterations.keys shouldBe setOf(typeName)
        def.typeBasis[typeName] shouldBe mapOf("venue" to emptyMap<String, Any?>())
        parseWfDef(LiteCxt(), def.toJsonMap()).toJsonMap() shouldBe def.toJsonMap()
    }

    "a workflow may alter only a type's field layout" {
        val raw = WfDefBuilder("request", WfEntry.creation).apply {
            task("ask", "Ask") {
                trait("note")
                save("make", "Make")
            }
            alterType(typeName, mapOf(SCH.properties to mapOf("title" to emptyMap<String, Any?>())))
        }.build()
        shouldThrow<KdrException> { parseWfDef(LiteCxt(), raw) }.fullMessage() shouldContain "may alter only a type's 'g-layout'"
    }

    "a workflow that alters nothing shares the client's store; one that does builds its own, once per client store" {
        val defs = mapOf(
            typeName to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("title" to mapOf(SCH.type to SCT.string)),
                SCH.layout to mapOf(SL.schemaFields to listOf(inherited)),
            ),
        )
        fun clientStore() = KdrSchemaStore(types = parseSchemaTypes(defs), defs = defs)
        val raw = WfDefBuilder("request", WfEntry.creation).apply {
            task("ask", "Ask") {
                trait("note")
                save("make", "Make")
            }
        }.build()
        fun declared(def: Map<String, Any?>) =
            WfDeclared(gedraConfig(LiteCxt(), "c", "client.acme", "acme") {}, parseWfDef(LiteCxt(), def))

        val store = clientStore()
        workflowSchemaStore(store, declared(raw)) shouldBeSameInstanceAs store

        val altered = declared(withLayoutEntry(raw, typeName, "title", mapOf(SL.label to "Name it"), inherited))
        val own = workflowSchemaStore(store, altered)
        own shouldNotBeSameInstanceAs store
        own.layouts[typeName]?.fieldFor("title")?.label shouldBe "Name it"
        store.layouts[typeName]?.fieldFor("title")?.label shouldBe "Title"
        // Built once, and rebuilt when the client reloads -- a reload builds a new client store.
        workflowSchemaStore(store, altered) shouldBeSameInstanceAs own
        workflowSchemaStore(clientStore(), altered) shouldNotBeSameInstanceAs own
    }
})
