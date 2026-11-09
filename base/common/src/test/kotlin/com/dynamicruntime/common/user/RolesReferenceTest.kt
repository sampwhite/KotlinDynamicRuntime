package com.dynamicruntime.common.user

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.http.request.SectionAccess
import com.dynamicruntime.common.startup.SS
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The roles and access reference (issue #1610) says something for every role and every section the gate knows: one
 * added without a meaning fails here rather than publishing a blank row.
 *
 * Theme: #DOCDR.
 */
class RolesReferenceTest : StringSpec({
    "every role and every section has a meaning" {
        ROLE.meanings.keys shouldBe setOf(ROLE.user, ROLE.operator, ROLE.admin, ROLE.allClients, ROLE.editor)
        ROLE.meanings.keys shouldContainAll RoleLadder.ordered + RoleLadder.impliedBy.keys
        SectionAccess.meanings.keys shouldBe SectionAccess.rules.keys
        (ROLE.meanings.values + SectionAccess.meanings.values).filter { it.isBlank() }.shouldBeEmpty()
    }

    "the page lists every role, section, and sandbox rule, and how to ask why" {
        val page = rolesAndAccessMarkdown()
        for (name in ROLE.meanings.keys + SectionAccess.rules.keys) {
            page shouldContain "| `$name` |"
        }
        for (rule in SandboxAccess.Withheld.entries) {
            page shouldContain "| `${rule.rule.substringBefore(':')}` |"
        }
        page shouldContain "_debug=${SS.explainAccess.name}"
    }
})
