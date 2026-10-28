package com.dynamicruntime.common.gedra

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The portable form of a client's configuration (issue #1435): which values are the client's own names, and so
 * rewritten, and which are not. In `commonTest`, so it holds on the JVM and under Kotlin/JS alike.
 */
class PortableConfigTest {
    @Test
    fun theClientsOwnNamesAreRewrittenWholeAndNothingElse() {
        val stored = mapOf(
            "typeName" to "client.acme.MemoEntry",
            "namespace" to "client.acme",
            "deeper" to "client.acme.forms.Memo",
            "\$ref" to "#/\$defs/client.acme.Address",
            "types" to mapOf("client.acme.Memo" to mapOf("g-layout" to "x")),
            // Not the client's own: another client's namespace, one that only starts like it, a global name, and text.
            "template" to "client.acmetemplate.Memo",
            "other" to "client.globex.Memo",
            "global" to "#/\$defs/kdr.core.Name",
            "description" to "Copied from client.acme.Memo, which it extends.",
            "list" to listOf("client.acme.A", 3L, true, null),
        )
        val portable = toPortable(stored, "acme")
        assertEquals(
            mapOf(
                "typeName" to "client.@self.MemoEntry",
                "namespace" to "client.@self",
                "deeper" to "client.@self.forms.Memo",
                "\$ref" to "#/\$defs/client.@self.Address",
                "types" to mapOf("client.@self.Memo" to mapOf("g-layout" to "x")),
                "template" to "client.acmetemplate.Memo",
                "other" to "client.globex.Memo",
                "global" to "#/\$defs/kdr.core.Name",
                "description" to "Copied from client.acme.Memo, which it extends.",
                "list" to listOf("client.@self.A", 3L, true, null),
            ),
            portable,
        )
        // And back, for the client it came from or any other.
        assertEquals(stored, fromPortable(portable, "acme"))
        assertEquals("client.globex.MemoEntry", fromPortable("client.@self.MemoEntry", "globex"))
    }

    @Test
    fun aSandboxWritesAndReadsItsParentsNamespace() {
        assertEquals("client.@self.Memo", toPortable("client.acme.Memo", "acme:sandbox"))
        assertEquals("client.acme.Memo", fromPortable("client.@self.Memo", "acme:sandbox"))
    }

    @Test
    fun theDefinitionAndCustomerSlotsAreKeptAsTheyAre() {
        val slots = mapOf(
            CCT.clientDef to listOf(mapOf("clientId" to "acme", "note" to "client.acme")),
            CCT.traitDef to listOf(mapOf("typeName" to "client.acme.MemoEntry")),
        )
        val portable = slotsToPortable(slots, "acme")
        assertEquals(slots[CCT.clientDef], portable[CCT.clientDef])
        assertEquals("client.@self.MemoEntry", portable.getValue(CCT.traitDef).single()["typeName"])
        assertEquals(slots, slotsFromPortable(portable, "acme"))
    }
}
