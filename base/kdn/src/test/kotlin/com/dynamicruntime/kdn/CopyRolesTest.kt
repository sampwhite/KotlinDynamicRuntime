package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.naming.clientNamespace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * A client's copy is held to the declared roles (issue #1562): a misspelled role, or one written on what it does not
 * apply to, is refused with its configuration, where it is written, rather than rendering as nothing.
 */
class CopyRolesTest : StringSpec({

    val cxt = TestInstances.default("copyRoles")

    "a client's copy writing an undeclared or misplaced role is refused with its configuration" {
        val client = "copyroles1562"
        fun write(note: String) {
            val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                fragmentOverlay(HFRAG.home) { namespace(HFRAG.homeNs) { key("note", note) } }
            }
            GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("copyRoles", client), config)
            GedraConfigReload.reloadClient(cxt, client)
        }
        write("Write to [ops@acme.test]{.value}.")
        shouldThrow<Exception> { write("Write to [ops@acme.test]{.vaule}.") }.message.orEmpty() shouldContain
            "The role '.vaule' is not one the renderer knows"
        shouldThrow<Exception> { write("[Beside]{.float-left} the text.") }.message.orEmpty() shouldContain
            "written on a span, but it applies only to an image"
    }
})
