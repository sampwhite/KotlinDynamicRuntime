package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

/**
 * A client's config may `$ref` global types and its own, and never another client's -- by construction rather
 * than by a check: a client's own types go into that client's overlays, never the global defs, and a client's
 * schema is the global defs plus its own overlays. So a reference to another client's type has nothing to
 * resolve against, and the write's trial refuses it.
 */
class ClientRefBoundaryTest : StringSpec({

    val cxt = TestInstances.default("refBoundary")
    val owner = "refboundaryowner"
    val other = "refboundaryother"

    fun store(client: String, build: GedraConfigBuilder.() -> Unit) {
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            build()
        }
        val writer: KdrCxt = cxt.mkSubContext("refBoundary", client)
        GedraConfigService.get(cxt).writeConfig(writer, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    // The owner defines a type of its own.
    store(owner) {
        type("Secret") {
            type = SCT.kObject
            property("code", "A code only this client knows about.")
        }
    }

    "a client's config may reference a global type" {
        store(other) {
            trait("GlobalRefEntry", "globalRef", setOf(GedraDataType.formDoc), "Refers to a global type.") {
                property("info", "A client's info.") { ref(CLD.infoTypeQualified) }
            }
        }
    }

    "a client's config referencing another client's type is refused at write" {
        val message = shouldThrow<KdrException> {
            store(other) {
                trait("StealEntry", "steal", setOf(GedraDataType.formDoc), "Refers to another client's type.") {
                    property("secret", "The other client's type.") { ref("${clientNamespace(owner)}.Secret") }
                }
            }
        }.message.shouldNotBeNull()
        message shouldContain "${clientNamespace(owner)}.Secret"
    }
})
